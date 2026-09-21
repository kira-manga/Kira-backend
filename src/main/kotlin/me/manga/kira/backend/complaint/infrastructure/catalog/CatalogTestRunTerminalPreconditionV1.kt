package me.manga.kira.backend.complaint.infrastructure.catalog

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableBindingV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableKindV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableRowV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableStateV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalEpochSealV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalInstallationReadV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalObjectRefV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalRunContextV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSealSetV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSyntaxV1
import me.manga.kira.backend.complaint.infrastructure.OwnerDeleteRows
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.infrastructure.journal.CatalogOrdinaryInventoryEntryV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestOrdinaryInventoryReadbackV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestInstallationManifestSourceV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestInstallationSourceSqlV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestInstallationSourceV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainRowsV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainPersistenceV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunPurgeOperationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestTerminalEpochSealManifestV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestTerminalEpochSealSqlV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestTerminalQuiescenceSqlV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestTerminalQuiescenceTargetV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestTerminalQuiescenceSourceV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestTerminalSqlRowV1
import me.manga.kira.backend.security.EpochSealFramesV1
import me.manga.kira.backend.security.ComplaintJournalDeletionKindV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalCodecV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalEventV1
import me.manga.kira.backend.security.TestTerminalCodecKindV1
import me.manga.kira.backend.security.TestTerminalFramesV1
import me.manga.kira.backend.security.TestTerminalInventoryEntryV1
import me.manga.kira.backend.security.TestTerminalJsonV1
import me.manga.kira.backend.security.TestTerminalRootsV1
import org.springframework.jdbc.core.JdbcTemplate
import java.security.MessageDigest
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/**
 * Detached scalar comparison owned by the exact released preflight, NOT a portable proof ticket.
 * The protected digest includes complete physical P/L, APPLIED, V21, optional V26/V29 identities
 * and both actual source passes. Run/counter/catalog transitions are separately compared.
 */
internal class CatalogTestRunTerminalPreconditionV1 private constructor(
    private val original: CatalogTestRunTerminalV1,
    private val operation: CatalogTestRunTerminalPreflightOperationV1,
    val physicalSha256: String,
    private val ordinary: List<CatalogTestRunTerminalAppliedV1>,
) {
    fun requireOwner(candidate: CatalogTestRunTerminalV1) {
        requireTestTerminalCatalog(candidate === original && operation.original === candidate)
        operation.requireReleased()
    }
    fun requireSame(other: CatalogTestRunTerminalPreconditionV1) {
        requireTestTerminalCatalog(original === other.original && physicalSha256 == other.physicalSha256 && ordinary == other.ordinary)
    }
    fun requireOrdinaryInventory(entries: List<CatalogOrdinaryInventoryEntryV1>) {
        requireOwner(original)
        requireTestTerminalCatalog(entries.size == ordinary.size)
        ordinary.indices.forEach { index -> ordinary[index].requireNative(entries[index]) }
    }
    override fun toString(): String = "CatalogTestRunTerminalPreconditionV1(original-released-comparison,not-authority)"
    companion object {
        internal fun fromOperation(operation: CatalogTestRunTerminalPreflightOperationV1, physicalSha256: String,
            entries: List<CatalogTestRunTerminalAppliedV1>): CatalogTestRunTerminalPreconditionV1 {
            operation.requireResultConstruction()
            return CatalogTestRunTerminalPreconditionV1(operation.original, operation, physicalSha256, entries.toList())
        }
    }
}

/** Fixed selector issued by the original; ALL three variants use the same full read-only lock path. */
internal enum class CatalogTestRunTerminalPreflightKindV1 { CURRENT, ORDINARY_VERSION, TERMINAL_ROW }

/**
 * M/RC/shared E -> global -> exact scope -> key-sorted P/L pairs -> sorted counters -> run ->
 * source/lineage reads. No catalog advisory, provider, paid staging, recycling, write or late lock.
 * Repeating this complete bounded preflight per native version is intentionally conservative;
 * the existing 2s phase ceiling still applies and large-run/deadline qualification is NOT claimed.
 */
internal class CatalogTestRunTerminalPreflightOperationV1 private constructor(
    private val phase: PersistencePhaseContext,
    private val jdbc: JdbcTemplate,
    internal val original: CatalogTestRunTerminalV1,
) {
    internal val path = PersistencePhasePath.COMPLAINT_TEST_RUN_TERMINAL_CATALOG_PREFLIGHT
    private val kind = original.preflightKind()
    private val input = original.preflightFrozen()
    private val expected = original.preflightSnapshot()
    private val record = input.manifest().terminalRecord
    private val journal = original.routing.journalConfiguration
    private val json = TestTerminalJsonV1(journal)
    private val maximumVersions = journal.declaration().limits.capacity.maximumRetainedVersions
    private val maximumBytes = journal.declaration().limits.capacity.maximumScanStagingBytes
    private var stage = Stage.NEW
    private var returnedRow: TestTerminalDurableRowV1? = null
    private var result: CatalogTestRunTerminalPreconditionV1? = null
    private var matchedNativePublication = false
    private var matchedNativeApplied = false
    private val publications = ArrayList<Publication>()
    private val pairs = ArrayList<String>()
    private val sidecars = ArrayList<String>()
    private var sourceHash: String? = null

    internal fun belongsTo(candidate: PersistencePhaseContext): Boolean = candidate === phase
    internal fun completedFor(candidate: PersistencePhaseContext): Boolean = belongsTo(candidate) && stage === Stage.COMPLETE
    internal fun requireReleased() { phase.testRunTerminalCatalogPreflight.requireCommitted(this); requireConnectionFree() }
    internal fun precondition(): CatalogTestRunTerminalPreconditionV1 { requireReleased(); return checkNotNull(result) }
    internal fun takeRow(): TestTerminalDurableRowV1 { requireReleased(); return checkNotNull(returnedRow).also { returnedRow = null } }
    internal fun discardRow() { returnedRow?.close(); returnedRow = null }

    private fun execute() {
        at(Stage.NEW); stage = Stage.CONTROLS
        requireControls(lock = true)
        stage = Stage.PAIRS
        readPairs(lock = true)
        stage = Stage.COUNTERS_REQUESTED
        val counters = JdbcComplaintCapacityStore(jdbc, original.process.consumers.capacityPolicy.digestBytes())
            .lockForTestRunTerminalCatalogPreflight(this)
        at(Stage.COUNTERS_LOCKING); expected.counters.requireSame(counters)
        stage = Stage.BODY
        val run = readRun(lock = true)
        expected.run.requireSame(run); run.requireFrozen(input)
        run.requirePayment(expected.terminal != null, expected.terminal?.projectedAt != null)
        requireActiveHistory(run)
        requireRelations(); requireCounts()
        val applied = readApplied()
        requirePrimaryMembership(applied)
        val source = readSource(run)
        requireSidecars(source, applied)
        requireRelations(); requireCounts()
        // No backwards lock acquisition on reread. The complete physical identities must persist.
        readPairs(lock = false)
        requireTestTerminalCatalog(applied == readApplied())
        requireSidecarIdentities()
        readRun(lock = false).requireSame(run)
        requireActiveHistory(run)
        requireControls(lock = false)
        requireTestTerminalCatalog(kind !== CatalogTestRunTerminalPreflightKindV1.ORDINARY_VERSION || matchedNativePublication && matchedNativeApplied)
        requireTestTerminalCatalog((kind === CatalogTestRunTerminalPreflightKindV1.TERMINAL_ROW) == (returnedRow != null))
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = append(digest, 0, listOf("kira-test-terminal-precondition-v1", input.scope.toString(), input.predecessorHash))
        pairs.forEach { bytes = append(digest, bytes, listOf("P/L", it)) }
        applied.forEach { bytes = append(digest, bytes, listOf("APPLIED", it.physicalSha256)) }
        sidecars.forEach { bytes = append(digest, bytes, listOf("V21", it)) }
        bytes = append(digest, bytes, listOf("INSTALLATIONS", checkNotNull(sourceHash)))
        expected.activeHistory.commitments().forEach { bytes = append(digest, bytes, it) }
        stage = Stage.RESULT
        result = CatalogTestRunTerminalPreconditionV1.fromOperation(this, TestTerminalFramesV1.finish(digest), applied)
        at(Stage.RESULT); stage = Stage.COMPLETE
    }

    private fun requireControls(lock: Boolean) {
        retained()
        for ((scope, expectedControl) in listOf(UUID(0L, 0L) to expected.global, input.scope to expected.scoped)) {
            val value = jdbc.query(if (lock) CatalogTestRunTerminalSqlV1.lockControl else CatalogTestRunTerminalSqlV1.readControl,
                { row, _ -> CatalogTestRunTerminalControlV1(row) }, *original.controlArguments(scope)).single()
            expectedControl.requireSame(value)
        }
        val ordinary = record.progress.completedCuts()[0]
        val terminal = record.progress.completedCuts()[1]
        expected.activeHistory.requireFrozen(input)
        val sql = if (input.hasActiveHistory) CatalogTestRunTerminalPreflightSqlV1.controlWithActiveHistory else CatalogTestRunTerminalPreflightSqlV1.control
        jdbc.query(sql, { row, _ ->
            requireTestTerminalCatalog(row.requiredTestActivationBoolean("valid") && row.requiredTestActivationBoolean("scan_requested") &&
                row.requiredTestActivationLong("publication_epoch") == Math.addExact(input.terminalEpoch, 1L) &&
                row.requiredTestActivationLong("rotation_sequence") == (if (input.hasActiveHistory) 2L else 1L) && row.getObject("rotation_id", UUID::class.java) != null &&
                row.requiredTestActivationLong("rotation_epoch_before") == input.ordinaryEpoch &&
                row.requiredTestActivationLong("rotation_capture_token") in 1..ordinary.fencingToken &&
                row.requiredTestActivationLong("lease_token") == terminal.fencingToken &&
                checkNotNull(row.getTimestamp("rotation_captured_at")).toInstant().epochSecond <= ordinary.denial.firstInventory.startedAtEpochSecond &&
                (if (input.hasActiveHistory) row.requiredTestActivationLong("seal_epoch") == 1L else row.getObject("seal_epoch") == null))
            original.requirePredecessorControl(this, row)
        }, input.scope).single()
        expected.activeHistory.requirePhysical(jdbc, original)
        retained()
    }

    private fun requireActiveHistory(run: CatalogTestRunTerminalRunV1) {
        retained()
        val current = CatalogTestRunTerminalActiveHistoryV1.read(jdbc, original, expected.activation)
        expected.activeHistory.requireSame(current); current.requireRun(run); current.requireFrozen(input)
    }

    private fun readPairs(lock: Boolean) {
        retained()
        var after: String? = null
        var index = 0
        val native = original.preflightOrdinaryReadback(this)
        while (true) {
            val page = jdbc.query(CatalogTestRunTerminalPreflightSqlV1.publicationPage,
                { row, _ -> checkNotNull(row.getString("object_key")) to checkNotNull(row.getString("event_id")) },
                input.scope, journal.ordinaryPrefix + "%", journal.sealTerminalPrefix + "%", after, after)
            if (page.isEmpty()) break
            page.forEach { (key, id) ->
                retained()
                requireTestTerminalCatalog(after?.let { it < key } != false && index.toLong() < Math.multiplyExact(2L, maximumVersions))
                val sampledAt = now() // No nested statement/result lifetime from inside a row mapper.
                val read = jdbc.query(if (lock) CatalogTestRunTerminalPreflightSqlV1.lockPublication else CatalogTestRunTerminalPreflightSqlV1.readPublication,
                    { row, _ -> publication(row, native, sampledAt) }, id).single()
                requireTestTerminalCatalog(read.facts.key == key && read.facts.id == id)
                val recovered = jdbc.query(if (lock) CatalogTestRunTerminalPreflightSqlV1.lockRecovery else CatalogTestRunTerminalPreflightSqlV1.readRecovery,
                    { row, _ -> recovery(row, read, sampledAt) }, id).single()
                val publication = read.facts.copy(convertedAt = recovered.at, appliedCharge = recovered.applied)
                val pair = Sha256.hex(TestTerminalFramesV1.bytes(listOf(publication.physicalSha256, recovered.physicalSha256)))
                if (lock) { publications.add(publication); pairs.add(pair) }
                else requireTestTerminalCatalog(index < publications.size && publications[index] == publication && pairs[index] == pair)
                after = key; index++
            }
        }
        requireTestTerminalCatalog(index == publications.size && publications.map { it.id }.distinct().size == index)
    }

    private fun publication(row: ResultSet, native: TestOrdinaryInventoryReadbackV1?, now: Instant): ReadPublication {
        requireTestTerminalCatalog(row.requiredTestActivationBoolean("valid_shape") && row.requiredTestActivationBoolean("test_only") &&
            row.getObject("data_scope_id", UUID::class.java) == input.scope && row.getObject("writer_generation", UUID::class.java) == input.writer &&
            row.getString("canonicalizer") == "kcj-1")
        val p = Publication(checkNotNull(row.getString("event_id")), checkNotNull(row.getString("object_key")),
            checkNotNull(row.getString("event_kind")), row.requiredTestActivationLong("journal_epoch"), row.getInt("target_count"),
            checkNotNull(row.getString("routing_key_id")), hash(row, "semantic_hash"), checkNotNull(row.getString("object_version")),
            hash(row, "ciphertext_hash"), checkNotNull(row.getTimestamp("created_at")).toInstant(),
            checkNotNull(row.getTimestamp("object_created_at")).toInstant(), checkNotNull(row.getTimestamp("retain_until")).toInstant(),
            checkNotNull(row.getTimestamp("verified_at")).toInstant(), row.getTimestamp("applied_at")?.toInstant(), hash(row, "physical_hash"),
            hash(row, "verification_hash"))
        requireTestTerminalCatalog(p.id.length == 43 && p.version != "null" && p.createdAt <= p.verifiedAt && p.objectCreatedAt <= p.verifiedAt &&
            p.verifiedAt <= now && p.retainUntil > now && p.retainUntil > p.verifiedAt && journal.declaration().routing.keys.any { it.keyId == p.routingKey })
        val terminal = p.kind in setOf("INSTALLATION_MANIFEST", "TEST_RUN_PURGE")
        if (terminal) {
            val target = input.targets.single { it.id == p.id && it.kind !== TestTerminalCodecKindV1.EPOCH_SEAL }
            requireTestTerminalCatalog(row.getString("state") == "VERIFIED" && p.appliedAt == null && p.epoch == input.terminalEpoch &&
                p.objectRef() == target.objectRef && p.createdAt >= expected.run.sealedAt &&
                p.count.toLong() == if (p.kind == "TEST_RUN_PURGE") 0L else record.installationManifest.chunks[target.ordinal].installationCount)
            original.requirePredecessorPublication(this, p.id, p.objectRef(), p.createdAt, p.objectCreatedAt, p.retainUntil, p.verifiedAt, p.verificationSha256)
        } else {
            requireTestTerminalCatalog(row.getString("state") == "APPLIED" && p.appliedAt != null && p.appliedAt >= p.verifiedAt && p.appliedAt <= now &&
                p.epoch in 1..input.ordinaryEpoch && p.key.startsWith(journal.ordinaryPrefix) && allowedKind(p.kind, p.count))
        }
        val bytes = checkNotNull(row.getBytes("event_bytes"))
        var event: TestOwnerDeleteJournalEventV1? = null
        var variants = emptyList<Variant>()
        try {
            requireTestTerminalCatalog(bytes.size in 1..65536 && Sha256.hex(bytes) == p.canonicalSha256)
            if (!terminal) {
                // Portless restoration is comparison data only. Every applicable alias/version must
                // still be returned by the genuine native reader before this original may proceed.
                val primary = restore(p.kind, bytes, p.routingKey)
                event = primary
                TestOrdinaryDrainPersistenceV1.FamilyFacts(original.routing, input.ordinaryEpoch).requireEvent(primary)
                requireTestTerminalCatalog(primary.route.eventId == p.id && primary.route.objectKey == p.key &&
                    primary.semanticSha256 == p.canonicalSha256 && primary.comparison.epoch == p.epoch &&
                    primary.comparison.eventKind.name == p.kind && primary.complaintIds().size == p.count)
                val canonical = CanonicalJson.json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as JsonObject
                variants = original.routing.derive(primary.comparison).candidates().map { route ->
                    val variantBytes = CanonicalJson.canonicalize(JsonObject.serializer(), JsonObject(canonical + ("eventId" to JsonPrimitive(route.eventId))))
                        .toByteArray(Charsets.UTF_8)
                    try {
                        val variant = restore(p.kind, variantBytes, route.routingKeyId)
                        requireTestTerminalCatalog(variant.route == route)
                        if (native?.event?.route?.eventId == route.eventId) {
                            requireTestTerminalCatalog(native.event.belongsTo(original.routing) && native.event.route == route)
                            val actual = native.event.canonicalBytes()
                            try { requireTestTerminalCatalog(actual.contentEquals(variantBytes)) } finally { actual.fill(0) }
                            if (route.eventId == p.id && native.versionId == p.version) requireTestTerminalCatalog(
                                native.wireSha256 == p.ciphertextSha256 && native.lastModified == p.objectCreatedAt && native.retainUntil == p.retainUntil)
                            matchedNativePublication = true
                        }
                        Variant(route.eventId, route.objectKey, route.routingKeyId, variant.semanticSha256)
                    } finally { variantBytes.fill(0) }
                }
                requireTestTerminalCatalog(variants.size == 4 && variants.map { it.id }.distinct().size == 4)
            }
        } finally { bytes.fill(0); row.getBytes("verification_bytes")?.fill(0) }
        return ReadPublication(p.copy(variants = variants), event)
    }

    private fun recovery(row: ResultSet, read: ReadPublication, now: Instant): Recovery {
        val p = read.facts
        requireTestTerminalCatalog(row.getString("event_id") == p.id && row.getString("publication_ref") == p.id &&
            row.getObject("data_scope_id", UUID::class.java) == input.scope && row.requiredTestActivationBoolean("test_only") &&
            row.requiredTestActivationBoolean("finite") && row.getInt("accounting_version") == 1 &&
            checkNotNull(row.getTimestamp("created_at")).toInstant() == p.createdAt)
        val reserved = TestOrdinaryDrainRowsV1.vector(row, "reserved_amounts")
        if (p.kind == "INSTALLATION_MANIFEST" || p.kind == "TEST_RUN_PURGE") {
            requireTestTerminalCatalog(row.getString("state") == "RESERVED" && row.getTimestamp("converted_at") == null &&
                row.getObject("converted_amounts") == null && reserved == if (p.kind == "TEST_RUN_PURGE") TestRunPurgeOperationV1.FUTURE else ComplaintCapacityVector.ZERO)
            return Recovery(hash(row, "physical_hash"), null, 0)
        }
        val recovered = TestOrdinaryDrainRowsV1.Recovery(row,
            TestOrdinaryDrainPersistenceV1.FamilyFacts(original.routing, input.ordinaryEpoch), p.id, p.kind, checkNotNull(read.event))
        requireTestTerminalCatalog(recovered.state == "CONVERTED" && recovered.promise == reserved &&
            recovered.lastAppliedAt >= p.verifiedAt && recovered.lastAppliedAt <= now)
        return Recovery(hash(row, "physical_hash"), recovered.lastAppliedAt, recovered.used[ComplaintCapacityCounter.JOURNAL_APPLIED])
    }

    private fun readApplied(): List<CatalogTestRunTerminalAppliedV1> {
        val all = ArrayList<CatalogTestRunTerminalAppliedV1>()
        val aliases = publications.flatMap { p -> p.variants.map { it.id to (p to it) } }
        requireTestTerminalCatalog(aliases.map { it.first }.distinct().size == aliases.size)
        val byId = aliases.toMap()
        var after: Pair<String, String>? = null
        val native = original.preflightOrdinaryReadback(this)
        while (true) {
            retained()
            val sampledAt = now()
            val page = jdbc.query(CatalogTestRunTerminalPreflightSqlV1.appliedPage, { row, _ ->
                val (p, variant) = checkNotNull(byId[row.getString("event_id")])
                requireTestTerminalCatalog(row.requiredTestActivationBoolean("test_only") && row.requiredTestActivationBoolean("finite") &&
                    row.getObject("data_scope_id", UUID::class.java) == input.scope && row.getObject("writer_generation", UUID::class.java) == input.writer &&
                    row.getString("event_kind") == p.kind && row.requiredTestActivationLong("journal_epoch") == p.epoch && row.getInt("target_count") == p.count &&
                    row.getString("object_key") == variant.key && allowedKind(p.kind, p.count))
                val at = checkNotNull(row.getTimestamp("applied_at")).toInstant()
                requireTestTerminalCatalog(at >= p.verifiedAt && at <= checkNotNull(p.convertedAt) && at <= sampledAt)
                CatalogTestRunTerminalAppliedV1(variant.id, variant.key, checkNotNull(row.getString("object_version")), hash(row, "ciphertext_hash"),
                    variant.canonicalSha256, p.kind, p.epoch, p.count, hash(row, "physical_hash")).also { a ->
                    requireTestTerminalCatalog(a.version != "null")
                    if (native != null && native.event.route.objectKey == a.key && native.versionId == a.version) {
                        a.requireNative(CatalogOrdinaryInventoryEntryV1.from(native)); matchedNativeApplied = true
                    }
                }
            }, input.scope, journal.ordinaryPrefix + "%", journal.sealTerminalPrefix + "%", after?.first, after?.first, after?.second)
            if (page.isEmpty()) break
            page.forEach { value ->
                requireTestTerminalCatalog(all.size.toLong() < maximumVersions && after?.let { TestOrdinaryDrainRowsV1.compare(it, value.locator) < 0 } != false)
                all.add(value); after = value.locator
            }
        }
        return all
    }

    private fun requirePrimaryMembership(all: List<CatalogTestRunTerminalAppliedV1>) {
        publications.filter { it.appliedAt != null }.forEach { p ->
            val ids = p.variants.map { it.id }.toSet()
            val family = all.filter { it.id in ids }
            requireTestTerminalCatalog(family.size.toLong() == p.appliedCharge && family.size in 1..4 &&
                family.groupBy { it.key }.values.all { versions -> versions.map { it.ciphertextSha256 }.distinct().size == 1 })
            requireTestTerminalCatalog(all.count { it.id == p.id && it.key == p.key && it.version == p.version && it.ciphertextSha256 == p.ciphertextSha256 } == 1)
        }
        requireTestTerminalCatalog(all.size.toLong() == record.progress.completedCuts()[0].denial.firstInventory.versionCount)
    }

    private fun readSource(run: CatalogTestRunTerminalRunV1): TestInstallationManifestSourceV1.Observation {
        val reads = record.progress.installationReads()
        requireTestTerminalCatalog(reads.size == 2)
        val binding = TestInstallationSourceV1.Binding(original.process.databaseIdentity.toString(), original.process.restoreIdentity.toString(),
            original.process.desiredGeneration, expected.scoped.leaseToken)
        val source = TestInstallationManifestSourceV1(journal, original.runContext, binding, run.installationLimit, run.enrolled, reads[0])
        repeat(2) {
            retained(); requireCredentials()
            source.begin(binding, now().epochSecond)
            val secondHistorical = MessageDigest.getInstance("SHA-256")
            val second = reads[1]
            var bytes = append(secondHistorical, 0, TestInstallationSourceV1.sourcePrefix(original.runContext,
                TestInstallationSourceV1.Binding(second.databaseIdentity, second.restoreIdentity, second.desiredGeneration, second.fencingToken), run.enrolled))
            var after: UUID? = null
            while (true) {
                retained()
                val page = jdbc.query(TestInstallationSourceSqlV1.page, { row, _ -> TestInstallationSourceV1.Row.read(row) }, input.scope, after, after)
                if (page.isEmpty()) break
                page.forEach { row ->
                    retained(); source.entry(row); bytes = append(secondHistorical, bytes, row.fields()); after = UUID.fromString(row.id)
                }
            }
            requireTestTerminalCatalog(bytes == second.sourceHighWater.framedByteCount && TestTerminalFramesV1.finish(secondHistorical) == second.sourceHighWater.sourceSha256)
            requireCredentials(); source.end(binding, now().epochSecond)
        }
        return source.finish().also { observed ->
            val actual = observed.progress.installationReads().first()
            reads.forEach { historical -> requireSourceMembership(historical, actual) }
            sourceHash = actual.sourceHighWater.sourceSha256
        }
    }

    private fun requireSidecars(source: TestInstallationManifestSourceV1.Observation, applied: List<CatalogTestRunTerminalAppliedV1>) {
        val roots = TestTerminalRootsV1(journal, expected.run.installationLimit, TestTerminalSyntaxV1.chunkCount(expected.run.installationLimit))
        val chunks = source.startChunks(input.terminalEpoch)
        val fullOrdinaryRoot = ordinaryManifest(applied) // Keep the whole 1..cutoff denial commitment.
        requireTestTerminalCatalog(!input.hasActiveHistory || applied.none { it.epoch == 1L })
        val expectedOrdinaryRoot = if (input.hasActiveHistory) ordinaryManifest(applied, 2, input.ordinaryEpoch) else fullOrdinaryRoot
        val expectedTerminalRoot = TestTerminalEpochSealManifestV1.Builder(journal, input.terminalEpoch, record.installationManifest.chunks.size)
        repeat(2) { pass ->
            if (pass == 1) expectedTerminalRoot.beginSecond()
            publications.filter { it.appliedAt == null }.forEach { p ->
                val target = input.targets.single { it.id == p.id }
                expectedTerminalRoot.entry(p.kind, target.ordinal, p.id, p.objectRef(), p.verificationSha256)
            }
        }
        val terminalManifest = expectedTerminalRoot.finish()
        val preterminalSeals = TestTerminalSealSetV1.create(original.runContext.dataScopeId, original.runContext.activationCatalogGeneration,
            original.runContext.activationCatalogSha256, record.sealSet.records().dropLast(1))
        requireTestTerminalCatalog(roots.preTerminalSeals(preterminalSeals) == record.purge.document.preTerminalSeals)
        val inventory = roots.preTerminalInventory(original.runContext, preterminalSeals)
        val preterminal = applied.map { TestTerminalInventoryEntryV1(original.writer, it.kind, it.epoch, it.epoch, it.objectRef()) } +
            record.sealSet.records().dropLast(1).map { TestTerminalInventoryEntryV1(it.writerGeneration, "EPOCH_SEAL", it.epochStartInclusive, it.epochEndInclusive, it.objectRef) }
        val sorted = preterminal.sortedWith { a, b ->
            val scalar = compareValuesBy(a, b, { it.epochStartInclusive }, { it.epochEndInclusive }, { it.objectKind }, { it.objectRef.objectKey })
            if (scalar != 0) scalar else TestTerminalFramesV1.compareUtf8(a.objectRef.objectVersion, b.objectRef.objectVersion)
        }
        sorted.forEach(inventory::firstPass); inventory.beginSecondPass(); sorted.forEach(inventory::secondPass)
        requireTestTerminalCatalog(inventory.finish() == record.purge.document.preTerminalInventory)
        val targets = sidecarTargets()
        val selected = original.preflightTerminalTarget(this)
        for (target in targets) {
            retained()
            val loaded = loadSidecar(target)
            try {
                val bytes = loaded.canonicalBytes()
                try {
                    when (target.kind) {
                        TestTerminalCodecKindV1.INSTALLATION_MANIFEST -> {
                            val document = json.installationManifest(bytes)
                            val expectedChunk = record.installationManifest.chunks[target.ordinal]
                            requireTestTerminalCatalog(document.eventId == expectedChunk.eventId && document.chunkIndex == expectedChunk.chunkIndex &&
                                document.installationCount == expectedChunk.installationCount && document.retiredCount == expectedChunk.retiredCount &&
                                document.deletedCount == expectedChunk.deletedCount && document.entriesSha256 == expectedChunk.entriesSha256)
                            chunks.add(document, target.objectRef)
                        }
                        TestTerminalCodecKindV1.TEST_RUN_PURGE -> requireTestTerminalCatalog(bytes.contentEquals(input.purgeBytes()))
                        TestTerminalCodecKindV1.EPOCH_SEAL -> {
                            val seal = json.epochSeal(bytes)
                            requireSeal(seal, loaded, target)
                            val active = target.source === TestTerminalQuiescenceSourceV1.V26_ACTIVE_SEAL
                            val count = if (active) 0L else if (target.ordinal == 0) applied.size.toLong() else terminalManifest.count
                            val root = if (active) ordinaryManifest(emptyList(), 1, 1) else if (target.ordinal == 0) expectedOrdinaryRoot else terminalManifest.sha256
                            requireTestTerminalCatalog(seal.eventCount == count && seal.eventManifestSha256 == root)
                        }
                    }
                } finally { bytes.fill(0) }
                if (selected == target) {
                    requireTestTerminalCatalog(returnedRow == null); returnedRow = loaded
                }
            } finally { if (returnedRow !== loaded) loaded.close() }
        }
        requireTestTerminalCatalog(chunks.finish() == record.installationManifest.summary)
    }

    private fun loadSidecar(target: TestTerminalQuiescenceTargetV1): TestTerminalDurableRowV1 {
        if (target.source === TestTerminalQuiescenceSourceV1.V26_ACTIVE_SEAL) {
            val loaded = expected.activeHistory.frozenInitial(jdbc, original, expected.activation, target)
            try { original.requirePredecessorSidecar(this, target, loaded.binding); return loaded }
            catch (problem: Throwable) { loaded.close(); throw problem }
        }
        val (sql, args) = when (target.kind) {
            TestTerminalCodecKindV1.INSTALLATION_MANIFEST -> CatalogTestRunTerminalPreflightSqlV1.manifest to arrayOf<Any?>(input.scope, target.ordinal)
            TestTerminalCodecKindV1.TEST_RUN_PURGE -> CatalogTestRunTerminalPreflightSqlV1.purge to arrayOf<Any?>(input.scope)
            TestTerminalCodecKindV1.EPOCH_SEAL -> if (target.ordinal == 0) CatalogTestRunTerminalPreflightSqlV1.ordinarySeal to
                arrayOf<Any?>(input.scope, journal.sealTerminalPrefix + "%") else CatalogTestRunTerminalPreflightSqlV1.terminalSeal to arrayOf<Any?>(input.scope)
        }
        val sampledAt = now()
        val allocated = ArrayList<TestTerminalDurableRowV1>(1)
        try {
            val rows = jdbc.query(sql, { row, _ ->
                requireTestTerminalCatalog(allocated.isEmpty() && row.requiredTestActivationBoolean("valid"))
                val context = TestTerminalRunContextV1(row.getObject("data_scope_id", UUID::class.java).toString(), row.requiredTestActivationLong("activation_catalog_generation"),
                    hash(row, "activation_catalog_hash"), hash(row, "configuration_hash"), hash(row, "terminal_encoding_hash"))
                val binding = TestTerminalDurableBindingV1(row.getObject("operation_token", UUID::class.java).toString(), context, hash(row, "journal_configuration_hash"),
                    TestTerminalDurableKindV1.valueOf(checkNotNull(row.getString("object_kind"))), row.getInt("object_ordinal"),
                    checkNotNull(row.getString("object_id")), checkNotNull(row.getString("object_key")), checkNotNull(row.getString("routing_key_id")),
                    row.getObject("writer_generation", UUID::class.java).toString(), row.requiredTestActivationLong("epoch_start"), row.requiredTestActivationLong("epoch_end"),
                    row.requiredTestActivationLong("preparing_fencing_token"), checkNotNull(row.getTimestamp("retention_floor")).toInstant(),
                    checkNotNull(row.getTimestamp("created_at")).toInstant())
                requireTestTerminalCatalog(context == original.runContext && binding.journalConfigurationSha256 == journal.sha256 &&
                    binding.objectKind.name == target.kind.name && binding.objectOrdinal == target.ordinal && binding.objectId == target.id &&
                    binding.writerGeneration == original.writer && binding.objectKey == target.objectRef.objectKey && binding.createdAt >= expected.run.sealedAt &&
                    binding.createdAt <= sampledAt && binding.epochStartInclusive == target.startEpoch && binding.epochEndInclusive == target.endEpoch &&
                    binding.preparingFencingToken in 1 until record.progress.completedCuts()[1].fencingToken &&
                    binding.retentionFloor >= original.acquisition.retention.lastPreRunRestoreHorizon.plusSeconds(31 * 86400L))
                original.requirePredecessorSidecar(this, target, binding)
                val loaded = TestTerminalSqlRowV1.restore(row, binding, sampledAt).also(allocated::add)
                requireTestTerminalCatalog(loaded.state === TestTerminalDurableStateV1.WIRE_FROZEN && loaded.canonicalSha256 == target.objectRef.canonicalSha256 &&
                    loaded.wireSha256 == target.objectRef.ciphertextSha256)
                if (target.kind !== TestTerminalCodecKindV1.EPOCH_SEAL) {
                    val p = publications.single { it.id == target.id }
                    requireTestTerminalCatalog(p.createdAt == binding.createdAt && p.canonicalSha256 == loaded.canonicalSha256 &&
                        p.retainUntil >= checkNotNull(loaded.retainUntil) && p.objectRef() == target.objectRef)
                }
                sidecars.add(hash(row, "physical_hash")); loaded
            }, *args)
            requireTestTerminalCatalog(rows.size == 1)
            return rows.single().also { allocated.clear() }
        } finally { allocated.forEach(TestTerminalDurableRowV1::close) }
    }

    private fun requireSidecarIdentities() {
        val targets = sidecarTargets().filter { it.source === TestTerminalQuiescenceSourceV1.V21_TERMINAL_INTENT }
        requireTestTerminalCatalog(sidecars.size == targets.size)
        targets.forEachIndexed { index, target ->
            retained()
            val current = jdbc.query(CatalogTestRunTerminalPreflightSqlV1.sidecarIdentity,
                { row, _ -> hash(row, "physical_hash") }, input.scope, target.kind.name, target.ordinal).single()
            requireTestTerminalCatalog(current == sidecars[index])
        }
        expected.activeHistory.requirePhysical(jdbc, original)
    }

    private fun sidecarTargets(): List<TestTerminalQuiescenceTargetV1> = input.targets.sortedWith(
        compareBy<TestTerminalQuiescenceTargetV1> { it.kind.name }.thenBy { it.source.name }.thenBy { it.ordinal })

    private fun restore(kind: String, bytes: ByteArray, routingKey: String): TestOwnerDeleteJournalEventV1 = when (kind) {
        "OWNER_DELETE", "OWNER_DELETE_ALL" -> TestOwnerDeleteJournalCodecV1.restoreCanonical(original.routing, bytes, routingKey)
        "ADMIN_DELETE", "ADMIN_BATCH_DELETE" -> TestOwnerDeleteJournalCodecV1.restoreAdminErasureCanonical(original.routing, bytes, routingKey,
            ComplaintJournalDeletionKindV1.valueOf(kind))
        else -> throw CatalogTestRunTerminalExceptionV1()
    }

    private fun requireSeal(seal: TestTerminalEpochSealV1, row: TestTerminalDurableRowV1, target: TestTerminalQuiescenceTargetV1) {
        val reference = record.sealSet.records().single { it.sealId == target.id && it.objectRef == target.objectRef &&
            it.epochStartInclusive == target.startEpoch && it.epochEndInclusive == target.endEpoch }
        requireTestTerminalCatalog(seal.sealId == reference.sealId && seal.writerGeneration == reference.writerGeneration && seal.dataScopeKind == "TEST" &&
            seal.dataScopeId == input.scope.toString() && seal.epochStartInclusive == reference.epochStartInclusive && seal.epochEndInclusive == reference.epochEndInclusive &&
            seal.precedingSealSha256 == reference.precedingSealSha256 && seal.preparingFencingToken == row.binding.preparingFencingToken)
    }
    private fun ordinaryManifest(applied: List<CatalogTestRunTerminalAppliedV1>, start: Long = 1, end: Long = input.ordinaryEpoch): String {
        requireTestTerminalCatalog(start in 1..end && end <= input.ordinaryEpoch && applied.all { it.epoch in start..end })
        val hash = MessageDigest.getInstance("SHA-256")
        var bytes = EpochSealFramesV1.update(hash, listOf(EpochSealFramesV1.DOMAIN, "1", "manifest", original.writer,
            journal.ordinaryPrefix, "TEST", input.scope.toString(), start.toString(), end.toString(), applied.size.toString()))
        applied.forEach { entry -> bytes = Math.addExact(bytes, EpochSealFramesV1.update(hash, listOf(entry.key, entry.version, entry.ciphertextSha256))) }
        val cut = record.progress.completedCuts()[0]
        val result = TestTerminalFramesV1.finish(hash)
        requireTestTerminalCatalog(bytes <= maximumBytes)
        if (start == 1L && end == input.ordinaryEpoch) requireTestTerminalCatalog(bytes == cut.framedByteCount && result == cut.denial.firstInventory.sha256)
        return result
    }
    private fun requireRelations() {
        retained()
        val sql = if (input.hasActiveHistory) TestTerminalQuiescenceSqlV1.relationWithActiveHistory else TestTerminalQuiescenceSqlV1.relation
        requireTestTerminalCatalog(jdbc.query(sql, { row, _ -> row.requiredTestActivationBoolean("valid") }, input.scope,
            journal.ordinaryPrefix + "%", journal.sealTerminalPrefix + "%", input.writer, input.ordinaryEpoch, input.terminalEpoch,
            journal.ownerDeleteAll, journal.registeredAdminDelete, journal.registeredAdminBatchDelete, OwnerDeleteRows.array(TestRunPurgeOperationV1.FUTURE)).single())
    }
    private fun requireCounts() {
        retained()
        jdbc.query(TestTerminalEpochSealSqlV1.counts, { row, _ ->
            val count = record.installationManifest.chunks.size.toLong()
            requireTestTerminalCatalog(row.requiredTestActivationLong("manifests") == count && row.requiredTestActivationLong("first_ordinal") == 0L &&
                row.requiredTestActivationLong("last_ordinal") == count - 1L && row.requiredTestActivationLong("ordinary_seals") == 1L &&
                row.requiredTestActivationLong("terminal_seals") == 1L && row.requiredTestActivationLong("purges") == 1L &&
                row.requiredTestActivationLong("other_intents") == 0L && row.requiredTestActivationLong("publications") == count &&
                row.requiredTestActivationLong("reservations") == count && row.requiredTestActivationLong("purge_publications") == 1L &&
                row.requiredTestActivationLong("purge_reservations") == 1L && row.requiredTestActivationLong("scan_runs") == 0L &&
                row.requiredTestActivationLong("scan_entries") == 0L)
        }, input.scope).single()
    }
    private fun requireCredentials() = requireTestTerminalCatalog(jdbc.query(TestInstallationSourceSqlV1.credentialsComplete,
        { row, _ -> row.requiredTestActivationBoolean("valid") }, input.scope).single())
    private fun readRun(lock: Boolean): CatalogTestRunTerminalRunV1 = jdbc.query(
        if (lock) CatalogTestRunTerminalProjectionSqlV1.lockRun else CatalogTestRunTerminalProjectionSqlV1.readRun,
        { row, _ -> CatalogTestRunTerminalRunV1(row, maximumVersions) }, *original.runArguments(expected.activation)).single()
    private fun allowedKind(kind: String, count: Int): Boolean = when (kind) {
        "OWNER_DELETE" -> count == 1
        "OWNER_DELETE_ALL" -> journal.ownerDeleteAll && count in 0..100
        "ADMIN_DELETE" -> journal.registeredAdminDelete && count == 1
        "ADMIN_BATCH_DELETE" -> journal.registeredAdminBatchDelete && count in 1..50
        else -> false
    }
    private fun requireSourceMembership(old: TestTerminalInstallationReadV1, actual: TestTerminalInstallationReadV1) = requireTestTerminalCatalog(
        old.databaseIdentity == actual.databaseIdentity && old.restoreIdentity == actual.restoreIdentity && old.desiredGeneration == actual.desiredGeneration &&
            old.sourceHighWater.enrolledCount == actual.sourceHighWater.enrolledCount && old.sourceHighWater.reservationCount == actual.sourceHighWater.reservationCount &&
            old.sourceHighWater.greatestReservationId == actual.sourceHighWater.greatestReservationId && old.installationCount == actual.installationCount &&
            old.retiredCount == actual.retiredCount && old.deletedCount == actual.deletedCount && old.chunkCount == actual.chunkCount &&
            old.installationsSha256 == actual.installationsSha256 && old.installationsFramedBytes == actual.installationsFramedBytes &&
            old.chunkSetSha256 == actual.chunkSetSha256 && old.chunkSetFramedBytes == actual.chunkSetFramedBytes)
    private fun now(): Instant {
        retained()
        return jdbc.query("SELECT clock_timestamp() AS at", { row, _ -> checkNotNull(row.getTimestamp("at")).toInstant() }).single().also {
            requireTestTerminalCatalog(it.epochSecond in 0..253_402_300_799L); retained()
            original.requirePreflightSqlTime(this, it)
        }
    }
    private fun append(hash: MessageDigest, before: Long, fields: List<String>): Long {
        val bytes = TestTerminalFramesV1.update(hash, fields)
        requireTestTerminalCatalog(bytes >= 0 && before >= 0 && bytes <= maximumBytes - before)
        return before + bytes
    }
    internal fun beginCounterLock(selected: JdbcTemplate) { at(Stage.COUNTERS_REQUESTED); requireTestTerminalCatalog(selected === jdbc); stage = Stage.COUNTERS_LOCKING }
    internal fun requireCounterRead(selected: JdbcTemplate) { at(Stage.COUNTERS_LOCKING); requireTestTerminalCatalog(selected === jdbc) }
    internal fun requireResultConstruction() = at(Stage.RESULT)
    private fun retained() {
        phase.testRunTerminalCatalogPreflight.requireRetained(this, jdbc)
        requireTestTerminalCatalog(kind === original.preflightKind())
    }
    private fun at(expected: Stage) { retained(); requireTestTerminalCatalog(stage === expected) }
    private enum class Stage { NEW, CONTROLS, PAIRS, COUNTERS_REQUESTED, COUNTERS_LOCKING, BODY, RESULT, COMPLETE }
    private data class Publication(val id: String, val key: String, val kind: String, val epoch: Long, val count: Int, val routingKey: String,
        val canonicalSha256: String, val version: String, val ciphertextSha256: String, val createdAt: Instant, val objectCreatedAt: Instant,
        val retainUntil: Instant, val verifiedAt: Instant, val appliedAt: Instant?, val physicalSha256: String, val verificationSha256: String,
        val variants: List<Variant> = emptyList(), val convertedAt: Instant? = null, val appliedCharge: Long = 0) {
        fun objectRef() = TestTerminalObjectRefV1(key, version, ciphertextSha256, canonicalSha256)
        override fun toString(): String = "CatalogTestRunTerminalPublicationV1(scalar-comparison,redacted)"
    }
    private data class Variant(val id: String, val key: String, val routingKey: String, val canonicalSha256: String)
    private class ReadPublication(val facts: Publication, val event: TestOwnerDeleteJournalEventV1?)
    private class Recovery(val physicalSha256: String, val at: Instant?, val applied: Long)
    companion object {
        internal fun execute(jdbc: JdbcTemplate, original: CatalogTestRunTerminalV1): CatalogTestRunTerminalPreflightOperationV1 {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            var operation: CatalogTestRunTerminalPreflightOperationV1? = null
            try {
                phase.testRunTerminalCatalogPreflight.requireOperation(original, jdbc)
                return CatalogTestRunTerminalPreflightOperationV1(phase, jdbc, original).also {
                    operation = it; phase.testRunTerminalCatalogPreflight.retain(it, jdbc); it.execute()
                }
            } catch (problem: Throwable) {
                operation?.discardRow(); original.observeFailure(problem); phase.recordFailure(problem); original.throwIfSignalled()
                throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
            }
        }
        private fun hash(row: ResultSet, name: String): String = TestOrdinaryDrainRowsV1.hash(row, name)
    }
    override fun toString(): String = "CatalogTestRunTerminalPreflightOperationV1(read-only,original-owned,no-catalog-lock)"
}

/** A.version is the complete all-version identity, not necessarily the publication's first version. */
internal data class CatalogTestRunTerminalAppliedV1(val id: String, val key: String, val version: String, val ciphertextSha256: String,
    val canonicalSha256: String, val kind: String, val epoch: Long, val count: Int, val physicalSha256: String) {
    val locator: Pair<String, String> get() = key to version
    fun objectRef() = TestTerminalObjectRefV1(key, version, ciphertextSha256, canonicalSha256)
    fun requireNative(entry: CatalogOrdinaryInventoryEntryV1) = requireTestTerminalCatalog(entry.key == key && entry.version == version &&
        entry.eventId == id && entry.wireSha256 == ciphertextSha256 && entry.canonicalSha256 == canonicalSha256 && entry.kind == kind && entry.epoch == epoch)
    override fun toString(): String = "CatalogTestRunTerminalAppliedV1(all-version-scalar-comparison,redacted)"
}
