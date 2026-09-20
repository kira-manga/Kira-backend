package me.manga.kira.backend.complaint.infrastructure

import me.manga.kira.backend.complaint.domain.ComplaintDataScope

/** Fixed LIVE or exact TEST-scope VERIFY: the API receipt precedes its publication, then one update/commit and stop. */
internal class OwnerDeleteAllVerificationSql private constructor(scope: ComplaintDataScope) {
    private val LIVE = "data_scope_id = '${scope.id}' AND ${if (scope.testOnly) "test_only" else "NOT test_only"}"

    val LOCK_RECEIPTS = """
        SELECT installation_id, deletion_key, submitted_credential_version, fingerprint, publication_ref, authorized_at,
            ($LIVE) AS live,
            (complaint_digest_valid(fingerprint) AND submitted_credential_version > 0
                AND complaint_finite_times(created_at, authorized_at, completed_at, expires_at)
                AND state = 'AUTHORIZED_DELETE' AND outcome IS NULL AND response_status IS NULL
                AND publication_ref IS NOT NULL AND authorized_at IS NOT NULL
                AND external_event_id IS NULL AND external_epoch IS NULL AND external_object_version IS NULL
                AND external_ciphertext_hash IS NULL AND completed_at IS NULL AND expires_at IS NULL) AS valid
        FROM installation_deletion_receipts WHERE installation_id = ?
        ORDER BY deletion_key LIMIT 2 FOR UPDATE
    """.trimIndent()

    // Never fetch an unbounded corrupt bytea into the parser. SHA checks run on the exact stored
    // bytes, not JSONB or a re-encoded document; PREPARED's absent verification stays absent.
    private val COLUMNS = """
        event_id, writer_generation, journal_epoch, event_kind, target_count, routing_key_id, object_key,
        canonicalizer, semantic_hash, state, created_at, object_version, ciphertext_hash,
        object_created_at, retain_until, verified_at, verification_hash,
        CASE WHEN complaint_bytes_match(event_bytes, semantic_hash, 65536) THEN event_bytes END AS event_bytes,
        CASE WHEN complaint_bytes_match(verification_bytes, verification_hash, 65536) THEN verification_bytes END AS verification_bytes,
        ($LIVE) AS live,
        (complaint_event_id_valid(event_id) AND complaint_is_v4(writer_generation) AND journal_epoch > 0
            AND event_kind = 'OWNER_DELETE_ALL' AND target_count BETWEEN 0 AND 100
            AND complaint_ascii_valid(routing_key_id, 128) AND complaint_ascii_valid(object_key, 1024)
            AND canonicalizer = 'kcj-1' AND complaint_bytes_match(event_bytes, semantic_hash, 65536)
            AND complaint_finite_times(created_at, object_created_at, retain_until, verified_at, applied_at)
            AND applied_at IS NULL AND (
                (state = 'PREPARED' AND object_version IS NULL AND ciphertext_hash IS NULL AND object_created_at IS NULL
                    AND retain_until IS NULL AND verified_at IS NULL AND verification_bytes IS NULL AND verification_hash IS NULL)
                OR (state = 'VERIFIED' AND complaint_opaque_valid(object_version, 1024) AND object_version <> 'null'
                    AND complaint_digest_valid(ciphertext_hash) AND object_created_at IS NOT NULL
                    AND retain_until IS NOT NULL AND verified_at IS NOT NULL
                    AND complaint_bytes_match(verification_bytes, verification_hash, 65536))
            )) AS valid
    """.trimIndent()

    val LOCK_PUBLICATION = "SELECT $COLUMNS FROM complaint_journal_publications WHERE event_id = ? FOR UPDATE"

    // Authorization prepaid this row's complete lifecycle. No counter, recovery reserve, audit,
    // receipt completion/TTL, installation or content statement belongs in this transaction.
    val RECORD_VERIFIED = """
        UPDATE complaint_journal_publications SET state = 'VERIFIED', object_version = ?, ciphertext_hash = ?,
            object_created_at = ?, retain_until = ?, verified_at = ?, verification_bytes = ?, verification_hash = ?
        WHERE event_id = ? AND $LIVE AND state = 'PREPARED' AND semantic_hash = ?
            AND object_version IS NULL AND ciphertext_hash IS NULL AND object_created_at IS NULL
            AND retain_until IS NULL AND verified_at IS NULL AND verification_bytes IS NULL AND verification_hash IS NULL AND applied_at IS NULL
        RETURNING $COLUMNS
    """.trimIndent()
    companion object {
        val LOCK_RECEIPTS get() = live.LOCK_RECEIPTS
        val LOCK_PUBLICATION get() = live.LOCK_PUBLICATION
        val RECORD_VERIFIED get() = live.RECORD_VERIFIED
        val live = OwnerDeleteAllVerificationSql(ComplaintDataScope.LIVE)
        fun test(scope: ComplaintDataScope): OwnerDeleteAllVerificationSql {
            require(scope.testOnly && scope.id.version() == 4 && scope.id.variant() == 2)
            return OwnerDeleteAllVerificationSql(scope)
        }
    }

}
