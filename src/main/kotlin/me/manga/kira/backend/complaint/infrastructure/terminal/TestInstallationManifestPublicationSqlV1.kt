package me.manga.kira.backend.complaint.infrastructure.terminal

import me.manga.kira.backend.complaint.infrastructure.OwnerDeletePersistenceSql

/** Fixed manifest-only publication statements. PREPARE's canonical-only relation remains unchanged. */
internal object TestInstallationManifestPublicationSqlV1 {
    // A failed publication child is never its successor's transferable predecessor. Only a genuinely
    // free/expired lease can be acquired. PREPARE COMPLETE already released its exact current lease.
    val acquire = TestOrdinarySealSqlV1.acquire
    val release = TestInstallationManifestSqlV1.release
    val discover = TestInstallationManifestSqlV1.discover
    val publication = OwnerDeletePersistenceSql.LOCK_PUBLICATION
    val recovery = TestInstallationManifestSqlV1.recovery
    val sidecar = TestInstallationManifestSqlV1.sidecar
    val counts = TestInstallationManifestSqlV1.counts
    val freeze = """
        UPDATE complaint_test_terminal_intents SET state = 'WIRE_FROZEN', wire_bytes = ?, wire_hash = ?, checksum_sha256 = ?,
            content_type = 'application/octet-stream', object_lock_mode = 'COMPLIANCE', retain_until = ?, metadata_bytes = ?, metadata_hash = ?, frozen_at = ?
        WHERE operation_token = ?::uuid AND data_scope_id = ?::uuid AND test_only AND object_kind = 'INSTALLATION_MANIFEST' AND object_ordinal = ?
            AND state = 'CANONICAL' AND canonical_hash = ?
    """.trimIndent()
    // The receiptless VERIFY operation has only this table: no control, sidecar, recovery, counter,
    // run, audit, receipt or domain-row access, including on exact VERIFIED replay.
    val verify = """
        UPDATE complaint_journal_publications SET state = 'VERIFIED', object_version = ?, ciphertext_hash = ?, object_created_at = ?,
            retain_until = ?, verified_at = ?, verification_bytes = ?, verification_hash = ?
        WHERE event_id = ? AND data_scope_id = ?::uuid AND test_only AND event_kind = 'INSTALLATION_MANIFEST' AND state = 'PREPARED'
            AND semantic_hash = ?
    """.trimIndent()

    /** Complete applicable relations. Neither mixed state nor a foreign-scope prefix row is filtered out. */
    val relation = """
        WITH e AS MATERIALIZED (SELECT ?::uuid AS scope, ?::text AS ordinary_prefix, ?::text AS terminal_prefix,
            ?::uuid AS writer, ?::bigint AS cutoff, ?::bigint AS epoch, ?::boolean AS allow_all, ?::boolean AS allow_admin)
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
                                    OR (e.allow_admin AND p.event_kind = 'ADMIN_DELETE' AND p.target_count = 1))
                                AND p.journal_epoch BETWEEN 1 AND e.cutoff AND p.object_key LIKE e.ordinary_prefix
                                AND r.state = 'CONVERTED' AND complaint_vector_lte(r.converted_amounts, r.reserved_amounts)
                                AND r.converted_at IS NOT NULL AND isfinite(r.converted_at) AND i.operation_token IS NULL)
                            OR (p.event_kind = 'INSTALLATION_MANIFEST' AND p.target_count BETWEEN 1 AND 500 AND p.state IN ('PREPARED', 'VERIFIED')
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
                                AND r.state = 'RESERVED' AND r.reserved_amounts = array_fill(0::bigint, ARRAY[22])
                                AND r.converted_amounts IS NULL AND r.converted_at IS NULL AND r.created_at = p.created_at
                                AND i.object_kind = 'INSTALLATION_MANIFEST' AND i.state IN ('CANONICAL', 'WIRE_FROZEN') AND i.data_scope_id = e.scope
                                AND i.object_id = p.event_id AND i.object_key = p.object_key AND i.routing_key_id = p.routing_key_id
                                AND i.writer_generation = p.writer_generation AND i.epoch_start = p.journal_epoch AND i.epoch_end = p.journal_epoch
                                AND i.created_at = p.created_at AND i.canonical_bytes = p.event_bytes AND i.canonical_hash = p.semantic_hash))) IS NOT TRUE)
            AND NOT EXISTS (SELECT 1 FROM complaint_test_terminal_intents i CROSS JOIN e
                LEFT JOIN complaint_journal_publications p ON p.event_id = i.publication_ref
                WHERE (i.data_scope_id = e.scope OR i.object_key LIKE e.terminal_prefix)
                    AND (i.data_scope_id = e.scope AND i.test_only AND i.writer_generation = e.writer
                        AND i.object_key LIKE e.terminal_prefix AND i.schema_version = 1 AND i.canonicalizer = 'kcj-1'
                        AND ((i.object_kind = 'EPOCH_SEAL' AND i.object_ordinal = 0 AND i.state = 'WIRE_FROZEN' AND i.publication_ref IS NULL)
                            OR (i.object_kind = 'INSTALLATION_MANIFEST' AND i.object_ordinal BETWEEN 0 AND 4095
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
                    AND (r.data_scope_id = e.scope AND r.test_only AND r.state = 'COMPLETED' AND r.operation <> 'ADMIN_BATCH_DELETE'
                        AND (r.operation <> 'ADMIN_DELETE' OR e.allow_admin)
                        AND ((r.publication_ref IS NULL AND r.external_event_id IS NULL AND r.authorized_at IS NULL
                                AND (r.operation NOT IN ('OWNER_DELETE', 'ADMIN_DELETE') OR r.outcome = 'REJECTED'))
                            OR (r.outcome = 'APPLIED' AND p.data_scope_id = e.scope AND p.state = 'APPLIED'
                                AND r.external_event_id = p.event_id AND r.external_epoch = p.journal_epoch
                                AND r.external_object_version = p.object_version AND r.external_ciphertext_hash = p.ciphertext_hash
                                AND r.authorized_at = p.created_at AND p.target_count = 1
                                AND ((r.operation = 'OWNER_DELETE' AND r.actor_kind = 'INSTALLATION' AND p.event_kind = 'OWNER_DELETE')
                                    OR (e.allow_admin AND r.operation = 'ADMIN_DELETE' AND r.actor_kind = 'ADMIN' AND p.event_kind = 'ADMIN_DELETE'))))) IS NOT TRUE)
            AND NOT EXISTS (SELECT 1 FROM complaint_deletion_journal_applied a CROSS JOIN e
                WHERE (a.data_scope_id = e.scope OR a.object_key LIKE e.ordinary_prefix OR a.object_key LIKE e.terminal_prefix)
                    AND (a.data_scope_id = e.scope AND a.test_only AND a.writer_generation = e.writer
                        AND ((a.event_kind = 'OWNER_DELETE' AND a.target_count = 1)
                            OR (e.allow_all AND a.event_kind = 'OWNER_DELETE_ALL' AND a.target_count BETWEEN 0 AND 100)
                            OR (e.allow_admin AND a.event_kind = 'ADMIN_DELETE' AND a.target_count = 1))
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
