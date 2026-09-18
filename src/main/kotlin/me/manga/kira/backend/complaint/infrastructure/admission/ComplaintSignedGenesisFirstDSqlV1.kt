package me.manga.kira.backend.complaint.infrastructure.admission

import me.manga.kira.backend.complaint.domain.catalog.CatalogGenesisCapacity
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleProtocol

/**
 * ALL history, bounded to two rows, in a LATER READ COMMITTED statement after epoch -> LIVE -> catalog locks.
 * History stays SELECT-only: no FOR UPDATE or new grant. Every production history writer MUST share that
 * lock order (currently PREPARE, signature, COMPLETE and PROJECT). Privileged SQL/restore/DDL is excluded
 * by environmental custody, not prevented by advisory locks. No history bytes are materialized by pgjdbc.
 */
internal val READ_SIGNED_GENESIS_FIRST_HISTORY_V1 = """
    SELECT (octet_length(approval_bytes) BETWEEN 1 AND 4096 AND octet_length(approval_hash) = 32
        AND octet_length(unsigned_bytes) BETWEEN 1 AND ${CatalogGenesisCapacity.MAX_DOCUMENT_BYTES} AND octet_length(unsigned_hash) = 32
        AND octet_length(signer_one_signature) = ${OfflineTrustBundleProtocol.SIGNATURE_BYTES}
        AND octet_length(envelope_bytes) BETWEEN 1 AND ${CatalogGenesisCapacity.MAX_DOCUMENT_BYTES} AND octet_length(envelope_hash) = 32
        AND operation_token = ? AND operation_type = 'GENESIS' AND data_scope_id IS NULL AND test_only IS NULL
        AND predecessor_generation = 0 AND predecessor_hash = decode(repeat('00', 32), 'hex') AND successor_generation = 1
        AND catalog_writer_generation = ? AND approval_bytes = ?::bytea AND approval_hash = ?::bytea AND canonicalizer = 'kcj-1'
        AND unsigned_bytes = ?::bytea AND unsigned_hash = ?::bytea AND signer_policy = 'SINGLE' AND signer_one_id = ? AND signer_one_algorithm = ?
        AND signer_one_signature = ?::bytea AND signer_two_id IS NULL AND signer_two_algorithm IS NULL AND signer_two_signature IS NULL
        AND envelope_bytes = ?::bytea AND envelope_hash = ?::bytea AND object_key = ? AND created_at = ?::timestamptz AND isfinite(created_at)
        AND state = 'PREPARED' AND completed_at IS NULL AND projected_at IS NULL AND object_version IS NULL AND retain_until IS NULL
        AND primary_evidence_bytes IS NULL AND primary_evidence_hash IS NULL AND replica_evidence_bytes IS NULL AND replica_evidence_hash IS NULL
    ) IS TRUE AS exact_signed_prepared
    FROM public.complaint_catalog_mutations
    ORDER BY successor_generation LIMIT 2
""".trimIndent()

/** Initial state outside catalog history. Never reuses or weakens bootstrap's empty-history predicate. */
internal const val READ_SIGNED_GENESIS_FIRST_OTHER_STATE_V1 = """
    SELECT NOT EXISTS (SELECT 1 FROM public.complaint_test_runs)
        AND NOT EXISTS (SELECT 1 FROM public.complaint_journal_control WHERE data_scope_id <> '00000000-0000-0000-0000-000000000000'::uuid)
        AND NOT EXISTS (SELECT 1 FROM public.complaint_journal_publications)
        AND NOT EXISTS (SELECT 1 FROM public.complaint_journal_scan_runs) AS initial_state
"""

/** The existing exact nullable control-preimage CAS already changes ONLY D and DB-sampled updated_at. */
internal val SELECT_SIGNED_GENESIS_FIRST_D_V1 = BOOTSTRAP_DESIRED_V1
