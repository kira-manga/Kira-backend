package me.manga.kira.backend.complaint.infrastructure.catalog

/** Exact SEALED -> PURGING only. No erasure, credential/ID change, PURGED audit or reserve release. */
internal object CatalogTestRunTerminalProjectionSqlV1 {
    private val expected = """
        WITH e AS MATERIALIZED (SELECT ?::uuid AS scope, ?::bytea AS configuration_hash, ?::bigint AS installation_limit,
            ?::bigint AS activation_generation, ?::bytea AS activation_hash, ?::timestamptz AS created_at, ?::bigint[] AS original_reserve)
    """.trimIndent()
    private const val CORE = """(to_jsonb(r) - ARRAY['state','unused_reserve','purging_at','event_manifest_count','event_manifest_root',
        'installation_manifest_count','installation_manifest_root','installation_chunk_count','retired_count','deleted_count',
        'terminal_event_id','terminal_object_key','terminal_object_version','terminal_ciphertext_hash','terminal_catalog_generation','terminal_catalog_hash'])::text"""
    private val valid = """
        r.test_only AND r.configuration_hash = e.configuration_hash AND r.accounting_version = 1
        AND r.installation_limit = e.installation_limit AND r.enrolled_count BETWEEN 0 AND r.installation_limit
        AND r.activation_catalog_generation = e.activation_generation AND r.activation_catalog_hash = e.activation_hash
        AND r.created_at = e.created_at AND r.original_reserve = e.original_reserve
        AND complaint_vector_valid(r.original_reserve) AND complaint_vector_lte(r.unused_reserve, r.original_reserve)
        AND r.sealed_at IS NOT NULL AND r.sealed_at >= r.created_at AND r.sealed_at <= clock_timestamp()
        AND complaint_finite_times(r.created_at, r.sealed_at, r.purging_at) AND r.purged_at IS NULL
        AND r.final_ordinary_epoch > 0 AND r.terminal_seal_epoch - 1 = r.final_ordinary_epoch
        AND r.generation_seal_count IN (2, 3) AND complaint_digest_valid(r.generation_seal_root)
        AND complaint_bytes_match(r.seal_set_bytes, r.seal_set_hash, 65536)
        AND complaint_bytes_match(r.permanent_denial_bytes, r.permanent_denial_hash, 51291)
        AND ((r.state = 'SEALED' AND r.purging_at IS NULL AND r.event_manifest_count IS NULL AND r.event_manifest_root IS NULL
                AND r.installation_manifest_count IS NULL AND r.installation_manifest_root IS NULL AND r.installation_chunk_count IS NULL
                AND r.retired_count IS NULL AND r.deleted_count IS NULL AND r.terminal_event_id IS NULL AND r.terminal_object_key IS NULL
                AND r.terminal_object_version IS NULL AND r.terminal_ciphertext_hash IS NULL
                AND r.terminal_catalog_generation IS NULL AND r.terminal_catalog_hash IS NULL)
            OR (r.state = 'PURGING' AND r.purging_at >= r.sealed_at AND r.purging_at <= clock_timestamp()
                AND r.event_manifest_count > 0 AND complaint_digest_valid(r.event_manifest_root)
                AND r.installation_manifest_count = r.enrolled_count AND complaint_digest_valid(r.installation_manifest_root)
                AND r.installation_chunk_count BETWEEN 0 AND 4096 AND r.retired_count >= 0 AND r.deleted_count >= 0
                AND r.retired_count + r.deleted_count = r.enrolled_count
                AND complaint_ascii_valid(r.terminal_event_id, 128) AND complaint_ascii_valid(r.terminal_object_key, 1024)
                AND complaint_opaque_valid(r.terminal_object_version, 1024) AND r.terminal_object_version <> 'null'
                AND complaint_digest_valid(r.terminal_ciphertext_hash) AND r.terminal_catalog_generation = r.activation_catalog_generation + 1
                AND complaint_digest_valid(r.terminal_catalog_hash)))
        AND octet_length($CORE) BETWEEN 1 AND 524288
    """.trimIndent()
    private val run = """
        $expected
        SELECT ($valid) IS TRUE AS valid, r.data_scope_id, r.state, r.enrolled_count, r.installation_limit, r.sealed_at, r.purging_at,
            CASE WHEN complaint_vector_valid(r.original_reserve) THEN r.original_reserve END AS original_reserve,
            CASE WHEN complaint_vector_valid(r.unused_reserve) THEN r.unused_reserve END AS unused_reserve,
            CASE WHEN octet_length(r.permanent_denial_bytes) BETWEEN 1 AND 51291 THEN r.permanent_denial_bytes END AS progress_bytes,
            CASE WHEN octet_length(r.permanent_denial_hash) = 32 THEN r.permanent_denial_hash END AS progress_hash,
            CASE WHEN octet_length(r.seal_set_bytes) BETWEEN 1 AND 65536 THEN r.seal_set_bytes END AS seal_set_bytes,
            CASE WHEN octet_length(r.seal_set_hash) = 32 THEN r.seal_set_hash END AS seal_set_hash,
            r.final_ordinary_epoch, r.terminal_seal_epoch, r.generation_seal_count,
            CASE WHEN octet_length(r.generation_seal_root) = 32 THEN r.generation_seal_root END AS generation_seal_root,
            r.event_manifest_count,
            CASE WHEN octet_length(r.event_manifest_root) = 32 THEN r.event_manifest_root END AS event_manifest_root,
            r.installation_manifest_count,
            CASE WHEN octet_length(r.installation_manifest_root) = 32 THEN r.installation_manifest_root END AS installation_manifest_root,
            r.installation_chunk_count, r.retired_count, r.deleted_count,
            CASE WHEN octet_length(r.terminal_event_id) BETWEEN 1 AND 128 THEN r.terminal_event_id END AS terminal_event_id,
            CASE WHEN octet_length(r.terminal_object_key) BETWEEN 1 AND 1024 THEN r.terminal_object_key END AS terminal_object_key,
            CASE WHEN octet_length(r.terminal_object_version) BETWEEN 1 AND 1024 THEN r.terminal_object_version END AS terminal_object_version,
            CASE WHEN octet_length(r.terminal_ciphertext_hash) = 32 THEN r.terminal_ciphertext_hash END AS terminal_ciphertext_hash,
            r.terminal_catalog_generation,
            CASE WHEN octet_length(r.terminal_catalog_hash) = 32 THEN r.terminal_catalog_hash END AS terminal_catalog_hash,
            CASE WHEN ($valid) IS TRUE THEN $CORE END AS core_preimage
        FROM complaint_test_runs r CROSS JOIN e WHERE r.data_scope_id = e.scope
    """.trimIndent()
    val readRun = run
    val lockRun = "$run FOR UPDATE OF r"

    val spendPrepared = """
        $expected
        UPDATE complaint_test_runs r SET unused_reserve = ?::bigint[] FROM e
        WHERE r.data_scope_id = e.scope AND ($valid) AND r.state = 'SEALED' AND r.unused_reserve = ?::bigint[]
            AND r.permanent_denial_bytes = ?::bytea AND r.seal_set_bytes = ?::bytea
    """.trimIndent()
    val sampleTime = """
        SELECT sampled_at FROM (SELECT clock_timestamp() AS sampled_at) s
        WHERE isfinite(sampled_at) AND sampled_at >= ?::timestamptz AND sampled_at < '10000-01-01T00:00:00Z'::timestamptz
    """.trimIndent()
    val projectRun = """
        $expected
        UPDATE complaint_test_runs r SET state = 'PURGING', purging_at = ?::timestamptz, unused_reserve = ?::bigint[],
            event_manifest_count = ?, event_manifest_root = ?::bytea,
            installation_manifest_count = ?, installation_manifest_root = ?::bytea, installation_chunk_count = ?, retired_count = ?, deleted_count = ?,
            terminal_event_id = ?, terminal_object_key = ?, terminal_object_version = ?, terminal_ciphertext_hash = ?::bytea,
            terminal_catalog_generation = ?, terminal_catalog_hash = ?::bytea
        FROM e WHERE r.data_scope_id = e.scope AND ($valid) AND r.state = 'SEALED' AND r.unused_reserve = ?::bigint[]
            AND r.permanent_denial_bytes = ?::bytea AND r.seal_set_bytes = ?::bytea
    """.trimIndent()
    val markProjected = """
        UPDATE complaint_catalog_mutations SET projected_at = ?::timestamptz
        WHERE operation_token = ?::uuid AND data_scope_id = ?::uuid AND operation_type = 'TEST_RUN_TERMINAL' AND test_only
            AND state = 'COMPLETED' AND projected_at IS NULL AND successor_generation = ? AND envelope_hash = ?::bytea
            AND completed_at <= ?::timestamptz
    """.trimIndent()
    val clearPending = """
        UPDATE complaint_journal_control SET pending_projection_token = NULL, updated_at = ?::timestamptz
        WHERE data_scope_id = '00000000-0000-0000-0000-000000000000'::uuid AND NOT test_only AND maintenance_closed AND creation_closed
            AND pending_projection_token = ?::uuid AND accepted_catalog_generation = ? AND accepted_catalog_hash = ?::bytea
            AND lease_owner = ?::uuid AND lease_token = ? AND lease_expires_at = ?::timestamptz AND lease_expires_at > clock_timestamp()
    """.trimIndent()
    private val auditExpected = """
        WITH e AS MATERIALIZED (SELECT ?::uuid AS scope, ?::uuid AS token, ?::bigint AS generation, ?::text AS envelope_hash, ?::timestamptz AS at)
    """.trimIndent()
    private const val DETAIL = "jsonb_build_object('generation', e.generation, 'operationToken', e.token::text, 'envelopeSha256', e.envelope_hash)"
    val insertAudit = """
        $auditExpected
        INSERT INTO audit_log (actor_user_id, action, entity_type, entity_id, detail, created_at, complaint_data_scope_id, complaint_actor_kind)
        SELECT NULL::uuid, 'COMPLAINT_TEST_RUN_TERMINAL_CATALOG_PROJECTED', 'complaint_test_run', e.scope::text, $DETAIL, e.at, e.scope, 'SYSTEM' FROM e
    """.trimIndent()
    val readAudit = """
        $auditExpected
        SELECT (a.id > 0 AND a.actor_user_id IS NULL AND a.complaint_actor_kind = 'SYSTEM' AND a.complaint_data_scope_id = e.scope
            AND a.entity_type = 'complaint_test_run' AND a.entity_id = e.scope::text AND a.detail = $DETAIL AND a.created_at = e.at) IS TRUE AS valid
        FROM audit_log a CROSS JOIN e WHERE a.action = 'COMPLAINT_TEST_RUN_TERMINAL_CATALOG_PROJECTED'
            AND (a.complaint_data_scope_id = e.scope OR (a.entity_type = 'complaint_test_run' AND a.entity_id = e.scope::text))
        ORDER BY a.id LIMIT 2
    """.trimIndent()
}
