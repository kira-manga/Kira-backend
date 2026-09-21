package me.manga.kira.backend.complaint.infrastructure.reconciliation

/** Closed bounded readers: no state/family filter is allowed to hide unresolved cutoff work. */
internal object TestActiveCutoffPublicationSqlV1 {
    const val PAGE_SIZE = 32
    private val columns = """
        CASE WHEN octet_length(p.event_id) = 43 THEN p.event_id END AS event_id,
        p.data_scope_id, p.test_only, p.writer_generation, p.journal_epoch,
        CASE WHEN octet_length(p.event_kind) BETWEEN 1 AND 32 THEN p.event_kind END AS event_kind,
        p.target_count, CASE WHEN octet_length(p.routing_key_id) BETWEEN 1 AND 64 THEN p.routing_key_id END AS routing_key_id,
        CASE WHEN octet_length(p.object_key) BETWEEN 1 AND 1024 THEN p.object_key END AS object_key, p.created_at,
        CASE WHEN octet_length(p.state) BETWEEN 1 AND 16 THEN p.state END AS state,
        CASE WHEN complaint_bytes_match(p.event_bytes, p.semantic_hash, 65536) THEN p.event_bytes END AS event_bytes,
        CASE WHEN octet_length(p.semantic_hash) = 32 THEN p.semantic_hash END AS semantic_hash,
        CASE WHEN octet_length(p.object_version) BETWEEN 1 AND 1024 THEN p.object_version END AS object_version,
        p.object_created_at, p.retain_until, p.verified_at, p.applied_at,
        CASE WHEN octet_length(p.ciphertext_hash) = 32 THEN p.ciphertext_hash END AS ciphertext_hash,
        CASE WHEN complaint_bytes_match(p.verification_bytes, p.verification_hash, 65536) THEN p.verification_bytes END AS verification_bytes,
        CASE WHEN octet_length(p.verification_hash) = 32 THEN p.verification_hash END AS verification_hash,
        (p.test_only AND complaint_is_v4(p.data_scope_id) AND complaint_is_v4(p.writer_generation)
            AND complaint_event_id_valid(p.event_id) AND p.journal_epoch > 0 AND p.target_count BETWEEN 0 AND 100
            AND p.event_kind IN ('OWNER_DELETE', 'OWNER_DELETE_ALL', 'ADMIN_DELETE', 'ADMIN_BATCH_DELETE')
            AND complaint_ascii_valid(p.routing_key_id, 64) AND complaint_ascii_valid(p.object_key, 1024)
            AND p.canonicalizer = 'kcj-1' AND complaint_bytes_match(p.event_bytes, p.semantic_hash, 65536)
            AND complaint_finite_times(p.created_at, p.object_created_at, p.retain_until, p.verified_at, p.applied_at)
            AND ((p.state = 'PREPARED' AND p.object_version IS NULL AND p.ciphertext_hash IS NULL
                    AND p.object_created_at IS NULL AND p.retain_until IS NULL AND p.verified_at IS NULL
                    AND p.verification_bytes IS NULL AND p.verification_hash IS NULL AND p.applied_at IS NULL)
                OR (p.state IN ('VERIFIED', 'APPLIED') AND complaint_opaque_valid(p.object_version, 1024)
                    AND p.object_version <> 'null' AND complaint_digest_valid(p.ciphertext_hash)
                    AND p.object_created_at IS NOT NULL AND p.retain_until IS NOT NULL AND p.verified_at IS NOT NULL
                    AND p.retain_until > p.verified_at AND p.object_created_at <= p.verified_at
                    AND complaint_bytes_match(p.verification_bytes, p.verification_hash, 65536)
                    AND ((p.state = 'VERIFIED' AND p.applied_at IS NULL)
                        OR (p.state = 'APPLIED' AND p.applied_at >= p.verified_at))))) IS TRUE AS valid
    """.trimIndent()

    // Two writer-range index probes exclude the supported writer's potentially large higher epochs.
    val writerBefore = "SELECT 1 FROM complaint_journal_publications WHERE data_scope_id = ?::uuid AND writer_generation < ?::uuid LIMIT 1"
    val writerAfter = "SELECT 1 FROM complaint_journal_publications WHERE data_scope_id = ?::uuid AND writer_generation > ?::uuid LIMIT 1"

    val epochPage = """
        SELECT $columns FROM complaint_journal_publications p
        WHERE p.data_scope_id = ?::uuid AND p.writer_generation = ?::uuid AND p.journal_epoch BETWEEN 1 AND ?
            AND (p.journal_epoch, p.event_id) > (?, ?::text)
        ORDER BY p.journal_epoch, p.event_id LIMIT $PAGE_SIZE
    """.trimIndent()

    // Closed recurrent reader; original first-range statement/argument order is unchanged.
    val recurrentEpochPage = """
        SELECT $columns FROM complaint_journal_publications p
        WHERE p.data_scope_id = ?::uuid AND p.writer_generation = ?::uuid AND p.journal_epoch BETWEEN ? AND ?
            AND (p.journal_epoch, p.event_id) > (?, ?::text)
        ORDER BY p.journal_epoch, p.event_id LIMIT $PAGE_SIZE
    """.trimIndent()

    // Global unique key index deliberately includes a foreign-scope alias under the owned range.
    // The complete epoch pass precedes this query, so malformed keys cannot disappear behind bounds.
    val keyPage = """
        SELECT $columns FROM complaint_journal_publications p
        WHERE p.object_key COLLATE "C" >= ?::text COLLATE "C" AND p.object_key COLLATE "C" < ?::text COLLATE "C"
            AND p.object_key COLLATE "C" > ?::text COLLATE "C"
        ORDER BY p.object_key COLLATE "C" LIMIT $PAGE_SIZE
    """.trimIndent()

    val lock = "SELECT $columns FROM complaint_journal_publications p WHERE p.event_id = ?::text FOR UPDATE"

    /** Historical evidence only: full immutable preimage CAS, no receipt/APPLY/lease authority. */
    val verified = """
        UPDATE complaint_journal_publications p SET state = 'VERIFIED', object_version = ?, ciphertext_hash = ?,
            object_created_at = ?, retain_until = ?, verified_at = ?, verification_bytes = ?, verification_hash = ?
        WHERE p.event_id = ? AND p.data_scope_id = ?::uuid AND p.test_only AND p.writer_generation = ?::uuid
            AND p.journal_epoch = ? AND p.event_kind = ? AND p.target_count = ? AND p.routing_key_id = ? AND p.object_key = ?
            AND p.canonicalizer = 'kcj-1' AND p.event_bytes = ?::bytea AND p.semantic_hash = ?::bytea AND p.created_at = ?::timestamptz
            AND p.state = 'PREPARED' AND p.object_version IS NULL AND p.ciphertext_hash IS NULL AND p.object_created_at IS NULL
            AND p.retain_until IS NULL AND p.verified_at IS NULL AND p.verification_bytes IS NULL
            AND p.verification_hash IS NULL AND p.applied_at IS NULL
        RETURNING $columns
    """.trimIndent()
}
