package me.manga.kira.backend.complaint.infrastructure.terminal

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.OwnerDeleteAllCapacityCharges
import me.manga.kira.backend.complaint.domain.OwnerDeleteCapacityCharges
import me.manga.kira.backend.complaint.domain.catalog.CatalogGetRequest
import me.manga.kira.backend.complaint.domain.catalog.CatalogListRequest
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPort
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackProtocol
import me.manga.kira.backend.complaint.domain.catalog.CatalogObjectMetadata
import me.manga.kira.backend.complaint.domain.catalog.CatalogVersionBody
import me.manga.kira.backend.complaint.domain.catalog.CatalogTestRunTerminalRecordV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalEpochSealV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalInstallationEntryV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalInstallationManifestV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalPurgeV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSealRoleV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSealSetV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSyntaxV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalInventoryWitnessV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationReadbackHttpPairV1
import me.manga.kira.backend.complaint.infrastructure.AdminDeleteRows
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalReadbackV4
import me.manga.kira.backend.complaint.infrastructure.catalog.withSignerRotationCleanup
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.S3CatalogReadbackAdapter
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.S3CatalogReadbackLimits
import me.manga.kira.backend.complaint.infrastructure.journal.CatalogOrdinaryInventoryEntryV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestOrdinaryInventoryReadbackV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestTerminalInventoryReadbackV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.JournalFetchedVersionV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.JournalS3HttpWireV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.TestOrdinaryInventoryS3ClientV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.journalMetadata
import me.manga.kira.backend.complaint.infrastructure.journal.aws.requireJournalVersion
import me.manga.kira.backend.security.EpochSealFramesV1
import me.manga.kira.backend.security.TestPostTerminalInventoryEntryV1
import me.manga.kira.backend.security.TestPostTerminalInventoryFoldV1
import me.manga.kira.backend.security.TestTerminalCodecKindV1
import me.manga.kira.backend.security.TestTerminalContentV1
import me.manga.kira.backend.security.TestTerminalFramesV1
import me.manga.kira.backend.security.TestTerminalInventoryEntryV1
import me.manga.kira.backend.security.TestTerminalJsonV1
import me.manga.kira.backend.security.TestTerminalRootsV1
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.http.SdkHttpClient
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID

/**
 * Native observations owned by one eraser, not a portable proof. The real full-chain dual read and
 * existing all-version readers own every native exchange. No Sign, PUT, file custody or caller
 * readback constructor is present. Historical terminal reads never require P/L or V21 to survive.
 */
internal class TestRunErasureEvidenceV1(private val original: TestRunErasureV1) : AutoCloseable {
    private val journal = original.routing.journalConfiguration
    private val maximumVersions = journal.declaration().limits.capacity.maximumRetainedVersions
    private val maximumBytes = journal.declaration().limits.capacity.maximumScanStagingBytes
    private val json = TestTerminalJsonV1(journal)
    private var round: ReadRound? = null
    private var observed: CatalogTestRunTerminalReadbackV4? = null
    private var checkedSnapshot: TestRunErasureRowsV1.Snapshot? = null
    private var checkedRecord: CatalogTestRunTerminalRecordV1? = null
    private var references: List<TestTerminalQuiescenceTargetV1>? = null
    private var closed = false
    private var complete = false
    private var ordinaryCount = 0L
    private var terminalCount = 0L
    private val ordinaryFacts = linkedMapOf<Pair<String, String>, OrdinaryFact>()
    private val documents = linkedMapOf<String, TestRunErasureDocumentV1>()
    private val repeatedDocuments = hashSetOf<String>()
    private var documentBytes = 0L
    private var repeatedBytes = 0L
    private var ordinaryPass = 0
    private var terminalPass = 0
    private var finalInstallations: Map<UUID, TestTerminalInstallationEntryV1>? = null
    private var finalOrdinary: List<CatalogOrdinaryInventoryEntryV1>? = null
    private var finalTerminal: List<TestPostTerminalInventoryEntryV1>? = null

    val record: CatalogTestRunTerminalRecordV1 get() = checkNotNull(checkedRecord)

    fun observe(snapshot: TestRunErasureRowsV1.Snapshot, primary: AwsSessionCredentials, replica: AwsSessionCredentials,
        factory: (() -> SdkHttpClient)?) {
        requireConnectionFree(); original.requireEvidence(this)
        requireErasure(!closed && round == null && observed == null)
        val reader = original.process.catalogReadback
        val selected = ReadRound(original.budget.capped(minOf(reader.totalAttemptMillis, 10_000L)), snapshot)
        round = selected // Before either actual client construction may fail or return late.
        val policy = reader.policyAt(original.sampleWallTime())
        val proof = withSignerRotationCleanup({
            selected.requireRunning()
            val configured = reader.sdkLimits
            val millis = selected.budget.remainingMillis(minOf(configured.requestTimeoutMillis, 10_000L))
            val limits = S3CatalogReadbackLimits(millis, minOf(configured.connectTimeoutMillis.toLong(), millis).toInt(),
                minOf(configured.readTimeoutMillis.toLong(), millis).toInt(), configured.maximumListBytes,
                configured.maximumErrorBytes, configured.maximumObjectBytes)
            val adapter = S3CatalogReadbackAdapter.openOwned(selected.construction, reader.currentBundleBytes(),
                reader.chainPolicy.trustBundlePolicy, primary, replica, limits,
                { selected.http.open(limits, factory) }, original.acquisition.nanoTime)
            selected.requireRunning()
            CatalogTestRunTerminalReadbackV4.verify(TimedReadback(adapter, selected), reader.initialBundleBytes(),
                reader.currentBundleBytes(), policy, snapshot.terminal.head, original.expectedDeclaration)
        }, selected::close)
        selected.requireRunning(); selected.requireCleanup()
        selected.requireFullLocalBinding()
        snapshot.requireNative(original, proof)
        checkedSnapshot = snapshot; observed = proof; checkedRecord = proof.chain.manifest.terminalRecord
        references = targets(record)
        requireErasure(checkNotNull(references).size.toLong() in 3..maximumVersions)
        original.requireEvidence(this)
    }

    /** Comparison only, locally callable in later SQL; actual full native cleanup has already ended. */
    fun requireCatalog(snapshot: TestRunErasureRowsV1.Snapshot) {
        requireErasure(!closed && observed != null && checkedRecord != null)
        checkNotNull(round).requireCleanup()
        checkNotNull(checkedSnapshot).requireImmutable(snapshot)
    }

    fun terminalTargets(): List<TestTerminalQuiescenceTargetV1> {
        original.requireEvidence(this); requireErasure(!closed && observed != null)
        return checkNotNull(references).toList()
    }

    fun ordinaryVersion(pass: Int, value: TestOrdinaryInventoryReadbackV1) {
        requireConnectionFree(); original.requireEvidence(this); value.requireErasure(original)
        requireErasure(!closed && pass in 1..2 && ordinaryPass == pass - 1)
        TestOrdinaryDrainPersistenceV1.FamilyFacts(original.routing, original.cutoff).requireEvent(value.event)
        val event = value.event
        val kind = event.comparison.eventKind.name
        val targets = event.complaintIds().size
        val owners = if (kind.startsWith("ADMIN_")) event.adminComparison.ownerInstallationIds().size else 1
        val promise = when (kind) {
            "OWNER_DELETE_ALL" -> OwnerDeleteAllCapacityCharges.RECOVERY
            "ADMIN_BATCH_DELETE" -> AdminDeleteRows.recovery(event)
            else -> OwnerDeleteCapacityCharges.RECOVERY
        }
        // Derive every retained-key alias from the actual native plaintext, but retain only bounded
        // scalars/hashes. This is comparison material, never a new event or positive producer seam.
        val canonical = event.canonicalBytes()
        val aliases = try {
            val objectValue = CanonicalJson.json.parseToJsonElement(canonical.toString(Charsets.UTF_8)).jsonObject
            original.routing.derive(event.comparison).candidates().map { route ->
                val bytes = CanonicalJson.canonicalize(JsonObject.serializer(), JsonObject(objectValue + ("eventId" to JsonPrimitive(route.eventId))))
                    .toByteArray(Charsets.UTF_8)
                try { OrdinaryAlias(route.eventId, route.objectKey, route.routingKeyId, Sha256.hex(bytes)) }
                finally { bytes.fill(0) }
            }
        } finally { canonical.fill(0) }
        requireErasure(aliases.size == 4 && aliases.map { it.id }.distinct().size == 4 && aliases.map { it.key }.distinct().size == 4)
        val fact = OrdinaryFact(CatalogOrdinaryInventoryEntryV1.from(value), targets,
            if (kind == "OWNER_DELETE_ALL") 100 else targets, owners, event.route.routingKeyId, promise, aliases)
        if (pass == 1) {
            requireErasure(ordinaryFacts.size.toLong() < maximumVersions && ordinaryFacts.put(fact.entry.locator, fact) == null)
        } else requireErasure(ordinaryFacts[fact.entry.locator] == fact)
        ordinaryCount = Math.addExact(ordinaryCount, 1L)
        requireErasure(ordinaryCount <= maximumVersions)
    }

    fun ordinaryComplete(pass: Int, values: List<CatalogOrdinaryInventoryEntryV1>, count: Long, bytes: Long) {
        original.requireEvidence(this)
        requireErasure(!closed && pass == ordinaryPass + 1 && pass in 1..2 && count == ordinaryCount && values.size.toLong() == count &&
            values.map { it.locator }.toSet() == ordinaryFacts.keys && values.fold(0L) { total, entry -> Math.addExact(total, entry.ciphertextBytes) } == bytes)
        val cut = record.progress.completedCuts()[0]
        val root = MessageDigest.getInstance("SHA-256")
        var framed = EpochSealFramesV1.update(root, listOf(EpochSealFramesV1.DOMAIN, "1", "manifest", original.writer,
            journal.ordinaryPrefix, "TEST", original.scope.toString(), "1", original.cutoff.toString(), count.toString()))
        values.forEach { framed = Math.addExact(framed, EpochSealFramesV1.update(root, it.fields())) }
        val hash = TestTerminalFramesV1.finish(root)
        requireErasure(framed == cut.framedByteCount && framed <= maximumBytes)
        requireWitness(cut.denial.firstInventory, count, bytes, hash)
        requireWitness(cut.denial.secondInventory, count, bytes, hash)
        if (pass == 1) finalOrdinary = values.toList() else requireErasure(finalOrdinary == values)
        ordinaryPass = pass; ordinaryCount = 0
    }

    fun terminalVersion(pass: Int, value: TestTerminalInventoryReadbackV1) {
        requireConnectionFree(); original.requireEvidence(this); value.requireErasure(original)
        requireErasure(!closed && ordinaryPass == 2 && pass in 1..2 && terminalPass == pass - 1)
        val target = checkNotNull(references).single { it.objectRef.objectKey == value.entry.objectRef.objectKey }
        requireErasure(value.entry.objectRef == target.objectRef && value.entry.kind == target.kind &&
            value.entry.epochStartInclusive == target.startEpoch && value.entry.epochEndInclusive == target.endEpoch)
        checkNotNull(checkedSnapshot).active?.requireNative(value.entry)
        checkNotNull(checkedSnapshot).recurrent?.records?.singleOrNull { it.reference.objectRef.objectKey == value.entry.objectRef.objectKey }
            ?.requireNative(value.entry)
        val doc = value.erasureDocument(original)
        doc.requireTarget(target, original)
        if (pass == 1) {
            requireErasure(doc.canonicalByteCount <= maximumBytes - documentBytes && documents.put(target.objectRef.objectKey, doc) == null)
            documentBytes += doc.canonicalByteCount
        } else {
            requireErasure(doc.canonicalByteCount <= maximumBytes - repeatedBytes && repeatedDocuments.add(target.objectRef.objectKey))
            checkNotNull(documents[target.objectRef.objectKey]).requireSame(doc)
            repeatedBytes += doc.canonicalByteCount
        }
        terminalCount = Math.addExact(terminalCount, 1L)
        requireErasure(terminalCount <= checkNotNull(references).size.toLong())
    }

    fun terminalComplete(pass: Int, values: List<TestPostTerminalInventoryEntryV1>, summary: TestPostTerminalInventoryFoldV1.Summary) {
        original.requireEvidence(this)
        requireErasure(!closed && ordinaryPass == 2 && pass == terminalPass + 1 && pass in 1..2 &&
            terminalCount == checkNotNull(references).size.toLong() && values.size.toLong() == terminalCount &&
            documents.keys == checkNotNull(references).map { it.objectRef.objectKey }.toSet())
        val cut = record.progress.completedCuts()[1]
        requireErasure(summary.versionCount == terminalCount && summary.framedByteCount == cut.framedByteCount &&
            summary.entryFramedByteCount in 1..maximumBytes)
        requireWitness(cut.denial.firstInventory, summary.versionCount, summary.ciphertextByteCount, summary.sha256)
        requireWitness(cut.denial.secondInventory, summary.versionCount, summary.ciphertextByteCount, summary.sha256)
        if (pass == 1) finalTerminal = values.toList()
        else requireErasure(finalTerminal == values && repeatedDocuments == documents.keys && repeatedBytes == documentBytes)
        terminalPass = pass; terminalCount = 0
    }

    /** Both complete native pairs, including all graphs/key leases, must be retired before this fold. */
    fun finish() {
        requireConnectionFree(); original.requireEvidence(this); original.requireRetiredInventories()
        requireErasure(!closed && !complete && ordinaryPass == 2 && terminalPass == 2)
        val roots = TestTerminalRootsV1(journal, original.installationLimit, 4096)
        val all = record.installationManifest.chunks.map { chunk ->
            val manifest = checkNotNull(documents[chunk.objectRef.objectKey]?.manifest)
            requireErasure(manifest.chunkIndex == chunk.chunkIndex && manifest.eventId == chunk.eventId &&
                manifest.installationCount == chunk.installationCount && manifest.retiredCount == chunk.retiredCount &&
                manifest.deletedCount == chunk.deletedCount && manifest.entriesSha256 == chunk.entriesSha256)
            manifest
        }
        val builder = roots.installations(original.runContext)
        val identities = linkedMapOf<UUID, TestTerminalInstallationEntryV1>()
        all.forEach { chunk -> chunk.entries().forEach { entry ->
            requireErasure(identities.size.toLong() < original.installationLimit && identities.put(UUID.fromString(entry.installationId), entry) == null)
            builder.firstPass(entry)
        } }
        builder.beginSecondPass()
        all.forEach { chunk -> chunk.entries().forEach(builder::secondPass) }
        val installations = builder.finish()
        val fold = roots.chunks(installations, original.epoch)
        all.forEachIndexed { index, manifest -> fold.add(manifest, record.installationManifest.chunks[index].objectRef) }
        requireErasure(fold.finish() == record.installationManifest.summary)
        val purge = checkNotNull(documents[record.purge.objectRef.objectKey]?.purge)
        val purgeBytes = json.encodePurge(purge)
        val recordedPurge = json.encodePurge(record.purge.document)
        try { requireErasure(purgeBytes.contentEquals(recordedPurge)) }
        finally { purgeBytes.fill(0); recordedPurge.fill(0) }
        val seals = record.sealSet.records()
        seals.forEach { reference ->
            val seal = checkNotNull(documents[reference.objectRef.objectKey]?.seal)
            requireErasure(seal.sealId == reference.sealId && seal.writerGeneration == reference.writerGeneration &&
                seal.epochStartInclusive == reference.epochStartInclusive && seal.epochEndInclusive == reference.epochEndInclusive &&
                seal.precedingSealSha256 == reference.precedingSealSha256)
            // A's initial seal can cover an empty earlier range. Never count later ordinary events in it.
            val entries = if (reference.role == TestTerminalSealRoleV1.ORDINARY)
                checkNotNull(finalOrdinary).filter { it.epoch in reference.epochStartInclusive..reference.epochEndInclusive }
                    .map { it.locator to it.fields() }
            else checkNotNull(references).filter { it.kind != TestTerminalCodecKindV1.EPOCH_SEAL }
                .map { (it.objectRef.objectKey to it.objectRef.objectVersion) to listOf(it.objectRef.objectKey, it.objectRef.objectVersion, it.objectRef.ciphertextSha256) }
            val hash = MessageDigest.getInstance("SHA-256")
            var bytes = EpochSealFramesV1.update(hash, listOf(EpochSealFramesV1.DOMAIN, "1", "manifest", original.writer,
                if (reference.role == TestTerminalSealRoleV1.ORDINARY) journal.ordinaryPrefix else journal.sealTerminalPrefix,
                "TEST", original.scope.toString(), reference.epochStartInclusive.toString(), reference.epochEndInclusive.toString(), entries.size.toString()))
            entries.sortedWith { a, b ->
                val key = TestTerminalFramesV1.compareUtf8(a.first.first, b.first.first)
                if (key != 0) key else TestTerminalFramesV1.compareUtf8(a.first.second, b.first.second)
            }.forEach {
                bytes = Math.addExact(bytes, EpochSealFramesV1.update(hash, it.second))
            }
            requireErasure(bytes <= maximumBytes && seal.eventCount == entries.size.toLong() && seal.eventManifestSha256 == TestTerminalFramesV1.finish(hash))
        }
        val preSeals = TestTerminalSealSetV1.create(original.runContext.dataScopeId, original.runContext.activationCatalogGeneration,
            original.runContext.activationCatalogSha256, seals.dropLast(1))
        requireErasure(roots.preTerminalSeals(preSeals) == purge.preTerminalSeals)
        val preterminal = checkNotNull(finalOrdinary).map { entry -> TestTerminalInventoryEntryV1(original.writer, entry.kind,
            entry.epoch, entry.epoch, me.manga.kira.backend.complaint.domain.terminal.TestTerminalObjectRefV1(
                entry.key, entry.version, entry.wireSha256, entry.canonicalSha256)) } +
            seals.dropLast(1).map { seal -> TestTerminalInventoryEntryV1(original.writer, "EPOCH_SEAL", seal.epochStartInclusive,
                seal.epochEndInclusive, seal.objectRef) }
        val sorted = preterminal.sortedWith { a, b ->
            val order = compareValuesBy(a, b, { it.epochStartInclusive }, { it.epochEndInclusive }, { it.objectKind }, { it.objectRef.objectKey })
            if (order != 0) order else TestTerminalFramesV1.compareUtf8(a.objectRef.objectVersion, b.objectRef.objectVersion)
        }
        val inventory = roots.preTerminalInventory(original.runContext, preSeals)
        sorted.forEach(inventory::firstPass); inventory.beginSecondPass(); sorted.forEach(inventory::secondPass)
        requireErasure(inventory.finish() == purge.preTerminalInventory)
        finalInstallations = identities.toMap(); complete = true
    }

    /** No native operation, reparse, callback or context switch occurs inside a later SQL holder. */
    fun requireComplete(snapshot: TestRunErasureRowsV1.Snapshot) {
        requireCatalog(snapshot)
        requireErasure(complete && ordinaryPass == 2 && terminalPass == 2 && finalInstallations != null)
    }
    fun installations(): Map<UUID, TestTerminalInstallationEntryV1> { requireErasure(complete && !closed); return checkNotNull(finalInstallations).toMap() }
    fun ordinary(key: String, version: String): OrdinaryFact { requireErasure(complete && !closed); return checkNotNull(ordinaryFacts[key to version]) }
    fun ordinaryFamily(id: String): List<OrdinaryFact> {
        requireErasure(complete && !closed)
        return ordinaryFacts.values.filter { fact -> fact.aliases.any { it.id == id } }.also { requireErasure(it.isNotEmpty()) }
    }
    fun terminal(key: String, version: String): TestPostTerminalInventoryEntryV1 {
        requireErasure(complete && !closed)
        return checkNotNull(finalTerminal).single { it.objectRef.objectKey == key && it.objectRef.objectVersion == version }
    }
    fun document(key: String): TestRunErasureDocumentV1 { requireErasure(complete && !closed); return checkNotNull(documents[key]) }

    override fun close() {
        closed = true
        round?.close()
        round?.requireCleanup()
    }
    override fun toString(): String = "TestRunErasureEvidenceV1(original-owned-native-closure,redacted)"

    internal data class OrdinaryFact(val entry: CatalogOrdinaryInventoryEntryV1, val targetCount: Int, val targetBound: Int,
        val ownerCount: Int, val routingKey: String, val recoveryPromise: ComplaintCapacityVector, val aliases: List<OrdinaryAlias>) {
        override fun toString(): String = "TestRunErasureOrdinaryFactV1(bounded-comparison,redacted)"
    }
    internal data class OrdinaryAlias(val id: String, val key: String, val routingKey: String, val canonicalSha256: String) {
        override fun toString(): String = "TestRunErasureOrdinaryAliasV1(native-derived-comparison,redacted)"
    }
    private fun targets(value: CatalogTestRunTerminalRecordV1): List<TestTerminalQuiescenceTargetV1> = buildList {
        value.installationManifest.chunks.forEach {
            add(TestTerminalQuiescenceTargetV1(TestTerminalCodecKindV1.INSTALLATION_MANIFEST, it.chunkIndex, it.eventId,
                value.closure.terminalEpoch, value.closure.terminalEpoch, it.objectRef))
        }
        add(TestTerminalQuiescenceTargetV1(TestTerminalCodecKindV1.TEST_RUN_PURGE, 0, value.purge.document.eventId,
            value.closure.terminalEpoch, value.closure.terminalEpoch, value.purge.objectRef))
        val seals = value.sealSet.records()
        requireErasure(seals.size in 2..16)
        val activeCount = seals.size - 2
        seals.forEachIndexed { index, it ->
            val active = index < activeCount
            val ordinal = if (active) index else index - activeCount
            add(TestTerminalQuiescenceTargetV1(TestTerminalCodecKindV1.EPOCH_SEAL, ordinal, it.sealId,
                it.epochStartInclusive, it.epochEndInclusive, it.objectRef,
                if (!active) TestTerminalQuiescenceSourceV1.V21_TERMINAL_INTENT else if (index == 0) TestTerminalQuiescenceSourceV1.V26_ACTIVE_SEAL
                    else TestTerminalQuiescenceSourceV1.V31_ACTIVE_RECURRENT_SEAL))
        }
    }.sortedBy { it.objectRef.objectKey }.also { requireErasure(it.map { ref -> ref.objectRef.objectKey }.distinct().size == it.size) }

    private inner class ReadRound(val budget: PersistenceTimeBudget, private val snapshot: TestRunErasureRowsV1.Snapshot) : AutoCloseable {
        val construction = S3CatalogReadbackAdapter.Construction()
        val http = CatalogSignerRotationReadbackHttpPairV1(original, budget)
        private var cleaned = false
        private var failure: Throwable? = null
        private val bound = hashSetOf<Pair<Long, String>>()
        fun requireRunning() { original.requireProviderRunning(); budget.remainingMillis(1) }
        fun requireCleanup() = requireErasure(cleaned && failure == null)
        fun bind(request: CatalogGetRequest, metadata: CatalogObjectMetadata, bytes: ByteArray) {
            requireRunning()
            val generation = request.key.removePrefix("complaints/catalog/v1/").removeSuffix(".json").toLong()
            requireErasure(generation in 1..snapshot.terminal.generation && request.key == CatalogReadbackProtocol.key(generation) &&
                metadata.requestBinding == request && request.location.role in setOf("PRIMARY", "REPLICA") &&
                bound.add(generation to request.location.role))
            snapshot.requireRaw(original, generation, bytes, metadata)
            requireRunning()
        }
        fun requireFullLocalBinding() {
            requireCleanup()
            requireErasure(bound.size.toLong() == Math.multiplyExact(2L, snapshot.terminal.generation))
        }
        override fun close() {
            runCatching { withSignerRotationCleanup(construction::close, http::close) }.exceptionOrNull()?.let {
                original.observeFailure(it); if (failure == null || it is Error) failure = it
            }
            failure?.let { throw it }; cleaned = true
        }
    }
    private inner class TimedReadback(private val actual: S3CatalogReadbackAdapter, private val round: ReadRound) : CatalogReadbackPort {
        override fun listVersions(request: CatalogListRequest) = checked { actual.listVersions(request) }
        override fun openVersion(request: CatalogGetRequest): CatalogVersionBody {
            round.requireRunning()
            val body = actual.openVersion(request)
            val failure = runCatching(round::requireRunning).exceptionOrNull()
            if (failure != null) return withSignerRotationCleanup({ throw failure }, body::close)
            return object : CatalogVersionBody {
                private var metadata: CatalogObjectMetadata? = null
                private var retained: ByteArray? = null
                private var count = 0
                private var eof = false
                private var closed = false
                override fun metadata(): CatalogObjectMetadata = checked { body.metadata() }.also { value ->
                    requireErasure(!closed && metadata == null && value.contentLength in 1..original.process.catalogReadback.chainPolicy.limits.maximumEnvelopeBytes.toLong())
                    metadata = value; retained = ByteArray(Math.toIntExact(value.contentLength))
                }
                override fun read(destination: ByteArray, offset: Int, length: Int): Int {
                    requireErasure(!closed && !eof && metadata != null)
                    val read = checked { body.read(destination, offset, length) }
                    if (read == -1) { requireErasure(count == checkNotNull(retained).size); eof = true }
                    else {
                        requireErasure(read in 1..length && read <= checkNotNull(retained).size - count)
                        destination.copyInto(checkNotNull(retained), count, offset, offset + read); count += read
                    }
                    return read
                }
                override fun close() {
                    if (closed) return
                    closed = true
                    try {
                        body.close() // Actual native body retirement precedes all local byte binding.
                        if (eof) round.bind(request, checkNotNull(metadata), checkNotNull(retained))
                    } finally { retained?.fill(0); retained = null }
                }
            }
        }
        private fun <T> checked(work: () -> T): T { round.requireRunning(); return work().also { round.requireRunning() } }
    }

    companion object {
        fun routingKey(target: TestTerminalQuiescenceTargetV1, journal: TestOwnerDeleteJournalConfigurationV1): String {
            val kind = when (target.kind) {
                TestTerminalCodecKindV1.EPOCH_SEAL -> "epoch-seal"
                TestTerminalCodecKindV1.INSTALLATION_MANIFEST -> "installation-manifest"
                TestTerminalCodecKindV1.TEST_RUN_PURGE -> "test-run-purge"
            }
            return TestTerminalSyntaxV1.terminalKey(target.objectRef.objectKey, journal.declaration().writer.generationId,
                journal.scope.id.toString(), target.endEpoch, kind).also { id -> requireErasure(journal.declaration().routing.keys.any { it.keyId == id }) }
        }

        /** Raw/SDK exact-reference equality only; the private reader supplies real exchange custody. */
        fun checkReferenced(target: TestTerminalQuiescenceTargetV1, listed: TestOrdinaryInventoryS3ClientV1.Entry,
            fetched: JournalFetchedVersionV1): RetentionFacts {
            val response = fetched.response; val raw = fetched.observed.response; val headers = raw.headers()
            requireErasure(listed.key == target.objectRef.objectKey && listed.version == target.objectRef.objectVersion &&
                raw.statusCode() == 200 && response.sdkHttpResponse().statusCode() == 200 &&
                requireJournalVersion(response.versionId()) == listed.version && JournalS3HttpWireV1.single(headers, "x-amz-version-id") == listed.version)
            requireErasure(response.contentLength() == listed.size && fetched.bytes.size.toLong() == listed.size &&
                fetched.observed.size == fetched.bytes.size && fetched.observed.wireSha256 == target.objectRef.ciphertextSha256 &&
                Sha256.hex(fetched.bytes) == target.objectRef.ciphertextSha256 && response.deleteMarker() != true &&
                response.contentRange() == null && response.contentEncoding() == null && response.expiration() == null)
            requireErasure(response.contentType() == JournalS3HttpWireV1.CONTENT_TYPE &&
                JournalS3HttpWireV1.single(headers, "Content-Type") == JournalS3HttpWireV1.CONTENT_TYPE)
            val metadata = JournalS3HttpWireV1.metadata(headers)
            val requestedText = checkNotNull(metadata["kira-journal-retain-until"])
            requireErasure(metadata == journalMetadata(target.id, target.objectRef.ciphertextSha256, requestedText) &&
                response.metadata() == metadata && (response.missingMeta() == null || response.missingMeta() == 0))
            val checksum = JournalS3HttpWireV1.single(headers, "x-amz-checksum-sha256")
            val digest = MessageDigest.getInstance("SHA-256").digest(fetched.bytes)
            try { requireErasure(checksum == Base64.getEncoder().encodeToString(digest) && response.checksumSHA256() == checksum &&
                JournalS3HttpWireV1.single(headers, "x-amz-checksum-type") in listOf(null, "FULL_OBJECT")) }
            finally { digest.fill(0) }
            requireErasure(response.objectLockModeAsString() == "COMPLIANCE" &&
                JournalS3HttpWireV1.single(headers, "x-amz-object-lock-mode") == "COMPLIANCE")
            val created = checkNotNull(response.lastModified()); val retained = checkNotNull(response.objectLockRetainUntilDate())
            val requested = Instant.parse(requestedText)
            requireErasure(created == listed.lastModified && Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(
                checkNotNull(JournalS3HttpWireV1.single(headers, "Last-Modified")))) == created &&
                Instant.parse(checkNotNull(JournalS3HttpWireV1.single(headers, "x-amz-object-lock-retain-until-date"))) == retained &&
                requested.toString() == requestedText)
            return RetentionFacts(created, retained, requested)
        }
        private fun requireWitness(value: TestTerminalInventoryWitnessV1, count: Long, bytes: Long, hash: String) =
            requireErasure(value.versionCount == count && value.byteCount == bytes && value.sha256 == hash)
    }
    internal data class RetentionFacts(val createdAt: Instant, val retainedUntil: Instant, val requestedUntil: Instant)
}

/** Closed parsed comparison fields only. Even decoded content is not an original or erasure right. */
internal class TestRunErasureDocumentV1 private constructor(
    val manifest: TestTerminalInstallationManifestV1?, val seal: TestTerminalEpochSealV1?, val purge: TestTerminalPurgeV1?,
    val canonicalSha256: String, val canonicalByteCount: Long,
) {
    fun requireTarget(target: TestTerminalQuiescenceTargetV1, original: TestRunErasureV1) {
        requireErasure(canonicalSha256 == target.objectRef.canonicalSha256)
        when (target.kind) {
            TestTerminalCodecKindV1.INSTALLATION_MANIFEST -> requireErasure(manifest != null && seal == null && purge == null &&
                manifest.eventId == target.id && manifest.context().run == original.runContext && manifest.publicationEpoch == original.epoch)
            TestTerminalCodecKindV1.TEST_RUN_PURGE -> requireErasure(purge != null && seal == null && manifest == null &&
                purge.eventId == target.id && purge.context().run == original.runContext && purge.publicationEpoch == original.epoch)
            TestTerminalCodecKindV1.EPOCH_SEAL -> requireErasure(seal != null && manifest == null && purge == null &&
                seal.sealId == target.id && seal.dataScopeId == original.scope.toString() && seal.writerGeneration == original.writer &&
                seal.epochStartInclusive == target.startEpoch && seal.epochEndInclusive == target.endEpoch)
        }
    }
    fun requireSame(other: TestRunErasureDocumentV1) {
        requireErasure(canonicalSha256 == other.canonicalSha256 && canonicalByteCount == other.canonicalByteCount &&
            seal == other.seal && manifest?.entries() == other.manifest?.entries())
    }
    override fun toString(): String = "TestRunErasureDocumentV1(closed-bounded-comparison,redacted)"
    companion object {
        fun decode(content: TestTerminalContentV1, json: TestTerminalJsonV1): TestRunErasureDocumentV1 {
            val bytes = content.canonicalBytes()
            return try {
                requireErasure(bytes.size == content.byteCount && Sha256.hex(bytes) == content.canonicalSha256)
                when (content.kind) {
                    TestTerminalCodecKindV1.INSTALLATION_MANIFEST -> TestRunErasureDocumentV1(json.installationManifest(bytes), null, null, content.canonicalSha256, bytes.size.toLong())
                    TestTerminalCodecKindV1.EPOCH_SEAL -> TestRunErasureDocumentV1(null, json.epochSeal(bytes), null, content.canonicalSha256, bytes.size.toLong())
                    TestTerminalCodecKindV1.TEST_RUN_PURGE -> TestRunErasureDocumentV1(null, null, json.purge(bytes), content.canonicalSha256, bytes.size.toLong())
                }
            } finally { bytes.fill(0) }
        }
    }
}
