package me.manga.kira.backend.complaint.infrastructure.terminal

import me.manga.kira.backend.complaint.infrastructure.OwnerDeletePersistenceSql

/** Fixed SEALED-only PREPARE statements. Existing V14/V21; no network or later terminal transition. */
internal object TestInstallationManifestSqlV1 {
    // The completed predecessor may transfer its own fence immediately. Any other live holder wins;
    // a later fresh original may take over only after that holder's actual database lease expires.
    val acquire = """
        WITH now AS MATERIALIZED (SELECT clock_timestamp() AS at)
        UPDATE complaint_journal_control c SET lease_owner = ?::uuid, lease_token = c.lease_token + 1,
            lease_expires_at = n.at + (?::bigint * interval '1 millisecond'), updated_at = n.at FROM now n
        WHERE c.data_scope_id = ?::uuid AND c.test_only AND c.lease_token BETWEEN 1 AND 9223372036854775806
            AND (c.lease_owner IS NULL OR c.lease_expires_at <= n.at OR (c.lease_owner = ?::uuid AND c.lease_token = ?))
        RETURNING c.lease_token
    """.trimIndent()
    val release = """
        UPDATE complaint_journal_control SET lease_owner = NULL, lease_expires_at = NULL, updated_at = clock_timestamp()
        WHERE data_scope_id = ?::uuid AND test_only AND lease_owner = ?::uuid AND lease_token = ? AND lease_expires_at > clock_timestamp()
    """.trimIndent()

    val discover = """
        SELECT object_id FROM complaint_test_terminal_intents
        WHERE data_scope_id = ?::uuid AND object_kind = 'INSTALLATION_MANIFEST' AND object_ordinal = ?
    """.trimIndent()
    val publication = OwnerDeletePersistenceSql.LOCK_PUBLICATION
    val recovery = """
        SELECT event_id, data_scope_id, test_only, publication_ref, state, accounting_version, reserved_amounts, created_at,
            (state = 'RESERVED' AND accounting_version = 1 AND publication_ref = event_id AND
                reserved_amounts = array_fill(0::bigint, ARRAY[22]) AND converted_amounts IS NULL AND converted_at IS NULL
                AND created_at IS NOT NULL AND isfinite(created_at)) IS TRUE AS valid
        FROM complaint_recovery_capacity_reservations WHERE event_id = ? FOR UPDATE
    """.trimIndent()

    // Reuse the existing bounded projections/shape guards, not its ORDINARY authority or winner parser.
    val sidecar = TestOrdinarySealSqlV1.sidecar
        .replace("i.object_kind = 'EPOCH_SEAL' AND i.object_ordinal = 0 AND i.publication_ref IS NULL",
            "i.object_kind = 'INSTALLATION_MANIFEST' AND i.object_ordinal BETWEEN 0 AND 4095 AND i.publication_ref = i.object_id")
        .replace("WHERE i.data_scope_id = ?::uuid OR i.object_key LIKE ?::text",
            "WHERE i.data_scope_id = ?::uuid AND i.object_kind = 'INSTALLATION_MANIFEST' AND i.object_ordinal = ?")
    val ordinarySidecar = TestOrdinarySealSqlV1.sidecar.replace(
        "WHERE i.data_scope_id = ?::uuid OR i.object_key LIKE ?::text",
        "WHERE (i.data_scope_id = ?::uuid OR i.object_key LIKE ?::text) AND i.object_kind = 'EPOCH_SEAL'",
    )

    val chunkPage = TestInstallationSourceSqlV1.page.replace(
        "ORDER BY i.id LIMIT", "AND i.id <= ?::uuid ORDER BY i.id LIMIT",
    )
    val counts = """
        WITH e AS MATERIALIZED (SELECT ?::uuid AS scope)
        SELECT count(*) FILTER (WHERE i.object_kind = 'INSTALLATION_MANIFEST') AS manifests,
            count(*) FILTER (WHERE i.object_kind <> 'INSTALLATION_MANIFEST') AS other_intents,
            coalesce(min(i.object_ordinal) FILTER (WHERE i.object_kind = 'INSTALLATION_MANIFEST'), 0) AS first_ordinal,
            coalesce(max(i.object_ordinal) FILTER (WHERE i.object_kind = 'INSTALLATION_MANIFEST'), -1) AS last_ordinal,
            (SELECT count(*) FROM complaint_journal_publications p WHERE p.data_scope_id = e.scope AND p.event_kind = 'INSTALLATION_MANIFEST') AS publications,
            (SELECT count(*) FROM complaint_recovery_capacity_reservations r JOIN complaint_journal_publications p ON p.event_id = r.publication_ref
                WHERE r.data_scope_id = e.scope AND p.event_kind = 'INSTALLATION_MANIFEST') AS reservations,
            (SELECT count(*) FROM complaint_journal_scan_runs s WHERE s.data_scope_id = e.scope) AS scan_runs,
            (SELECT count(*) FROM complaint_journal_scan_entries s WHERE s.data_scope_id = e.scope) AS scan_entries
        FROM e LEFT JOIN complaint_test_terminal_intents i ON i.data_scope_id = e.scope GROUP BY e.scope
    """.trimIndent()

    /** Complete applicable relations, including foreign-scope rows under this exact prefix. No state filter hides bad rows. */
    val relation = """
        WITH e AS MATERIALIZED (SELECT ?::uuid AS scope, ?::text AS ordinary_prefix, ?::text AS terminal_prefix,
            ?::uuid AS writer, ?::bigint AS cutoff, ?::bigint AS epoch)
        SELECT (NOT EXISTS (SELECT 1 FROM complaint_journal_publications p CROSS JOIN e
                LEFT JOIN complaint_recovery_capacity_reservations r ON r.event_id = p.event_id
                LEFT JOIN complaint_test_terminal_intents i ON i.publication_ref = p.event_id
                WHERE (p.data_scope_id = e.scope OR p.object_key LIKE e.ordinary_prefix OR p.object_key LIKE e.terminal_prefix)
                    AND (p.data_scope_id = e.scope AND p.test_only AND p.writer_generation = e.writer AND p.canonicalizer = 'kcj-1'
                        AND complaint_bytes_match(p.event_bytes, p.semantic_hash, 65536) AND isfinite(p.created_at)
                        AND r.data_scope_id = e.scope AND r.test_only AND r.publication_ref = p.event_id AND r.accounting_version = 1
                        AND ((p.event_kind = 'OWNER_DELETE' AND p.target_count = 1 AND p.state = 'APPLIED'
                                AND p.journal_epoch BETWEEN 1 AND e.cutoff AND p.object_key LIKE e.ordinary_prefix
                                AND r.state = 'CONVERTED' AND complaint_vector_lte(r.converted_amounts, r.reserved_amounts)
                                AND r.converted_at IS NOT NULL AND isfinite(r.converted_at) AND i.operation_token IS NULL)
                            OR (p.event_kind = 'INSTALLATION_MANIFEST' AND p.target_count BETWEEN 1 AND 500 AND p.state = 'PREPARED'
                                AND p.journal_epoch = e.epoch AND p.object_key LIKE e.terminal_prefix
                                AND p.object_version IS NULL AND p.ciphertext_hash IS NULL AND p.object_created_at IS NULL
                                AND p.retain_until IS NULL AND p.verified_at IS NULL AND p.verification_bytes IS NULL
                                AND p.verification_hash IS NULL AND p.applied_at IS NULL
                                AND r.state = 'RESERVED' AND r.reserved_amounts = array_fill(0::bigint, ARRAY[22])
                                AND r.converted_amounts IS NULL AND r.converted_at IS NULL AND r.created_at = p.created_at
                                AND i.object_kind = 'INSTALLATION_MANIFEST' AND i.state = 'CANONICAL' AND i.data_scope_id = e.scope
                                AND i.object_id = p.event_id AND i.object_key = p.object_key AND i.routing_key_id = p.routing_key_id
                                AND i.writer_generation = p.writer_generation AND i.epoch_start = p.journal_epoch AND i.epoch_end = p.journal_epoch
                                AND i.created_at = p.created_at AND i.canonical_bytes = p.event_bytes AND i.canonical_hash = p.semantic_hash))) IS NOT TRUE)
            AND NOT EXISTS (SELECT 1 FROM complaint_test_terminal_intents i CROSS JOIN e
                LEFT JOIN complaint_journal_publications p ON p.event_id = i.publication_ref
                WHERE (i.data_scope_id = e.scope OR i.object_key LIKE e.terminal_prefix)
                    AND (i.data_scope_id = e.scope AND i.test_only AND i.writer_generation = e.writer
                        AND i.object_key LIKE e.terminal_prefix AND i.schema_version = 1 AND i.canonicalizer = 'kcj-1'
                        AND ((i.object_kind = 'EPOCH_SEAL' AND i.object_ordinal = 0 AND i.state = 'WIRE_FROZEN' AND i.publication_ref IS NULL)
                            OR (i.object_kind = 'INSTALLATION_MANIFEST' AND i.object_ordinal BETWEEN 0 AND 4095
                                AND i.state = 'CANONICAL' AND p.event_id = i.object_id AND p.data_scope_id = e.scope))) IS NOT TRUE)
            AND NOT EXISTS (SELECT 1 FROM complaint_recovery_capacity_reservations r CROSS JOIN e
                LEFT JOIN complaint_journal_publications p ON p.event_id = r.publication_ref
                WHERE (r.data_scope_id = e.scope OR p.data_scope_id = e.scope)
                    AND (r.data_scope_id = e.scope AND r.test_only AND r.event_id = r.publication_ref AND p.data_scope_id = e.scope) IS NOT TRUE)
            AND NOT EXISTS (SELECT 1 FROM complaint_idempotency_receipts r CROSS JOIN e
                LEFT JOIN complaint_journal_publications p ON p.event_id = r.publication_ref
                WHERE (r.data_scope_id = e.scope OR p.data_scope_id = e.scope)
                    AND (r.data_scope_id = e.scope AND r.test_only AND r.state = 'COMPLETED'
                        AND ((r.publication_ref IS NULL AND r.external_event_id IS NULL)
                            OR (r.operation = 'OWNER_DELETE' AND r.actor_kind = 'INSTALLATION' AND p.data_scope_id = e.scope
                                AND p.event_kind = 'OWNER_DELETE' AND p.state = 'APPLIED'))) IS NOT TRUE)
            AND NOT EXISTS (SELECT 1 FROM complaint_deletion_journal_applied a CROSS JOIN e
                WHERE (a.data_scope_id = e.scope OR a.object_key LIKE e.ordinary_prefix OR a.object_key LIKE e.terminal_prefix)
                    AND (a.data_scope_id = e.scope AND a.test_only AND a.writer_generation = e.writer AND a.event_kind = 'OWNER_DELETE'
                        AND a.target_count = 1 AND a.journal_epoch BETWEEN 1 AND e.cutoff AND a.object_key LIKE e.ordinary_prefix) IS NOT TRUE)
            AND NOT EXISTS (SELECT 1 FROM complaint_resource_ids r CROSS JOIN e WHERE r.data_scope_id = e.scope AND r.state = 'DELETION_PENDING')
            AND NOT EXISTS (SELECT 1 FROM complaint_installation_ids r CROSS JOIN e WHERE r.data_scope_id = e.scope AND r.state = 'DELETION_PENDING')
            AND NOT EXISTS (SELECT 1 FROM complaint_deletion_journal_retirements r CROSS JOIN e WHERE r.data_scope_id = e.scope)
            AND NOT EXISTS (SELECT 1 FROM installation_deletion_receipts r CROSS JOIN e WHERE r.data_scope_id = e.scope)) AS valid
    """.trimIndent()

    val insertPublication = """
        INSERT INTO complaint_journal_publications (event_id, data_scope_id, test_only, writer_generation, journal_epoch, event_kind,
            target_count, routing_key_id, object_key, canonicalizer, event_bytes, semantic_hash, state, created_at)
        VALUES (?, ?::uuid, true, ?::uuid, ?, 'INSTALLATION_MANIFEST', ?, ?, ?, 'kcj-1', ?, ?, 'PREPARED', ?::timestamptz)
    """.trimIndent()
    val insertRecovery = """
        INSERT INTO complaint_recovery_capacity_reservations (event_id, data_scope_id, test_only, publication_ref, state,
            accounting_version, reserved_amounts, created_at)
        VALUES (?, ?::uuid, true, ?, 'RESERVED', 1, array_fill(0::bigint, ARRAY[22]), ?::timestamptz)
    """.trimIndent()
    val insertSidecar = """
        INSERT INTO complaint_test_terminal_intents (schema_version, operation_token, data_scope_id, test_only, object_kind, object_ordinal,
            object_id, object_key, routing_key_id, writer_generation, epoch_start, epoch_end, preparing_fencing_token,
            activation_catalog_generation, activation_catalog_hash, configuration_hash, journal_configuration_hash, terminal_encoding_hash,
            publication_ref, canonicalizer, canonical_bytes, canonical_hash, retention_floor, created_at, state)
        VALUES (1, ?::uuid, ?::uuid, true, 'INSTALLATION_MANIFEST', ?, ?, ?, ?, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'kcj-1', ?, ?, ?, ?, 'CANONICAL')
    """.trimIndent()
}
