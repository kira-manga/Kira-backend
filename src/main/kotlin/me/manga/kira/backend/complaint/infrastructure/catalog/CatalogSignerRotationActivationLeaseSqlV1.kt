package me.manga.kira.backend.complaint.infrastructure.catalog

/**
 * Fresh acquisition for the named fixed activation3 pending-delivery owner only.
 * Group10 = exact B3's nine fields then operation3 token, detached by that original owner.
 * No ordinary/overlap2 SQL changes, generic pending allowance, renewal or old campaign revival.
 * Only lease metadata changes; concrete owner/phase, raw proof, full history and release checks remain mandatory.
 */
internal object CatalogSignerRotationActivationLeaseSqlV1 {
    private val binding = """
        NOT c.test_only AND c.implementation_schema = 1 AND c.desired_generation = ?
            AND c.desired_configuration_hash = ?::bytea AND c.database_identity = ?::uuid AND c.restore_identity = ?::uuid
            AND c.event_writer_generation = ?::uuid AND c.accepted_catalog_generation = ? AND c.accepted_catalog_hash = ?::bytea
            AND c.trust_bundle_hash = ?::bytea AND c.catalog_writer_generation = ?::uuid
            AND c.accepted_catalog_generation = 3 AND c.pending_projection_token = ?::uuid
            AND c.maintenance_closed AND c.creation_closed
    """.trimIndent()

    private val select = """
        SELECT COALESCE(($binding), false) AS binding_matches,
            c.lease_owner, c.lease_token, c.lease_expires_at, c.updated_at,
            isfinite(c.updated_at) AND (c.lease_expires_at IS NULL OR isfinite(c.lease_expires_at)) AS finite_times
        FROM complaint_journal_control c
        WHERE c.data_scope_id = '00000000-0000-0000-0000-000000000000'::uuid
    """.trimIndent()

    /** Group10 / 10 arguments. The clock is sampled only after the control row lock returns. */
    internal val LOCK_SIGNER_ROTATION_ACTIVATION_PENDING_LEASE_CONTROL = select + "\nFOR UPDATE"

    /** Group10 / 10 arguments. The store separately verifies the actual CAS-returned lease tuple. */
    internal val READ_SIGNER_ROTATION_ACTIVATION_PENDING_LEASE_CONTROL = select

    private val preimage = """
        c.lease_owner IS NOT DISTINCT FROM ?::uuid AND c.lease_token = ?
            AND c.lease_expires_at IS NOT DISTINCT FROM ?::timestamptz AND c.updated_at = ?::timestamptz
    """.trimIndent()

    /** New owner + group10 + locked old owner/token/expiry/updated_at / 15 arguments. */
    internal val ACQUIRE_SIGNER_ROTATION_ACTIVATION_PENDING_LEASE = """
        WITH sampled AS MATERIALIZED (SELECT clock_timestamp() AS sampled_at)
        UPDATE complaint_journal_control c
        SET lease_owner = ?::uuid, lease_token = c.lease_token + 1,
            lease_expires_at = sampled.sampled_at + interval '30 seconds', updated_at = sampled.sampled_at
        FROM sampled
        WHERE c.data_scope_id = '00000000-0000-0000-0000-000000000000'::uuid
            AND ($binding) AND ($preimage)
            AND c.lease_token < 9223372036854775807
            AND ((c.lease_owner IS NULL AND c.lease_expires_at IS NULL) OR c.lease_expires_at <= sampled.sampled_at)
            AND isfinite(sampled.sampled_at) AND isfinite(sampled.sampled_at + interval '30 seconds')
        RETURNING c.lease_owner, c.lease_token, c.lease_expires_at, c.updated_at, sampled.sampled_at,
            isfinite(c.updated_at) AND isfinite(sampled.sampled_at)
                AND (c.lease_expires_at IS NULL OR isfinite(c.lease_expires_at)) AS finite_times
    """.trimIndent()
}
