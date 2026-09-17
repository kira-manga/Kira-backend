package me.manga.kira.backend.complaint.infrastructure

/** Fixed LIVE statements only. No caller-supplied relation, scope, lock expression or state transition. */
internal object OwnerDeleteAllPersistenceSql {
    private const val LIVE = "data_scope_id = '00000000-0000-0000-0000-000000000000' AND NOT test_only"

    val LOCK_RECEIPTS = """
        SELECT deletion_key, submitted_credential_version, fingerprint, state, publication_ref,
            ($LIVE) AS live,
            complaint_digest_valid(fingerprint) AND complaint_finite_times(created_at, authorized_at, completed_at, expires_at) AS valid
        FROM installation_deletion_receipts WHERE installation_id = ?
        ORDER BY deletion_key LIMIT 2 FOR UPDATE
    """.trimIndent()

    // Both the reservation FK here and the recovery publication FK below are deferred by V14.
    val INSERT_RECEIPT = """
        INSERT INTO installation_deletion_receipts
            (installation_id, deletion_key, submitted_credential_version, fingerprint, data_scope_id, test_only, state, created_at)
        VALUES (?, ?, ?, ?, '00000000-0000-0000-0000-000000000000', false, 'IN_PROGRESS', clock_timestamp())
    """.trimIndent()

    val INSERT_RECOVERY = """
        INSERT INTO complaint_recovery_capacity_reservations
            (event_id, data_scope_id, test_only, publication_ref, state, accounting_version, reserved_amounts, created_at)
        VALUES (?, '00000000-0000-0000-0000-000000000000', false, ?, 'RESERVED', 1, ?::bigint[], clock_timestamp())
    """.trimIndent()

    val LOCK_RECOVERY = """
        SELECT ($LIVE AND publication_ref = event_id AND state = 'RESERVED' AND accounting_version = 1
            AND complaint_vector_valid(reserved_amounts) AND reserved_amounts = ?::bigint[]
            AND converted_amounts IS NULL AND converted_at IS NULL AND isfinite(created_at)) AS matches
        FROM complaint_recovery_capacity_reservations WHERE event_id = ? FOR UPDATE
    """.trimIndent()

    val LOCK_INSTALLATION_ID = "SELECT state, ($LIVE) AS live FROM complaint_installation_ids WHERE id = ? FOR UPDATE"

    val LOCK_CREDENTIAL = """
        SELECT state, credential_version, secret_verifier, ($LIVE) AS live,
            (platform IS NOT NULL AND owner_reference IS NOT NULL AND last_authenticated_at IS NOT NULL
                AND complaint_digest_valid(secret_verifier) AND deleted_at IS NULL AND verifier_expires_at IS NULL
                AND version > 0 AND isfinite(created_at) AND isfinite(last_authenticated_at)) AS nonterminal
        FROM app_installations WHERE id = ? FOR UPDATE
    """.trimIndent()

    // Conservative backlog gate, deliberately global. Existing state-leading index permits bounded
    // existence probes; no publication lock, JSON scan or false per-owner indexing assumption.
    val NO_PENDING = """
        SELECT NOT EXISTS (SELECT 1 FROM complaint_journal_publications WHERE state = 'PREPARED')
            AND NOT EXISTS (SELECT 1 FROM complaint_journal_publications WHERE state = 'VERIFIED')
    """.trimIndent()

    val OWNER_TARGETS = "SELECT id FROM complaints WHERE owner_id = ? AND $LIVE ORDER BY id LIMIT 101"
    val LOCK_RESOURCES = "SELECT id, state, ($LIVE) AS live FROM complaint_resource_ids WHERE id = ANY (?::uuid[]) ORDER BY id FOR UPDATE"
    val LOCK_CONTENT = """
        SELECT id, owner_id, ownership, kind, version, ($LIVE) AS live
        FROM complaints WHERE id = ANY (?::uuid[]) ORDER BY id FOR UPDATE
    """.trimIndent()

    val PEND_ID = "UPDATE complaint_installation_ids SET state = 'DELETION_PENDING' WHERE id = ? AND $LIVE AND state = 'ACTIVE' AND terminal_at IS NULL"
    val PEND_CREDENTIAL = """
        UPDATE app_installations SET state = 'DELETION_PENDING', version = version + 1
        WHERE id = ? AND $LIVE AND state = 'ACTIVE' AND credential_version = ? AND secret_verifier = ?
            AND deleted_at IS NULL AND verifier_expires_at IS NULL
    """.trimIndent()

    val INSERT_PUBLICATION = """
        INSERT INTO complaint_journal_publications
            (event_id, data_scope_id, test_only, writer_generation, journal_epoch, event_kind, target_count,
                routing_key_id, object_key, canonicalizer, event_bytes, semantic_hash, state, created_at)
        VALUES (?, '00000000-0000-0000-0000-000000000000', false, ?, ?, 'OWNER_DELETE_ALL', ?, ?, ?, 'kcj-1', ?, ?, 'PREPARED', clock_timestamp())
        RETURNING created_at
    """.trimIndent()

    // Publication FK is NOT deferred: only update after the real PREPARED INSERT above returned.
    val AUTHORIZE_RECEIPT = """
        UPDATE installation_deletion_receipts SET state = 'AUTHORIZED_DELETE', publication_ref = ?, authorized_at = ?
        WHERE installation_id = ? AND deletion_key = ? AND $LIVE AND state = 'IN_PROGRESS'
            AND publication_ref IS NULL AND authorized_at IS NULL
    """.trimIndent()

    val LOCK_PUBLICATION = """
        SELECT event_id, writer_generation, journal_epoch, event_kind, target_count, routing_key_id, object_key,
            canonicalizer, event_bytes, semantic_hash, state, ($LIVE) AS live,
            (complaint_bytes_match(event_bytes, semantic_hash, 65536)
                AND complaint_finite_times(created_at, object_created_at, retain_until, verified_at, applied_at)
                AND applied_at IS NULL AND (
                    (state = 'PREPARED' AND object_version IS NULL AND ciphertext_hash IS NULL AND object_created_at IS NULL
                        AND retain_until IS NULL AND verified_at IS NULL AND verification_bytes IS NULL AND verification_hash IS NULL)
                    OR (state = 'VERIFIED' AND complaint_opaque_valid(object_version, 1024) AND object_version <> 'null'
                        AND complaint_digest_valid(ciphertext_hash) AND object_created_at IS NOT NULL
                        AND retain_until IS NOT NULL AND verified_at IS NOT NULL
                        AND complaint_bytes_match(verification_bytes, verification_hash, 65536))
                )) AS valid
        FROM complaint_journal_publications WHERE event_id = ? FOR UPDATE
    """.trimIndent()
}
