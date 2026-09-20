package me.manga.kira.backend.complaint.infrastructure.terminal

import me.manga.kira.backend.complaint.infrastructure.OwnerDeletePersistenceSql

/** Fixed first/current-writer TEST only; V19 LIVE intent fields are read-for-NULL and never written. */
internal object TestOrdinarySealSqlV1 {
    const val PAGE = 16
    val control = """
        SELECT c.publication_epoch, c.lease_token, c.rotation_sequence, c.rotation_id, c.rotation_epoch_before,
            c.rotation_capture_token, c.rotation_captured_at, c.seal_state, c.seal_epoch, c.seal_writer_generation,
            c.seal_operation_token,
            CASE WHEN octet_length(c.seal_object_key) BETWEEN 1 AND 1024 THEN c.seal_object_key END AS seal_object_key,
            CASE WHEN octet_length(c.seal_bytes) BETWEEN 1 AND 65536 THEN c.seal_bytes END AS seal_bytes,
            CASE WHEN octet_length(c.seal_hash) = 32 THEN c.seal_hash END AS seal_hash,
            CASE WHEN octet_length(c.seal_object_version) BETWEEN 1 AND 1024 THEN c.seal_object_version END AS seal_object_version,
            CASE WHEN octet_length(c.seal_ciphertext_hash) = 32 THEN c.seal_ciphertext_hash END AS seal_ciphertext_hash,
            c.seal_retain_until, c.seal_verified_at,
            CASE WHEN octet_length(c.seal_verification_bytes) BETWEEN 1 AND 65536 THEN c.seal_verification_bytes END AS seal_verification_bytes,
            CASE WHEN octet_length(c.seal_verification_hash) = 32 THEN c.seal_verification_hash END AS seal_verification_hash,
            (c.test_only AND c.retention_lease_owner IS NULL AND c.retention_lease_token = 0 AND c.retention_lease_expires_at IS NULL
                AND c.checkpoint_generation IS NULL AND c.checkpoint_fencing_token IS NULL AND c.checkpoint_catalog_generation IS NULL
                AND c.checkpoint_catalog_hash IS NULL AND c.checkpoint_writer_generation IS NULL AND c.checkpoint_cutoff_epoch IS NULL
                AND c.checkpoint_configuration_hash IS NULL AND c.checkpoint_database_identity IS NULL AND c.checkpoint_restore_identity IS NULL
                AND c.checkpoint_schema IS NULL AND c.checkpoint_started_at IS NULL AND c.checkpoint_completed_at IS NULL
                AND c.checkpoint_object_count IS NULL AND c.checkpoint_byte_count IS NULL AND c.checkpoint_result IS NULL
                AND c.checkpoint_bytes IS NULL AND c.checkpoint_hash IS NULL
                AND c.seal_format IS NULL AND c.seal_rotation_id IS NULL AND c.seal_rotation_sequence IS NULL
                AND c.seal_preparing_fencing_token IS NULL AND c.seal_routing_key_id IS NULL AND c.seal_epoch_start IS NULL AND c.seal_preceding_hash IS NULL
                AND ((c.seal_state IS NULL AND c.seal_epoch IS NULL AND c.seal_writer_generation IS NULL AND c.seal_operation_token IS NULL
                        AND c.seal_object_key IS NULL AND c.seal_bytes IS NULL AND c.seal_hash IS NULL AND c.seal_object_version IS NULL
                        AND c.seal_ciphertext_hash IS NULL AND c.seal_retain_until IS NULL AND c.seal_verified_at IS NULL
                        AND c.seal_verification_bytes IS NULL AND c.seal_verification_hash IS NULL)
                    OR (c.seal_state IN ('SEAL_PREPARED', 'SEAL_VERIFIED') AND c.seal_epoch > 0
                        AND complaint_is_v4(c.seal_writer_generation) AND complaint_is_v4(c.seal_operation_token)
                        AND complaint_ascii_valid(c.seal_object_key, 1024) AND complaint_bytes_match(c.seal_bytes, c.seal_hash, 65536)
                        AND ((c.seal_state = 'SEAL_PREPARED' AND c.seal_object_version IS NULL AND c.seal_ciphertext_hash IS NULL
                                AND c.seal_retain_until IS NULL AND c.seal_verified_at IS NULL AND c.seal_verification_bytes IS NULL AND c.seal_verification_hash IS NULL)
                            OR (c.seal_state = 'SEAL_VERIFIED' AND complaint_opaque_valid(c.seal_object_version, 1024) AND c.seal_object_version <> 'null'
                                AND complaint_digest_valid(c.seal_ciphertext_hash) AND isfinite(c.seal_retain_until) AND isfinite(c.seal_verified_at)
                                AND c.seal_retain_until > c.seal_verified_at AND complaint_bytes_match(c.seal_verification_bytes, c.seal_verification_hash, 65536)))))
                AND ((c.rotation_sequence = 0 AND c.rotation_id IS NULL AND c.rotation_state IS NULL AND c.seal_state IS NULL
                        AND c.rotation_epoch_before IS NULL AND c.rotation_implementation_schema IS NULL AND c.rotation_desired_generation IS NULL
                        AND c.rotation_desired_configuration_hash IS NULL AND c.rotation_database_identity IS NULL AND c.rotation_restore_identity IS NULL
                        AND c.rotation_event_writer_generation IS NULL AND c.rotation_accepted_catalog_generation IS NULL AND c.rotation_accepted_catalog_hash IS NULL
                        AND c.rotation_trust_bundle_hash IS NULL AND c.rotation_catalog_writer_generation IS NULL AND c.rotation_request_owner IS NULL
                        AND c.rotation_request_token IS NULL AND c.rotation_requested_at IS NULL AND c.rotation_capture_owner IS NULL
                        AND c.rotation_capture_token IS NULL AND c.rotation_captured_at IS NULL AND c.rotation_epoch_after IS NULL)
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

    val acquire = """
        WITH now AS MATERIALIZED (SELECT clock_timestamp() AS at)
        UPDATE complaint_journal_control c SET lease_owner = ?::uuid, lease_token = c.lease_token + 1,
            lease_expires_at = n.at + (?::bigint * interval '1 millisecond'), updated_at = n.at FROM now n
        WHERE c.data_scope_id = ?::uuid AND c.test_only AND c.lease_token BETWEEN 0 AND 9223372036854775806
            AND (c.lease_owner IS NULL OR c.lease_expires_at <= n.at)
        RETURNING c.lease_token
    """.trimIndent()
    val lease = """
        SELECT (c.lease_owner = ?::uuid AND c.lease_token = ?::bigint AND c.lease_expires_at > clock_timestamp()) IS TRUE AS valid
        FROM complaint_journal_control c WHERE c.data_scope_id = ?::uuid AND c.test_only
    """.trimIndent()
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
        FROM now n WHERE c.data_scope_id = ?::uuid AND c.test_only AND c.rotation_sequence = 0 AND c.seal_state IS NULL
            AND c.publication_epoch BETWEEN 1 AND 9223372036854775806
            AND c.lease_owner = ?::uuid AND c.lease_token = ?::bigint AND c.lease_expires_at > n.at
    """.trimIndent()

    /** No state/epoch/writer predicate truncates the candidate relation. Every relevant row is subsequently validated. */
    val manifestPage = """
        SELECT CASE WHEN octet_length(p.event_id) BETWEEN 1 AND 43 THEN p.event_id END AS event_id,
            CASE WHEN octet_length(p.object_key) BETWEEN 1 AND 1024 THEN p.object_key END AS object_key
        FROM complaint_journal_publications p
        WHERE (p.data_scope_id = ?::uuid OR p.object_key LIKE ?::text OR p.object_key LIKE ?::text)
            AND (?::text IS NULL OR p.object_key > ?::text COLLATE "C")
        ORDER BY p.object_key COLLATE "C" LIMIT $PAGE
    """.trimIndent()
    val receipt = """
        SELECT ${OwnerDeletePersistenceSql.RECEIPT_COLUMNS}, r.actor_kind, r.xmin::text AS stamp
        FROM complaint_idempotency_receipts r WHERE r.publication_ref = ?::text
        ORDER BY r.actor_kind, r.actor_id, r.idempotency_key LIMIT 2 FOR UPDATE
    """.trimIndent()
    val publication = "SELECT ${OwnerDeletePersistenceSql.PUBLICATION_COLUMNS}, xmin::text AS stamp FROM complaint_journal_publications WHERE event_id = ?::text FOR UPDATE"
    val applied = """
        SELECT CASE WHEN octet_length(a.event_id) = 43 THEN a.event_id END AS event_id,
            a.data_scope_id, a.test_only, a.writer_generation, a.journal_epoch, a.event_kind, a.target_count,
            CASE WHEN octet_length(a.object_key) BETWEEN 1 AND 1024 THEN a.object_key END AS object_key,
            CASE WHEN octet_length(a.object_version) BETWEEN 1 AND 1024 THEN a.object_version END AS object_version,
            CASE WHEN octet_length(a.ciphertext_hash) = 32 THEN a.ciphertext_hash END AS ciphertext_hash,
            a.applied_at, a.xmin::text AS stamp
        FROM complaint_deletion_journal_applied a WHERE a.event_id = ?::text
        ORDER BY a.object_key, a.object_version LIMIT 2 FOR UPDATE
    """.trimIndent()
    val recovery = OwnerDeletePersistenceSql.LOCK_RECOVERY.replace("converted_at, complaint_finite_times", "xmin::text AS stamp, converted_at, complaint_finite_times")

    /** Orphans/unsupported families are errors, not a smaller expected set. This is LOCAL completeness only. */
    val completeRelation = """
        WITH expected AS MATERIALIZED (SELECT ?::uuid AS scope, ?::text AS prefix, ?::text AS seal_prefix)
        SELECT (NOT EXISTS (SELECT 1 FROM complaint_journal_publications p CROSS JOIN expected e
                WHERE (p.data_scope_id = e.scope OR p.object_key LIKE e.prefix OR p.object_key LIKE e.seal_prefix)
                    AND (complaint_ascii_valid(p.object_key, 1024) IS NOT TRUE OR complaint_event_id_valid(p.event_id) IS NOT TRUE))
            AND NOT EXISTS (
            SELECT 1 FROM complaint_idempotency_receipts r LEFT JOIN complaint_journal_publications p ON p.event_id = r.publication_ref
            CROSS JOIN expected e
            WHERE (r.data_scope_id = e.scope OR p.data_scope_id = e.scope OR p.object_key LIKE e.prefix OR p.object_key LIKE e.seal_prefix)
                AND (r.state <> 'COMPLETED' OR (r.publication_ref IS NOT NULL AND
                    (p.event_id IS NULL OR r.data_scope_id <> e.scope OR NOT r.test_only OR r.operation <> 'OWNER_DELETE' OR r.actor_kind <> 'INSTALLATION'))
                    OR (r.external_event_id IS NOT NULL AND r.publication_ref IS NULL)))
            AND NOT EXISTS (SELECT 1 FROM complaint_deletion_journal_applied a LEFT JOIN complaint_journal_publications p ON p.event_id = a.event_id
                CROSS JOIN expected e WHERE (a.data_scope_id = e.scope OR a.object_key LIKE e.prefix OR a.object_key LIKE e.seal_prefix)
                AND (p.event_id IS NULL OR p.data_scope_id <> e.scope OR p.state <> 'APPLIED' OR NOT a.test_only OR a.data_scope_id <> e.scope))
            AND NOT EXISTS (SELECT 1 FROM complaint_recovery_capacity_reservations r LEFT JOIN complaint_journal_publications p ON p.event_id = r.publication_ref
                CROSS JOIN expected e WHERE r.data_scope_id = e.scope AND (p.event_id IS NULL OR p.data_scope_id <> e.scope OR NOT r.test_only))
            AND NOT EXISTS (SELECT 1 FROM complaint_deletion_journal_retirements r CROSS JOIN expected e WHERE r.data_scope_id = e.scope)
            AND NOT EXISTS (SELECT 1 FROM complaint_journal_scan_runs r CROSS JOIN expected e WHERE r.data_scope_id = e.scope)
            AND NOT EXISTS (SELECT 1 FROM installation_deletion_receipts r CROSS JOIN expected e WHERE r.data_scope_id = e.scope)
            AND NOT EXISTS (SELECT 1 FROM complaint_installation_ids r CROSS JOIN expected e WHERE r.data_scope_id = e.scope AND r.state = 'DELETION_PENDING')
            AND NOT EXISTS (SELECT 1 FROM complaint_resource_ids r CROSS JOIN expected e WHERE r.data_scope_id = e.scope AND r.state = 'DELETION_PENDING')) AS valid
    """.trimIndent()

    val prepareControl = """
        UPDATE complaint_journal_control SET seal_state = 'SEAL_PREPARED', seal_epoch = ?, seal_writer_generation = ?::uuid,
            seal_operation_token = ?::uuid, seal_object_key = ?, seal_bytes = ?, seal_hash = ?, updated_at = clock_timestamp()
        WHERE data_scope_id = ?::uuid AND test_only AND rotation_id = ?::uuid AND rotation_sequence = 1 AND rotation_state = 'CAPTURED'
            AND seal_state IS NULL AND lease_owner = ?::uuid AND lease_token = ? AND lease_expires_at > clock_timestamp()
    """.trimIndent()
    val verifyControl = """
        UPDATE complaint_journal_control SET seal_state = 'SEAL_VERIFIED', seal_object_version = ?, seal_ciphertext_hash = ?,
            seal_retain_until = ?, seal_verified_at = ?, seal_verification_bytes = ?, seal_verification_hash = ?, updated_at = clock_timestamp()
        WHERE data_scope_id = ?::uuid AND test_only AND seal_state = 'SEAL_PREPARED' AND seal_operation_token = ?::uuid
            AND seal_hash = ? AND lease_owner = ?::uuid AND lease_token = ? AND lease_expires_at > clock_timestamp()
    """.trimIndent()
    val insert = """
        INSERT INTO complaint_test_terminal_intents (schema_version, operation_token, data_scope_id, test_only, object_kind, object_ordinal,
            object_id, object_key, routing_key_id, writer_generation, epoch_start, epoch_end, preparing_fencing_token,
            activation_catalog_generation, activation_catalog_hash, configuration_hash, journal_configuration_hash, terminal_encoding_hash,
            publication_ref, canonicalizer, canonical_bytes, canonical_hash, retention_floor, created_at, state)
        VALUES (1, ?::uuid, ?::uuid, true, 'EPOCH_SEAL', 0, ?, ?, ?, ?::uuid, 1, ?, ?, ?, ?, ?, ?, ?, NULL, 'kcj-1', ?, ?, ?, ?, 'CANONICAL')
    """.trimIndent()
    val freeze = """
        UPDATE complaint_test_terminal_intents SET state = 'WIRE_FROZEN', wire_bytes = ?, wire_hash = ?, checksum_sha256 = ?,
            content_type = 'application/octet-stream', object_lock_mode = 'COMPLIANCE', retain_until = ?, metadata_bytes = ?, metadata_hash = ?, frozen_at = ?
        WHERE operation_token = ?::uuid AND data_scope_id = ?::uuid AND test_only AND object_kind = 'EPOCH_SEAL' AND object_ordinal = 0
            AND state = 'CANONICAL' AND canonical_hash = ?
    """.trimIndent()
    val sidecar = """
        SELECT i.operation_token, i.data_scope_id, i.object_kind, i.object_ordinal,
            CASE WHEN octet_length(i.object_id) = 43 THEN i.object_id END AS object_id,
            CASE WHEN octet_length(i.object_key) BETWEEN 1 AND 1024 THEN i.object_key END AS object_key,
            CASE WHEN octet_length(i.routing_key_id) BETWEEN 1 AND 64 THEN i.routing_key_id END AS routing_key_id,
            i.writer_generation, i.epoch_start, i.epoch_end, i.preparing_fencing_token, i.activation_catalog_generation,
            CASE WHEN octet_length(i.activation_catalog_hash) = 32 THEN i.activation_catalog_hash END AS activation_catalog_hash,
            CASE WHEN octet_length(i.configuration_hash) = 32 THEN i.configuration_hash END AS configuration_hash,
            CASE WHEN octet_length(i.journal_configuration_hash) = 32 THEN i.journal_configuration_hash END AS journal_configuration_hash,
            CASE WHEN octet_length(i.terminal_encoding_hash) = 32 THEN i.terminal_encoding_hash END AS terminal_encoding_hash,
            i.retention_floor, i.created_at, i.state, i.retain_until, i.frozen_at,
            CASE WHEN octet_length(i.canonical_bytes) BETWEEN 1 AND 65536 THEN i.canonical_bytes END AS canonical_bytes,
            CASE WHEN octet_length(i.canonical_hash) = 32 THEN i.canonical_hash END AS canonical_hash,
            CASE WHEN octet_length(i.wire_bytes) BETWEEN 1 AND 98304 THEN i.wire_bytes END AS wire_bytes,
            CASE WHEN octet_length(i.wire_hash) = 32 THEN i.wire_hash END AS wire_hash,
            CASE WHEN octet_length(i.metadata_bytes) BETWEEN 1 AND 512 THEN i.metadata_bytes END AS metadata_bytes,
            CASE WHEN octet_length(i.metadata_hash) = 32 THEN i.metadata_hash END AS metadata_hash,
            i.checksum_sha256, i.content_type, i.object_lock_mode,
            (i.schema_version = 1 AND i.test_only AND i.object_kind = 'EPOCH_SEAL' AND i.object_ordinal = 0 AND i.publication_ref IS NULL
                AND i.canonicalizer = 'kcj-1' AND complaint_bytes_match(i.canonical_bytes, i.canonical_hash, 65536)
                AND ((i.state = 'CANONICAL' AND i.wire_bytes IS NULL AND i.wire_hash IS NULL AND i.checksum_sha256 IS NULL
                    AND i.content_type IS NULL AND i.object_lock_mode IS NULL AND i.retain_until IS NULL AND i.metadata_bytes IS NULL
                    AND i.metadata_hash IS NULL AND i.frozen_at IS NULL)
                    OR (i.state = 'WIRE_FROZEN' AND complaint_bytes_match(i.wire_bytes, i.wire_hash, 98304)
                        AND i.checksum_sha256 = encode(i.wire_hash, 'base64') AND i.content_type = 'application/octet-stream'
                        AND i.object_lock_mode = 'COMPLIANCE' AND complaint_bytes_match(i.metadata_bytes, i.metadata_hash, 512)
                        AND i.metadata_bytes = complaint_test_terminal_metadata(i.object_id, i.wire_hash, i.retain_until)))) IS TRUE AS valid
        FROM complaint_test_terminal_intents i WHERE i.data_scope_id = ?::uuid OR i.object_key LIKE ?::text
        ORDER BY i.object_kind, i.object_ordinal, i.operation_token LIMIT 2 FOR UPDATE
    """.trimIndent()
}
