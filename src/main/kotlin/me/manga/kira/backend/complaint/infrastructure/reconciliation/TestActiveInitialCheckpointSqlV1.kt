package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.complaint.infrastructure.ComplaintInstallationTestRunRows

/** Fixed initial EMPTY checkpoint SQL. No E/epoch change, publication, V17/V26 update or terminal spending. */
internal object TestActiveInitialCheckpointSqlV1 {
    private const val GLOBAL = "'00000000-0000-0000-0000-000000000000'::uuid"
    val lockGlobal = "SELECT data_scope_id FROM complaint_journal_control WHERE data_scope_id = $GLOBAL FOR UPDATE"
    val lockScope = "SELECT data_scope_id FROM complaint_journal_control WHERE data_scope_id = ?::uuid AND test_only FOR UPDATE"
    val lockRun = "SELECT data_scope_id FROM complaint_test_runs WHERE data_scope_id = ?::uuid AND test_only FOR UPDATE"
    val lockSlot = "SELECT operation_token FROM complaint_test_active_seal_intents WHERE data_scope_id = ?::uuid LIMIT 2 FOR UPDATE"
    private val expected = """
        WITH e AS MATERIALIZED (SELECT ?::uuid AS scope, ?::bytea AS configuration_hash, ?::bigint AS installation_limit,
            ?::bigint AS generation, ?::bytea AS activation_hash, ?::timestamptz AS created_at, ?::bigint[] AS original_reserve),
        d AS MATERIALIZED (SELECT ?::uuid AS scope, ?::bigint AS desired_generation, ?::integer AS implementation_schema,
            ?::bytea AS configuration_hash, ?::uuid AS database_identity, ?::uuid AS restore_identity,
            ?::uuid AS event_writer, ?::uuid AS catalog_writer, ?::bytea AS trust_hash, ?::bigint AS generation, ?::bytea AS activation_hash),
        b AS MATERIALIZED (SELECT ?::bigint AS desired_generation, ?::bytea AS configuration_hash),
        j AS MATERIALIZED (SELECT ?::bytea AS journal_hash), s AS MATERIALIZED (SELECT clock_timestamp() AS sampled_at)
    """.trimIndent()
    private val noHistory = """
        c.retention_lease_owner IS NULL AND c.retention_lease_token = 0 AND c.retention_lease_expires_at IS NULL
        AND c.seal_format IS NULL AND c.seal_rotation_id IS NULL AND c.seal_rotation_sequence IS NULL
        AND c.seal_preparing_fencing_token IS NULL AND c.seal_routing_key_id IS NULL AND c.seal_epoch_start IS NULL AND c.seal_preceding_hash IS NULL
        AND c.checkpoint_generation IS NULL AND c.checkpoint_fencing_token IS NULL AND c.checkpoint_catalog_generation IS NULL
        AND c.checkpoint_catalog_hash IS NULL AND c.checkpoint_writer_generation IS NULL AND c.checkpoint_cutoff_epoch IS NULL
        AND c.checkpoint_configuration_hash IS NULL AND c.checkpoint_database_identity IS NULL AND c.checkpoint_restore_identity IS NULL
        AND c.checkpoint_schema IS NULL AND c.checkpoint_started_at IS NULL AND c.checkpoint_completed_at IS NULL
        AND c.checkpoint_object_count IS NULL AND c.checkpoint_byte_count IS NULL AND c.checkpoint_result IS NULL
        AND c.checkpoint_bytes IS NULL AND c.checkpoint_hash IS NULL
    """.trimIndent()
    /** Same frozen21 identity comparison positions as C. Dynamic ACTIVE enrollment is legal, not an initial reserve snapshot. */
    val current = current(noHistory)

    /** Same registered first-seal comparisons, but only the completed, lease-free initial checkpoint. */
    val currentForOwnerCreate = current("""
        c.retention_lease_owner IS NULL AND c.retention_lease_token = 0 AND c.retention_lease_expires_at IS NULL
        AND c.seal_format IS NULL AND c.seal_rotation_id IS NULL AND c.seal_rotation_sequence IS NULL
        AND c.seal_preparing_fencing_token IS NULL AND c.seal_routing_key_id IS NULL AND c.seal_epoch_start IS NULL AND c.seal_preceding_hash IS NULL
        AND c.lease_owner IS NULL AND c.lease_expires_at IS NULL AND c.checkpoint_fencing_token = c.lease_token
        AND c.checkpoint_fencing_token > i.preparing_fencing_token AND c.checkpoint_generation = d.desired_generation
        AND c.checkpoint_catalog_generation = d.generation AND c.checkpoint_catalog_hash = d.activation_hash
        AND c.checkpoint_writer_generation = d.event_writer AND c.checkpoint_cutoff_epoch = 1
        AND c.checkpoint_configuration_hash = d.configuration_hash AND c.checkpoint_database_identity = d.database_identity
        AND c.checkpoint_restore_identity = d.restore_identity AND c.checkpoint_schema = d.implementation_schema
        AND c.checkpoint_result = 'SUCCESS' AND c.checkpoint_object_count = 0 AND c.checkpoint_byte_count = 0
        AND complaint_bytes_match(c.checkpoint_bytes, c.checkpoint_hash, 65536)
        AND complaint_finite_times(c.checkpoint_started_at, c.checkpoint_completed_at)
        AND c.checkpoint_started_at >= c.seal_verified_at AND c.checkpoint_completed_at >= c.checkpoint_started_at
        AND c.checkpoint_completed_at <= c.updated_at AND c.checkpoint_completed_at <= s.sampled_at
        AND NOT g.scan_requested
        AND NOT EXISTS (SELECT 1 FROM complaint_journal_scan_runs x WHERE x.data_scope_id = e.scope)
    """.trimIndent())

    // No caller-selectable predicate: the two fixed readers share the complete registered identity and lineage.
    private fun current(history: String) = """
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
            AND NOT c.maintenance_closed AND NOT c.creation_closed AND c.pending_projection_token IS NULL AND ($history)
            AND NOT g.test_only AND g.implementation_schema = 1 AND g.desired_generation = b.desired_generation
            AND g.desired_configuration_hash IS NOT DISTINCT FROM b.configuration_hash
            AND g.database_identity = d.database_identity AND g.restore_identity = d.restore_identity
            AND g.event_writer_generation = d.event_writer AND g.catalog_writer_generation = d.catalog_writer
            AND g.trust_bundle_hash = d.trust_hash AND g.accepted_catalog_generation = d.generation AND g.accepted_catalog_hash = d.activation_hash
            AND NOT g.maintenance_closed AND NOT g.creation_closed AND g.pending_projection_token IS NULL AND g.publication_epoch > 0
            AND c.rotation_state = 'CAPTURED' AND c.rotation_sequence = 1 AND c.rotation_id = i.operation_token
            AND c.publication_epoch = i.epoch_after AND NOT c.scan_requested
            AND c.rotation_epoch_before = i.epoch_end AND c.rotation_epoch_after = i.epoch_after
            AND c.rotation_implementation_schema = d.implementation_schema AND c.rotation_desired_generation = d.desired_generation
            AND c.rotation_desired_configuration_hash = d.configuration_hash AND c.rotation_database_identity = d.database_identity
            AND c.rotation_restore_identity = d.restore_identity AND c.rotation_event_writer_generation = d.event_writer
            AND c.rotation_accepted_catalog_generation = d.generation AND c.rotation_accepted_catalog_hash = d.activation_hash
            AND c.rotation_trust_bundle_hash = d.trust_hash AND c.rotation_catalog_writer_generation = d.catalog_writer
            AND c.rotation_request_owner = i.request_owner AND c.rotation_request_token = i.request_token AND c.rotation_requested_at = i.requested_at
            AND c.rotation_capture_owner = i.capture_owner AND c.rotation_capture_token = i.capture_token AND c.rotation_captured_at = i.captured_at
            AND i.schema_version = 1 AND i.test_only AND i.run_created_at = e.created_at AND i.implementation_schema = d.implementation_schema
            AND i.desired_generation = d.desired_generation AND i.configuration_hash = d.configuration_hash AND i.journal_configuration_hash = j.journal_hash
            AND i.database_identity = d.database_identity AND i.restore_identity = d.restore_identity AND i.writer_generation = d.event_writer
            AND i.activation_catalog_generation = e.generation AND i.activation_catalog_hash = e.activation_hash
            AND i.accepted_catalog_generation = d.generation AND i.accepted_catalog_hash = d.activation_hash
            AND i.trust_bundle_hash = d.trust_hash AND i.catalog_writer_generation = d.catalog_writer
            AND i.rotation_sequence = 1 AND i.epoch_start = 1 AND i.epoch_end = 1 AND i.epoch_after = 2 AND i.charged_storage_bytes = 2097152
            AND i.captured_at >= i.requested_at AND i.requested_at >= r.created_at AND i.captured_at <= s.sampled_at
            AND c.lease_token >= i.capture_token AND c.lease_token >= i.request_token
            AND ((c.lease_owner IS NULL AND c.lease_expires_at IS NULL) OR (complaint_is_v4(c.lease_owner) AND c.lease_token > 0 AND c.lease_expires_at IS NOT NULL))
            AND complaint_finite_times(g.updated_at, c.updated_at, c.lease_expires_at, i.requested_at, i.captured_at, s.sampled_at)
            AND s.sampled_at >= c.updated_at AND s.sampled_at >= g.updated_at
            AND s.sampled_at >= '1970-01-01T00:00:00Z'::timestamptz AND s.sampled_at < '10000-01-01T00:00:00Z'::timestamptz
            AND octet_length(to_jsonb(g)::text) BETWEEN 1 AND 524288 AND octet_length(to_jsonb(c)::text) BETWEEN 1 AND 524288
            AND octet_length(to_jsonb(r)::text) BETWEEN 1 AND 524288 AND octet_length(to_jsonb(i)::text) BETWEEN 1 AND 524288
            AND NOT EXISTS (SELECT 1 FROM complaint_test_terminal_intents t WHERE t.data_scope_id = e.scope)
            AND i.state = 'WIRE_FROZEN' AND i.preparing_fencing_token > i.capture_token
            AND c.lease_token >= i.preparing_fencing_token AND c.seal_state = 'SEAL_VERIFIED' AND c.seal_epoch = 1
            AND c.seal_operation_token = i.operation_token AND c.seal_writer_generation = i.writer_generation
            -- Same fixed TEST prefix grammar as TestOwnerDeleteJournalConfigurationV1. A foreign-scope
            -- pending/local alias under the native whole-prefix scan cannot be silently ignored.
            AND NOT EXISTS (SELECT 1 FROM complaint_journal_publications p WHERE p.data_scope_id = e.scope
                OR starts_with(p.object_key, 'complaints/journal/v1/' || d.event_writer::text || '/test/' || e.scope::text || '/ordinary/'))
            AND NOT EXISTS (SELECT 1 FROM complaint_journal_scan_entries t WHERE t.data_scope_id = e.scope)
        ) IS TRUE AS valid, c.lease_owner, c.lease_token, c.lease_expires_at, s.sampled_at, c.seal_verified_at,
            i.operation_token, i.request_owner, i.request_token, i.requested_at, i.capture_owner, i.capture_token, i.captured_at, i.preparing_fencing_token,
            CASE WHEN octet_length(i.state) BETWEEN 1 AND 16 THEN i.state END AS state,
            sha256(convert_to((to_jsonb(i) || jsonb_build_object('row_xmin', i.xmin::text))::text, 'UTF8')) AS slot_fingerprint,
            sha256(convert_to((to_jsonb(g) || jsonb_build_object('row_xmin', g.xmin::text))::text, 'UTF8')) AS global_fingerprint,
            sha256(convert_to((to_jsonb(r) || jsonb_build_object('row_xmin', r.xmin::text))::text, 'UTF8')) AS run_fingerprint,
            sha256(convert_to((to_jsonb(c) - ARRAY['lease_owner','lease_token','lease_expires_at','updated_at'])::text, 'UTF8')) AS control_fingerprint
        FROM e CROSS JOIN d CROSS JOIN b CROSS JOIN j CROSS JOIN s
        LEFT JOIN complaint_test_runs r ON r.data_scope_id = e.scope
        LEFT JOIN complaint_journal_control c ON c.data_scope_id = d.scope
        LEFT JOIN complaint_journal_control g ON g.data_scope_id = $GLOBAL
        LEFT JOIN complaint_test_active_seal_intents i ON i.data_scope_id = e.scope LIMIT 2
    """.trimIndent()

    // The locked current token, not a historical capture/preparing token, is the acquisition CAS.
    val acquire = """
        WITH s AS MATERIALIZED (SELECT clock_timestamp() AS at)
        UPDATE complaint_journal_control c SET lease_owner = ?::uuid, lease_token = c.lease_token + 1,
            lease_expires_at = s.at + interval '30 seconds', updated_at = s.at FROM s
        WHERE c.data_scope_id = ?::uuid AND c.test_only AND c.rotation_id = ?::uuid AND c.rotation_state = 'CAPTURED'
            AND c.lease_token = ? AND c.lease_token < 9223372036854775807
            AND (c.lease_owner IS NULL OR c.lease_expires_at <= s.at) AND isfinite(s.at) AND s.at >= c.updated_at
        RETURNING c.lease_token
    """.trimIndent()
    val renew = TestActiveOrdinarySealSqlV1.renew
    val lease = TestActiveOrdinarySealSqlV1.lease
    val slot = TestActiveOrdinarySealSqlV1.slot
    val sealControl = TestActiveOrdinarySealSqlV1.sealControl

    // All scope rows, not only rows whose ownership happens to match. A foreign third row refuses.
    val scans = """
        SELECT scan_id, pass, data_scope_id, test_only, restore_identity, desired_generation, fencing_token,
            writer_generation, cutoff_epoch, maximum_entries, maximum_bytes, entry_count, entry_bytes,
            CASE WHEN octet_length(state) BETWEEN 1 AND 16 THEN state END AS state,
            CASE WHEN octet_length(manifest_hash) = 32 THEN manifest_hash END AS manifest_hash,
            started_at, finished_at, active_initial_seal_token, active_initial_storage_bytes,
            (isfinite(started_at) AND complaint_finite_times(finished_at)
                AND ((state = 'SCANNING' AND manifest_hash IS NULL AND finished_at IS NULL)
                    OR (state = 'COMPLETE' AND complaint_digest_valid(manifest_hash) AND finished_at IS NOT NULL)
                    OR (state = 'ABANDONED' AND manifest_hash IS NULL AND finished_at IS NOT NULL))) IS TRUE AS valid
        FROM complaint_journal_scan_runs WHERE data_scope_id = ?::uuid ORDER BY pass, scan_id LIMIT 3 FOR UPDATE
    """.trimIndent()
    val insertScan = """
        INSERT INTO complaint_journal_scan_runs (scan_id, pass, data_scope_id, test_only, restore_identity,
            desired_generation, fencing_token, writer_generation, cutoff_epoch, maximum_entries, maximum_bytes,
            entry_count, entry_bytes, state, started_at, active_initial_seal_token, active_initial_storage_bytes)
        VALUES (?::uuid, ?, ?::uuid, true, ?::uuid, ?, ?, ?::uuid, 1, ?, ?, 0, 0, 'SCANNING', ?, ?::uuid, 4416)
    """.trimIndent()
    val completeScan = """
        UPDATE complaint_journal_scan_runs SET state = 'COMPLETE', manifest_hash = ?, finished_at = ?
        WHERE scan_id = ?::uuid AND pass = ? AND data_scope_id = ?::uuid AND test_only
            AND fencing_token = ? AND active_initial_seal_token = ?::uuid AND active_initial_storage_bytes = 4416
            AND state = 'SCANNING' AND entry_count = 0 AND entry_bytes = 0 AND manifest_hash IS NULL AND finished_at IS NULL
            AND started_at = ?
    """.trimIndent()
    val deleteScan = """
        DELETE FROM complaint_journal_scan_runs WHERE scan_id = ?::uuid AND pass = ? AND data_scope_id = ?::uuid AND test_only
            AND fencing_token = ? AND active_initial_seal_token = ?::uuid AND active_initial_storage_bytes = 4416
            AND restore_identity = ?::uuid AND desired_generation = ? AND writer_generation = ?::uuid AND cutoff_epoch = 1
            AND maximum_entries = ? AND maximum_bytes = ? AND entry_count = 0 AND entry_bytes = 0
            AND state = ? AND manifest_hash IS NOT DISTINCT FROM ?::bytea AND started_at = ?
            AND finished_at IS NOT DISTINCT FROM ?::timestamptz
    """.trimIndent()
    // Seventeen fields are supplied from ONE checked canonical document; lease release is in this same update.
    val success = """
        WITH s AS MATERIALIZED (SELECT clock_timestamp() AS at)
        UPDATE complaint_journal_control c SET checkpoint_generation = ?, checkpoint_fencing_token = ?,
            checkpoint_catalog_generation = ?, checkpoint_catalog_hash = ?, checkpoint_writer_generation = ?::uuid,
            checkpoint_cutoff_epoch = ?, checkpoint_configuration_hash = ?, checkpoint_database_identity = ?::uuid,
            checkpoint_restore_identity = ?::uuid, checkpoint_schema = ?, checkpoint_started_at = ?, checkpoint_completed_at = ?,
            checkpoint_object_count = ?, checkpoint_byte_count = ?, checkpoint_result = ?, checkpoint_bytes = ?, checkpoint_hash = ?,
            lease_owner = NULL, lease_expires_at = NULL, updated_at = s.at FROM s
        WHERE c.data_scope_id = ?::uuid AND c.test_only AND c.lease_owner = ?::uuid AND c.lease_token = ?
            AND c.lease_expires_at > s.at AND isfinite(s.at) AND s.at >= c.updated_at AND c.seal_state = 'SEAL_VERIFIED'
            AND c.rotation_state = 'CAPTURED' AND c.publication_epoch = 2 AND c.checkpoint_result IS NULL
            AND NOT EXISTS (SELECT 1 FROM complaint_journal_scan_runs r WHERE r.data_scope_id = c.data_scope_id)
    """.trimIndent()
    val completed = """
        SELECT (checkpoint_generation = ? AND checkpoint_fencing_token = ? AND checkpoint_catalog_generation = ?
            AND checkpoint_catalog_hash = ? AND checkpoint_writer_generation = ?::uuid AND checkpoint_cutoff_epoch = ?
            AND checkpoint_configuration_hash = ? AND checkpoint_database_identity = ?::uuid AND checkpoint_restore_identity = ?::uuid
            AND checkpoint_schema = ? AND checkpoint_started_at = ? AND checkpoint_completed_at = ? AND checkpoint_object_count = ?
            AND checkpoint_byte_count = ? AND checkpoint_result = ? AND checkpoint_bytes = ? AND checkpoint_hash = ?
            AND lease_owner IS NULL AND lease_expires_at IS NULL AND lease_token = ?
            AND checkpoint_completed_at <= updated_at AND updated_at <= clock_timestamp()) IS TRUE AS valid
        FROM complaint_journal_control WHERE data_scope_id = ?::uuid AND test_only
    """.trimIndent()
}
