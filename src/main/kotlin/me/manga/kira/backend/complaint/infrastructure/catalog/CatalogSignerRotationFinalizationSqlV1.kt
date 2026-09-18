package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackProtocol
import me.manga.kira.backend.complaint.domain.catalog.CatalogSignerRotationCapacityV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleProtocol

/**
 * Fixed overlap2 statements only. The concrete owner/phase supplies authority, locks, capacity verification and released-result checks.
 * B12 = existing full-B nine fields, owner, token, exact expiry. B1 uses accepted1/hash1; B2 uses accepted2/hash2.
 * R17 = token, writer, approval/hash, unsigned/hash, signer1 id/algorithm, signer2 id/algorithm, key, creation,
 *       predecessor hash, signature1, signature2, envelope/hash.
 * G21 = token, writer, approval/hash, unsigned/hash, signer id/algorithm/signature, envelope/hash, key, creation,
 *       version, retention, primary evidence/hash, replica evidence/hash, completed, projected.
 * C6 = primary version, retention, primary evidence/hash, replica evidence/hash. All groups are detached before entry.
 * History returns three required booleans (genesis_matches, rotation_matches, bounded) and all 33 bounded V14 columns.
 * The store must require exactly G1 and operation2, retain the complete G1 preimage, and preserve it on every reread.
 */
private val SIGNER_ROTATION_FINAL_CONTROL_EXPECTED = """
    WITH expected AS (SELECT ?::bigint AS desired_generation, ?::bytea AS desired_hash,
        ?::uuid AS database_identity, ?::uuid AS restore_identity, ?::uuid AS event_writer,
        ?::bigint AS catalog_generation, ?::bytea AS catalog_hash, ?::bytea AS trust_hash, ?::uuid AS catalog_writer,
        ?::uuid AS lease_owner, ?::bigint AS lease_token, ?::timestamptz AS lease_expires_at)
""".trimIndent()

private val SIGNER_ROTATION_FINAL_PENDING_EXPECTED = """
    $SIGNER_ROTATION_FINAL_CONTROL_EXPECTED,
    pending AS (SELECT ?::uuid AS token)
""".trimIndent()

private val SIGNER_ROTATION_FINAL_BINDING = """
    NOT c.test_only AND c.implementation_schema = 1 AND c.desired_generation = e.desired_generation
        AND c.desired_configuration_hash = e.desired_hash AND c.database_identity = e.database_identity
        AND c.restore_identity = e.restore_identity AND c.event_writer_generation = e.event_writer
        AND c.accepted_catalog_generation = e.catalog_generation AND c.accepted_catalog_hash = e.catalog_hash
        AND c.trust_bundle_hash = e.trust_hash AND c.catalog_writer_generation = e.catalog_writer
        AND c.maintenance_closed AND c.creation_closed
        AND c.lease_owner = e.lease_owner AND c.lease_token = e.lease_token AND c.lease_token > 0
        AND c.lease_expires_at = e.lease_expires_at AND isfinite(c.lease_expires_at) AND isfinite(c.updated_at)
""".trimIndent()

private val SIGNER_ROTATION_FINAL_PREPARED_BINDING = """
    $SIGNER_ROTATION_FINAL_BINDING AND c.accepted_catalog_generation = 1 AND c.pending_projection_token IS NULL
""".trimIndent()

private val SIGNER_ROTATION_FINAL_PENDING_BINDING = """
    $SIGNER_ROTATION_FINAL_BINDING AND c.accepted_catalog_generation = 2 AND c.pending_projection_token = p.token
""".trimIndent()

private val SIGNER_ROTATION_FINAL_PROJECTED_BINDING = """
    $SIGNER_ROTATION_FINAL_BINDING AND c.accepted_catalog_generation = 2 AND c.pending_projection_token IS NULL
""".trimIndent()

private const val SIGNER_ROTATION_FINAL_SCOPE = "c.data_scope_id = '00000000-0000-0000-0000-000000000000'::uuid"
private const val SIGNER_ROTATION_FINAL_CURRENT_LEASE = "isfinite(sampled.sampled_at) AND c.lease_expires_at > sampled.sampled_at"

/** B12. No clock is sampled in the locking SELECT; use the later PREPARED_LEASE statement after it returns. */
internal val READ_SIGNER_ROTATION_FINAL_PREPARED_CONTROL = """
    $SIGNER_ROTATION_FINAL_CONTROL_EXPECTED
    SELECT ($SIGNER_ROTATION_FINAL_PREPARED_BINDING) IS TRUE AS binding_matches
    FROM complaint_journal_control c CROSS JOIN expected e WHERE $SIGNER_ROTATION_FINAL_SCOPE
""".trimIndent()

internal val LOCK_SIGNER_ROTATION_FINAL_PREPARED_CONTROL = READ_SIGNER_ROTATION_FINAL_PREPARED_CONTROL + "\nFOR UPDATE OF c"

/** B12. Reissue immediately before effects and before returning from the unchanged head1 read/recheck. */
internal val READ_SIGNER_ROTATION_FINAL_PREPARED_LEASE = """
    $SIGNER_ROTATION_FINAL_CONTROL_EXPECTED,
    sampled AS MATERIALIZED (SELECT clock_timestamp() AS sampled_at)
    SELECT ($SIGNER_ROTATION_FINAL_PREPARED_BINDING AND $SIGNER_ROTATION_FINAL_CURRENT_LEASE) IS TRUE AS binding_matches
    FROM complaint_journal_control c CROSS JOIN expected e CROSS JOIN sampled WHERE $SIGNER_ROTATION_FINAL_SCOPE
""".trimIndent()

/** B12 + operation2 token. Only the exact post-COMPLETE head/pending image, never a generic pending allowance. */
internal val READ_SIGNER_ROTATION_FINAL_PENDING_CONTROL = """
    $SIGNER_ROTATION_FINAL_PENDING_EXPECTED
    SELECT ($SIGNER_ROTATION_FINAL_PENDING_BINDING) IS TRUE AS binding_matches
    FROM complaint_journal_control c CROSS JOIN expected e CROSS JOIN pending p WHERE $SIGNER_ROTATION_FINAL_SCOPE
""".trimIndent()

internal val LOCK_SIGNER_ROTATION_FINAL_PENDING_CONTROL = READ_SIGNER_ROTATION_FINAL_PENDING_CONTROL + "\nFOR UPDATE OF c"

/** B12 + operation2 token. COMPLETE rereads and PROJECT prewrite checks must use B2/pending2, not B1. */
internal val READ_SIGNER_ROTATION_FINAL_PENDING_LEASE = """
    $SIGNER_ROTATION_FINAL_PENDING_EXPECTED,
    sampled AS MATERIALIZED (SELECT clock_timestamp() AS sampled_at)
    SELECT ($SIGNER_ROTATION_FINAL_PENDING_BINDING AND $SIGNER_ROTATION_FINAL_CURRENT_LEASE) IS TRUE AS binding_matches
    FROM complaint_journal_control c CROSS JOIN expected e CROSS JOIN pending p CROSS JOIN sampled WHERE $SIGNER_ROTATION_FINAL_SCOPE
""".trimIndent()

/** B12. Exact projected-state observation under the retained actual lease; never mutation authority. */
internal val READ_SIGNER_ROTATION_FINAL_PROJECTED_CONTROL = """
    $SIGNER_ROTATION_FINAL_CONTROL_EXPECTED
    SELECT ($SIGNER_ROTATION_FINAL_PROJECTED_BINDING) IS TRUE AS binding_matches
    FROM complaint_journal_control c CROSS JOIN expected e WHERE $SIGNER_ROTATION_FINAL_SCOPE
""".trimIndent()

internal val LOCK_SIGNER_ROTATION_FINAL_PROJECTED_CONTROL = READ_SIGNER_ROTATION_FINAL_PROJECTED_CONTROL + "\nFOR UPDATE OF c"

internal val READ_SIGNER_ROTATION_FINAL_PROJECTED_LEASE = """
    $SIGNER_ROTATION_FINAL_CONTROL_EXPECTED,
    sampled AS MATERIALIZED (SELECT clock_timestamp() AS sampled_at)
    SELECT ($SIGNER_ROTATION_FINAL_PROJECTED_BINDING AND $SIGNER_ROTATION_FINAL_CURRENT_LEASE) IS TRUE AS binding_matches
    FROM complaint_journal_control c CROSS JOIN expected e CROSS JOIN sampled WHERE $SIGNER_ROTATION_FINAL_SCOPE
""".trimIndent()

private val SIGNER_ROTATION_FINAL_ROTATION_EXPECTED = """
    WITH rotation_expected AS (SELECT ?::uuid AS token, ?::uuid AS writer, ?::bytea AS approval_bytes, ?::bytea AS approval_hash,
        ?::bytea AS unsigned_bytes, ?::bytea AS unsigned_hash, ?::text AS signer_one_id, ?::text AS signer_one_algorithm,
        ?::text AS signer_two_id, ?::text AS signer_two_algorithm, ?::text AS object_key, ?::timestamptz AS created_at,
        ?::bytea AS predecessor_hash, ?::bytea AS signer_one_signature, ?::bytea AS signer_two_signature,
        ?::bytea AS envelope_bytes, ?::bytea AS envelope_hash)
""".trimIndent()

private val SIGNER_ROTATION_FINAL_GENESIS_EXPECTED = """
    genesis_expected AS (SELECT ?::uuid AS token, ?::uuid AS writer, ?::bytea AS approval_bytes, ?::bytea AS approval_hash,
        ?::bytea AS unsigned_bytes, ?::bytea AS unsigned_hash, ?::text AS signer_one_id, ?::text AS signer_one_algorithm,
        ?::bytea AS signer_one_signature, ?::bytea AS envelope_bytes, ?::bytea AS envelope_hash, ?::text AS object_key,
        ?::timestamptz AS created_at, ?::text AS object_version, ?::timestamptz AS retain_until,
        ?::bytea AS primary_evidence_bytes, ?::bytea AS primary_evidence_hash,
        ?::bytea AS replica_evidence_bytes, ?::bytea AS replica_evidence_hash,
        ?::timestamptz AS completed_at, ?::timestamptz AS projected_at)
""".trimIndent()

private val SIGNER_ROTATION_FINAL_COPIES_EXPECTED = """
    copies_expected AS (SELECT ?::text AS object_version, ?::timestamptz AS retain_until,
        ?::bytea AS primary_evidence_bytes, ?::bytea AS primary_evidence_hash,
        ?::bytea AS replica_evidence_bytes, ?::bytea AS replica_evidence_hash)
""".trimIndent()

private val SIGNER_ROTATION_FINAL_GENESIS_SHAPE = """
    m.operation_type = 'GENESIS' AND m.successor_generation = 1 AND m.predecessor_generation = 0
        AND m.predecessor_hash = decode(repeat('00', 32), 'hex') AND m.data_scope_id IS NULL AND m.test_only IS NULL
        AND m.canonicalizer = 'kcj-1' AND m.signer_policy = 'SINGLE' AND m.envelope_hash = r.predecessor_hash
        AND m.signer_one_id = r.signer_one_id AND m.signer_one_algorithm = r.signer_one_algorithm
        AND m.catalog_writer_generation = r.writer AND m.signer_one_signature IS NOT NULL
        AND m.signer_two_id IS NULL AND m.signer_two_algorithm IS NULL AND m.signer_two_signature IS NULL
        AND m.state = 'COMPLETED' AND m.completed_at IS NOT NULL AND m.projected_at IS NOT NULL
        AND m.object_key = '${CatalogReadbackProtocol.key(1)}' AND m.object_version IS NOT NULL AND m.retain_until IS NOT NULL
        AND m.primary_evidence_bytes IS NOT NULL AND m.primary_evidence_hash IS NOT NULL
        AND m.replica_evidence_bytes IS NOT NULL AND m.replica_evidence_hash IS NOT NULL
""".trimIndent()

private val SIGNER_ROTATION_FINAL_GENESIS_MATCH = """
    $SIGNER_ROTATION_FINAL_GENESIS_SHAPE AND m.operation_token = g.token AND m.catalog_writer_generation = g.writer
        AND m.approval_bytes = g.approval_bytes AND m.approval_hash = g.approval_hash
        AND m.unsigned_bytes = g.unsigned_bytes AND m.unsigned_hash = g.unsigned_hash
        AND m.signer_one_id = g.signer_one_id AND m.signer_one_algorithm = g.signer_one_algorithm
        AND m.signer_one_signature = g.signer_one_signature AND m.envelope_bytes = g.envelope_bytes AND m.envelope_hash = g.envelope_hash
        AND m.object_key = g.object_key AND m.created_at = g.created_at AND m.object_version = g.object_version AND m.retain_until = g.retain_until
        AND m.primary_evidence_bytes = g.primary_evidence_bytes AND m.primary_evidence_hash = g.primary_evidence_hash
        AND m.replica_evidence_bytes = g.replica_evidence_bytes AND m.replica_evidence_hash = g.replica_evidence_hash
        AND m.completed_at = g.completed_at AND m.projected_at = g.projected_at
""".trimIndent()

private val SIGNER_ROTATION_FINAL_FROZEN = """
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
""".trimIndent()

private val SIGNER_ROTATION_FINAL_PREPARED = """
    m.state = 'PREPARED' AND m.completed_at IS NULL AND m.projected_at IS NULL
        AND m.object_version IS NULL AND m.retain_until IS NULL
        AND m.primary_evidence_bytes IS NULL AND m.primary_evidence_hash IS NULL
        AND m.replica_evidence_bytes IS NULL AND m.replica_evidence_hash IS NULL
""".trimIndent()

private val SIGNER_ROTATION_FINAL_COPIES_MATCH = """
    m.object_version = cp.object_version AND m.retain_until = cp.retain_until
        AND m.primary_evidence_bytes = cp.primary_evidence_bytes AND m.primary_evidence_hash = cp.primary_evidence_hash
        AND m.replica_evidence_bytes = cp.replica_evidence_bytes AND m.replica_evidence_hash = cp.replica_evidence_hash
""".trimIndent()

private val SIGNER_ROTATION_FINAL_PENDING = """
    m.state = 'COMPLETED' AND m.completed_at IS NOT NULL AND m.projected_at IS NULL AND $SIGNER_ROTATION_FINAL_COPIES_MATCH
""".trimIndent()

private val SIGNER_ROTATION_FINAL_PROJECTED = """
    m.state = 'COMPLETED' AND m.completed_at IS NOT NULL AND m.projected_at IS NOT NULL AND $SIGNER_ROTATION_FINAL_COPIES_MATCH
""".trimIndent()

private val SIGNER_ROTATION_FINAL_BOUNDED = """
    octet_length(m.approval_bytes) BETWEEN 1 AND ${CatalogSignerRotationCapacityV1.MAX_APPROVAL_BYTES} AND octet_length(m.approval_hash) = 32
        AND octet_length(m.unsigned_bytes) BETWEEN 1 AND ${CatalogSignerRotationCapacityV1.MAX_DOCUMENT_BYTES} AND octet_length(m.unsigned_hash) = 32
        AND octet_length(m.envelope_bytes) BETWEEN 1 AND ${CatalogSignerRotationCapacityV1.MAX_DOCUMENT_BYTES} AND octet_length(m.envelope_hash) = 32
        AND octet_length(m.predecessor_hash) = 32 AND octet_length(m.signer_one_signature) = ${OfflineTrustBundleProtocol.SIGNATURE_BYTES}
        AND (m.signer_two_signature IS NULL OR octet_length(m.signer_two_signature) = ${OfflineTrustBundleProtocol.SIGNATURE_BYTES})
        AND octet_length(m.object_key) BETWEEN 1 AND 1024
        AND (m.object_version IS NULL OR (octet_length(m.object_version) BETWEEN 1 AND 1024 AND m.object_version <> 'null'))
        AND ((m.primary_evidence_bytes IS NULL AND m.primary_evidence_hash IS NULL)
            OR (octet_length(m.primary_evidence_bytes) BETWEEN 1 AND 65536 AND octet_length(m.primary_evidence_hash) = 32))
        AND ((m.replica_evidence_bytes IS NULL AND m.replica_evidence_hash IS NULL)
            OR (octet_length(m.replica_evidence_bytes) BETWEEN 1 AND 65536 AND octet_length(m.replica_evidence_hash) = 32))
        AND (m.completed_at IS NULL OR m.completed_at BETWEEN TIMESTAMPTZ '1970-01-01 00:00:00+00' AND TIMESTAMPTZ '9999-12-31 23:59:59+00')
        AND (m.projected_at IS NULL OR m.projected_at BETWEEN TIMESTAMPTZ '1970-01-01 00:00:00+00' AND TIMESTAMPTZ '9999-12-31 23:59:59+00')
        AND complaint_finite_times(m.created_at, m.retain_until, m.completed_at, m.projected_at)
""".trimIndent()

/** Only finite bounded values leave JDBC, even when the caller must reject the row's shape. No unbounded m.* projection. */
private val SIGNER_ROTATION_FINAL_COLUMNS = """
    m.operation_token, m.operation_type, m.data_scope_id, m.test_only, m.predecessor_generation, m.successor_generation,
    m.catalog_writer_generation, m.canonicalizer, m.signer_policy, m.signer_one_id, m.signer_one_algorithm,
    m.signer_two_id, m.signer_two_algorithm, m.state,
    CASE WHEN octet_length(m.predecessor_hash) = 32 THEN m.predecessor_hash END AS predecessor_hash,
    CASE WHEN octet_length(m.approval_bytes) BETWEEN 1 AND ${CatalogSignerRotationCapacityV1.MAX_APPROVAL_BYTES} THEN m.approval_bytes END AS approval_bytes,
    CASE WHEN octet_length(m.approval_hash) = 32 THEN m.approval_hash END AS approval_hash,
    CASE WHEN octet_length(m.unsigned_bytes) BETWEEN 1 AND ${CatalogSignerRotationCapacityV1.MAX_DOCUMENT_BYTES} THEN m.unsigned_bytes END AS unsigned_bytes,
    CASE WHEN octet_length(m.unsigned_hash) = 32 THEN m.unsigned_hash END AS unsigned_hash,
    CASE WHEN octet_length(m.signer_one_signature) = ${OfflineTrustBundleProtocol.SIGNATURE_BYTES} THEN m.signer_one_signature END AS signer_one_signature,
    CASE WHEN octet_length(m.signer_two_signature) = ${OfflineTrustBundleProtocol.SIGNATURE_BYTES} THEN m.signer_two_signature END AS signer_two_signature,
    CASE WHEN octet_length(m.envelope_bytes) BETWEEN 1 AND ${CatalogSignerRotationCapacityV1.MAX_DOCUMENT_BYTES} THEN m.envelope_bytes END AS envelope_bytes,
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

/** R17. First locked observation captures complete G1; no supplied nullable preimage or bypass flag. */
internal val READ_SIGNER_ROTATION_FINAL_INITIAL_HISTORY = """
    $SIGNER_ROTATION_FINAL_ROTATION_EXPECTED
    SELECT ($SIGNER_ROTATION_FINAL_GENESIS_SHAPE) IS TRUE AS genesis_matches,
        ($SIGNER_ROTATION_FINAL_FROZEN AND $SIGNER_ROTATION_FINAL_PREPARED) IS TRUE AS rotation_matches,
        ($SIGNER_ROTATION_FINAL_BOUNDED) IS TRUE AS bounded, $SIGNER_ROTATION_FINAL_COLUMNS
    FROM complaint_catalog_mutations m CROSS JOIN rotation_expected r
    ORDER BY m.successor_generation LIMIT 3
""".trimIndent()

internal val LOCK_SIGNER_ROTATION_FINAL_INITIAL_HISTORY = READ_SIGNER_ROTATION_FINAL_INITIAL_HISTORY + "\nFOR UPDATE OF m"

/** R17 + C6. Cold pending2 observation captures current G1; no claim of equality with an unavailable precrash G1. */
internal val READ_SIGNER_ROTATION_FINAL_INITIAL_PENDING_HISTORY = """
    $SIGNER_ROTATION_FINAL_ROTATION_EXPECTED,
    $SIGNER_ROTATION_FINAL_COPIES_EXPECTED
    SELECT ($SIGNER_ROTATION_FINAL_GENESIS_SHAPE) IS TRUE AS genesis_matches,
        ($SIGNER_ROTATION_FINAL_FROZEN AND $SIGNER_ROTATION_FINAL_PENDING) IS TRUE AS rotation_matches,
        ($SIGNER_ROTATION_FINAL_BOUNDED) IS TRUE AS bounded, $SIGNER_ROTATION_FINAL_COLUMNS
    FROM complaint_catalog_mutations m CROSS JOIN rotation_expected r CROSS JOIN copies_expected cp
    ORDER BY m.successor_generation LIMIT 3
""".trimIndent()

internal val LOCK_SIGNER_ROTATION_FINAL_INITIAL_PENDING_HISTORY = READ_SIGNER_ROTATION_FINAL_INITIAL_PENDING_HISTORY + "\nFOR UPDATE OF m"

/** R17 + C6 from genuine Accepted2 raw evidence. Cold projected observation cannot issue a write or replay capability. */
internal val READ_SIGNER_ROTATION_FINAL_INITIAL_PROJECTED_HISTORY = """
    $SIGNER_ROTATION_FINAL_ROTATION_EXPECTED,
    $SIGNER_ROTATION_FINAL_COPIES_EXPECTED
    SELECT ($SIGNER_ROTATION_FINAL_GENESIS_SHAPE) IS TRUE AS genesis_matches,
        ($SIGNER_ROTATION_FINAL_FROZEN AND $SIGNER_ROTATION_FINAL_PROJECTED) IS TRUE AS rotation_matches,
        ($SIGNER_ROTATION_FINAL_BOUNDED) IS TRUE AS bounded, $SIGNER_ROTATION_FINAL_COLUMNS
    FROM complaint_catalog_mutations m CROSS JOIN rotation_expected r CROSS JOIN copies_expected cp
    ORDER BY m.successor_generation LIMIT 3
""".trimIndent()

internal val LOCK_SIGNER_ROTATION_FINAL_INITIAL_PROJECTED_HISTORY = READ_SIGNER_ROTATION_FINAL_INITIAL_PROJECTED_HISTORY + "\nFOR UPDATE OF m"

/** R17 + G21. Every later head1 READ/recheck/COMPLETE requires the exact retained G1 row, including lifecycle/copies. */
internal val READ_SIGNER_ROTATION_FINAL_PREPARED_HISTORY = """
    $SIGNER_ROTATION_FINAL_ROTATION_EXPECTED,
    $SIGNER_ROTATION_FINAL_GENESIS_EXPECTED
    SELECT ($SIGNER_ROTATION_FINAL_GENESIS_MATCH) IS TRUE AS genesis_matches,
        ($SIGNER_ROTATION_FINAL_FROZEN AND $SIGNER_ROTATION_FINAL_PREPARED) IS TRUE AS rotation_matches,
        ($SIGNER_ROTATION_FINAL_BOUNDED) IS TRUE AS bounded, $SIGNER_ROTATION_FINAL_COLUMNS
    FROM complaint_catalog_mutations m CROSS JOIN rotation_expected r CROSS JOIN genesis_expected g
    ORDER BY m.successor_generation LIMIT 3
""".trimIndent()

internal val LOCK_SIGNER_ROTATION_FINAL_PREPARED_HISTORY = READ_SIGNER_ROTATION_FINAL_PREPARED_HISTORY + "\nFOR UPDATE OF m"

/** R17 + G21 + C6. The store also compares completed_at with its retained pending capability before PROJECT. */
internal val READ_SIGNER_ROTATION_FINAL_PENDING_HISTORY = """
    $SIGNER_ROTATION_FINAL_ROTATION_EXPECTED,
    $SIGNER_ROTATION_FINAL_GENESIS_EXPECTED,
    $SIGNER_ROTATION_FINAL_COPIES_EXPECTED
    SELECT ($SIGNER_ROTATION_FINAL_GENESIS_MATCH) IS TRUE AS genesis_matches,
        ($SIGNER_ROTATION_FINAL_FROZEN AND $SIGNER_ROTATION_FINAL_PENDING) IS TRUE AS rotation_matches,
        ($SIGNER_ROTATION_FINAL_BOUNDED) IS TRUE AS bounded, $SIGNER_ROTATION_FINAL_COLUMNS
    FROM complaint_catalog_mutations m CROSS JOIN rotation_expected r CROSS JOIN genesis_expected g CROSS JOIN copies_expected cp
    ORDER BY m.successor_generation LIMIT 3
""".trimIndent()

internal val LOCK_SIGNER_ROTATION_FINAL_PENDING_HISTORY = READ_SIGNER_ROTATION_FINAL_PENDING_HISTORY + "\nFOR UPDATE OF m"

/** R17 + G21 + C6. Read-only projected observation; timestamps are checked against the actual retained preimage. */
internal val READ_SIGNER_ROTATION_FINAL_PROJECTED_HISTORY = """
    $SIGNER_ROTATION_FINAL_ROTATION_EXPECTED,
    $SIGNER_ROTATION_FINAL_GENESIS_EXPECTED,
    $SIGNER_ROTATION_FINAL_COPIES_EXPECTED
    SELECT ($SIGNER_ROTATION_FINAL_GENESIS_MATCH) IS TRUE AS genesis_matches,
        ($SIGNER_ROTATION_FINAL_FROZEN AND $SIGNER_ROTATION_FINAL_PROJECTED) IS TRUE AS rotation_matches,
        ($SIGNER_ROTATION_FINAL_BOUNDED) IS TRUE AS bounded, $SIGNER_ROTATION_FINAL_COLUMNS
    FROM complaint_catalog_mutations m CROSS JOIN rotation_expected r CROSS JOIN genesis_expected g CROSS JOIN copies_expected cp
    ORDER BY m.successor_generation LIMIT 3
""".trimIndent()

internal val LOCK_SIGNER_ROTATION_FINAL_PROJECTED_HISTORY = READ_SIGNER_ROTATION_FINAL_PROJECTED_HISTORY + "\nFOR UPDATE OF m"

private val SIGNER_ROTATION_FINAL_COPY_INPUT_BOUNDED = """
    octet_length(cp.object_version) BETWEEN 1 AND 1024 AND cp.object_version <> 'null' AND isfinite(cp.retain_until)
        AND octet_length(cp.primary_evidence_bytes) BETWEEN 1 AND 65536 AND octet_length(cp.primary_evidence_hash) = 32
        AND octet_length(cp.replica_evidence_bytes) BETWEEN 1 AND 65536 AND octet_length(cp.replica_evidence_hash) = 32
""".trimIndent()

/** R17 + C6. Exactly one PREPARED2 row changes; the same transaction must next write and verify head2/pending2. */
internal val WRITE_SIGNER_ROTATION_FINAL_COMPLETION = """
    $SIGNER_ROTATION_FINAL_ROTATION_EXPECTED,
    $SIGNER_ROTATION_FINAL_COPIES_EXPECTED,
    sampled AS MATERIALIZED (SELECT clock_timestamp() AS sampled_at)
    UPDATE complaint_catalog_mutations m SET state = 'COMPLETED', completed_at = sampled.sampled_at,
        object_version = cp.object_version, retain_until = cp.retain_until,
        primary_evidence_bytes = cp.primary_evidence_bytes, primary_evidence_hash = cp.primary_evidence_hash,
        replica_evidence_bytes = cp.replica_evidence_bytes, replica_evidence_hash = cp.replica_evidence_hash
    FROM rotation_expected r CROSS JOIN copies_expected cp CROSS JOIN sampled
    WHERE $SIGNER_ROTATION_FINAL_FROZEN AND $SIGNER_ROTATION_FINAL_PREPARED AND $SIGNER_ROTATION_FINAL_BOUNDED
        AND $SIGNER_ROTATION_FINAL_COPY_INPUT_BOUNDED AND isfinite(sampled.sampled_at)
""".trimIndent()

/** B1's B12 + operation2 token + exact envelope2 hash. Never initializes identities, trust/writers, gates or lease fields. */
internal val WRITE_SIGNER_ROTATION_FINAL_HEAD = """
    $SIGNER_ROTATION_FINAL_CONTROL_EXPECTED,
    successor AS (SELECT ?::uuid AS token, ?::bytea AS envelope_hash),
    sampled AS MATERIALIZED (SELECT clock_timestamp() AS sampled_at)
    UPDATE complaint_journal_control c SET accepted_catalog_generation = 2, accepted_catalog_hash = n.envelope_hash,
        pending_projection_token = n.token, updated_at = sampled.sampled_at
    FROM expected e CROSS JOIN successor n CROSS JOIN sampled
    WHERE $SIGNER_ROTATION_FINAL_SCOPE AND $SIGNER_ROTATION_FINAL_PREPARED_BINDING AND $SIGNER_ROTATION_FINAL_CURRENT_LEASE
        AND octet_length(n.envelope_hash) = 32
        AND EXISTS (SELECT 1 FROM complaint_catalog_mutations m
            WHERE m.operation_token = n.token AND m.operation_type = 'SIGNER_ROTATION_OVERLAP'
                AND m.data_scope_id IS NULL AND m.test_only IS NULL AND m.predecessor_generation = 1 AND m.successor_generation = 2
                AND m.canonicalizer = 'kcj-1' AND m.signer_policy = 'ROTATION_OVERLAP'
                AND m.catalog_writer_generation = e.catalog_writer AND m.predecessor_hash = e.catalog_hash
                AND m.object_key = '${CatalogReadbackProtocol.key(2)}' AND m.envelope_hash = n.envelope_hash
                AND m.state = 'COMPLETED' AND m.completed_at IS NOT NULL AND m.projected_at IS NULL)
""".trimIndent()

/** R17 + C6 + exact completed_at. Pending capability only; no already-projected rewrite or completion-time change. */
internal val WRITE_SIGNER_ROTATION_FINAL_PROJECTION = """
    $SIGNER_ROTATION_FINAL_ROTATION_EXPECTED,
    $SIGNER_ROTATION_FINAL_COPIES_EXPECTED,
    completion AS (SELECT ?::timestamptz AS completed_at),
    sampled AS MATERIALIZED (SELECT clock_timestamp() AS sampled_at)
    UPDATE complaint_catalog_mutations m SET projected_at = sampled.sampled_at
    FROM rotation_expected r CROSS JOIN copies_expected cp CROSS JOIN completion actual CROSS JOIN sampled
    WHERE $SIGNER_ROTATION_FINAL_FROZEN AND $SIGNER_ROTATION_FINAL_PENDING AND $SIGNER_ROTATION_FINAL_BOUNDED
        AND m.completed_at = actual.completed_at AND isfinite(sampled.sampled_at)
""".trimIndent()

/** B2's B12 + operation2 token. Clear only this pending marker; the same transaction must verify the projected history. */
internal val WRITE_SIGNER_ROTATION_FINAL_CLEAR_PENDING = """
    $SIGNER_ROTATION_FINAL_PENDING_EXPECTED,
    sampled AS MATERIALIZED (SELECT clock_timestamp() AS sampled_at)
    UPDATE complaint_journal_control c SET pending_projection_token = NULL, updated_at = sampled.sampled_at
    FROM expected e CROSS JOIN pending p CROSS JOIN sampled
    WHERE $SIGNER_ROTATION_FINAL_SCOPE AND $SIGNER_ROTATION_FINAL_PENDING_BINDING AND $SIGNER_ROTATION_FINAL_CURRENT_LEASE
        AND EXISTS (SELECT 1 FROM complaint_catalog_mutations m
            WHERE m.operation_token = p.token AND m.operation_type = 'SIGNER_ROTATION_OVERLAP'
                AND m.data_scope_id IS NULL AND m.test_only IS NULL AND m.predecessor_generation = 1 AND m.successor_generation = 2
                AND m.canonicalizer = 'kcj-1' AND m.signer_policy = 'ROTATION_OVERLAP'
                AND m.catalog_writer_generation = e.catalog_writer
                AND m.object_key = '${CatalogReadbackProtocol.key(2)}' AND m.envelope_hash = e.catalog_hash
                AND m.state = 'COMPLETED' AND m.completed_at IS NOT NULL AND m.projected_at IS NOT NULL)
""".trimIndent()
