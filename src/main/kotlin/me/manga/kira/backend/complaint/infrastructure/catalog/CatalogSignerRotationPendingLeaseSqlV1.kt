package me.manga.kira.backend.complaint.infrastructure.catalog

/**
 * Fresh acquisition only for the named cold overlap2 pending-delivery owner. Ordinary null-pending SQL is unchanged.
 * Group10 = exact B2's nine fields then operation2 token; the retained owner detaches them before entry.
 * These statements change lease metadata only. Actual owner/phase, history, raw proof and released-result checks remain mandatory.
 */
internal object CatalogSignerRotationPendingLeaseSqlV1 {
    private val binding = """
        NOT c.test_only AND c.implementation_schema = 1 AND c.desired_generation = ?
            AND c.desired_configuration_hash = ?::bytea AND c.database_identity = ?::uuid AND c.restore_identity = ?::uuid
            AND c.event_writer_generation = ?::uuid AND c.accepted_catalog_generation = ? AND c.accepted_catalog_hash = ?::bytea
            AND c.trust_bundle_hash = ?::bytea AND c.catalog_writer_generation = ?::uuid
            AND c.accepted_catalog_generation = 2 AND c.pending_projection_token = ?::uuid
            AND c.maintenance_closed AND c.creation_closed
    """.trimIndent()

    private val select = """
        SELECT COALESCE(($binding), false) AS binding_matches,
            c.lease_owner, c.lease_token, c.lease_expires_at, c.updated_at,
            isfinite(c.updated_at) AND (c.lease_expires_at IS NULL OR isfinite(c.lease_expires_at)) AS finite_times
        FROM complaint_journal_control c
        WHERE c.data_scope_id = '00000000-0000-0000-0000-000000000000'::uuid
    """.trimIndent()

    /** Group10 / 10 arguments. No clock is sampled while acquiring the control row lock. */
    internal val LOCK_SIGNER_ROTATION_PENDING_LEASE_CONTROL = select + "\nFOR UPDATE"

    /** Group10 / 10 arguments. The store also requires the exact lease tuple returned by the CAS. */
    internal val READ_SIGNER_ROTATION_PENDING_LEASE_CONTROL = select

    private val preimage = """
        c.lease_owner IS NOT DISTINCT FROM ?::uuid AND c.lease_token = ?
            AND c.lease_expires_at IS NOT DISTINCT FROM ?::timestamptz AND c.updated_at = ?::timestamptz
    """.trimIndent()

    /** New owner + group10 + locked old owner/token/expiry/updated_at / 15 arguments. Never renews or revives the old campaign. */
    internal val ACQUIRE_SIGNER_ROTATION_PENDING_LEASE = """
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
