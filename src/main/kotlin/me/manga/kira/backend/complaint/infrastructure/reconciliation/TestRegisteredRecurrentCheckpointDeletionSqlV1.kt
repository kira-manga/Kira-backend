package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.infrastructure.AdminDeletePersistenceSql
import me.manga.kira.backend.complaint.infrastructure.OwnerDeletePersistenceSql

/** Fixed passive comparisons, under the original deletion holder's global→scope locks. */
internal object TestRegisteredRecurrentCheckpointDeletionSqlV1 {
    const val PAGE = 32
    val branch = """
        SELECT c.rotation_sequence,
            (c.rotation_sequence <> 1 OR (
                NOT EXISTS (SELECT 1 FROM complaint_test_active_recurrent_seal_intents i WHERE i.data_scope_id = c.data_scope_id)
                AND NOT EXISTS (SELECT 1 FROM complaint_test_active_checkpoint_history h WHERE h.data_scope_id = c.data_scope_id))) IS TRUE AS valid
        FROM complaint_journal_control c WHERE c.data_scope_id = ?::uuid AND c.test_only LIMIT 2
    """.trimIndent()

    // Privacy deletion is not CREATE eligibility. A completed B holder may advance the lease
    // token without replacing this exact checkpoint; a live lease or partial checkpoint refuses.
    val current = """
        WITH observed AS MATERIALIZED (
        ${TestActiveRecurrentSqlV1.read}
        )
        SELECT o.*,
            (o.valid AND c.test_only AND NOT g.scan_requested
                AND c.rotation_sequence BETWEEN 2 AND 14 AND c.rotation_state = 'CAPTURED' AND NOT c.scan_requested
                AND c.lease_owner IS NULL AND c.lease_expires_at IS NULL
                AND c.seal_state = 'SEAL_VERIFIED' AND c.publication_epoch = c.seal_epoch + 1
                AND c.checkpoint_result = 'SUCCESS' AND c.checkpoint_fencing_token <= c.lease_token
                AND c.checkpoint_cutoff_epoch = c.seal_epoch AND c.checkpoint_started_at >= c.seal_verified_at
                AND c.checkpoint_completed_at >= c.checkpoint_started_at AND c.checkpoint_completed_at <= c.updated_at
                AND c.checkpoint_completed_at <= o.sampled_at
                AND EXISTS (SELECT 1 FROM complaint_test_active_checkpoint_history h
                    WHERE h.data_scope_id = c.data_scope_id AND h.test_only AND h.ordinal = c.rotation_sequence
                        AND h.operation_token = c.rotation_id AND h.source = 'V31_RECURRENT'
                        AND h.initial_seal_token IS NULL AND h.recurrent_seal_token = c.rotation_id
                        AND h.checkpoint_bytes = c.checkpoint_bytes AND h.checkpoint_hash = c.checkpoint_hash
                        AND h.checkpointed_at = c.checkpoint_completed_at)
                AND NOT EXISTS (SELECT 1 FROM complaint_journal_scan_runs s WHERE s.data_scope_id = c.data_scope_id)
                AND NOT EXISTS (SELECT 1 FROM complaint_journal_scan_entries s WHERE s.data_scope_id = c.data_scope_id)
            ) IS TRUE AS deletion_valid
        FROM observed o
        LEFT JOIN complaint_journal_control c ON c.data_scope_id = ?::uuid
        LEFT JOIN complaint_journal_control g ON g.data_scope_id = '00000000-0000-0000-0000-000000000000'::uuid
        LIMIT 2
    """.trimIndent()
    val initialHeaders = TestActiveRecurrentSqlV1.initialHeaders.removeSuffix(" FOR UPDATE")
    val recurrentHeaders = TestActiveRecurrentSqlV1.recurrentHeaders.removeSuffix(" FOR UPDATE")
    val initialPayload = TestActiveRecurrentSqlV1.initialPayload
    val recurrentPayload = TestActiveRecurrentSqlV1.recurrentPayload
    val history = TestActiveRecurrentSqlV1.history.removeSuffix(" FOR UPDATE")

    /** Exactly the concrete new claim's17 comparisons; historical rows never supply these values. */
    val owned = """
        WITH b AS (SELECT ?::uuid AS scope, ?::uuid AS writer, ?::bigint AS epoch),
        o AS (SELECT ?::text AS family, ?::uuid AS actor_id, ?::uuid AS operation_key,
            ?::bytea AS fingerprint, ?::bigint AS credential_version, ?::uuid[] AS target_ids,
            ?::text AS event_id, ?::text AS object_key, ?::text AS routing_key_id, ?::bytea AS event_bytes, ?::bytea AS semantic_hash,
            ?::bigint[] AS recovery, ?::boolean AS reservation_inserted, ?::timestamptz AS authorized_at,
            ?::uuid AS consumed_grant_id, ?::text AS rejection, ?::integer AS rejection_status),
        s AS (SELECT clock_timestamp() AS at)
        SELECT (o.family IN ('OWNER_DELETE','OWNER_DELETE_ALL','ADMIN_DELETE','ADMIN_BATCH_DELETE') AND
            CASE WHEN o.family = 'OWNER_DELETE_ALL' THEN
                (SELECT count(*) FROM (SELECT 1 FROM installation_deletion_receipts n WHERE n.installation_id = o.actor_id LIMIT 2) x) = 1
                AND EXISTS (SELECT 1 FROM installation_deletion_receipts n WHERE n.installation_id = o.actor_id
                    AND n.data_scope_id = b.scope AND n.test_only AND n.deletion_key = o.operation_key
                    AND n.submitted_credential_version = o.credential_version AND n.fingerprint = o.fingerprint
                    AND n.outcome IS NULL AND n.response_status IS NULL AND n.completed_at IS NULL AND n.expires_at IS NULL
                    AND n.external_event_id IS NULL AND n.external_epoch IS NULL AND n.external_object_version IS NULL AND n.external_ciphertext_hash IS NULL
                    AND complaint_finite_times(n.created_at, n.authorized_at) AND n.created_at <= s.at
                    AND ((o.authorized_at IS NULL AND n.state = 'IN_PROGRESS' AND n.authorized_at IS NULL AND n.publication_ref IS NULL)
                        OR (o.authorized_at IS NOT NULL AND n.state = 'AUTHORIZED_DELETE' AND n.authorized_at = o.authorized_at AND n.publication_ref = o.event_id)))
            ELSE EXISTS (SELECT 1 FROM complaint_idempotency_receipts n
                WHERE n.actor_kind = CASE WHEN o.family = 'OWNER_DELETE' THEN 'INSTALLATION' ELSE 'ADMIN' END
                    AND n.actor_id = o.actor_id AND n.idempotency_key = o.operation_key
                    AND n.data_scope_id = b.scope AND n.test_only AND n.operation = o.family
                    AND n.fingerprint = o.fingerprint AND n.target_ids = o.target_ids
                    AND n.ack_ids IS NULL AND n.ack_versions IS NULL AND n.response_etag IS NULL AND n.response_location IS NULL
                    AND n.external_event_id IS NULL AND n.external_epoch IS NULL AND n.external_object_version IS NULL AND n.external_ciphertext_hash IS NULL
                    AND complaint_finite_times(n.created_at, n.authorized_at, n.completed_at, n.expires_at) AND n.created_at <= s.at
                    AND ((o.authorized_at IS NOT NULL AND o.rejection IS NULL AND n.state = 'AUTHORIZED_DELETE'
                        AND n.publication_ref = o.event_id AND n.authorized_at = o.authorized_at
                        AND n.consumed_grant_id IS NOT DISTINCT FROM o.consumed_grant_id
                        AND n.outcome IS NULL AND n.response_status IS NULL AND n.problem_code IS NULL AND n.completed_at IS NULL AND n.expires_at IS NULL)
                    OR (o.authorized_at IS NULL AND o.rejection IS NULL AND n.state = 'IN_PROGRESS'
                        AND n.publication_ref IS NULL AND n.authorized_at IS NULL AND n.consumed_grant_id IS NULL
                        AND n.outcome IS NULL AND n.response_status IS NULL AND n.problem_code IS NULL AND n.completed_at IS NULL AND n.expires_at IS NULL)
                    OR (o.authorized_at IS NULL AND o.rejection IS NOT NULL AND n.state = 'COMPLETED' AND n.outcome = 'REJECTED'
                        AND n.problem_code = o.rejection AND n.response_status = o.rejection_status
                        AND n.consumed_grant_id IS NOT DISTINCT FROM o.consumed_grant_id
                        AND n.publication_ref IS NULL AND n.authorized_at IS NULL AND n.completed_at <= s.at
                        AND n.expires_at = n.completed_at + interval '192 hours')))
            END
            AND (SELECT count(*) FROM (SELECT 1 FROM complaint_journal_publications p
                WHERE p.event_id = o.event_id OR p.object_key = o.object_key LIMIT 2) x) = CASE WHEN o.authorized_at IS NULL THEN 0 ELSE 1 END
            AND NOT EXISTS (SELECT 1 FROM complaint_journal_publications p WHERE (p.event_id = o.event_id OR p.object_key = o.object_key)
                AND NOT ((p.data_scope_id = b.scope AND p.test_only AND p.event_id = o.event_id AND p.object_key = o.object_key
                    AND p.writer_generation = b.writer AND p.journal_epoch = b.epoch AND p.event_kind = o.family AND p.routing_key_id = o.routing_key_id
                    AND p.target_count = cardinality(o.target_ids) AND p.canonicalizer = 'kcj-1'
                    AND p.event_bytes = o.event_bytes AND p.semantic_hash = o.semantic_hash
                    AND complaint_bytes_match(p.event_bytes, p.semantic_hash, 65536) AND p.created_at = o.authorized_at
                    AND p.state = 'PREPARED' AND p.object_version IS NULL AND p.ciphertext_hash IS NULL AND p.object_created_at IS NULL
                    AND p.retain_until IS NULL AND p.verified_at IS NULL AND p.verification_bytes IS NULL AND p.verification_hash IS NULL AND p.applied_at IS NULL) IS TRUE))
            AND (SELECT count(*) FROM (SELECT 1 FROM complaint_recovery_capacity_reservations l
                WHERE l.event_id = o.event_id OR l.publication_ref = o.event_id LIMIT 2) x) = CASE WHEN o.reservation_inserted THEN 1 ELSE 0 END
            AND NOT EXISTS (SELECT 1 FROM complaint_recovery_capacity_reservations l WHERE (l.event_id = o.event_id OR l.publication_ref = o.event_id)
                AND NOT ((o.reservation_inserted AND l.data_scope_id = b.scope AND l.test_only AND l.event_id = o.event_id
                    AND l.publication_ref = o.event_id AND l.state = 'RESERVED' AND l.accounting_version = 1
                    AND l.reserved_amounts = o.recovery AND complaint_vector_valid(l.reserved_amounts)
                    AND l.converted_amounts IS NULL AND l.converted_at IS NULL AND isfinite(l.created_at) AND l.created_at <= s.at) IS TRUE))
            AND NOT EXISTS (SELECT 1 FROM complaint_deletion_journal_applied a WHERE a.event_id = o.event_id OR a.object_key = o.object_key)
        ) IS TRUE AS valid FROM b CROSS JOIN o CROSS JOIN s
    """.trimIndent()

    // Scalar/count-only bounded materializations. Limits are retained declaration/P ceilings +
    // one sentinel; no unbounded relation or payload inventory is transferred or materialized.
    val historyCounts = """
        WITH b AS (SELECT ?::uuid AS scope, ?::text AS prefix, ?::bigint AS event_limit, ?::bigint AS normal_limit, ?::bigint AS all_limit),
        p AS MATERIALIZED (SELECT CASE WHEN octet_length(p.event_id)=43 THEN p.event_id END AS event_id FROM complaint_journal_publications p,b
            WHERE p.data_scope_id = b.scope OR starts_with(p.object_key,b.prefix) LIMIT (SELECT event_limit FROM b)),
        n AS MATERIALIZED (SELECT 1 FROM complaint_idempotency_receipts n,b
            WHERE (n.data_scope_id = b.scope AND n.operation IN ('OWNER_DELETE','ADMIN_DELETE','ADMIN_BATCH_DELETE'))
                OR n.publication_ref IN (SELECT event_id FROM p) OR n.external_event_id IN (SELECT event_id FROM p)
            LIMIT (SELECT normal_limit FROM b)),
        all_n AS MATERIALIZED (SELECT 1 FROM installation_deletion_receipts n,b
            WHERE n.data_scope_id = b.scope OR n.publication_ref IN (SELECT event_id FROM p) OR n.external_event_id IN (SELECT event_id FROM p)
            LIMIT (SELECT all_limit FROM b)),
        l AS MATERIALIZED (SELECT 1 FROM complaint_recovery_capacity_reservations l,b
            WHERE l.data_scope_id = b.scope OR l.event_id IN (SELECT event_id FROM p) OR l.publication_ref IN (SELECT event_id FROM p)
            LIMIT (SELECT event_limit FROM b)),
        a AS MATERIALIZED (SELECT 1 FROM complaint_deletion_journal_applied a,b
            WHERE a.data_scope_id = b.scope OR starts_with(a.object_key,b.prefix) OR a.event_id IN (SELECT event_id FROM p)
            LIMIT (SELECT event_limit FROM b)),
        rejected_owner AS (SELECT r.created_at, ${OwnerDeletePersistenceSql.RECEIPT_COLUMNS}
            FROM complaint_idempotency_receipts r,b WHERE r.data_scope_id = b.scope AND r.test_only
                AND r.actor_kind = 'INSTALLATION' AND r.operation = 'OWNER_DELETE' AND r.outcome = 'REJECTED'
                AND complaint_is_v4(r.actor_id) AND complaint_is_v4(r.idempotency_key) AND complaint_digest_valid(r.fingerprint)
                AND complaint_uuid_array_valid(r.target_ids,1,1) LIMIT (SELECT normal_limit FROM b)),
        rejected_admin AS (SELECT r.created_at, ${AdminDeletePersistenceSql.RECEIPT_COLUMNS}
            FROM complaint_idempotency_receipts r,b WHERE r.data_scope_id = b.scope AND r.test_only
                AND r.actor_kind = 'ADMIN' AND r.operation IN ('ADMIN_DELETE','ADMIN_BATCH_DELETE') AND r.outcome = 'REJECTED'
                AND r.actor_id <> '00000000-0000-0000-0000-000000000000'::uuid AND complaint_is_v4(r.idempotency_key)
                AND complaint_digest_valid(r.fingerprint) LIMIT (SELECT normal_limit FROM b))
        SELECT (SELECT count(*) FROM p) AS publications, (SELECT count(*) FROM n) AS receipts,
            (SELECT count(*) FROM all_n) AS all_receipts, (SELECT count(*) FROM l) AS reservations, (SELECT count(*) FROM a) AS applied,
            ((SELECT count(*) FROM rejected_owner WHERE valid_shape AND state = 'COMPLETED'
                AND created_at >= '1970-01-01T00:00:00Z'::timestamptz AND created_at <= completed_at AND completed_at <= clock_timestamp()) +
                (SELECT count(*) FROM rejected_admin WHERE valid_shape AND state = 'COMPLETED'
                    AND created_at >= '1970-01-01T00:00:00Z'::timestamptz AND created_at <= completed_at AND completed_at <= clock_timestamp()
                    AND CASE WHEN complaint_uuid_array_valid(target_ids,1,50) THEN
                        target_ids = ARRAY(SELECT id FROM unnest(target_ids) id ORDER BY id::text COLLATE "C")
                        AND NOT EXISTS (SELECT 1 FROM unnest(target_ids) id WHERE NOT complaint_is_v4(id)) ELSE false END)) AS rejections,
            NOT EXISTS (SELECT 1 FROM complaint_deletion_journal_retirements t,b
                WHERE t.data_scope_id = b.scope OR starts_with(t.object_key,b.prefix)
                    OR EXISTS (SELECT 1 FROM complaint_deletion_journal_applied a
                        WHERE a.object_key = t.object_key AND a.object_version = t.object_version
                            AND a.data_scope_id = t.data_scope_id AND a.event_kind = t.event_kind
                            AND a.event_id IN (SELECT event_id FROM p))) AS no_retirements
    """.trimIndent()

    /** No state/family filter can hide pending, malformed or foreign-prefix work. */
    val publications = """
        WITH p AS (SELECT CASE WHEN octet_length(event_id)=43 THEN event_id END AS event_id
            FROM complaint_journal_publications WHERE data_scope_id = ?::uuid OR starts_with(object_key,?::text))
        SELECT event_id FROM p WHERE event_id IS NULL OR (event_id IS DISTINCT FROM ?::text
            AND (?::text IS NULL OR event_id COLLATE "C" > ?::text COLLATE "C"))
        ORDER BY event_id COLLATE "C" NULLS FIRST LIMIT $PAGE
    """.trimIndent()
    val publication = OwnerDeletePersistenceSql.LOCK_PUBLICATION.removeSuffix(" FOR UPDATE")
    val ownerReceipt = "SELECT r.created_at, ${OwnerDeletePersistenceSql.RECEIPT_COLUMNS} FROM complaint_idempotency_receipts r " +
        "WHERE r.actor_kind = 'INSTALLATION' AND r.actor_id = ?::uuid AND r.idempotency_key = ?::uuid LIMIT 2"
    val adminReceipt = "SELECT r.created_at, ${AdminDeletePersistenceSql.RECEIPT_COLUMNS} FROM complaint_idempotency_receipts r " +
        "WHERE r.actor_kind = 'ADMIN' AND r.actor_id = ?::uuid AND r.idempotency_key = ?::uuid LIMIT 2"
    // Retain the existing ALL shape/decoder, but bound variable-length output before JDBC copies it.
    fun allReceipt(scope: ComplaintDataScope) = """
        SELECT deletion_key,created_at,submitted_credential_version,authorized_at,completed_at,expires_at,external_epoch,live,valid,
            CASE WHEN octet_length(state) BETWEEN 1 AND 32 THEN state END AS state,
            CASE WHEN octet_length(fingerprint)=32 THEN fingerprint END AS fingerprint,
            CASE WHEN octet_length(publication_ref)=43 THEN publication_ref END AS publication_ref,
            CASE WHEN octet_length(external_event_id)=43 THEN external_event_id END AS external_event_id,
            CASE WHEN octet_length(external_object_version) BETWEEN 1 AND 1024 THEN external_object_version END AS external_object_version,
            CASE WHEN octet_length(external_ciphertext_hash)=32 THEN external_ciphertext_hash END AS external_ciphertext_hash
        FROM (${TestActiveRecurrentSqlV1.retainedAllReceipt(scope)}) n
    """.trimIndent()
    val recovery = "SELECT r.created_at, q.* FROM (${OwnerDeletePersistenceSql.LOCK_RECOVERY.removeSuffix(" FOR UPDATE")}) q " +
        "JOIN complaint_recovery_capacity_reservations r ON r.event_id = q.event_id"

    // Each bound family must account for exactly one primary/N/L, including foreign aliases.
    // Scope matching is checked by the typed readers; counts deliberately do NOT hide outsiders.
    val family = """
        WITH b AS (SELECT ?::text[] AS ids, ?::text[] AS keys)
        SELECT (SELECT count(*) FROM (SELECT 1 FROM complaint_journal_publications p,b
                WHERE p.event_id = ANY(b.ids) OR p.object_key = ANY(b.keys) LIMIT 2) x) AS publications,
            (SELECT count(*) FROM (SELECT 1 FROM complaint_idempotency_receipts n,b
                WHERE n.publication_ref = ANY(b.ids) OR n.external_event_id = ANY(b.ids) LIMIT 2) x) AS receipts,
            (SELECT count(*) FROM (SELECT 1 FROM installation_deletion_receipts n,b
                WHERE n.publication_ref = ANY(b.ids) OR n.external_event_id = ANY(b.ids) LIMIT 2) x) AS all_receipts,
            (SELECT count(*) FROM (SELECT 1 FROM complaint_recovery_capacity_reservations l,b
                WHERE l.event_id = ANY(b.ids) OR l.publication_ref = ANY(b.ids) LIMIT 2) x) AS reservations,
            NOT EXISTS (SELECT 1 FROM complaint_deletion_journal_retirements t,b
                WHERE EXISTS (SELECT 1 FROM complaint_deletion_journal_applied a
                    WHERE a.object_key = t.object_key AND a.object_version = t.object_version
                        AND a.data_scope_id = t.data_scope_id AND a.event_kind = t.event_kind
                        AND a.event_id = ANY(b.ids)) OR t.object_key = ANY(b.keys)) AS no_retirements
    """.trimIndent()
    val appliedFamily = """
        SELECT data_scope_id,test_only,writer_generation,journal_epoch,target_count,applied_at,
            CASE WHEN octet_length(event_kind) BETWEEN 1 AND 32 THEN event_kind END AS event_kind,
            CASE WHEN octet_length(event_id)=43 THEN event_id END AS event_id,
            CASE WHEN octet_length(object_key) BETWEEN 1 AND 1024 THEN object_key END AS object_key,
            CASE WHEN octet_length(object_version) BETWEEN 1 AND 1024 THEN object_version END AS object_version,
            CASE WHEN octet_length(ciphertext_hash)=32 THEN ciphertext_hash END AS ciphertext_hash
        FROM complaint_deletion_journal_applied
        WHERE event_id = ANY(?::text[]) OR object_key = ANY(?::text[])
        ORDER BY object_key COLLATE "C",object_version COLLATE "C" LIMIT 5
    """.trimIndent()
}
