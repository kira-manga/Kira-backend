package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.complaint.infrastructure.ComplaintInstallationTestRunRows

/** Fixed first-only TEST SQL. Neither a supplied query nor terminal/LIVE authority enters this family. */
internal object TestActiveFirstCutSqlV1 {
    private const val GLOBAL = "'00000000-0000-0000-0000-000000000000'::uuid"
    private const val MAX_LONG = "9223372036854775807"
    val authenticate = "SELECT session_user = ? AND current_user = ? AND current_database() = ? AS valid"
    val lockGlobal = "SELECT data_scope_id FROM complaint_journal_control WHERE data_scope_id = $GLOBAL FOR UPDATE"
    val lockScope = "SELECT data_scope_id FROM complaint_journal_control WHERE data_scope_id = ?::uuid AND test_only FOR UPDATE"
    val lockRun = "SELECT data_scope_id FROM complaint_test_runs WHERE data_scope_id = ?::uuid AND test_only FOR UPDATE"
    val lockSlot = "SELECT operation_token FROM complaint_test_active_seal_intents WHERE data_scope_id = ?::uuid LIMIT 2 FOR UPDATE"

    // Exact same20 privately registered run/control/global comparison arguments used by real
    // identity holders; J is a21st independently retained commitment. Current enrollment/unused
    // reserve is validated, not compared to the unused first-PROJECT effect.
    private val expected = """
        WITH expected_run AS MATERIALIZED (
            SELECT ?::uuid AS scope, ?::bytea AS configuration_hash, ?::bigint AS installation_limit,
                ?::bigint AS generation, ?::bytea AS activation_hash, ?::timestamptz AS created_at, ?::bigint[] AS original_reserve
        ), expected_control AS MATERIALIZED (
            SELECT ?::uuid AS scope, ?::bigint AS desired_generation, ?::integer AS implementation_schema,
                ?::bytea AS configuration_hash, ?::uuid AS database_identity, ?::uuid AS restore_identity,
                ?::uuid AS event_writer, ?::uuid AS catalog_writer, ?::bytea AS trust_hash, ?::bigint AS generation, ?::bytea AS activation_hash
        ), expected_global AS MATERIALIZED (SELECT ?::bigint AS desired_generation, ?::bytea AS configuration_hash),
            expected_journal AS MATERIALIZED (SELECT ?::bytea AS journal_hash)
    """.trimIndent()

    private val emptySeal = """
        c.retention_lease_owner IS NULL AND c.retention_lease_token = 0 AND c.retention_lease_expires_at IS NULL
        AND c.seal_state IS NULL AND c.seal_epoch IS NULL AND c.seal_writer_generation IS NULL AND c.seal_operation_token IS NULL
        AND c.seal_object_key IS NULL AND c.seal_bytes IS NULL AND c.seal_hash IS NULL AND c.seal_object_version IS NULL
        AND c.seal_ciphertext_hash IS NULL AND c.seal_retain_until IS NULL AND c.seal_verified_at IS NULL
        AND c.seal_verification_bytes IS NULL AND c.seal_verification_hash IS NULL
        AND c.seal_format IS NULL AND c.seal_rotation_id IS NULL AND c.seal_rotation_sequence IS NULL
        AND c.seal_preparing_fencing_token IS NULL AND c.seal_routing_key_id IS NULL AND c.seal_epoch_start IS NULL AND c.seal_preceding_hash IS NULL
        AND c.checkpoint_generation IS NULL AND c.checkpoint_fencing_token IS NULL AND c.checkpoint_catalog_generation IS NULL
        AND c.checkpoint_catalog_hash IS NULL AND c.checkpoint_writer_generation IS NULL AND c.checkpoint_cutoff_epoch IS NULL
        AND c.checkpoint_configuration_hash IS NULL AND c.checkpoint_database_identity IS NULL AND c.checkpoint_restore_identity IS NULL
        AND c.checkpoint_schema IS NULL AND c.checkpoint_started_at IS NULL AND c.checkpoint_completed_at IS NULL
        AND c.checkpoint_object_count IS NULL AND c.checkpoint_byte_count IS NULL AND c.checkpoint_result IS NULL
        AND c.checkpoint_bytes IS NULL AND c.checkpoint_hash IS NULL
    """.trimIndent()
    private val emptyRotation = """
        c.rotation_sequence = 0 AND c.rotation_id IS NULL AND c.rotation_state IS NULL AND c.rotation_epoch_before IS NULL
        AND c.rotation_implementation_schema IS NULL AND c.rotation_desired_generation IS NULL AND c.rotation_desired_configuration_hash IS NULL
        AND c.rotation_database_identity IS NULL AND c.rotation_restore_identity IS NULL AND c.rotation_event_writer_generation IS NULL
        AND c.rotation_accepted_catalog_generation IS NULL AND c.rotation_accepted_catalog_hash IS NULL
        AND c.rotation_trust_bundle_hash IS NULL AND c.rotation_catalog_writer_generation IS NULL
        AND c.rotation_request_owner IS NULL AND c.rotation_request_token IS NULL AND c.rotation_requested_at IS NULL
        AND c.rotation_capture_owner IS NULL AND c.rotation_capture_token IS NULL AND c.rotation_captured_at IS NULL AND c.rotation_epoch_after IS NULL
    """.trimIndent()
    private val paidBase = """
        i.schema_version = 1 AND i.test_only AND i.data_scope_id = e.scope AND i.operation_token = c.rotation_id
        AND i.run_created_at = e.created_at AND i.implementation_schema = d.implementation_schema AND i.desired_generation = d.desired_generation
        AND i.configuration_hash = d.configuration_hash AND i.journal_configuration_hash = j.journal_hash
        AND i.database_identity = d.database_identity AND i.restore_identity = d.restore_identity AND i.writer_generation = d.event_writer
        AND i.activation_catalog_generation = e.generation AND i.activation_catalog_hash = e.activation_hash
        AND i.accepted_catalog_generation = d.generation AND i.accepted_catalog_hash = d.activation_hash
        AND i.trust_bundle_hash = d.trust_hash AND i.catalog_writer_generation = d.catalog_writer
        AND i.rotation_sequence = 1 AND i.epoch_start = 1 AND i.epoch_end = c.rotation_epoch_before
        AND i.request_owner = c.rotation_request_owner AND i.request_token = c.rotation_request_token AND i.requested_at = c.rotation_requested_at
        AND i.charged_storage_bytes = 2097152 AND i.state = 'RESERVED'
        AND i.object_id IS NULL AND i.object_key IS NULL AND i.routing_key_id IS NULL AND i.preparing_fencing_token IS NULL
        AND i.seal_encoding_hash IS NULL AND i.canonicalizer IS NULL AND i.canonical_bytes IS NULL AND i.canonical_hash IS NULL
        AND i.retention_floor IS NULL AND i.created_at IS NULL AND i.wire_bytes IS NULL AND i.wire_hash IS NULL
        AND i.checksum_sha256 IS NULL AND i.content_type IS NULL AND i.object_lock_mode IS NULL AND i.retain_until IS NULL
        AND i.metadata_bytes IS NULL AND i.metadata_hash IS NULL AND i.frozen_at IS NULL
    """.trimIndent()
    private val rotation = """
        c.rotation_sequence = 1 AND complaint_is_v4(c.rotation_id) AND c.rotation_epoch_before = 1
        AND c.rotation_implementation_schema = d.implementation_schema AND c.rotation_desired_generation = d.desired_generation
        AND c.rotation_desired_configuration_hash = d.configuration_hash AND c.rotation_database_identity = d.database_identity
        AND c.rotation_restore_identity = d.restore_identity AND c.rotation_event_writer_generation = d.event_writer
        AND c.rotation_accepted_catalog_generation = d.generation AND c.rotation_accepted_catalog_hash = d.activation_hash
        AND c.rotation_trust_bundle_hash = d.trust_hash AND c.rotation_catalog_writer_generation = d.catalog_writer
        AND complaint_is_v4(c.rotation_request_owner) AND c.rotation_request_token > 0 AND c.rotation_requested_at >= r.created_at
        AND ($paidBase)
        AND ((c.rotation_state = 'REQUESTED' AND c.publication_epoch = 1 AND c.scan_requested
                AND c.rotation_capture_owner IS NULL AND c.rotation_capture_token IS NULL AND c.rotation_captured_at IS NULL AND c.rotation_epoch_after IS NULL
                AND i.capture_owner IS NULL AND i.capture_token IS NULL AND i.captured_at IS NULL AND i.epoch_after IS NULL)
            OR (c.rotation_state = 'CAPTURED' AND c.publication_epoch = 2 AND NOT c.scan_requested
                AND complaint_is_v4(c.rotation_capture_owner) AND c.rotation_capture_token > 0 AND c.rotation_captured_at >= c.rotation_requested_at
                AND c.rotation_epoch_after = 2 AND i.capture_owner = c.rotation_capture_owner AND i.capture_token = c.rotation_capture_token
                AND i.captured_at = c.rotation_captured_at AND i.epoch_after = c.rotation_epoch_after))
    """.trimIndent()

    val read = """
        $expected, sampled AS MATERIALIZED (SELECT clock_timestamp() AS sampled_at)
        SELECT (
            e.scope = d.scope AND r.test_only AND r.state = 'ACTIVE' AND (${ComplaintInstallationTestRunRows.activeShape})
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
            AND NOT c.maintenance_closed AND NOT c.creation_closed AND c.pending_projection_token IS NULL AND ($emptySeal)
            AND c.lease_token BETWEEN 0 AND $MAX_LONG
            AND ((c.lease_owner IS NULL AND c.lease_expires_at IS NULL)
                OR (complaint_is_v4(c.lease_owner) AND c.lease_token > 0 AND c.lease_expires_at IS NOT NULL))
            AND NOT g.test_only AND g.implementation_schema = 1 AND g.desired_generation = b.desired_generation
            AND g.desired_configuration_hash IS NOT DISTINCT FROM b.configuration_hash
            AND g.database_identity = d.database_identity AND g.restore_identity = d.restore_identity
            AND g.event_writer_generation = d.event_writer AND g.catalog_writer_generation = d.catalog_writer
            AND g.trust_bundle_hash = d.trust_hash AND g.accepted_catalog_generation = d.generation AND g.accepted_catalog_hash = d.activation_hash
            AND NOT g.maintenance_closed AND NOT g.creation_closed AND g.pending_projection_token IS NULL AND g.publication_epoch > 0
            AND complaint_finite_times(g.updated_at, c.updated_at, c.lease_expires_at, c.rotation_requested_at, c.rotation_captured_at, s.sampled_at)
            AND s.sampled_at >= c.updated_at AND s.sampled_at >= g.updated_at
            AND s.sampled_at >= '1970-01-01T00:00:00Z'::timestamptz AND s.sampled_at < '10000-01-01T00:00:00Z'::timestamptz
            AND octet_length(to_jsonb(g)::text) BETWEEN 1 AND 524288 AND octet_length(to_jsonb(c)::text) BETWEEN 1 AND 524288
            AND octet_length(to_jsonb(r)::text) BETWEEN 1 AND 524288
            AND ((($emptyRotation) AND c.publication_epoch = 1 AND i.operation_token IS NULL) OR ($rotation))
        ) IS TRUE AS valid,
            c.publication_epoch, c.scan_requested, c.lease_owner, c.lease_token, c.lease_expires_at, s.sampled_at,
            c.rotation_sequence, c.rotation_id, c.rotation_state, c.rotation_epoch_before,
            c.rotation_request_owner, c.rotation_request_token, c.rotation_requested_at,
            c.rotation_capture_owner, c.rotation_capture_token, c.rotation_captured_at, c.rotation_epoch_after,
            sha256(convert_to((to_jsonb(g) || jsonb_build_object('row_xmin', g.xmin::text))::text, 'UTF8')) AS global_fingerprint,
            sha256(convert_to((to_jsonb(c) || jsonb_build_object('row_xmin', c.xmin::text))::text, 'UTF8')) AS control_fingerprint,
            sha256(convert_to((to_jsonb(r) || jsonb_build_object('row_xmin', r.xmin::text))::text, 'UTF8')) AS run_fingerprint,
            CASE WHEN i.operation_token IS NOT NULL THEN sha256(convert_to((to_jsonb(i) || jsonb_build_object('row_xmin', i.xmin::text))::text, 'UTF8')) END AS slot_fingerprint
        FROM expected_run e CROSS JOIN expected_control d CROSS JOIN expected_global b CROSS JOIN expected_journal j CROSS JOIN sampled s
        LEFT JOIN complaint_test_runs r ON r.data_scope_id = e.scope
        LEFT JOIN complaint_journal_control c ON c.data_scope_id = d.scope
        LEFT JOIN complaint_journal_control g ON g.data_scope_id = $GLOBAL
        LEFT JOIN complaint_test_active_seal_intents i ON i.data_scope_id = e.scope
        LIMIT 2
    """.trimIndent()

    val acquireLease = """
        WITH sampled AS MATERIALIZED (SELECT clock_timestamp() AS sampled_at)
        UPDATE complaint_journal_control c SET lease_owner = ?::uuid, lease_token = c.lease_token + 1,
            lease_expires_at = s.sampled_at + interval '30 seconds', updated_at = s.sampled_at
        FROM sampled s WHERE c.data_scope_id = ?::uuid AND c.test_only
            AND sha256(convert_to((to_jsonb(c) || jsonb_build_object('row_xmin', c.xmin::text))::text, 'UTF8')) = ?::bytea
            AND c.lease_token < $MAX_LONG AND isfinite(s.sampled_at) AND s.sampled_at >= c.updated_at
            AND (c.lease_owner IS NULL OR c.lease_expires_at <= s.sampled_at)
    """.trimIndent()

    val request = """
        WITH sampled AS MATERIALIZED (SELECT clock_timestamp() AS sampled_at)
        UPDATE complaint_journal_control c SET scan_requested = true, rotation_sequence = 1, rotation_id = ?::uuid,
            rotation_state = 'REQUESTED', rotation_epoch_before = c.publication_epoch,
            rotation_implementation_schema = c.implementation_schema, rotation_desired_generation = c.desired_generation,
            rotation_desired_configuration_hash = c.desired_configuration_hash, rotation_database_identity = c.database_identity,
            rotation_restore_identity = c.restore_identity, rotation_event_writer_generation = c.event_writer_generation,
            rotation_accepted_catalog_generation = c.accepted_catalog_generation, rotation_accepted_catalog_hash = c.accepted_catalog_hash,
            rotation_trust_bundle_hash = c.trust_bundle_hash, rotation_catalog_writer_generation = c.catalog_writer_generation,
            rotation_request_owner = c.lease_owner, rotation_request_token = c.lease_token,
            rotation_requested_at = s.sampled_at, updated_at = s.sampled_at
        FROM sampled s WHERE c.data_scope_id = ?::uuid AND c.test_only AND c.rotation_sequence = 0 AND c.publication_epoch = 1
            AND c.lease_owner = ?::uuid AND c.lease_token = ?::bigint AND c.lease_expires_at = ?::timestamptz
            AND c.lease_expires_at > s.sampled_at AND isfinite(s.sampled_at) AND s.sampled_at >= c.updated_at
            AND sha256(convert_to((to_jsonb(c) || jsonb_build_object('row_xmin', c.xmin::text))::text, 'UTF8')) = ?::bytea
    """.trimIndent()

    val insertSlot = """
        $expected
        INSERT INTO complaint_test_active_seal_intents (schema_version, operation_token, data_scope_id, test_only, run_created_at,
            implementation_schema, desired_generation, configuration_hash, journal_configuration_hash, database_identity, restore_identity,
            writer_generation, activation_catalog_generation, activation_catalog_hash, accepted_catalog_generation, accepted_catalog_hash,
            trust_bundle_hash, catalog_writer_generation, rotation_sequence, epoch_start, epoch_end, request_owner, request_token, requested_at,
            charged_storage_bytes, state)
        SELECT 1, c.rotation_id, e.scope, true, e.created_at, d.implementation_schema, d.desired_generation, d.configuration_hash, j.journal_hash,
            d.database_identity, d.restore_identity, d.event_writer, e.generation, e.activation_hash, d.generation, d.activation_hash,
            d.trust_hash, d.catalog_writer, 1, 1, c.rotation_epoch_before, c.rotation_request_owner, c.rotation_request_token, c.rotation_requested_at,
            2097152, 'RESERVED'
        FROM expected_run e CROSS JOIN expected_control d CROSS JOIN expected_global b CROSS JOIN expected_journal j
        JOIN complaint_journal_control c ON c.data_scope_id = d.scope
        WHERE e.scope = d.scope AND c.test_only AND c.rotation_state = 'REQUESTED' AND c.rotation_id = ?::uuid
    """.trimIndent()

    /** Same sampled, actually current scoped owner only. Store provenance then relinquish owner/expiry; keep the monotonic token. */
    val capture = """
        WITH sampled AS MATERIALIZED (SELECT clock_timestamp() AS sampled_at)
        UPDATE complaint_journal_control c SET publication_epoch = c.publication_epoch + 1, scan_requested = false,
            rotation_state = 'CAPTURED', rotation_capture_owner = c.lease_owner, rotation_capture_token = c.lease_token,
            rotation_captured_at = s.sampled_at, rotation_epoch_after = c.publication_epoch + 1,
            lease_owner = NULL, lease_expires_at = NULL, updated_at = s.sampled_at
        FROM sampled s WHERE c.data_scope_id = ?::uuid AND c.test_only AND c.rotation_id = ?::uuid
            AND c.rotation_sequence = 1 AND c.rotation_state = 'REQUESTED' AND c.rotation_epoch_before = 1 AND c.publication_epoch = 1 AND c.scan_requested
            AND c.lease_owner = ?::uuid AND c.lease_token = ?::bigint AND c.lease_expires_at = ?::timestamptz
            AND c.lease_expires_at > s.sampled_at AND isfinite(s.sampled_at) AND s.sampled_at >= c.updated_at
            AND sha256(convert_to((to_jsonb(c) || jsonb_build_object('row_xmin', c.xmin::text))::text, 'UTF8')) = ?::bytea
    """.trimIndent()
    val captureSlot = """
        UPDATE complaint_test_active_seal_intents i SET capture_owner = c.rotation_capture_owner,
            capture_token = c.rotation_capture_token, captured_at = c.rotation_captured_at, epoch_after = c.rotation_epoch_after
        FROM complaint_journal_control c WHERE i.data_scope_id = ?::uuid AND i.operation_token = ?::uuid AND i.test_only
            AND c.data_scope_id = i.data_scope_id AND c.test_only AND c.rotation_id = i.operation_token AND c.rotation_state = 'CAPTURED'
            AND i.state = 'RESERVED' AND i.capture_owner IS NULL
            AND sha256(convert_to((to_jsonb(i) || jsonb_build_object('row_xmin', i.xmin::text))::text, 'UTF8')) = ?::bytea
    """.trimIndent()
}
