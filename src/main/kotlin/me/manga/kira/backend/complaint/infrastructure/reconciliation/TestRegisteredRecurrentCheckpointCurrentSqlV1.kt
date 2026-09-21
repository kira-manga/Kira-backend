package me.manga.kira.backend.complaint.infrastructure.reconciliation

/** Fixed passive statements under the ordinary owner's existing global/scoped control locks. */
internal object TestRegisteredRecurrentCheckpointCurrentSqlV1 {
    /** Selection only, never eligibility. A recurrent failure cannot select the initial reader. */
    val branch = """
        SELECT c.rotation_sequence,
            (c.rotation_sequence <> 1 OR (
                NOT EXISTS (SELECT 1 FROM complaint_test_active_recurrent_seal_intents i WHERE i.data_scope_id = c.data_scope_id)
                AND NOT EXISTS (SELECT 1 FROM complaint_test_active_checkpoint_history h WHERE h.data_scope_id = c.data_scope_id))) IS TRUE AS valid
        FROM complaint_journal_control c WHERE c.data_scope_id = ?::uuid AND c.test_only LIMIT 2
    """.trimIndent()

    /** The producer's full registered identity/shape plus the strictly COMPLETED consumer state. */
    val current = """
        WITH observed AS MATERIALIZED (
        ${TestActiveRecurrentSqlV1.read}
        )
        SELECT o.*,
            (o.valid AND c.test_only AND NOT c.creation_closed AND NOT g.creation_closed AND NOT g.scan_requested
                AND c.rotation_sequence BETWEEN 2 AND 14 AND c.rotation_state = 'CAPTURED' AND NOT c.scan_requested
                AND c.lease_owner IS NULL AND c.lease_expires_at IS NULL
                AND c.seal_state = 'SEAL_VERIFIED' AND c.publication_epoch = c.seal_epoch + 1
                AND c.checkpoint_result = 'SUCCESS' AND c.checkpoint_fencing_token = c.lease_token
                AND c.checkpoint_cutoff_epoch = c.seal_epoch AND c.checkpoint_started_at >= c.seal_verified_at
                AND c.checkpoint_completed_at >= c.checkpoint_started_at AND c.checkpoint_completed_at <= c.updated_at
                AND c.checkpoint_completed_at <= o.sampled_at
                AND EXISTS (SELECT 1 FROM complaint_test_active_checkpoint_history h
                    WHERE h.data_scope_id = c.data_scope_id AND h.test_only AND h.ordinal = c.rotation_sequence
                        AND h.operation_token = c.rotation_id AND h.source = 'V31_RECURRENT'
                        AND h.initial_seal_token IS NULL AND h.recurrent_seal_token = c.rotation_id
                        AND h.checkpoint_bytes = c.checkpoint_bytes AND h.checkpoint_hash = c.checkpoint_hash
                        AND h.checkpointed_at = c.checkpoint_completed_at)
                AND NOT EXISTS (SELECT 1 FROM complaint_journal_scan_runs s WHERE s.data_scope_id = c.data_scope_id)
                AND NOT EXISTS (SELECT 1 FROM complaint_journal_scan_entries s WHERE s.data_scope_id = c.data_scope_id)
            ) IS TRUE AS ordinary_valid
        FROM observed o
        LEFT JOIN complaint_journal_control c ON c.data_scope_id = ?::uuid
        LEFT JOIN complaint_journal_control g ON g.data_scope_id = '00000000-0000-0000-0000-000000000000'::uuid
        LIMIT 2
    """.trimIndent()

    // Exact bounded source projections, without the producer's later intent/history locks.
    // Ordinary rechecks already own global→scope, possibly counters/run/actor; never lock backward.
    val initialHeaders = TestActiveRecurrentSqlV1.initialHeaders.removeSuffix(" FOR UPDATE")
    val recurrentHeaders = TestActiveRecurrentSqlV1.recurrentHeaders.removeSuffix(" FOR UPDATE")
    val initialPayload = TestActiveRecurrentSqlV1.initialPayload
    val recurrentPayload = TestActiveRecurrentSqlV1.recurrentPayload
    val history = TestActiveRecurrentSqlV1.history.removeSuffix(" FOR UPDATE")
}
