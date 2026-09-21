package me.manga.kira.backend.complaint.infrastructure.admission

import me.manga.kira.backend.complaint.infrastructure.ComplaintInstallationTestRunRows

/** Already-open ACTIVE comparisons only. No gate, lease, run, counter, rotation or checkpoint writer. */
internal object TestNamespaceActiveRegistrationSqlV1 {
    private val controls = """
        WITH e AS MATERIALIZED (SELECT ?::uuid AS scope, ?::bigint AS desired_generation, ?::integer AS implementation_schema,
            ?::bytea AS configuration_hash, ?::uuid AS database_identity, ?::uuid AS restore_identity,
            ?::uuid AS event_writer, ?::uuid AS catalog_writer, ?::bytea AS trust_hash, ?::bigint AS maximum_generation)
        SELECT (NOT c.maintenance_closed AND NOT c.creation_closed AND c.pending_projection_token IS NULL
            AND c.publication_epoch > 0 AND c.database_identity = e.database_identity AND c.restore_identity = e.restore_identity
            AND c.event_writer_generation = e.event_writer AND c.catalog_writer_generation = e.catalog_writer AND c.trust_bundle_hash = e.trust_hash
            AND c.accepted_catalog_generation BETWEEN 2 AND e.maximum_generation AND complaint_digest_valid(c.accepted_catalog_hash)
            AND c.lease_token >= 0 AND ((c.lease_owner IS NULL AND c.lease_expires_at IS NULL)
                OR (complaint_is_v4(c.lease_owner) AND isfinite(c.lease_expires_at)))
            AND isfinite(c.updated_at) AND octet_length(to_jsonb(c)::text) BETWEEN 1 AND 524288
            AND NOT EXISTS (SELECT 1 FROM complaint_catalog_mutations WHERE state = 'PREPARED' OR (state = 'COMPLETED' AND projected_at IS NULL))
            AND ((c.data_scope_id = e.scope AND c.test_only AND c.implementation_schema = e.implementation_schema
                    AND c.desired_generation = e.desired_generation AND c.desired_configuration_hash = e.configuration_hash)
                OR (c.data_scope_id = '00000000-0000-0000-0000-000000000000'::uuid AND NOT c.test_only
                    AND c.implementation_schema = 1 AND c.desired_generation > 0))) IS TRUE AS valid,
            c.data_scope_id, c.desired_generation, c.desired_configuration_hash, c.accepted_catalog_generation, c.accepted_catalog_hash,
            CASE WHEN octet_length(to_jsonb(c)::text) BETWEEN 1 AND 524288
                THEN sha256(convert_to((to_jsonb(c) || jsonb_build_object('row_xmin', c.xmin::text))::text, 'UTF8')) END AS fingerprint
        FROM complaint_journal_control c CROSS JOIN e WHERE c.data_scope_id = ?::uuid
    """.trimIndent()
    val lockControl = "$controls FOR UPDATE OF c"

    // Neutral completed schema3 tail/history machinery is shared; no closed-recovery run/control predicate is reused.
    val tail = TestNamespaceRecoveryRegistrationSqlV1.tail
    val lockRunIdentity = TestNamespaceRecoveryRegistrationSqlV1.lockRunIdentity
    val run = """
        WITH expected AS MATERIALIZED (SELECT ?::uuid AS scope, ?::bytea AS configuration_hash, ?::bigint AS installation_limit,
            ?::bigint AS generation, ?::bytea AS activation_hash, ?::timestamptz AS created_at, ?::bigint[] AS original_reserve)
        SELECT (r.test_only AND r.state = 'ACTIVE' AND (${ComplaintInstallationTestRunRows.activeShape})
            AND r.configuration_hash = e.configuration_hash AND r.accounting_version = 1
            AND r.installation_limit = e.installation_limit AND r.installation_limit > 0 AND r.enrolled_count BETWEEN 0 AND r.installation_limit
            AND r.activation_catalog_generation = e.generation AND r.activation_catalog_hash = e.activation_hash
            AND r.created_at = e.created_at AND isfinite(r.created_at) AND r.created_at <= clock_timestamp()
            AND r.original_reserve = e.original_reserve AND complaint_vector_valid(r.original_reserve)
            AND complaint_vector_lte(r.unused_reserve, r.original_reserve)
            AND octet_length(to_jsonb(r)::text) BETWEEN 1 AND 262144) IS TRUE AS valid,
            r.installation_limit, r.enrolled_count, r.original_reserve, r.unused_reserve,
            CASE WHEN octet_length(to_jsonb(r)::text) BETWEEN 1 AND 262144
                THEN sha256(convert_to((to_jsonb(r) || jsonb_build_object('row_xmin', r.xmin::text))::text, 'UTF8')) END AS fingerprint
        FROM complaint_test_runs r CROSS JOIN expected e WHERE r.data_scope_id = e.scope
    """.trimIndent()

    // Scoped indexed counts after the ACTIVE run lock. No secrets/content, broad row materialization or row-count proof of provider history.
    val installationCounts = """
        SELECT (SELECT count(*) FROM complaint_installation_ids WHERE data_scope_id = ?::uuid) AS ids,
            (SELECT count(*) FROM app_installations WHERE data_scope_id = ?::uuid) AS credentials
    """.trimIndent()
}
