package me.manga.kira.backend.complaint.infrastructure

import me.manga.kira.backend.complaint.domain.ComplaintDataScope

/** Fixed LIVE or exact TEST-scope statements in the concrete APPLY operation; no caller-supplied relation/scope/lock strategy. */
internal class OwnerDeleteAllApplySql private constructor(scope: ComplaintDataScope) {
    private val LIVE = "data_scope_id = '${scope.id}' AND ${if (scope.testOnly) "test_only" else "NOT test_only"}"
    val NOW = "SELECT clock_timestamp()"

    val LOCK_RECEIPTS = """
        SELECT deletion_key, submitted_credential_version, fingerprint, publication_ref, authorized_at, state,
            external_event_id, external_epoch, external_object_version, external_ciphertext_hash, completed_at, expires_at,
            ($LIVE) AS live,
            (complaint_digest_valid(fingerprint) AND submitted_credential_version > 0
                AND publication_ref IS NOT NULL AND authorized_at IS NOT NULL
                AND complaint_finite_times(created_at, authorized_at, completed_at, expires_at) AND (
                    (state = 'AUTHORIZED_DELETE' AND outcome IS NULL AND response_status IS NULL
                        AND external_event_id IS NULL AND external_epoch IS NULL AND external_object_version IS NULL
                        AND external_ciphertext_hash IS NULL AND completed_at IS NULL AND expires_at IS NULL)
                    OR (state = 'COMPLETED' AND outcome = 'APPLIED' AND response_status = 204
                        AND external_event_id = publication_ref AND external_epoch > 0
                        AND complaint_opaque_valid(external_object_version, 1024) AND external_object_version <> 'null'
                        AND complaint_digest_valid(external_ciphertext_hash) AND completed_at IS NOT NULL
                        AND expires_at = completed_at + interval '192 hours'))) AS valid
        FROM installation_deletion_receipts WHERE installation_id = ? ORDER BY deletion_key LIMIT 2 FOR UPDATE
    """.trimIndent()

    val LOCK_PUBLICATION = """
        SELECT event_id, writer_generation, journal_epoch, target_count, routing_key_id, object_key, state, created_at, applied_at,
            object_version, ciphertext_hash, object_created_at, retain_until, verified_at, verification_hash, semantic_hash,
            CASE WHEN complaint_bytes_match(event_bytes, semantic_hash, 65536) THEN event_bytes END AS event_bytes,
            CASE WHEN complaint_bytes_match(verification_bytes, verification_hash, 65536) THEN verification_bytes END AS verification_bytes,
            ($LIVE) AS live,
            (complaint_event_id_valid(event_id) AND complaint_is_v4(writer_generation) AND journal_epoch > 0
                AND event_kind = 'OWNER_DELETE_ALL' AND target_count BETWEEN 0 AND 100 AND canonicalizer = 'kcj-1'
                AND complaint_ascii_valid(routing_key_id, 128) AND complaint_ascii_valid(object_key, 1024)
                AND complaint_bytes_match(event_bytes, semantic_hash, 65536)
                AND complaint_bytes_match(verification_bytes, verification_hash, 65536)
                AND complaint_opaque_valid(object_version, 1024) AND object_version <> 'null' AND complaint_digest_valid(ciphertext_hash)
                AND object_created_at IS NOT NULL AND retain_until IS NOT NULL AND verified_at IS NOT NULL
                AND complaint_finite_times(created_at, object_created_at, retain_until, verified_at, applied_at)
                AND ((state = 'VERIFIED' AND applied_at IS NULL) OR (state = 'APPLIED' AND applied_at IS NOT NULL))) AS valid
        FROM complaint_journal_publications WHERE event_id = ? FOR UPDATE
    """.trimIndent()

    // Queue-only nullable proof grammar. The strict committed-primary reader above is unchanged.
    // A PREPARED row is not proof; only this original delivery's native GET/decrypt may fill it.
    val QUEUE_LOCK_PUBLICATION = """
        SELECT event_id, writer_generation, journal_epoch, target_count, routing_key_id, object_key, state, created_at, applied_at,
            object_version, ciphertext_hash, object_created_at, retain_until, verified_at, verification_hash, semantic_hash,
            CASE WHEN complaint_bytes_match(event_bytes, semantic_hash, 65536) THEN event_bytes END AS event_bytes,
            CASE WHEN complaint_bytes_match(verification_bytes, verification_hash, 65536) THEN verification_bytes END AS verification_bytes,
            ($LIVE) AS live,
            (complaint_event_id_valid(event_id) AND complaint_is_v4(writer_generation) AND journal_epoch > 0
                AND event_kind = 'OWNER_DELETE_ALL' AND target_count BETWEEN 0 AND 100 AND canonicalizer = 'kcj-1'
                AND complaint_ascii_valid(routing_key_id, 128) AND complaint_ascii_valid(object_key, 1024)
                AND complaint_bytes_match(event_bytes, semantic_hash, 65536)
                AND complaint_finite_times(created_at, object_created_at, retain_until, verified_at, applied_at)
                AND ((state = 'PREPARED' AND applied_at IS NULL AND object_version IS NULL AND ciphertext_hash IS NULL
                        AND object_created_at IS NULL AND retain_until IS NULL AND verified_at IS NULL
                        AND verification_bytes IS NULL AND verification_hash IS NULL)
                    OR (state IN ('VERIFIED', 'APPLIED') AND complaint_bytes_match(verification_bytes, verification_hash, 65536)
                        AND complaint_opaque_valid(object_version, 1024) AND object_version <> 'null'
                        AND complaint_digest_valid(ciphertext_hash) AND object_created_at IS NOT NULL
                        AND retain_until IS NOT NULL AND verified_at IS NOT NULL
                        AND ((state = 'VERIFIED' AND applied_at IS NULL) OR (state = 'APPLIED' AND applied_at IS NOT NULL))))) AS valid
        FROM complaint_journal_publications WHERE event_id = ? FOR UPDATE
    """.trimIndent()

    val LOCK_RECOVERY = """
        SELECT event_id, publication_ref, state, converted_at,
            CASE WHEN complaint_vector_valid(reserved_amounts) THEN reserved_amounts END AS reserved_amounts,
            CASE WHEN complaint_vector_valid(converted_amounts) THEN converted_amounts END AS converted_amounts,
            ($LIVE) AS live,
            (publication_ref = event_id AND accounting_version = 1 AND complaint_vector_valid(reserved_amounts)
                AND complaint_finite_times(created_at, converted_at) AND (
                    (state = 'RESERVED' AND converted_amounts IS NULL AND converted_at IS NULL)
                    OR (state = 'PARTIAL' AND complaint_vector_lte(converted_amounts, reserved_amounts)
                        AND converted_amounts <> array_fill(0::bigint, ARRAY[22]) AND converted_amounts <> reserved_amounts
                        AND converted_at IS NOT NULL))) AS valid
        FROM complaint_recovery_capacity_reservations WHERE event_id = ? FOR UPDATE
    """.trimIndent()

    val LOCK_INSTALLATION = """
        SELECT state, terminal_at, ($LIVE) AS live,
            (complaint_finite_times(created_at, terminal_at) AND (
                (state = 'DELETION_PENDING' AND terminal_at IS NULL) OR (state = 'DELETED' AND terminal_at IS NOT NULL))) AS valid
        FROM complaint_installation_ids WHERE id = ? FOR UPDATE
    """.trimIndent()

    val LOCK_CREDENTIAL = """
        SELECT state, credential_version, version, secret_verifier, deleted_at, verifier_expires_at, ($LIVE) AS live,
            (credential_version > 0 AND version > 0 AND complaint_digest_valid(secret_verifier)
                AND complaint_finite_times(created_at, last_authenticated_at, deleted_at, verifier_expires_at) AND (
                    (state = 'DELETION_PENDING' AND platform IN ('ANDROID', 'IOS') AND complaint_is_v4(owner_reference)
                        AND owner_reference <> id AND last_authenticated_at IS NOT NULL AND deleted_at IS NULL AND verifier_expires_at IS NULL)
                    OR (state = 'DELETED' AND platform IS NULL AND owner_reference IS NULL AND last_authenticated_at IS NULL
                        AND deleted_at IS NOT NULL AND verifier_expires_at = deleted_at + interval '192 hours'))) AS valid
        FROM app_installations WHERE id = ? FOR UPDATE
    """.trimIndent()

    val OWNER_TARGETS = "SELECT id FROM complaints WHERE owner_id = ? ORDER BY id LIMIT 101"

    val LOCK_RESOURCE = """
        SELECT id, state, deleted_at, ($LIVE) AS live,
            (complaint_finite_times(created_at, deleted_at) AND (
                (state IN ('LIVE', 'DELETION_PENDING') AND deleted_at IS NULL) OR (state = 'DELETED' AND deleted_at IS NOT NULL))) AS valid
        FROM complaint_resource_ids WHERE id = ? FOR UPDATE
    """.trimIndent()

    val LOCK_CONTENT = """
        SELECT id, owner_id, kind, version, ($LIVE) AS live,
            (ownership = 'INSTALLATION' AND kind IN ('REPORT', 'REPLY') AND owner_id IS NOT NULL AND version > 0
                AND legacy_collection IS NULL AND legacy_key_id IS NULL AND legacy_document_hmac IS NULL
                AND legacy_payload_hash IS NULL AND legacy_owner_fingerprint IS NULL AND legacy_reconciliation_code IS NULL
                AND complaint_finite_times(created_at, updated_at, closed_at)) AS valid
        FROM complaints WHERE id = ANY (?::uuid[]) ORDER BY id FOR UPDATE
    """.trimIndent()

    // Called in UUID order, interleaved with LOCK_RESOURCE for missing E. No out-of-order second insertion pass.
    val RECONSTRUCT_RESOURCE = """
        INSERT INTO complaint_resource_ids (id, data_scope_id, test_only, state, created_at, deleted_at)
        VALUES (?, '${scope.id}', ${scope.testOnly}, 'DELETED', ?, ?)
    """.trimIndent()

    val DELETE_CONTENT = """
        DELETE FROM complaints WHERE id = ? AND $LIVE AND owner_id = ? AND ownership = 'INSTALLATION'
            AND kind IN ('REPORT', 'REPLY') AND version = ? RETURNING id, version
    """.trimIndent()

    val TOMBSTONE_RESOURCE = """
        UPDATE complaint_resource_ids SET state = 'DELETED', deleted_at = ?
        WHERE id = ? AND $LIVE AND state IN ('LIVE', 'DELETION_PENDING') AND deleted_at IS NULL
    """.trimIndent()

    val DELETE_INSTALLATION = """
        UPDATE complaint_installation_ids SET state = 'DELETED', terminal_at = ?
        WHERE id = ? AND $LIVE AND state = 'DELETION_PENDING' AND terminal_at IS NULL
    """.trimIndent()

    val DELETE_CREDENTIAL = """
        UPDATE app_installations SET state = 'DELETED', credential_version = credential_version + 1, version = version + 1,
            platform = NULL, owner_reference = NULL, last_authenticated_at = NULL, deleted_at = ?, verifier_expires_at = ?
        WHERE id = ? AND $LIVE AND state = 'DELETION_PENDING' AND credential_version = ? AND version = ?
            AND secret_verifier = ? AND deleted_at IS NULL AND verifier_expires_at IS NULL
        RETURNING credential_version, version, secret_verifier, deleted_at, verifier_expires_at
    """.trimIndent()

    val RECORD_PROGRESS = """
        UPDATE complaint_recovery_capacity_reservations SET state = 'PARTIAL', converted_amounts = ?::bigint[], converted_at = ?
        WHERE event_id = ? AND $LIVE AND publication_ref = event_id AND accounting_version = 1 AND reserved_amounts = ?::bigint[]
            AND state = ? AND converted_amounts IS NOT DISTINCT FROM ?::bigint[] AND converted_at IS NOT DISTINCT FROM ?::timestamptz
    """.trimIndent()

    val COMPLETE_RECEIPT = """
        UPDATE installation_deletion_receipts SET state = 'COMPLETED', outcome = 'APPLIED', response_status = 204,
            external_event_id = publication_ref, external_epoch = ?, external_object_version = ?, external_ciphertext_hash = ?,
            completed_at = ?, expires_at = ?
        WHERE installation_id = ? AND deletion_key = ? AND $LIVE AND state = 'AUTHORIZED_DELETE'
            AND publication_ref = ? AND fingerprint = ? AND submitted_credential_version = ?
            AND completed_at IS NULL AND expires_at IS NULL
    """.trimIndent()

    val MARK_APPLIED = """
        UPDATE complaint_journal_publications SET state = 'APPLIED', applied_at = ?
        WHERE event_id = ? AND $LIVE AND state = 'VERIFIED' AND applied_at IS NULL
            AND verification_bytes = ? AND verification_hash = ? AND retain_until > clock_timestamp()
    """.trimIndent()

    val APPLIED = """
        SELECT event_id, object_key, object_version, ciphertext_hash, writer_generation, journal_epoch, target_count, applied_at,
            ($LIVE) AS live, (event_kind = 'OWNER_DELETE_ALL' AND isfinite(applied_at) AND complaint_digest_valid(ciphertext_hash)) AS valid
        FROM complaint_deletion_journal_applied WHERE object_key = ? AND object_version = ?
    """.trimIndent()

    /** Used only by the private ACTIVE queue capture, not a terminal inventory or primary-request grant. */
    val QUEUE_APPLIED_FAMILY = """
        SELECT event_id, object_key, object_version, ciphertext_hash, writer_generation, journal_epoch, target_count, applied_at,
            ($LIVE) AS live, (event_kind = 'OWNER_DELETE_ALL' AND isfinite(applied_at) AND complaint_digest_valid(ciphertext_hash)
                AND complaint_event_id_valid(event_id) AND complaint_is_v4(writer_generation) AND journal_epoch > 0
                AND target_count BETWEEN 0 AND 100 AND complaint_opaque_valid(object_version, 1024) AND object_version <> 'null') AS valid
        FROM complaint_deletion_journal_applied WHERE event_id = ANY (?::text[])
        ORDER BY event_id, object_key, object_version LIMIT 5
    """.trimIndent()

    val INSERT_APPLIED = """
        INSERT INTO complaint_deletion_journal_applied
            (object_key, object_version, event_id, ciphertext_hash, writer_generation, journal_epoch, event_kind, target_count,
                data_scope_id, test_only, applied_at)
        VALUES (?, ?, ?, ?, ?, ?, 'OWNER_DELETE_ALL', ?, '${scope.id}', ${scope.testOnly}, ?)
    """.trimIndent()

    val NO_OWNED_CONTENT = "SELECT NOT EXISTS (SELECT 1 FROM complaints WHERE owner_id = ?)"
    val ALL_TOMBSTONED = """
        SELECT count(*) FROM complaint_resource_ids WHERE id = ANY (?::uuid[]) AND $LIVE AND state = 'DELETED' AND deleted_at IS NOT NULL
    """.trimIndent()
    companion object {
        val NOW get() = live.NOW
        val LOCK_RECEIPTS get() = live.LOCK_RECEIPTS
        val LOCK_PUBLICATION get() = live.LOCK_PUBLICATION
        val LOCK_RECOVERY get() = live.LOCK_RECOVERY
        val LOCK_INSTALLATION get() = live.LOCK_INSTALLATION
        val LOCK_CREDENTIAL get() = live.LOCK_CREDENTIAL
        val OWNER_TARGETS get() = live.OWNER_TARGETS
        val LOCK_RESOURCE get() = live.LOCK_RESOURCE
        val LOCK_CONTENT get() = live.LOCK_CONTENT
        val RECONSTRUCT_RESOURCE get() = live.RECONSTRUCT_RESOURCE
        val DELETE_CONTENT get() = live.DELETE_CONTENT
        val TOMBSTONE_RESOURCE get() = live.TOMBSTONE_RESOURCE
        val DELETE_INSTALLATION get() = live.DELETE_INSTALLATION
        val DELETE_CREDENTIAL get() = live.DELETE_CREDENTIAL
        val RECORD_PROGRESS get() = live.RECORD_PROGRESS
        val COMPLETE_RECEIPT get() = live.COMPLETE_RECEIPT
        val MARK_APPLIED get() = live.MARK_APPLIED
        val APPLIED get() = live.APPLIED
        val INSERT_APPLIED get() = live.INSERT_APPLIED
        val NO_OWNED_CONTENT get() = live.NO_OWNED_CONTENT
        val ALL_TOMBSTONED get() = live.ALL_TOMBSTONED
        val live = OwnerDeleteAllApplySql(ComplaintDataScope.LIVE)
        fun test(scope: ComplaintDataScope): OwnerDeleteAllApplySql {
            require(scope.testOnly && scope.id.version() == 4 && scope.id.variant() == 2)
            return OwnerDeleteAllApplySql(scope)
        }
    }

}
