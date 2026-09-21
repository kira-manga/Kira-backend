package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.complaint.infrastructure.ComplaintInstallationTestRunRows

/** Fixed first-deletion readers. C's stricter CREATE/no-publication reader remains unchanged. */
internal object TestRegisteredInitialCheckpointDeletionSqlV1 {
    private const val GLOBAL = "'00000000-0000-0000-0000-000000000000'::uuid"
    private val expected = """
        WITH e AS MATERIALIZED (SELECT ?::uuid AS scope, ?::bytea AS configuration_hash, ?::bigint AS installation_limit,
            ?::bigint AS generation, ?::bytea AS activation_hash, ?::timestamptz AS created_at, ?::bigint[] AS original_reserve),
        d AS MATERIALIZED (SELECT ?::uuid AS scope, ?::bigint AS desired_generation, ?::integer AS implementation_schema,
            ?::bytea AS configuration_hash, ?::uuid AS database_identity, ?::uuid AS restore_identity,
            ?::uuid AS event_writer, ?::uuid AS catalog_writer, ?::bytea AS trust_hash, ?::bigint AS generation, ?::bytea AS activation_hash),
        b AS MATERIALIZED (SELECT ?::bigint AS desired_generation, ?::bytea AS configuration_hash),
        j AS MATERIALIZED (SELECT ?::bytea AS journal_hash), s AS MATERIALIZED (SELECT clock_timestamp() AS sampled_at)
    """.trimIndent()
    /** Same-statement registered birth/desired identity only. No lease, scan, freshness or quota gate. */
    val identity = """
        $expected, current_identity AS MATERIALIZED (
            SELECT (e.scope = d.scope AND r.test_only AND r.configuration_hash = e.configuration_hash
                AND r.installation_limit = e.installation_limit AND r.activation_catalog_generation = e.generation
                AND r.activation_catalog_hash = e.activation_hash AND r.created_at = e.created_at
                AND r.original_reserve = e.original_reserve AND r.accounting_version = 1
                AND c.test_only AND c.implementation_schema = d.implementation_schema AND c.desired_generation = d.desired_generation
                AND c.desired_configuration_hash = d.configuration_hash AND c.database_identity = d.database_identity
                AND c.restore_identity = d.restore_identity AND c.event_writer_generation = d.event_writer
                AND c.catalog_writer_generation = d.catalog_writer AND c.trust_bundle_hash = d.trust_hash
                AND c.accepted_catalog_generation = d.generation AND c.accepted_catalog_hash = d.activation_hash
                AND NOT g.test_only AND g.implementation_schema = 1 AND g.desired_generation = b.desired_generation
                AND g.desired_configuration_hash IS NOT DISTINCT FROM b.configuration_hash
                AND g.database_identity = d.database_identity AND g.restore_identity = d.restore_identity
                AND g.event_writer_generation = d.event_writer AND g.catalog_writer_generation = d.catalog_writer
                AND g.trust_bundle_hash = d.trust_hash AND g.accepted_catalog_generation = d.generation AND g.accepted_catalog_hash = d.activation_hash
            ) IS TRUE AS matches, c.publication_epoch, COALESCE(c.seal_epoch, 0) AS seal_epoch
            FROM e CROSS JOIN d CROSS JOIN b
            LEFT JOIN complaint_test_runs r ON r.data_scope_id = e.scope
            LEFT JOIN complaint_journal_control c ON c.data_scope_id = d.scope
            LEFT JOIN complaint_journal_control g ON g.data_scope_id = $GLOBAL
        )
    """.trimIndent()
    val controls = "$identity SELECT matches, publication_epoch, seal_epoch FROM current_identity"

    // These comparisons come ONLY from the retained concrete new-claim operation, never a request DTO.
    private val owned = """
        o AS MATERIALIZED (SELECT ?::text AS family, ?::uuid AS actor_id, ?::uuid AS operation_key,
            ?::bytea AS fingerprint, ?::bigint AS credential_version, ?::uuid[] AS target_ids,
            ?::text AS event_id, ?::text AS object_key, ?::text AS routing_key_id, ?::bytea AS event_bytes, ?::bytea AS semantic_hash,
            ?::bigint[] AS recovery, ?::boolean AS reservation_inserted, ?::timestamptz AS authorized_at,
            ?::uuid AS consumed_grant_id, ?::text AS rejection, ?::integer AS rejection_status)
    """.trimIndent()
    private val ownedHistory = """
        -- Exactly this first claim; CREATE receipts are not deletion history.
        (SELECT count(*) FROM complaint_idempotency_receipts n WHERE n.data_scope_id = e.scope
            AND n.operation IN ('OWNER_DELETE','ADMIN_DELETE','ADMIN_BATCH_DELETE')) = CASE WHEN o.family = 'OWNER_DELETE_ALL' THEN 0 ELSE 1 END
        AND NOT EXISTS (SELECT 1 FROM complaint_idempotency_receipts n
            WHERE n.data_scope_id = e.scope AND n.operation IN ('OWNER_DELETE','ADMIN_DELETE','ADMIN_BATCH_DELETE')
            AND NOT ((n.test_only AND n.actor_id = o.actor_id AND n.idempotency_key = o.operation_key
                AND n.actor_kind = CASE WHEN o.family = 'OWNER_DELETE' THEN 'INSTALLATION' ELSE 'ADMIN' END
                AND n.operation = o.family AND n.fingerprint = o.fingerprint AND n.target_ids = o.target_ids
                AND n.ack_ids IS NULL AND n.ack_versions IS NULL AND n.response_etag IS NULL AND n.response_location IS NULL
                AND n.external_event_id IS NULL AND n.external_epoch IS NULL AND n.external_object_version IS NULL AND n.external_ciphertext_hash IS NULL
                AND complaint_finite_times(n.created_at, n.authorized_at, n.completed_at, n.expires_at) AND n.created_at <= s.sampled_at
                AND ((o.authorized_at IS NOT NULL AND o.rejection IS NULL AND n.state = 'AUTHORIZED_DELETE'
                    AND n.publication_ref = o.event_id AND n.authorized_at = o.authorized_at
                    AND n.consumed_grant_id IS NOT DISTINCT FROM o.consumed_grant_id
                    AND n.outcome IS NULL AND n.response_status IS NULL AND n.problem_code IS NULL AND n.completed_at IS NULL AND n.expires_at IS NULL)
                OR (o.authorized_at IS NULL AND o.rejection IS NULL AND n.state = 'IN_PROGRESS'
                    AND n.publication_ref IS NULL AND n.authorized_at IS NULL AND n.consumed_grant_id IS NULL
                    AND n.outcome IS NULL AND n.response_status IS NULL AND n.problem_code IS NULL AND n.completed_at IS NULL AND n.expires_at IS NULL)
                OR (o.authorized_at IS NULL AND o.rejection IS NOT NULL AND n.state = 'COMPLETED' AND n.outcome = 'REJECTED'
                    AND n.problem_code = o.rejection AND n.response_status = o.rejection_status
                    AND n.consumed_grant_id IS NOT DISTINCT FROM o.consumed_grant_id
                    AND n.publication_ref IS NULL AND n.authorized_at IS NULL AND n.completed_at <= s.sampled_at
                    AND n.expires_at = n.completed_at + interval '192 hours'))) IS TRUE))
        AND (SELECT count(*) FROM installation_deletion_receipts n WHERE n.data_scope_id = e.scope) = CASE WHEN o.family = 'OWNER_DELETE_ALL' THEN 1 ELSE 0 END
        AND NOT EXISTS (SELECT 1 FROM installation_deletion_receipts n WHERE n.data_scope_id = e.scope
            AND NOT ((o.family = 'OWNER_DELETE_ALL' AND n.test_only AND n.installation_id = o.actor_id
                AND n.deletion_key = o.operation_key AND n.submitted_credential_version = o.credential_version AND n.fingerprint = o.fingerprint
                AND n.outcome IS NULL AND n.response_status IS NULL AND n.completed_at IS NULL AND n.expires_at IS NULL
                AND n.external_event_id IS NULL AND n.external_epoch IS NULL AND n.external_object_version IS NULL AND n.external_ciphertext_hash IS NULL
                AND complaint_finite_times(n.created_at, n.authorized_at) AND n.created_at <= s.sampled_at
                AND ((o.authorized_at IS NULL AND n.state = 'IN_PROGRESS' AND n.authorized_at IS NULL AND n.publication_ref IS NULL)
                    OR (o.authorized_at IS NOT NULL AND n.state = 'AUTHORIZED_DELETE' AND n.authorized_at = o.authorized_at AND n.publication_ref = o.event_id))) IS TRUE))
        -- Reject foreign-scope aliases too: native inventory scans the entire ordinary prefix.
        AND (SELECT count(*) FROM complaint_journal_publications p WHERE p.data_scope_id = e.scope
            OR starts_with(p.object_key, 'complaints/journal/v1/' || d.event_writer::text || '/test/' || e.scope::text || '/ordinary/'))
                = CASE WHEN o.authorized_at IS NULL THEN 0 ELSE 1 END
        AND NOT EXISTS (SELECT 1 FROM complaint_journal_publications p WHERE
            (p.data_scope_id = e.scope OR starts_with(p.object_key, 'complaints/journal/v1/' || d.event_writer::text || '/test/' || e.scope::text || '/ordinary/'))
            AND NOT ((p.data_scope_id = e.scope AND p.test_only AND p.event_id = o.event_id AND p.object_key = o.object_key
                AND p.writer_generation = d.event_writer AND p.journal_epoch = 2 AND p.event_kind = o.family AND p.routing_key_id = o.routing_key_id
                AND p.target_count = cardinality(o.target_ids) AND p.canonicalizer = 'kcj-1'
                AND p.event_bytes = o.event_bytes AND p.semantic_hash = o.semantic_hash
                AND complaint_bytes_match(p.event_bytes, p.semantic_hash, 65536) AND p.created_at = o.authorized_at
                AND p.state = 'PREPARED' AND p.object_version IS NULL AND p.ciphertext_hash IS NULL AND p.object_created_at IS NULL
                AND p.retain_until IS NULL AND p.verified_at IS NULL AND p.verification_bytes IS NULL AND p.verification_hash IS NULL AND p.applied_at IS NULL) IS TRUE))
        AND (SELECT count(*) FROM complaint_recovery_capacity_reservations l WHERE l.data_scope_id = e.scope
            OR l.event_id = o.event_id OR l.publication_ref = o.event_id) = CASE WHEN o.reservation_inserted THEN 1 ELSE 0 END
        AND NOT EXISTS (SELECT 1 FROM complaint_recovery_capacity_reservations l
            WHERE (l.data_scope_id = e.scope OR l.event_id = o.event_id OR l.publication_ref = o.event_id)
            AND NOT ((o.reservation_inserted AND l.data_scope_id = e.scope AND l.test_only AND l.event_id = o.event_id
                AND l.publication_ref = o.event_id AND l.state = 'RESERVED' AND l.accounting_version = 1
                AND l.reserved_amounts = o.recovery AND complaint_vector_valid(l.reserved_amounts)
                AND l.converted_amounts IS NULL AND l.converted_at IS NULL AND isfinite(l.created_at) AND l.created_at <= s.sampled_at) IS TRUE))
        AND NOT EXISTS (SELECT 1 FROM complaint_deletion_journal_applied a WHERE a.data_scope_id = e.scope
            OR starts_with(a.object_key, 'complaints/journal/v1/' || d.event_writer::text || '/test/' || e.scope::text || '/ordinary/'))
        AND NOT EXISTS (SELECT 1 FROM complaint_deletion_journal_retirements t WHERE t.data_scope_id = e.scope
            OR starts_with(t.object_key, 'complaints/journal/v1/' || d.event_writer::text || '/test/' || e.scope::text || '/ordinary/'))
    """.trimIndent()

    private val completedCheckpoint = """
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
    """.trimIndent()

    val current = """
        $expected, $owned
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
            -- Privacy deletion keeps the existing hard-ledger path when creation alone is closed.
            AND NOT c.maintenance_closed AND c.pending_projection_token IS NULL AND ($completedCheckpoint) AND ($ownedHistory)
            AND NOT g.test_only AND g.implementation_schema = 1 AND g.desired_generation = b.desired_generation
            AND g.desired_configuration_hash IS NOT DISTINCT FROM b.configuration_hash
            AND g.database_identity = d.database_identity AND g.restore_identity = d.restore_identity
            AND g.event_writer_generation = d.event_writer AND g.catalog_writer_generation = d.catalog_writer
            AND g.trust_bundle_hash = d.trust_hash AND g.accepted_catalog_generation = d.generation AND g.accepted_catalog_hash = d.activation_hash
            AND NOT g.maintenance_closed AND g.pending_projection_token IS NULL AND g.publication_epoch > 0
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
            AND NOT EXISTS (SELECT 1 FROM complaint_journal_scan_entries t WHERE t.data_scope_id = e.scope)
        ) IS TRUE AS valid, c.lease_owner, c.lease_token, c.lease_expires_at, s.sampled_at, c.seal_verified_at,
            i.operation_token, i.request_owner, i.request_token, i.requested_at, i.capture_owner, i.capture_token, i.captured_at, i.preparing_fencing_token,
            CASE WHEN octet_length(i.state) BETWEEN 1 AND 16 THEN i.state END AS state,
            sha256(convert_to((to_jsonb(i) || jsonb_build_object('row_xmin', i.xmin::text))::text, 'UTF8')) AS slot_fingerprint,
            sha256(convert_to((to_jsonb(g) || jsonb_build_object('row_xmin', g.xmin::text))::text, 'UTF8')) AS global_fingerprint,
            sha256(convert_to((to_jsonb(r) || jsonb_build_object('row_xmin', r.xmin::text))::text, 'UTF8')) AS run_fingerprint,
            sha256(convert_to((to_jsonb(c) - ARRAY['lease_owner','lease_token','lease_expires_at','updated_at'])::text, 'UTF8')) AS control_fingerprint
        FROM e CROSS JOIN d CROSS JOIN b CROSS JOIN j CROSS JOIN s CROSS JOIN o
        LEFT JOIN complaint_test_runs r ON r.data_scope_id = e.scope
        LEFT JOIN complaint_journal_control c ON c.data_scope_id = d.scope
        LEFT JOIN complaint_journal_control g ON g.data_scope_id = $GLOBAL
        LEFT JOIN complaint_test_active_seal_intents i ON i.data_scope_id = e.scope LIMIT 2
    """.trimIndent()
}
