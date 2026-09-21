package me.manga.kira.backend.complaint.infrastructure.terminal

import me.manga.kira.backend.complaint.infrastructure.OwnerDeletePersistenceSql

/** Fixed registered SEALED-only SQL. No caller SQL, state-filtered inventory or legacy guard change. */
internal object TestOrdinaryDrainSqlV1 {
    const val PAGE = 100
    private val expectedRun = """
        WITH expected AS MATERIALIZED (SELECT ?::uuid AS scope, ?::bytea AS configuration_hash, ?::bigint AS installation_limit,
            ?::bigint AS generation, ?::bytea AS activation_hash, ?::timestamptz AS created_at, ?::bigint[] AS original_reserve)
    """.trimIndent()
    val run = """
        $expectedRun
        SELECT (r.test_only AND r.state = 'SEALED' AND r.configuration_hash = e.configuration_hash AND r.accounting_version = 1
            AND r.installation_limit = e.installation_limit AND r.enrolled_count BETWEEN 0 AND r.installation_limit
            AND r.activation_catalog_generation = e.generation AND r.activation_catalog_hash = e.activation_hash
            AND r.created_at = e.created_at AND isfinite(r.created_at) AND r.original_reserve = e.original_reserve
            AND complaint_vector_valid(r.original_reserve) AND complaint_vector_lte(r.unused_reserve, r.original_reserve)
            AND isfinite(r.sealed_at) AND r.sealed_at >= r.created_at AND r.sealed_at <= clock_timestamp()
            AND r.sealed_at >= '1970-01-01T00:00:00Z'::timestamptz AND r.sealed_at < '10000-01-01T00:00:00Z'::timestamptz
            AND r.purging_at IS NULL AND r.purged_at IS NULL
            AND r.event_manifest_count IS NULL AND r.event_manifest_root IS NULL
            AND r.installation_manifest_count IS NULL AND r.installation_manifest_root IS NULL AND r.installation_chunk_count IS NULL
            AND r.retired_count IS NULL AND r.deleted_count IS NULL AND r.terminal_event_id IS NULL AND r.terminal_object_key IS NULL
            AND r.terminal_object_version IS NULL AND r.terminal_ciphertext_hash IS NULL
            AND r.terminal_catalog_generation IS NULL AND r.terminal_catalog_hash IS NULL
            AND ((r.permanent_denial_bytes IS NULL AND r.permanent_denial_hash IS NULL)
                OR complaint_bytes_match(r.permanent_denial_bytes, r.permanent_denial_hash, 51291))
            AND ((r.final_ordinary_epoch IS NULL AND r.terminal_seal_epoch IS NULL AND r.generation_seal_count IS NULL
                    AND r.generation_seal_root IS NULL AND r.seal_set_bytes IS NULL AND r.seal_set_hash IS NULL)
                OR (r.final_ordinary_epoch > 0 AND r.terminal_seal_epoch - 1 = r.final_ordinary_epoch AND r.generation_seal_count = 1
                    AND complaint_digest_valid(r.generation_seal_root) AND complaint_bytes_match(r.seal_set_bytes, r.seal_set_hash, 65536)
                    AND r.permanent_denial_bytes IS NOT NULL))) IS TRUE AS valid,
            r.sealed_at, r.installation_limit, r.enrolled_count, r.original_reserve, r.unused_reserve,
            CASE WHEN octet_length(r.permanent_denial_bytes) BETWEEN 1 AND 51291 THEN r.permanent_denial_bytes END AS progress_bytes,
            CASE WHEN octet_length(r.permanent_denial_hash) = 32 THEN r.permanent_denial_hash END AS progress_hash,
            r.final_ordinary_epoch, r.terminal_seal_epoch, r.generation_seal_count,
            CASE WHEN octet_length(r.generation_seal_root) = 32 THEN r.generation_seal_root END AS generation_seal_root,
            CASE WHEN octet_length(r.seal_set_bytes) BETWEEN 1 AND 65536 THEN r.seal_set_bytes END AS seal_set_bytes,
            CASE WHEN octet_length(r.seal_set_hash) = 32 THEN r.seal_set_hash END AS seal_set_hash
        FROM complaint_test_runs r CROSS JOIN expected e WHERE r.data_scope_id = e.scope FOR UPDATE OF r
    """.trimIndent()

    // Exact two-ordinary-seal relation, never a count-only relaxation of the first-history query.
    val runWithActiveHistory = """
        $expectedRun
        SELECT (r.test_only AND r.state = 'SEALED' AND r.configuration_hash = e.configuration_hash AND r.accounting_version = 1
            AND r.installation_limit = e.installation_limit AND r.enrolled_count BETWEEN 0 AND r.installation_limit
            AND r.activation_catalog_generation = e.generation AND r.activation_catalog_hash = e.activation_hash
            AND r.created_at = e.created_at AND isfinite(r.created_at) AND r.original_reserve = e.original_reserve
            AND complaint_vector_valid(r.original_reserve) AND complaint_vector_lte(r.unused_reserve, r.original_reserve)
            AND isfinite(r.sealed_at) AND r.sealed_at >= r.created_at AND r.sealed_at <= clock_timestamp()
            AND r.sealed_at >= '1970-01-01T00:00:00Z'::timestamptz AND r.sealed_at < '10000-01-01T00:00:00Z'::timestamptz
            AND r.purging_at IS NULL AND r.purged_at IS NULL
            AND r.event_manifest_count IS NULL AND r.event_manifest_root IS NULL
            AND r.installation_manifest_count IS NULL AND r.installation_manifest_root IS NULL AND r.installation_chunk_count IS NULL
            AND r.retired_count IS NULL AND r.deleted_count IS NULL AND r.terminal_event_id IS NULL AND r.terminal_object_key IS NULL
            AND r.terminal_object_version IS NULL AND r.terminal_ciphertext_hash IS NULL
            AND r.terminal_catalog_generation IS NULL AND r.terminal_catalog_hash IS NULL
            AND ((r.permanent_denial_bytes IS NULL AND r.permanent_denial_hash IS NULL)
                OR complaint_bytes_match(r.permanent_denial_bytes, r.permanent_denial_hash, 51291))
            AND ((r.final_ordinary_epoch IS NULL AND r.terminal_seal_epoch IS NULL AND r.generation_seal_count IS NULL
                    AND r.generation_seal_root IS NULL AND r.seal_set_bytes IS NULL AND r.seal_set_hash IS NULL)
                OR (r.final_ordinary_epoch = 2 AND r.terminal_seal_epoch = 3 AND r.generation_seal_count = 2
                    AND complaint_digest_valid(r.generation_seal_root) AND complaint_bytes_match(r.seal_set_bytes, r.seal_set_hash, 65536)
                    AND r.permanent_denial_bytes IS NOT NULL))) IS TRUE AS valid,
            r.sealed_at, r.installation_limit, r.enrolled_count, r.original_reserve, r.unused_reserve,
            CASE WHEN octet_length(r.permanent_denial_bytes) BETWEEN 1 AND 51291 THEN r.permanent_denial_bytes END AS progress_bytes,
            CASE WHEN octet_length(r.permanent_denial_hash) = 32 THEN r.permanent_denial_hash END AS progress_hash,
            r.final_ordinary_epoch, r.terminal_seal_epoch, r.generation_seal_count,
            CASE WHEN octet_length(r.generation_seal_root) = 32 THEN r.generation_seal_root END AS generation_seal_root,
            CASE WHEN octet_length(r.seal_set_bytes) BETWEEN 1 AND 65536 THEN r.seal_set_bytes END AS seal_set_bytes,
            CASE WHEN octet_length(r.seal_set_hash) = 32 THEN r.seal_set_hash END AS seal_set_hash
        FROM complaint_test_runs r CROSS JOIN expected e WHERE r.data_scope_id = e.scope FOR UPDATE OF r
    """.trimIndent()

    // Preserve all previous seal/checkpoint bytes. This digest is a SQL comparison, not lineage authority.
    internal val historyFields = """
        c.seal_state, c.seal_epoch, c.seal_writer_generation, c.seal_operation_token, c.seal_object_key,
        c.seal_bytes, c.seal_hash, c.seal_object_version, c.seal_ciphertext_hash,
        extract(epoch FROM c.seal_retain_until), extract(epoch FROM c.seal_verified_at), c.seal_verification_bytes, c.seal_verification_hash,
        c.checkpoint_generation, c.checkpoint_fencing_token, c.checkpoint_catalog_generation, c.checkpoint_catalog_hash,
        c.checkpoint_writer_generation, c.checkpoint_cutoff_epoch, c.checkpoint_configuration_hash, c.checkpoint_database_identity,
        c.checkpoint_restore_identity, c.checkpoint_schema, extract(epoch FROM c.checkpoint_started_at), extract(epoch FROM c.checkpoint_completed_at),
        c.checkpoint_object_count, c.checkpoint_byte_count, c.checkpoint_result, c.checkpoint_bytes, c.checkpoint_hash
    """.trimIndent()
    // Fresh closed PROJECT retains its scan request at epoch 1 before any rotation. The unrotated
    // exception requires wholly empty history; the captured rotation checks below stay unchanged.
    val control = """
        SELECT c.publication_epoch, c.lease_token, c.rotation_sequence, c.rotation_id, c.rotation_epoch_before,
            c.rotation_capture_token, c.rotation_captured_at, c.scan_requested, c.seal_epoch,
            sha256(convert_to(jsonb_build_array($historyFields)::text, 'UTF8')) AS history_hash,
            (c.test_only AND c.retention_lease_owner IS NULL AND c.retention_lease_token = 0 AND c.retention_lease_expires_at IS NULL
                AND c.seal_format IS NULL AND c.seal_rotation_id IS NULL AND c.seal_rotation_sequence IS NULL
                AND c.seal_preparing_fencing_token IS NULL AND c.seal_routing_key_id IS NULL AND c.seal_epoch_start IS NULL AND c.seal_preceding_hash IS NULL
                AND ((c.seal_state IS NULL AND c.seal_epoch IS NULL AND c.seal_writer_generation IS NULL AND c.seal_operation_token IS NULL
                        AND c.seal_object_key IS NULL AND c.seal_bytes IS NULL AND c.seal_hash IS NULL AND c.seal_object_version IS NULL
                        AND c.seal_ciphertext_hash IS NULL AND c.seal_retain_until IS NULL AND c.seal_verified_at IS NULL
                        AND c.seal_verification_bytes IS NULL AND c.seal_verification_hash IS NULL
                        AND c.checkpoint_generation IS NULL AND c.checkpoint_fencing_token IS NULL AND c.checkpoint_catalog_generation IS NULL
                        AND c.checkpoint_catalog_hash IS NULL AND c.checkpoint_writer_generation IS NULL AND c.checkpoint_cutoff_epoch IS NULL
                        AND c.checkpoint_configuration_hash IS NULL AND c.checkpoint_database_identity IS NULL AND c.checkpoint_restore_identity IS NULL
                        AND c.checkpoint_schema IS NULL AND c.checkpoint_started_at IS NULL AND c.checkpoint_completed_at IS NULL
                        AND c.checkpoint_object_count IS NULL AND c.checkpoint_byte_count IS NULL AND c.checkpoint_result IS NULL
                        AND c.checkpoint_bytes IS NULL AND c.checkpoint_hash IS NULL)
                    OR (c.seal_state = 'SEAL_VERIFIED' AND c.seal_writer_generation = c.event_writer_generation
                        AND c.seal_epoch > 0 AND c.seal_epoch < c.publication_epoch
                        AND complaint_bytes_match(c.seal_bytes, c.seal_hash, 65536)
                        AND complaint_bytes_match(c.seal_verification_bytes, c.seal_verification_hash, 65536)
                        AND isfinite(c.seal_verified_at) AND isfinite(c.seal_retain_until)
                        AND c.seal_verified_at <= clock_timestamp() AND c.seal_retain_until > clock_timestamp()
                        AND c.checkpoint_generation > 0 AND c.checkpoint_fencing_token BETWEEN 1 AND c.lease_token
                        AND c.checkpoint_catalog_generation = c.accepted_catalog_generation AND c.checkpoint_catalog_hash = c.accepted_catalog_hash
                        AND c.checkpoint_writer_generation = c.event_writer_generation AND c.checkpoint_cutoff_epoch BETWEEN 1 AND c.seal_epoch
                        AND c.checkpoint_configuration_hash = c.desired_configuration_hash AND c.checkpoint_database_identity = c.database_identity
                        AND c.checkpoint_restore_identity = c.restore_identity AND c.checkpoint_schema = c.implementation_schema
                        AND c.checkpoint_result = 'SUCCESS' AND c.checkpoint_object_count >= 0 AND c.checkpoint_byte_count >= 0
                        AND complaint_bytes_match(c.checkpoint_bytes, c.checkpoint_hash, 65536)
                        AND isfinite(c.checkpoint_started_at) AND isfinite(c.checkpoint_completed_at)
                        AND c.checkpoint_started_at <= c.checkpoint_completed_at AND c.checkpoint_completed_at <= clock_timestamp()))
                AND ((c.rotation_sequence = 0 AND c.rotation_id IS NULL AND c.rotation_state IS NULL
                        AND c.rotation_epoch_before IS NULL AND c.rotation_epoch_after IS NULL
                        AND (NOT c.scan_requested OR (c.scan_requested AND c.publication_epoch = 1
                            AND c.seal_state IS NULL AND c.checkpoint_generation IS NULL))
                        AND c.rotation_implementation_schema IS NULL AND c.rotation_desired_generation IS NULL
                        AND c.rotation_desired_configuration_hash IS NULL AND c.rotation_database_identity IS NULL AND c.rotation_restore_identity IS NULL
                        AND c.rotation_event_writer_generation IS NULL AND c.rotation_accepted_catalog_generation IS NULL AND c.rotation_accepted_catalog_hash IS NULL
                        AND c.rotation_trust_bundle_hash IS NULL AND c.rotation_catalog_writer_generation IS NULL AND c.rotation_request_owner IS NULL
                        AND c.rotation_request_token IS NULL AND c.rotation_requested_at IS NULL AND c.rotation_capture_owner IS NULL
                        AND c.rotation_capture_token IS NULL AND c.rotation_captured_at IS NULL)
                    OR (c.rotation_sequence = 1 AND complaint_is_v4(c.rotation_id) AND c.rotation_state = 'CAPTURED' AND c.scan_requested
                        AND c.publication_epoch = c.rotation_epoch_after AND c.rotation_epoch_before > 0
                        AND c.rotation_epoch_after - 1 = c.rotation_epoch_before
                        AND c.rotation_implementation_schema = c.implementation_schema AND c.rotation_desired_generation = c.desired_generation
                        AND c.rotation_desired_configuration_hash = c.desired_configuration_hash
                        AND c.rotation_database_identity = c.database_identity AND c.rotation_restore_identity = c.restore_identity
                        AND c.rotation_event_writer_generation = c.event_writer_generation
                        AND c.rotation_accepted_catalog_generation = c.accepted_catalog_generation AND c.rotation_accepted_catalog_hash = c.accepted_catalog_hash
                        AND c.rotation_trust_bundle_hash = c.trust_bundle_hash AND c.rotation_catalog_writer_generation = c.catalog_writer_generation
                        AND c.rotation_request_owner = c.rotation_capture_owner AND c.rotation_request_token = c.rotation_capture_token
                        AND complaint_is_v4(c.rotation_capture_owner) AND c.rotation_capture_token > 0
                        AND c.rotation_requested_at = c.rotation_captured_at AND isfinite(c.rotation_captured_at)))) IS TRUE AS valid
        FROM complaint_journal_control c WHERE c.data_scope_id = ?::uuid FOR UPDATE
    """.trimIndent()
    // Only this registered owner may capture with a retained previous comparison; old first-seal SQL stays unchanged.
    val capture = """
        WITH now AS MATERIALIZED (SELECT clock_timestamp() AS at)
        UPDATE complaint_journal_control c SET rotation_sequence = 1, rotation_id = ?::uuid, rotation_state = 'CAPTURED',
            rotation_epoch_before = c.publication_epoch, rotation_epoch_after = c.publication_epoch + 1,
            rotation_implementation_schema = c.implementation_schema, rotation_desired_generation = c.desired_generation,
            rotation_desired_configuration_hash = c.desired_configuration_hash, rotation_database_identity = c.database_identity,
            rotation_restore_identity = c.restore_identity, rotation_event_writer_generation = c.event_writer_generation,
            rotation_accepted_catalog_generation = c.accepted_catalog_generation, rotation_accepted_catalog_hash = c.accepted_catalog_hash,
            rotation_trust_bundle_hash = c.trust_bundle_hash, rotation_catalog_writer_generation = c.catalog_writer_generation,
            rotation_request_owner = c.lease_owner, rotation_request_token = c.lease_token, rotation_requested_at = n.at,
            rotation_capture_owner = c.lease_owner, rotation_capture_token = c.lease_token, rotation_captured_at = n.at,
            publication_epoch = c.publication_epoch + 1, scan_requested = true, updated_at = n.at
        FROM now n WHERE c.data_scope_id = ?::uuid AND c.test_only AND c.rotation_sequence = 0
            AND c.publication_epoch BETWEEN 1 AND 9223372036854775806
            AND c.lease_owner = ?::uuid AND c.lease_token = ?::bigint AND c.lease_expires_at > n.at
    """.trimIndent()

    /** Exact A successor lane. The V26/V14 comparator is independently reread under these controls. */
    val controlWithActiveHistory = """
        SELECT c.publication_epoch, c.lease_token, c.rotation_sequence, c.rotation_id, c.rotation_epoch_before,
            c.rotation_capture_token, c.rotation_captured_at, c.scan_requested, c.seal_epoch,
            sha256(convert_to(jsonb_build_array($historyFields)::text, 'UTF8')) AS history_hash,
            (c.test_only AND c.retention_lease_owner IS NULL AND c.retention_lease_token = 0 AND c.retention_lease_expires_at IS NULL
                AND c.seal_format IS NULL AND c.seal_rotation_id IS NULL AND c.seal_rotation_sequence IS NULL
                AND c.seal_preparing_fencing_token IS NULL AND c.seal_routing_key_id IS NULL AND c.seal_epoch_start IS NULL AND c.seal_preceding_hash IS NULL
                AND c.seal_state = 'SEAL_VERIFIED' AND c.seal_epoch = 1 AND c.seal_operation_token = i.operation_token
                AND c.rotation_state = 'CAPTURED' AND c.rotation_implementation_schema = c.implementation_schema
                AND c.rotation_desired_generation = c.desired_generation AND c.rotation_desired_configuration_hash = c.desired_configuration_hash
                AND c.rotation_database_identity = c.database_identity AND c.rotation_restore_identity = c.restore_identity
                AND c.rotation_event_writer_generation = c.event_writer_generation
                AND c.rotation_accepted_catalog_generation = c.accepted_catalog_generation AND c.rotation_accepted_catalog_hash = c.accepted_catalog_hash
                AND c.rotation_trust_bundle_hash = c.trust_bundle_hash AND c.rotation_catalog_writer_generation = c.catalog_writer_generation
                AND ((c.rotation_sequence = 1 AND c.rotation_id = i.operation_token AND c.publication_epoch = 2 AND NOT c.scan_requested
                        AND c.rotation_epoch_before = 1 AND c.rotation_epoch_after = 2
                        AND c.rotation_request_owner = i.request_owner AND c.rotation_request_token = i.request_token AND c.rotation_requested_at = i.requested_at
                        AND c.rotation_capture_owner = i.capture_owner AND c.rotation_capture_token = i.capture_token AND c.rotation_captured_at = i.captured_at)
                    OR (c.rotation_sequence = 2 AND complaint_is_v4(c.rotation_id) AND c.rotation_id <> i.operation_token
                        AND c.publication_epoch = 3 AND c.scan_requested AND c.rotation_epoch_before = 2 AND c.rotation_epoch_after = 3
                        AND c.rotation_request_owner = c.rotation_capture_owner AND c.rotation_request_token = c.rotation_capture_token
                        AND complaint_is_v4(c.rotation_capture_owner) AND c.rotation_capture_token > c.checkpoint_fencing_token
                        AND c.rotation_capture_token <= c.lease_token AND c.rotation_requested_at = c.rotation_captured_at
                        AND c.rotation_captured_at >= c.checkpoint_completed_at AND isfinite(c.rotation_captured_at)))) IS TRUE AS valid
        FROM complaint_journal_control c LEFT JOIN complaint_test_active_seal_intents i ON i.data_scope_id = c.data_scope_id
        WHERE c.data_scope_id = ?::uuid FOR UPDATE OF c
    """.trimIndent()

    // Do not clear A's retained seal/checkpoint, fabricate sequence zero, or reuse its earlier capture.
    val captureWithActiveHistory = """
        WITH now AS MATERIALIZED (SELECT clock_timestamp() AS at)
        UPDATE complaint_journal_control c SET rotation_sequence = 2, rotation_id = ?::uuid, rotation_state = 'CAPTURED',
            rotation_epoch_before = 2, rotation_epoch_after = 3,
            rotation_implementation_schema = c.implementation_schema, rotation_desired_generation = c.desired_generation,
            rotation_desired_configuration_hash = c.desired_configuration_hash, rotation_database_identity = c.database_identity,
            rotation_restore_identity = c.restore_identity, rotation_event_writer_generation = c.event_writer_generation,
            rotation_accepted_catalog_generation = c.accepted_catalog_generation, rotation_accepted_catalog_hash = c.accepted_catalog_hash,
            rotation_trust_bundle_hash = c.trust_bundle_hash, rotation_catalog_writer_generation = c.catalog_writer_generation,
            rotation_request_owner = c.lease_owner, rotation_request_token = c.lease_token, rotation_requested_at = n.at,
            rotation_capture_owner = c.lease_owner, rotation_capture_token = c.lease_token, rotation_captured_at = n.at,
            publication_epoch = 3, scan_requested = true, updated_at = n.at
        FROM now n, complaint_test_active_seal_intents i
        WHERE c.data_scope_id = ?::uuid AND c.test_only AND c.rotation_sequence = 1 AND c.rotation_state = 'CAPTURED'
            AND c.publication_epoch = 2 AND NOT c.scan_requested AND c.rotation_epoch_before = 1 AND c.rotation_epoch_after = 2
            AND i.data_scope_id = c.data_scope_id AND i.operation_token = c.rotation_id AND i.state = 'WIRE_FROZEN'
            AND c.rotation_request_owner = i.request_owner AND c.rotation_request_token = i.request_token AND c.rotation_requested_at = i.requested_at
            AND c.rotation_capture_owner = i.capture_owner AND c.rotation_capture_token = i.capture_token AND c.rotation_captured_at = i.captured_at
            AND c.seal_operation_token = i.operation_token AND c.seal_state = 'SEAL_VERIFIED' AND c.seal_epoch = 1
            AND c.checkpoint_result = 'SUCCESS' AND c.checkpoint_cutoff_epoch = 1 AND c.checkpoint_completed_at <= n.at
            AND c.lease_owner = ?::uuid AND c.lease_token = ?::bigint AND c.lease_token > c.checkpoint_fencing_token AND c.lease_expires_at > n.at
    """.trimIndent()

    val runs = """
        SELECT * FROM complaint_journal_scan_runs WHERE data_scope_id = ?::uuid ORDER BY scan_id, pass LIMIT 3 FOR UPDATE
    """.trimIndent()
    val pool = """
        SELECT (SELECT count(*) FROM complaint_journal_scan_runs WHERE data_scope_id = ?::uuid) AS runs,
            (SELECT count(*) FROM complaint_journal_scan_entries WHERE data_scope_id = ?::uuid) AS entries
    """.trimIndent()
    val insertRun = """
        INSERT INTO complaint_journal_scan_runs (scan_id, pass, data_scope_id, test_only, restore_identity, desired_generation,
            fencing_token, writer_generation, cutoff_epoch, maximum_entries, maximum_bytes, entry_count, entry_bytes, state, started_at)
        VALUES (?::uuid, ?, ?::uuid, true, ?::uuid, ?, ?, ?::uuid, ?, ?, ?, 0, 0, 'SCANNING', ?::timestamptz)
    """.trimIndent()
    val insertEntry = """
        INSERT INTO complaint_journal_scan_entries (scan_id, pass, data_scope_id, test_only, object_key, object_version,
            ciphertext_hash, semantic_hash, event_id, event_kind, writer_generation, journal_epoch, entry_bytes, replay_state)
        VALUES (?::uuid, ?, ?::uuid, true, ?, ?, ?, ?, ?, ?, ?::uuid, ?, ?, 'PENDING')
    """.trimIndent()
    val appendRun = """
        UPDATE complaint_journal_scan_runs SET entry_count = entry_count + 1, entry_bytes = entry_bytes + ?
        WHERE scan_id = ?::uuid AND pass = ? AND data_scope_id = ?::uuid AND test_only AND state = 'SCANNING'
            AND entry_count < maximum_entries AND ?::bigint <= maximum_bytes - entry_bytes
    """.trimIndent()
    val finishRun = """
        UPDATE complaint_journal_scan_runs SET state = 'COMPLETE', manifest_hash = ?, finished_at = ?::timestamptz, entry_bytes = ?
        WHERE scan_id = ?::uuid AND pass = ? AND data_scope_id = ?::uuid AND test_only AND state = 'SCANNING'
            AND entry_count = ? AND entry_bytes = ? AND ?::bigint <= maximum_bytes
    """.trimIndent()
    val entryPage = """
        SELECT * FROM complaint_journal_scan_entries WHERE scan_id = ?::uuid AND pass = ? AND data_scope_id = ?::uuid
            AND (?::text IS NULL OR (object_key, object_version) > (?::text COLLATE "C", ?::text COLLATE "C"))
        ORDER BY object_key COLLATE "C", object_version COLLATE "C" LIMIT $PAGE
    """.trimIndent()
    val exactEntries = """
        SELECT * FROM complaint_journal_scan_entries WHERE scan_id = ?::uuid AND data_scope_id = ?::uuid AND object_key = ? AND object_version = ?
        ORDER BY pass FOR UPDATE
    """.trimIndent()
    val markApplied = """
        UPDATE complaint_journal_scan_entries SET replay_state = 'APPLIED'
        WHERE scan_id = ?::uuid AND data_scope_id = ?::uuid AND object_key = ? AND object_version = ? AND pass IN (1, 2)
            AND ciphertext_hash = ? AND semantic_hash = ? AND event_id = ? AND test_only AND event_kind = ?
            AND writer_generation = ?::uuid AND journal_epoch = ? AND entry_bytes = ? AND replay_state IN ('PENDING', 'APPLIED')
    """.trimIndent()
    val spendAndProgress = """
        UPDATE complaint_test_runs SET unused_reserve = ?::bigint[], permanent_denial_bytes = ?, permanent_denial_hash = ?
        WHERE data_scope_id = ?::uuid AND test_only AND state = 'SEALED' AND unused_reserve = ?::bigint[]
            AND permanent_denial_bytes IS NOT DISTINCT FROM ?::bytea AND permanent_denial_hash IS NOT DISTINCT FROM ?::bytea
    """.trimIndent()
    val spend = """
        UPDATE complaint_test_runs SET unused_reserve = ?::bigint[]
        WHERE data_scope_id = ?::uuid AND test_only AND state = 'SEALED' AND unused_reserve = ?::bigint[]
    """.trimIndent()
    val convert = """
        UPDATE complaint_recovery_capacity_reservations SET state = 'CONVERTED'
        WHERE event_id = ? AND data_scope_id = ?::uuid AND test_only AND state = 'PARTIAL' AND accounting_version = 1
            AND reserved_amounts = ?::bigint[] AND converted_amounts = ?::bigint[] AND converted_at = ?::timestamptz
    """.trimIndent()
    val primaryPage = """
        SELECT event_id FROM complaint_journal_publications WHERE (data_scope_id = ?::uuid OR object_key LIKE ?::text)
            AND (?::text IS NULL OR event_id > ?::text COLLATE "C") ORDER BY event_id COLLATE "C" LIMIT $PAGE
    """.trimIndent()
    val allPrimaryPage = """
        SELECT r.installation_id, r.deletion_key,
            (r.test_only AND r.state = 'AUTHORIZED_DELETE' AND r.data_scope_id = e.scope
                AND p.test_only AND p.data_scope_id = e.scope AND p.event_kind = 'OWNER_DELETE_ALL'
                AND p.state IN ('PREPARED', 'VERIFIED') AND p.writer_generation = e.writer
                AND r.authorized_at = p.created_at AND isfinite(p.created_at) AND p.created_at <= e.sealed_at) IS TRUE AS valid
        FROM installation_deletion_receipts r FULL JOIN complaint_journal_publications p ON p.event_id = r.publication_ref
        CROSS JOIN (SELECT ?::uuid AS scope, ?::uuid AS writer, ?::timestamptz AS sealed_at) e
        WHERE (r.data_scope_id = e.scope AND r.state <> 'COMPLETED')
            OR (p.data_scope_id = e.scope AND p.event_kind = 'OWNER_DELETE_ALL' AND p.state <> 'APPLIED')
        ORDER BY p.created_at, p.event_id COLLATE "C", r.installation_id, r.deletion_key LIMIT 3
    """.trimIndent()
    val adminPrimaryPage = """
        SELECT r.actor_id, r.idempotency_key,
            (r.test_only AND r.actor_kind = 'ADMIN' AND ((r.operation = 'ADMIN_DELETE' AND p.target_count = 1)
                    OR (e.allow_batch AND r.operation = 'ADMIN_BATCH_DELETE' AND p.target_count BETWEEN 1 AND 50))
                AND r.state = 'AUTHORIZED_DELETE' AND r.data_scope_id = e.scope AND complaint_is_v4(r.consumed_grant_id)
                AND p.test_only AND p.data_scope_id = e.scope AND p.event_kind = r.operation AND cardinality(r.target_ids) = p.target_count
                AND p.state IN ('PREPARED', 'VERIFIED') AND p.writer_generation = e.writer
                AND r.authorized_at = p.created_at AND isfinite(p.created_at) AND p.created_at <= e.sealed_at) IS TRUE AS valid
        FROM complaint_idempotency_receipts r FULL JOIN complaint_journal_publications p ON p.event_id = r.publication_ref
        CROSS JOIN (SELECT ?::uuid AS scope, ?::uuid AS writer, ?::timestamptz AS sealed_at, ?::boolean AS allow_batch) e
        WHERE (r.data_scope_id = e.scope AND r.operation IN ('ADMIN_DELETE', 'ADMIN_BATCH_DELETE') AND r.state <> 'COMPLETED')
            OR (p.data_scope_id = e.scope AND p.event_kind IN ('ADMIN_DELETE', 'ADMIN_BATCH_DELETE') AND p.state <> 'APPLIED')
        ORDER BY COALESCE(p.created_at, r.authorized_at, r.created_at), COALESCE(p.event_id, r.publication_ref) COLLATE "C",
            r.actor_kind COLLATE "C", r.actor_id, r.idempotency_key LIMIT 3
    """.trimIndent()
    // No actor/kind filter. Lock the exact receipt relation before reading either closed row shape
    // and before the publication, so foreign/mixed/duplicate receipts cannot disappear in dispatch.
    val primaryReceiptLocators = """
        SELECT actor_kind, actor_id, idempotency_key FROM complaint_idempotency_receipts
        WHERE publication_ref = ?::text ORDER BY actor_kind, actor_id, idempotency_key LIMIT 2 FOR UPDATE
    """.trimIndent()
    val recovery = OwnerDeletePersistenceSql.LOCK_RECOVERY
    val abandon = """
        UPDATE complaint_journal_scan_runs SET state = 'ABANDONED', manifest_hash = NULL, finished_at = clock_timestamp()
        WHERE scan_id = ?::uuid AND pass = ? AND data_scope_id = ?::uuid AND test_only AND state IN ('SCANNING', 'COMPLETE', 'ABANDONED')
    """.trimIndent()
    val deleteEntry = """
        DELETE FROM complaint_journal_scan_entries WHERE scan_id = ?::uuid AND pass = ? AND data_scope_id = ?::uuid AND test_only
            AND object_key = ? AND object_version = ? AND ciphertext_hash = ? AND semantic_hash = ? AND event_id = ?
            AND event_kind = ? AND writer_generation = ?::uuid AND journal_epoch = ? AND entry_bytes = ? AND replay_state = ?
    """.trimIndent()
    val deleteRun = """
        DELETE FROM complaint_journal_scan_runs r WHERE r.scan_id = ?::uuid AND r.pass = ? AND r.data_scope_id = ?::uuid AND r.test_only
            AND NOT EXISTS (SELECT 1 FROM complaint_journal_scan_entries e WHERE e.scan_id = r.scan_id AND e.pass = r.pass)
    """.trimIndent()
    // Discovery is deliberately unlocked and bounded. The operation re-locks/revalidates these
    // exact rows after counters/run; no page count, cursor or run.entry_count authorizes a refund.
    val recyclePage = """
        SELECT * FROM complaint_journal_scan_entries WHERE data_scope_id = ?::uuid
        ORDER BY scan_id, pass, object_key COLLATE "C", object_version COLLATE "C" LIMIT $PAGE
    """.trimIndent()
    val exactRecycleEntry = """
        SELECT * FROM complaint_journal_scan_entries WHERE scan_id = ?::uuid AND pass = ?
            AND object_key = ? AND object_version = ? FOR UPDATE
    """.trimIndent()
    val scanEntryCount = """
        SELECT count(*) FROM complaint_journal_scan_entries WHERE scan_id = ?::uuid AND pass = ?
    """.trimIndent()
    val appliedFamily = """
        SELECT event_id, data_scope_id, test_only, writer_generation, journal_epoch, event_kind, target_count,
            object_key, object_version, ciphertext_hash, applied_at, xmin::text AS stamp,
            isfinite(applied_at) AS finite FROM complaint_deletion_journal_applied
        WHERE event_id = ANY (?::text[]) ORDER BY event_id, object_key, object_version LIMIT 5
    """.trimIndent()
    val appliedPage = """
        SELECT event_id, data_scope_id, test_only, writer_generation, journal_epoch, event_kind, target_count,
            object_key, object_version, ciphertext_hash, applied_at, xmin::text AS stamp,
            isfinite(applied_at) AS finite FROM complaint_deletion_journal_applied
        WHERE (data_scope_id = ?::uuid OR object_key LIKE ?::text OR object_key LIKE ?::text)
            AND (?::text IS NULL OR (object_key, object_version) > (?::text COLLATE "C", ?::text COLLATE "C"))
        ORDER BY object_key COLLATE "C", object_version COLLATE "C" LIMIT $PAGE
    """.trimIndent()
    val targetAudits = """
        SELECT action, created_at, (complaint_data_scope_id = ?::uuid AND actor_user_id IS NULL
            AND entity_type = 'complaint' AND isfinite(created_at)
            AND ((action = 'COMPLAINT_DELETED' AND complaint_actor_kind = 'INSTALLATION'
                    AND jsonb_typeof(detail->'version') = 'number' AND detail = jsonb_build_object('version', (detail->>'version')::bigint)
                    AND (detail->>'version')::bigint > 0)
                OR (action = 'COMPLAINT_RECOVERY_APPLIED' AND complaint_actor_kind = 'SYSTEM' AND detail = '{}'::jsonb))) IS TRUE AS valid
        FROM audit_log WHERE entity_type = 'complaint' AND entity_id = ?::text
            AND action IN ('COMPLAINT_DELETED', 'COMPLAINT_RECOVERY_APPLIED') ORDER BY id LIMIT 6
    """.trimIndent()
    val ownerIdentity = """
        SELECT data_scope_id, test_only, state, created_at, terminal_at, complaint_finite_times(created_at, terminal_at) AS finite
        FROM complaint_installation_ids WHERE id = ?::uuid
    """.trimIndent()
    val resourceIdentity = """
        SELECT data_scope_id, test_only, state, created_at, deleted_at, complaint_finite_times(created_at, deleted_at) AS finite
        FROM complaint_resource_ids WHERE id = ?::uuid
    """.trimIndent()

    /** Unfiltered applicable relations: unsupported kinds/foreign scope, extra intents or orphaned history fail the whole attempt. */
    val supported = """
        WITH e AS MATERIALIZED (SELECT ?::uuid AS scope, ?::text AS ordinary_prefix, ?::text AS terminal_prefix, ?::uuid AS writer,
            ?::boolean AS allow_all, ?::boolean AS allow_admin, ?::boolean AS allow_batch)
        SELECT (NOT EXISTS (SELECT 1 FROM complaint_journal_publications p CROSS JOIN e
                WHERE (p.data_scope_id = e.scope OR p.object_key LIKE e.ordinary_prefix OR p.object_key LIKE e.terminal_prefix)
                    AND (p.data_scope_id <> e.scope OR NOT p.test_only OR p.writer_generation <> e.writer OR NOT (p.event_kind = 'OWNER_DELETE' OR (e.allow_all AND p.event_kind = 'OWNER_DELETE_ALL') OR (e.allow_admin AND p.event_kind = 'ADMIN_DELETE') OR (e.allow_batch AND p.event_kind = 'ADMIN_BATCH_DELETE'))
                        OR NOT ((p.event_kind = 'OWNER_DELETE' AND p.target_count = 1)
                            OR (e.allow_all AND p.event_kind = 'OWNER_DELETE_ALL' AND p.target_count BETWEEN 0 AND 100)
                            OR (e.allow_admin AND p.event_kind = 'ADMIN_DELETE' AND p.target_count = 1)
                            OR (e.allow_batch AND p.event_kind = 'ADMIN_BATCH_DELETE' AND p.target_count BETWEEN 1 AND 50))
                        OR p.object_key NOT LIKE e.ordinary_prefix))
            AND NOT EXISTS (SELECT 1 FROM complaint_deletion_journal_applied a CROSS JOIN e
                WHERE (a.data_scope_id = e.scope OR a.object_key LIKE e.ordinary_prefix OR a.object_key LIKE e.terminal_prefix)
                    AND (a.data_scope_id <> e.scope OR NOT a.test_only OR a.writer_generation <> e.writer OR NOT (a.event_kind = 'OWNER_DELETE' OR (e.allow_all AND a.event_kind = 'OWNER_DELETE_ALL') OR (e.allow_admin AND a.event_kind = 'ADMIN_DELETE') OR (e.allow_batch AND a.event_kind = 'ADMIN_BATCH_DELETE'))
                        OR NOT ((a.event_kind = 'OWNER_DELETE' AND a.target_count = 1) OR (e.allow_all AND a.event_kind = 'OWNER_DELETE_ALL' AND a.target_count BETWEEN 0 AND 100)
                            OR (e.allow_admin AND a.event_kind = 'ADMIN_DELETE' AND a.target_count = 1)
                            OR (e.allow_batch AND a.event_kind = 'ADMIN_BATCH_DELETE' AND a.target_count BETWEEN 1 AND 50)) OR a.object_key NOT LIKE e.ordinary_prefix))
            AND NOT EXISTS (SELECT 1 FROM complaint_journal_scan_entries a CROSS JOIN e
                WHERE (a.data_scope_id = e.scope OR a.object_key LIKE e.ordinary_prefix OR a.object_key LIKE e.terminal_prefix)
                    AND (a.data_scope_id <> e.scope OR NOT a.test_only OR a.writer_generation <> e.writer OR NOT (a.event_kind = 'OWNER_DELETE' OR (e.allow_all AND a.event_kind = 'OWNER_DELETE_ALL') OR (e.allow_admin AND a.event_kind = 'ADMIN_DELETE') OR (e.allow_batch AND a.event_kind = 'ADMIN_BATCH_DELETE'))
                        OR a.object_key NOT LIKE e.ordinary_prefix))
            AND NOT EXISTS (SELECT 1 FROM complaint_recovery_capacity_reservations r CROSS JOIN e
                LEFT JOIN complaint_journal_publications p ON p.event_id = r.publication_ref
                WHERE r.data_scope_id = e.scope AND (NOT r.test_only OR r.event_id <> r.publication_ref OR p.event_id IS NULL OR p.data_scope_id <> e.scope))
            AND NOT EXISTS (SELECT 1 FROM complaint_idempotency_receipts r CROSS JOIN e
                LEFT JOIN complaint_journal_publications p ON p.event_id = r.publication_ref
                WHERE r.data_scope_id = e.scope AND (NOT r.test_only OR (r.operation = 'ADMIN_BATCH_DELETE' AND NOT e.allow_batch) OR (r.publication_ref IS NOT NULL AND
                    (p.event_id IS NULL OR p.data_scope_id <> e.scope OR NOT p.test_only OR
                        NOT ((r.operation = 'OWNER_DELETE' AND r.actor_kind = 'INSTALLATION' AND p.event_kind = 'OWNER_DELETE') OR
                            (e.allow_admin AND r.operation = 'ADMIN_DELETE' AND r.actor_kind = 'ADMIN' AND p.event_kind = 'ADMIN_DELETE' AND p.target_count = 1) OR
                            (e.allow_batch AND r.operation = 'ADMIN_BATCH_DELETE' AND r.actor_kind = 'ADMIN' AND p.event_kind = 'ADMIN_BATCH_DELETE'
                                AND p.target_count BETWEEN 1 AND 50 AND cardinality(r.target_ids) = p.target_count))))
                    OR (r.external_event_id IS NOT NULL AND r.publication_ref IS NULL)))
            AND NOT EXISTS (SELECT 1 FROM complaint_deletion_journal_retirements r CROSS JOIN e WHERE r.data_scope_id = e.scope)
            AND NOT EXISTS (SELECT 1 FROM installation_deletion_receipts r CROSS JOIN e
                LEFT JOIN complaint_journal_publications p ON p.event_id = r.publication_ref
                WHERE r.data_scope_id = e.scope AND (NOT e.allow_all OR NOT r.test_only OR p.event_id IS NULL OR
                    p.data_scope_id <> e.scope OR NOT p.test_only OR p.event_kind <> 'OWNER_DELETE_ALL'))) AS valid
    """.trimIndent()
    val noPending = """
        SELECT (NOT EXISTS (SELECT 1 FROM complaint_journal_publications WHERE data_scope_id = ?::uuid AND state <> 'APPLIED')
            AND NOT EXISTS (SELECT 1 FROM complaint_idempotency_receipts WHERE data_scope_id = ?::uuid AND state <> 'COMPLETED')
            AND NOT EXISTS (SELECT 1 FROM complaint_resource_ids WHERE data_scope_id = ?::uuid AND state = 'DELETION_PENDING')
            AND NOT EXISTS (SELECT 1 FROM complaint_installation_ids WHERE data_scope_id = ?::uuid AND state = 'DELETION_PENDING')
            AND NOT EXISTS (SELECT 1 FROM installation_deletion_receipts WHERE data_scope_id = ?::uuid AND state <> 'COMPLETED')) AS valid
    """.trimIndent()
}
