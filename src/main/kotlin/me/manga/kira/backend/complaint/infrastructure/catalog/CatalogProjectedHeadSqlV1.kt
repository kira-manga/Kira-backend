package me.manga.kira.backend.complaint.infrastructure.catalog

/** Exact already-projected full-B observation. No epoch/gate/lease or null-D compatibility shortcut. */
internal val READ_PROJECTED_HEAD_CONTROL_V1 = """
    SELECT (NOT c.test_only AND c.implementation_schema = 1 AND c.desired_generation = ?
        AND c.desired_configuration_hash = ?::bytea AND c.database_identity = ?::uuid AND c.restore_identity = ?::uuid
        AND c.event_writer_generation = ?::uuid AND c.accepted_catalog_generation = ? AND c.accepted_catalog_hash = ?::bytea
        AND c.trust_bundle_hash = ?::bytea AND c.catalog_writer_generation = ?::uuid AND c.pending_projection_token IS NULL) IS TRUE AS exact_matches,
        NOT EXISTS (SELECT 1 FROM complaint_catalog_mutations WHERE state = 'PREPARED' OR (state = 'COMPLETED' AND projected_at IS NULL)) AS pending_absent
    FROM complaint_journal_control c WHERE c.data_scope_id = '00000000-0000-0000-0000-000000000000'::uuid
""".trimIndent()

internal val LOCK_PROJECTED_HEAD_CONTROL_V1 = READ_PROJECTED_HEAD_CONTROL_V1 + "\nFOR UPDATE OF c"

private val PROJECTED_HEAD_EXPECTED_V1 = """
    WITH expected AS (SELECT ?::uuid AS token, ?::text AS operation, ?::bigint AS predecessor, ?::bytea AS predecessor_hash,
        ?::bigint AS successor, ?::uuid AS writer, ?::bytea AS approval, ?::bytea AS approval_hash,
        ?::bytea AS unsigned_bytes, ?::bytea AS unsigned_hash, ?::text AS signer_policy,
        ?::text AS signer_one_id, ?::text AS signer_one_algorithm, ?::bytea AS signer_one_signature,
        ?::text AS signer_two_id, ?::text AS signer_two_algorithm, ?::bytea AS signer_two_signature,
        ?::bytea AS envelope_bytes, ?::bytea AS envelope_hash, ?::text AS object_key, ?::timestamptz AS created_at,
        ?::text AS object_version, ?::timestamptz AS retain_until, ?::bytea AS primary_bytes, ?::bytea AS primary_hash,
        ?::bytea AS replica_bytes, ?::bytea AS replica_hash)
""".trimIndent()

/** Point lookup, never G1's all-history cardinality restriction. Only bounded scalar timestamps leave PostgreSQL. */
internal val READ_PROJECTED_HEAD_MUTATION_V1 = """
    $PROJECTED_HEAD_EXPECTED_V1
    SELECT (m.operation_type = e.operation AND m.data_scope_id IS NULL AND m.test_only IS NULL
        AND m.predecessor_generation = e.predecessor AND m.predecessor_hash = e.predecessor_hash
        AND m.catalog_writer_generation = e.writer AND m.canonicalizer = 'kcj-1'
        AND m.approval_bytes = e.approval AND m.approval_hash = e.approval_hash
        AND m.unsigned_bytes = e.unsigned_bytes AND m.unsigned_hash = e.unsigned_hash
        AND m.signer_policy = e.signer_policy AND m.signer_one_id = e.signer_one_id AND m.signer_one_algorithm = e.signer_one_algorithm
        AND m.signer_one_signature = e.signer_one_signature AND m.signer_two_id IS NOT DISTINCT FROM e.signer_two_id
        AND m.signer_two_algorithm IS NOT DISTINCT FROM e.signer_two_algorithm AND m.signer_two_signature IS NOT DISTINCT FROM e.signer_two_signature
        AND m.envelope_bytes = e.envelope_bytes AND m.envelope_hash = e.envelope_hash AND m.object_key = e.object_key
        AND m.created_at = e.created_at AND m.object_version = e.object_version AND m.retain_until = e.retain_until
        AND m.primary_evidence_bytes = e.primary_bytes AND m.primary_evidence_hash = e.primary_hash
        AND m.replica_evidence_bytes = e.replica_bytes AND m.replica_evidence_hash = e.replica_hash
        AND m.state = 'COMPLETED' AND m.completed_at IS NOT NULL AND m.projected_at IS NOT NULL
        AND m.completed_at BETWEEN TIMESTAMPTZ '1970-01-01 00:00:00+00' AND TIMESTAMPTZ '9999-12-31 23:59:59+00'
        AND m.projected_at BETWEEN TIMESTAMPTZ '1970-01-01 00:00:00+00' AND TIMESTAMPTZ '9999-12-31 23:59:59+00'
        AND complaint_finite_times(m.created_at, m.retain_until, m.completed_at, m.projected_at)) IS TRUE AS exact_matches,
        CASE WHEN isfinite(m.completed_at) THEN m.completed_at END AS completed_at,
        CASE WHEN isfinite(m.projected_at) THEN m.projected_at END AS projected_at
    FROM complaint_catalog_mutations m CROSS JOIN expected e
    WHERE m.operation_token = e.token AND m.successor_generation = e.successor LIMIT 2
""".trimIndent()

internal val LOCK_PROJECTED_HEAD_MUTATION_V1 = READ_PROJECTED_HEAD_MUTATION_V1 + "\nFOR UPDATE OF m"
