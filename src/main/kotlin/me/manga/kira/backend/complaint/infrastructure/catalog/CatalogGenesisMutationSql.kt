package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.complaint.domain.catalog.CatalogGenesisCapacity

/** Fixed pre-publication G1 statements. No caller-supplied identifier, predicate, lock key or callback. */
internal val LOCK_GENESIS_CONTROL = """
    SELECT NOT test_only AND maintenance_closed AND creation_closed AND NOT scan_requested
        AND accepted_catalog_generation IS NULL AND accepted_catalog_hash IS NULL AND trust_bundle_hash IS NULL
        AND catalog_writer_generation IS NULL AND pending_projection_token IS NULL AS genesis_closed
    FROM complaint_journal_control
    WHERE data_scope_id = '00000000-0000-0000-0000-000000000000'::uuid
    FOR UPDATE
""".trimIndent()

internal const val TRY_CATALOG_LOCK = "SELECT pg_try_advisory_xact_lock(hashtextextended('complaint-catalog-mutation', 0)) AS locked"

private val GENESIS_FROZEN_MATCH = """
    operation_token = ? AND operation_type = 'GENESIS' AND data_scope_id IS NULL AND test_only IS NULL
        AND predecessor_generation = 0 AND predecessor_hash = decode(repeat('00', 32), 'hex') AND successor_generation = 1
        AND catalog_writer_generation = ? AND approval_bytes = ? AND approval_hash = ? AND canonicalizer = 'kcj-1'
        AND unsigned_bytes = ? AND unsigned_hash = ? AND signer_policy = 'SINGLE' AND signer_one_id = ? AND signer_one_algorithm = ?
        AND signer_two_id IS NULL AND signer_two_algorithm IS NULL AND signer_two_signature IS NULL
        AND object_key = ? AND state = 'PREPARED' AND created_at = ? AND completed_at IS NULL AND projected_at IS NULL
        AND object_version IS NULL AND retain_until IS NULL AND primary_evidence_bytes IS NULL AND primary_evidence_hash IS NULL
        AND replica_evidence_bytes IS NULL AND replica_evidence_hash IS NULL
""".trimIndent()

private val GENESIS_SELECT = """
    SELECT ($GENESIS_FROZEN_MATCH) IS TRUE AS frozen_matches,
        (octet_length(unsigned_bytes) BETWEEN 1 AND ${CatalogGenesisCapacity.MAX_DOCUMENT_BYTES} AND octet_length(unsigned_hash) = 32
            AND (signer_one_signature IS NULL OR octet_length(signer_one_signature) BETWEEN 1 AND 1024)
            AND ((envelope_bytes IS NULL AND envelope_hash IS NULL)
                OR (octet_length(envelope_bytes) BETWEEN 1 AND ${CatalogGenesisCapacity.MAX_DOCUMENT_BYTES}
                    AND octet_length(envelope_hash) = 32 AND signer_one_signature IS NOT NULL))) IS TRUE AS bounded,
        CASE WHEN octet_length(unsigned_bytes) BETWEEN 1 AND ${CatalogGenesisCapacity.MAX_DOCUMENT_BYTES} THEN unsigned_bytes END AS unsigned_bytes,
        CASE WHEN octet_length(unsigned_hash) = 32 THEN unsigned_hash END AS unsigned_hash,
        CASE WHEN octet_length(signer_one_signature) BETWEEN 1 AND 1024 THEN signer_one_signature END AS signer_one_signature,
        CASE WHEN octet_length(envelope_bytes) BETWEEN 1 AND ${CatalogGenesisCapacity.MAX_DOCUMENT_BYTES} THEN envelope_bytes END AS envelope_bytes,
        CASE WHEN octet_length(envelope_hash) = 32 THEN envelope_hash END AS envelope_hash
    FROM complaint_catalog_mutations
    ORDER BY successor_generation
    LIMIT 2
""".trimIndent()

internal val LOCK_GENESIS_MUTATION = GENESIS_SELECT + "\nFOR UPDATE"
internal val READ_GENESIS_MUTATION = GENESIS_SELECT

internal val INSERT_GENESIS = """
    INSERT INTO complaint_catalog_mutations (
        operation_token, catalog_writer_generation, approval_bytes, approval_hash, unsigned_bytes, unsigned_hash,
        signer_one_id, signer_one_algorithm, object_key, created_at,
        operation_type, predecessor_generation, predecessor_hash, successor_generation, canonicalizer, signer_policy, state
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'GENESIS', 0, decode(repeat('00', 32), 'hex'), 1, 'kcj-1', 'SINGLE', 'PREPARED')
""".trimIndent()

internal val WRITE_GENESIS_SIGNATURE = """
    UPDATE complaint_catalog_mutations SET signer_one_signature = ?, envelope_bytes = ?, envelope_hash = ?
    WHERE $GENESIS_FROZEN_MATCH
        AND signer_one_signature IS NOT DISTINCT FROM ?::bytea
        AND envelope_bytes IS NOT DISTINCT FROM ?::bytea AND envelope_hash IS NOT DISTINCT FROM ?::bytea
""".trimIndent()
