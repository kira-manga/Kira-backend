package me.manga.kira.backend.complaint.infrastructure

import me.manga.kira.backend.complaint.domain.ComplaintDataScope

/** Registered native ALL recovery only. Existing committed-primary/LIVE SQL is unchanged. */
internal class OwnerDeleteAllInventorySqlV1(scope: ComplaintDataScope) {
    init { require(scope.testOnly) }
    private val scoped = "data_scope_id = '${scope.id}' AND test_only"
    val installation = """
        SELECT state, terminal_at, ($scoped) AS live,
            (complaint_finite_times(created_at, terminal_at) AND
                ((state IN ('ACTIVE', 'DELETION_PENDING', 'RECOVERY_RESERVED') AND terminal_at IS NULL)
                    OR (state = 'DELETED' AND terminal_at IS NOT NULL))) AS valid
        FROM complaint_installation_ids WHERE id = ? FOR UPDATE
    """.trimIndent()
    val credential = """
        SELECT state, credential_version, version, secret_verifier, deleted_at, verifier_expires_at, ($scoped) AS live,
            (credential_version > 0 AND version > 0 AND complaint_digest_valid(secret_verifier)
                AND complaint_finite_times(created_at, last_authenticated_at, deleted_at, verifier_expires_at) AND (
                    (state IN ('ACTIVE', 'DELETION_PENDING') AND platform IN ('ANDROID', 'IOS') AND complaint_is_v4(owner_reference)
                        AND owner_reference <> id AND last_authenticated_at IS NOT NULL AND deleted_at IS NULL AND verifier_expires_at IS NULL)
                    OR (state = 'DELETED' AND platform IS NULL AND owner_reference IS NULL AND last_authenticated_at IS NULL
                        AND deleted_at IS NOT NULL AND verifier_expires_at = deleted_at + interval '192 hours'))) AS valid
        FROM app_installations WHERE id = ? FOR UPDATE
    """.trimIndent()
    val reconstructInstallation = """
        INSERT INTO complaint_installation_ids (id, data_scope_id, test_only, state, created_at, terminal_at)
        VALUES (?, '${scope.id}', true, 'DELETED', ?, ?)
    """.trimIndent()
    val deleteInstallation = """
        UPDATE complaint_installation_ids SET state = 'DELETED', terminal_at = ?
        WHERE id = ? AND $scoped AND state IN ('ACTIVE', 'DELETION_PENDING', 'RECOVERY_RESERVED') AND terminal_at IS NULL
    """.trimIndent()
    val deleteCredential = """
        UPDATE app_installations SET state = 'DELETED', credential_version = credential_version + 1, version = version + 1,
            platform = NULL, owner_reference = NULL, last_authenticated_at = NULL, deleted_at = ?, verifier_expires_at = ?
        WHERE id = ? AND $scoped AND state IN ('ACTIVE', 'DELETION_PENDING') AND credential_version = ? AND version = ?
            AND secret_verifier = ? AND deleted_at IS NULL AND verifier_expires_at IS NULL
    """.trimIndent()
}
