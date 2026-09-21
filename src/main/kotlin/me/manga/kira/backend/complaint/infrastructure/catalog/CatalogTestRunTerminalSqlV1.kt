package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.complaint.infrastructure.OwnerDeletePersistenceSql
import me.manga.kira.backend.complaint.infrastructure.terminal.TestInstallationManifestSqlV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinarySealSqlV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunPurgeSqlV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestTerminalEpochSealSqlV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestTerminalQuiescenceSqlV1

/** Closed terminal statements only. No caller chooses a table, predicate, lock order or charge. */
internal object CatalogTestRunTerminalSqlV1 {
    private const val GLOBAL = "'00000000-0000-0000-0000-000000000000'::uuid"
    private val expected = """
        WITH e AS MATERIALIZED (SELECT ?::uuid AS scope, ?::bigint AS desired_generation, ?::integer AS implementation_schema,
            ?::bytea AS configuration_hash, ?::uuid AS database_identity, ?::uuid AS restore_identity,
            ?::uuid AS event_writer, ?::uuid AS catalog_writer, ?::bytea AS trust_hash, ?::bigint AS maximum_generation)
    """.trimIndent()
    // Only the global catalog head/pending and this operation's global lease may move. The scope's
    // complete lease/rotation/checkpoint/seal/scan row remains bound to its original activation.
    private val core = """
        (CASE WHEN c.data_scope_id = $GLOBAL THEN
            (to_jsonb(c) - ARRAY['lease_owner','lease_token','lease_expires_at','updated_at']) ||
                jsonb_build_object('accepted_catalog_generation', r.activation_catalog_generation,
                    'accepted_catalog_hash', r.activation_catalog_hash, 'pending_projection_token', NULL::uuid)
            ELSE to_jsonb(c) END)::text
    """.trimIndent()
    private val controlValid = """
        c.maintenance_closed AND c.creation_closed AND c.publication_epoch > 0
        AND c.database_identity = e.database_identity AND c.restore_identity = e.restore_identity
        AND c.event_writer_generation = e.event_writer AND c.catalog_writer_generation = e.catalog_writer AND c.trust_bundle_hash = e.trust_hash
        AND c.accepted_catalog_generation BETWEEN 2 AND e.maximum_generation AND complaint_digest_valid(c.accepted_catalog_hash)
        AND c.lease_token >= 0 AND complaint_finite_times(c.updated_at, c.lease_expires_at, c.retention_lease_expires_at)
        AND ((c.lease_owner IS NULL AND c.lease_expires_at IS NULL)
            OR (complaint_is_v4(c.lease_owner) AND c.lease_expires_at IS NOT NULL))
        AND ((c.data_scope_id = e.scope AND c.test_only AND c.implementation_schema = e.implementation_schema
                AND c.desired_generation = e.desired_generation AND c.desired_configuration_hash = e.configuration_hash
                AND c.accepted_catalog_generation = r.activation_catalog_generation AND c.accepted_catalog_hash = r.activation_catalog_hash
                AND c.pending_projection_token IS NULL AND c.lease_owner IS NULL AND c.lease_expires_at IS NULL)
            OR (c.data_scope_id = $GLOBAL AND NOT c.test_only AND c.implementation_schema = 1 AND c.desired_generation > 0))
        AND r.test_only AND r.configuration_hash = e.configuration_hash AND r.state IN ('SEALED', 'PURGING')
        AND octet_length($core) BETWEEN 1 AND 524288
    """.trimIndent()
    private val controls = """
        $expected
        SELECT ($controlValid) IS TRUE AS valid, c.data_scope_id, c.accepted_catalog_generation,
            CASE WHEN octet_length(c.accepted_catalog_hash) = 32 THEN c.accepted_catalog_hash END AS accepted_catalog_hash,
            c.pending_projection_token, c.lease_token,
            CASE WHEN ($controlValid) IS TRUE THEN $core END AS core_preimage,
            CASE WHEN ($controlValid) IS TRUE THEN sha256(convert_to($core, 'UTF8')) END AS core_hash
        FROM complaint_journal_control c CROSS JOIN e JOIN complaint_test_runs r ON r.data_scope_id = e.scope
        WHERE c.data_scope_id = ?::uuid
    """.trimIndent()
    val lockControl = "$controls FOR UPDATE OF c"
    val readControl = controls

    // Raw history frames remain exactly the established V1/V2+V3 local comparison contract.
    // A separate unfiltered suffix read below rejects EVERY extra row, not merely valid terminal rows.
    val lockActivationHistory = CatalogTestRunActivationSqlV1.lockRecoveryRegistrationHistory.replace(
        "FROM complaint_catalog_mutations m ORDER BY m.successor_generation",
        "FROM complaint_catalog_mutations m WHERE m.successor_generation <= ?::bigint ORDER BY m.successor_generation",
    )
    val readActivationHistory = lockActivationHistory.removeSuffix("\nFOR UPDATE")

    private val noSignature = "m.signer_one_signature IS NULL AND m.envelope_bytes IS NULL AND m.envelope_hash IS NULL"
    private val signature = "octet_length(m.signer_one_signature) = 384 AND complaint_bytes_match(m.envelope_bytes, m.envelope_hash, 131072)"
    private val completed = """
        m.state = 'COMPLETED' AND $signature AND complaint_opaque_valid(m.object_version, 1024) AND m.object_version <> 'null'
        AND m.completed_at IS NOT NULL AND m.completed_at >= m.created_at AND m.completed_at <= clock_timestamp()
        AND m.retain_until IS NOT NULL AND m.retain_until > clock_timestamp()
        AND complaint_bytes_match(m.primary_evidence_bytes, m.primary_evidence_hash, 65536)
        AND complaint_bytes_match(m.replica_evidence_bytes, m.replica_evidence_hash, 65536)
        AND (m.projected_at IS NULL OR (m.projected_at >= m.completed_at AND m.projected_at <= clock_timestamp()))
    """.trimIndent()
    private val valid = """
        m.operation_type IN ('TEST_RUN_ACTIVATION', 'TEST_RUN_TERMINAL') AND m.test_only AND complaint_scope_valid(m.data_scope_id, m.test_only)
        AND complaint_is_v4(m.operation_token) AND complaint_is_v4(m.catalog_writer_generation)
        AND m.successor_generation BETWEEN 2 AND 65536 AND m.predecessor_generation = m.successor_generation - 1
        AND complaint_digest_valid(m.predecessor_hash) AND m.canonicalizer = 'kcj-1' AND m.signer_policy = 'SINGLE'
        AND complaint_ascii_valid(m.signer_one_id, 128) AND complaint_ascii_valid(m.signer_one_algorithm, 128)
        AND m.signer_two_id IS NULL AND m.signer_two_algorithm IS NULL AND m.signer_two_signature IS NULL
        AND complaint_bytes_match(m.approval_bytes, m.approval_hash, 4096) AND complaint_bytes_match(m.unsigned_bytes, m.unsigned_hash, 131072)
        AND complaint_ascii_valid(m.object_key, 1024) AND complaint_finite_times(m.created_at, m.completed_at, m.projected_at, m.retain_until)
        AND m.created_at >= '1970-01-01T00:00:00Z'::timestamptz AND m.created_at <= clock_timestamp()
        AND ((m.state = 'PREPARED' AND m.operation_type = 'TEST_RUN_TERMINAL' AND (($noSignature) OR ($signature))
                AND m.object_version IS NULL AND m.retain_until IS NULL AND m.completed_at IS NULL AND m.projected_at IS NULL
                AND m.primary_evidence_bytes IS NULL AND m.primary_evidence_hash IS NULL AND m.replica_evidence_bytes IS NULL AND m.replica_evidence_hash IS NULL)
            OR (($completed) AND (m.operation_type = 'TEST_RUN_TERMINAL' OR m.projected_at IS NOT NULL)))
    """.trimIndent()
    private val mutation = """
        SELECT ($valid) IS TRUE AS valid, (m.state = 'COMPLETED') AS completed,
            m.operation_token, m.data_scope_id, m.operation_type, m.predecessor_generation, m.successor_generation, m.catalog_writer_generation,
            m.created_at, m.completed_at, m.projected_at, m.retain_until,
            CASE WHEN octet_length(m.predecessor_hash) = 32 THEN m.predecessor_hash END AS predecessor_hash,
            CASE WHEN octet_length(m.approval_bytes) BETWEEN 1 AND 4096 THEN m.approval_bytes END AS approval_bytes,
            CASE WHEN octet_length(m.approval_hash) = 32 THEN m.approval_hash END AS approval_hash,
            CASE WHEN octet_length(m.unsigned_bytes) BETWEEN 1 AND 131072 THEN m.unsigned_bytes END AS unsigned_bytes,
            CASE WHEN octet_length(m.unsigned_hash) = 32 THEN m.unsigned_hash END AS unsigned_hash,
            CASE WHEN octet_length(m.signer_one_id) BETWEEN 1 AND 128 THEN m.signer_one_id END AS signer_one_id,
            CASE WHEN octet_length(m.signer_one_algorithm) BETWEEN 1 AND 128 THEN m.signer_one_algorithm END AS signer_one_algorithm,
            CASE WHEN octet_length(m.signer_one_signature) = 384 THEN m.signer_one_signature END AS signature_bytes,
            CASE WHEN octet_length(m.envelope_bytes) BETWEEN 1 AND 131072 THEN m.envelope_bytes END AS envelope_bytes,
            CASE WHEN octet_length(m.envelope_hash) = 32 THEN m.envelope_hash END AS envelope_hash,
            CASE WHEN octet_length(m.object_key) BETWEEN 1 AND 1024 THEN m.object_key END AS object_key,
            CASE WHEN octet_length(m.object_version) BETWEEN 1 AND 1024 THEN m.object_version END AS object_version,
            CASE WHEN octet_length(m.primary_evidence_bytes) BETWEEN 1 AND 65536 THEN m.primary_evidence_bytes END AS primary_evidence_bytes,
            CASE WHEN octet_length(m.primary_evidence_hash) = 32 THEN m.primary_evidence_hash END AS primary_evidence_hash,
            CASE WHEN octet_length(m.replica_evidence_bytes) BETWEEN 1 AND 65536 THEN m.replica_evidence_bytes END AS replica_evidence_bytes,
            CASE WHEN octet_length(m.replica_evidence_hash) = 32 THEN m.replica_evidence_hash END AS replica_evidence_hash
        FROM complaint_catalog_mutations m
    """.trimIndent()
    val lockActivation = "$mutation WHERE m.successor_generation = ?::bigint FOR UPDATE"
    val lockSuffix = "$mutation WHERE m.successor_generation > ?::bigint ORDER BY m.successor_generation LIMIT 2 FOR UPDATE"
    val readActivation = lockActivation.removeSuffix(" FOR UPDATE")
    val readSuffix = lockSuffix.removeSuffix(" FOR UPDATE")

    // Closed substitutions of the existing exact fourteen-field tuple mechanics, never activation authority.
    val insertPrepared = CatalogTestRunActivationSqlV1.insertPrepared.replace("'TEST_RUN_ACTIVATION'", "'TEST_RUN_TERMINAL'")
    val persistSignature = CatalogTestRunActivationSqlV1.persistSignature.replace("'TEST_RUN_ACTIVATION'", "'TEST_RUN_TERMINAL'")
    val complete = CatalogTestRunActivationSqlV1.complete.replace("'TEST_RUN_ACTIVATION'", "'TEST_RUN_TERMINAL'")
    val markPending = CatalogTestRunActivationSqlV1.markPending
    val acquireLease = CatalogTestRunActivationSqlV1.acquireLease
    val currentLease = CatalogTestRunActivationSqlV1.currentLease
    val releaseLease = """
        UPDATE complaint_journal_control SET lease_owner = NULL, lease_expires_at = NULL, updated_at = clock_timestamp()
        WHERE data_scope_id = $GLOBAL AND lease_owner = ?::uuid AND lease_token = ?::bigint AND lease_expires_at = ?::timestamptz
            AND lease_expires_at > clock_timestamp() AND maintenance_closed AND creation_closed
    """.trimIndent()
}

/**
 * Read-only prerequisite statements, deliberately separate from the catalog-lock phase. The
 * two controls precede key-sorted existing P/L pairs, counters and the run. Every later read is
 * nonlocking. Physical hashes are local comparison commitments, never denial/native authority.
 */
internal object CatalogTestRunTerminalPreflightSqlV1 {
    val publicationPage = TestOrdinarySealSqlV1.manifestPage
    val lockPublication = """
        SELECT ${OwnerDeletePersistenceSql.PUBLICATION_COLUMNS},
            CASE WHEN octet_length(to_jsonb(p)::text) BETWEEN 1 AND 524288
                THEN sha256(convert_to(jsonb_build_array(p.xmin::text, to_jsonb(p))::text, 'UTF8')) END AS physical_hash
        FROM complaint_journal_publications p WHERE event_id = ?::text FOR UPDATE
    """.trimIndent()
    val readPublication = lockPublication.removeSuffix(" FOR UPDATE")
    val lockRecovery = """
        SELECT event_id, data_scope_id, test_only, publication_ref, state, accounting_version, created_at, converted_at,
            CASE WHEN complaint_vector_valid(reserved_amounts) THEN reserved_amounts END AS reserved_amounts,
            CASE WHEN complaint_vector_valid(converted_amounts) THEN converted_amounts END AS converted_amounts,
            complaint_finite_times(created_at, converted_at) AS finite,
            CASE WHEN octet_length(to_jsonb(l)::text) BETWEEN 1 AND 16384
                THEN sha256(convert_to(jsonb_build_array(l.xmin::text, to_jsonb(l))::text, 'UTF8')) END AS physical_hash
        FROM complaint_recovery_capacity_reservations l WHERE event_id = ?::text
        ORDER BY data_scope_id LIMIT 2 FOR UPDATE
    """.trimIndent()
    val readRecovery = lockRecovery.removeSuffix(" FOR UPDATE")
    val control = TestTerminalQuiescenceSqlV1.control.removeSuffix(" FOR UPDATE")
    val controlWithActiveHistory = TestTerminalQuiescenceSqlV1.controlWithActiveHistory.removeSuffix(" FOR UPDATE OF c")
    val appliedPage = """
        SELECT a.event_id, a.data_scope_id, a.test_only, a.writer_generation, a.journal_epoch, a.event_kind, a.target_count,
            CASE WHEN octet_length(a.object_key) BETWEEN 1 AND 1024 THEN a.object_key END AS object_key,
            CASE WHEN octet_length(a.object_version) BETWEEN 1 AND 1024 THEN a.object_version END AS object_version,
            CASE WHEN octet_length(a.ciphertext_hash) = 32 THEN a.ciphertext_hash END AS ciphertext_hash,
            a.applied_at, isfinite(a.applied_at) AS finite,
            CASE WHEN octet_length(to_jsonb(a)::text) BETWEEN 1 AND 16384
                THEN sha256(convert_to(jsonb_build_array(a.xmin::text, to_jsonb(a))::text, 'UTF8')) END AS physical_hash
        FROM complaint_deletion_journal_applied a
        WHERE (a.data_scope_id = ?::uuid OR a.object_key LIKE ?::text OR a.object_key LIKE ?::text)
            AND (?::text IS NULL OR (a.object_key COLLATE "C", a.object_version COLLATE "C") > (?::text COLLATE "C", ?::text COLLATE "C"))
        ORDER BY a.object_key COLLATE "C", a.object_version COLLATE "C" LIMIT 16
    """.trimIndent()
    private const val SIDECAR_HASH = """SELECT CASE WHEN octet_length(to_jsonb(i)::text) BETWEEN 1 AND 524288
        THEN sha256(convert_to(jsonb_build_array(i.xmin::text, to_jsonb(i))::text, 'UTF8')) END AS physical_hash, i.operation_token"""
    val manifest = TestInstallationManifestSqlV1.sidecar.removeSuffix(" FOR UPDATE").replace("SELECT i.operation_token", SIDECAR_HASH)
    val purge = TestRunPurgeSqlV1.sidecar.removeSuffix(" FOR UPDATE").replace("SELECT i.operation_token", SIDECAR_HASH)
    val ordinarySeal = TestTerminalEpochSealSqlV1.ordinarySidecar.removeSuffix(" FOR UPDATE").replace("SELECT i.operation_token", SIDECAR_HASH)
    val terminalSeal = TestTerminalEpochSealSqlV1.sidecar.removeSuffix(" FOR UPDATE").replace("SELECT i.operation_token", SIDECAR_HASH)
    val sidecarIdentity = """
        SELECT CASE WHEN octet_length(to_jsonb(i)::text) BETWEEN 1 AND 524288
            THEN sha256(convert_to(jsonb_build_array(i.xmin::text, to_jsonb(i))::text, 'UTF8')) END AS physical_hash
        FROM complaint_test_terminal_intents i
        WHERE i.data_scope_id = ?::uuid AND i.object_kind = ?::text AND i.object_ordinal = ?::integer
    """.trimIndent()
}
