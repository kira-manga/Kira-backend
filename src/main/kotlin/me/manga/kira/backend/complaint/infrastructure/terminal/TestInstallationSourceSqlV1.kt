package me.manga.kira.backend.complaint.infrastructure.terminal

/** Read-only complete reservation source under the existing SEALED run lock; no disposition or credential write. */
internal object TestInstallationSourceSqlV1 {
    // Join by ID alone: a mismatched credential must be rejected, not hidden by a scope/state join predicate.
    val page = """
        SELECT i.id, i.data_scope_id, i.test_only, i.state, i.created_at, i.terminal_at, i.xmin::text AS reservation_stamp,
            c.id AS credential_id, c.data_scope_id AS credential_scope, c.test_only AS credential_test_only,
            c.state AS credential_state, c.credential_version, c.version AS credential_row_version,
            c.xmin::text AS credential_stamp,
            (complaint_is_v4(i.id) AND complaint_scope_valid(i.data_scope_id, i.test_only)
                AND i.created_at IS NOT NULL AND complaint_finite_times(i.created_at, i.terminal_at)
                AND ((i.state IN ('ACTIVE', 'DELETION_PENDING', 'RECOVERY_RESERVED') AND i.terminal_at IS NULL)
                    OR (i.state IN ('RETIRED', 'DELETED') AND i.terminal_at IS NOT NULL))) IS TRUE AS reservation_valid,
            (c.id IS NULL OR (complaint_scope_valid(c.data_scope_id, c.test_only)
                AND c.credential_version > 0 AND c.version > 0 AND complaint_digest_valid(c.secret_verifier)
                AND c.created_at IS NOT NULL
                AND complaint_finite_times(c.created_at, c.last_authenticated_at, c.deleted_at, c.verifier_expires_at)
                AND ((c.state IN ('ACTIVE', 'DELETION_PENDING') AND c.platform IN ('ANDROID', 'IOS')
                        AND complaint_is_v4(c.owner_reference) AND c.owner_reference <> c.id
                        AND c.last_authenticated_at IS NOT NULL AND c.deleted_at IS NULL AND c.verifier_expires_at IS NULL)
                    OR (c.state = 'DELETED' AND c.platform IS NULL AND c.owner_reference IS NULL
                        AND c.last_authenticated_at IS NULL AND c.deleted_at IS NOT NULL
                        AND c.verifier_expires_at = c.deleted_at + interval '192 hours')))) IS TRUE AS credential_valid
        FROM complaint_installation_ids i LEFT JOIN app_installations c ON c.id = i.id
        WHERE i.data_scope_id = ?::uuid AND (?::uuid IS NULL OR i.id > ?::uuid)
        ORDER BY i.id LIMIT ${TestOrdinarySealSqlV1.PAGE}
    """.trimIndent()

    // The reservation-led page must not omit credential-only or cross-scope rows belonging to this run.
    val credentialsComplete = """
        SELECT NOT EXISTS (
            SELECT 1 FROM app_installations c LEFT JOIN complaint_installation_ids i ON i.id = c.id
            WHERE c.data_scope_id = ?::uuid AND
                (i.id IS NULL OR i.data_scope_id <> c.data_scope_id OR NOT i.test_only OR NOT c.test_only)
        ) AS valid
    """.trimIndent()
}
