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
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveFirstCutIdentityV1
import me.manga.kira.backend.security.EpochSealFramesV1
import me.manga.kira.backend.security.TestTerminalJsonV1
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

/**
 * Exactly one completed A initial-empty V26/V14 history, never a current authority or an adopted
 * terminal sidecar. Callers first acquire their own current controls/lease. Each use rereads the
 * unfiltered bounded V26 relation and exact retained bytes; native verification is separate.
 */
internal class TestOrdinaryDrainActiveHistoryV1 private constructor(
    val reference: TestTerminalSealRefV1,
    val operationToken: UUID,
    val checkpointCompletedAt: Instant,
    val verifiedAt: Instant,
    val retainUntil: Instant,
    private val slotFingerprint: String,
    private val historyFingerprint: String,
    private val verificationSha256: String,
) {
    fun requireSame(other: TestOrdinaryDrainActiveHistoryV1?) {
        requireDrain(other != null && reference == other.reference && operationToken == other.operationToken &&
            checkpointCompletedAt == other.checkpointCompletedAt && verifiedAt == other.verifiedAt && retainUntil == other.retainUntil &&
            slotFingerprint == other.slotFingerprint && historyFingerprint == other.historyFingerprint && verificationSha256 == other.verificationSha256)
    }

    /** Fresh bounded frozen-row view from V26; no INSERT/UPDATE, reprice or V21 provenance. */
    fun frozen(jdbc: JdbcTemplate, original: TestRunOrdinaryDrainV1): TestTerminalDurableRowV1 {
        val value = checkNotNull(materialize(jdbc, original.registration))
        try { requireSame(value.history); return value.row }
        catch (problem: Throwable) { value.row.close(); throw problem }
    }

    fun requireNative(row: TestTerminalDurableRowV1, proof: TestOrdinarySealProofV1, now: Instant) {
        requireDrain(proof.version == reference.objectRef.objectVersion && proof.retainUntil == retainUntil &&
            !proof.verifiedAt.isBefore(verifiedAt) && !proof.verifiedAt.isAfter(now) && proof.retainUntil.isAfter(now) &&
            row.binding.operationToken == operationToken.toString() && row.binding.objectKey == reference.objectRef.objectKey &&
            row.canonicalSha256 == reference.objectRef.canonicalSha256 && row.wireSha256 == reference.objectRef.ciphertextSha256)
        val bytes = proof.canonicalBytes(row, verifiedAt)
        try { requireDrain(Sha256.hex(bytes) == verificationSha256) } finally { bytes.fill(0) }
    }

    override fun toString(): String = "TestOrdinaryDrainActiveHistoryV1(V26-comparison-only,no-current-authority,redacted)"

    companion object {
        fun read(jdbc: JdbcTemplate, original: TestRunOrdinaryDrainV1): TestOrdinaryDrainActiveHistoryV1? =
            materialize(jdbc, original.registration)?.let { value -> value.row.use { value.history } }

        /** The existing sealed-audit owner compares A even on already-closed audit replay. */
        fun beforeClosure(jdbc: JdbcTemplate, original: TestRunSealingV1): TestOrdinaryDrainActiveHistoryV1? {
            original.requirePersistence(original.coordinator.ownership, jdbc)
            requireSealing(original.path === PersistencePhasePath.COMPLAINT_TEST_RUN_SEALED_AUDIT)
            return materialize(jdbc, original.registration)?.let { value -> value.row.use { value.history } }
        }

        fun requireCurrent(jdbc: JdbcTemplate, original: TestRunOrdinaryDrainV1, expected: TestOrdinaryDrainActiveHistoryV1?) {
            val current = read(jdbc, original)
            requireDrain((expected == null) == (current == null))
            expected?.requireSame(current)
        }

        private class Materialized(val history: TestOrdinaryDrainActiveHistoryV1, val row: TestTerminalDurableRowV1)

        private fun materialize(jdbc: JdbcTemplate, registration: ComplaintTestNamespaceRegistrationV1): Materialized? {
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
            } finally { allocated.forEach { it.row.close() } }
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
                val history = TestOrdinaryDrainActiveHistoryV1(TestTerminalSealRefV1(TestTerminalSealRoleV1.ORDINARY, writer, 1, 1,
                    binding.objectId, "", TestTerminalObjectRefV1(binding.objectKey, version, checkNotNull(frozen.wireSha256), frozen.canonicalSha256)),
                    token, checkNotNull(row.getTimestamp("checkpoint_completed_at")).toInstant(), checkNotNull(row.getTimestamp("seal_verified_at")).toInstant(),
                    checkNotNull(row.getTimestamp("seal_retain_until")).toInstant(), hash(row, "slot_fingerprint"), hash(row, "history_fingerprint"),
                    hash(row, "seal_verification_hash"))
                return Materialized(history, frozen)
            } catch (problem: Throwable) { frozen.close(); throw problem }
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
