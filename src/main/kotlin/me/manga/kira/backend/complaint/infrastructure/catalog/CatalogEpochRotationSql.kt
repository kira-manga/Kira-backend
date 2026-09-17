package me.manga.kira.backend.complaint.infrastructure.catalog

/** Fixed LIVE control projections. No seal/checkpoint body, staging row, counter or arbitrary SQL input is exposed. */
internal val EPOCH_ROTATION_COLUMNS = """
    c.data_scope_id, c.test_only, c.implementation_schema, c.desired_generation, c.desired_configuration_hash,
    c.database_identity, c.restore_identity, c.event_writer_generation, c.accepted_catalog_generation,
    c.accepted_catalog_hash, c.trust_bundle_hash, c.catalog_writer_generation, c.pending_projection_token,
    c.publication_epoch, c.scan_requested, c.maintenance_closed, c.creation_closed,
    c.lease_owner, c.lease_token, c.lease_expires_at, c.updated_at,
    c.rotation_sequence, c.rotation_id, c.rotation_state, c.rotation_epoch_before,
    c.rotation_implementation_schema, c.rotation_desired_generation, c.rotation_desired_configuration_hash,
    c.rotation_database_identity, c.rotation_restore_identity, c.rotation_event_writer_generation,
    c.rotation_accepted_catalog_generation, c.rotation_accepted_catalog_hash, c.rotation_trust_bundle_hash,
    c.rotation_catalog_writer_generation, c.rotation_request_owner, c.rotation_request_token, c.rotation_requested_at,
    c.rotation_capture_owner, c.rotation_capture_token, c.rotation_captured_at, c.rotation_epoch_after,
    c.seal_state IS NULL AND c.checkpoint_generation IS NULL AS rotation_history_empty,
    complaint_finite_times(c.updated_at, c.lease_expires_at, c.rotation_requested_at, c.rotation_captured_at)
        AS rotation_finite_times
""".trimIndent()

private const val EPOCH_ROTATION_LIVE = "c.data_scope_id = '00000000-0000-0000-0000-000000000000'::uuid"

/** No clock expression runs before the blocking control-row lock has actually returned. */
internal val LOCK_EPOCH_ROTATION_CONTROL = """
    SELECT $EPOCH_ROTATION_COLUMNS, NULL::timestamptz AS rotation_sampled_at, true AS rotation_sample_finite
    FROM complaint_journal_control c WHERE $EPOCH_ROTATION_LIVE
    FOR UPDATE
""".trimIndent()

/** Only a later statement samples DB time. Every observation is checked against the actual current lease. */
internal val READ_EPOCH_ROTATION_CONTROL = """
    WITH sampled AS MATERIALIZED (SELECT clock_timestamp() AS sampled_at)
    SELECT $EPOCH_ROTATION_COLUMNS, sampled.sampled_at AS rotation_sampled_at,
        isfinite(sampled.sampled_at) AS rotation_sample_finite
    FROM complaint_journal_control c CROSS JOIN sampled WHERE $EPOCH_ROTATION_LIVE
""".trimIndent()

/** Same exact retained INITIAL_LIVE/G1 binding as the genuine campaign; D includes the new actual D3 resource. */
internal val EPOCH_ROTATION_BINDING = """
    NOT c.test_only AND c.implementation_schema = 1 AND c.desired_generation = ?
        AND c.desired_configuration_hash = ?::bytea AND c.database_identity = ?::uuid AND c.restore_identity = ?::uuid
        AND c.event_writer_generation = ?::uuid AND c.accepted_catalog_generation = 1 AND c.accepted_catalog_hash = ?::bytea
        AND c.trust_bundle_hash = ?::bytea AND c.catalog_writer_generation = ?::uuid AND c.pending_projection_token IS NULL
""".trimIndent()

internal val EPOCH_ROTATION_CURRENT_LEASE = """
    c.lease_owner = ?::uuid AND c.lease_token = ? AND c.lease_expires_at > sampled.sampled_at
        AND isfinite(c.lease_expires_at) AND isfinite(sampled.sampled_at)
""".trimIndent()

private val EPOCH_ROTATION_STORED_BINDING = """
    c.rotation_implementation_schema = c.implementation_schema AND c.rotation_desired_generation = c.desired_generation
        AND c.rotation_desired_configuration_hash = c.desired_configuration_hash
        AND c.rotation_database_identity = c.database_identity AND c.rotation_restore_identity = c.restore_identity
        AND c.rotation_event_writer_generation = c.event_writer_generation
        AND c.rotation_accepted_catalog_generation = c.accepted_catalog_generation
        AND c.rotation_accepted_catalog_hash = c.accepted_catalog_hash AND c.rotation_trust_bundle_hash = c.trust_bundle_hash
        AND c.rotation_catalog_writer_generation = c.catalog_writer_generation
""".trimIndent()

private val EPOCH_ROTATION_RETURNING = """
    RETURNING $EPOCH_ROTATION_COLUMNS, sampled.sampled_at AS rotation_sampled_at,
        isfinite(sampled.sampled_at) AS rotation_sample_finite
""".trimIndent()

/**
 * Only an empty slot may become a NEW request. The exact observed predecessor/epoch/flag/update time
 * is retained before this statement. No existing REQUESTED/CAPTURED tuple is overwritten or cleared.
 */
internal val REQUEST_EPOCH_ROTATION_CONTROL = """
    WITH sampled AS MATERIALIZED (SELECT clock_timestamp() AS sampled_at)
    UPDATE complaint_journal_control c
    SET scan_requested = true, rotation_sequence = c.rotation_sequence + 1, rotation_id = ?::uuid,
        rotation_state = 'REQUESTED', rotation_epoch_before = c.publication_epoch,
        rotation_implementation_schema = c.implementation_schema, rotation_desired_generation = c.desired_generation,
        rotation_desired_configuration_hash = c.desired_configuration_hash,
        rotation_database_identity = c.database_identity, rotation_restore_identity = c.restore_identity,
        rotation_event_writer_generation = c.event_writer_generation,
        rotation_accepted_catalog_generation = c.accepted_catalog_generation, rotation_accepted_catalog_hash = c.accepted_catalog_hash,
        rotation_trust_bundle_hash = c.trust_bundle_hash, rotation_catalog_writer_generation = c.catalog_writer_generation,
        rotation_request_owner = c.lease_owner, rotation_request_token = c.lease_token, rotation_requested_at = sampled.sampled_at,
        updated_at = sampled.sampled_at
    FROM sampled
    WHERE $EPOCH_ROTATION_LIVE AND ($EPOCH_ROTATION_BINDING) AND ($EPOCH_ROTATION_CURRENT_LEASE)
        AND c.rotation_sequence = ? AND c.rotation_id IS NOT DISTINCT FROM ?::uuid
        AND c.rotation_state IS NOT DISTINCT FROM ?::text AND c.publication_epoch = ?
        AND c.scan_requested = ? AND c.updated_at = ?::timestamptz
        AND c.rotation_sequence = 0 AND c.rotation_id IS NULL AND c.rotation_state IS NULL
        AND c.publication_epoch BETWEEN 1 AND 9223372036854775806
        AND c.seal_state IS NULL AND c.checkpoint_generation IS NULL
    $EPOCH_ROTATION_RETURNING
""".trimIndent()

/**
 * Called only by the new owned non-pooled session after its exclusive fence and control lock.
 * Current authority and the exact immutable request are rechecked in this one atomic advance.
 * CAPTURED has no UPDATE branch: its stored cutoff is reread, never reconstructed or incremented.
 */
internal val CAPTURE_EPOCH_ROTATION_CONTROL = """
    WITH sampled AS MATERIALIZED (SELECT clock_timestamp() AS sampled_at)
    UPDATE complaint_journal_control c
    SET publication_epoch = CASE WHEN c.rotation_epoch_before BETWEEN 1 AND 9223372036854775806
            THEN c.rotation_epoch_before + 1 ELSE NULL END,
        rotation_state = 'CAPTURED', rotation_capture_owner = c.lease_owner, rotation_capture_token = c.lease_token,
        rotation_captured_at = sampled.sampled_at,
        rotation_epoch_after = CASE WHEN c.rotation_epoch_before BETWEEN 1 AND 9223372036854775806
            THEN c.rotation_epoch_before + 1 ELSE NULL END,
        scan_requested = false, updated_at = sampled.sampled_at
    FROM sampled
    WHERE $EPOCH_ROTATION_LIVE AND ($EPOCH_ROTATION_BINDING) AND ($EPOCH_ROTATION_CURRENT_LEASE)
        AND ($EPOCH_ROTATION_STORED_BINDING) AND c.rotation_state = 'REQUESTED' AND c.scan_requested
        AND c.rotation_id = ?::uuid AND c.rotation_sequence = ? AND c.rotation_epoch_before = ?
        AND c.rotation_request_owner = ?::uuid AND c.rotation_request_token = ? AND c.rotation_requested_at = ?::timestamptz
        AND c.publication_epoch = c.rotation_epoch_before AND c.updated_at = ?::timestamptz
        AND c.rotation_capture_owner IS NULL AND c.rotation_capture_token IS NULL
        AND c.rotation_captured_at IS NULL AND c.rotation_epoch_after IS NULL
        AND c.rotation_epoch_before BETWEEN 1 AND 9223372036854775806
    $EPOCH_ROTATION_RETURNING
""".trimIndent()
