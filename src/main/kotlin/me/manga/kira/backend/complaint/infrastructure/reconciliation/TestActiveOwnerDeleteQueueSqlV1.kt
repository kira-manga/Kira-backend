package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.complaint.infrastructure.ComplaintInstallationTestRunRows

/** Fixed ACTIVE queue bookkeeping only. No scan/checkpoint, epoch change or terminal-reserve authority. */
internal object TestActiveOwnerDeleteQueueSqlV1 {
    private const val GLOBAL = "'00000000-0000-0000-0000-000000000000'::uuid"
    val lockGlobal = "SELECT data_scope_id FROM complaint_journal_control WHERE data_scope_id = $GLOBAL FOR UPDATE"
    val lockScope = "SELECT data_scope_id FROM complaint_journal_control WHERE data_scope_id = ?::uuid AND test_only FOR UPDATE"
    val lockRun = "SELECT data_scope_id FROM complaint_test_runs WHERE data_scope_id = ?::uuid AND test_only FOR UPDATE"
    private val expected = """
        WITH e AS MATERIALIZED (SELECT ?::uuid AS scope, ?::bytea AS configuration_hash, ?::bigint AS installation_limit,
            ?::bigint AS generation, ?::bytea AS activation_hash, ?::timestamptz AS created_at, ?::bigint[] AS original_reserve),
        d AS MATERIALIZED (SELECT ?::uuid AS scope, ?::bigint AS desired_generation, ?::integer AS implementation_schema,
            ?::bytea AS configuration_hash, ?::uuid AS database_identity, ?::uuid AS restore_identity,
            ?::uuid AS event_writer, ?::uuid AS catalog_writer, ?::bytea AS trust_hash, ?::bigint AS generation, ?::bytea AS activation_hash),
        b AS MATERIALIZED (SELECT ?::bigint AS desired_generation, ?::bytea AS configuration_hash),
        j AS MATERIALIZED (SELECT ?::bytea AS journal_hash), s AS MATERIALIZED (SELECT clock_timestamp() AS sampled_at)
    """.trimIndent()

    // Sampled only AFTER the fixed global -> scope -> counters(if acquisition) -> run -> observation locks.
    // Queue work can repair an unhealthy journal; neither a copied checkpoint nor its age is admission here.
    // Privacy recovery is independent of creation closure. Maintenance, scan, full D and current-owner
    // checks still apply; unchanged fingerprints also refuse control drift during this exact attempt.
    val current = """
        $expected
        SELECT (e.scope = d.scope AND r.test_only AND r.state = 'ACTIVE' AND (${ComplaintInstallationTestRunRows.activeShape})
            AND r.accounting_version = 1 AND r.configuration_hash = e.configuration_hash AND r.installation_limit = e.installation_limit
            AND r.installation_limit > 0 AND r.enrolled_count BETWEEN 0 AND r.installation_limit
            AND r.activation_catalog_generation = e.generation AND r.activation_catalog_hash = e.activation_hash
            AND r.created_at = e.created_at AND isfinite(r.created_at) AND r.original_reserve = e.original_reserve
            AND complaint_vector_valid(r.original_reserve) AND complaint_vector_lte(r.unused_reserve, r.original_reserve)
            AND c.test_only AND c.implementation_schema = d.implementation_schema AND c.desired_generation = d.desired_generation
            AND c.desired_configuration_hash = d.configuration_hash AND c.database_identity = d.database_identity
            AND c.restore_identity = d.restore_identity AND c.event_writer_generation = d.event_writer
            AND c.catalog_writer_generation = d.catalog_writer AND c.trust_bundle_hash = d.trust_hash
            AND c.accepted_catalog_generation = d.generation AND c.accepted_catalog_hash = d.activation_hash
            AND NOT c.maintenance_closed AND c.pending_projection_token IS NULL AND NOT c.scan_requested
            AND ((c.publication_epoch = 1 AND c.rotation_sequence = 0 AND c.rotation_state IS NULL AND c.rotation_id IS NULL AND c.seal_state IS NULL)
                OR (c.publication_epoch = 2 AND c.rotation_sequence = 1 AND c.rotation_state = 'CAPTURED'
                    AND c.rotation_epoch_before = 1 AND c.rotation_epoch_after = 2 AND c.seal_state = 'SEAL_VERIFIED'
                    AND c.seal_epoch = 1 AND c.seal_writer_generation = d.event_writer))
            AND c.retention_lease_owner IS NULL AND c.retention_lease_expires_at IS NULL
            AND c.lease_token BETWEEN 0 AND 9223372036854775807
            AND ((c.lease_owner IS NULL AND c.lease_expires_at IS NULL)
                OR (complaint_is_v4(c.lease_owner) AND c.lease_token > 0 AND c.lease_expires_at IS NOT NULL))
            AND NOT g.test_only AND g.implementation_schema = 1 AND g.desired_generation = b.desired_generation
            AND g.desired_configuration_hash IS NOT DISTINCT FROM b.configuration_hash
            AND g.database_identity = d.database_identity AND g.restore_identity = d.restore_identity
            AND g.event_writer_generation = d.event_writer AND g.catalog_writer_generation = d.catalog_writer
            AND g.trust_bundle_hash = d.trust_hash AND g.accepted_catalog_generation = d.generation AND g.accepted_catalog_hash = d.activation_hash
            AND NOT g.maintenance_closed AND g.pending_projection_token IS NULL AND NOT g.scan_requested AND g.publication_epoch > 0
            AND complaint_finite_times(g.updated_at, c.updated_at, c.lease_expires_at, s.sampled_at)
            AND s.sampled_at >= c.updated_at AND s.sampled_at >= g.updated_at
            AND s.sampled_at >= '1970-01-01T00:00:00Z'::timestamptz AND s.sampled_at < '10000-01-01T00:00:00Z'::timestamptz
            AND octet_length(to_jsonb(g)::text) BETWEEN 1 AND 524288 AND octet_length(to_jsonb(c)::text) BETWEEN 1 AND 524288
            AND octet_length(to_jsonb(r)::text) BETWEEN 1 AND 524288
            AND NOT EXISTS (SELECT 1 FROM complaint_test_terminal_intents t WHERE t.data_scope_id = e.scope)
            AND NOT EXISTS (SELECT 1 FROM complaint_journal_scan_runs t WHERE t.data_scope_id = e.scope)
            AND NOT EXISTS (SELECT 1 FROM complaint_journal_scan_entries t WHERE t.data_scope_id = e.scope)
        ) IS TRUE AS valid, c.publication_epoch, c.lease_owner, c.lease_token, c.lease_expires_at, s.sampled_at,
            sha256(convert_to((to_jsonb(g) || jsonb_build_object('row_xmin', g.xmin::text))::text, 'UTF8')) AS global_fingerprint,
            sha256(convert_to((to_jsonb(r) || jsonb_build_object('row_xmin', r.xmin::text))::text, 'UTF8')) AS run_fingerprint,
            sha256(convert_to((to_jsonb(c) - ARRAY['lease_owner','lease_token','lease_expires_at','updated_at'])::text, 'UTF8')) AS control_fingerprint
        FROM e CROSS JOIN d CROSS JOIN b CROSS JOIN j CROSS JOIN s
        LEFT JOIN complaint_test_runs r ON r.data_scope_id = e.scope
        LEFT JOIN complaint_journal_control c ON c.data_scope_id = d.scope
        LEFT JOIN complaint_journal_control g ON g.data_scope_id = $GLOBAL LIMIT 2
    """.trimIndent()

    val observation = """
        $expected
        SELECT (o.test_only AND o.desired_generation = d.desired_generation AND o.implementation_schema = d.implementation_schema
            AND o.database_identity = d.database_identity AND o.restore_identity = d.restore_identity AND o.writer_generation = d.event_writer
            AND o.configuration_hash = d.configuration_hash AND o.journal_hash = j.journal_hash
            AND o.catalog_generation = d.generation AND o.catalog_hash = d.activation_hash AND o.trust_bundle_hash = d.trust_hash
            AND o.catalog_writer_generation = d.catalog_writer AND o.storage_bytes = 8192
            AND complaint_is_v4(o.lease_owner) AND o.fencing_token > 0 AND complaint_finite_times(o.started_at, o.settled_at)
            AND ((o.state = 'POLLING' AND o.settled_at IS NULL AND o.primary_acked = 0 AND o.dlq_acked = 0)
                OR (o.state = 'SETTLED' AND o.settled_at >= o.started_at AND o.primary_acked BETWEEN 0 AND 1 AND o.dlq_acked BETWEEN 0 AND 1))
            AND octet_length(to_jsonb(o)::text) BETWEEN 1 AND 4096
        ) IS TRUE AS valid, o.lease_owner, o.fencing_token, o.state, o.started_at, o.settled_at, o.primary_acked, o.dlq_acked,
            sha256(convert_to(to_jsonb(o)::text, 'UTF8')) AS fingerprint
        FROM e CROSS JOIN d CROSS JOIN b CROSS JOIN j JOIN complaint_test_active_queue_observations o ON o.data_scope_id = e.scope
        LIMIT 2 FOR UPDATE OF o
    """.trimIndent()
    val acquire = """
        WITH s AS MATERIALIZED (SELECT clock_timestamp() AS at)
        UPDATE complaint_journal_control c SET lease_owner = ?::uuid, lease_token = c.lease_token + 1,
            lease_expires_at = s.at + interval '30 seconds', updated_at = s.at FROM s
        WHERE c.data_scope_id = ?::uuid AND c.test_only AND c.lease_token = ? AND c.lease_token < 9223372036854775807
            AND (c.lease_owner IS NULL OR c.lease_expires_at <= s.at) AND isfinite(s.at) AND s.at >= c.updated_at
        RETURNING c.lease_token
    """.trimIndent()
    val insertObservation = """
        INSERT INTO complaint_test_active_queue_observations (data_scope_id, test_only, desired_generation, implementation_schema,
            database_identity, restore_identity, writer_generation, configuration_hash, journal_hash,
            catalog_generation, catalog_hash, trust_bundle_hash, catalog_writer_generation,
            lease_owner, fencing_token, state, started_at, primary_acked, dlq_acked, storage_bytes)
        VALUES (?::uuid, true, ?, ?, ?::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?::uuid, ?::uuid, ?, 'POLLING', ?, 0, 0, 8192)
    """.trimIndent()
    val restartObservation = """
        UPDATE complaint_test_active_queue_observations SET lease_owner = ?::uuid, fencing_token = ?, state = 'POLLING', started_at = ?,
            settled_at = NULL, primary_acked = 0, dlq_acked = 0
        WHERE data_scope_id = ?::uuid AND test_only AND fencing_token = ? AND fencing_token < ? AND started_at <= ?
    """.trimIndent()
    val settle = """
        UPDATE complaint_test_active_queue_observations SET state = 'SETTLED', settled_at = ?, primary_acked = ?, dlq_acked = ?
        WHERE data_scope_id = ?::uuid AND test_only AND lease_owner = ?::uuid AND fencing_token = ? AND state = 'POLLING'
            AND started_at = ? AND started_at <= ? AND settled_at IS NULL AND primary_acked = 0 AND dlq_acked = 0
    """.trimIndent()
    val relinquish = """
        WITH s AS MATERIALIZED (SELECT clock_timestamp() AS at)
        UPDATE complaint_journal_control c SET lease_owner = NULL, lease_expires_at = NULL, updated_at = s.at FROM s
        WHERE c.data_scope_id = ?::uuid AND c.test_only AND c.lease_owner = ?::uuid AND c.lease_token = ?
            AND c.lease_expires_at = ? AND c.lease_expires_at > s.at AND isfinite(s.at) AND s.at >= c.updated_at
    """.trimIndent()
}
