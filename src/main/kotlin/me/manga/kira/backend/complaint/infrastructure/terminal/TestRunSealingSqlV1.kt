package me.manga.kira.backend.complaint.infrastructure.terminal

/** Fixed barrier and paid audit only. No epoch/catalog mutation, drain assertion or terminal evidence. */
internal object TestRunSealingSqlV1 {
    private val expectedRun = """
        WITH expected AS MATERIALIZED (SELECT ?::uuid AS scope, ?::bytea AS configuration_hash, ?::bigint AS installation_limit,
            ?::bigint AS generation, ?::bytea AS activation_hash, ?::timestamptz AS created_at, ?::bigint[] AS original_reserve)
    """.trimIndent()
    private val noTerminal = """
        r.purging_at IS NULL AND r.purged_at IS NULL
        AND r.final_ordinary_epoch IS NULL AND r.terminal_seal_epoch IS NULL AND r.generation_seal_count IS NULL
        AND r.generation_seal_root IS NULL AND r.seal_set_bytes IS NULL AND r.seal_set_hash IS NULL
        AND r.event_manifest_count IS NULL AND r.event_manifest_root IS NULL
        AND r.installation_manifest_count IS NULL AND r.installation_manifest_root IS NULL AND r.installation_chunk_count IS NULL
        AND r.retired_count IS NULL AND r.deleted_count IS NULL AND r.permanent_denial_bytes IS NULL AND r.permanent_denial_hash IS NULL
        AND r.terminal_event_id IS NULL AND r.terminal_object_key IS NULL AND r.terminal_object_version IS NULL
        AND r.terminal_ciphertext_hash IS NULL AND r.terminal_catalog_generation IS NULL AND r.terminal_catalog_hash IS NULL
    """.trimIndent()

    val readRun = """
        $expectedRun
        SELECT (r.test_only AND r.configuration_hash = e.configuration_hash AND r.accounting_version = 1
            AND r.installation_limit = e.installation_limit AND r.installation_limit > 0 AND r.enrolled_count BETWEEN 0 AND r.installation_limit
            AND r.activation_catalog_generation = e.generation AND r.activation_catalog_hash = e.activation_hash
            AND r.created_at = e.created_at AND isfinite(r.created_at) AND r.original_reserve = e.original_reserve
            AND complaint_vector_valid(r.original_reserve) AND complaint_vector_lte(r.unused_reserve, r.original_reserve)
            AND ((r.state = 'ACTIVE' AND r.sealed_at IS NULL)
                OR (r.state = 'SEALED' AND isfinite(r.sealed_at) AND r.sealed_at >= r.created_at
                    AND r.sealed_at >= '1970-01-01T00:00:00Z'::timestamptz AND r.sealed_at < '10000-01-01T00:00:00Z'::timestamptz))
            AND $noTerminal) IS TRUE AS valid,
            r.state, r.installation_limit, r.enrolled_count,
            CASE WHEN isfinite(r.sealed_at) THEN r.sealed_at END AS sealed_at,
            CASE WHEN complaint_vector_valid(r.original_reserve) THEN r.original_reserve END AS original_reserve,
            CASE WHEN complaint_vector_valid(r.unused_reserve) THEN r.unused_reserve END AS unused_reserve
        FROM complaint_test_runs r CROSS JOIN expected e WHERE r.data_scope_id = e.scope
    """.trimIndent()
    val lockRun = "$readRun FOR UPDATE OF r"

    // This transaction holds ONLY the run row (plus mandatory M participation), never E/control/counters/catalog.
    val sealRun = """
        $expectedRun, sampled AS MATERIALIZED (SELECT clock_timestamp() AS sealed_at)
        UPDATE complaint_test_runs r SET state = 'SEALED', sealed_at = s.sealed_at FROM expected e CROSS JOIN sampled s
        WHERE r.data_scope_id = e.scope AND r.test_only AND r.state = 'ACTIVE' AND r.sealed_at IS NULL
            AND r.configuration_hash = e.configuration_hash AND r.activation_catalog_generation = e.generation
            AND r.activation_catalog_hash = e.activation_hash AND r.original_reserve = e.original_reserve AND r.created_at = e.created_at
            AND isfinite(s.sealed_at) AND s.sealed_at >= r.created_at
            AND s.sealed_at >= '1970-01-01T00:00:00Z'::timestamptz AND s.sealed_at < '10000-01-01T00:00:00Z'::timestamptz
        RETURNING r.sealed_at
    """.trimIndent()

    private val expectedControl = """
        WITH expected AS MATERIALIZED (SELECT ?::uuid AS scope, ?::bigint AS desired_generation, ?::integer AS implementation_schema,
            ?::bytea AS configuration_hash, ?::uuid AS database_identity, ?::uuid AS restore_identity,
            ?::uuid AS event_writer, ?::uuid AS catalog_writer, ?::bytea AS trust_hash, ?::bigint AS generation, ?::bytea AS activation_hash)
    """.trimIndent()
    private val controlIdentity = """
        c.maintenance_closed AND c.creation_closed AND c.pending_projection_token IS NULL AND c.publication_epoch > 0
        AND c.database_identity = e.database_identity AND c.restore_identity = e.restore_identity
        AND c.event_writer_generation = e.event_writer AND c.catalog_writer_generation = e.catalog_writer
        AND c.trust_bundle_hash = e.trust_hash AND c.accepted_catalog_generation = e.generation AND c.accepted_catalog_hash = e.activation_hash
        AND isfinite(c.updated_at)
    """.trimIndent()
    val readGlobalControl = """
        $expectedControl
        SELECT (NOT c.test_only AND c.implementation_schema = 1 AND c.desired_generation > 0 AND $controlIdentity) IS TRUE AS valid
        FROM complaint_journal_control c CROSS JOIN expected e
        WHERE c.data_scope_id = '00000000-0000-0000-0000-000000000000'::uuid
    """.trimIndent()
    val lockGlobalControl = "$readGlobalControl FOR UPDATE OF c"
    val readScopeControl = """
        $expectedControl
        SELECT (c.test_only AND c.implementation_schema = e.implementation_schema AND c.desired_generation = e.desired_generation
            AND c.desired_configuration_hash = e.configuration_hash AND $controlIdentity) IS TRUE AS valid,
            c.database_identity, c.restore_identity, c.desired_generation, c.lease_token
        FROM complaint_journal_control c CROSS JOIN expected e WHERE c.data_scope_id = e.scope
    """.trimIndent()
    val lockScopeControl = "$readScopeControl FOR UPDATE OF c"

    private val expectedAudit = """
        WITH expected AS MATERIALIZED (SELECT ?::uuid AS scope, ?::bigint AS generation, ?::timestamptz AS sealed_at)
    """.trimIndent()
    val readAudit = """
        $expectedAudit
        SELECT (a.id > 0 AND a.complaint_data_scope_id = e.scope AND a.actor_user_id IS NULL AND a.complaint_actor_kind = 'SYSTEM'
            AND a.entity_type = 'complaint_test_run' AND a.entity_id = e.scope::text
            AND a.detail = jsonb_build_object('generation', e.generation) AND a.created_at = e.sealed_at) IS TRUE AS valid
        FROM audit_log a CROSS JOIN expected e
        WHERE a.action = 'COMPLAINT_TEST_RUN_SEALED'
            AND (a.complaint_data_scope_id = e.scope OR (a.entity_type = 'complaint_test_run' AND a.entity_id = e.scope::text))
        ORDER BY a.id LIMIT 2
    """.trimIndent()
    val lockAudit = "$readAudit FOR UPDATE OF a"
    val spendRun = """
        $expectedRun
        UPDATE complaint_test_runs r SET unused_reserve = ?::bigint[] FROM expected e
        WHERE r.data_scope_id = e.scope AND r.test_only AND r.state = 'SEALED' AND r.accounting_version = 1
            AND r.configuration_hash = e.configuration_hash AND r.original_reserve = e.original_reserve
            AND r.activation_catalog_generation = e.generation AND r.activation_catalog_hash = e.activation_hash
            AND r.sealed_at = ?::timestamptz AND r.unused_reserve = ?::bigint[]
    """.trimIndent()
    val insertAudit = """
        $expectedAudit
        INSERT INTO audit_log (actor_user_id, action, entity_type, entity_id, detail, created_at, complaint_data_scope_id, complaint_actor_kind)
        SELECT NULL::uuid, 'COMPLAINT_TEST_RUN_SEALED', 'complaint_test_run', e.scope::text,
            jsonb_build_object('generation', e.generation), e.sealed_at, e.scope, 'SYSTEM' FROM expected e
    """.trimIndent()
}
