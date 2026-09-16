package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.complaint.domain.catalog.CatalogGenesisCapacity

/** Closed G1 statements only. Every parameter is detached before entry; neither JSON nor provider/crypto work occurs here. */
private val GENESIS_FINAL_EXPECTED = """
    WITH expected AS (SELECT ?::uuid AS token, ?::uuid AS writer, ?::bytea AS approval, ?::bytea AS approval_hash,
        ?::bytea AS unsigned_bytes, ?::bytea AS unsigned_hash, ?::text AS signer, ?::text AS algorithm,
        ?::text AS object_key, ?::timestamptz AS created_at, ?::bytea AS signature, ?::bytea AS envelope_bytes,
        ?::bytea AS envelope_hash, ?::text AS object_version, ?::timestamptz AS retain_until,
        ?::bytea AS primary_bytes, ?::bytea AS primary_hash, ?::bytea AS replica_bytes, ?::bytea AS replica_hash)
""".trimIndent()

private val GENESIS_FINAL_FROZEN = """
    m.operation_token = e.token AND m.operation_type = 'GENESIS' AND m.data_scope_id IS NULL AND m.test_only IS NULL
    AND m.predecessor_generation = 0 AND m.predecessor_hash = decode(repeat('00', 32), 'hex') AND m.successor_generation = 1
    AND m.catalog_writer_generation = e.writer AND m.approval_bytes = e.approval AND m.approval_hash = e.approval_hash
    AND m.canonicalizer = 'kcj-1' AND m.unsigned_bytes = e.unsigned_bytes AND m.unsigned_hash = e.unsigned_hash
    AND m.signer_policy = 'SINGLE' AND m.signer_one_id = e.signer AND m.signer_one_algorithm = e.algorithm
    AND m.signer_one_signature = e.signature AND m.signer_two_id IS NULL AND m.signer_two_algorithm IS NULL
    AND m.signer_two_signature IS NULL AND m.envelope_bytes = e.envelope_bytes AND m.envelope_hash = e.envelope_hash
    AND m.object_key = e.object_key AND m.created_at = e.created_at
""".trimIndent()

private val GENESIS_FINAL_COPIES = """
    m.object_version = e.object_version AND m.retain_until = e.retain_until
    AND m.primary_evidence_bytes = e.primary_bytes AND m.primary_evidence_hash = e.primary_hash
    AND m.replica_evidence_bytes = e.replica_bytes AND m.replica_evidence_hash = e.replica_hash
""".trimIndent()

private val GENESIS_FINAL_PREPARED = """
    m.state = 'PREPARED' AND m.completed_at IS NULL AND m.projected_at IS NULL
    AND m.object_version IS NULL AND m.retain_until IS NULL
    AND m.primary_evidence_bytes IS NULL AND m.primary_evidence_hash IS NULL
    AND m.replica_evidence_bytes IS NULL AND m.replica_evidence_hash IS NULL
""".trimIndent()

private val GENESIS_FINAL_PENDING = """
    m.state = 'COMPLETED' AND m.completed_at IS NOT NULL AND m.projected_at IS NULL AND $GENESIS_FINAL_COPIES
""".trimIndent()

private val GENESIS_FINAL_PROJECTED = """
    m.state = 'COMPLETED' AND m.completed_at IS NOT NULL AND m.projected_at IS NOT NULL AND $GENESIS_FINAL_COPIES
""".trimIndent()

internal val READ_GENESIS_FINALIZATION = """
    $GENESIS_FINAL_EXPECTED
    SELECT ($GENESIS_FINAL_FROZEN) IS TRUE AS frozen_matches,
        (octet_length(m.unsigned_bytes) BETWEEN 1 AND ${CatalogGenesisCapacity.MAX_DOCUMENT_BYTES}
            AND octet_length(m.unsigned_hash) = 32 AND octet_length(m.signer_one_signature) BETWEEN 1 AND 1024
            AND octet_length(m.envelope_bytes) BETWEEN 1 AND ${CatalogGenesisCapacity.MAX_DOCUMENT_BYTES}
            AND octet_length(m.envelope_hash) = 32 AND octet_length(m.approval_bytes) BETWEEN 1 AND 4096
            AND (m.primary_evidence_bytes IS NULL OR octet_length(m.primary_evidence_bytes) BETWEEN 1 AND 65536)
            AND (m.primary_evidence_hash IS NULL OR octet_length(m.primary_evidence_hash) = 32)
            AND (m.replica_evidence_bytes IS NULL OR octet_length(m.replica_evidence_bytes) BETWEEN 1 AND 65536)
            AND (m.replica_evidence_hash IS NULL OR octet_length(m.replica_evidence_hash) = 32)
            AND (m.object_version IS NULL OR octet_length(m.object_version) BETWEEN 1 AND 1024)
            AND (m.completed_at IS NULL OR m.completed_at BETWEEN TIMESTAMPTZ '1970-01-01 00:00:00+00' AND TIMESTAMPTZ '9999-12-31 23:59:59+00')
            AND (m.projected_at IS NULL OR m.projected_at BETWEEN TIMESTAMPTZ '1970-01-01 00:00:00+00' AND TIMESTAMPTZ '9999-12-31 23:59:59+00')
            AND complaint_finite_times(m.created_at, m.retain_until, m.completed_at, m.projected_at)) IS TRUE AS bounded,
        NULL::bytea AS unsigned_bytes, NULL::bytea AS unsigned_hash, NULL::bytea AS signer_one_signature,
        NULL::bytea AS envelope_bytes, NULL::bytea AS envelope_hash,
        ($GENESIS_FINAL_PREPARED) IS TRUE AS final_prepared,
        ($GENESIS_FINAL_PENDING) IS TRUE AS final_pending,
        ($GENESIS_FINAL_PROJECTED) IS TRUE AS final_projected,
        CASE WHEN isfinite(m.completed_at) THEN m.completed_at END AS completed_at,
        CASE WHEN isfinite(m.projected_at) THEN m.projected_at END AS projected_at
    FROM complaint_catalog_mutations m CROSS JOIN expected e
    ORDER BY m.successor_generation LIMIT 2
""".trimIndent()

// ALL history is locked, not a filtered token or pending subset that could hide a conflicting generation.
internal val LOCK_GENESIS_FINALIZATION = READ_GENESIS_FINALIZATION + "\nFOR UPDATE OF m"

internal val WRITE_GENESIS_COMPLETION = """
    $GENESIS_FINAL_EXPECTED
    UPDATE complaint_catalog_mutations m SET state = 'COMPLETED', completed_at = clock_timestamp(),
        object_version = e.object_version, retain_until = e.retain_until,
        primary_evidence_bytes = e.primary_bytes, primary_evidence_hash = e.primary_hash,
        replica_evidence_bytes = e.replica_bytes, replica_evidence_hash = e.replica_hash
    FROM expected e WHERE $GENESIS_FINAL_FROZEN AND $GENESIS_FINAL_PREPARED
""".trimIndent()

internal val WRITE_GENESIS_PROJECTION = """
    $GENESIS_FINAL_EXPECTED
    UPDATE complaint_catalog_mutations m SET projected_at = clock_timestamp()
    FROM expected e WHERE $GENESIS_FINAL_FROZEN AND $GENESIS_FINAL_PENDING
""".trimIndent()

private val GENESIS_CONTROL_EXPECTED = """
    WITH expected AS (SELECT ?::bytea AS envelope_hash, ?::bytea AS trust_hash, ?::uuid AS writer, ?::uuid AS token,
        ?::uuid AS database_identity, ?::uuid AS restore_identity, ?::uuid AS event_writer,
        ?::integer AS implementation_schema, ?::bigint AS desired_generation, ?::bytea AS desired_configuration_hash)
""".trimIndent()

private val GENESIS_INITIAL_CONTROL = """
    NOT c.test_only AND c.maintenance_closed AND c.creation_closed AND c.publication_epoch = 1
    AND c.implementation_schema = e.implementation_schema AND c.desired_generation = e.desired_generation
    AND ((c.database_identity IS NULL AND c.restore_identity IS NULL)
        OR (c.database_identity = e.database_identity AND c.restore_identity = e.restore_identity))
    AND (c.event_writer_generation IS NULL OR c.event_writer_generation = e.event_writer)
    AND (c.desired_configuration_hash IS NULL OR c.desired_configuration_hash = e.desired_configuration_hash)
""".trimIndent()

private val GENESIS_EMPTY_CONTROL = """
    c.accepted_catalog_generation IS NULL AND c.accepted_catalog_hash IS NULL AND c.trust_bundle_hash IS NULL
    AND c.catalog_writer_generation IS NULL AND c.pending_projection_token IS NULL
""".trimIndent()

private val GENESIS_ACCEPTED_CONTROL = """
    c.accepted_catalog_generation = 1 AND c.accepted_catalog_hash = e.envelope_hash
    AND c.trust_bundle_hash = e.trust_hash AND c.catalog_writer_generation = e.writer
""".trimIndent()

internal val READ_GENESIS_FINAL_CONTROL = """
    $GENESIS_CONTROL_EXPECTED
    SELECT ($GENESIS_INITIAL_CONTROL) IS TRUE AS initial_matches,
        ($GENESIS_EMPTY_CONTROL) IS TRUE AS head_absent,
        ($GENESIS_ACCEPTED_CONTROL AND c.pending_projection_token = e.token) IS TRUE AS head_pending,
        ($GENESIS_ACCEPTED_CONTROL AND c.pending_projection_token IS NULL) IS TRUE AS head_projected,
        (c.database_identity = e.database_identity AND c.restore_identity = e.restore_identity
            AND c.event_writer_generation = e.event_writer) IS TRUE AS identities_projected
    FROM complaint_journal_control c CROSS JOIN expected e
    WHERE c.data_scope_id = '00000000-0000-0000-0000-000000000000'::uuid
""".trimIndent()

internal val LOCK_GENESIS_FINAL_CONTROL = READ_GENESIS_FINAL_CONTROL + "\nFOR UPDATE OF c"

internal val WRITE_GENESIS_HEAD = """
    $GENESIS_CONTROL_EXPECTED
    UPDATE complaint_journal_control c SET accepted_catalog_generation = 1, accepted_catalog_hash = e.envelope_hash,
        trust_bundle_hash = e.trust_hash, catalog_writer_generation = e.writer,
        pending_projection_token = e.token, updated_at = clock_timestamp()
    FROM expected e WHERE c.data_scope_id = '00000000-0000-0000-0000-000000000000'::uuid
        AND $GENESIS_INITIAL_CONTROL AND $GENESIS_EMPTY_CONTROL
""".trimIndent()

// Closed initial bindings only: scan/maintenance/creation/desired configuration are NEVER cleared or synthesized.
internal val WRITE_GENESIS_INITIAL_CONTROL = """
    $GENESIS_CONTROL_EXPECTED
    UPDATE complaint_journal_control c SET database_identity = e.database_identity, restore_identity = e.restore_identity,
        event_writer_generation = e.event_writer, pending_projection_token = NULL, updated_at = clock_timestamp()
    FROM expected e WHERE c.data_scope_id = '00000000-0000-0000-0000-000000000000'::uuid
        AND $GENESIS_INITIAL_CONTROL AND $GENESIS_ACCEPTED_CONTROL AND c.pending_projection_token = e.token
""".trimIndent()
