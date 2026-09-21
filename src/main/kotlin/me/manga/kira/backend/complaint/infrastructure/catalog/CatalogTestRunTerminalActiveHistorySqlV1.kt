package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainSqlV1

/** Bounded historical reads under E's already held closed controls. No new lock, write or authority. */
internal object CatalogTestRunTerminalActiveHistorySqlV1 {
    private val expected = """
        WITH e AS MATERIALIZED (SELECT ?::uuid AS scope, ?::bytea AS configuration_hash, ?::bytea AS journal_hash,
            ?::bigint AS desired_generation, ?::integer AS implementation_schema, ?::uuid AS database_identity,
            ?::uuid AS restore_identity, ?::uuid AS writer, ?::bigint AS activation_generation, ?::bytea AS activation_hash,
            ?::timestamptz AS run_created_at, ?::uuid AS catalog_writer, ?::bytea AS trust_hash),
            s AS MATERIALIZED (SELECT clock_timestamp() AS at)
    """.trimIndent()
    private val identity = """
        r.data_scope_id = e.scope AND r.test_only AND r.state IN ('SEALED', 'PURGING')
        AND r.configuration_hash = e.configuration_hash AND r.created_at = e.run_created_at
        AND r.activation_catalog_generation = e.activation_generation AND r.activation_catalog_hash = e.activation_hash
        AND c.data_scope_id = e.scope AND c.test_only AND c.maintenance_closed AND c.creation_closed
        AND c.implementation_schema = e.implementation_schema AND c.desired_generation = e.desired_generation
        AND c.desired_configuration_hash = e.configuration_hash AND c.database_identity = e.database_identity
        AND c.restore_identity = e.restore_identity AND c.event_writer_generation = e.writer
        AND c.accepted_catalog_generation = e.activation_generation AND c.accepted_catalog_hash = e.activation_hash
        AND c.catalog_writer_generation = e.catalog_writer AND c.trust_bundle_hash = e.trust_hash
        AND c.pending_projection_token IS NULL AND c.lease_owner IS NULL AND c.lease_expires_at IS NULL
        AND r.created_at <= r.sealed_at AND r.sealed_at <= s.at AND complaint_finite_times(r.created_at, r.sealed_at, s.at)
    """.trimIndent()
    private val slotHash = """CASE WHEN octet_length(to_jsonb(i)::text) BETWEEN 1 AND 524288
        THEN sha256(convert_to((to_jsonb(i) || jsonb_build_object('row_xmin', i.xmin::text))::text, 'UTF8')) END"""
    private val queueHash = """CASE WHEN octet_length(to_jsonb(o)::text) BETWEEN 1 AND 4096
        THEN sha256(convert_to((to_jsonb(o) || jsonb_build_object('row_xmin', o.xmin::text))::text, 'UTF8')) END"""
    // Caps are applied before pgjdbc allocation. A violated cap becomes refusal, never truncation.
    private val sealProjection = (listOf(
        "i.journal_configuration_hash", "i.seal_encoding_hash", "i.configuration_hash", "i.activation_catalog_hash",
        "i.accepted_catalog_hash", "i.trust_bundle_hash", "i.canonical_hash", "i.wire_hash", "i.metadata_hash",
        "c.seal_verification_hash", "c.checkpoint_hash",
    ).map { it to 32 } + listOf(
        "i.canonical_bytes" to 65536, "i.wire_bytes" to 98304, "i.metadata_bytes" to 512,
        "c.checkpoint_bytes" to 65536, "c.seal_verification_bytes" to 65536,
        "i.state" to 16, "i.object_id" to 43, "i.object_key" to 1024, "i.routing_key_id" to 64,
        "i.checksum_sha256" to 44, "i.content_type" to 24, "i.object_lock_mode" to 10, "c.seal_object_version" to 1024,
    )).joinToString(",\n            ") { (field, maximum) ->
        "CASE WHEN octet_length($field) BETWEEN 1 AND $maximum THEN $field END AS ${field.substringAfter('.')}"
    }
    val initialSeal = """
        $expected
        SELECT i.data_scope_id, i.writer_generation, i.activation_catalog_generation, i.operation_token,
            i.preparing_fencing_token, i.retention_floor, i.created_at, i.desired_generation, i.database_identity,
            i.restore_identity, i.accepted_catalog_generation, i.catalog_writer_generation, i.retain_until, i.frozen_at,
            c.seal_retain_until, c.seal_verified_at, c.checkpoint_fencing_token, c.checkpoint_started_at, c.checkpoint_completed_at,
            $sealProjection, s.at AS sampled_at, $slotHash AS slot_fingerprint,
            sha256(convert_to(jsonb_build_array(${TestOrdinaryDrainSqlV1.historyFields})::text, 'UTF8')) AS history_fingerprint,
            ($identity AND i.data_scope_id = e.scope AND i.schema_version = 1 AND i.test_only
                AND i.run_created_at = r.created_at AND i.configuration_hash = r.configuration_hash
                AND i.activation_catalog_generation = r.activation_catalog_generation AND i.activation_catalog_hash = r.activation_catalog_hash
                AND i.implementation_schema = 1 AND i.implementation_schema = c.implementation_schema AND i.desired_generation = c.desired_generation
                AND i.configuration_hash = c.desired_configuration_hash AND i.database_identity = c.database_identity
                AND i.restore_identity = c.restore_identity AND i.writer_generation = c.event_writer_generation
                AND i.journal_configuration_hash = e.journal_hash
                AND i.accepted_catalog_generation = c.accepted_catalog_generation AND i.accepted_catalog_hash = c.accepted_catalog_hash
                AND i.trust_bundle_hash = c.trust_bundle_hash AND i.catalog_writer_generation = c.catalog_writer_generation
                AND i.rotation_sequence = 1 AND i.epoch_start = 1 AND i.epoch_end = 1 AND i.epoch_after = 2
                AND i.charged_storage_bytes = 2097152 AND i.state = 'WIRE_FROZEN' AND i.canonicalizer = 'kcj-1'
                AND complaint_is_v4(i.request_owner) AND complaint_is_v4(i.capture_owner)
                AND i.request_token > 0 AND i.capture_token >= i.request_token AND i.preparing_fencing_token > i.capture_token
                AND i.requested_at >= r.created_at AND i.captured_at >= i.requested_at AND i.created_at >= i.captured_at
                AND i.frozen_at >= i.created_at AND i.frozen_at <= c.seal_verified_at AND i.retain_until > s.at
                AND c.seal_state = 'SEAL_VERIFIED' AND c.seal_epoch = 1 AND c.seal_writer_generation = i.writer_generation
                AND c.seal_operation_token = i.operation_token AND c.seal_object_key = i.object_key
                AND c.seal_bytes = i.canonical_bytes AND c.seal_hash = i.canonical_hash AND c.seal_ciphertext_hash = i.wire_hash
                AND complaint_bytes_match(i.canonical_bytes, i.canonical_hash, 65536) AND complaint_bytes_match(i.wire_bytes, i.wire_hash, 98304)
                AND complaint_bytes_match(i.metadata_bytes, i.metadata_hash, 512) AND complaint_bytes_match(c.seal_verification_bytes, c.seal_verification_hash, 65536)
                AND c.seal_retain_until >= i.retain_until AND c.seal_retain_until > s.at AND c.seal_verified_at <= c.checkpoint_started_at
                AND c.checkpoint_generation = i.desired_generation AND c.checkpoint_fencing_token > i.preparing_fencing_token AND c.checkpoint_fencing_token <= c.lease_token
                AND c.checkpoint_catalog_generation = i.accepted_catalog_generation AND c.checkpoint_catalog_hash = i.accepted_catalog_hash
                AND c.checkpoint_writer_generation = i.writer_generation AND c.checkpoint_cutoff_epoch = 1
                AND c.checkpoint_configuration_hash = i.configuration_hash AND c.checkpoint_database_identity = i.database_identity
                AND c.checkpoint_restore_identity = i.restore_identity AND c.checkpoint_schema = 1 AND c.checkpoint_result = 'SUCCESS'
                AND c.checkpoint_object_count = 0 AND c.checkpoint_byte_count = 0
                AND c.checkpoint_completed_at >= c.checkpoint_started_at AND c.checkpoint_completed_at <= r.sealed_at
                AND complaint_bytes_match(c.checkpoint_bytes, c.checkpoint_hash, 65536)
                AND c.rotation_sequence = 2 AND c.rotation_epoch_before = 2 AND c.rotation_epoch_after = 3 AND c.publication_epoch = 4
                AND r.final_ordinary_epoch = 2 AND r.terminal_seal_epoch = 3 AND r.generation_seal_count = 3
                AND complaint_finite_times(c.checkpoint_started_at, c.checkpoint_completed_at, c.seal_verified_at, c.seal_retain_until)
                AND complaint_finite_times(i.requested_at, i.captured_at, i.created_at, i.retention_floor, i.retain_until, i.frozen_at)
                AND NOT EXISTS (SELECT 1 FROM complaint_journal_scan_runs x WHERE x.data_scope_id = e.scope AND x.active_initial_seal_token IS NOT NULL)
                AND octet_length(to_jsonb(i)::text) BETWEEN 1 AND 524288) IS TRUE AS valid
        FROM complaint_test_active_seal_intents i CROSS JOIN e CROSS JOIN s
        LEFT JOIN complaint_test_runs r ON r.data_scope_id = e.scope
        LEFT JOIN complaint_journal_control c ON c.data_scope_id = e.scope
        WHERE i.data_scope_id = e.scope OR i.object_key LIKE ?::text ORDER BY i.operation_token LIMIT 2
        """.trimIndent()

    // SETTLED is bookkeeping, not a drain/checkpoint/denial proof. Never filter away POLLING or a
    // foreign alias of this full-D/J. Those rows must reach the mapper and be refused.
    val queue = """
        $expected
        SELECT ($identity AND o.data_scope_id = e.scope AND o.test_only
            AND o.desired_generation = e.desired_generation AND o.implementation_schema = e.implementation_schema
            AND o.database_identity = e.database_identity AND o.restore_identity = e.restore_identity AND o.writer_generation = e.writer
            AND o.configuration_hash = e.configuration_hash AND o.journal_hash = e.journal_hash
            AND o.catalog_generation = e.activation_generation AND o.catalog_hash = e.activation_hash
            AND o.trust_bundle_hash = e.trust_hash AND o.catalog_writer_generation = e.catalog_writer
            AND o.storage_bytes = 8192 AND complaint_is_v4(o.lease_owner)
            AND o.fencing_token > 0 AND o.fencing_token < c.rotation_capture_token AND o.fencing_token < c.lease_token
            AND o.state = 'SETTLED' AND o.started_at >= r.created_at AND o.settled_at >= o.started_at
            AND o.settled_at <= r.sealed_at AND o.settled_at <= c.rotation_captured_at
            AND o.primary_acked BETWEEN 0 AND 1 AND o.primary_acked IS NOT NULL AND o.dlq_acked BETWEEN 0 AND 1 AND o.dlq_acked IS NOT NULL
            AND complaint_finite_times(o.started_at, o.settled_at) AND octet_length(to_jsonb(o)::text) BETWEEN 1 AND 4096
        ) IS TRUE AS valid, $queueHash AS fingerprint
        FROM complaint_test_active_queue_observations o CROSS JOIN e CROSS JOIN s
        LEFT JOIN complaint_test_runs r ON r.data_scope_id = e.scope
        LEFT JOIN complaint_journal_control c ON c.data_scope_id = e.scope
        WHERE o.data_scope_id = e.scope OR o.configuration_hash = e.configuration_hash OR o.journal_hash = e.journal_hash
        ORDER BY o.data_scope_id LIMIT 2
    """.trimIndent()
    val globalIdentity = """
        SELECT desired_generation,
            CASE WHEN desired_configuration_hash IS NULL OR octet_length(desired_configuration_hash) = 32 THEN desired_configuration_hash END AS configuration_hash,
            (desired_generation > 0 AND (desired_configuration_hash IS NULL OR octet_length(desired_configuration_hash) = 32)) IS TRUE AS valid
        FROM complaint_journal_control WHERE data_scope_id = '00000000-0000-0000-0000-000000000000'::uuid
    """.trimIndent()
    // Lease-only phases compare already validated immutable row/xmin identities without a run read
    // or a new lock class. Absence is also preserved. No current-content approval is issued here.
    val sealIdentity = """
        SELECT $slotHash AS fingerprint FROM complaint_test_active_seal_intents i
        WHERE i.data_scope_id = ?::uuid OR i.object_key LIKE ?::text ORDER BY i.operation_token LIMIT 2
    """.trimIndent()
    val queueIdentity = """
        SELECT $queueHash AS fingerprint FROM complaint_test_active_queue_observations o
        WHERE o.data_scope_id = ?::uuid OR o.configuration_hash = ?::bytea OR o.journal_hash = ?::bytea
        ORDER BY o.data_scope_id LIMIT 2
    """.trimIndent()
}
