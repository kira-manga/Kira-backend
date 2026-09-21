package me.manga.kira.backend.complaint.infrastructure.terminal

import me.manga.kira.backend.complaint.infrastructure.OwnerDeletePersistenceSql

/** Exact initial-registry purge producer, not a generic terminal-kind SQL surface. */
internal object TestRunPurgeSqlV1 {
    val acquire = TestOrdinarySealSqlV1.acquire
    val release = TestInstallationManifestSqlV1.release
    val publication = OwnerDeletePersistenceSql.LOCK_PUBLICATION
    val discover = "SELECT object_id, object_key FROM complaint_test_terminal_intents WHERE data_scope_id = ?::uuid AND object_kind = 'TEST_RUN_PURGE' AND object_ordinal = 0"
    val recovery = """
        SELECT event_id, data_scope_id, test_only, publication_ref, state, accounting_version, reserved_amounts, created_at,
            (state = 'RESERVED' AND accounting_version = 1 AND publication_ref = event_id AND reserved_amounts = ?::bigint[]
                AND converted_amounts IS NULL AND converted_at IS NULL AND created_at IS NOT NULL AND isfinite(created_at)) IS TRUE AS valid
        FROM complaint_recovery_capacity_reservations WHERE event_id = ? FOR UPDATE
    """.trimIndent()
    val sidecar = TestOrdinarySealSqlV1.sidecar
        .replace("i.object_kind = 'EPOCH_SEAL' AND i.object_ordinal = 0 AND i.publication_ref IS NULL",
            "i.object_kind = 'TEST_RUN_PURGE' AND i.object_ordinal = 0 AND i.publication_ref = i.object_id")
        .replace("WHERE i.data_scope_id = ?::uuid OR i.object_key LIKE ?::text",
            "WHERE i.data_scope_id = ?::uuid AND i.object_kind = 'TEST_RUN_PURGE' AND i.object_ordinal = 0")
    val counts = TestInstallationManifestSqlV1.counts
        .replace("count(*) FILTER (WHERE i.object_kind <> 'INSTALLATION_MANIFEST') AS other_intents,",
            "count(*) FILTER (WHERE i.object_kind NOT IN ('INSTALLATION_MANIFEST', 'TEST_RUN_PURGE')) AS other_intents, " +
                "count(*) FILTER (WHERE i.object_kind = 'TEST_RUN_PURGE') AS purges, " +
                "(SELECT count(*) FROM complaint_journal_publications p WHERE p.data_scope_id = e.scope AND p.event_kind = 'TEST_RUN_PURGE') AS purge_publications, " +
                "(SELECT count(*) FROM complaint_recovery_capacity_reservations r JOIN complaint_journal_publications p ON p.event_id = r.publication_ref " +
                "WHERE r.data_scope_id = e.scope AND p.event_kind = 'TEST_RUN_PURGE') AS purge_reservations,")
    val freeze = TestInstallationManifestPublicationSqlV1.freeze
        .replace("object_kind = 'INSTALLATION_MANIFEST' AND object_ordinal = ?", "object_kind = 'TEST_RUN_PURGE' AND object_ordinal = 0")
    val verify = TestInstallationManifestPublicationSqlV1.verify.replace("event_kind = 'INSTALLATION_MANIFEST'", "event_kind = 'TEST_RUN_PURGE'")
    val insertPublication = TestInstallationManifestSqlV1.insertPublication.replace("'INSTALLATION_MANIFEST', ?", "'TEST_RUN_PURGE', 0")
    val insertRecovery = TestInstallationManifestSqlV1.insertRecovery.replace("array_fill(0::bigint, ARRAY[22])", "?::bigint[]")
    val insertSidecar = TestInstallationManifestSqlV1.insertSidecar.replace("'INSTALLATION_MANIFEST', ?", "'TEST_RUN_PURGE', 0")

    // No entire-run scalar sorting. Fixed one-family lookup and <=50 current APPLIED rows.
    val primaryNext = """
        SELECT event_id FROM complaint_journal_publications
        WHERE (data_scope_id = ?::uuid OR object_key LIKE ?::text OR object_key LIKE ?::text)
            AND event_kind NOT IN ('INSTALLATION_MANIFEST', 'TEST_RUN_PURGE')
            AND (?::text IS NULL OR event_id COLLATE "C" > ?::text COLLATE "C")
        ORDER BY event_id COLLATE "C" LIMIT 1
    """.trimIndent()
    val inventoryPage = """
        SELECT event_id, data_scope_id, test_only, writer_generation, journal_epoch, event_kind, target_count,
            object_key, object_version, ciphertext_hash, applied_at, xmin::text AS stamp, isfinite(applied_at) AS finite
        FROM complaint_deletion_journal_applied
        WHERE (data_scope_id = ?::uuid OR object_key LIKE ?::text OR object_key LIKE ?::text)
            AND (?::bigint IS NULL OR (journal_epoch, event_kind COLLATE "C", object_key COLLATE "C", object_version COLLATE "C") >
                (?::bigint, ?::text COLLATE "C", ?::text COLLATE "C", ?::text COLLATE "C"))
        ORDER BY journal_epoch, event_kind COLLATE "C", object_key COLLATE "C", object_version COLLATE "C" LIMIT 50
    """.trimIndent()

    /** Complete applicable relations. Neither mixed state nor a foreign-scope prefix row is filtered out. */
    val relation = """
        WITH e AS MATERIALIZED (SELECT ?::uuid AS scope, ?::text AS ordinary_prefix, ?::text AS terminal_prefix,
            ?::uuid AS writer, ?::bigint AS cutoff, ?::bigint AS epoch, ?::boolean AS allow_all, ?::boolean AS allow_admin,
            ?::boolean AS allow_batch, ?::bigint[] AS purge_promise)
        SELECT (NOT EXISTS (SELECT 1 FROM complaint_journal_publications p CROSS JOIN e
                LEFT JOIN complaint_recovery_capacity_reservations r ON r.event_id = p.event_id
                LEFT JOIN complaint_test_terminal_intents i ON i.publication_ref = p.event_id
                WHERE (p.data_scope_id = e.scope OR p.object_key LIKE e.ordinary_prefix OR p.object_key LIKE e.terminal_prefix)
                    AND (p.data_scope_id = e.scope AND p.test_only AND p.writer_generation = e.writer AND p.canonicalizer = 'kcj-1'
                        AND complaint_bytes_match(p.event_bytes, p.semantic_hash, 65536) AND isfinite(p.created_at)
                        AND r.data_scope_id = e.scope AND r.test_only AND r.publication_ref = p.event_id AND r.accounting_version = 1
                        AND ((p.state = 'APPLIED'
                                AND ((p.event_kind = 'OWNER_DELETE' AND p.target_count = 1)
                                    OR (e.allow_all AND p.event_kind = 'OWNER_DELETE_ALL' AND p.target_count BETWEEN 0 AND 100)
                                    OR (e.allow_admin AND p.event_kind = 'ADMIN_DELETE' AND p.target_count = 1)
                                    OR (e.allow_batch AND p.event_kind = 'ADMIN_BATCH_DELETE' AND p.target_count BETWEEN 1 AND 50))
                                AND p.journal_epoch BETWEEN 1 AND e.cutoff AND p.object_key LIKE e.ordinary_prefix
                                AND r.state = 'CONVERTED' AND complaint_vector_lte(r.converted_amounts, r.reserved_amounts)
                                AND r.converted_at IS NOT NULL AND isfinite(r.converted_at) AND i.operation_token IS NULL)
                            OR (((p.event_kind = 'INSTALLATION_MANIFEST' AND p.target_count BETWEEN 1 AND 500 AND p.state = 'VERIFIED')
                                    OR (p.event_kind = 'TEST_RUN_PURGE' AND p.target_count = 0)) AND p.state IN ('PREPARED', 'VERIFIED')
                                AND p.journal_epoch = e.epoch AND p.object_key LIKE e.terminal_prefix
                                AND p.applied_at IS NULL
                                AND ((p.state = 'PREPARED' AND p.object_version IS NULL AND p.ciphertext_hash IS NULL AND p.object_created_at IS NULL
                                        AND p.retain_until IS NULL AND p.verified_at IS NULL AND p.verification_bytes IS NULL AND p.verification_hash IS NULL)
                                    OR (p.state = 'VERIFIED' AND complaint_opaque_valid(p.object_version, 1024) AND p.object_version <> 'null'
                                        AND i.state = 'WIRE_FROZEN' AND p.ciphertext_hash = i.wire_hash
                                        AND isfinite(p.object_created_at) AND p.object_created_at <= p.verified_at
                                        AND isfinite(p.retain_until) AND isfinite(p.verified_at) AND p.retain_until > clock_timestamp()
                                        AND p.retain_until >= i.retain_until AND p.retain_until > p.verified_at
                                        AND complaint_bytes_match(p.verification_bytes, p.verification_hash, 65536)))
                                AND r.state = 'RESERVED' AND r.reserved_amounts = CASE WHEN p.event_kind = 'TEST_RUN_PURGE' THEN e.purge_promise ELSE array_fill(0::bigint, ARRAY[22]) END
                                AND r.converted_amounts IS NULL AND r.converted_at IS NULL AND r.created_at = p.created_at
                                AND i.object_kind = p.event_kind AND i.state IN ('CANONICAL', 'WIRE_FROZEN') AND i.data_scope_id = e.scope
                                AND i.object_id = p.event_id AND i.object_key = p.object_key AND i.routing_key_id = p.routing_key_id
                                AND i.writer_generation = p.writer_generation AND i.epoch_start = p.journal_epoch AND i.epoch_end = p.journal_epoch
                                AND i.created_at = p.created_at AND i.canonical_bytes = p.event_bytes AND i.canonical_hash = p.semantic_hash))) IS NOT TRUE)
            AND NOT EXISTS (SELECT 1 FROM complaint_test_terminal_intents i CROSS JOIN e
                LEFT JOIN complaint_journal_publications p ON p.event_id = i.publication_ref
                WHERE (i.data_scope_id = e.scope OR i.object_key LIKE e.terminal_prefix)
                    AND (i.data_scope_id = e.scope AND i.test_only AND i.writer_generation = e.writer
                        AND i.object_key LIKE e.terminal_prefix AND i.schema_version = 1 AND i.canonicalizer = 'kcj-1'
                        AND ((i.object_kind = 'EPOCH_SEAL' AND i.object_ordinal = 0 AND i.state = 'WIRE_FROZEN' AND i.publication_ref IS NULL)
                            OR (((i.object_kind = 'INSTALLATION_MANIFEST' AND i.object_ordinal BETWEEN 0 AND 4095 AND i.state = 'WIRE_FROZEN')
                                    OR (i.object_kind = 'TEST_RUN_PURGE' AND i.object_ordinal = 0))
                                AND i.state IN ('CANONICAL', 'WIRE_FROZEN') AND p.event_id = i.object_id AND p.data_scope_id = e.scope
                                AND complaint_bytes_match(i.canonical_bytes, i.canonical_hash, 65536)
                                AND ((i.state = 'CANONICAL' AND i.wire_bytes IS NULL AND i.wire_hash IS NULL AND i.checksum_sha256 IS NULL
                                        AND i.content_type IS NULL AND i.object_lock_mode IS NULL AND i.retain_until IS NULL
                                        AND i.metadata_bytes IS NULL AND i.metadata_hash IS NULL AND i.frozen_at IS NULL)
                                    OR (i.state = 'WIRE_FROZEN' AND complaint_bytes_match(i.wire_bytes, i.wire_hash, 98304)
                                        AND i.checksum_sha256 = encode(i.wire_hash, 'base64') AND i.content_type = 'application/octet-stream'
                                        AND i.object_lock_mode = 'COMPLIANCE' AND isfinite(i.retain_until) AND isfinite(i.frozen_at)
                                        AND i.frozen_at >= i.created_at AND i.retain_until >= i.retention_floor AND i.retain_until > clock_timestamp()
                                        AND complaint_bytes_match(i.metadata_bytes, i.metadata_hash, 512)
                                        AND i.metadata_bytes = complaint_test_terminal_metadata(i.object_id, i.wire_hash, i.retain_until)))))) IS NOT TRUE)
            AND NOT EXISTS (SELECT 1 FROM complaint_recovery_capacity_reservations r CROSS JOIN e
                LEFT JOIN complaint_journal_publications p ON p.event_id = r.publication_ref
                WHERE (r.data_scope_id = e.scope OR p.data_scope_id = e.scope)
                    AND (r.data_scope_id = e.scope AND r.test_only AND r.event_id = r.publication_ref AND p.data_scope_id = e.scope) IS NOT TRUE)
            AND NOT EXISTS (SELECT 1 FROM complaint_idempotency_receipts r CROSS JOIN e
                LEFT JOIN complaint_journal_publications p ON p.event_id = r.publication_ref
                WHERE (r.data_scope_id = e.scope OR p.data_scope_id = e.scope)
                    AND (r.data_scope_id = e.scope AND r.test_only AND r.state = 'COMPLETED' AND (r.operation <> 'ADMIN_BATCH_DELETE' OR e.allow_batch)
                        AND (r.operation <> 'ADMIN_DELETE' OR e.allow_admin)
                        AND ((r.publication_ref IS NULL AND r.external_event_id IS NULL AND r.authorized_at IS NULL
                                AND (r.operation NOT IN ('OWNER_DELETE', 'ADMIN_DELETE', 'ADMIN_BATCH_DELETE') OR r.outcome = 'REJECTED'))
                            OR (r.outcome = 'APPLIED' AND p.data_scope_id = e.scope AND p.state = 'APPLIED'
                                AND r.external_event_id = p.event_id AND r.external_epoch = p.journal_epoch
                                AND r.external_object_version = p.object_version AND r.external_ciphertext_hash = p.ciphertext_hash
                                AND r.authorized_at = p.created_at
                                AND ((r.operation = 'OWNER_DELETE' AND r.actor_kind = 'INSTALLATION' AND p.event_kind = 'OWNER_DELETE' AND p.target_count = 1)
                                    OR (e.allow_admin AND r.operation = 'ADMIN_DELETE' AND r.actor_kind = 'ADMIN' AND p.event_kind = 'ADMIN_DELETE' AND p.target_count = 1)
                                    OR (e.allow_batch AND r.operation = 'ADMIN_BATCH_DELETE' AND r.actor_kind = 'ADMIN' AND p.event_kind = 'ADMIN_BATCH_DELETE'
                                        AND p.target_count BETWEEN 1 AND 50 AND cardinality(r.target_ids) = p.target_count
                                        AND r.response_status = 200 AND r.ack_ids = r.target_ids AND r.ack_versions IS NULL
                                        AND r.response_location IS NULL AND r.response_etag IS NULL AND complaint_is_v4(r.consumed_grant_id)))))) IS NOT TRUE)
            AND NOT EXISTS (SELECT 1 FROM complaint_deletion_journal_applied a CROSS JOIN e
                WHERE (a.data_scope_id = e.scope OR a.object_key LIKE e.ordinary_prefix OR a.object_key LIKE e.terminal_prefix)
                    AND (a.data_scope_id = e.scope AND a.test_only AND a.writer_generation = e.writer
                        AND ((a.event_kind = 'OWNER_DELETE' AND a.target_count = 1)
                            OR (e.allow_all AND a.event_kind = 'OWNER_DELETE_ALL' AND a.target_count BETWEEN 0 AND 100)
                            OR (e.allow_admin AND a.event_kind = 'ADMIN_DELETE' AND a.target_count = 1)
                            OR (e.allow_batch AND a.event_kind = 'ADMIN_BATCH_DELETE' AND a.target_count BETWEEN 1 AND 50))
                        AND a.journal_epoch BETWEEN 1 AND e.cutoff AND a.object_key LIKE e.ordinary_prefix) IS NOT TRUE)
            AND NOT EXISTS (SELECT 1 FROM complaint_resource_ids r CROSS JOIN e WHERE r.data_scope_id = e.scope AND r.state = 'DELETION_PENDING')
            AND NOT EXISTS (SELECT 1 FROM complaint_installation_ids r CROSS JOIN e WHERE r.data_scope_id = e.scope AND r.state = 'DELETION_PENDING')
            AND NOT EXISTS (SELECT 1 FROM complaint_deletion_journal_retirements r CROSS JOIN e WHERE r.data_scope_id = e.scope)
            AND NOT EXISTS (SELECT 1 FROM installation_deletion_receipts r CROSS JOIN e
                LEFT JOIN complaint_journal_publications p ON p.event_id = r.publication_ref
                WHERE (r.data_scope_id = e.scope OR p.data_scope_id = e.scope)
                    AND (e.allow_all AND r.data_scope_id = e.scope AND r.test_only AND r.state = 'COMPLETED'
                        AND p.data_scope_id = e.scope AND p.test_only AND p.event_kind = 'OWNER_DELETE_ALL'
                        AND p.target_count BETWEEN 0 AND 100 AND p.state = 'APPLIED'
                        AND r.external_event_id = p.event_id AND r.external_epoch = p.journal_epoch
                        AND r.external_object_version = p.object_version AND r.external_ciphertext_hash = p.ciphertext_hash
                        AND r.authorized_at = p.created_at AND r.completed_at = p.applied_at
                        AND r.expires_at = r.completed_at + interval '192 hours') IS NOT TRUE)) AS valid
    """.trimIndent()

}
