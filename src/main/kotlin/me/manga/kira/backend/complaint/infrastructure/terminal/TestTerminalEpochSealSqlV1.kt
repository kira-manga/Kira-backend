package me.manga.kira.backend.complaint.infrastructure.terminal

/** Successor-only SQL. The old ordinary control/run predicates are deliberately not widened. */
internal object TestTerminalEpochSealSqlV1 {
    val acquire = TestOrdinarySealSqlV1.acquire
    val release = TestInstallationManifestSqlV1.release
    val control = TestOrdinaryDrainSqlV1.control.replace(
        "c.publication_epoch = c.rotation_epoch_after AND c.rotation_epoch_before > 0",
        "c.publication_epoch >= c.rotation_epoch_after AND c.publication_epoch - c.rotation_epoch_after <= 1 AND c.rotation_epoch_before > 0",
    )
    val run = TestOrdinaryDrainSqlV1.run.replace("r.generation_seal_count = 1", "r.generation_seal_count BETWEEN 1 AND 2")
    val controlWithActiveHistory = TestOrdinaryDrainSqlV1.controlWithActiveHistory.replace(
        "c.publication_epoch = i.epoch_after + 1 AND c.scan_requested",
        "c.publication_epoch BETWEEN i.epoch_after + 1 AND i.epoch_after + 2 AND c.scan_requested",
    )
    val runWithActiveHistory = TestOrdinaryDrainSqlV1.runWithActiveHistory.replace(
        TestOrdinaryDrainSqlV1.activeOrdinaryCount, TestOrdinaryDrainSqlV1.activeGrowingCount)
    val sidecar = TestOrdinarySealSqlV1.sidecar
        .replace("i.object_kind = 'EPOCH_SEAL' AND i.object_ordinal = 0", "i.object_kind = 'EPOCH_SEAL' AND i.object_ordinal = 1")
        .replace("WHERE i.data_scope_id = ?::uuid OR i.object_key LIKE ?::text",
            "WHERE i.data_scope_id = ?::uuid AND i.object_kind = 'EPOCH_SEAL' AND i.object_ordinal = 1")
    val ordinarySidecar = TestInstallationManifestSqlV1.ordinarySidecar.replace(
        "AND i.object_kind = 'EPOCH_SEAL'", "AND i.object_kind = 'EPOCH_SEAL' AND i.object_ordinal = 0",
    )

    /** One transaction also INSERTs the paid ordinal-1 CANONICAL row; this is never a standalone phase. */
    val rotate = """
        UPDATE complaint_journal_control SET publication_epoch = ?::bigint, updated_at = clock_timestamp()
        WHERE data_scope_id = ?::uuid AND test_only AND publication_epoch = ?::bigint
            AND event_writer_generation = ?::uuid AND rotation_state = 'CAPTURED' AND rotation_sequence = 1
            AND rotation_id = ?::uuid AND rotation_capture_token = ?::bigint AND rotation_epoch_before = ?::bigint
            AND rotation_epoch_after = publication_epoch AND scan_requested
            AND lease_owner = ?::uuid AND lease_token = ?::bigint AND lease_expires_at > clock_timestamp()
    """.trimIndent()
    val rotateWithActiveHistory = rotate.replace("rotation_sequence = 1", """rotation_sequence BETWEEN 2 AND 15
                AND rotation_sequence = 1 + (SELECT count(*) FROM complaint_test_active_seal_intents a WHERE a.data_scope_id = complaint_journal_control.data_scope_id)
                    + (SELECT count(*) FROM complaint_test_active_recurrent_seal_intents a WHERE a.data_scope_id = complaint_journal_control.data_scope_id)""")
    val insert = """
        INSERT INTO complaint_test_terminal_intents (schema_version, operation_token, data_scope_id, test_only, object_kind, object_ordinal,
            object_id, object_key, routing_key_id, writer_generation, epoch_start, epoch_end, preparing_fencing_token,
            activation_catalog_generation, activation_catalog_hash, configuration_hash, journal_configuration_hash, terminal_encoding_hash,
            publication_ref, canonicalizer, canonical_bytes, canonical_hash, retention_floor, created_at, state)
        VALUES (1, ?::uuid, ?::uuid, true, 'EPOCH_SEAL', 1, ?, ?, ?, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, NULL, 'kcj-1', ?, ?, ?, ?, 'CANONICAL')
    """.trimIndent()
    val freeze = TestOrdinarySealSqlV1.freeze.replace("object_kind = 'EPOCH_SEAL' AND object_ordinal = 0", "object_kind = 'EPOCH_SEAL' AND object_ordinal = 1")
    val verifyRun = """
        UPDATE complaint_test_runs SET generation_seal_count = 2, generation_seal_root = ?, seal_set_bytes = ?, seal_set_hash = ?
        WHERE data_scope_id = ?::uuid AND test_only AND state = 'SEALED' AND accounting_version = 1
            AND original_reserve = ?::bigint[] AND unused_reserve = ?::bigint[]
            AND permanent_denial_bytes = ? AND permanent_denial_hash = ?
            AND final_ordinary_epoch = ?::bigint AND terminal_seal_epoch = ?::bigint AND generation_seal_count = 1
            AND generation_seal_root = ? AND seal_set_bytes = ? AND seal_set_hash = ?
    """.trimIndent()
    val verifyRunWithActiveHistory = verifyRun
        .replace("SET generation_seal_count = 2", "SET generation_seal_count = generation_seal_count + 1")
        .replace("AND generation_seal_count = 1", """AND generation_seal_count BETWEEN 2 AND 15
            AND generation_seal_count = 1 + (SELECT count(*) FROM complaint_test_active_seal_intents a WHERE a.data_scope_id = complaint_test_runs.data_scope_id)
                + (SELECT count(*) FROM complaint_test_active_recurrent_seal_intents a WHERE a.data_scope_id = complaint_test_runs.data_scope_id)""")
    val counts = """
        WITH e AS MATERIALIZED (SELECT ?::uuid AS scope)
        SELECT count(*) FILTER (WHERE i.object_kind = 'INSTALLATION_MANIFEST') AS manifests,
            coalesce(min(i.object_ordinal) FILTER (WHERE i.object_kind = 'INSTALLATION_MANIFEST'), 0) AS first_ordinal,
            coalesce(max(i.object_ordinal) FILTER (WHERE i.object_kind = 'INSTALLATION_MANIFEST'), -1) AS last_ordinal,
            count(*) FILTER (WHERE i.object_kind = 'EPOCH_SEAL' AND i.object_ordinal = 0) AS ordinary_seals,
            count(*) FILTER (WHERE i.object_kind = 'EPOCH_SEAL' AND i.object_ordinal = 1) AS terminal_seals,
            count(*) FILTER (WHERE i.object_kind = 'TEST_RUN_PURGE') AS purges,
            count(*) FILTER (WHERE i.object_kind NOT IN ('INSTALLATION_MANIFEST', 'TEST_RUN_PURGE', 'EPOCH_SEAL')
                OR (i.object_kind = 'EPOCH_SEAL' AND i.object_ordinal NOT IN (0, 1))) AS other_intents,
            (SELECT count(*) FROM complaint_journal_publications p WHERE p.data_scope_id = e.scope AND p.event_kind = 'INSTALLATION_MANIFEST') AS publications,
            (SELECT count(*) FROM complaint_recovery_capacity_reservations r JOIN complaint_journal_publications p ON p.event_id = r.publication_ref
                WHERE r.data_scope_id = e.scope AND p.event_kind = 'INSTALLATION_MANIFEST') AS reservations,
            (SELECT count(*) FROM complaint_journal_publications p WHERE p.data_scope_id = e.scope AND p.event_kind = 'TEST_RUN_PURGE') AS purge_publications,
            (SELECT count(*) FROM complaint_recovery_capacity_reservations r JOIN complaint_journal_publications p ON p.event_id = r.publication_ref
                WHERE r.data_scope_id = e.scope AND p.event_kind = 'TEST_RUN_PURGE') AS purge_reservations,
            (SELECT count(*) FROM complaint_journal_scan_runs s WHERE s.data_scope_id = e.scope) AS scan_runs,
            (SELECT count(*) FROM complaint_journal_scan_entries s WHERE s.data_scope_id = e.scope) AS scan_entries
        FROM e LEFT JOIN complaint_test_terminal_intents i ON i.data_scope_id = e.scope GROUP BY e.scope
    """.trimIndent()

    // Same full applicable relation, with a VERIFIED purge and exactly the new paid seal slot.
    // Unknown states/kinds, foreign-scope prefix rows and unrepresented primaries remain errors.
    val relation = TestRunPurgeSqlV1.relation
        .replace("p.event_kind = 'TEST_RUN_PURGE' AND p.target_count = 0", "p.event_kind = 'TEST_RUN_PURGE' AND p.target_count = 0 AND p.state = 'VERIFIED'")
        .replace("(i.object_kind = 'EPOCH_SEAL' AND i.object_ordinal = 0 AND i.state = 'WIRE_FROZEN' AND i.publication_ref IS NULL)",
            "(i.object_kind = 'EPOCH_SEAL' AND i.publication_ref IS NULL AND " +
                "((i.object_ordinal = 0 AND i.state = 'WIRE_FROZEN' AND i.epoch_start = 1 AND i.epoch_end = e.cutoff) OR " +
                "(i.object_ordinal = 1 AND i.state IN ('CANONICAL', 'WIRE_FROZEN') AND i.epoch_start = e.epoch AND i.epoch_end = e.epoch)))")
    val relationWithActiveHistory = relation.replace("i.epoch_start = 1 AND i.epoch_end = e.cutoff", "i.epoch_start = (SELECT c.seal_epoch + 1 FROM complaint_journal_control c WHERE c.data_scope_id = e.scope) AND i.epoch_end = e.cutoff")
}
