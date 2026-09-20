package me.manga.kira.backend.complaint.infrastructure.catalog

/** Fixed first-TEST effect. No caller-selected table, prose, audit, run shape or lifecycle transition. */
internal object CatalogTestRunActivationProjectionSqlV1 {
    // Nineteen detached arguments, in the original frozen declaration's fixed order. No supplied DB snapshot.
    internal val expected = """
        WITH expected AS MATERIALIZED (SELECT ?::uuid AS scope, ?::bytea AS configuration_hash, ?::bigint AS installation_limit,
            ?::bigint AS generation, ?::bigint AS desired_generation, ?::integer AS implementation_schema,
            ?::uuid AS database_identity, ?::uuid AS restore_identity, ?::uuid AS event_writer, ?::uuid AS catalog_writer,
            ?::bytea AS trust_hash, ?::uuid AS first_id, ?::text AS first_key, ?::uuid AS second_id, ?::text AS second_key,
            ?::bigint[] AS reserve, ?::bytea AS envelope_hash, ?::timestamptz AS projected_at, ?::uuid AS token)
    """.trimIndent()

    private val seeds = """
        seeds AS MATERIALIZED (SELECT e.first_id AS id, e.first_key AS notice_key FROM expected e
            UNION ALL SELECT e.second_id, e.second_key FROM expected e)
    """.trimIndent()

    private val runColumns = """
        data_scope_id, test_only, state, configuration_hash, accounting_version, installation_limit, enrolled_count,
        original_reserve, unused_reserve, activation_catalog_generation, activation_catalog_hash, created_at,
        sealed_at, purging_at, purged_at, final_ordinary_epoch, terminal_seal_epoch, generation_seal_count,
        generation_seal_root, seal_set_bytes, seal_set_hash, event_manifest_count, event_manifest_root,
        installation_manifest_count, installation_manifest_root, installation_chunk_count, retired_count, deleted_count,
        permanent_denial_bytes, permanent_denial_hash, terminal_event_id, terminal_object_key, terminal_object_version,
        terminal_ciphertext_hash, terminal_catalog_generation, terminal_catalog_hash
    """.trimIndent()
    private val runValues = """
        e.scope, true, 'ACTIVE'::text, e.configuration_hash, 1::smallint, e.installation_limit, 0::bigint,
        e.reserve, e.reserve, e.generation, e.envelope_hash, e.projected_at,
        NULL::timestamptz, NULL::timestamptz, NULL::timestamptz, NULL::bigint, NULL::bigint, NULL::integer,
        NULL::bytea, NULL::bytea, NULL::bytea, NULL::bigint, NULL::bytea,
        NULL::bigint, NULL::bytea, NULL::integer, NULL::bigint, NULL::bigint,
        NULL::bytea, NULL::bytea, NULL::text, NULL::text, NULL::text,
        NULL::bytea, NULL::bigint, NULL::bytea
    """.trimIndent()
    val insertRun = "$expected\nINSERT INTO complaint_test_runs ($runColumns) SELECT $runValues FROM expected e"

    private val controlColumns = """
        data_scope_id, test_only, publication_epoch, desired_generation, implementation_schema, desired_configuration_hash,
        database_identity, restore_identity, event_writer_generation, accepted_catalog_generation, accepted_catalog_hash,
        trust_bundle_hash, catalog_writer_generation, pending_projection_token, maintenance_closed, creation_closed, scan_requested,
        lease_owner, lease_token, lease_expires_at, retention_lease_owner, retention_lease_token, retention_lease_expires_at,
        seal_state, seal_epoch, seal_writer_generation, seal_operation_token, seal_object_key, seal_bytes, seal_hash,
        seal_object_version, seal_ciphertext_hash, seal_retain_until, seal_verified_at, seal_verification_bytes, seal_verification_hash,
        checkpoint_generation, checkpoint_fencing_token, checkpoint_catalog_generation, checkpoint_catalog_hash,
        checkpoint_writer_generation, checkpoint_cutoff_epoch, checkpoint_configuration_hash, checkpoint_database_identity,
        checkpoint_restore_identity, checkpoint_schema, checkpoint_started_at, checkpoint_completed_at, checkpoint_object_count,
        checkpoint_byte_count, checkpoint_result, checkpoint_bytes, checkpoint_hash, updated_at,
        rotation_sequence, rotation_id, rotation_state, rotation_epoch_before, rotation_implementation_schema,
        rotation_desired_generation, rotation_desired_configuration_hash, rotation_database_identity, rotation_restore_identity,
        rotation_event_writer_generation, rotation_accepted_catalog_generation, rotation_accepted_catalog_hash,
        rotation_trust_bundle_hash, rotation_catalog_writer_generation, rotation_request_owner, rotation_request_token,
        rotation_requested_at, rotation_capture_owner, rotation_capture_token, rotation_captured_at, rotation_epoch_after,
        seal_format, seal_rotation_id, seal_rotation_sequence, seal_preparing_fencing_token, seal_routing_key_id,
        seal_epoch_start, seal_preceding_hash
    """.trimIndent()
    private val controlValues = """
        e.scope, true, 1::bigint, e.desired_generation, e.implementation_schema, e.configuration_hash,
        e.database_identity, e.restore_identity, e.event_writer, e.generation, e.envelope_hash,
        e.trust_hash, e.catalog_writer, NULL::uuid, true, true, true,
        NULL::uuid, 0::bigint, NULL::timestamptz, NULL::uuid, 0::bigint, NULL::timestamptz,
        NULL::text, NULL::bigint, NULL::uuid, NULL::uuid, NULL::text, NULL::bytea, NULL::bytea,
        NULL::text, NULL::bytea, NULL::timestamptz, NULL::timestamptz, NULL::bytea, NULL::bytea,
        NULL::bigint, NULL::bigint, NULL::bigint, NULL::bytea,
        NULL::uuid, NULL::bigint, NULL::bytea, NULL::uuid,
        NULL::uuid, NULL::integer, NULL::timestamptz, NULL::timestamptz, NULL::bigint,
        NULL::bigint, NULL::text, NULL::bytea, NULL::bytea, e.projected_at,
        0::bigint, NULL::uuid, NULL::text, NULL::bigint, NULL::integer,
        NULL::bigint, NULL::bytea, NULL::uuid, NULL::uuid,
        NULL::uuid, NULL::bigint, NULL::bytea,
        NULL::bytea, NULL::uuid, NULL::uuid, NULL::bigint,
        NULL::timestamptz, NULL::uuid, NULL::bigint, NULL::timestamptz, NULL::bigint,
        NULL::integer, NULL::uuid, NULL::bigint, NULL::bigint, NULL::text,
        NULL::bigint, NULL::bytea
    """.trimIndent()
    val insertControl = "$expected\nINSERT INTO complaint_journal_control ($controlColumns) SELECT $controlValues FROM expected e"

    private const val RESOURCE_COLUMNS = "id, data_scope_id, test_only, state, created_at, deleted_at"
    private const val RESOURCE_VALUES = "s.id, e.scope, true, 'LIVE'::text, e.projected_at, NULL::timestamptz"
    val insertResources = "$expected, $seeds\nINSERT INTO complaint_resource_ids ($RESOURCE_COLUMNS) SELECT $RESOURCE_VALUES FROM expected e CROSS JOIN seeds s ORDER BY s.id"

    private val noticeColumns = """
        id, data_scope_id, test_only, owner_id, ownership, kind, type, status, notice_key, subject, body, parent_resource_id,
        app_version, platform, os_version, manufacturer, device_model, closure_reason, closed_at, closure_provenance, closure_actor_id,
        legacy_collection, legacy_key_id, legacy_document_hmac, legacy_payload_hash, legacy_owner_fingerprint, legacy_reconciliation_code,
        created_at, updated_at, version
    """.trimIndent()
    private val noticeValues = """
        s.id, e.scope, true, NULL::uuid, 'SYSTEM'::text, 'NOTICE'::text, NULL::text, 'PINNED'::text, s.notice_key, NULL::text, NULL::text, NULL::uuid,
        NULL::text, NULL::text, NULL::text, NULL::text, NULL::text, NULL::text, NULL::timestamptz, NULL::text, NULL::uuid,
        NULL::text, NULL::text, NULL::bytea, NULL::bytea, NULL::bytea, NULL::text,
        e.projected_at, e.projected_at, 1::bigint
    """.trimIndent()
    val insertNotices = "$expected, $seeds\nINSERT INTO complaints ($noticeColumns) SELECT $noticeValues FROM expected e CROSS JOIN seeds s ORDER BY s.id"

    private const val AUDIT_COLUMNS = "actor_user_id, action, entity_type, entity_id, detail, created_at, complaint_data_scope_id, complaint_actor_kind"
    private val audits = """
        expected_audits(stage, $AUDIT_COLUMNS) AS MATERIALIZED (
            SELECT 0, NULL::uuid, 'COMPLAINT_CREATED'::text, 'complaint'::text, e.first_id::text, jsonb_build_object('version', 1), e.projected_at, e.scope, 'SYSTEM'::text FROM expected e
            UNION ALL SELECT 1, NULL::uuid, 'COMPLAINT_CREATED', 'complaint', e.second_id::text, jsonb_build_object('version', 1), e.projected_at, e.scope, 'SYSTEM' FROM expected e
            UNION ALL SELECT 2, NULL::uuid, 'COMPLAINT_TEST_RUN_ACTIVATED', 'complaint_test_run', e.scope::text, jsonb_build_object('generation', e.generation), e.projected_at, e.scope, 'SYSTEM' FROM expected e
            UNION ALL SELECT 3, NULL::uuid, 'COMPLAINT_CATALOG_PROJECTED', 'complaint_catalog', e.token::text, jsonb_build_object('generation', e.generation), e.projected_at, e.scope, 'SYSTEM' FROM expected e)
    """.trimIndent()
    // Four distinct fixed statements; the original retained audit writer spends each stage exactly once.
    internal val insertFirstNoticeAudit = "$expected, $audits\nINSERT INTO audit_log ($AUDIT_COLUMNS) SELECT $AUDIT_COLUMNS FROM expected_audits WHERE stage = 0"
    internal val insertSecondNoticeAudit = "$expected, $audits\nINSERT INTO audit_log ($AUDIT_COLUMNS) SELECT $AUDIT_COLUMNS FROM expected_audits WHERE stage = 1"
    internal val insertActivatedAudit = "$expected, $audits\nINSERT INTO audit_log ($AUDIT_COLUMNS) SELECT $AUDIT_COLUMNS FROM expected_audits WHERE stage = 2"
    internal val insertProjectedAudit = "$expected, $audits\nINSERT INTO audit_log ($AUDIT_COLUMNS) SELECT $AUDIT_COLUMNS FROM expected_audits WHERE stage = 3"

    val sampleTime = """
        WITH sampled AS MATERIALIZED (SELECT clock_timestamp() AS sampled_at)
        SELECT sampled_at FROM sampled WHERE isfinite(sampled_at) AND sampled_at >= ?::timestamptz
            AND sampled_at >= '1970-01-01T00:00:00Z'::timestamptz AND sampled_at < '10000-01-01T00:00:00Z'::timestamptz
    """.trimIndent()
    val project = """
        $expected
        UPDATE complaint_catalog_mutations m SET projected_at = e.projected_at FROM expected e
        WHERE m.operation_token = e.token AND m.data_scope_id = e.scope AND m.test_only AND m.operation_type = 'TEST_RUN_ACTIVATION'
            AND m.state = 'COMPLETED' AND m.projected_at IS NULL AND m.successor_generation = e.generation AND m.envelope_hash = e.envelope_hash
            AND m.completed_at IS NOT NULL AND isfinite(e.projected_at) AND e.projected_at >= m.completed_at
    """.trimIndent()
    val clearPending = """
        $expected
        UPDATE complaint_journal_control c SET pending_projection_token = NULL, updated_at = e.projected_at FROM expected e
        WHERE c.data_scope_id = '00000000-0000-0000-0000-000000000000'::uuid AND NOT c.test_only
            AND c.maintenance_closed AND c.creation_closed AND c.pending_projection_token = e.token
            AND c.accepted_catalog_generation = e.generation AND c.accepted_catalog_hash = e.envelope_hash
            AND c.lease_owner = ?::uuid AND c.lease_token = ? AND c.lease_expires_at = ?::timestamptz
    """.trimIndent()

    // A projected replay locks the existing scope control BEFORE catalog/history, never after counters.
    val lockScopeControl = "SELECT data_scope_id FROM complaint_journal_control WHERE data_scope_id = ?::uuid AND test_only FOR UPDATE"
    val lockRun = "SELECT data_scope_id FROM complaint_test_runs WHERE data_scope_id = ?::uuid FOR UPDATE"
    val lockResources = "SELECT id FROM complaint_resource_ids WHERE data_scope_id = ?::uuid ORDER BY id LIMIT 3 FOR UPDATE"
    val lockNotices = "SELECT id FROM complaints WHERE data_scope_id = ?::uuid ORDER BY id LIMIT 3 FOR UPDATE"
    val lockAudits = "SELECT id FROM audit_log WHERE complaint_data_scope_id = ?::uuid ORDER BY id LIMIT 5 FOR UPDATE"

    /** Exact nonempty branch: no extra scoped rows/references; the old zero-row preflight stays unchanged. */
    private val noOtherEffect = """
        NOT EXISTS (SELECT 1 FROM complaint_installation_ids WHERE data_scope_id = e.scope)
        AND NOT EXISTS (SELECT 1 FROM app_installations WHERE data_scope_id = e.scope)
        AND NOT EXISTS (SELECT 1 FROM complaint_journal_publications WHERE data_scope_id = e.scope)
        AND NOT EXISTS (SELECT 1 FROM complaint_idempotency_receipts WHERE data_scope_id = e.scope
            OR target_ids && ARRAY[e.first_id, e.second_id] OR ack_ids && ARRAY[e.first_id, e.second_id])
        AND NOT EXISTS (SELECT 1 FROM installation_deletion_receipts WHERE data_scope_id = e.scope)
        AND NOT EXISTS (SELECT 1 FROM complaint_deletion_journal_applied WHERE data_scope_id = e.scope)
        AND NOT EXISTS (SELECT 1 FROM complaint_deletion_journal_retirements WHERE data_scope_id = e.scope)
        AND NOT EXISTS (SELECT 1 FROM complaint_recovery_capacity_reservations WHERE data_scope_id = e.scope)
        AND NOT EXISTS (SELECT 1 FROM complaint_journal_scan_runs WHERE data_scope_id = e.scope)
        AND NOT EXISTS (SELECT 1 FROM complaint_journal_scan_entries WHERE data_scope_id = e.scope)
        AND NOT EXISTS (SELECT 1 FROM complaint_import_runs WHERE data_scope_id = e.scope)
        AND NOT EXISTS (SELECT 1 FROM complaint_import_staging WHERE data_scope_id = e.scope
            OR assigned_id IN (e.first_id, e.second_id) OR assigned_parent_id IN (e.first_id, e.second_id))
        AND NOT EXISTS (SELECT 1 FROM complaint_import_artifacts WHERE data_scope_id = e.scope)
        AND NOT EXISTS (SELECT 1 FROM complaint_legacy_records WHERE data_scope_id = e.scope
            OR assigned_id IN (e.first_id, e.second_id) OR assigned_parent_id IN (e.first_id, e.second_id))
    """.trimIndent()

    /**
     * Only one boolean and one32-byte fingerprint leave JDBC. Each effect class has a cardinality
     * sentinel; exact full-column equality with the fixed inserted shape precedes fingerprinting.
     * Generated audit IDs and original xmin are included, so a substituted equal-looking row is
     * not the same two-round preimage. No application JSON parser runs under the original holder.
     */
    val readEffect = """
        $expected, $seeds, $audits,
        expected_run($runColumns) AS MATERIALIZED (SELECT $runValues FROM expected e),
        expected_control($controlColumns) AS MATERIALIZED (SELECT $controlValues FROM expected e),
        expected_resources($RESOURCE_COLUMNS) AS MATERIALIZED (SELECT $RESOURCE_VALUES FROM expected e CROSS JOIN seeds s),
        expected_notices($noticeColumns) AS MATERIALIZED (SELECT $noticeValues FROM expected e CROSS JOIN seeds s),
        actual_run AS MATERIALIZED (SELECT r.*, r.xmin::text AS row_xmin FROM complaint_test_runs r ORDER BY r.data_scope_id LIMIT 2),
        actual_control AS MATERIALIZED (SELECT c.*, c.xmin::text AS row_xmin FROM complaint_journal_control c, expected e WHERE c.data_scope_id = e.scope),
        actual_resources AS MATERIALIZED (SELECT r.*, r.xmin::text AS row_xmin FROM complaint_resource_ids r, expected e
            WHERE r.data_scope_id = e.scope OR r.id IN (e.first_id, e.second_id) ORDER BY r.id LIMIT 3),
        actual_notices AS MATERIALIZED (SELECT n.*, n.xmin::text AS row_xmin FROM complaints n, expected e
            WHERE n.data_scope_id = e.scope OR n.id IN (e.first_id, e.second_id) OR n.parent_resource_id IN (e.first_id, e.second_id) ORDER BY n.id LIMIT 3),
        actual_audits AS MATERIALIZED (SELECT a.*, a.xmin::text AS row_xmin FROM audit_log a, expected e
            WHERE a.complaint_data_scope_id = e.scope ORDER BY a.id LIMIT 5),
        checked AS MATERIALIZED (SELECT (isfinite(e.projected_at)
            AND (SELECT count(*) FROM actual_run) = 1
            AND (SELECT count(*) FROM actual_control) = 1
            AND (SELECT count(*) FROM actual_resources) = 2
            AND (SELECT count(*) FROM actual_notices) = 2
            AND (SELECT count(*) FROM actual_audits) = 4
            AND NOT EXISTS (SELECT 1 FROM actual_run a WHERE NOT EXISTS (SELECT 1 FROM expected_run x WHERE (to_jsonb(a) - 'row_xmin') = to_jsonb(x)))
            AND NOT EXISTS (SELECT 1 FROM actual_control a WHERE NOT EXISTS (SELECT 1 FROM expected_control x WHERE (to_jsonb(a) - 'row_xmin') = to_jsonb(x)))
            AND NOT EXISTS (SELECT 1 FROM actual_resources a WHERE NOT EXISTS (SELECT 1 FROM expected_resources x WHERE (to_jsonb(a) - 'row_xmin') = to_jsonb(x)))
            AND NOT EXISTS (SELECT 1 FROM actual_notices a WHERE NOT EXISTS (SELECT 1 FROM expected_notices x WHERE (to_jsonb(a) - 'row_xmin') = to_jsonb(x)))
            AND NOT EXISTS (SELECT 1 FROM expected_audits x WHERE (SELECT count(*) FROM actual_audits a
                WHERE (to_jsonb(a) - ARRAY['id','row_xmin']) = (to_jsonb(x) - 'stage') AND a.id > 0) <> 1)
            AND $noOtherEffect) IS TRUE AS valid FROM expected e)
        SELECT valid, CASE WHEN valid THEN sha256(convert_to(jsonb_build_array('kira-test-run-projection-effect-v1',
            (SELECT jsonb_agg(to_jsonb(r) ORDER BY r.data_scope_id) FROM actual_run r),
            (SELECT jsonb_agg(to_jsonb(c) ORDER BY c.data_scope_id) FROM actual_control c),
            (SELECT jsonb_agg(to_jsonb(r) ORDER BY r.id) FROM actual_resources r),
            (SELECT jsonb_agg(to_jsonb(n) ORDER BY n.id) FROM actual_notices n),
            (SELECT jsonb_agg(to_jsonb(a) ORDER BY a.id) FROM actual_audits a))::text, 'UTF8')) END AS effect_fingerprint
        FROM checked
    """.trimIndent()
}
