package me.manga.kira.backend.complaint.infrastructure.terminal

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveCheckpointHistoryV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveRecurrentCheckpointDocumentV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveRecurrentStorageV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveInitialCheckpointDocumentV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableBindingV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableKindV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableRowV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalObjectRefV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalProfileV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalRunContextV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSealRefV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSealRoleV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalMutationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveFirstCutIdentityV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveRecurrentVerificationV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestInitialCheckpointCurrentCodecV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.recurrentDocumentArguments
import me.manga.kira.backend.complaint.infrastructure.reconciliation.recurrentHistoryIdentity
import me.manga.kira.backend.security.EpochSealFramesV1
import me.manga.kira.backend.security.TestTerminalJsonV1
import me.manga.kira.backend.security.TestTerminalFramesV1
import me.manga.kira.backend.security.TestPostTerminalInventoryEntryV1
import me.manga.kira.backend.security.TestTerminalCodecKindV1
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.security.MessageDigest
import java.time.Instant
import java.util.Collections
import java.util.HexFormat
import java.util.UUID

/**
 * Complete ordered original-source comparison under the consumer's current holder. N0 and the
 * unarchived V26 N1 keep their old meanings; recurrent histories require EVERY V26/V31 source and
 * charged archive, including the last complete checkpoint. This object never issues authority.
 */
internal class TestOrdinaryDrainActiveHistoryV1 private constructor(
    records: List<Record>,
    private val historyFingerprint: String,
) {
    val records: List<Record> = Collections.unmodifiableList(records.toList())
    val reference get() = records.last().reference
    val operationToken get() = records.last().operationToken
    val checkpointCompletedAt get() = records.last().checkpointCompletedAt
    val count get() = records.size
    val commitment: TestActiveCheckpointHistoryV1? = records.first().entry?.let {
        TestActiveCheckpointHistoryV1.of(records.map { checkNotNull(it.entry) })
    }
    val ordinaryPaid = ComplaintCapacityVector.units(ComplaintCapacityCounter.STORAGE_BYTES,
        Math.multiplyExact(count.toLong(), TestActiveRecurrentStorageV1.INTENT_STORAGE_BYTES) +
            if (commitment == null) 0L else Math.multiplyExact(count.toLong(), TestActiveRecurrentStorageV1.HISTORY_STORAGE_BYTES))

    fun requireSame(other: TestOrdinaryDrainActiveHistoryV1?) {
        requireDrain(other != null && count == other.count && historyFingerprint == other.historyFingerprint)
        records.zip(checkNotNull(other).records).forEach { (a, b) -> a.requireSame(b) }
    }
    fun record(ordinal: Int): Record = records.single { it.binding.objectOrdinal == ordinal }
    fun requireControlFingerprint(value: String) = requireDrain(historyFingerprint == value)

    /** A fresh complete source read, not a payload selected by an untrusted target alone. */
    fun frozen(jdbc: JdbcTemplate, original: TestRunOrdinaryDrainV1, record: Record): TestTerminalDurableRowV1 =
        takeFrozen(checkNotNull(materialize(jdbc, original.registration)), record)

    fun frozenRows(jdbc: JdbcTemplate, original: TestRunOrdinaryDrainV1): List<TestTerminalDurableRowV1> {
        val current = checkNotNull(materialize(jdbc, original.registration))
        try { requireSame(current.history); return current.rows }
        catch (problem: Throwable) { current.rows.forEach { it.close() }; throw problem }
    }

    fun frozenForCatalog(jdbc: JdbcTemplate, original: CatalogTestRunTerminalV1,
        activation: CatalogTestRunTerminalMutationV1, record: Record): TestTerminalDurableRowV1 {
        original.requireHistoryRead(jdbc)
        return takeFrozen(checkNotNull(materializeRecurrentForCatalog(jdbc, original, activation)), record)
    }
    private fun takeFrozen(current: Materialized, record: Record): TestTerminalDurableRowV1 {
        var retained: TestTerminalDurableRowV1? = null
        try {
            requireSame(current.history)
            this.record(record.binding.objectOrdinal).requireSame(record)
            val row = current.rows.single { it.binding == record.binding }
            retained = row
            return row
        } finally { current.rows.filter { it !== retained }.forEach { it.close() } }
    }
    fun commitments(): List<List<String>> {
        requireDrain(commitment != null)
        return records.map { listOf(it.source.name, it.binding.objectOrdinal.toString(), it.slotFingerprint,
            checkNotNull(it.archiveFingerprint), checkNotNull(it.entry).sha256, it.verificationSha256) } +
            listOf(listOf("V31_HISTORY", checkNotNull(commitment).rootSha256, historyFingerprint))
    }
    /** E persists this bounded comparison at its actual projection; F never authors or repairs it. */
    fun erasureCommitment(terminalToken: UUID, terminalGeneration: Long, terminalSha256: String): String {
        val identity = checkNotNull(commitment).first.identity
        requireDrain(count in 2..TestActiveRecurrentStorageV1.MAX_ACTIVE_SEALS && terminalToken.version() == 4 &&
            terminalGeneration == identity.activationCatalogGeneration + 1L && terminalSha256.matches(Regex("[0-9a-f]{64}")))
        val digest = MessageDigest.getInstance("SHA-256")
        TestTerminalFramesV1.update(digest, listOf("kira-test-recurrent-erasure-history-v1", identity.scope, identity.runCreatedAt.toString(),
            identity.configurationSha256, identity.activationCatalogGeneration.toString(), identity.activationCatalogSha256,
            terminalToken.toString(), terminalGeneration.toString(), terminalSha256, count.toString()))
        commitments().forEach { TestTerminalFramesV1.update(digest, it) }
        return TestTerminalFramesV1.finish(digest)
    }
    fun requirePhysical(jdbc: JdbcTemplate, original: CatalogTestRunTerminalV1) {
        original.requireHistoryRead(jdbc)
        val shape = shape(jdbc, original.scope, original.routing.journalConfiguration.sealTerminalPrefix)
        requireDrain(shape.first == records.map { it.slotFingerprint } && shape.second == records.map { checkNotNull(it.archiveFingerprint) })
        original.requireHistoryRead(jdbc)
    }

    internal class Record(
        val reference: TestTerminalSealRefV1,
        val binding: TestTerminalDurableBindingV1,
        val checkpointCompletedAt: Instant,
        private val verifiedAt: Instant,
        private val retainUntil: Instant,
        internal val slotFingerprint: String,
        internal val verificationSha256: String,
        val eventCount: Long,
        val manifestSha256: String,
        val manifestFramedBytes: Long,
        val entry: TestActiveCheckpointHistoryV1.Entry? = null,
        internal val archiveFingerprint: String? = null,
        private val wireByteCount: Long? = null,
    ) {
        val operationToken: UUID get() = UUID.fromString(binding.operationToken)
        val source: TestActiveCheckpointHistoryV1.Source get() = entry?.source ?: TestActiveCheckpointHistoryV1.Source.V26_INITIAL
        fun requireSame(other: Record) = requireDrain(reference == other.reference && binding == other.binding &&
            checkpointCompletedAt == other.checkpointCompletedAt && verifiedAt == other.verifiedAt && retainUntil == other.retainUntil &&
            slotFingerprint == other.slotFingerprint && verificationSha256 == other.verificationSha256 &&
            eventCount == other.eventCount && manifestSha256 == other.manifestSha256 && manifestFramedBytes == other.manifestFramedBytes &&
            entry?.sha256 == other.entry?.sha256 && archiveFingerprint == other.archiveFingerprint && wireByteCount == other.wireByteCount)
        fun requireNative(row: TestTerminalDurableRowV1, proof: TestOrdinarySealProofV1, now: Instant) {
            requireDrain(proof.version == reference.objectRef.objectVersion && proof.retainUntil == retainUntil &&
                !proof.verifiedAt.isBefore(verifiedAt) && !proof.verifiedAt.isAfter(now) && proof.retainUntil.isAfter(now) &&
                row.binding == binding && row.canonicalSha256 == reference.objectRef.canonicalSha256 && row.wireSha256 == reference.objectRef.ciphertextSha256)
            val bytes = proof.canonicalBytes(row, verifiedAt)
            try { requireDrain(Sha256.hex(bytes) == verificationSha256) } finally { bytes.fill(0) }
        }
        fun requireNative(observed: TestPostTerminalInventoryEntryV1) {
            val e = checkNotNull(entry)
            requireDrain(observed.kind === TestTerminalCodecKindV1.EPOCH_SEAL && observed.writerGeneration == reference.writerGeneration &&
                observed.epochStartInclusive == reference.epochStartInclusive && observed.epochEndInclusive == reference.epochEndInclusive &&
                observed.eventId == null && observed.objectRef == reference.objectRef && observed.ciphertextByteCount == wireByteCount &&
                observed.lastModified == e.lastModified && observed.requestedRetainUntil == e.frozenRetainUntil && observed.retainUntil == e.retainUntil)
        }
        override fun toString(): String = "ActiveHistoryRecord(original-source-comparison,no-authority,redacted)"
    }
    override fun toString(): String = "TestOrdinaryDrainActiveHistoryV1(complete-ordered-comparison,no-current-authority,redacted)"

    companion object {
        fun read(jdbc: JdbcTemplate, original: TestRunOrdinaryDrainV1): TestOrdinaryDrainActiveHistoryV1? =
            materialize(jdbc, original.registration)?.let { value -> try { value.history } finally { value.rows.forEach { it.close() } } }

        /** The existing sealed-audit owner compares A even on already-closed audit replay. */
        fun beforeClosure(jdbc: JdbcTemplate, original: TestRunSealingV1): TestOrdinaryDrainActiveHistoryV1? {
            original.requirePersistence(original.coordinator.ownership, jdbc)
            requireSealing(original.path === PersistencePhasePath.COMPLAINT_TEST_RUN_SEALED_AUDIT)
            return materialize(jdbc, original.registration)?.let { value -> try { value.history } finally { value.rows.forEach { it.close() } } }
        }

        fun requireCurrent(jdbc: JdbcTemplate, original: TestRunOrdinaryDrainV1, expected: TestOrdinaryDrainActiveHistoryV1?) {
            val current = read(jdbc, original)
            requireDrain((expected == null) == (current == null))
            expected?.requireSame(current)
        }

        private class Materialized(val history: TestOrdinaryDrainActiveHistoryV1, val rows: List<TestTerminalDurableRowV1>)

        private fun materialize(jdbc: JdbcTemplate, registration: ComplaintTestNamespaceRegistrationV1): Materialized? {
            val journal = registration.process.consumers.journalConfiguration
            val shape = shape(jdbc, journal.scope.id, journal.sealTerminalPrefix)
            if (shape.first.size > 1 || shape.second.isNotEmpty()) {
                requireGlobalIdentity(jdbc, registration)
                return materializeArchived(jdbc, recurrentHistoryIdentity(TestActiveFirstCutIdentityV1.fromRegistration(registration)),
                    journal, checkNotNull(registration.process.ordinarySeal), shape)
            }
            requireNoRecurrentSources(jdbc, journal.scope.id, journal.sealTerminalPrefix)
            val allocated = ArrayList<Materialized>(1)
            try {
                val rows = jdbc.query(current, { row, _ ->
                    requireDrain(allocated.isEmpty()) // A second or foreign-prefix row is not a smaller history.
                    readRow(row, registration).also(allocated::add)
                }, registration.process.consumers.journalConfiguration.scope.id,
                    registration.process.consumers.journalConfiguration.sealTerminalPrefix + "%")
                requireDrain(rows.size <= 1)
                val value = rows.singleOrNull()
                if (value != null) requireGlobalIdentity(jdbc, registration)
                return value.also { allocated.clear() }
            } finally { allocated.forEach { it.rows.forEach { row -> row.close() } } }
        }

        private fun requireGlobalIdentity(jdbc: JdbcTemplate, registration: ComplaintTestNamespaceRegistrationV1) {
            // Every caller already holds its current global/scoped controls. Compare the retained
            // initial RELEASE's global B on EVERY A materialization, not just the first open-gate
            // closure. This adds neither a new lock order nor the completed drain's old budget.
            // An actually empty V26 relation keeps the legacy no-A path and needs no RELEASE latch.
            val identity = TestActiveFirstCutIdentityV1.fromRegistration(registration)
            requireDrain(identity.scope == registration.process.consumers.journalConfiguration.scope.id)
            val hash = identity.globalConfigurationHash()
            try {
                requireDrain(jdbc.query(TestRunSealingSqlV1.readActiveHistoryGlobal,
                    { row, _ -> TestOrdinaryDrainRowsV1.boolean(row, "valid") },
                    *registration.sealingControlArguments(), identity.globalDesiredGeneration, hash).single())
            } finally { hash?.fill(0) }
        }

        private fun readRow(row: ResultSet, registration: ComplaintTestNamespaceRegistrationV1): Materialized {
            val journal = registration.process.consumers.journalConfiguration
            val scope = journal.scope.id
            val writer = journal.declaration().writer.generationId
            val args = registration.sealingRunArguments()
            val context = TestTerminalRunContextV1(scope.toString(), args[3] as Long, HexFormat.of().formatHex(args[4] as ByteArray),
                HexFormat.of().formatHex(args[1] as ByteArray), TestTerminalProfileV1.encodingSha256)
            requireDrain(TestOrdinaryDrainRowsV1.boolean(row, "valid") && row.getObject("data_scope_id", UUID::class.java) == scope &&
                row.getObject("writer_generation", UUID::class.java).toString() == writer &&
                hash(row, "journal_configuration_hash") == journal.sha256 && hash(row, "seal_encoding_hash") == context.terminalEncodingSha256 &&
                hash(row, "configuration_hash") == context.configurationSha256 &&
                row.getLong("activation_catalog_generation") == context.activationCatalogGeneration &&
                hash(row, "activation_catalog_hash") == context.activationCatalogSha256)
            val at = checkNotNull(row.getTimestamp("sampled_at")).toInstant()
            val token = checkNotNull(row.getObject("operation_token", UUID::class.java))
            val binding = TestTerminalDurableBindingV1(token.toString(), context, journal.sha256,
                TestTerminalDurableKindV1.EPOCH_SEAL, 0, checkNotNull(row.getString("object_id")), checkNotNull(row.getString("object_key")),
                checkNotNull(row.getString("routing_key_id")), writer, 1, 1, row.getLong("preparing_fencing_token"),
                checkNotNull(row.getTimestamp("retention_floor")).toInstant(), checkNotNull(row.getTimestamp("created_at")).toInstant())
            val frozen = TestTerminalSqlRowV1.restore(row, binding, at)
            try {
                val canonical = frozen.canonicalBytes()
                val seal = try { TestTerminalJsonV1(journal).epochSeal(canonical) } finally { canonical.fill(0) }
                val frame = EpochSealFramesV1.frame(listOf(EpochSealFramesV1.DOMAIN, "1", "manifest", writer,
                    journal.ordinaryPrefix, "TEST", scope.toString(), "1", "1", "0"))
                val framedBytes = frame.size.toLong()
                val emptyRoot = try { Sha256.hex(frame) } finally { frame.fill(0) }
                requireDrain(seal.sealId == binding.objectId && seal.writerGeneration == writer && seal.dataScopeId == scope.toString() &&
                    seal.epochStartInclusive == 1L && seal.epochEndInclusive == 1L && seal.precedingSealSha256.isEmpty() &&
                    seal.preparingFencingToken == binding.preparingFencingToken && seal.eventCount == 0L && seal.eventManifestSha256 == emptyRoot)
                val version = checkNotNull(row.getString("seal_object_version"))
                val checkpoint = checkNotNull(row.getBytes("checkpoint_bytes"))
                try {
                    requireDrain(checkpoint.size in 1..TestActiveInitialCheckpointDocumentV1.MAX_BYTES && Sha256.hex(checkpoint) == hash(row, "checkpoint_hash"))
                    checkpointFactory.createParser(checkpoint).use { parser ->
                        var tokens = 0
                        while (parser.nextToken() != null) requireDrain(++tokens <= 512)
                    }
                    val passes = CanonicalJson.json.parseToJsonElement(checkpoint.toString(Charsets.UTF_8)).jsonObject.getValue("passes").jsonArray
                    requireDrain(passes.size == 2)
                    fun pass(index: Int): TestActiveInitialCheckpointDocumentV1.Pass {
                        val value = passes[index].jsonObject
                        return TestActiveInitialCheckpointDocumentV1.Pass(Instant.parse(value.getValue("startedAt").jsonPrimitive.content),
                            Instant.parse(value.getValue("completedAt").jsonPrimitive.content))
                    }
                    val document = TestActiveInitialCheckpointDocumentV1(scope.toString(), row.getLong("desired_generation"),
                        row.getLong("checkpoint_fencing_token"), context.configurationSha256, journal.sha256,
                        row.getObject("database_identity", UUID::class.java).toString(), row.getObject("restore_identity", UUID::class.java).toString(),
                        row.getLong("accepted_catalog_generation"), hash(row, "accepted_catalog_hash"), hash(row, "trust_bundle_hash"),
                        row.getObject("catalog_writer_generation", UUID::class.java).toString(), writer, token.toString(), binding.objectKey,
                        version, frozen.canonicalSha256, checkNotNull(frozen.wireSha256), emptyRoot, framedBytes, pass(0), pass(1))
                    val expected = document.canonicalBytes()
                    try { requireDrain(checkpoint.contentEquals(expected)) } finally { expected.fill(0) }
                    requireDrain(document.startedAt == row.getTimestamp("checkpoint_started_at")?.toInstant() &&
                        document.completedAt == row.getTimestamp("checkpoint_completed_at")?.toInstant())
                } finally { checkpoint.fill(0) }
                val record = Record(TestTerminalSealRefV1(TestTerminalSealRoleV1.ORDINARY, writer, 1, 1,
                    binding.objectId, "", TestTerminalObjectRefV1(binding.objectKey, version, checkNotNull(frozen.wireSha256), frozen.canonicalSha256)),
                    binding, checkNotNull(row.getTimestamp("checkpoint_completed_at")).toInstant(), checkNotNull(row.getTimestamp("seal_verified_at")).toInstant(),
                    checkNotNull(row.getTimestamp("seal_retain_until")).toInstant(), hash(row, "slot_fingerprint"), hash(row, "seal_verification_hash"),
                    0L, emptyRoot, framedBytes)
                return Materialized(TestOrdinaryDrainActiveHistoryV1(listOf(record), hash(row, "history_fingerprint")), listOf(frozen))
            } catch (problem: Throwable) { frozen.close(); throw problem }
        }

        /** E owns its real retained process/activation. No registration or producer is reconstructed. */
        fun readRecurrentForCatalog(jdbc: JdbcTemplate, original: CatalogTestRunTerminalV1,
            activation: CatalogTestRunTerminalMutationV1): TestOrdinaryDrainActiveHistoryV1? {
            original.requireHistoryRead(jdbc)
            return materializeRecurrentForCatalog(jdbc, original, activation)?.let { value ->
                try { value.history } finally { value.rows.forEach { it.close() } }
            }.also { original.requireHistoryRead(jdbc) }
        }
        fun requireNoRecurrentForCatalog(jdbc: JdbcTemplate, original: CatalogTestRunTerminalV1) {
            original.requireHistoryRead(jdbc)
            val journal = original.routing.journalConfiguration
            requireNoRecurrentSources(jdbc, original.scope, journal.sealTerminalPrefix)
            requireDrain(shape(jdbc, original.scope, journal.sealTerminalPrefix).second.isEmpty())
        }

        /** F compares the complete source/archive history under its own fixed current SQL holder. */
        fun readRecurrentForErasure(jdbc: JdbcTemplate, operation: TestRunErasureOperationV1,
            run: TestRunErasureRowsV1.Run): TestOrdinaryDrainActiveHistoryV1? {
            operation.requireHistoryRead(jdbc)
            val original = operation.original
            val journal = original.routing.journalConfiguration
            val shape = shape(jdbc, original.scope, journal.sealTerminalPrefix)
            if (run.purged) {
                requireDrain(shape.first.isEmpty() && shape.second.isEmpty())
                return null
            }
            if (shape.first.size <= 1 && shape.second.isEmpty()) {
                requireNoRecurrentSources(jdbc, original.scope, journal.sealTerminalPrefix)
                return null // N0 or the unchanged unarchived V26 N1; F separately authenticates it.
            }
            val process = original.process
            val identity = TestActiveCheckpointHistoryV1.Identity(original.scope.toString(), run.createdAt,
                process.implementationSchema, process.desiredGeneration, HexFormat.of().formatHex(process.configurationHashBytes()),
                journal.sha256, process.databaseIdentity.toString(), process.restoreIdentity.toString(), original.writer,
                run.context.activationCatalogGeneration, run.context.activationCatalogSha256,
                run.context.activationCatalogGeneration, run.context.activationCatalogSha256,
                process.catalogReadback.currentTrustBundleSha256, process.catalogActivation.catalogWriterGenerationId)
            val value = materializeArchived(jdbc, identity, journal, original.acquisition, shape)
            try { operation.requireHistoryRead(jdbc); return value.history }
            finally { value.rows.forEach { it.close() } }
        }

        private fun materializeRecurrentForCatalog(jdbc: JdbcTemplate, original: CatalogTestRunTerminalV1,
            activation: CatalogTestRunTerminalMutationV1): Materialized? {
            val journal = original.routing.journalConfiguration
            val shape = shape(jdbc, original.scope, journal.sealTerminalPrefix)
            if (shape.first.size <= 1 && shape.second.isEmpty()) {
                requireNoRecurrentSources(jdbc, original.scope, journal.sealTerminalPrefix)
                return null
            }
            val process = original.process
            val identity = TestActiveCheckpointHistoryV1.Identity(original.scope.toString(),
                checkNotNull(checkNotNull(activation.completed).projectedAt), process.implementationSchema, process.desiredGeneration,
                HexFormat.of().formatHex(process.configurationHashBytes()), journal.sha256, process.databaseIdentity.toString(),
                process.restoreIdentity.toString(), original.writer, activation.generation, checkNotNull(activation.head).envelopeSha256,
                activation.generation, checkNotNull(activation.head).envelopeSha256, process.catalogReadback.currentTrustBundleSha256,
                process.catalogActivation.catalogWriterGenerationId)
            return materializeArchived(jdbc, identity, journal, original.acquisition, shape)
        }
        private fun shape(jdbc: JdbcTemplate, scope: UUID, prefix: String): Pair<List<String>, List<String>> {
            val slots = jdbc.query(TestOrdinaryDrainActiveHistorySqlV1.sourceIdentity, { row, _ -> hash(row, "fingerprint") }, scope, prefix + "%")
            val archives = jdbc.query(TestOrdinaryDrainActiveHistorySqlV1.archiveIdentity, { row, _ -> hash(row, "fingerprint") }, scope, prefix + "%")
            requireDrain(slots.size <= TestActiveRecurrentStorageV1.MAX_ACTIVE_SEALS && archives.size <= TestActiveRecurrentStorageV1.MAX_ACTIVE_SEALS)
            return slots to archives
        }
        private fun requireNoRecurrentSources(jdbc: JdbcTemplate, scope: UUID, prefix: String) {
            requireDrain(jdbc.query(TestOrdinaryDrainActiveHistorySqlV1.noRecurrent, { row, _ -> TestOrdinaryDrainRowsV1.boolean(row, "valid") }, scope, prefix + "%").single())
        }
        private class Archived(
            val record: Record, val row: TestTerminalDurableRowV1, val checkpoint: ByteArray, val checkpointHash: String,
            val predecessorToken: String?, val predecessorCheckpointHash: String?, val predecessorHistoryHash: String?,
        )
        private fun materializeArchived(jdbc: JdbcTemplate, identity: TestActiveCheckpointHistoryV1.Identity,
            journal: TestOwnerDeleteJournalConfigurationV1, acquisition: VersionBoundTestOrdinarySealV1,
            shape: Pair<List<String>, List<String>>): Materialized {
            requireDrain(shape.first.size in 2..TestActiveRecurrentStorageV1.MAX_ACTIVE_SEALS && shape.second.size == shape.first.size)
            val allocated = ArrayList<Archived>()
            var transferred = false
            try {
                val rows = jdbc.query(TestOrdinaryDrainActiveHistorySqlV1.rows, { row, _ ->
                    requireDrain(allocated.size < TestActiveRecurrentStorageV1.MAX_ACTIVE_SEALS)
                    readArchivedRow(row, identity, journal, acquisition).also(allocated::add)
                }, UUID.fromString(identity.scope), journal.sealTerminalPrefix + "%")
                requireDrain(rows.size == shape.first.size && rows.map { it.record.slotFingerprint } == shape.first &&
                    rows.map { it.record.archiveFingerprint } == shape.second)
                val entries = rows.map { checkNotNull(it.record.entry) }
                val history = TestActiveCheckpointHistoryV1.of(entries)
                rows.forEachIndexed { index, value ->
                    val e = entries[index]
                    requireDrain(e.ordinal == index + 1)
                    if (index == 0) {
                        requireDrain(value.predecessorToken == null && value.predecessorCheckpointHash == null && value.predecessorHistoryHash == null)
                        requireInitialArchive(value, identity)
                    } else {
                        val prior = rows[index - 1]
                        requireDrain(value.predecessorToken == prior.record.binding.operationToken && value.predecessorCheckpointHash == prior.checkpointHash &&
                            value.predecessorHistoryHash == TestActiveCheckpointHistoryV1.of(entries.take(index)).rootSha256 &&
                            e.requestedAt >= prior.record.checkpointCompletedAt)
                        val doc = TestActiveRecurrentCheckpointDocumentV1.parse(value.checkpoint)
                        doc.requireHistory(TestActiveCheckpointHistoryV1.of(entries.take(index + 1)))
                        requireDrain(doc.predecessorCheckpointSha256 == prior.checkpointHash && doc.completedAt == value.record.checkpointCompletedAt)
                    }
                }
                val last = rows.last()
                val doc = TestActiveRecurrentCheckpointDocumentV1.parse(last.checkpoint)
                doc.requireHistory(history)
                val columns = recurrentDocumentArguments(doc, last.checkpoint, last.checkpointHash)
                val fingerprint = try {
                    jdbc.query(TestOrdinaryDrainActiveHistorySqlV1.currentLast, { row, _ ->
                        requireDrain(TestOrdinaryDrainRowsV1.boolean(row, "valid")); hash(row, "history_fingerprint")
                    }, *columns, java.sql.Timestamp.from(checkNotNull(last.record.entry).verifiedAt),
                        java.sql.Timestamp.from(checkNotNull(last.record.entry).retainUntil), UUID.fromString(identity.scope), UUID.fromString(last.record.binding.operationToken)).single()
                } finally { columns.forEach { if (it is ByteArray) it.fill(0) } }
                return Materialized(TestOrdinaryDrainActiveHistoryV1(rows.map { it.record }, fingerprint), rows.map { it.row }).also { transferred = true }
            } finally {
                allocated.forEach { it.checkpoint.fill(0); if (!transferred) it.row.close() }
            }
        }
        private fun readArchivedRow(row: ResultSet, identity: TestActiveCheckpointHistoryV1.Identity,
            journal: TestOwnerDeleteJournalConfigurationV1, acquisition: VersionBoundTestOrdinarySealV1): Archived {
            requireDrain(TestOrdinaryDrainRowsV1.boolean(row, "valid"))
            val bytes = checkNotNull(row.getBytes("entry_bytes"))
            val entry = try { TestActiveCheckpointHistoryV1.Entry.parse(bytes).also { requireDrain(it.sha256 == hash(row, "entry_hash")) } }
                finally { bytes.fill(0) }
            val sourceIdentity = TestActiveCheckpointHistoryV1.Identity(row.getObject("data_scope_id", UUID::class.java).toString(), time(row, "run_created_at"),
                row.getInt("implementation_schema"), row.getLong("desired_generation"), hash(row, "configuration_hash"), hash(row, "journal_configuration_hash"),
                row.getObject("database_identity", UUID::class.java).toString(), row.getObject("restore_identity", UUID::class.java).toString(),
                row.getObject("writer_generation", UUID::class.java).toString(), row.getLong("activation_catalog_generation"), hash(row, "activation_catalog_hash"),
                row.getLong("accepted_catalog_generation"), hash(row, "accepted_catalog_hash"), hash(row, "trust_bundle_hash"),
                row.getObject("catalog_writer_generation", UUID::class.java).toString())
            requireDrain(sourceIdentity.same(identity) && entry.identity.same(identity))
            val ordinal = row.getInt("rotation_sequence")
            val source = TestActiveCheckpointHistoryV1.Source.valueOf(checkNotNull(row.getString("source")))
            val token = checkNotNull(row.getObject("operation_token", UUID::class.java))
            requireDrain(row.getObject("history_scope", UUID::class.java).toString() == identity.scope && row.getInt("history_ordinal") == ordinal &&
                row.getString("history_source") == source.name && row.getObject("history_token", UUID::class.java) == token &&
                row.getObject("initial_seal_token", UUID::class.java) == (if (ordinal == 1) token else null) &&
                row.getObject("recurrent_seal_token", UUID::class.java) == (if (ordinal == 1) null else token))
            val binding = TestTerminalDurableBindingV1(token.toString(), TestTerminalRunContextV1(identity.scope, identity.activationCatalogGeneration,
                identity.activationCatalogSha256, identity.configurationSha256, TestTerminalProfileV1.encodingSha256), identity.journalConfigurationSha256,
                TestTerminalDurableKindV1.EPOCH_SEAL, ordinal - 1, checkNotNull(row.getString("object_id")), checkNotNull(row.getString("object_key")),
                checkNotNull(row.getString("routing_key_id")), identity.writerGeneration, row.getLong("epoch_start"), row.getLong("epoch_end"),
                row.getLong("preparing_fencing_token"), time(row, "retention_floor"), time(row, "created_at"))
            requireDrain(hash(row, "seal_encoding_hash") == binding.run.terminalEncodingSha256 &&
                binding.retentionFloor >= acquisition.retention.lastPreRunRestoreHorizon.plusSeconds(31 * 86400L))
            val sampledAt = time(row, "sampled_at")
            val frozen = TestTerminalSqlRowV1.restore(row, binding, sampledAt)
            var checkpoint: ByteArray? = null
            try {
                val canonical = frozen.canonicalBytes()
                val seal = try { TestTerminalJsonV1(journal).epochSeal(canonical) } finally { canonical.fill(0) }
                requireDrain(seal.sealId == binding.objectId && seal.writerGeneration == binding.writerGeneration && seal.dataScopeId == identity.scope &&
                    seal.epochStartInclusive == binding.epochStartInclusive && seal.epochEndInclusive == binding.epochEndInclusive && seal.preparingFencingToken == binding.preparingFencingToken)
                val verification = checkNotNull(row.getBytes("verification_bytes"))
                val projected = try {
                    TestActiveRecurrentVerificationV1.parse(verification, hash(row, "verification_hash"), frozen,
                        checkNotNull(row.getString("object_version")), entry.retainUntil, entry.verifiedAt, sampledAt, acquisition).use { proof ->
                        TestActiveCheckpointHistoryV1.Entry(sourceIdentity, source, ordinal, token.toString(), binding.epochStartInclusive, binding.epochEndInclusive, row.getLong("epoch_after"),
                            row.getObject("request_owner", UUID::class.java).toString(), row.getLong("request_token"), time(row, "requested_at"),
                            row.getObject("capture_owner", UUID::class.java).toString(), row.getLong("capture_token"), time(row, "captured_at"), binding.preparingFencingToken,
                            binding.objectId, binding.objectKey, binding.routingKeyId, frozen.canonicalSha256, checkNotNull(frozen.wireSha256), checkNotNull(frozen.metadataSha256),
                            binding.run.terminalEncodingSha256, binding.retentionFloor, binding.createdAt, checkNotNull(frozen.retainUntil), checkNotNull(frozen.frozenAt),
                            proof.version, proof.lastModified, proof.retainUntil, proof.verifiedAt, proof.sha256, seal.precedingSealSha256, seal.eventCount,
                            seal.eventManifestSha256, entry.manifestFramedBytes, row.getLong("charged_storage_bytes"), row.getLong("history_storage_bytes"))
                    }
                } finally { verification.fill(0) }
                requireDrain(projected.sha256 == entry.sha256)
                val completedAt = time(row, "checkpointed_at")
                requireDrain(time(row, "archived_at") in entry.verifiedAt..sampledAt && completedAt in entry.verifiedAt..time(row, "sealed_at") &&
                    completedAt <= sampledAt && entry.objectKey.startsWith(journal.sealTerminalPrefix))
                checkpoint = checkNotNull(row.getBytes("checkpoint_bytes"))
                val checkpointHash = hash(row, "checkpoint_hash")
                requireDrain(Sha256.hex(checkpoint) == checkpointHash)
                val wire = checkNotNull(frozen.wireBytes())
                val wireCount = try { wire.size.toLong() } finally { wire.fill(0) }
                val record = Record(TestTerminalSealRefV1(TestTerminalSealRoleV1.ORDINARY, binding.writerGeneration, binding.epochStartInclusive, binding.epochEndInclusive,
                    binding.objectId, seal.precedingSealSha256, TestTerminalObjectRefV1(binding.objectKey, entry.objectVersion, entry.wireSha256, entry.canonicalSha256)),
                    binding, completedAt, entry.verifiedAt, entry.retainUntil, hash(row, "slot_fingerprint"), entry.verificationSha256,
                    entry.eventCount, entry.manifestSha256, entry.manifestFramedBytes, entry, hash(row, "archive_fingerprint"), wireCount)
                return Archived(record, frozen, checkpoint, checkpointHash, row.getObject("predecessor_operation_token", UUID::class.java)?.toString(),
                    row.getBytes("predecessor_checkpoint_hash")?.let { HexFormat.of().formatHex(it) }, row.getBytes("predecessor_history_hash")?.let { HexFormat.of().formatHex(it) })
            } catch (problem: Throwable) { checkpoint?.fill(0); frozen.close(); throw problem }
        }
        private fun requireInitialArchive(value: Archived, identity: TestActiveCheckpointHistoryV1.Identity) {
            val e = checkNotNull(value.record.entry)
            val doc = TestInitialCheckpointCurrentCodecV1.checkpoint(value.checkpoint)
            requireDrain(doc.scope == identity.scope && doc.desiredGeneration == identity.desiredGeneration && doc.configurationSha256 == identity.configurationSha256 &&
                doc.journalConfigurationSha256 == identity.journalConfigurationSha256 && doc.databaseIdentity == identity.databaseIdentity && doc.restoreIdentity == identity.restoreIdentity &&
                doc.writerGeneration == identity.writerGeneration && doc.catalogGeneration == identity.catalogGeneration && doc.catalogSha256 == identity.catalogSha256 &&
                doc.trustBundleSha256 == identity.trustBundleSha256 && doc.catalogWriterGeneration == identity.catalogWriterGeneration && doc.sealOperationToken == e.operationToken &&
                doc.sealObjectKey == e.objectKey && doc.sealObjectVersion == e.objectVersion && doc.sealCanonicalSha256 == e.canonicalSha256 && doc.sealCiphertextSha256 == e.wireSha256 &&
                doc.manifestSha256 == e.manifestSha256 && doc.manifestFramedBytes == e.manifestFramedBytes && e.eventCount == 0L &&
                doc.fencingToken > e.preparingFencingToken && doc.startedAt >= e.verifiedAt && doc.completedAt == value.record.checkpointCompletedAt)
        }
        private fun time(row: ResultSet, name: String): Instant = checkNotNull(row.getTimestamp(name)).toInstant().also {
            requireDrain(TestActiveInitialCheckpointDocumentV1.time(it))
        }

        private fun hash(row: ResultSet, field: String) = TestOrdinaryDrainRowsV1.hash(row, field)
        private val checkpointFactory = JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(4).maxStringLength(1024).maxNameLength(128).maxNumberLength(19).build()).build()

        // Fixed projection caps precede pgjdbc allocation. A violated cap is NULL/refusal, never a
        // shortened accepted value; the scope OR prefix relation remains unfiltered and LIMIT2.
        private val boundedProjection = (listOf(
            "i.journal_configuration_hash", "i.seal_encoding_hash", "i.configuration_hash", "i.activation_catalog_hash",
            "i.accepted_catalog_hash", "i.trust_bundle_hash", "i.canonical_hash", "i.wire_hash", "i.metadata_hash",
            "c.seal_verification_hash", "c.checkpoint_hash",
        ).map { it to 32 } + listOf(
            "i.canonical_bytes" to 65536, "i.wire_bytes" to 98304, "i.metadata_bytes" to 512, "c.checkpoint_bytes" to 65536,
            "i.state" to 16, "i.object_id" to 43, "i.object_key" to 1024, "i.routing_key_id" to 64,
            "i.checksum_sha256" to 44, "i.content_type" to 24, "i.object_lock_mode" to 10, "c.seal_object_version" to 1024,
        )).joinToString(",\n                ") { (field, maximum) ->
            "CASE WHEN octet_length($field) BETWEEN 1 AND $maximum THEN $field END AS ${field.substringAfter('.')}"
        }
        private val current = """
            SELECT i.data_scope_id, i.writer_generation, i.activation_catalog_generation, i.operation_token,
                i.preparing_fencing_token, i.retention_floor, i.created_at, i.desired_generation, i.database_identity,
                i.restore_identity, i.accepted_catalog_generation, i.catalog_writer_generation, i.retain_until, i.frozen_at,
                c.seal_retain_until, c.seal_verified_at, c.checkpoint_fencing_token, c.checkpoint_started_at, c.checkpoint_completed_at,
                $boundedProjection,
                s.at AS sampled_at,
                CASE WHEN octet_length(to_jsonb(i)::text) BETWEEN 1 AND 524288
                    THEN sha256(convert_to((to_jsonb(i) || jsonb_build_object('row_xmin', i.xmin::text))::text, 'UTF8')) END AS slot_fingerprint,
                sha256(convert_to(jsonb_build_array(${TestOrdinaryDrainSqlV1.historyFields})::text, 'UTF8')) AS history_fingerprint,
                (i.schema_version = 1 AND i.test_only AND r.test_only AND r.state IN ('SEALED', 'PURGING') AND c.test_only
                    AND i.run_created_at = r.created_at AND i.configuration_hash = r.configuration_hash
                    AND i.activation_catalog_generation = r.activation_catalog_generation AND i.activation_catalog_hash = r.activation_catalog_hash
                    AND i.implementation_schema = 1 AND i.implementation_schema = c.implementation_schema AND i.desired_generation = c.desired_generation
                    AND i.configuration_hash = c.desired_configuration_hash AND i.database_identity = c.database_identity
                    AND i.restore_identity = c.restore_identity AND i.writer_generation = c.event_writer_generation
                    AND i.accepted_catalog_generation = c.accepted_catalog_generation AND i.accepted_catalog_hash = c.accepted_catalog_hash
                    AND i.trust_bundle_hash = c.trust_bundle_hash AND i.catalog_writer_generation = c.catalog_writer_generation
                    AND i.rotation_sequence = 1 AND i.epoch_start = 1 AND i.epoch_end = 1 AND i.epoch_after = 2
                    AND i.charged_storage_bytes = 2097152 AND i.state = 'WIRE_FROZEN' AND i.canonicalizer = 'kcj-1'
                    AND complaint_is_v4(i.request_owner) AND complaint_is_v4(i.capture_owner)
                    AND i.request_token > 0 AND i.capture_token >= i.request_token AND i.preparing_fencing_token > i.capture_token
                    AND i.requested_at >= r.created_at AND i.captured_at >= i.requested_at AND i.created_at >= i.captured_at
                    AND i.frozen_at >= i.created_at AND i.frozen_at <= c.seal_verified_at AND i.retain_until > s.at
                    AND c.seal_state = 'SEAL_VERIFIED' AND c.seal_epoch = 1 AND c.seal_writer_generation = i.writer_generation
                    AND c.seal_operation_token = i.operation_token AND c.seal_object_key = i.object_key
                    AND c.seal_bytes = i.canonical_bytes AND c.seal_hash = i.canonical_hash AND c.seal_ciphertext_hash = i.wire_hash
                    AND complaint_bytes_match(i.canonical_bytes, i.canonical_hash, 65536) AND complaint_bytes_match(i.wire_bytes, i.wire_hash, 98304)
                    AND complaint_bytes_match(i.metadata_bytes, i.metadata_hash, 512) AND complaint_bytes_match(c.seal_verification_bytes, c.seal_verification_hash, 65536)
                    AND c.seal_retain_until >= i.retain_until AND c.seal_retain_until > s.at AND c.seal_verified_at <= c.checkpoint_started_at
                    AND c.checkpoint_generation = i.desired_generation AND c.checkpoint_fencing_token > i.preparing_fencing_token AND c.checkpoint_fencing_token <= c.lease_token
                    AND c.checkpoint_catalog_generation = i.accepted_catalog_generation AND c.checkpoint_catalog_hash = i.accepted_catalog_hash
                    AND c.checkpoint_writer_generation = i.writer_generation AND c.checkpoint_cutoff_epoch = 1
                    AND c.checkpoint_configuration_hash = i.configuration_hash AND c.checkpoint_database_identity = i.database_identity
                    AND c.checkpoint_restore_identity = i.restore_identity AND c.checkpoint_schema = 1 AND c.checkpoint_result = 'SUCCESS'
                    AND c.checkpoint_object_count = 0 AND c.checkpoint_byte_count = 0
                    AND c.checkpoint_completed_at >= c.checkpoint_started_at AND c.checkpoint_completed_at <= r.sealed_at AND r.sealed_at <= s.at
                    AND complaint_bytes_match(c.checkpoint_bytes, c.checkpoint_hash, 65536)
                    AND complaint_finite_times(c.checkpoint_started_at, c.checkpoint_completed_at, c.seal_verified_at, c.seal_retain_until, r.sealed_at, s.at)
                    AND complaint_finite_times(i.requested_at, i.captured_at, i.created_at, i.retention_floor, i.retain_until, i.frozen_at)
                    AND NOT EXISTS (SELECT 1 FROM complaint_journal_scan_runs x WHERE x.data_scope_id = i.data_scope_id AND x.active_initial_seal_token IS NOT NULL)
                    AND octet_length(to_jsonb(i)::text) BETWEEN 1 AND 524288) IS TRUE AS valid
            FROM complaint_test_active_seal_intents i
            LEFT JOIN complaint_test_runs r ON r.data_scope_id = i.data_scope_id
            LEFT JOIN complaint_journal_control c ON c.data_scope_id = i.data_scope_id
            CROSS JOIN (SELECT clock_timestamp() AS at) s
            WHERE i.data_scope_id = ?::uuid OR i.object_key LIKE ?::text ORDER BY i.operation_token LIMIT 2
        """.trimIndent()
    }
}

/** Fixed comparison projections only; no caller SQL, provider result or writer authority. */
internal object TestOrdinaryDrainActiveHistorySqlV1 {
    private fun bounded(field: String, maximum: Int, alias: String = field.substringAfter('.')) =
        "CASE WHEN octet_length($field) BETWEEN 1 AND $maximum THEN $field END AS $alias"
    private fun fingerprint(alias: String, maximum: Int) = """CASE WHEN octet_length(to_jsonb($alias)::text) BETWEEN 1 AND $maximum
        THEN sha256(convert_to((to_jsonb($alias) || jsonb_build_object('row_xmin', $alias.xmin::text))::text, 'UTF8')) END"""
    private val projection = listOf(
        "schema_version", "operation_token", "data_scope_id", "test_only", "run_created_at", "implementation_schema", "desired_generation",
        "database_identity", "restore_identity", "writer_generation", "activation_catalog_generation", "accepted_catalog_generation", "catalog_writer_generation",
        "rotation_sequence", "epoch_start", "epoch_end", "epoch_after", "request_owner", "request_token", "requested_at", "capture_owner", "capture_token",
        "captured_at", "charged_storage_bytes", "preparing_fencing_token", "retention_floor", "created_at", "retain_until", "frozen_at",
    ).map { "i.$it" } + listOf("configuration_hash", "journal_configuration_hash", "activation_catalog_hash", "accepted_catalog_hash", "trust_bundle_hash",
        "seal_encoding_hash", "canonical_hash", "wire_hash", "metadata_hash").map { bounded("i.$it", 32) } + listOf(
        "state" to 16, "canonicalizer" to 5, "object_id" to 43, "object_key" to 1024, "routing_key_id" to 64,
        "canonical_bytes" to 65536, "wire_bytes" to 98304, "metadata_bytes" to 512, "checksum_sha256" to 44, "content_type" to 24, "object_lock_mode" to 10,
    ).map { (field, maximum) -> bounded("i.$field", maximum) }
    private fun source(table: String, initial: Boolean) = """
        SELECT ${projection.joinToString(", ")}, '${if (initial) "V26_INITIAL" else "V31_RECURRENT"}'::text AS source,
            ${if (initial) "NULL::uuid AS predecessor_operation_token, NULL::bytea AS predecessor_checkpoint_hash, NULL::bytea AS predecessor_history_hash"
            else "i.predecessor_operation_token, ${bounded("i.predecessor_checkpoint_hash", 32)}, ${bounded("i.predecessor_history_hash", 32)}"},
            ${fingerprint("i", 524288)} AS slot_fingerprint,
            (i.schema_version = 1 AND i.test_only AND i.state = 'WIRE_FROZEN' AND i.canonicalizer = 'kcj-1'
                AND i.charged_storage_bytes = 2097152 AND i.rotation_sequence BETWEEN 1 AND 14
                AND complaint_bytes_match(i.canonical_bytes, i.canonical_hash, 65536) AND complaint_bytes_match(i.wire_bytes, i.wire_hash, 98304)
                AND complaint_bytes_match(i.metadata_bytes, i.metadata_hash, 512)
                AND complaint_finite_times(i.run_created_at, i.requested_at, i.captured_at, i.retention_floor, i.created_at, i.retain_until, i.frozen_at)
                AND octet_length(to_jsonb(i)::text) BETWEEN 1 AND 524288) IS TRUE AS source_valid
        FROM $table i CROSS JOIN e WHERE i.data_scope_id = e.scope OR i.object_key LIKE e.prefix
        ORDER BY i.rotation_sequence, i.operation_token LIMIT 15
    """.trimIndent()
    private val sources = """
        WITH e AS MATERIALIZED (SELECT ?::uuid AS scope, ?::text AS prefix),
        sources AS MATERIALIZED ((${source("complaint_test_active_seal_intents", true)})
            UNION ALL (${source("complaint_test_active_recurrent_seal_intents", false)}))
    """.trimIndent()
    val sourceIdentity = "$sources SELECT slot_fingerprint AS fingerprint FROM sources ORDER BY rotation_sequence, source, operation_token LIMIT 15"
    val archiveIdentity = """
        $sources SELECT ${fingerprint("h", 524288)} AS fingerprint FROM complaint_test_active_checkpoint_history h CROSS JOIN e
        WHERE h.data_scope_id = e.scope OR h.operation_token IN (SELECT operation_token FROM sources)
        ORDER BY h.ordinal, h.operation_token LIMIT 15
    """.trimIndent()
    val noRecurrent = """
        SELECT NOT EXISTS (SELECT 1 FROM complaint_test_active_recurrent_seal_intents
            WHERE data_scope_id = ?::uuid OR object_key LIKE ?::text) AS valid
    """.trimIndent()
    val rows = """
        $sources
        SELECT i.*, h.data_scope_id AS history_scope, h.ordinal AS history_ordinal, h.source AS history_source,
            h.operation_token AS history_token, h.initial_seal_token, h.recurrent_seal_token,
            ${bounded("h.entry_bytes", 16384)}, ${bounded("h.entry_hash", 32)}, ${bounded("h.object_version", 1024)},
            ${bounded("h.verification_bytes", 65536)}, ${bounded("h.verification_hash", 32)},
            ${bounded("h.checkpoint_bytes", 65536)}, ${bounded("h.checkpoint_hash", 32)},
            h.archived_at, h.checkpointed_at, h.charged_storage_bytes AS history_storage_bytes,
            ${fingerprint("h", 524288)} AS archive_fingerprint, r.sealed_at, s.at AS sampled_at,
            (i.source_valid AND r.test_only AND r.state IN ('SEALED', 'PURGING') AND c.test_only
                AND i.run_created_at = r.created_at AND i.configuration_hash = r.configuration_hash
                AND i.activation_catalog_generation = r.activation_catalog_generation AND i.activation_catalog_hash = r.activation_catalog_hash
                AND i.implementation_schema = c.implementation_schema AND i.desired_generation = c.desired_generation
                AND i.configuration_hash = c.desired_configuration_hash AND i.database_identity = c.database_identity
                AND i.restore_identity = c.restore_identity AND i.writer_generation = c.event_writer_generation
                AND i.accepted_catalog_generation = c.accepted_catalog_generation AND i.accepted_catalog_hash = c.accepted_catalog_hash
                AND i.trust_bundle_hash = c.trust_bundle_hash AND i.catalog_writer_generation = c.catalog_writer_generation
                AND h.schema_version = 1 AND h.test_only AND h.charged_storage_bytes = 2097152
                AND complaint_bytes_match(h.entry_bytes, h.entry_hash, 16384) AND complaint_bytes_match(h.verification_bytes, h.verification_hash, 65536)
                AND complaint_bytes_match(h.checkpoint_bytes, h.checkpoint_hash, 65536) AND h.checkpointed_at IS NOT NULL
                AND r.created_at <= r.sealed_at AND r.sealed_at <= s.at
                AND complaint_finite_times(h.archived_at, h.checkpointed_at, r.sealed_at, s.at)
                AND NOT EXISTS (SELECT 1 FROM complaint_journal_scan_runs x WHERE x.data_scope_id = i.data_scope_id
                    AND (x.active_initial_seal_token IS NOT NULL OR x.active_recurrent_seal_token IS NOT NULL))) IS TRUE AS valid
        FROM sources i CROSS JOIN (SELECT clock_timestamp() AS at) s
        LEFT JOIN complaint_test_active_checkpoint_history h ON h.operation_token = i.operation_token
        LEFT JOIN complaint_test_runs r ON r.data_scope_id = i.data_scope_id
        LEFT JOIN complaint_journal_control c ON c.data_scope_id = i.data_scope_id
        ORDER BY i.rotation_sequence, i.source, i.operation_token LIMIT 15
    """.trimIndent()
    private val checkpointColumns = listOf(
        "checkpoint_generation" to "bigint", "checkpoint_fencing_token" to "bigint", "checkpoint_catalog_generation" to "bigint",
        "checkpoint_catalog_hash" to "bytea", "checkpoint_writer_generation" to "uuid", "checkpoint_cutoff_epoch" to "bigint",
        "checkpoint_configuration_hash" to "bytea", "checkpoint_database_identity" to "uuid", "checkpoint_restore_identity" to "uuid",
        "checkpoint_schema" to "integer", "checkpoint_started_at" to "timestamptz", "checkpoint_completed_at" to "timestamptz",
        "checkpoint_object_count" to "bigint", "checkpoint_byte_count" to "bigint", "checkpoint_result" to "text",
        "checkpoint_bytes" to "bytea", "checkpoint_hash" to "bytea",
    )
    val currentLast = """
        WITH e AS MATERIALIZED (SELECT ${checkpointColumns.joinToString(", ") { (name, type) -> "?::$type AS $name" }},
            ?::timestamptz AS verified_at, ?::timestamptz AS retain_until)
        SELECT sha256(convert_to(jsonb_build_array(${TestOrdinaryDrainSqlV1.historyFields})::text, 'UTF8')) AS history_fingerprint,
            (c.test_only AND i.test_only AND h.test_only AND i.rotation_sequence BETWEEN 2 AND 14
                AND i.data_scope_id = c.data_scope_id AND h.data_scope_id = c.data_scope_id AND h.ordinal = i.rotation_sequence
                AND h.source = 'V31_RECURRENT' AND h.recurrent_seal_token = i.operation_token AND h.initial_seal_token IS NULL
                AND c.seal_state = 'SEAL_VERIFIED' AND c.seal_epoch = i.epoch_end AND c.seal_writer_generation = i.writer_generation
                AND c.seal_operation_token = i.operation_token AND c.seal_object_key = i.object_key
                AND c.seal_bytes = i.canonical_bytes AND c.seal_hash = i.canonical_hash AND c.seal_ciphertext_hash = i.wire_hash
                AND c.seal_object_version = h.object_version AND c.seal_verification_bytes = h.verification_bytes AND c.seal_verification_hash = h.verification_hash
                AND c.seal_verified_at = e.verified_at
                AND c.seal_retain_until = e.retain_until
                AND c.checkpoint_bytes = h.checkpoint_bytes AND c.checkpoint_hash = h.checkpoint_hash AND c.checkpoint_completed_at = h.checkpointed_at
                AND c.checkpoint_fencing_token <= c.lease_token
                AND ${checkpointColumns.joinToString(" AND ") { (name, _) -> "c.$name = e.$name" }}) IS TRUE AS valid
        FROM complaint_journal_control c CROSS JOIN e
        JOIN complaint_test_active_recurrent_seal_intents i ON i.data_scope_id = c.data_scope_id
        JOIN complaint_test_active_checkpoint_history h ON h.operation_token = i.operation_token
        WHERE c.data_scope_id = ?::uuid AND i.operation_token = ?::uuid
    """.trimIndent()
}
