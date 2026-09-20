package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackProtocol
import me.manga.kira.backend.complaint.domain.catalog.CatalogSignerRotationActivationCapacityV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleProtocol

/**
 * Fixed immediate schema1 activation3 only. The original owner/phase supplies all authority.
 * B12 = full B9, actual lease owner/token/exact expiry; pending adds the exact operation3 token.
 * O17 = frozen signed overlap2: token/writer, approval/hash, unsigned/hash, old id/algorithm,
 *       new id/algorithm, key2/created/predecessor1 hash, signature1/signature2/envelope2/hash.
 * A11 = token/writer, approval/hash, unsigned/hash, new id/algorithm, key3/created/predecessor2 hash.
 * S3 = signature/envelope/hash, either all NULL or all present. A14 = A11 + S3.
 * C6 = version/retention, primary evidence/hash, replica evidence/hash.
 *
 * Every history SELECT is unfiltered LIMIT4 with all 33 bounded V14 columns. HEAD requires
 * exactly G1+projected2; all later states require exactly G1+projected2+activation3.
 * The store captures both actual full33 predecessors, compares every later expected preimage
 * and every same-transaction reread, and never reconstructs either predecessor's copy evidence.
 */
private val SIGNER_ROTATION_ACTIVATION_CONTROL_EXPECTED = """
    WITH expected AS (SELECT ?::bigint AS desired_generation, ?::bytea AS desired_hash,
        ?::uuid AS database_identity, ?::uuid AS restore_identity, ?::uuid AS event_writer,
        ?::bigint AS catalog_generation, ?::bytea AS catalog_hash, ?::bytea AS trust_hash, ?::uuid AS catalog_writer,
        ?::uuid AS lease_owner, ?::bigint AS lease_token, ?::timestamptz AS lease_expires_at)
""".trimIndent()

private val SIGNER_ROTATION_ACTIVATION_PENDING_EXPECTED = """
    $SIGNER_ROTATION_ACTIVATION_CONTROL_EXPECTED,
    pending AS (SELECT ?::uuid AS token)
""".trimIndent()

private val SIGNER_ROTATION_ACTIVATION_BINDING = """
    NOT c.test_only AND c.implementation_schema = 1 AND c.desired_generation = e.desired_generation
        AND c.desired_configuration_hash = e.desired_hash AND c.database_identity = e.database_identity
        AND c.restore_identity = e.restore_identity AND c.event_writer_generation = e.event_writer
        AND c.accepted_catalog_generation = e.catalog_generation AND c.accepted_catalog_hash = e.catalog_hash
        AND c.trust_bundle_hash = e.trust_hash AND c.catalog_writer_generation = e.catalog_writer
        AND c.maintenance_closed AND c.creation_closed
        AND c.lease_owner = e.lease_owner AND c.lease_token = e.lease_token AND c.lease_token > 0
        AND c.lease_expires_at = e.lease_expires_at AND isfinite(c.lease_expires_at) AND isfinite(c.updated_at)
""".trimIndent()

private val SIGNER_ROTATION_ACTIVATION_HEAD_BINDING = """
    $SIGNER_ROTATION_ACTIVATION_BINDING AND c.accepted_catalog_generation = 2 AND c.pending_projection_token IS NULL
""".trimIndent()

private val SIGNER_ROTATION_ACTIVATION_PENDING_BINDING = """
    $SIGNER_ROTATION_ACTIVATION_BINDING AND c.accepted_catalog_generation = 3 AND c.pending_projection_token = p.token
""".trimIndent()

private val SIGNER_ROTATION_ACTIVATION_PROJECTED_BINDING = """
    $SIGNER_ROTATION_ACTIVATION_BINDING AND c.accepted_catalog_generation = 3 AND c.pending_projection_token IS NULL
""".trimIndent()

private const val SIGNER_ROTATION_ACTIVATION_SCOPE = "c.data_scope_id = '00000000-0000-0000-0000-000000000000'::uuid"
private const val SIGNER_ROTATION_ACTIVATION_CURRENT_LEASE = "isfinite(sampled.sampled_at) AND c.lease_expires_at > sampled.sampled_at"

/** Exact B2/B12. No pre-lock clock sample; the separate lease statement must run after this returns. */
internal val READ_SIGNER_ROTATION_ACTIVATION_HEAD_CONTROL = """
    $SIGNER_ROTATION_ACTIVATION_CONTROL_EXPECTED
    SELECT ($SIGNER_ROTATION_ACTIVATION_HEAD_BINDING) IS TRUE AS binding_matches
    FROM complaint_journal_control c CROSS JOIN expected e WHERE $SIGNER_ROTATION_ACTIVATION_SCOPE
""".trimIndent()

internal val LOCK_SIGNER_ROTATION_ACTIVATION_HEAD_CONTROL = READ_SIGNER_ROTATION_ACTIVATION_HEAD_CONTROL + "\nFOR UPDATE OF c"

internal val READ_SIGNER_ROTATION_ACTIVATION_HEAD_LEASE = """
    $SIGNER_ROTATION_ACTIVATION_CONTROL_EXPECTED,
    sampled AS MATERIALIZED (SELECT clock_timestamp() AS sampled_at)
    SELECT ($SIGNER_ROTATION_ACTIVATION_HEAD_BINDING AND $SIGNER_ROTATION_ACTIVATION_CURRENT_LEASE) IS TRUE AS binding_matches
    FROM complaint_journal_control c CROSS JOIN expected e CROSS JOIN sampled WHERE $SIGNER_ROTATION_ACTIVATION_SCOPE
""".trimIndent()

/** Exact B3/B12 + operation3. Not a generic pending allowance or a relabelled head2 preimage. */
internal val READ_SIGNER_ROTATION_ACTIVATION_PENDING_CONTROL = """
    $SIGNER_ROTATION_ACTIVATION_PENDING_EXPECTED
    SELECT ($SIGNER_ROTATION_ACTIVATION_PENDING_BINDING) IS TRUE AS binding_matches
    FROM complaint_journal_control c CROSS JOIN expected e CROSS JOIN pending p WHERE $SIGNER_ROTATION_ACTIVATION_SCOPE
""".trimIndent()

internal val LOCK_SIGNER_ROTATION_ACTIVATION_PENDING_CONTROL = READ_SIGNER_ROTATION_ACTIVATION_PENDING_CONTROL + "\nFOR UPDATE OF c"

internal val READ_SIGNER_ROTATION_ACTIVATION_PENDING_LEASE = """
    $SIGNER_ROTATION_ACTIVATION_PENDING_EXPECTED,
    sampled AS MATERIALIZED (SELECT clock_timestamp() AS sampled_at)
    SELECT ($SIGNER_ROTATION_ACTIVATION_PENDING_BINDING AND $SIGNER_ROTATION_ACTIVATION_CURRENT_LEASE) IS TRUE AS binding_matches
    FROM complaint_journal_control c CROSS JOIN expected e CROSS JOIN pending p CROSS JOIN sampled WHERE $SIGNER_ROTATION_ACTIVATION_SCOPE
""".trimIndent()

/** Exact projected B3/B12, including the same actual lease tuple. */
internal val READ_SIGNER_ROTATION_ACTIVATION_PROJECTED_CONTROL = """
    $SIGNER_ROTATION_ACTIVATION_CONTROL_EXPECTED
    SELECT ($SIGNER_ROTATION_ACTIVATION_PROJECTED_BINDING) IS TRUE AS binding_matches
    FROM complaint_journal_control c CROSS JOIN expected e WHERE $SIGNER_ROTATION_ACTIVATION_SCOPE
""".trimIndent()

internal val LOCK_SIGNER_ROTATION_ACTIVATION_PROJECTED_CONTROL = READ_SIGNER_ROTATION_ACTIVATION_PROJECTED_CONTROL + "\nFOR UPDATE OF c"

internal val READ_SIGNER_ROTATION_ACTIVATION_PROJECTED_LEASE = """
    $SIGNER_ROTATION_ACTIVATION_CONTROL_EXPECTED,
    sampled AS MATERIALIZED (SELECT clock_timestamp() AS sampled_at)
    SELECT ($SIGNER_ROTATION_ACTIVATION_PROJECTED_BINDING AND $SIGNER_ROTATION_ACTIVATION_CURRENT_LEASE) IS TRUE AS binding_matches
    FROM complaint_journal_control c CROSS JOIN expected e CROSS JOIN sampled WHERE $SIGNER_ROTATION_ACTIVATION_SCOPE
""".trimIndent()

private val SIGNER_ROTATION_ACTIVATION_OVERLAP_EXPECTED = """
    WITH overlap_expected AS (SELECT ?::uuid AS token, ?::uuid AS writer, ?::bytea AS approval_bytes, ?::bytea AS approval_hash,
        ?::bytea AS unsigned_bytes, ?::bytea AS unsigned_hash, ?::text AS signer_one_id, ?::text AS signer_one_algorithm,
        ?::text AS signer_two_id, ?::text AS signer_two_algorithm, ?::text AS object_key, ?::timestamptz AS created_at,
        ?::bytea AS predecessor_hash, ?::bytea AS signer_one_signature, ?::bytea AS signer_two_signature,
        ?::bytea AS envelope_bytes, ?::bytea AS envelope_hash)
""".trimIndent()

private val SIGNER_ROTATION_ACTIVATION_UNSIGNED_EXPECTED = """
    activation_unsigned AS (SELECT ?::uuid AS token, ?::uuid AS writer, ?::bytea AS approval_bytes, ?::bytea AS approval_hash,
        ?::bytea AS unsigned_bytes, ?::bytea AS unsigned_hash, ?::text AS signer_one_id, ?::text AS signer_one_algorithm,
        ?::text AS object_key, ?::timestamptz AS created_at, ?::bytea AS predecessor_hash)
""".trimIndent()

private val SIGNER_ROTATION_ACTIVATION_EXPECTED = """
    activation_expected AS (SELECT ?::uuid AS token, ?::uuid AS writer, ?::bytea AS approval_bytes, ?::bytea AS approval_hash,
        ?::bytea AS unsigned_bytes, ?::bytea AS unsigned_hash, ?::text AS signer_one_id, ?::text AS signer_one_algorithm,
        ?::text AS object_key, ?::timestamptz AS created_at, ?::bytea AS predecessor_hash,
        ?::bytea AS signer_one_signature, ?::bytea AS envelope_bytes, ?::bytea AS envelope_hash)
""".trimIndent()

private val SIGNER_ROTATION_ACTIVATION_COPIES_EXPECTED = """
    copies_expected AS (SELECT ?::text AS object_version, ?::timestamptz AS retain_until,
        ?::bytea AS primary_evidence_bytes, ?::bytea AS primary_evidence_hash,
        ?::bytea AS replica_evidence_bytes, ?::bytea AS replica_evidence_hash)
""".trimIndent()

private val SIGNER_ROTATION_ACTIVATION_COMPLETE_COPIES = """
    m.object_version IS NOT NULL AND m.retain_until IS NOT NULL
        AND m.primary_evidence_bytes IS NOT NULL AND m.primary_evidence_hash IS NOT NULL
        AND m.replica_evidence_bytes IS NOT NULL AND m.replica_evidence_hash IS NOT NULL
""".trimIndent()

private val SIGNER_ROTATION_ACTIVATION_GENESIS_MATCH = """
    m.operation_type = 'GENESIS' AND m.successor_generation = 1 AND m.predecessor_generation = 0
        AND m.predecessor_hash = decode(repeat('00', 32), 'hex') AND m.data_scope_id IS NULL AND m.test_only IS NULL
        AND m.canonicalizer = 'kcj-1' AND m.signer_policy = 'SINGLE' AND m.envelope_hash = r.predecessor_hash
        AND m.envelope_bytes IS NOT NULL AND m.signer_one_id = r.signer_one_id AND m.signer_one_algorithm = r.signer_one_algorithm
        AND m.catalog_writer_generation = r.writer AND m.signer_one_signature IS NOT NULL
        AND m.signer_two_id IS NULL AND m.signer_two_algorithm IS NULL AND m.signer_two_signature IS NULL
        AND m.state = 'COMPLETED' AND m.completed_at IS NOT NULL AND m.projected_at IS NOT NULL
        AND m.object_key = '${CatalogReadbackProtocol.key(1)}' AND $SIGNER_ROTATION_ACTIVATION_COMPLETE_COPIES
""".trimIndent()

private val SIGNER_ROTATION_ACTIVATION_OVERLAP_MATCH = """
    m.operation_token = r.token AND m.catalog_writer_generation = r.writer
        AND m.approval_bytes = r.approval_bytes AND m.approval_hash = r.approval_hash
        AND m.unsigned_bytes = r.unsigned_bytes AND m.unsigned_hash = r.unsigned_hash
        AND m.signer_one_id = r.signer_one_id AND m.signer_one_algorithm = r.signer_one_algorithm
        AND m.signer_two_id = r.signer_two_id AND m.signer_two_algorithm = r.signer_two_algorithm
        AND m.signer_one_signature = r.signer_one_signature AND m.signer_two_signature = r.signer_two_signature
        AND m.envelope_bytes = r.envelope_bytes AND m.envelope_hash = r.envelope_hash
        AND m.object_key = r.object_key AND m.object_key = '${CatalogReadbackProtocol.key(2)}'
        AND m.created_at = r.created_at AND m.predecessor_hash = r.predecessor_hash
        AND m.operation_type = 'SIGNER_ROTATION_OVERLAP' AND m.data_scope_id IS NULL AND m.test_only IS NULL
        AND m.predecessor_generation = 1 AND m.successor_generation = 2 AND m.canonicalizer = 'kcj-1' AND m.signer_policy = 'ROTATION_OVERLAP'
        AND m.signer_one_id <> m.signer_two_id
        AND m.state = 'COMPLETED' AND m.completed_at IS NOT NULL AND m.projected_at IS NOT NULL
        AND $SIGNER_ROTATION_ACTIVATION_COMPLETE_COPIES
""".trimIndent()

private val SIGNER_ROTATION_ACTIVATION_FROZEN = """
    m.operation_token = a.token AND m.catalog_writer_generation = a.writer
        AND m.approval_bytes = a.approval_bytes AND m.approval_hash = a.approval_hash
        AND m.unsigned_bytes = a.unsigned_bytes AND m.unsigned_hash = a.unsigned_hash
        AND m.signer_one_id = a.signer_one_id AND m.signer_one_algorithm = a.signer_one_algorithm
        AND m.signer_one_signature IS NOT DISTINCT FROM a.signer_one_signature
        AND m.envelope_bytes IS NOT DISTINCT FROM a.envelope_bytes AND m.envelope_hash IS NOT DISTINCT FROM a.envelope_hash
        AND m.signer_two_id IS NULL AND m.signer_two_algorithm IS NULL AND m.signer_two_signature IS NULL
        AND m.object_key = a.object_key AND m.object_key = '${CatalogReadbackProtocol.key(3)}'
        AND m.created_at = a.created_at AND m.predecessor_hash = a.predecessor_hash
        AND m.operation_type = 'SIGNER_ROTATION_ACTIVATION' AND m.data_scope_id IS NULL AND m.test_only IS NULL
        AND m.predecessor_generation = 2 AND m.successor_generation = 3 AND m.canonicalizer = 'kcj-1' AND m.signer_policy = 'SINGLE'
        AND ((m.signer_one_signature IS NULL AND m.envelope_bytes IS NULL AND m.envelope_hash IS NULL)
            OR (m.signer_one_signature IS NOT NULL AND m.envelope_bytes IS NOT NULL AND m.envelope_hash IS NOT NULL))
""".trimIndent()

private val SIGNER_ROTATION_ACTIVATION_CHAIN = """
    a.writer = r.writer AND a.predecessor_hash = r.envelope_hash
        AND a.signer_one_id = r.signer_two_id AND a.signer_one_algorithm = r.signer_two_algorithm
""".trimIndent()

private val SIGNER_ROTATION_ACTIVATION_PREPARED = """
    m.state = 'PREPARED' AND m.completed_at IS NULL AND m.projected_at IS NULL
        AND m.object_version IS NULL AND m.retain_until IS NULL
        AND m.primary_evidence_bytes IS NULL AND m.primary_evidence_hash IS NULL
        AND m.replica_evidence_bytes IS NULL AND m.replica_evidence_hash IS NULL
""".trimIndent()

private val SIGNER_ROTATION_ACTIVATION_SIGNED = """
    m.signer_one_signature IS NOT NULL AND m.envelope_bytes IS NOT NULL AND m.envelope_hash IS NOT NULL
""".trimIndent()

private val SIGNER_ROTATION_ACTIVATION_COPIES_MATCH = """
    m.object_version = cp.object_version AND m.retain_until = cp.retain_until
        AND m.primary_evidence_bytes = cp.primary_evidence_bytes AND m.primary_evidence_hash = cp.primary_evidence_hash
        AND m.replica_evidence_bytes = cp.replica_evidence_bytes AND m.replica_evidence_hash = cp.replica_evidence_hash
""".trimIndent()

private val SIGNER_ROTATION_ACTIVATION_PENDING = """
    m.state = 'COMPLETED' AND m.completed_at IS NOT NULL AND m.projected_at IS NULL
        AND $SIGNER_ROTATION_ACTIVATION_SIGNED AND $SIGNER_ROTATION_ACTIVATION_COPIES_MATCH
""".trimIndent()

private val SIGNER_ROTATION_ACTIVATION_PROJECTED = """
    m.state = 'COMPLETED' AND m.completed_at IS NOT NULL AND m.projected_at IS NOT NULL
        AND $SIGNER_ROTATION_ACTIVATION_SIGNED AND $SIGNER_ROTATION_ACTIVATION_COPIES_MATCH
""".trimIndent()

private val SIGNER_ROTATION_ACTIVATION_BOUNDED = """
    octet_length(m.approval_bytes) BETWEEN 1 AND ${CatalogSignerRotationActivationCapacityV1.MAX_APPROVAL_BYTES} AND octet_length(m.approval_hash) = 32
        AND octet_length(m.unsigned_bytes) BETWEEN 1 AND ${CatalogSignerRotationActivationCapacityV1.MAX_DOCUMENT_BYTES} AND octet_length(m.unsigned_hash) = 32
        AND ((m.envelope_bytes IS NULL AND m.envelope_hash IS NULL)
            OR (octet_length(m.envelope_bytes) BETWEEN 1 AND ${CatalogSignerRotationActivationCapacityV1.MAX_DOCUMENT_BYTES} AND octet_length(m.envelope_hash) = 32))
        AND octet_length(m.predecessor_hash) = 32
        AND (m.signer_one_signature IS NULL OR octet_length(m.signer_one_signature) = ${OfflineTrustBundleProtocol.SIGNATURE_BYTES})
        AND (m.signer_two_signature IS NULL OR octet_length(m.signer_two_signature) = ${OfflineTrustBundleProtocol.SIGNATURE_BYTES})
        AND octet_length(m.operation_type) BETWEEN 1 AND 64 AND octet_length(m.canonicalizer) BETWEEN 1 AND 16
        AND octet_length(m.signer_policy) BETWEEN 1 AND 24 AND octet_length(m.state) BETWEEN 1 AND 16
        AND octet_length(m.signer_one_id) BETWEEN 1 AND 128 AND octet_length(m.signer_one_algorithm) BETWEEN 1 AND 128
        AND (m.signer_two_id IS NULL OR octet_length(m.signer_two_id) BETWEEN 1 AND 128)
        AND (m.signer_two_algorithm IS NULL OR octet_length(m.signer_two_algorithm) BETWEEN 1 AND 128)
        AND octet_length(m.object_key) BETWEEN 1 AND 1024
        AND (m.object_version IS NULL OR (octet_length(m.object_version) BETWEEN 1 AND 1024 AND m.object_version <> 'null'))
        AND ((m.primary_evidence_bytes IS NULL AND m.primary_evidence_hash IS NULL)
            OR (octet_length(m.primary_evidence_bytes) BETWEEN 1 AND 65536 AND octet_length(m.primary_evidence_hash) = 32))
        AND ((m.replica_evidence_bytes IS NULL AND m.replica_evidence_hash IS NULL)
            OR (octet_length(m.replica_evidence_bytes) BETWEEN 1 AND 65536 AND octet_length(m.replica_evidence_hash) = 32))
        AND m.created_at BETWEEN TIMESTAMPTZ '1970-01-01 00:00:00+00' AND TIMESTAMPTZ '9999-12-31 23:59:59+00'
        AND (m.retain_until IS NULL OR m.retain_until BETWEEN TIMESTAMPTZ '1970-01-01 00:00:00+00' AND TIMESTAMPTZ '9999-12-31 23:59:59+00')
        AND (m.completed_at IS NULL OR m.completed_at BETWEEN TIMESTAMPTZ '1970-01-01 00:00:00+00' AND TIMESTAMPTZ '9999-12-31 23:59:59+00')
        AND (m.projected_at IS NULL OR m.projected_at BETWEEN TIMESTAMPTZ '1970-01-01 00:00:00+00' AND TIMESTAMPTZ '9999-12-31 23:59:59+00')
        AND complaint_finite_times(m.created_at, m.retain_until, m.completed_at, m.projected_at)
""".trimIndent()

/** No unbounded m.* projection; optional CASE-truncated values are never accepted unless bounded is TRUE. */
private val SIGNER_ROTATION_ACTIVATION_COLUMNS = """
    m.operation_token, m.operation_type, m.data_scope_id, m.test_only, m.predecessor_generation, m.successor_generation,
    m.catalog_writer_generation, m.canonicalizer, m.signer_policy, m.signer_one_id, m.signer_one_algorithm,
    m.signer_two_id, m.signer_two_algorithm, m.state,
    CASE WHEN octet_length(m.predecessor_hash) = 32 THEN m.predecessor_hash END AS predecessor_hash,
    CASE WHEN octet_length(m.approval_bytes) BETWEEN 1 AND ${CatalogSignerRotationActivationCapacityV1.MAX_APPROVAL_BYTES} THEN m.approval_bytes END AS approval_bytes,
    CASE WHEN octet_length(m.approval_hash) = 32 THEN m.approval_hash END AS approval_hash,
    CASE WHEN octet_length(m.unsigned_bytes) BETWEEN 1 AND ${CatalogSignerRotationActivationCapacityV1.MAX_DOCUMENT_BYTES} THEN m.unsigned_bytes END AS unsigned_bytes,
    CASE WHEN octet_length(m.unsigned_hash) = 32 THEN m.unsigned_hash END AS unsigned_hash,
    CASE WHEN octet_length(m.signer_one_signature) = ${OfflineTrustBundleProtocol.SIGNATURE_BYTES} THEN m.signer_one_signature END AS signer_one_signature,
    CASE WHEN octet_length(m.signer_two_signature) = ${OfflineTrustBundleProtocol.SIGNATURE_BYTES} THEN m.signer_two_signature END AS signer_two_signature,
    CASE WHEN octet_length(m.envelope_bytes) BETWEEN 1 AND ${CatalogSignerRotationActivationCapacityV1.MAX_DOCUMENT_BYTES} THEN m.envelope_bytes END AS envelope_bytes,
    CASE WHEN octet_length(m.envelope_hash) = 32 THEN m.envelope_hash END AS envelope_hash,
    CASE WHEN octet_length(m.object_key) BETWEEN 1 AND 1024 THEN m.object_key END AS object_key,
    CASE WHEN octet_length(m.object_version) BETWEEN 1 AND 1024 THEN m.object_version END AS object_version,
    CASE WHEN octet_length(m.primary_evidence_bytes) BETWEEN 1 AND 65536 THEN m.primary_evidence_bytes END AS primary_evidence_bytes,
    CASE WHEN octet_length(m.primary_evidence_hash) = 32 THEN m.primary_evidence_hash END AS primary_evidence_hash,
    CASE WHEN octet_length(m.replica_evidence_bytes) BETWEEN 1 AND 65536 THEN m.replica_evidence_bytes END AS replica_evidence_bytes,
    CASE WHEN octet_length(m.replica_evidence_hash) = 32 THEN m.replica_evidence_hash END AS replica_evidence_hash,
    CASE WHEN isfinite(m.created_at) THEN m.created_at END AS created_at,
    CASE WHEN isfinite(m.retain_until) THEN m.retain_until END AS retain_until,
    CASE WHEN isfinite(m.completed_at) THEN m.completed_at END AS completed_at,
    CASE WHEN isfinite(m.projected_at) THEN m.projected_at END AS projected_at
""".trimIndent()

/** O17 / 17. Exactly two rows, including actual projected2, must be present before PREPARE. */
internal val READ_SIGNER_ROTATION_ACTIVATION_HEAD_HISTORY = """
    $SIGNER_ROTATION_ACTIVATION_OVERLAP_EXPECTED
    SELECT ($SIGNER_ROTATION_ACTIVATION_GENESIS_MATCH) IS TRUE AS genesis_matches,
        ($SIGNER_ROTATION_ACTIVATION_OVERLAP_MATCH) IS TRUE AS overlap_matches, false AS activation_matches,
        ($SIGNER_ROTATION_ACTIVATION_BOUNDED) IS TRUE AS bounded, $SIGNER_ROTATION_ACTIVATION_COLUMNS
    FROM complaint_catalog_mutations m CROSS JOIN overlap_expected r
    ORDER BY m.successor_generation LIMIT 4
""".trimIndent()

internal val LOCK_SIGNER_ROTATION_ACTIVATION_HEAD_HISTORY = READ_SIGNER_ROTATION_ACTIVATION_HEAD_HISTORY + "\nFOR UPDATE OF m"

/** O17 + A14 / 31. No sparse single-signature state and no nullable predecessor-preimage bypass. */
internal val READ_SIGNER_ROTATION_ACTIVATION_PREPARED_HISTORY = """
    $SIGNER_ROTATION_ACTIVATION_OVERLAP_EXPECTED,
    $SIGNER_ROTATION_ACTIVATION_EXPECTED
    SELECT ($SIGNER_ROTATION_ACTIVATION_GENESIS_MATCH) IS TRUE AS genesis_matches,
        ($SIGNER_ROTATION_ACTIVATION_OVERLAP_MATCH) IS TRUE AS overlap_matches,
        ($SIGNER_ROTATION_ACTIVATION_FROZEN AND $SIGNER_ROTATION_ACTIVATION_CHAIN AND $SIGNER_ROTATION_ACTIVATION_PREPARED) IS TRUE AS activation_matches,
        ($SIGNER_ROTATION_ACTIVATION_BOUNDED) IS TRUE AS bounded, $SIGNER_ROTATION_ACTIVATION_COLUMNS
    FROM complaint_catalog_mutations m CROSS JOIN overlap_expected r CROSS JOIN activation_expected a
    ORDER BY m.successor_generation LIMIT 4
""".trimIndent()

internal val LOCK_SIGNER_ROTATION_ACTIVATION_PREPARED_HISTORY = READ_SIGNER_ROTATION_ACTIVATION_PREPARED_HISTORY + "\nFOR UPDATE OF m"

/** O17 + A14 + C6 / 37. Initial cold reads capture current full33 predecessors, not unavailable precrash observations. */
internal val READ_SIGNER_ROTATION_ACTIVATION_PENDING_HISTORY = """
    $SIGNER_ROTATION_ACTIVATION_OVERLAP_EXPECTED,
    $SIGNER_ROTATION_ACTIVATION_EXPECTED,
    $SIGNER_ROTATION_ACTIVATION_COPIES_EXPECTED
    SELECT ($SIGNER_ROTATION_ACTIVATION_GENESIS_MATCH) IS TRUE AS genesis_matches,
        ($SIGNER_ROTATION_ACTIVATION_OVERLAP_MATCH) IS TRUE AS overlap_matches,
        ($SIGNER_ROTATION_ACTIVATION_FROZEN AND $SIGNER_ROTATION_ACTIVATION_CHAIN AND $SIGNER_ROTATION_ACTIVATION_PENDING) IS TRUE AS activation_matches,
        ($SIGNER_ROTATION_ACTIVATION_BOUNDED) IS TRUE AS bounded, $SIGNER_ROTATION_ACTIVATION_COLUMNS
    FROM complaint_catalog_mutations m CROSS JOIN overlap_expected r CROSS JOIN activation_expected a CROSS JOIN copies_expected cp
    ORDER BY m.successor_generation LIMIT 4
""".trimIndent()

internal val LOCK_SIGNER_ROTATION_ACTIVATION_PENDING_HISTORY = READ_SIGNER_ROTATION_ACTIVATION_PENDING_HISTORY + "\nFOR UPDATE OF m"

/** O17 + A14 + C6 / 37. Actual accepted3/projected history; never a capability to repeat a completed effect. */
internal val READ_SIGNER_ROTATION_ACTIVATION_PROJECTED_HISTORY = """
    $SIGNER_ROTATION_ACTIVATION_OVERLAP_EXPECTED,
    $SIGNER_ROTATION_ACTIVATION_EXPECTED,
    $SIGNER_ROTATION_ACTIVATION_COPIES_EXPECTED
    SELECT ($SIGNER_ROTATION_ACTIVATION_GENESIS_MATCH) IS TRUE AS genesis_matches,
        ($SIGNER_ROTATION_ACTIVATION_OVERLAP_MATCH) IS TRUE AS overlap_matches,
        ($SIGNER_ROTATION_ACTIVATION_FROZEN AND $SIGNER_ROTATION_ACTIVATION_CHAIN AND $SIGNER_ROTATION_ACTIVATION_PROJECTED) IS TRUE AS activation_matches,
        ($SIGNER_ROTATION_ACTIVATION_BOUNDED) IS TRUE AS bounded, $SIGNER_ROTATION_ACTIVATION_COLUMNS
    FROM complaint_catalog_mutations m CROSS JOIN overlap_expected r CROSS JOIN activation_expected a CROSS JOIN copies_expected cp
    ORDER BY m.successor_generation LIMIT 4
""".trimIndent()

internal val LOCK_SIGNER_ROTATION_ACTIVATION_PROJECTED_HISTORY = READ_SIGNER_ROTATION_ACTIVATION_PROJECTED_HISTORY + "\nFOR UPDATE OF m"

/** A11 / 11. This is the only row creation; the same transaction checks all three rows and prepays one lifecycle charge. */
internal val INSERT_SIGNER_ROTATION_ACTIVATION_PREPARED = """
    WITH $SIGNER_ROTATION_ACTIVATION_UNSIGNED_EXPECTED
    INSERT INTO complaint_catalog_mutations (
        operation_token, catalog_writer_generation, approval_bytes, approval_hash, unsigned_bytes, unsigned_hash,
        signer_one_id, signer_one_algorithm, object_key, created_at, predecessor_hash,
        operation_type, predecessor_generation, successor_generation, canonicalizer, signer_policy, state,
        data_scope_id, test_only, signer_two_id, signer_two_algorithm, signer_two_signature,
        signer_one_signature, envelope_bytes, envelope_hash
    )
    SELECT a.token, a.writer, a.approval_bytes, a.approval_hash, a.unsigned_bytes, a.unsigned_hash,
        a.signer_one_id, a.signer_one_algorithm, a.object_key, a.created_at, a.predecessor_hash,
        'SIGNER_ROTATION_ACTIVATION', 2, 3, 'kcj-1', 'SINGLE', 'PREPARED',
        NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL
    FROM activation_unsigned a
    WHERE octet_length(a.approval_bytes) BETWEEN 1 AND ${CatalogSignerRotationActivationCapacityV1.MAX_APPROVAL_BYTES} AND octet_length(a.approval_hash) = 32
        AND octet_length(a.unsigned_bytes) BETWEEN 1 AND ${CatalogSignerRotationActivationCapacityV1.MAX_DOCUMENT_BYTES} AND octet_length(a.unsigned_hash) = 32
        AND complaint_ascii_valid(a.signer_one_id, 128) AND complaint_ascii_valid(a.signer_one_algorithm, 128)
        AND a.object_key = '${CatalogReadbackProtocol.key(3)}' AND octet_length(a.predecessor_hash) = 32
        AND a.created_at BETWEEN TIMESTAMPTZ '1970-01-01 00:00:00+00' AND TIMESTAMPTZ '9999-12-31 23:59:59+00' AND isfinite(a.created_at)
""".trimIndent()

/** Before A14 + new S3 / 17. Only NULL->complete S3 or byte-identical replay; the second signer is always NULL. */
internal val WRITE_SIGNER_ROTATION_ACTIVATION_SIGNATURE = """
    WITH $SIGNER_ROTATION_ACTIVATION_EXPECTED,
    next_signature AS (SELECT ?::bytea AS signature, ?::bytea AS envelope_bytes, ?::bytea AS envelope_hash)
    UPDATE complaint_catalog_mutations m
    SET signer_one_signature = n.signature, envelope_bytes = n.envelope_bytes, envelope_hash = n.envelope_hash
    FROM activation_expected a CROSS JOIN next_signature n
    WHERE $SIGNER_ROTATION_ACTIVATION_FROZEN AND $SIGNER_ROTATION_ACTIVATION_PREPARED AND $SIGNER_ROTATION_ACTIVATION_BOUNDED
        AND octet_length(n.signature) = ${OfflineTrustBundleProtocol.SIGNATURE_BYTES}
        AND octet_length(n.envelope_bytes) BETWEEN 1 AND ${CatalogSignerRotationActivationCapacityV1.MAX_DOCUMENT_BYTES} AND octet_length(n.envelope_hash) = 32
        AND ((a.signer_one_signature IS NULL AND a.envelope_bytes IS NULL AND a.envelope_hash IS NULL)
            OR (a.signer_one_signature = n.signature AND a.envelope_bytes = n.envelope_bytes AND a.envelope_hash = n.envelope_hash))
""".trimIndent()

private val SIGNER_ROTATION_ACTIVATION_COPY_INPUT_BOUNDED = """
    octet_length(cp.object_version) BETWEEN 1 AND 1024 AND cp.object_version <> 'null' AND isfinite(cp.retain_until)
        AND cp.retain_until BETWEEN TIMESTAMPTZ '1970-01-01 00:00:00+00' AND TIMESTAMPTZ '9999-12-31 23:59:59+00'
        AND octet_length(cp.primary_evidence_bytes) BETWEEN 1 AND 65536 AND octet_length(cp.primary_evidence_hash) = 32
        AND octet_length(cp.replica_evidence_bytes) BETWEEN 1 AND 65536 AND octet_length(cp.replica_evidence_hash) = 32
""".trimIndent()

/** A14 + C6 / 20. Complete only the exact signed PREPARED3; head3/pending3 follows atomically. */
internal val WRITE_SIGNER_ROTATION_ACTIVATION_COMPLETION = """
    WITH $SIGNER_ROTATION_ACTIVATION_EXPECTED,
    $SIGNER_ROTATION_ACTIVATION_COPIES_EXPECTED,
    sampled AS MATERIALIZED (SELECT clock_timestamp() AS sampled_at)
    UPDATE complaint_catalog_mutations m SET state = 'COMPLETED', completed_at = sampled.sampled_at,
        object_version = cp.object_version, retain_until = cp.retain_until,
        primary_evidence_bytes = cp.primary_evidence_bytes, primary_evidence_hash = cp.primary_evidence_hash,
        replica_evidence_bytes = cp.replica_evidence_bytes, replica_evidence_hash = cp.replica_evidence_hash
    FROM activation_expected a CROSS JOIN copies_expected cp CROSS JOIN sampled
    WHERE $SIGNER_ROTATION_ACTIVATION_FROZEN AND $SIGNER_ROTATION_ACTIVATION_PREPARED AND $SIGNER_ROTATION_ACTIVATION_SIGNED
        AND $SIGNER_ROTATION_ACTIVATION_BOUNDED AND $SIGNER_ROTATION_ACTIVATION_COPY_INPUT_BOUNDED AND isfinite(sampled.sampled_at)
""".trimIndent()

/** B2/B12 + operation3 + envelope3 hash / 14. Never modifies any identity, trust, writer, gate or lease field. */
internal val WRITE_SIGNER_ROTATION_ACTIVATION_HEAD = """
    $SIGNER_ROTATION_ACTIVATION_CONTROL_EXPECTED,
    successor AS (SELECT ?::uuid AS token, ?::bytea AS envelope_hash),
    sampled AS MATERIALIZED (SELECT clock_timestamp() AS sampled_at)
    UPDATE complaint_journal_control c SET accepted_catalog_generation = 3, accepted_catalog_hash = n.envelope_hash,
        pending_projection_token = n.token, updated_at = sampled.sampled_at
    FROM expected e CROSS JOIN successor n CROSS JOIN sampled
    WHERE $SIGNER_ROTATION_ACTIVATION_SCOPE AND $SIGNER_ROTATION_ACTIVATION_HEAD_BINDING AND $SIGNER_ROTATION_ACTIVATION_CURRENT_LEASE
        AND octet_length(n.envelope_hash) = 32
        AND EXISTS (SELECT 1 FROM complaint_catalog_mutations m
            WHERE m.operation_token = n.token AND m.operation_type = 'SIGNER_ROTATION_ACTIVATION'
                AND m.data_scope_id IS NULL AND m.test_only IS NULL AND m.predecessor_generation = 2 AND m.successor_generation = 3
                AND m.canonicalizer = 'kcj-1' AND m.signer_policy = 'SINGLE' AND m.signer_one_signature IS NOT NULL
                AND m.signer_two_id IS NULL AND m.signer_two_algorithm IS NULL AND m.signer_two_signature IS NULL
                AND m.catalog_writer_generation = e.catalog_writer AND m.predecessor_hash = e.catalog_hash
                AND m.object_key = '${CatalogReadbackProtocol.key(3)}' AND m.envelope_hash = n.envelope_hash
                AND m.state = 'COMPLETED' AND m.completed_at IS NOT NULL AND m.projected_at IS NULL)
""".trimIndent()

/** A14 + C6 + exact completed_at / 21. Separate pending3 phase; only projected_at may change. */
internal val WRITE_SIGNER_ROTATION_ACTIVATION_PROJECTION = """
    WITH $SIGNER_ROTATION_ACTIVATION_EXPECTED,
    $SIGNER_ROTATION_ACTIVATION_COPIES_EXPECTED,
    completion AS (SELECT ?::timestamptz AS completed_at),
    sampled AS MATERIALIZED (SELECT clock_timestamp() AS sampled_at)
    UPDATE complaint_catalog_mutations m SET projected_at = sampled.sampled_at
    FROM activation_expected a CROSS JOIN copies_expected cp CROSS JOIN completion actual CROSS JOIN sampled
    WHERE $SIGNER_ROTATION_ACTIVATION_FROZEN AND $SIGNER_ROTATION_ACTIVATION_PENDING AND $SIGNER_ROTATION_ACTIVATION_BOUNDED
        AND m.completed_at = actual.completed_at AND isfinite(sampled.sampled_at)
""".trimIndent()

/** B3/B12 + operation3 / 13. Clear only this pending marker and preserve the already accepted3 head. */
internal val WRITE_SIGNER_ROTATION_ACTIVATION_CLEAR_PENDING = """
    $SIGNER_ROTATION_ACTIVATION_PENDING_EXPECTED,
    sampled AS MATERIALIZED (SELECT clock_timestamp() AS sampled_at)
    UPDATE complaint_journal_control c SET pending_projection_token = NULL, updated_at = sampled.sampled_at
    FROM expected e CROSS JOIN pending p CROSS JOIN sampled
    WHERE $SIGNER_ROTATION_ACTIVATION_SCOPE AND $SIGNER_ROTATION_ACTIVATION_PENDING_BINDING AND $SIGNER_ROTATION_ACTIVATION_CURRENT_LEASE
        AND EXISTS (SELECT 1 FROM complaint_catalog_mutations m
            WHERE m.operation_token = p.token AND m.operation_type = 'SIGNER_ROTATION_ACTIVATION'
                AND m.data_scope_id IS NULL AND m.test_only IS NULL AND m.predecessor_generation = 2 AND m.successor_generation = 3
                AND m.canonicalizer = 'kcj-1' AND m.signer_policy = 'SINGLE' AND m.signer_one_signature IS NOT NULL
                AND m.signer_two_id IS NULL AND m.signer_two_algorithm IS NULL AND m.signer_two_signature IS NULL
                AND m.catalog_writer_generation = e.catalog_writer
                AND m.object_key = '${CatalogReadbackProtocol.key(3)}' AND m.envelope_hash = e.catalog_hash
                AND m.state = 'COMPLETED' AND m.completed_at IS NOT NULL AND m.projected_at IS NOT NULL)
""".trimIndent()
