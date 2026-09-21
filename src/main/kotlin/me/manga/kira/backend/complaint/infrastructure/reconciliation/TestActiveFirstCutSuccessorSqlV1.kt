package me.manga.kira.backend.complaint.infrastructure.reconciliation

/** Fixed successor-only reads and exact own-lease release. No REQUEST, INSERT, charge or later-state writer. */
internal object TestActiveFirstCutSuccessorSqlV1 {
    // Reuse every original C current-D, birth, V17/V26, RESERVED and empty-seal/checkpoint predicate verbatim.
    val authenticate = TestActiveFirstCutSqlV1.authenticate
    val lockGlobal = TestActiveFirstCutSqlV1.lockGlobal
    val lockScope = TestActiveFirstCutSqlV1.lockScope
    val lockRun = TestActiveFirstCutSqlV1.lockRun
    val lockSlot = TestActiveFirstCutSqlV1.lockSlot
    val read = TestActiveFirstCutSqlV1.read
    val acquireLease = TestActiveFirstCutSqlV1.acquireLease
    val capture = TestActiveFirstCutSqlV1.capture
    val captureSlot = TestActiveFirstCutSqlV1.captureSlot

    val readCounters = """
        SELECT c.name, c.ordinal, c.accounting_version, c.configuration_hash, c.configuration_closed,
            c.hard_limit, c.creation_limit, c.free_units, c.actual_units, c.recovery_reserved_units, c.test_reserved_units,
            c.admission_utc_date, c.admission_count, c.admission_daily_limit,
            (isfinite(c.updated_at) AND (c.admission_utc_date IS NULL OR isfinite(c.admission_utc_date))) IS TRUE AS finite_times,
            CASE WHEN octet_length(to_jsonb(c)::text) BETWEEN 1 AND 4096
                THEN sha256(convert_to((to_jsonb(c) || jsonb_build_object('row_xmin', c.xmin::text))::text, 'UTF8')) END AS row_digest
        FROM complaint_capacity_counters c ORDER BY c.name COLLATE "C" LIMIT 23
    """.trimIndent()
    val lockCounters = "$readCounters FOR UPDATE OF c"

    // Run is already locked and the original C read authenticates its complete current identity/shape.
    val runAccounting = """
        SELECT r.installation_limit, r.enrolled_count, r.original_reserve, r.unused_reserve,
            CASE WHEN octet_length(to_jsonb(r)::text) BETWEEN 1 AND 262144
                THEN sha256(convert_to((to_jsonb(r) || jsonb_build_object('row_xmin', r.xmin::text))::text, 'UTF8')) END AS fingerprint
        FROM complaint_test_runs r WHERE r.data_scope_id = ?::uuid AND r.test_only AND r.state = 'ACTIVE'
    """.trimIndent()
    // Same scoped identity counts as current ACTIVE D admission; no content/secrets are materialized.
    val installationCounts = """
        SELECT (SELECT count(*) FROM complaint_installation_ids WHERE data_scope_id = ?::uuid) AS ids,
            (SELECT count(*) FROM app_installations WHERE data_scope_id = ?::uuid) AS credentials
    """.trimIndent()

    val releaseCapturedLease = """
        WITH sampled AS MATERIALIZED (SELECT clock_timestamp() AS sampled_at)
        UPDATE complaint_journal_control c SET lease_owner = NULL, lease_expires_at = NULL, updated_at = s.sampled_at
        FROM sampled s WHERE c.data_scope_id = ?::uuid AND c.test_only AND c.rotation_id = ?::uuid
            AND c.rotation_sequence = 1 AND c.rotation_state = 'CAPTURED' AND c.rotation_epoch_before = 1
            AND c.publication_epoch = 2 AND c.rotation_epoch_after = 2 AND NOT c.scan_requested
            AND c.lease_owner = ?::uuid AND c.lease_token = ?::bigint AND c.lease_expires_at = ?::timestamptz
            AND c.lease_expires_at > s.sampled_at AND isfinite(s.sampled_at) AND s.sampled_at >= c.updated_at
            AND sha256(convert_to((to_jsonb(c) || jsonb_build_object('row_xmin', c.xmin::text))::text, 'UTF8')) = ?::bytea
    """.trimIndent()
}
