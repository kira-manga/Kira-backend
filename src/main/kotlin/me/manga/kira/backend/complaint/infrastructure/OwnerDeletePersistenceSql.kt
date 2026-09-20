package me.manga.kira.backend.complaint.infrastructure

/** Fixed one-target TEST SQL. Caller values never select a relation, lock order or statement grammar. */
internal object OwnerDeletePersistenceSql {
    private val ACTOR = """
        WITH actor AS (
            SELECT a.id, a.platform FROM app_installations a
            JOIN complaint_installation_ids i ON i.id = a.id AND i.data_scope_id = a.data_scope_id
            JOIN complaint_test_runs t ON t.data_scope_id = a.data_scope_id
            WHERE a.id = ?::uuid AND a.data_scope_id = ?::uuid AND a.credential_version = ?
                AND a.state = 'ACTIVE' AND i.state = 'ACTIVE' AND t.state = 'ACTIVE'
                AND a.test_only AND i.test_only AND t.test_only AND a.platform IN ('ANDROID', 'IOS')
                AND clock_timestamp() >= ?::timestamptz - interval '60 seconds'
                AND clock_timestamp() < ?::timestamptz + interval '60 seconds' AND t.configuration_hash = ?
        )
    """.trimIndent()
    val AUTHENTICATE = "$ACTOR SELECT actor.platform FROM (SELECT 1) seed LEFT JOIN actor ON true"

    private val RECEIPT_COLUMNS = """
        r.actor_id, r.idempotency_key, r.operation, r.data_scope_id, r.test_only, r.state,
        CASE WHEN cardinality(r.target_ids) = 1 THEN r.target_ids[1] END AS target_id,
        CASE WHEN octet_length(r.fingerprint) = 32 THEN r.fingerprint END AS fingerprint,
        r.outcome, r.response_status, r.completed_at, r.expires_at, r.authorized_at,
        CASE WHEN octet_length(r.publication_ref) <= 128 THEN r.publication_ref END AS publication_ref,
        CASE WHEN octet_length(r.problem_code) <= 64 THEN r.problem_code END AS problem_code,
        CASE WHEN octet_length(r.external_event_id) <= 128 THEN r.external_event_id END AS external_event_id,
        r.external_epoch,
        CASE WHEN octet_length(r.external_object_version) <= 1024 THEN r.external_object_version END AS external_object_version,
        CASE WHEN octet_length(r.external_ciphertext_hash) = 32 THEN r.external_ciphertext_hash END AS external_ciphertext_hash,
        (r.state <> 'COMPLETED' OR r.expires_at > clock_timestamp()) AS comparable,
        (r.state = 'COMPLETED' AND r.expires_at > clock_timestamp()) AS visible,
        COALESCE(complaint_finite_times(r.created_at, r.authorized_at, r.completed_at, r.expires_at)
            AND r.ack_ids IS NULL AND r.ack_versions IS NULL AND r.response_etag IS NULL
            AND r.response_location IS NULL AND r.consumed_grant_id IS NULL AND (
                (r.state = 'IN_PROGRESS' AND r.outcome IS NULL AND r.response_status IS NULL AND r.problem_code IS NULL
                    AND r.publication_ref IS NULL AND r.authorized_at IS NULL AND r.completed_at IS NULL AND r.expires_at IS NULL
                    AND r.external_event_id IS NULL AND r.external_epoch IS NULL AND r.external_object_version IS NULL AND r.external_ciphertext_hash IS NULL)
                OR (r.state = 'AUTHORIZED_DELETE' AND r.outcome IS NULL AND r.response_status IS NULL AND r.problem_code IS NULL
                    AND r.publication_ref IS NOT NULL AND octet_length(r.publication_ref) <= 128 AND r.authorized_at IS NOT NULL
                    AND r.completed_at IS NULL AND r.expires_at IS NULL AND r.external_event_id IS NULL AND r.external_epoch IS NULL
                    AND r.external_object_version IS NULL AND r.external_ciphertext_hash IS NULL)
                OR (r.state = 'COMPLETED' AND r.completed_at IS NOT NULL AND r.expires_at = r.completed_at + interval '192 hours' AND (
                    (r.outcome = 'REJECTED' AND r.publication_ref IS NULL AND r.authorized_at IS NULL
                        AND r.external_event_id IS NULL AND r.external_epoch IS NULL AND r.external_object_version IS NULL AND r.external_ciphertext_hash IS NULL
                        AND ((r.problem_code = 'COMPLAINT_NOT_FOUND' AND r.response_status = 404)
                            OR (r.problem_code = 'COMPLAINT_DELETION_PENDING' AND r.response_status = 409)
                            OR (r.problem_code = 'PRECONDITION_FAILED' AND r.response_status = 412)))
                    OR (r.outcome = 'APPLIED' AND r.response_status = 204 AND r.problem_code IS NULL
                        AND r.publication_ref = r.external_event_id AND r.authorized_at IS NOT NULL AND r.external_epoch > 0
                        AND complaint_opaque_valid(r.external_object_version, 1024) AND r.external_object_version <> 'null'
                        AND complaint_digest_valid(r.external_ciphertext_hash))
                ))
            ), false) AS valid_shape
    """.trimIndent()
    val OBSERVE = """
        $ACTOR SELECT actor.platform, $RECEIPT_COLUMNS FROM (SELECT 1) seed LEFT JOIN actor ON true
        LEFT JOIN complaint_idempotency_receipts r ON r.actor_kind = 'INSTALLATION' AND r.actor_id = actor.id AND r.idempotency_key = ?::uuid
    """.trimIndent()
    val LOCK_RECEIPT = "SELECT $RECEIPT_COLUMNS FROM complaint_idempotency_receipts r WHERE r.actor_kind = 'INSTALLATION' AND r.actor_id = ? AND r.idempotency_key = ? FOR UPDATE"
    val LOCK_REGISTERED_RECEIPT = """
        SELECT $RECEIPT_COLUMNS FROM complaint_idempotency_receipts r
        WHERE r.actor_kind = 'INSTALLATION' AND r.actor_id = ? AND r.idempotency_key = ?
            AND r.data_scope_id = ? AND r.test_only AND r.operation = 'OWNER_DELETE' FOR UPDATE
    """.trimIndent()
    // One page, not a scan or a retained-key bound. The extra row reports only locally observed work.
    const val REGISTERED_PRIMARY_PAGE_LIMIT = 2
    val SELECT_REGISTERED_PRIMARY_PAGE = """
        SELECT r.actor_id, r.idempotency_key, p.created_at,
            COALESCE(r.actor_kind = 'INSTALLATION' AND r.operation = 'OWNER_DELETE'
                AND r.state = 'AUTHORIZED_DELETE' AND r.data_scope_id = s.scope AND r.test_only
                AND p.data_scope_id = s.scope AND p.test_only AND p.event_kind = 'OWNER_DELETE'
                AND p.target_count = 1 AND p.canonicalizer = 'kcj-1' AND p.writer_generation = ?::uuid
                AND p.state IN ('PREPARED', 'VERIFIED') AND r.authorized_at = p.created_at
                AND isfinite(p.created_at), false) AS valid
        FROM complaint_idempotency_receipts r
        FULL JOIN complaint_journal_publications p ON p.event_id = r.publication_ref
        CROSS JOIN (SELECT ?::uuid AS scope) s
        WHERE (r.data_scope_id = s.scope AND r.operation = 'OWNER_DELETE' AND r.state <> 'COMPLETED')
            OR (p.data_scope_id = s.scope AND p.event_kind = 'OWNER_DELETE' AND p.state <> 'APPLIED')
        ORDER BY COALESCE(p.created_at, r.authorized_at, r.created_at),
            COALESCE(p.event_id, r.publication_ref) COLLATE "C", r.actor_kind COLLATE "C", r.actor_id, r.idempotency_key
        LIMIT ${REGISTERED_PRIMARY_PAGE_LIMIT + 1}
    """.trimIndent()
    val INSERT_CLAIM = """
        INSERT INTO complaint_idempotency_receipts
            (actor_kind, actor_id, idempotency_key, operation, fingerprint, target_ids, data_scope_id, test_only, state, created_at)
        VALUES ('INSTALLATION', ?, ?, 'OWNER_DELETE', ?, ARRAY[?::uuid], ?, true, 'IN_PROGRESS', clock_timestamp())
        ON CONFLICT (actor_kind, actor_id, idempotency_key) DO NOTHING
    """.trimIndent()
    val REJECT_RECEIPT = """
        WITH stamp AS (SELECT clock_timestamp() AS at) UPDATE complaint_idempotency_receipts
        SET state = 'COMPLETED', outcome = 'REJECTED', response_status = ?, problem_code = ?, completed_at = stamp.at,
            expires_at = stamp.at + interval '192 hours'
        FROM stamp WHERE actor_kind = 'INSTALLATION' AND actor_id = ? AND idempotency_key = ? AND data_scope_id = ? AND test_only
            AND operation = 'OWNER_DELETE' AND state = 'IN_PROGRESS' AND fingerprint = ? AND target_ids = ARRAY[?::uuid]
    """.trimIndent()
    val AUTHORIZE_RECEIPT = """
        UPDATE complaint_idempotency_receipts SET state = 'AUTHORIZED_DELETE', publication_ref = ?, authorized_at = ?
        WHERE actor_kind = 'INSTALLATION' AND actor_id = ? AND idempotency_key = ? AND data_scope_id = ? AND test_only
            AND operation = 'OWNER_DELETE' AND state = 'IN_PROGRESS' AND fingerprint = ? AND target_ids = ARRAY[?::uuid]
    """.trimIndent()
    val COMPLETE_RECEIPT = """
        WITH stamp AS (SELECT clock_timestamp() AS at) UPDATE complaint_idempotency_receipts
        SET state = 'COMPLETED', outcome = 'APPLIED', response_status = 204, external_event_id = publication_ref,
            external_epoch = ?, external_object_version = ?, external_ciphertext_hash = ?, completed_at = stamp.at,
            expires_at = stamp.at + interval '192 hours'
        FROM stamp WHERE actor_kind = 'INSTALLATION' AND actor_id = ? AND idempotency_key = ? AND data_scope_id = ? AND test_only
            AND operation = 'OWNER_DELETE' AND state = 'AUTHORIZED_DELETE' AND publication_ref = ? AND fingerprint = ? AND target_ids = ARRAY[?::uuid]
    """.trimIndent()
    const val LOCK_INSTALLATION = "SELECT data_scope_id, test_only, state FROM complaint_installation_ids WHERE id = ? FOR UPDATE"
    val LOCK_CREDENTIAL = """
        SELECT data_scope_id, test_only, state, credential_version, platform FROM app_installations WHERE id = ? FOR UPDATE
    """.trimIndent()
    const val TOKEN_TIME = "SELECT clock_timestamp() >= ?::timestamptz - interval '60 seconds' AND clock_timestamp() < ?::timestamptz + interval '60 seconds'"
    val CANDIDATE = """
        SELECT EXISTS (SELECT 1 FROM complaints WHERE id = ? AND data_scope_id = ? AND test_only AND owner_id = ?
            AND ownership = 'INSTALLATION' AND kind IN ('REPORT', 'REPLY'))
    """.trimIndent()
    const val LOCK_RESOURCE = "SELECT data_scope_id, test_only, state FROM complaint_resource_ids WHERE id = ? FOR UPDATE"
    const val LOCK_CONTENT = "SELECT data_scope_id, test_only, owner_id, ownership, kind, version FROM complaints WHERE id = ? FOR UPDATE"
    val PEND_RESOURCE = """
        UPDATE complaint_resource_ids SET state = 'DELETION_PENDING' WHERE id = ? AND data_scope_id = ? AND test_only AND state = 'LIVE'
    """.trimIndent()
    val INSERT_PUBLICATION = """
        INSERT INTO complaint_journal_publications
            (event_id, data_scope_id, test_only, writer_generation, journal_epoch, event_kind, target_count,
                routing_key_id, object_key, canonicalizer, event_bytes, semantic_hash, state, created_at)
        VALUES (?, ?, true, ?, ?, 'OWNER_DELETE', 1, ?, ?, 'kcj-1', ?, ?, 'PREPARED', clock_timestamp()) RETURNING created_at
    """.trimIndent()
    private val PUBLICATION_COLUMNS = """
        event_id, data_scope_id, test_only, writer_generation, journal_epoch, event_kind, target_count, routing_key_id, object_key,
        canonicalizer, state, created_at,
        CASE WHEN octet_length(object_version) BETWEEN 1 AND 1024 THEN object_version END AS object_version,
        CASE WHEN octet_length(ciphertext_hash) = 32 THEN ciphertext_hash END AS ciphertext_hash,
        object_created_at, retain_until, verified_at, applied_at,
        CASE WHEN octet_length(event_bytes) BETWEEN 1 AND 65536 THEN event_bytes END AS event_bytes,
        CASE WHEN octet_length(semantic_hash) = 32 THEN semantic_hash END AS semantic_hash,
        CASE WHEN octet_length(verification_bytes) BETWEEN 1 AND 65536 THEN verification_bytes END AS verification_bytes,
        CASE WHEN octet_length(verification_hash) = 32 THEN verification_hash END AS verification_hash,
        COALESCE(complaint_bytes_match(event_bytes, semantic_hash, 65536)
            AND complaint_finite_times(created_at, object_created_at, retain_until, verified_at, applied_at)
            AND ((state = 'PREPARED' AND object_version IS NULL AND ciphertext_hash IS NULL AND object_created_at IS NULL
                AND retain_until IS NULL AND verified_at IS NULL AND verification_bytes IS NULL AND verification_hash IS NULL AND applied_at IS NULL)
            OR (state IN ('VERIFIED', 'APPLIED') AND complaint_opaque_valid(object_version, 1024) AND object_version <> 'null'
                AND complaint_digest_valid(ciphertext_hash) AND object_created_at IS NOT NULL AND retain_until IS NOT NULL AND verified_at IS NOT NULL
                AND complaint_bytes_match(verification_bytes, verification_hash, 65536) AND ((state = 'APPLIED') = (applied_at IS NOT NULL)))), false) AS valid_shape
    """.trimIndent()
    val LOCK_PUBLICATION = "SELECT $PUBLICATION_COLUMNS FROM complaint_journal_publications WHERE event_id = ? FOR UPDATE"
    val RECORD_VERIFIED = """
        UPDATE complaint_journal_publications SET state = 'VERIFIED', object_version = ?, ciphertext_hash = ?, object_created_at = ?,
            retain_until = ?, verified_at = ?, verification_bytes = ?, verification_hash = ?
        WHERE event_id = ? AND data_scope_id = ? AND test_only AND event_kind = 'OWNER_DELETE' AND state = 'PREPARED'
            AND semantic_hash = ? RETURNING $PUBLICATION_COLUMNS
    """.trimIndent()
    val MARK_APPLIED = """
        UPDATE complaint_journal_publications SET state = 'APPLIED', applied_at = clock_timestamp()
        WHERE event_id = ? AND data_scope_id = ? AND test_only AND event_kind = 'OWNER_DELETE' AND state = 'VERIFIED'
            AND object_version = ? AND ciphertext_hash = ? AND verification_hash = ? AND retain_until > clock_timestamp()
    """.trimIndent()
    val INSERT_RECOVERY = """
        INSERT INTO complaint_recovery_capacity_reservations
            (event_id, data_scope_id, test_only, publication_ref, state, accounting_version, reserved_amounts, created_at)
        VALUES (?, ?, true, ?, 'RESERVED', 1, ?::bigint[], clock_timestamp())
    """.trimIndent()
    val DROP_PROVISIONAL_RECOVERY = """
        DELETE FROM complaint_recovery_capacity_reservations WHERE event_id = ? AND data_scope_id = ? AND test_only
            AND state = 'RESERVED' AND publication_ref = event_id AND reserved_amounts = ?::bigint[]
            AND converted_amounts IS NULL AND converted_at IS NULL
    """.trimIndent()
    val LOCK_RECOVERY = """
        SELECT event_id, data_scope_id, test_only, publication_ref, state, accounting_version,
            CASE WHEN complaint_vector_valid(reserved_amounts) THEN reserved_amounts END AS reserved_amounts,
            CASE WHEN complaint_vector_valid(converted_amounts) THEN converted_amounts END AS converted_amounts,
            converted_at, complaint_finite_times(created_at, converted_at) AS finite FROM complaint_recovery_capacity_reservations WHERE event_id = ? FOR UPDATE
    """.trimIndent()
    val SPEND_RECOVERY = """
        UPDATE complaint_recovery_capacity_reservations SET state = 'PARTIAL', converted_amounts = ?::bigint[], converted_at = clock_timestamp()
        WHERE event_id = ? AND data_scope_id = ? AND test_only AND state IN ('RESERVED', 'PARTIAL')
            AND reserved_amounts = ?::bigint[] AND converted_amounts IS NOT DISTINCT FROM ?::bigint[] AND converted_at IS NOT DISTINCT FROM ?::timestamptz
    """.trimIndent()
    val READ_APPLIED_FAMILY = """
        SELECT event_id, data_scope_id, test_only, writer_generation, journal_epoch, event_kind, target_count, object_key, object_version,
            ciphertext_hash, isfinite(applied_at) AS finite FROM complaint_deletion_journal_applied
        WHERE event_id = ANY (?::text[]) ORDER BY event_id, object_key, object_version LIMIT 5
    """.trimIndent()
    val LOCK_APPLIED = """
        SELECT event_id, data_scope_id, test_only, writer_generation, journal_epoch, event_kind, target_count, object_key, object_version,
            ciphertext_hash, isfinite(applied_at) AS finite FROM complaint_deletion_journal_applied
        WHERE object_key = ? AND object_version = ? FOR UPDATE
    """.trimIndent()
    val INSERT_APPLIED = """
        INSERT INTO complaint_deletion_journal_applied
            (object_key, object_version, event_id, ciphertext_hash, writer_generation, journal_epoch, event_kind, target_count,
                data_scope_id, test_only, applied_at)
        VALUES (?, ?, ?, ?, ?, ?, 'OWNER_DELETE', 1, ?, true, clock_timestamp())
    """.trimIndent()
    val DELETE_CONTENT = "DELETE FROM complaints WHERE id = ? AND data_scope_id = ? AND test_only AND owner_id = ? AND ownership = 'INSTALLATION' AND kind IN ('REPORT', 'REPLY') AND version = ?"
    val DELETE_RESOURCE = "UPDATE complaint_resource_ids SET state = 'DELETED', deleted_at = clock_timestamp() WHERE id = ? AND data_scope_id = ? AND test_only AND state IN ('LIVE', 'DELETION_PENDING')"
    val RECONSTRUCT_RESOURCE = "INSERT INTO complaint_resource_ids(id, data_scope_id, test_only, state, created_at, deleted_at) VALUES (?, ?, true, 'DELETED', clock_timestamp(), clock_timestamp())"
    val RECONSTRUCT_INSTALLATION = "INSERT INTO complaint_installation_ids(id, data_scope_id, test_only, state, created_at) VALUES (?, ?, true, 'RECOVERY_RESERVED', clock_timestamp())"
}
