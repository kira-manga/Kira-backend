package me.manga.kira.backend.complaint.infrastructure.terminal

import me.manga.kira.backend.complaint.infrastructure.OwnerDeletePersistenceSql
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalSqlV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalActiveHistorySqlV1

/** Fixed erasure relations only. Discovery is unlocked; each selected preimage is locked and compared. */
internal object TestRunErasureSqlV1 {
    const val PAGE = 50
    private const val GLOBAL = "'00000000-0000-0000-0000-000000000000'::uuid"
    private fun physical(alias: String, maximum: Int) = """CASE WHEN octet_length(to_jsonb($alias)::text) BETWEEN 1 AND $maximum
        THEN sha256(convert_to(jsonb_build_array($alias.xmin::text, to_jsonb($alias))::text, 'UTF8')) END"""
    private fun capped(fields: List<Pair<String, Int>>) = fields.joinToString(",\n            ") { (field, maximum) ->
        "CASE WHEN octet_length($field) BETWEEN 1 AND $maximum THEN $field END AS ${field.substringAfter('.')}"
    }
    private val expected = """
        WITH e AS MATERIALIZED (SELECT ?::uuid AS scope, ?::bigint AS desired_generation, ?::integer AS implementation_schema,
            ?::bytea AS configuration_hash, ?::uuid AS database_identity, ?::uuid AS restore_identity,
            ?::uuid AS event_writer, ?::uuid AS catalog_writer, ?::bytea AS trust_hash)
    """.trimIndent()
    private const val CONTROL_CORE = "(to_jsonb(c) - ARRAY['lease_owner','lease_token','lease_expires_at','updated_at'])::text"
    val control = """
        $expected
        SELECT c.data_scope_id, c.accepted_catalog_generation, c.accepted_catalog_hash, c.lease_token, c.lease_owner, c.lease_expires_at,
            ${physical("c", 524288)} AS physical_hash,
            CASE WHEN octet_length($CONTROL_CORE) BETWEEN 1 AND 524288 THEN sha256(convert_to($CONTROL_CORE, 'UTF8')) END AS core_hash,
            (c.maintenance_closed AND c.creation_closed AND c.pending_projection_token IS NULL
                AND c.database_identity = e.database_identity AND c.restore_identity = e.restore_identity
                AND c.event_writer_generation = e.event_writer AND c.catalog_writer_generation = e.catalog_writer AND c.trust_bundle_hash = e.trust_hash
                AND c.implementation_schema = e.implementation_schema AND c.lease_token >= 0
                AND c.retention_lease_owner IS NULL AND c.retention_lease_expires_at IS NULL
                AND complaint_finite_times(c.updated_at, c.lease_expires_at) AND c.updated_at <= clock_timestamp()
                AND ((c.data_scope_id = $GLOBAL AND NOT c.test_only AND c.desired_generation > 0
                    AND c.lease_owner IS NULL AND c.lease_expires_at IS NULL
                    AND c.accepted_catalog_generation = r.terminal_catalog_generation AND c.accepted_catalog_hash = r.terminal_catalog_hash)
                  OR (c.data_scope_id = e.scope AND c.test_only AND r.state = 'PURGING'
                    AND c.desired_generation = e.desired_generation AND c.desired_configuration_hash = e.configuration_hash
                    AND c.accepted_catalog_generation = r.activation_catalog_generation AND c.accepted_catalog_hash = r.activation_catalog_hash
                    AND c.rotation_state = 'CAPTURED' AND c.scan_requested AND c.publication_epoch = r.terminal_seal_epoch + 1
                    AND c.rotation_epoch_before = r.final_ordinary_epoch AND c.rotation_epoch_after = r.terminal_seal_epoch
                    AND c.rotation_sequence = r.generation_seal_count - 1
                    AND ((c.lease_owner IS NULL AND c.lease_expires_at IS NULL)
                      OR (complaint_is_v4(c.lease_owner) AND c.lease_expires_at IS NOT NULL))))
            ) IS TRUE AS valid
        FROM complaint_journal_control c CROSS JOIN e JOIN complaint_test_runs r ON r.data_scope_id = e.scope
        WHERE c.data_scope_id = ?::uuid
    """.trimIndent()
    val lockControl = "$control FOR UPDATE OF c"
    private const val RUN_CORE = "(to_jsonb(r) - ARRAY['state','unused_reserve','purged_at'])::text"
    val run = """
        SELECT r.data_scope_id, r.state, r.activation_catalog_generation, r.terminal_catalog_generation, r.created_at, r.sealed_at,
            r.purging_at, r.purged_at, r.installation_limit, r.enrolled_count, r.final_ordinary_epoch, r.terminal_seal_epoch, r.generation_seal_count,
            r.event_manifest_count, r.installation_manifest_count, r.installation_chunk_count, r.retired_count, r.deleted_count,
            ${capped(listOf("r.configuration_hash", "r.activation_catalog_hash", "r.terminal_catalog_hash", "r.generation_seal_root", "r.seal_set_hash",
                "r.event_manifest_root", "r.installation_manifest_root", "r.terminal_ciphertext_hash").map { it to 32 } + listOf(
                "r.seal_set_bytes" to 65536, "r.terminal_event_id" to 43, "r.terminal_object_key" to 1024, "r.terminal_object_version" to 1024))},
            CASE WHEN complaint_vector_valid(r.original_reserve) THEN r.original_reserve END AS original_reserve,
            CASE WHEN complaint_vector_valid(r.unused_reserve) THEN r.unused_reserve END AS unused_reserve,
            CASE WHEN complaint_bytes_match(r.permanent_denial_bytes, r.permanent_denial_hash, 51291) THEN r.permanent_denial_bytes END AS progress_bytes,
            CASE WHEN octet_length(r.permanent_denial_hash) = 32 THEN r.permanent_denial_hash END AS progress_hash,
            ${physical("r", 524288)} AS physical_hash,
            CASE WHEN octet_length($RUN_CORE) BETWEEN 1 AND 524288 THEN sha256(convert_to($RUN_CORE, 'UTF8')) END AS core_hash,
            (r.test_only AND r.state IN ('PURGING','PURGED') AND r.accounting_version = 1
                AND r.enrolled_count BETWEEN 0 AND r.installation_limit AND complaint_vector_lte(r.unused_reserve, r.original_reserve)
                AND r.sealed_at >= r.created_at AND r.purging_at >= r.sealed_at AND r.purging_at <= clock_timestamp()
                AND complaint_finite_times(r.created_at, r.sealed_at, r.purging_at, r.purged_at)
                AND ((r.state = 'PURGING' AND r.purged_at IS NULL) OR (r.state = 'PURGED' AND r.purged_at >= r.purging_at AND r.purged_at <= clock_timestamp()
                    AND r.unused_reserve = array_fill(0::bigint, ARRAY[22])))
                AND r.final_ordinary_epoch > 0 AND r.terminal_seal_epoch = r.final_ordinary_epoch + 1 AND r.generation_seal_count BETWEEN 2 AND 3
                AND complaint_bytes_match(r.seal_set_bytes, r.seal_set_hash, 65536) AND r.event_manifest_count > 0
                AND r.installation_manifest_count = r.enrolled_count AND r.installation_chunk_count BETWEEN 0 AND 4096
                AND r.retired_count >= 0 AND r.deleted_count >= 0 AND r.retired_count + r.deleted_count = r.enrolled_count
                AND r.terminal_catalog_generation = r.activation_catalog_generation + 1) IS TRUE AS valid
        FROM complaint_test_runs r WHERE r.data_scope_id = ?::uuid
    """.trimIndent()
    val lockRun = "$run FOR UPDATE"
    val activation = CatalogTestRunTerminalSqlV1.readActivation
    val suffix = CatalogTestRunTerminalSqlV1.readSuffix
    val history = CatalogTestRunTerminalSqlV1.readActivationHistory
    val sampleTime = "SELECT clock_timestamp() AS at"

    // A remains intact until FINAL. This uses E's frozen read-only projection, not any E authority.
    val active = CatalogTestRunTerminalActiveHistorySqlV1.initialSeal.replace("r.state IN ('SEALED', 'PURGING')", "r.state = 'PURGING'")
    val queue = """
        $expected
        SELECT ${physical("o", 4096)} AS physical_hash,
            (o.data_scope_id = e.scope AND o.test_only AND o.desired_generation = e.desired_generation AND o.implementation_schema = e.implementation_schema
                AND o.database_identity = e.database_identity AND o.restore_identity = e.restore_identity AND o.writer_generation = e.event_writer
                AND o.configuration_hash = e.configuration_hash AND o.journal_hash = ?::bytea
                AND o.catalog_generation = r.activation_catalog_generation AND o.catalog_hash = r.activation_catalog_hash
                AND o.trust_bundle_hash = e.trust_hash AND o.catalog_writer_generation = e.catalog_writer
                AND o.storage_bytes = 8192 AND o.state = 'SETTLED' AND complaint_is_v4(o.lease_owner) AND o.fencing_token > 0
                AND o.started_at >= r.created_at AND o.settled_at >= o.started_at AND o.settled_at <= r.sealed_at
                AND o.primary_acked BETWEEN 0 AND 1 AND o.dlq_acked BETWEEN 0 AND 1 AND complaint_finite_times(o.started_at, o.settled_at)
                AND (r.state = 'PURGED' OR (o.fencing_token < c.rotation_capture_token AND o.fencing_token < c.lease_token AND o.settled_at <= c.rotation_captured_at))) IS TRUE AS valid
        FROM complaint_test_active_queue_observations o CROSS JOIN e JOIN complaint_test_runs r ON r.data_scope_id = e.scope
        LEFT JOIN complaint_journal_control c ON c.data_scope_id = e.scope
        WHERE o.data_scope_id = e.scope OR o.configuration_hash = e.configuration_hash OR o.journal_hash = ?::bytea
        ORDER BY o.data_scope_id LIMIT 2
    """.trimIndent()

    val acquireLease = """
        UPDATE complaint_journal_control SET lease_owner = ?::uuid, lease_token = lease_token + 1,
            lease_expires_at = clock_timestamp() + (?::bigint * interval '1 millisecond'), updated_at = clock_timestamp()
        WHERE data_scope_id = ?::uuid AND test_only AND maintenance_closed AND creation_closed AND pending_projection_token IS NULL
            AND lease_token BETWEEN 0 AND 9223372036854775806 AND (lease_owner IS NULL OR lease_expires_at <= clock_timestamp())
        RETURNING lease_token, lease_expires_at
    """.trimIndent()
    val currentLease = """SELECT (lease_owner = ?::uuid AND lease_token = ?::bigint AND lease_expires_at = ?::timestamptz
        AND lease_expires_at > clock_timestamp() AND test_only AND maintenance_closed AND creation_closed AND pending_projection_token IS NULL) IS TRUE AS valid
        FROM complaint_journal_control WHERE data_scope_id = ?::uuid"""
    val releaseLease = """UPDATE complaint_journal_control SET lease_owner = NULL, lease_expires_at = NULL, updated_at = clock_timestamp()
        WHERE data_scope_id = ?::uuid AND lease_owner = ?::uuid AND lease_token = ?::bigint AND lease_expires_at = ?::timestamptz
            AND lease_expires_at > clock_timestamp() AND test_only AND maintenance_closed AND creation_closed"""

    /** Covers ALL applicable rows before any batch, including foreign-scope prefix aliases. */
    val supported = """
        WITH e AS MATERIALIZED (SELECT ?::uuid AS scope, ?::text AS ordinary_prefix, ?::text AS terminal_prefix, ?::uuid AS writer, ?::bigint AS cutoff)
        SELECT NOT EXISTS (SELECT 1 FROM complaint_journal_scan_runs s CROSS JOIN e WHERE s.data_scope_id = e.scope)
            AND NOT EXISTS (SELECT 1 FROM complaint_journal_scan_entries s CROSS JOIN e WHERE s.data_scope_id = e.scope OR s.object_key LIKE e.ordinary_prefix OR s.object_key LIKE e.terminal_prefix)
            AND NOT EXISTS (SELECT 1 FROM complaint_deletion_journal_retirements s CROSS JOIN e WHERE s.data_scope_id = e.scope)
            AND NOT EXISTS (SELECT 1 FROM complaint_installation_ids i CROSS JOIN e WHERE i.data_scope_id = e.scope AND (NOT i.test_only OR i.state = 'DELETION_PENDING'))
            AND NOT EXISTS (SELECT 1 FROM app_installations i CROSS JOIN e WHERE i.data_scope_id = e.scope AND (NOT i.test_only OR i.state = 'DELETION_PENDING'))
            AND NOT EXISTS (SELECT 1 FROM complaint_resource_ids i CROSS JOIN e WHERE i.data_scope_id = e.scope AND (NOT i.test_only OR i.state = 'DELETION_PENDING'))
            AND NOT EXISTS (SELECT 1 FROM complaint_journal_publications p CROSS JOIN e
                WHERE (p.data_scope_id = e.scope OR p.object_key LIKE e.ordinary_prefix OR p.object_key LIKE e.terminal_prefix)
                AND (p.data_scope_id = e.scope AND p.test_only AND p.writer_generation = e.writer AND
                    ((p.event_kind IN ('OWNER_DELETE','OWNER_DELETE_ALL','ADMIN_DELETE','ADMIN_BATCH_DELETE') AND p.state = 'APPLIED' AND p.journal_epoch BETWEEN 1 AND e.cutoff)
                        OR (p.event_kind IN ('INSTALLATION_MANIFEST','TEST_RUN_PURGE') AND p.state = 'VERIFIED' AND p.journal_epoch = e.cutoff + 1))) IS NOT TRUE)
            AND NOT EXISTS (SELECT 1 FROM complaint_recovery_capacity_reservations l CROSS JOIN e
                LEFT JOIN complaint_journal_publications p ON p.event_id = l.publication_ref
                WHERE (l.data_scope_id = e.scope OR p.data_scope_id = e.scope) AND
                    (l.data_scope_id = e.scope AND l.test_only AND l.event_id = l.publication_ref AND p.data_scope_id = e.scope
                        AND ((p.state = 'APPLIED' AND l.state = 'CONVERTED') OR (p.state = 'VERIFIED' AND l.state = 'RESERVED'))) IS NOT TRUE)
            AND NOT EXISTS (SELECT 1 FROM complaint_idempotency_receipts r CROSS JOIN e LEFT JOIN complaint_journal_publications p ON p.event_id = r.publication_ref
                WHERE (r.data_scope_id = e.scope OR p.data_scope_id = e.scope) AND
                    (r.data_scope_id = e.scope AND r.test_only AND r.state = 'COMPLETED' AND
                        ((r.publication_ref IS NULL AND r.external_event_id IS NULL AND r.authorized_at IS NULL)
                          OR (p.data_scope_id = e.scope AND p.state = 'APPLIED' AND r.external_event_id = p.event_id AND r.external_epoch = p.journal_epoch
                            AND r.external_object_version = p.object_version AND r.external_ciphertext_hash = p.ciphertext_hash
                            AND r.authorized_at = p.created_at AND r.completed_at >= p.verified_at AND r.operation = p.event_kind))) IS NOT TRUE)
            AND NOT EXISTS (SELECT 1 FROM installation_deletion_receipts r CROSS JOIN e LEFT JOIN complaint_journal_publications p ON p.event_id = r.publication_ref
                WHERE (r.data_scope_id = e.scope OR p.data_scope_id = e.scope) AND
                    (r.data_scope_id = e.scope AND r.test_only AND r.state = 'COMPLETED' AND p.data_scope_id = e.scope AND p.state = 'APPLIED'
                        AND p.event_kind = 'OWNER_DELETE_ALL' AND r.external_event_id = p.event_id AND r.external_epoch = p.journal_epoch
                        AND r.external_object_version = p.object_version AND r.external_ciphertext_hash = p.ciphertext_hash
                        AND r.authorized_at = p.created_at AND r.completed_at = p.applied_at) IS NOT TRUE) AS valid
    """.trimIndent()

    // One fixed category per BATCH. The caller supplies no relation, predicate, work function or price.
    val discoverContent = "SELECT id, owner_id, ${physical("c", 524288)} AS physical_hash FROM complaints c WHERE data_scope_id = ?::uuid ORDER BY id LIMIT $PAGE"
    val discoverResources = "SELECT id, ${physical("r", 16384)} AS physical_hash FROM complaint_resource_ids r WHERE data_scope_id = ?::uuid ORDER BY id LIMIT $PAGE"
    val discoverReceipts = "SELECT actor_kind, actor_id, idempotency_key, ${capped(listOf("r.publication_ref" to 43))}, ${physical("r", 16384)} AS physical_hash FROM complaint_idempotency_receipts r WHERE data_scope_id = ?::uuid ORDER BY actor_kind COLLATE \"C\", actor_id, idempotency_key LIMIT $PAGE"
    val discoverDeletionReceipts = "SELECT installation_id, deletion_key, ${capped(listOf("r.publication_ref" to 43))}, ${physical("r", 16384)} AS physical_hash FROM installation_deletion_receipts r WHERE data_scope_id = ?::uuid ORDER BY installation_id, deletion_key LIMIT $PAGE"
    val discoverApplied = "SELECT ${capped(listOf("a.object_key" to 1024, "a.object_version" to 1024, "a.event_id" to 43))}, ${physical("a", 16384)} AS physical_hash FROM complaint_deletion_journal_applied a WHERE data_scope_id = ?::uuid ORDER BY object_key COLLATE \"C\", object_version COLLATE \"C\" LIMIT $PAGE"
    val discoverPublications = "SELECT ${capped(listOf("p.event_id" to 43, "p.object_key" to 1024))}, ${physical("p", 524288)} AS physical_hash FROM complaint_journal_publications p WHERE data_scope_id = ?::uuid AND event_kind NOT IN ('INSTALLATION_MANIFEST','TEST_RUN_PURGE') ORDER BY object_key COLLATE \"C\" LIMIT $PAGE"
    val discoverInstallations = """SELECT i.id, ${physical("i", 16384)} AS physical_hash FROM complaint_installation_ids i
        WHERE i.data_scope_id = ?::uuid AND (i.state NOT IN ('RETIRED','DELETED') OR EXISTS (SELECT 1 FROM app_installations a WHERE a.id = i.id)) ORDER BY i.id LIMIT $PAGE"""

    private val publication = """SELECT ${OwnerDeletePersistenceSql.PUBLICATION_COLUMNS}, ${physical("p", 524288)} AS physical_hash
        FROM complaint_journal_publications p"""
    val readPublication = "$publication WHERE event_id = ?::text"
    val lockPublication = "$readPublication FOR UPDATE"
    val publicationPage = """$publication WHERE (p.data_scope_id = ?::uuid OR p.object_key LIKE ?::text OR p.object_key LIKE ?::text)
        AND (?::text IS NULL OR p.object_key COLLATE "C" > ?::text COLLATE "C") ORDER BY p.object_key COLLATE "C" LIMIT $PAGE"""
    val recovery = """SELECT data_scope_id, test_only, accounting_version, created_at, converted_at,
        ${capped(listOf("l.event_id" to 43, "l.publication_ref" to 43, "l.state" to 16))},
        CASE WHEN complaint_vector_valid(reserved_amounts) THEN reserved_amounts END AS reserved_amounts,
        CASE WHEN complaint_vector_valid(converted_amounts) THEN converted_amounts END AS converted_amounts,
        complaint_finite_times(created_at, converted_at) AS finite, ${physical("l", 16384)} AS physical_hash
        FROM complaint_recovery_capacity_reservations l WHERE event_id = ?::text ORDER BY data_scope_id LIMIT 2"""
    val lockRecovery = "$recovery FOR UPDATE"
    val applied = """SELECT a.data_scope_id, a.test_only, a.writer_generation, a.journal_epoch, a.target_count,
        ${capped(listOf("a.event_id" to 43, "a.event_kind" to 32, "a.object_key" to 1024, "a.object_version" to 1024, "a.ciphertext_hash" to 32))},
        a.applied_at, isfinite(a.applied_at) AS finite, ${physical("a", 16384)} AS physical_hash FROM complaint_deletion_journal_applied a"""
    val lockApplied = "$applied WHERE a.object_key = ?::text AND a.object_version = ?::text FOR UPDATE"
    val appliedPage = """$applied WHERE (a.data_scope_id = ?::uuid OR a.object_key LIKE ?::text OR a.object_key LIKE ?::text)
        AND (?::text IS NULL OR (a.object_key COLLATE "C", a.object_version COLLATE "C") > (?::text COLLATE "C", ?::text COLLATE "C"))
        ORDER BY a.object_key COLLATE "C", a.object_version COLLATE "C" LIMIT $PAGE"""
    val lockReceipt = """SELECT r.actor_kind, r.actor_id, r.idempotency_key, ${capped(listOf("r.publication_ref" to 43))}, ${physical("r", 16384)} AS physical_hash,
        (r.data_scope_id = ?::uuid AND r.test_only AND r.state = 'COMPLETED' AND r.completed_at <= clock_timestamp()
            AND r.expires_at = r.completed_at + interval '192 hours' AND complaint_finite_times(r.created_at,r.authorized_at,r.completed_at,r.expires_at)) IS TRUE AS valid
        FROM complaint_idempotency_receipts r WHERE actor_kind = ?::text AND actor_id = ?::uuid AND idempotency_key = ?::uuid FOR UPDATE"""
    val lockDeletionReceipt = """SELECT r.installation_id, r.deletion_key, ${capped(listOf("r.publication_ref" to 43))}, ${physical("r", 16384)} AS physical_hash,
        (r.data_scope_id = ?::uuid AND r.test_only AND r.state = 'COMPLETED' AND r.completed_at <= clock_timestamp()
            AND r.expires_at = r.completed_at + interval '192 hours' AND complaint_finite_times(r.created_at,r.authorized_at,r.completed_at,r.expires_at)) IS TRUE AS valid
        FROM installation_deletion_receipts r WHERE installation_id = ?::uuid AND deletion_key = ?::uuid FOR UPDATE"""
    val noPublicationDependencies = """SELECT NOT EXISTS (SELECT 1 FROM complaint_idempotency_receipts WHERE publication_ref = ?::text)
        AND NOT EXISTS (SELECT 1 FROM installation_deletion_receipts WHERE publication_ref = ?::text)
        AND NOT EXISTS (SELECT 1 FROM complaint_test_terminal_intents WHERE publication_ref = ?::text) AS valid"""

    val lockResource = """SELECT r.id, r.state, ${physical("r", 16384)} AS physical_hash,
        (r.data_scope_id = ?::uuid AND r.test_only AND r.state IN ('LIVE','DELETED') AND complaint_finite_times(r.created_at,r.deleted_at)) IS TRUE AS valid
        FROM complaint_resource_ids r WHERE r.id = ?::uuid FOR UPDATE"""
    val lockContent = """SELECT c.id, c.owner_id, c.kind, ${physical("c", 524288)} AS physical_hash,
        (c.data_scope_id = ?::uuid AND c.test_only AND c.version > 0 AND complaint_finite_times(c.created_at,c.updated_at,c.closed_at)
            AND ((c.ownership = 'INSTALLATION' AND c.kind IN ('REPORT','REPLY') AND c.owner_id IS NOT NULL)
                OR (c.ownership = 'SYSTEM' AND c.kind = 'NOTICE' AND c.owner_id IS NULL))
            AND c.legacy_collection IS NULL AND c.legacy_key_id IS NULL AND c.legacy_document_hmac IS NULL AND c.legacy_payload_hash IS NULL
            AND c.legacy_owner_fingerprint IS NULL AND c.legacy_reconciliation_code IS NULL) IS TRUE AS valid
        FROM complaints c WHERE c.id = ?::uuid FOR UPDATE"""
    val noResourceDependencies = "SELECT NOT EXISTS (SELECT 1 FROM complaints WHERE id = ?::uuid OR parent_resource_id = ?::uuid) AS valid"
    val installation = """SELECT i.id, i.state, i.terminal_at, ${physical("i", 16384)} AS physical_hash,
        (i.data_scope_id = ?::uuid AND i.test_only AND i.state <> 'DELETION_PENDING' AND complaint_finite_times(i.created_at,i.terminal_at)) IS TRUE AS valid
        FROM complaint_installation_ids i WHERE i.id = ?::uuid"""
    val lockInstallation = "$installation FOR UPDATE"
    val credential = """SELECT a.id, a.state, ${physical("a", 16384)} AS physical_hash,
        (a.data_scope_id = ?::uuid AND a.test_only AND a.state IN ('ACTIVE','DELETED') AND a.credential_version > 0 AND a.version > 0
            AND complaint_digest_valid(a.secret_verifier) AND complaint_finite_times(a.created_at,a.last_authenticated_at,a.deleted_at,a.verifier_expires_at)) IS TRUE AS valid
        FROM app_installations a WHERE a.id = ?::uuid"""
    val lockCredential = "$credential FOR UPDATE"
    val installationIds = "SELECT id FROM complaint_installation_ids WHERE data_scope_id = ?::uuid AND (?::uuid IS NULL OR id > ?::uuid) ORDER BY id LIMIT $PAGE"
    val installationDependencies = """SELECT EXISTS (SELECT 1 FROM complaints WHERE owner_id = ?::uuid) AS content,
        (EXISTS (SELECT 1 FROM complaint_idempotency_receipts WHERE actor_kind = 'INSTALLATION' AND actor_id = ?::uuid)
            OR EXISTS (SELECT 1 FROM installation_deletion_receipts WHERE installation_id = ?::uuid)) AS receipts"""

    val sidecar = """SELECT i.operation_token, i.data_scope_id, i.object_kind, i.object_ordinal,
        i.writer_generation, i.epoch_start, i.epoch_end, i.preparing_fencing_token, i.activation_catalog_generation, i.retention_floor,
        i.created_at, i.retain_until, i.frozen_at, i.state,
        ${capped(listOf("i.activation_catalog_hash", "i.configuration_hash", "i.journal_configuration_hash", "i.terminal_encoding_hash",
            "i.canonical_hash", "i.wire_hash", "i.metadata_hash").map { it to 32 } + listOf("i.canonical_bytes" to 65536, "i.wire_bytes" to 98304, "i.metadata_bytes" to 512))},
        ${capped(listOf("i.object_id" to 43, "i.object_key" to 1024, "i.routing_key_id" to 64, "i.publication_ref" to 43,
            "i.checksum_sha256" to 44, "i.content_type" to 64, "i.object_lock_mode" to 16))},
        ${physical("i", 524288)} AS physical_hash,
        (i.schema_version = 1 AND i.test_only AND i.state = 'WIRE_FROZEN' AND i.canonicalizer = 'kcj-1') IS TRUE AS valid
        FROM complaint_test_terminal_intents i WHERE i.data_scope_id = ?::uuid OR i.object_key LIKE ?::text ORDER BY i.object_key COLLATE "C" LIMIT 4099"""

    // Exact physical hashes include xmin. No DELETE receives merely a count or a boolean success witness.
    val deleteContent = "DELETE FROM complaints c WHERE id = ?::uuid AND data_scope_id = ?::uuid AND test_only AND ${physical("c", 524288)} = ?::bytea"
    val deleteResource = "DELETE FROM complaint_resource_ids r WHERE id = ?::uuid AND data_scope_id = ?::uuid AND test_only AND ${physical("r", 16384)} = ?::bytea"
    val deleteReceipt = "DELETE FROM complaint_idempotency_receipts r WHERE actor_kind = ?::text AND actor_id = ?::uuid AND idempotency_key = ?::uuid AND data_scope_id = ?::uuid AND test_only AND state = 'COMPLETED' AND ${physical("r", 16384)} = ?::bytea"
    val deleteDeletionReceipt = "DELETE FROM installation_deletion_receipts r WHERE installation_id = ?::uuid AND deletion_key = ?::uuid AND data_scope_id = ?::uuid AND test_only AND state = 'COMPLETED' AND ${physical("r", 16384)} = ?::bytea"
    val deleteApplied = "DELETE FROM complaint_deletion_journal_applied a WHERE object_key = ?::text AND object_version = ?::text AND data_scope_id = ?::uuid AND test_only AND ${physical("a", 16384)} = ?::bytea"
    val deleteRecovery = "DELETE FROM complaint_recovery_capacity_reservations l WHERE event_id = ?::text AND data_scope_id = ?::uuid AND test_only AND ${physical("l", 16384)} = ?::bytea"
    val deletePublication = "DELETE FROM complaint_journal_publications p WHERE event_id = ?::text AND data_scope_id = ?::uuid AND test_only AND ${physical("p", 524288)} = ?::bytea"
    val deleteCredential = "DELETE FROM app_installations a WHERE id = ?::uuid AND data_scope_id = ?::uuid AND test_only AND ${physical("a", 16384)} = ?::bytea"
    val projectInstallation = "UPDATE complaint_installation_ids i SET state = ?::text, terminal_at = ?::timestamptz WHERE id = ?::uuid AND data_scope_id = ?::uuid AND test_only AND state IN ('ACTIVE','RECOVERY_RESERVED') AND ${physical("i", 16384)} = ?::bytea"
    val deleteSidecar = "DELETE FROM complaint_test_terminal_intents i WHERE operation_token = ?::uuid AND data_scope_id = ?::uuid AND test_only AND state = 'WIRE_FROZEN' AND ${physical("i", 524288)} = ?::bytea"
    val deleteActive = """DELETE FROM complaint_test_active_seal_intents i WHERE operation_token = ?::uuid AND data_scope_id = ?::uuid AND test_only AND state = 'WIRE_FROZEN'
        AND sha256(convert_to((to_jsonb(i) || jsonb_build_object('row_xmin', i.xmin::text))::text,'UTF8')) = ?::bytea"""
    val deleteControl = """DELETE FROM complaint_journal_control c WHERE data_scope_id = ?::uuid AND test_only AND maintenance_closed AND creation_closed
        AND lease_owner = ?::uuid AND lease_token = ?::bigint AND lease_expires_at = ?::timestamptz AND lease_expires_at > clock_timestamp()
        AND pending_projection_token IS NULL AND sha256(convert_to($CONTROL_CORE,'UTF8')) = ?::bytea"""
    val spendRun = "UPDATE complaint_test_runs r SET unused_reserve = ?::bigint[] WHERE data_scope_id = ?::uuid AND test_only AND state = 'PURGING' AND ${physical("r", 524288)} = ?::bytea"
    val purgeRun = "UPDATE complaint_test_runs r SET state = 'PURGED', unused_reserve = array_fill(0::bigint, ARRAY[22]), purged_at = ?::timestamptz WHERE data_scope_id = ?::uuid AND test_only AND state = 'PURGING' AND ${physical("r", 524288)} = ?::bytea"

    val counts = """
        WITH e AS MATERIALIZED (SELECT ?::uuid AS scope)
        SELECT (SELECT count(*) FROM complaint_installation_ids i WHERE i.data_scope_id = e.scope) AS installations,
            (SELECT count(*) FROM complaint_installation_ids i WHERE i.data_scope_id = e.scope AND i.state NOT IN ('RETIRED','DELETED')) AS mutable_installations,
            (SELECT count(*) FROM app_installations i WHERE i.data_scope_id = e.scope) AS credentials,
            (SELECT count(*) FROM complaint_resource_ids i WHERE i.data_scope_id = e.scope) AS resources,
            (SELECT count(*) FROM complaints i WHERE i.data_scope_id = e.scope AND i.kind <> 'NOTICE') AS content,
            (SELECT count(*) FROM complaints i WHERE i.data_scope_id = e.scope AND i.kind = 'NOTICE') AS notices,
            (SELECT count(*) FROM complaint_idempotency_receipts i WHERE i.data_scope_id = e.scope) AS receipts,
            (SELECT count(*) FROM installation_deletion_receipts i WHERE i.data_scope_id = e.scope) AS deletion_receipts,
            (SELECT count(*) FROM complaint_deletion_journal_applied i WHERE i.data_scope_id = e.scope) AS applied,
            (SELECT count(*) FROM complaint_journal_publications i WHERE i.data_scope_id = e.scope) AS publications,
            (SELECT count(*) FROM complaint_journal_publications i WHERE i.data_scope_id = e.scope AND i.event_kind NOT IN ('INSTALLATION_MANIFEST','TEST_RUN_PURGE')) AS ordinary_publications,
            (SELECT count(*) FROM complaint_recovery_capacity_reservations i WHERE i.data_scope_id = e.scope) AS reservations,
            (SELECT count(*) FROM complaint_test_terminal_intents i WHERE i.data_scope_id = e.scope) AS sidecars,
            (SELECT count(*) FROM complaint_test_active_seal_intents i WHERE i.data_scope_id = e.scope) AS active_seals,
            (SELECT count(*) FROM complaint_test_active_queue_observations i WHERE i.data_scope_id = e.scope) AS queue_observations,
            (SELECT count(*) FROM complaint_catalog_mutations i WHERE i.data_scope_id = e.scope) AS catalogs,
            (SELECT count(*) FROM complaint_journal_control i WHERE i.data_scope_id = e.scope) AS controls,
            (SELECT count(*) FROM audit_log i WHERE i.complaint_data_scope_id = e.scope) AS audits
        FROM e
    """.trimIndent()
    private val auditExpected = """WITH e AS MATERIALIZED (SELECT ?::uuid AS scope, ?::uuid AS token, ?::bigint AS generation)"""
    private const val AUDIT_DETAIL = "jsonb_build_object('erasure','TEST_RUN_ERASURE_V1','operationToken',e.token::text,'generation',e.generation,'transition',?::text)"
    val insertAudit = """$auditExpected
        INSERT INTO audit_log(actor_user_id, action, entity_type, entity_id, detail, created_at, complaint_data_scope_id, complaint_actor_kind)
        SELECT NULL::uuid, ?::text, 'complaint_scope', e.scope::text, $AUDIT_DETAIL, ?::timestamptz, e.scope, 'SYSTEM' FROM e"""
    val auditSummary = """$auditExpected
        SELECT count(*) FILTER (WHERE a.action <> 'COMPLAINT_TEST_RUN_PURGED') AS dispositions,
            count(*) FILTER (WHERE a.action = 'COMPLAINT_TEST_RUN_PURGED') AS purges,
            coalesce(bool_and(a.id > 0 AND a.actor_user_id IS NULL AND a.complaint_actor_kind = 'SYSTEM'
                AND a.complaint_data_scope_id = e.scope AND a.entity_type = 'complaint_scope' AND a.entity_id = e.scope::text
                AND isfinite(a.created_at) AND a.created_at >= r.purging_at AND a.created_at <= clock_timestamp()
                AND a.detail = jsonb_build_object('erasure','TEST_RUN_ERASURE_V1','operationToken',e.token::text,'generation',e.generation,
                    'transition',CASE a.action WHEN 'COMPLAINT_INSTALLATION_RETIRED' THEN 'RETIRED'
                        WHEN 'COMPLAINT_INSTALLATION_DELETED' THEN 'DELETED' WHEN 'COMPLAINT_TEST_RUN_PURGED' THEN 'PURGED' END)
                AND (a.action <> 'COMPLAINT_TEST_RUN_PURGED' OR a.created_at = r.purged_at)), true) AS valid
        FROM e JOIN complaint_test_runs r ON r.data_scope_id = e.scope JOIN audit_log a ON
            (a.complaint_data_scope_id = e.scope OR (a.entity_type = 'complaint_scope' AND a.entity_id = e.scope::text))
            AND (a.action = 'COMPLAINT_TEST_RUN_PURGED' OR a.detail ->> 'erasure' = 'TEST_RUN_ERASURE_V1')
    """.trimIndent()
}
