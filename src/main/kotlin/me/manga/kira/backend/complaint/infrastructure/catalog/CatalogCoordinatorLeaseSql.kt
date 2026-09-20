package me.manga.kira.backend.complaint.infrastructure.catalog

/**
 * Exact INITIAL_LIVE binding from the G1-only producer. Epoch, gates, scan flags,
 * checkpoint/seal and the different retention lease are not lease-cookie fields.
 */
private val COORDINATOR_LEASE_BINDING = """
    NOT c.test_only AND c.implementation_schema = 1 AND c.desired_generation = ?
        AND c.desired_configuration_hash = ?::bytea AND c.database_identity = ?::uuid AND c.restore_identity = ?::uuid
        AND c.event_writer_generation = ?::uuid AND c.accepted_catalog_generation = ? AND c.accepted_catalog_hash = ?::bytea
        AND c.trust_bundle_hash = ?::bytea AND c.catalog_writer_generation = ?::uuid AND c.pending_projection_token IS NULL
""".trimIndent()

private val COORDINATOR_LEASE_SELECT = """
    SELECT COALESCE(($COORDINATOR_LEASE_BINDING), false) AS binding_matches,
        c.lease_owner, c.lease_token, c.lease_expires_at, c.updated_at,
        isfinite(c.updated_at) AND (c.lease_expires_at IS NULL OR isfinite(c.lease_expires_at)) AS finite_times
    FROM complaint_journal_control c
    WHERE c.data_scope_id = '00000000-0000-0000-0000-000000000000'::uuid
""".trimIndent()

internal val LOCK_COORDINATOR_LEASE_CONTROL = COORDINATOR_LEASE_SELECT + "\nFOR UPDATE"
internal val READ_COORDINATOR_LEASE_CONTROL = COORDINATOR_LEASE_SELECT

private val COORDINATOR_LEASE_PREIMAGE = """
    c.lease_owner IS NOT DISTINCT FROM ?::uuid AND c.lease_token = ?
        AND c.lease_expires_at IS NOT DISTINCT FROM ?::timestamptz AND c.updated_at = ?::timestamptz
""".trimIndent()

private val COORDINATOR_LEASE_RETURNING = """
    RETURNING c.lease_owner, c.lease_token, c.lease_expires_at, c.updated_at, sampled.sampled_at,
        isfinite(c.updated_at) AND isfinite(sampled.sampled_at)
            AND (c.lease_expires_at IS NULL OR isfinite(c.lease_expires_at)) AS finite_times
""".trimIndent()

/** The earlier locking SELECT has returned. One materialized server-time value drives predicate, expiry and updated_at. */
internal val ACQUIRE_COORDINATOR_LEASE = """
    WITH sampled AS MATERIALIZED (SELECT clock_timestamp() AS sampled_at)
    UPDATE complaint_journal_control c
    SET lease_owner = ?::uuid, lease_token = c.lease_token + 1,
        lease_expires_at = sampled.sampled_at + interval '30 seconds', updated_at = sampled.sampled_at
    FROM sampled
    WHERE c.data_scope_id = '00000000-0000-0000-0000-000000000000'::uuid
        AND ($COORDINATOR_LEASE_BINDING) AND ($COORDINATOR_LEASE_PREIMAGE)
        AND c.lease_token < 9223372036854775807
        AND ((c.lease_owner IS NULL AND c.lease_expires_at IS NULL) OR c.lease_expires_at <= sampled.sampled_at)
        AND isfinite(sampled.sampled_at) AND isfinite(sampled.sampled_at + interval '30 seconds')
    $COORDINATOR_LEASE_RETURNING
""".trimIndent()

internal val RENEW_COORDINATOR_LEASE = """
    WITH sampled AS MATERIALIZED (SELECT clock_timestamp() AS sampled_at)
    UPDATE complaint_journal_control c
    SET lease_expires_at = sampled.sampled_at + interval '30 seconds', updated_at = sampled.sampled_at
    FROM sampled
    WHERE c.data_scope_id = '00000000-0000-0000-0000-000000000000'::uuid
        AND ($COORDINATOR_LEASE_BINDING) AND ($COORDINATOR_LEASE_PREIMAGE)
        AND c.lease_owner = ?::uuid AND c.lease_token = ? AND c.lease_expires_at > sampled.sampled_at
        AND isfinite(sampled.sampled_at) AND isfinite(sampled.sampled_at + interval '30 seconds')
    $COORDINATOR_LEASE_RETURNING
""".trimIndent()

/** Authority reduction may clear this campaign's expired row, but never a successor or a different B. */
internal val RELINQUISH_COORDINATOR_LEASE = """
    WITH sampled AS MATERIALIZED (SELECT clock_timestamp() AS sampled_at)
    UPDATE complaint_journal_control c
    SET lease_owner = NULL, lease_expires_at = NULL, updated_at = sampled.sampled_at
    FROM sampled
    WHERE c.data_scope_id = '00000000-0000-0000-0000-000000000000'::uuid
        AND ($COORDINATOR_LEASE_BINDING) AND ($COORDINATOR_LEASE_PREIMAGE)
        AND c.lease_owner = ?::uuid AND c.lease_token = ? AND isfinite(sampled.sampled_at)
    $COORDINATOR_LEASE_RETURNING
""".trimIndent()
