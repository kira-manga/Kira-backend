package me.manga.kira.backend.complaint.infrastructure.catalog

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveInitialCheckpointDocumentV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableBindingV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableKindV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableRowV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalObjectRefV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalProfileV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalRunContextV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSealRefV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSealRoleV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainRowsV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestTerminalQuiescenceSourceV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestTerminalQuiescenceTargetV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestTerminalSqlRowV1
import me.manga.kira.backend.complaint.infrastructure.terminal.testOrdinarySealVerificationBytesV1
import me.manga.kira.backend.security.EpochSealFramesV1
import me.manga.kira.backend.security.TestPostTerminalInventoryEntryV1
import me.manga.kira.backend.security.TestTerminalCodecKindV1
import me.manga.kira.backend.security.TestTerminalJsonV1
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

/**
 * One bounded A/V26 initial-empty history and optional permanent B/V29 SETTLED observation.
 * Detached exact comparisons only: no registration, checkpoint, drain, lease or recovery authority.
 * In particular a fresh E recovery uses its own retained process/activation and existing custody;
 * it does not fabricate a registration in order to call the original-bound D materializer.
 */
internal class CatalogTestRunTerminalActiveHistoryV1 private constructor(
    val initial: InitialSeal?,
    private val queueFingerprint: String?,
) {
    val ordinaryPaid: ComplaintCapacityVector = ComplaintCapacityVector.units(ComplaintCapacityCounter.STORAGE_BYTES,
        (if (initial == null) 0L else 2_097_152L) + (if (queueFingerprint == null) 0L else 8_192L))

    fun requireSame(other: CatalogTestRunTerminalActiveHistoryV1) {
        requireTestTerminalCatalog((initial == null) == (other.initial == null) && queueFingerprint == other.queueFingerprint)
        initial?.requireSame(checkNotNull(other.initial))
    }
    fun requireRun(run: CatalogTestRunTerminalRunV1) = requireTestTerminalCatalog(run.sealCount == if (initial == null) 2L else 3L)
    fun requireFrozen(input: CatalogTestRunTerminalFrozenV1) {
        requireTestTerminalCatalog(input.hasActiveHistory == (initial != null))
        initial?.let { requireTestTerminalCatalog(input.manifest().terminalRecord.sealSet.records().first() == it.reference) }
    }
    /** Empty histories append no bytes, keeping the previous two-seal custody digest unchanged. */
    fun commitments(): List<List<String>> = buildList {
        initial?.let { add(listOf("V26", it.slotFingerprint, it.historyFingerprint, it.verificationSha256)) }
        queueFingerprint?.let { add(listOf("V29", it)) }
    }
    fun requirePhysical(jdbc: JdbcTemplate, original: CatalogTestRunTerminalV1) {
        original.requireHistoryRead(jdbc)
        val scope = original.scope
        val journal = original.routing.journalConfiguration
        val seals = jdbc.query(CatalogTestRunTerminalActiveHistorySqlV1.sealIdentity,
            { row, _ -> hash(row, "fingerprint") }, scope, journal.sealTerminalPrefix + "%")
        val queue = jdbc.query(CatalogTestRunTerminalActiveHistorySqlV1.queueIdentity,
            { row, _ -> hash(row, "fingerprint") }, scope, original.process.configurationHashBytes(), terminalCatalogHex(journal.sha256))
        requireTestTerminalCatalog(seals == listOfNotNull(initial?.slotFingerprint) && queue == listOfNotNull(queueFingerprint))
        original.requireHistoryRead(jdbc)
    }
    fun frozenInitial(jdbc: JdbcTemplate, original: CatalogTestRunTerminalV1,
        activation: CatalogTestRunTerminalMutationV1, target: TestTerminalQuiescenceTargetV1): TestTerminalDurableRowV1 {
        original.requireHistoryRead(jdbc)
        val expected = checkNotNull(initial)
        requireTestTerminalCatalog(target.source === TestTerminalQuiescenceSourceV1.V26_ACTIVE_SEAL &&
            target.kind === TestTerminalCodecKindV1.EPOCH_SEAL && target.ordinal == 0 && target.id == expected.reference.sealId &&
            target.startEpoch == 1L && target.endEpoch == 1L && target.objectRef == expected.reference.objectRef)
        val current = checkNotNull(materializeInitial(jdbc, original, activation))
        try { expected.requireSame(current.history); return current.row }
        catch (problem: Throwable) { current.row.close(); throw problem }
    }
    fun requireNative(entry: TestPostTerminalInventoryEntryV1) {
        initial?.takeIf { it.reference.objectRef.objectKey == entry.objectRef.objectKey }?.requireNative(entry)
    }

    internal class InitialSeal(
        val reference: TestTerminalSealRefV1,
        val binding: TestTerminalDurableBindingV1,
        val checkpointCompletedAt: Instant,
        private val verifiedAt: Instant,
        private val lastModified: Instant,
        private val requestedRetainUntil: Instant,
        private val retainUntil: Instant,
        private val wireByteCount: Long,
        internal val slotFingerprint: String,
        internal val historyFingerprint: String,
        internal val verificationSha256: String,
    ) {
        fun requireSame(other: InitialSeal) = requireTestTerminalCatalog(reference == other.reference && binding == other.binding &&
            checkpointCompletedAt == other.checkpointCompletedAt && verifiedAt == other.verifiedAt && lastModified == other.lastModified &&
            requestedRetainUntil == other.requestedRetainUntil && retainUntil == other.retainUntil && wireByteCount == other.wireByteCount &&
            slotFingerprint == other.slotFingerprint && historyFingerprint == other.historyFingerprint && verificationSha256 == other.verificationSha256)
        fun requireNative(entry: TestPostTerminalInventoryEntryV1) = requireTestTerminalCatalog(entry.kind === TestTerminalCodecKindV1.EPOCH_SEAL &&
            entry.writerGeneration == reference.writerGeneration && entry.epochStartInclusive == 1L && entry.epochEndInclusive == 1L &&
            entry.eventId == null && entry.objectRef == reference.objectRef && entry.ciphertextByteCount == wireByteCount &&
            entry.lastModified == lastModified && entry.lastModified <= verifiedAt && entry.requestedRetainUntil == requestedRetainUntil && entry.retainUntil == retainUntil)
        override fun toString(): String = "CatalogTerminalInitialSeal(exact-V26-comparison,no-authority)"
    }

    companion object {
        fun read(jdbc: JdbcTemplate, original: CatalogTestRunTerminalV1, activation: CatalogTestRunTerminalMutationV1): CatalogTestRunTerminalActiveHistoryV1 {
            original.requireHistoryRead(jdbc)
            val initial = materializeInitial(jdbc, original, activation)?.let { it.row.use { _ -> it.history } }
            val queue = jdbc.query(CatalogTestRunTerminalActiveHistorySqlV1.queue, { row, _ ->
                requireTestTerminalCatalog(row.requiredTestActivationBoolean("valid")); hash(row, "fingerprint")
            }, *arguments(original, activation))
            requireTestTerminalCatalog(queue.size <= 1)
            if (initial != null || queue.isNotEmpty()) {
                jdbc.query(CatalogTestRunTerminalActiveHistorySqlV1.globalIdentity, { row, _ ->
                    requireTestTerminalCatalog(row.requiredTestActivationBoolean("valid"))
                    val hash = row.getBytes("configuration_hash")
                    try { original.requirePredecessorGlobal(row.requiredTestActivationLong("desired_generation"), hash) }
                    finally { hash?.fill(0) }
                }).single()
                // For the genuine child only, preserve D's complete original A slot fingerprint as
                // well as its reference. The fresh recovery has no old D/registration authority.
                if (initial != null) original.requirePredecessorActiveHistory(jdbc)
            }
            original.requireHistoryRead(jdbc)
            return CatalogTestRunTerminalActiveHistoryV1(initial, queue.singleOrNull())
        }
        private class Materialized(val history: InitialSeal, val row: TestTerminalDurableRowV1)
        private fun materializeInitial(jdbc: JdbcTemplate, original: CatalogTestRunTerminalV1,
            activation: CatalogTestRunTerminalMutationV1): Materialized? {
            val allocated = ArrayList<Materialized>(1)
            try {
                val rows = jdbc.query(CatalogTestRunTerminalActiveHistorySqlV1.initialSeal, { row, _ ->
                    requireTestTerminalCatalog(allocated.isEmpty())
                    readInitial(row, original, activation).also(allocated::add)
                }, *arguments(original, activation), original.routing.journalConfiguration.sealTerminalPrefix + "%")
                requireTestTerminalCatalog(rows.size <= 1)
                return rows.singleOrNull().also { allocated.clear() }
            } finally { allocated.forEach { it.row.close() } }
        }
        private fun readInitial(row: ResultSet, original: CatalogTestRunTerminalV1, activation: CatalogTestRunTerminalMutationV1): Materialized {
            val journal = original.routing.journalConfiguration
            val context = TestTerminalRunContextV1(original.scope.toString(), activation.generation, checkNotNull(activation.head).envelopeSha256,
                HexFormat.of().formatHex(original.process.configurationHashBytes()), TestTerminalProfileV1.encodingSha256)
            requireTestTerminalCatalog(row.requiredTestActivationBoolean("valid") && hash(row, "seal_encoding_hash") == context.terminalEncodingSha256)
            val at = checkNotNull(row.getTimestamp("sampled_at")).toInstant()
            val token = checkNotNull(row.getObject("operation_token", UUID::class.java))
            val binding = TestTerminalDurableBindingV1(token.toString(), context, journal.sha256, TestTerminalDurableKindV1.EPOCH_SEAL, 0,
                checkNotNull(row.getString("object_id")), checkNotNull(row.getString("object_key")), checkNotNull(row.getString("routing_key_id")),
                original.writer, 1, 1, row.requiredTestActivationLong("preparing_fencing_token"),
                checkNotNull(row.getTimestamp("retention_floor")).toInstant(), checkNotNull(row.getTimestamp("created_at")).toInstant())
            requireTestTerminalCatalog(binding.retentionFloor >= original.acquisition.retention.lastPreRunRestoreHorizon.plusSeconds(31 * 86400L))
            val frozen = TestTerminalSqlRowV1.restore(row, binding, at)
            try {
                val canonical = frozen.canonicalBytes()
                val seal = try { TestTerminalJsonV1(journal).epochSeal(canonical) } finally { canonical.fill(0) }
                val frame = EpochSealFramesV1.frame(listOf(EpochSealFramesV1.DOMAIN, "1", "manifest", original.writer,
                    journal.ordinaryPrefix, "TEST", original.scope.toString(), "1", "1", "0"))
                val framedBytes = frame.size.toLong()
                val emptyRoot = try { Sha256.hex(frame) } finally { frame.fill(0) }
                requireTestTerminalCatalog(seal.sealId == binding.objectId && seal.writerGeneration == original.writer && seal.dataScopeId == original.scope.toString() &&
                    seal.epochStartInclusive == 1L && seal.epochEndInclusive == 1L && seal.precedingSealSha256.isEmpty() &&
                    seal.preparingFencingToken == binding.preparingFencingToken && seal.eventCount == 0L && seal.eventManifestSha256 == emptyRoot)
                val version = checkNotNull(row.getString("seal_object_version"))
                val checkpoint = checkNotNull(row.getBytes("checkpoint_bytes"))
                try {
                    requireTestTerminalCatalog(Sha256.hex(checkpoint) == hash(row, "checkpoint_hash"))
                    val passes = boundedObject(checkpoint).getValue("passes").jsonArray
                    requireTestTerminalCatalog(passes.size == 2)
                    fun pass(index: Int): TestActiveInitialCheckpointDocumentV1.Pass {
                        val value = passes[index].jsonObject
                        return TestActiveInitialCheckpointDocumentV1.Pass(Instant.parse(value.getValue("startedAt").jsonPrimitive.content),
                            Instant.parse(value.getValue("completedAt").jsonPrimitive.content))
                    }
                    val document = TestActiveInitialCheckpointDocumentV1(original.scope.toString(), original.process.desiredGeneration,
                        row.requiredTestActivationLong("checkpoint_fencing_token"), context.configurationSha256, journal.sha256,
                        original.process.databaseIdentity.toString(), original.process.restoreIdentity.toString(), activation.generation, context.activationCatalogSha256,
                        original.process.catalogReadback.currentTrustBundleSha256, original.process.catalogActivation.catalogWriterGenerationId,
                        original.writer, token.toString(), binding.objectKey, version, frozen.canonicalSha256, checkNotNull(frozen.wireSha256),
                        emptyRoot, framedBytes, pass(0), pass(1))
                    val expected = document.canonicalBytes()
                    try { requireTestTerminalCatalog(checkpoint.contentEquals(expected)) } finally { expected.fill(0) }
                    requireTestTerminalCatalog(document.startedAt == row.getTimestamp("checkpoint_started_at")?.toInstant() &&
                        document.completedAt == row.getTimestamp("checkpoint_completed_at")?.toInstant())
                } finally { checkpoint.fill(0) }
                val verifiedAt = checkNotNull(row.getTimestamp("seal_verified_at")).toInstant()
                val retained = checkNotNull(row.getTimestamp("seal_retain_until")).toInstant()
                val verification = checkNotNull(row.getBytes("seal_verification_bytes"))
                val lastModified = try {
                    val modified = Instant.parse(boundedObject(verification).getValue("lastModified").jsonPrimitive.content)
                    requireTestTerminalCatalog(modified.nano == 0 && modified.epochSecond in 0..253_402_300_799L && modified <= verifiedAt && verifiedAt <= at)
                    val expected = testOrdinarySealVerificationBytesV1(frozen, version, modified, retained, verifiedAt)
                    try { requireTestTerminalCatalog(verification.contentEquals(expected) && Sha256.hex(verification) == hash(row, "seal_verification_hash")) }
                    finally { expected.fill(0) }
                    modified
                } finally { verification.fill(0) }
                val wire = checkNotNull(frozen.wireBytes())
                val wireBytes = try { wire.size.toLong() } finally { wire.fill(0) }
                val history = InitialSeal(TestTerminalSealRefV1(TestTerminalSealRoleV1.ORDINARY, original.writer, 1, 1,
                    binding.objectId, "", TestTerminalObjectRefV1(binding.objectKey, version, checkNotNull(frozen.wireSha256), frozen.canonicalSha256)),
                    binding, checkNotNull(row.getTimestamp("checkpoint_completed_at")).toInstant(), verifiedAt, lastModified,
                    checkNotNull(frozen.retainUntil), retained, wireBytes, hash(row, "slot_fingerprint"), hash(row, "history_fingerprint"), hash(row, "seal_verification_hash"))
                return Materialized(history, frozen)
            } catch (problem: Throwable) { frozen.close(); throw problem }
        }
        private fun arguments(original: CatalogTestRunTerminalV1, activation: CatalogTestRunTerminalMutationV1): Array<Any?> = arrayOf(
            original.scope, original.process.configurationHashBytes(), terminalCatalogHex(original.routing.journalConfiguration.sha256),
            original.process.desiredGeneration, original.process.implementationSchema, original.process.databaseIdentity, original.process.restoreIdentity,
            UUID.fromString(original.writer), activation.generation, terminalCatalogHex(checkNotNull(activation.head).envelopeSha256),
            Timestamp.from(checkNotNull(checkNotNull(activation.completed).projectedAt)), UUID.fromString(original.process.catalogActivation.catalogWriterGenerationId),
            terminalCatalogHex(original.process.catalogReadback.currentTrustBundleSha256))
        private fun hash(row: ResultSet, name: String): String = TestOrdinaryDrainRowsV1.hash(row, name)
        private fun boundedObject(bytes: ByteArray): JsonObject {
            requireTestTerminalCatalog(bytes.size in 1..TestActiveInitialCheckpointDocumentV1.MAX_BYTES)
            jsonFactory.createParser(bytes).use { parser ->
                var tokens = 0
                while (parser.nextToken() != null) requireTestTerminalCatalog(++tokens <= 512)
            }
            return CanonicalJson.json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        }
        private val jsonFactory = JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(4).maxStringLength(1024).maxNameLength(128).maxNumberLength(19).build()).build()
    }
    override fun toString(): String = "CatalogTestRunTerminalActiveHistoryV1(bounded-A-and-permanent-B,no-authority)"
}
