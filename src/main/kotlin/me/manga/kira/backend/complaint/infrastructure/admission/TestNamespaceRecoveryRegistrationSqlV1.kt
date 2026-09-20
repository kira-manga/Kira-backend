package me.manga.kira.backend.complaint.infrastructure.admission

/** Fixed lock/read-only cold TEST admission. No lease, accounting, PROJECT or admission-gate write. */
internal object TestNamespaceRecoveryRegistrationSqlV1 {
    private val controls = """
        WITH e AS MATERIALIZED (SELECT ?::uuid AS scope, ?::bigint AS desired_generation, ?::integer AS implementation_schema,
            ?::bytea AS configuration_hash, ?::uuid AS database_identity, ?::uuid AS restore_identity,
            ?::uuid AS event_writer, ?::uuid AS catalog_writer, ?::bytea AS trust_hash, ?::bigint AS maximum_generation)
        SELECT (c.maintenance_closed AND c.creation_closed AND c.pending_projection_token IS NULL
            AND c.publication_epoch > 0 AND c.database_identity = e.database_identity AND c.restore_identity = e.restore_identity
            AND c.event_writer_generation = e.event_writer AND c.catalog_writer_generation = e.catalog_writer AND c.trust_bundle_hash = e.trust_hash
            AND c.accepted_catalog_generation BETWEEN 2 AND e.maximum_generation AND complaint_digest_valid(c.accepted_catalog_hash)
            AND c.lease_token >= 0 AND ((c.lease_owner IS NULL AND c.lease_expires_at IS NULL)
                OR (complaint_is_v4(c.lease_owner) AND isfinite(c.lease_expires_at) AND c.lease_expires_at <= clock_timestamp()))
            AND isfinite(c.updated_at) AND octet_length(to_jsonb(c)::text) BETWEEN 1 AND 524288
            AND ((c.data_scope_id = e.scope AND c.test_only AND c.implementation_schema = e.implementation_schema
                    AND c.desired_generation = e.desired_generation AND c.desired_configuration_hash = e.configuration_hash)
                OR (c.data_scope_id = '00000000-0000-0000-0000-000000000000'::uuid AND NOT c.test_only
                    AND c.implementation_schema = 1 AND c.desired_generation > 0))) IS TRUE AS valid,
            c.data_scope_id, c.accepted_catalog_generation, c.accepted_catalog_hash, c.lease_token,
            CASE WHEN octet_length(to_jsonb(c)::text) BETWEEN 1 AND 524288 THEN sha256(convert_to(to_jsonb(c)::text, 'UTF8')) END AS fingerprint
        FROM complaint_journal_control c CROSS JOIN e WHERE c.data_scope_id = ?::uuid
    """.trimIndent()
    val lockControl = "$controls FOR UPDATE OF c"

    /** Bounded ONE activation tail only. Historical rows use the existing fixed-width fingerprint stream. */
    val tail = """
        SELECT (m.operation_type = 'TEST_RUN_ACTIVATION' AND m.test_only AND complaint_is_v4(m.operation_token)
            AND m.canonicalizer = 'kcj-1' AND m.signer_policy = 'SINGLE' AND m.state = 'COMPLETED'
            AND m.predecessor_generation = m.successor_generation - 1 AND complaint_digest_valid(m.predecessor_hash)
            AND complaint_bytes_match(m.unsigned_bytes, m.unsigned_hash, 131072)
            AND complaint_bytes_match(m.envelope_bytes, m.envelope_hash, 131072)
            AND complaint_bytes_match(m.approval_bytes, m.approval_hash, 4096)
            AND complaint_ascii_valid(m.signer_one_id, 128) AND complaint_ascii_valid(m.signer_one_algorithm, 128)
            AND octet_length(m.signer_one_signature) = 384
            AND m.signer_two_id IS NULL AND m.signer_two_algorithm IS NULL AND m.signer_two_signature IS NULL
            AND complaint_ascii_valid(m.object_key, 1024) AND complaint_opaque_valid(m.object_version, 1024) AND m.object_version <> 'null'
            AND complaint_finite_times(m.created_at, m.completed_at, m.projected_at, m.retain_until)
            AND m.created_at >= '1970-01-01T00:00:00Z'::timestamptz AND m.completed_at >= m.created_at
            AND m.projected_at >= m.completed_at AND m.projected_at <= clock_timestamp() AND m.retain_until > clock_timestamp()
            AND complaint_bytes_match(m.primary_evidence_bytes, m.primary_evidence_hash, 65536)
            AND complaint_bytes_match(m.replica_evidence_bytes, m.replica_evidence_hash, 65536)) IS TRUE AS valid,
            true AS completed, m.operation_token, m.successor_generation, m.object_key, m.object_version,
            m.created_at, m.completed_at, m.projected_at, m.retain_until, m.signer_one_id, m.signer_one_algorithm,
            CASE WHEN octet_length(m.signer_one_signature) = 384 THEN m.signer_one_signature END AS signature_bytes,
            CASE WHEN octet_length(m.approval_bytes) BETWEEN 1 AND 4096 THEN m.approval_bytes END AS approval_bytes,
            CASE WHEN octet_length(m.unsigned_bytes) BETWEEN 1 AND 131072 THEN m.unsigned_bytes END AS unsigned_bytes,
            CASE WHEN octet_length(m.unsigned_hash) = 32 THEN m.unsigned_hash END AS unsigned_hash,
            CASE WHEN octet_length(m.envelope_bytes) BETWEEN 1 AND 131072 THEN m.envelope_bytes END AS envelope_bytes,
            CASE WHEN octet_length(m.envelope_hash) = 32 THEN m.envelope_hash END AS envelope_hash,
            CASE WHEN octet_length(m.primary_evidence_bytes) BETWEEN 1 AND 65536 THEN m.primary_evidence_bytes END AS primary_evidence_bytes,
            CASE WHEN octet_length(m.primary_evidence_hash) = 32 THEN m.primary_evidence_hash END AS primary_evidence_hash,
            CASE WHEN octet_length(m.replica_evidence_bytes) BETWEEN 1 AND 65536 THEN m.replica_evidence_bytes END AS replica_evidence_bytes,
            CASE WHEN octet_length(m.replica_evidence_hash) = 32 THEN m.replica_evidence_hash END AS replica_evidence_hash
        FROM complaint_catalog_mutations m WHERE m.data_scope_id = ?::uuid AND m.successor_generation = ?::bigint
            AND m.envelope_hash = ?::bytea AND m.catalog_writer_generation = ?::uuid
    """.trimIndent()

    // This first recovery unit admits one TEST run, not a mixed historical/restore reconstruction.
    val lockRunIdentity = "SELECT data_scope_id, installation_limit FROM complaint_test_runs ORDER BY data_scope_id LIMIT 2 FOR UPDATE"
    val runFingerprint = """
        SELECT CASE WHEN octet_length(to_jsonb(r)::text) BETWEEN 1 AND 262144
            THEN sha256(convert_to(to_jsonb(r)::text, 'UTF8')) END AS fingerprint
        FROM complaint_test_runs r WHERE r.data_scope_id = ?::uuid
    """.trimIndent()
    val sidecars = me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinarySealSqlV1.sidecar.replace(
        "SELECT i.operation_token,",
        "SELECT CASE WHEN octet_length(to_jsonb(i)::text) BETWEEN 1 AND 524288 THEN sha256(convert_to(to_jsonb(i)::text, 'UTF8')) END AS fingerprint, i.operation_token,",
    )
    val scans = """
        SELECT r.*, (SELECT count(*) FROM complaint_journal_scan_entries e WHERE e.scan_id = r.scan_id AND e.pass = r.pass) AS actual_entries,
            sha256(convert_to(to_jsonb(r)::text, 'UTF8')) AS fingerprint
        FROM complaint_journal_scan_runs r WHERE r.data_scope_id = ?::uuid ORDER BY r.scan_id, r.pass LIMIT 3 FOR UPDATE OF r
    """.trimIndent()
}
