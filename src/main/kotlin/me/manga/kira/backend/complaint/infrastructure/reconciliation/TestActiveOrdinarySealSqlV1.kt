package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.complaint.infrastructure.ComplaintInstallationTestRunRows

/** Fixed ACTIVE first-range writer. No terminal ledger/V21 writer, V19 LIVE link, receipt or APPLY. */
internal object TestActiveOrdinarySealSqlV1 {
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
            AND NOT c.maintenance_closed AND NOT c.creation_closed AND c.pending_projection_token IS NULL AND ($noHistory)
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
            AND NOT EXISTS (SELECT 1 FROM complaint_journal_scan_runs t WHERE t.data_scope_id = e.scope)
        ) IS TRUE AS valid, c.lease_owner, c.lease_token, c.lease_expires_at, s.sampled_at,
            i.operation_token, i.request_owner, i.request_token, i.requested_at, i.capture_owner, i.capture_token, i.captured_at,
            CASE WHEN octet_length(i.state) BETWEEN 1 AND 16 THEN i.state END AS state,
            sha256(convert_to((to_jsonb(i) || jsonb_build_object('row_xmin', i.xmin::text))::text, 'UTF8')) AS slot_fingerprint
        FROM e CROSS JOIN d CROSS JOIN b CROSS JOIN j CROSS JOIN s
        LEFT JOIN complaint_test_runs r ON r.data_scope_id = e.scope
        LEFT JOIN complaint_journal_control c ON c.data_scope_id = d.scope
        LEFT JOIN complaint_journal_control g ON g.data_scope_id = $GLOBAL
        LEFT JOIN complaint_test_active_seal_intents i ON i.data_scope_id = e.scope LIMIT 2
    """.trimIndent()
    val acquire = """
        WITH s AS MATERIALIZED (SELECT clock_timestamp() AS at)
        UPDATE complaint_journal_control c SET lease_owner = ?::uuid, lease_token = c.lease_token + 1,
            lease_expires_at = s.at + interval '30 seconds', updated_at = s.at FROM s
        WHERE c.data_scope_id = ?::uuid AND c.test_only AND c.rotation_id = ?::uuid AND c.rotation_state = 'CAPTURED'
            AND c.lease_token >= ? AND c.lease_token < 9223372036854775807
            AND (c.lease_owner IS NULL OR c.lease_expires_at <= s.at) AND isfinite(s.at) AND s.at >= c.updated_at
        RETURNING c.lease_token
    """.trimIndent()
    val renew = """
        WITH s AS MATERIALIZED (SELECT clock_timestamp() AS at)
        UPDATE complaint_journal_control c SET lease_expires_at = s.at + interval '30 seconds', updated_at = s.at FROM s
        WHERE c.data_scope_id = ?::uuid AND c.test_only AND c.lease_owner = ?::uuid AND c.lease_token = ?
            AND c.lease_expires_at > s.at AND isfinite(s.at) AND s.at >= c.updated_at
    """.trimIndent()
    val lease = "SELECT (lease_owner = ?::uuid AND lease_token = ? AND lease_expires_at > clock_timestamp()) IS TRUE AS valid FROM complaint_journal_control WHERE data_scope_id = ?::uuid AND test_only"
    val canonical = """
        UPDATE complaint_test_active_seal_intents SET state = 'CANONICAL', object_id = ?, object_key = ?, routing_key_id = ?,
            preparing_fencing_token = ?, seal_encoding_hash = ?, canonicalizer = 'kcj-1', canonical_bytes = ?, canonical_hash = ?, retention_floor = ?, created_at = ?
        WHERE operation_token = ?::uuid AND data_scope_id = ?::uuid AND test_only AND state = 'RESERVED' AND capture_owner IS NOT NULL
    """.trimIndent()
    val freeze = """
        UPDATE complaint_test_active_seal_intents SET state = 'WIRE_FROZEN', wire_bytes = ?, wire_hash = ?, checksum_sha256 = ?,
            content_type = 'application/octet-stream', object_lock_mode = 'COMPLIANCE', retain_until = ?, metadata_bytes = ?, metadata_hash = ?, frozen_at = ?
        WHERE operation_token = ?::uuid AND data_scope_id = ?::uuid AND test_only AND state = 'CANONICAL' AND canonical_hash = ?
    """.trimIndent()
    // Existing V14 evidence columns only. Shared syntax SQL has no terminal ledger or V21 operation in these two statements.
    val prepareControl = me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinarySealSqlV1.prepareControl
    val verifyControl = me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinarySealSqlV1.verifyControl
    val sealControl = """
        SELECT CASE WHEN octet_length(seal_state) BETWEEN 1 AND 16 THEN seal_state END AS seal_state,
            seal_epoch, seal_writer_generation, seal_operation_token,
            CASE WHEN octet_length(seal_object_key) BETWEEN 1 AND 1024 THEN seal_object_key END AS seal_object_key,
            CASE WHEN complaint_bytes_match(seal_bytes, seal_hash, 65536) THEN seal_bytes END AS seal_bytes,
            CASE WHEN octet_length(seal_hash) = 32 THEN seal_hash END AS seal_hash,
            CASE WHEN octet_length(seal_object_version) BETWEEN 1 AND 1024 THEN seal_object_version END AS seal_object_version,
            CASE WHEN octet_length(seal_ciphertext_hash) = 32 THEN seal_ciphertext_hash END AS seal_ciphertext_hash,
            seal_retain_until, seal_verified_at,
            CASE WHEN complaint_bytes_match(seal_verification_bytes, seal_verification_hash, 65536) THEN seal_verification_bytes END AS seal_verification_bytes,
            CASE WHEN octet_length(seal_verification_hash) = 32 THEN seal_verification_hash END AS seal_verification_hash,
            ((seal_state IS NULL AND seal_epoch IS NULL AND seal_writer_generation IS NULL AND seal_operation_token IS NULL
                AND seal_object_key IS NULL AND seal_bytes IS NULL AND seal_hash IS NULL AND seal_object_version IS NULL
                AND seal_ciphertext_hash IS NULL AND seal_retain_until IS NULL AND seal_verified_at IS NULL
                AND seal_verification_bytes IS NULL AND seal_verification_hash IS NULL)
            OR (seal_state IN ('SEAL_PREPARED', 'SEAL_VERIFIED') AND seal_epoch > 0
                AND complaint_is_v4(seal_writer_generation) AND complaint_is_v4(seal_operation_token)
                AND complaint_ascii_valid(seal_object_key, 1024) AND complaint_bytes_match(seal_bytes, seal_hash, 65536)
                AND ((seal_state = 'SEAL_PREPARED' AND seal_object_version IS NULL AND seal_ciphertext_hash IS NULL
                    AND seal_retain_until IS NULL AND seal_verified_at IS NULL AND seal_verification_bytes IS NULL AND seal_verification_hash IS NULL)
                OR (seal_state = 'SEAL_VERIFIED' AND complaint_opaque_valid(seal_object_version, 1024) AND seal_object_version <> 'null'
                    AND complaint_digest_valid(seal_ciphertext_hash) AND seal_retain_until IS NOT NULL AND seal_verified_at IS NOT NULL
                    AND complaint_finite_times(seal_retain_until, seal_verified_at) AND seal_retain_until > seal_verified_at
                    AND complaint_bytes_match(seal_verification_bytes, seal_verification_hash, 65536))))) IS TRUE AS valid
        FROM complaint_journal_control WHERE data_scope_id = ?::uuid AND test_only
    """.trimIndent()
    val slot = """
        SELECT CASE WHEN octet_length(i.state) BETWEEN 1 AND 16 THEN i.state END AS state,
            CASE WHEN octet_length(i.object_id) = 43 THEN i.object_id END AS object_id,
            CASE WHEN octet_length(i.object_key) BETWEEN 1 AND 1024 THEN i.object_key END AS object_key,
            CASE WHEN octet_length(i.routing_key_id) BETWEEN 1 AND 64 THEN i.routing_key_id END AS routing_key_id,
            i.preparing_fencing_token, CASE WHEN octet_length(i.seal_encoding_hash) = 32 THEN i.seal_encoding_hash END AS seal_encoding_hash,
            i.retention_floor, i.created_at, i.retain_until, i.frozen_at,
            CASE WHEN octet_length(i.checksum_sha256) = 44 THEN i.checksum_sha256 END AS checksum_sha256,
            CASE WHEN octet_length(i.content_type) BETWEEN 1 AND 24 THEN i.content_type END AS content_type,
            CASE WHEN octet_length(i.object_lock_mode) BETWEEN 1 AND 10 THEN i.object_lock_mode END AS object_lock_mode,
            CASE WHEN complaint_bytes_match(i.canonical_bytes, i.canonical_hash, 65536) THEN i.canonical_bytes END AS canonical_bytes,
            CASE WHEN octet_length(i.canonical_hash) = 32 THEN i.canonical_hash END AS canonical_hash,
            CASE WHEN complaint_bytes_match(i.wire_bytes, i.wire_hash, 98304) THEN i.wire_bytes END AS wire_bytes,
            CASE WHEN octet_length(i.wire_hash) = 32 THEN i.wire_hash END AS wire_hash,
            CASE WHEN complaint_bytes_match(i.metadata_bytes, i.metadata_hash, 512) THEN i.metadata_bytes END AS metadata_bytes,
            CASE WHEN octet_length(i.metadata_hash) = 32 THEN i.metadata_hash END AS metadata_hash,
            (i.canonicalizer = 'kcj-1' AND i.state IN ('CANONICAL','WIRE_FROZEN')
                AND complaint_event_id_valid(i.object_id) AND complaint_ascii_valid(i.object_key, 1024)
                AND i.routing_key_id ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$' AND i.preparing_fencing_token > 0
                AND complaint_digest_valid(i.seal_encoding_hash) AND i.capture_owner IS NOT NULL
                AND complaint_bytes_match(i.canonical_bytes, i.canonical_hash, 65536)
                AND i.object_key = 'complaints/journal/v1/' || i.writer_generation::text || '/test/' || i.data_scope_id::text
                    || '/seal-terminal/' || i.epoch_end::text || '/' || i.routing_key_id || '/epoch-seal/' || i.object_id || '.kjev'
                AND CASE WHEN complaint_test_terminal_instant_valid(i.created_at) AND complaint_test_terminal_instant_valid(i.retention_floor)
                    THEN i.retention_floor AT TIME ZONE 'UTC' >= (i.created_at AT TIME ZONE 'UTC') + interval '10 years'
                        AND i.created_at >= i.captured_at ELSE false END
                AND ((i.state = 'CANONICAL' AND i.wire_bytes IS NULL AND i.wire_hash IS NULL AND i.checksum_sha256 IS NULL
                    AND i.content_type IS NULL AND i.object_lock_mode IS NULL AND i.retain_until IS NULL AND i.metadata_bytes IS NULL AND i.metadata_hash IS NULL AND i.frozen_at IS NULL)
                OR (i.state = 'WIRE_FROZEN' AND complaint_bytes_match(i.wire_bytes, i.wire_hash, 98304)
                    AND i.checksum_sha256 = encode(i.wire_hash, 'base64') AND i.content_type = 'application/octet-stream'
                    AND i.object_lock_mode = 'COMPLIANCE' AND complaint_bytes_match(i.metadata_bytes, i.metadata_hash, 512)
                    AND complaint_test_terminal_instant_valid(i.retain_until) AND complaint_test_terminal_instant_valid(i.frozen_at)
                    AND i.retain_until >= i.retention_floor AND i.retain_until > i.frozen_at AND i.frozen_at >= i.created_at
                    AND i.metadata_bytes = complaint_test_terminal_metadata(i.object_id, i.wire_hash, i.retain_until)))) IS TRUE AS valid
        FROM complaint_test_active_seal_intents i WHERE i.operation_token = ?::uuid AND i.data_scope_id = ?::uuid AND i.test_only
    """.trimIndent()
}
