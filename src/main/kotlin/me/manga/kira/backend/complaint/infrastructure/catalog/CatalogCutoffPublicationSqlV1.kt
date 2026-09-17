package me.manga.kira.backend.complaint.infrastructure.catalog

/** Bounded new-backend LIVE rows only. No state/family filter may hide unresolved or unsupported work. */
internal object CatalogCutoffPublicationSqlV1 {
    const val PAGE_SIZE = 32
    private const val LIVE = "data_scope_id = '00000000-0000-0000-0000-000000000000'::uuid AND NOT test_only"
    private val COLUMNS = """
        event_id, writer_generation, journal_epoch, event_kind, target_count, routing_key_id, object_key,
        canonicalizer, semantic_hash, state, created_at, object_version, ciphertext_hash,
        object_created_at, retain_until, verified_at, verification_hash, applied_at,
        CASE WHEN complaint_bytes_match(event_bytes, semantic_hash, 65536) THEN event_bytes END AS event_bytes,
        CASE WHEN complaint_bytes_match(verification_bytes, verification_hash, 65536) THEN verification_bytes END AS verification_bytes,
        ($LIVE) AS live,
        (complaint_event_id_valid(event_id) AND complaint_is_v4(writer_generation) AND journal_epoch > 0
            AND target_count BETWEEN 0 AND 100 AND complaint_ascii_valid(routing_key_id, 128)
            AND complaint_ascii_valid(object_key, 1024) AND canonicalizer = 'kcj-1'
            AND complaint_bytes_match(event_bytes, semantic_hash, 65536)
            AND complaint_finite_times(created_at, object_created_at, retain_until, verified_at, applied_at)
            AND ((state = 'PREPARED' AND object_version IS NULL AND ciphertext_hash IS NULL AND object_created_at IS NULL
                AND retain_until IS NULL AND verified_at IS NULL AND verification_bytes IS NULL AND verification_hash IS NULL AND applied_at IS NULL)
            OR (state IN ('VERIFIED', 'APPLIED') AND complaint_opaque_valid(object_version, 1024) AND object_version <> 'null'
                AND complaint_digest_valid(ciphertext_hash) AND object_created_at IS NOT NULL AND retain_until IS NOT NULL AND verified_at IS NOT NULL
                AND complaint_bytes_match(verification_bytes, verification_hash, 65536)
                AND ((state = 'VERIFIED' AND applied_at IS NULL) OR (state = 'APPLIED' AND applied_at IS NOT NULL))))) AS valid
    """.trimIndent()

    /** The existing scope/writer/epoch/event index bounds work, not just returned rows. */
    val EPOCH_PAGE = """
        SELECT $COLUMNS FROM complaint_journal_publications
        WHERE data_scope_id = '00000000-0000-0000-0000-000000000000'::uuid AND writer_generation = ?::uuid
            AND journal_epoch BETWEEN 1 AND ? AND (journal_epoch, event_id) > (?, ?::text)
        ORDER BY journal_epoch, event_id LIMIT $PAGE_SIZE
    """.trimIndent()

    /**
     * Exact padded-epoch lower/upper key bounds use V18's scope/writer/object_key(C) index. Higher
     * epochs cannot be scanned merely to filter them away. The preceding EPOCH_PAGE pass MUST
     * inspect/validate all <=cutoff rows first, so malformed keys cannot disappear behind this bound.
     */
    val SORTED_PAGE = """
        SELECT $COLUMNS FROM complaint_journal_publications
        WHERE data_scope_id = '00000000-0000-0000-0000-000000000000'::uuid AND writer_generation = ?::uuid
            AND object_key >= ?::text AND object_key < ?::text AND object_key > ?::text
        ORDER BY object_key LIMIT $PAGE_SIZE
    """.trimIndent()

    val LOCK_PUBLICATION = "SELECT $COLUMNS FROM complaint_journal_publications WHERE event_id = ? FOR UPDATE"

    /** Full immutable preimage CAS. Neither a receipt nor current leadership is asserted by this evidence write. */
    val RECORD_VERIFIED = """
        UPDATE complaint_journal_publications SET state = 'VERIFIED', object_version = ?, ciphertext_hash = ?,
            object_created_at = ?, retain_until = ?, verified_at = ?, verification_bytes = ?, verification_hash = ?
        WHERE event_id = ? AND $LIVE AND writer_generation = ?::uuid AND journal_epoch = ?
            AND event_kind = ? AND target_count = ? AND routing_key_id = ? AND object_key = ?
            AND canonicalizer = 'kcj-1' AND event_bytes = ?::bytea AND semantic_hash = ?::bytea AND created_at = ?::timestamptz
            AND state = 'PREPARED' AND object_version IS NULL AND ciphertext_hash IS NULL AND object_created_at IS NULL
            AND retain_until IS NULL AND verified_at IS NULL AND verification_bytes IS NULL AND verification_hash IS NULL AND applied_at IS NULL
        RETURNING $COLUMNS
    """.trimIndent()
}
