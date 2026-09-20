package me.manga.kira.backend.complaint.infrastructure.catalog

/** Fixed LIVE control-only canonical storage. No publication, receipt, counter, domain, fence, wire or provider operation. */
internal object CatalogCutoffControlSqlV1 {
    private const val LIVE = "c.data_scope_id = '00000000-0000-0000-0000-000000000000'::uuid"
    private val UNVERIFIED = """
        c.seal_object_version IS NULL AND c.seal_ciphertext_hash IS NULL AND c.seal_retain_until IS NULL
        AND c.seal_verified_at IS NULL AND c.seal_verification_bytes IS NULL AND c.seal_verification_hash IS NULL
    """.trimIndent()
    private val SEAL_EMPTY = """
        c.seal_state IS NULL AND c.seal_epoch IS NULL AND c.seal_writer_generation IS NULL AND c.seal_operation_token IS NULL
        AND c.seal_object_key IS NULL AND c.seal_bytes IS NULL AND c.seal_hash IS NULL AND ($UNVERIFIED)
        AND c.seal_format IS NULL AND c.seal_rotation_id IS NULL AND c.seal_rotation_sequence IS NULL
        AND c.seal_preparing_fencing_token IS NULL AND c.seal_routing_key_id IS NULL
        AND c.seal_epoch_start IS NULL AND c.seal_preceding_hash IS NULL
    """.trimIndent()
    private val CHECKPOINT_EMPTY = """
        c.checkpoint_generation IS NULL AND c.checkpoint_fencing_token IS NULL AND c.checkpoint_catalog_generation IS NULL
        AND c.checkpoint_catalog_hash IS NULL AND c.checkpoint_writer_generation IS NULL AND c.checkpoint_cutoff_epoch IS NULL
        AND c.checkpoint_configuration_hash IS NULL AND c.checkpoint_database_identity IS NULL AND c.checkpoint_restore_identity IS NULL
        AND c.checkpoint_schema IS NULL AND c.checkpoint_started_at IS NULL AND c.checkpoint_completed_at IS NULL
        AND c.checkpoint_object_count IS NULL AND c.checkpoint_byte_count IS NULL AND c.checkpoint_result IS NULL
        AND c.checkpoint_bytes IS NULL AND c.checkpoint_hash IS NULL
    """.trimIndent()
    private val PREPARED_VALID = """
        c.seal_format = 1 AND c.seal_state = 'SEAL_PREPARED' AND NOT c.test_only AND ($LIVE)
        AND c.rotation_state = 'CAPTURED' AND c.rotation_sequence = 1 AND c.rotation_accepted_catalog_generation = 1
        AND complaint_is_v4(c.seal_rotation_id) AND c.seal_rotation_id = c.rotation_id
        AND c.seal_rotation_sequence = c.rotation_sequence AND c.seal_writer_generation = c.rotation_event_writer_generation
        AND c.seal_epoch = c.rotation_epoch_before AND c.seal_epoch > 0 AND complaint_is_v4(c.seal_writer_generation)
        AND complaint_is_v4(c.seal_operation_token) AND complaint_ascii_valid(c.seal_object_key, 1024)
        AND complaint_bytes_match(c.seal_bytes, c.seal_hash, 65536) AND c.seal_preparing_fencing_token > 0
        AND complaint_ascii_valid(c.seal_routing_key_id, 128) AND c.seal_epoch_start = 1
        AND c.seal_preceding_hash IS NOT NULL AND octet_length(c.seal_preceding_hash) = 0 AND ($UNVERIFIED)
    """.trimIndent()
    private val COLUMNS = """
        $EPOCH_ROTATION_COLUMNS,
        c.retention_lease_owner, c.retention_lease_token, c.retention_lease_expires_at,
        complaint_finite_times(c.retention_lease_expires_at) AS cutoff_finite_times,
        ($CHECKPOINT_EMPTY) AS cutoff_checkpoint_empty,
        (($SEAL_EMPTY) OR ($PREPARED_VALID)) IS TRUE AS cutoff_seal_valid,
        c.seal_state, c.seal_format, c.seal_rotation_id, c.seal_rotation_sequence, c.seal_preparing_fencing_token,
        c.seal_epoch_start, c.seal_epoch, c.seal_writer_generation, c.seal_operation_token,
        CASE WHEN complaint_ascii_valid(c.seal_routing_key_id, 128) THEN c.seal_routing_key_id END AS seal_routing_key_id,
        CASE WHEN complaint_ascii_valid(c.seal_object_key, 1024) THEN c.seal_object_key END AS seal_object_key,
        CASE WHEN octet_length(c.seal_preceding_hash) = 0 THEN c.seal_preceding_hash END AS seal_preceding_hash,
        CASE WHEN complaint_bytes_match(c.seal_bytes, c.seal_hash, 65536) THEN c.seal_bytes END AS seal_bytes,
        CASE WHEN complaint_digest_valid(c.seal_hash) THEN c.seal_hash END AS seal_hash
    """.trimIndent()

    /** No database clock is sampled by the statement that waits for the control row. */
    val LOCK = """
        SELECT $COLUMNS, NULL::timestamptz AS rotation_sampled_at, true AS rotation_sample_finite
        FROM complaint_journal_control c WHERE $LIVE FOR UPDATE
    """.trimIndent()

    /** Distinct fixed statement identifies the actual canonical phase without a supplied SQL hook or authority flag. */
    val PREPARE_LOCK = "/* complaint canonical seal prepare: lock before clock */\n$LOCK"

    val READ = """
        WITH sampled AS MATERIALIZED (SELECT clock_timestamp() AS sampled_at)
        SELECT $COLUMNS, sampled.sampled_at AS rotation_sampled_at, isfinite(sampled.sampled_at) AS rotation_sample_finite
        FROM complaint_journal_control c CROSS JOIN sampled WHERE $LIVE
    """.trimIndent()

    /** Every non-seal field equals the locked preimage; every prior seal/checkpoint field is NULL. No old intent is updated/adopted. */
    val PREPARE = """
        WITH sampled AS MATERIALIZED (SELECT clock_timestamp() AS sampled_at)
        UPDATE complaint_journal_control c
        SET seal_state = 'SEAL_PREPARED', seal_epoch = ?, seal_writer_generation = ?::uuid, seal_operation_token = ?::uuid,
            seal_object_key = ?, seal_bytes = ?::bytea, seal_hash = ?::bytea, seal_format = 1, seal_rotation_id = ?::uuid,
            seal_rotation_sequence = ?, seal_preparing_fencing_token = ?, seal_routing_key_id = ?, seal_epoch_start = ?,
            seal_preceding_hash = ?::bytea, updated_at = sampled.sampled_at
        FROM sampled
        WHERE $LIVE AND ($EPOCH_ROTATION_BINDING) AND ($EPOCH_ROTATION_CURRENT_LEASE)
            AND (${CatalogCutoffControlPreimageV1.PREDICATE}) AND ($SEAL_EMPTY) AND ($CHECKPOINT_EMPTY)
            AND c.rotation_state = 'CAPTURED' AND c.rotation_sequence = 1 AND c.rotation_accepted_catalog_generation = 1
            AND c.publication_epoch = c.rotation_epoch_after
        RETURNING $COLUMNS, sampled.sampled_at AS rotation_sampled_at, isfinite(sampled.sampled_at) AS rotation_sample_finite
    """.trimIndent()
}
