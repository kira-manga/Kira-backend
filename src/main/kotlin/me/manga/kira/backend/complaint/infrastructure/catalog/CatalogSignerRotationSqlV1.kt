package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackProtocol
import me.manga.kira.backend.complaint.domain.catalog.CatalogSignerRotationCapacityV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleProtocol

/** New fixed first-overlap statements. G1's all-history<=1 SQL and its named AUTHOR/TARGET roots stay untouched. */
private val SIGNER_ROTATION_BINDING = """
    NOT c.test_only AND c.implementation_schema = 1 AND c.desired_generation = ?
        AND c.desired_configuration_hash = ?::bytea AND c.database_identity = ?::uuid AND c.restore_identity = ?::uuid
        AND c.event_writer_generation = ?::uuid AND c.accepted_catalog_generation = ? AND c.accepted_catalog_hash = ?::bytea
        AND c.trust_bundle_hash = ?::bytea AND c.catalog_writer_generation = ?::uuid AND c.pending_projection_token IS NULL
        AND c.lease_owner = ?::uuid AND c.lease_token = ? AND c.lease_expires_at IS NOT NULL AND isfinite(c.lease_expires_at)
""".trimIndent()

internal val LOCK_SIGNER_ROTATION_CONTROL = """
    SELECT ($SIGNER_ROTATION_BINDING) IS TRUE AS binding_matches
    FROM complaint_journal_control c
    WHERE c.data_scope_id = '00000000-0000-0000-0000-000000000000'::uuid
    FOR UPDATE
""".trimIndent()

/** Invoked only AFTER the locked SELECT has returned, and again before effects/final reread; never pre-lock sampled time. */
internal val READ_SIGNER_ROTATION_CURRENT_LEASE = """
    WITH sampled AS MATERIALIZED (SELECT clock_timestamp() AS sampled_at)
    SELECT (($SIGNER_ROTATION_BINDING) AND isfinite(sampled.sampled_at) AND c.lease_expires_at > sampled.sampled_at) IS TRUE AS binding_matches
    FROM complaint_journal_control c CROSS JOIN sampled
    WHERE c.data_scope_id = '00000000-0000-0000-0000-000000000000'::uuid
""".trimIndent()

private val SIGNER_ROTATION_GENESIS_MATCH = """
    operation_type = 'GENESIS' AND successor_generation = 1 AND predecessor_generation = 0
        AND predecessor_hash = decode(repeat('00', 32), 'hex') AND data_scope_id IS NULL AND test_only IS NULL
        AND canonicalizer = 'kcj-1' AND signer_policy = 'SINGLE' AND envelope_hash = ?::bytea
        AND signer_one_id = ? AND signer_one_algorithm = ? AND catalog_writer_generation = ?::uuid
        AND (?::bytea IS NULL OR envelope_bytes = ?::bytea)
        AND signer_one_signature IS NOT NULL AND signer_two_id IS NULL AND signer_two_algorithm IS NULL AND signer_two_signature IS NULL
        AND state = 'COMPLETED' AND completed_at IS NOT NULL AND projected_at IS NOT NULL
        AND object_key = '${CatalogReadbackProtocol.key(1)}' AND object_version IS NOT NULL AND octet_length(object_version) BETWEEN 1 AND 1024
        AND retain_until IS NOT NULL AND isfinite(retain_until) AND isfinite(created_at) AND isfinite(completed_at) AND isfinite(projected_at)
        AND octet_length(primary_evidence_bytes) BETWEEN 1 AND 65536 AND octet_length(primary_evidence_hash) = 32
        AND octet_length(replica_evidence_bytes) BETWEEN 1 AND 65536 AND octet_length(replica_evidence_hash) = 32
""".trimIndent()

private val SIGNER_ROTATION_FROZEN_MATCH = """
    operation_token = ?::uuid AND catalog_writer_generation = ?::uuid AND approval_bytes = ?::bytea AND approval_hash = ?::bytea
        AND unsigned_bytes = ?::bytea AND unsigned_hash = ?::bytea AND signer_one_id = ? AND signer_one_algorithm = ?
        AND signer_two_id = ? AND signer_two_algorithm = ? AND object_key = ? AND created_at = ?::timestamptz AND predecessor_hash = ?::bytea
        AND operation_type = 'SIGNER_ROTATION_OVERLAP' AND data_scope_id IS NULL AND test_only IS NULL
        AND predecessor_generation = 1 AND successor_generation = 2 AND canonicalizer = 'kcj-1' AND signer_policy = 'ROTATION_OVERLAP'
        AND state = 'PREPARED' AND completed_at IS NULL AND projected_at IS NULL
        AND object_version IS NULL AND retain_until IS NULL AND primary_evidence_bytes IS NULL AND primary_evidence_hash IS NULL
        AND replica_evidence_bytes IS NULL AND replica_evidence_hash IS NULL
""".trimIndent()

private val SIGNER_ROTATION_BOUNDED = """
    octet_length(unsigned_bytes) BETWEEN 1 AND ${CatalogSignerRotationCapacityV1.MAX_DOCUMENT_BYTES} AND octet_length(unsigned_hash) = 32
        AND octet_length(approval_bytes) BETWEEN 1 AND ${CatalogSignerRotationCapacityV1.MAX_APPROVAL_BYTES} AND octet_length(approval_hash) = 32
        AND (signer_one_signature IS NULL OR octet_length(signer_one_signature) = ${OfflineTrustBundleProtocol.SIGNATURE_BYTES})
        AND (signer_two_signature IS NULL OR octet_length(signer_two_signature) = ${OfflineTrustBundleProtocol.SIGNATURE_BYTES})
        AND ((envelope_bytes IS NULL AND envelope_hash IS NULL) OR
            (octet_length(envelope_bytes) BETWEEN 1 AND ${CatalogSignerRotationCapacityV1.MAX_DOCUMENT_BYTES} AND octet_length(envelope_hash) = 32))
""".trimIndent()

private val SIGNER_ROTATION_HISTORY_SELECT = """
    SELECT ($SIGNER_ROTATION_GENESIS_MATCH) IS TRUE AS genesis_matches, ($SIGNER_ROTATION_FROZEN_MATCH) IS TRUE AS rotation_matches,
        ($SIGNER_ROTATION_BOUNDED) IS TRUE AS bounded, operation_token, successor_generation,
        signer_one_id, signer_one_algorithm, signer_two_id, signer_two_algorithm,
        CASE WHEN octet_length(unsigned_bytes) BETWEEN 1 AND ${CatalogSignerRotationCapacityV1.MAX_DOCUMENT_BYTES} THEN unsigned_bytes END AS unsigned_bytes,
        CASE WHEN octet_length(unsigned_hash) = 32 THEN unsigned_hash END AS unsigned_hash,
        CASE WHEN octet_length(approval_bytes) BETWEEN 1 AND ${CatalogSignerRotationCapacityV1.MAX_APPROVAL_BYTES} THEN approval_bytes END AS approval_bytes,
        CASE WHEN octet_length(signer_one_signature) = ${OfflineTrustBundleProtocol.SIGNATURE_BYTES} THEN signer_one_signature END AS signer_one_signature,
        CASE WHEN octet_length(signer_two_signature) = ${OfflineTrustBundleProtocol.SIGNATURE_BYTES} THEN signer_two_signature END AS signer_two_signature,
        CASE WHEN octet_length(envelope_bytes) BETWEEN 1 AND ${CatalogSignerRotationCapacityV1.MAX_DOCUMENT_BYTES} THEN envelope_bytes END AS envelope_bytes,
        CASE WHEN octet_length(envelope_hash) = 32 THEN envelope_hash END AS envelope_hash
    FROM complaint_catalog_mutations
    ORDER BY successor_generation
    LIMIT 3
""".trimIndent()

internal val LOCK_SIGNER_ROTATION_HISTORY = SIGNER_ROTATION_HISTORY_SELECT + "\nFOR UPDATE"
internal val READ_SIGNER_ROTATION_HISTORY = SIGNER_ROTATION_HISTORY_SELECT

internal val INSERT_SIGNER_ROTATION_PREPARED = """
    INSERT INTO complaint_catalog_mutations (
        operation_token, catalog_writer_generation, approval_bytes, approval_hash, unsigned_bytes, unsigned_hash,
        signer_one_id, signer_one_algorithm, signer_two_id, signer_two_algorithm, object_key, created_at, predecessor_hash,
        operation_type, predecessor_generation, successor_generation, canonicalizer, signer_policy, state
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'SIGNER_ROTATION_OVERLAP', 1, 2, 'kcj-1', 'ROTATION_OVERLAP', 'PREPARED')
""".trimIndent()

internal val WRITE_SIGNER_ROTATION_SIGNATURE = """
    UPDATE complaint_catalog_mutations SET signer_one_signature = ?, signer_two_signature = ?, envelope_bytes = ?, envelope_hash = ?
    WHERE $SIGNER_ROTATION_FROZEN_MATCH AND signer_one_signature IS NOT DISTINCT FROM ?::bytea AND signer_two_signature IS NOT DISTINCT FROM ?::bytea
        AND envelope_bytes IS NOT DISTINCT FROM ?::bytea AND envelope_hash IS NOT DISTINCT FROM ?::bytea
""".trimIndent()
