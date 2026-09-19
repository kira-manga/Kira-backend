package me.manga.kira.backend.common.infrastructure.persistence

import org.springframework.jdbc.core.JdbcTemplate
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** Legal synthetic V14/V17 storage shapes only: no signed catalog, provider evidence, TEST sealer or reserve authority. */
internal val TEST_TERMINAL_CAPACITY_SCOPE: UUID = UUID.fromString("00000000-0000-4000-8000-000000000001")
internal val TEST_TERMINAL_CAPACITY_TOKEN: UUID = UUID.fromString("00000000-0000-4000-8000-000000000002")
internal val TEST_TERMINAL_NOTICE_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000003")
internal val TEST_TERMINAL_RESOURCE_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000004")
private val sizingTime = Timestamp.from(Instant.parse("2026-09-19T00:00:00Z"))

/** Independent canonical JSON scalar for logical sizing, deliberately not a valid catalog/evidence DTO or signature. */
private fun sizingBytes(size: Int): ByteArray = ("\"" + "x".repeat(size - 2) + "\"").toByteArray(Charsets.UTF_8)
private fun sizingHash(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

internal fun seedTestTerminalCatalog(sql: JdbcTemplate, maximumDocumentBytes: Int) {
    val document = sizingBytes(maximumDocumentBytes)
    val approval = sizingBytes(4096)
    val evidence = sizingBytes(65536)
    sql.update(
        "INSERT INTO complaint_catalog_mutations (operation_token, operation_type, data_scope_id, test_only, predecessor_generation, " +
            "predecessor_hash, successor_generation, catalog_writer_generation, approval_bytes, approval_hash, canonicalizer, unsigned_bytes, unsigned_hash, " +
            "signer_policy, signer_one_id, signer_one_algorithm, signer_one_signature, envelope_bytes, envelope_hash, object_key, object_version, retain_until, " +
            "primary_evidence_bytes, primary_evidence_hash, replica_evidence_bytes, replica_evidence_hash, state, created_at, completed_at) " +
            "VALUES (?, 'TEST_RUN_TERMINAL', ?, true, 65535, ?, 65536, ?, ?, ?, 'kcj-1', ?, ?, 'SINGLE', repeat('s',128), repeat('a',128), ?, " +
            "?, ?, repeat('k',1024), repeat('v',1024), ?, ?, ?, ?, ?, 'COMPLETED', ?, ?)",
        TEST_TERMINAL_CAPACITY_TOKEN, TEST_TERMINAL_CAPACITY_SCOPE, sizingHash(document), TEST_TERMINAL_CAPACITY_TOKEN,
        approval, sizingHash(approval), document, sizingHash(document), ByteArray(1024) { 99 }, document, sizingHash(document), sizingTime,
        evidence, sizingHash(evidence), evidence, sizingHash(evidence), sizingTime, sizingTime,
    )
}

internal fun seedTestTerminalActiveRun(sql: JdbcTemplate) {
    sql.update(
        "INSERT INTO complaint_test_runs (data_scope_id, test_only, state, configuration_hash, accounting_version, installation_limit, enrolled_count, " +
            "original_reserve, unused_reserve, activation_catalog_generation, activation_catalog_hash, created_at) VALUES " +
            "(?, true, 'ACTIVE', ?, 1, 500, 500, array_fill(9223372036854775807::bigint, ARRAY[22]), " +
            "array_fill(9223372036854775807::bigint, ARRAY[22]), 65535, ?, ?)",
        TEST_TERMINAL_CAPACITY_SCOPE, sizingHash(sizingBytes(32)), sizingHash(sizingBytes(33)), sizingTime,
    )
}

internal fun populateTestTerminalRun(sql: JdbcTemplate, state: String) {
    require(state in setOf("SEALED", "PURGING", "PURGED"))
    val evidence = sizingBytes(65536)
    sql.update(
        "WITH v AS (SELECT ?::bytea AS b, ?::bytea AS h, ?::timestamptz AS t) UPDATE complaint_test_runs SET state = ?, " +
            "sealed_at = v.t, purging_at = ?, purged_at = ?, final_ordinary_epoch = 9223372036854775806, terminal_seal_epoch = 9223372036854775807, " +
            "generation_seal_count = 16, generation_seal_root = v.h, seal_set_bytes = v.b, seal_set_hash = v.h, event_manifest_count = 500, " +
            "event_manifest_root = v.h, installation_manifest_count = 500, installation_manifest_root = v.h, installation_chunk_count = 1, " +
            "retired_count = 250, deleted_count = 250, permanent_denial_bytes = v.b, permanent_denial_hash = v.h, terminal_event_id = repeat('A',43), " +
            "terminal_object_key = repeat('k',1024), terminal_object_version = repeat('v',1024), terminal_ciphertext_hash = v.h, " +
            "terminal_catalog_generation = 65536, terminal_catalog_hash = v.h, unused_reserve = " +
            (if (state == "PURGED") "array_fill(0::bigint, ARRAY[22])" else "original_reserve") + " FROM v WHERE data_scope_id = ?",
        evidence, sizingHash(evidence), sizingTime, state, if (state == "SEALED") null else sizingTime,
        if (state == "PURGED") sizingTime else null, TEST_TERMINAL_CAPACITY_SCOPE,
    )
}

internal fun seedTestTerminalControl(sql: JdbcTemplate) {
    sql.update(
        "INSERT INTO complaint_journal_control (data_scope_id, test_only, publication_epoch, desired_generation, implementation_schema, " +
            "maintenance_closed, creation_closed, scan_requested, lease_token, retention_lease_token, updated_at) " +
            "VALUES (?, true, 9223372036854775807, 9223372036854775807, 1, true, true, true, 0, 0, ?)",
        TEST_TERMINAL_CAPACITY_SCOPE, sizingTime,
    )
    val evidence = sizingBytes(65536)
    sql.update(
        "WITH v AS (SELECT ?::uuid AS u, ?::bytea AS b, ?::bytea AS h, ?::timestamptz AS t) UPDATE complaint_journal_control SET " +
            "desired_configuration_hash = v.h, database_identity = v.u, restore_identity = v.u, event_writer_generation = v.u, " +
            "accepted_catalog_generation = 65536, accepted_catalog_hash = v.h, trust_bundle_hash = v.h, catalog_writer_generation = v.u, " +
            "pending_projection_token = v.u, lease_owner = v.u, lease_token = 9223372036854775807, lease_expires_at = v.t, " +
            "retention_lease_owner = v.u, retention_lease_token = 9223372036854775807, retention_lease_expires_at = v.t, " +
            "seal_state = 'SEAL_VERIFIED', seal_epoch = 9223372036854775806, seal_writer_generation = v.u, seal_operation_token = v.u, " +
            "seal_object_key = repeat('k',1024), seal_bytes = v.b, seal_hash = v.h, seal_object_version = repeat('v',1024), " +
            "seal_ciphertext_hash = v.h, seal_retain_until = v.t, seal_verified_at = v.t, seal_verification_bytes = v.b, seal_verification_hash = v.h, " +
            "checkpoint_generation = 9223372036854775807, checkpoint_fencing_token = 9223372036854775807, checkpoint_catalog_generation = 65536, " +
            "checkpoint_catalog_hash = v.h, checkpoint_writer_generation = v.u, checkpoint_cutoff_epoch = 9223372036854775807, " +
            "checkpoint_configuration_hash = v.h, checkpoint_database_identity = v.u, checkpoint_restore_identity = v.u, checkpoint_schema = 1, " +
            "checkpoint_started_at = v.t, checkpoint_completed_at = v.t, checkpoint_object_count = 9223372036854775807, " +
            "checkpoint_byte_count = 9223372036854775807, checkpoint_result = 'SUCCESS', checkpoint_bytes = v.b, checkpoint_hash = v.h, " +
            "rotation_sequence = 9223372036854775807, rotation_id = v.u, rotation_state = 'CAPTURED', rotation_epoch_before = 9223372036854775806, " +
            "rotation_implementation_schema = 1, rotation_desired_generation = 9223372036854775807, rotation_desired_configuration_hash = v.h, " +
            "rotation_database_identity = v.u, rotation_restore_identity = v.u, rotation_event_writer_generation = v.u, " +
            "rotation_accepted_catalog_generation = 65536, rotation_accepted_catalog_hash = v.h, rotation_trust_bundle_hash = v.h, " +
            "rotation_catalog_writer_generation = v.u, rotation_request_owner = v.u, rotation_request_token = 9223372036854775807, " +
            "rotation_requested_at = v.t, rotation_capture_owner = v.u, rotation_capture_token = 9223372036854775807, rotation_captured_at = v.t, " +
            "rotation_epoch_after = 9223372036854775807 FROM v WHERE data_scope_id = ?",
        TEST_TERMINAL_CAPACITY_TOKEN, evidence, sizingHash(evidence), sizingTime, TEST_TERMINAL_CAPACITY_SCOPE,
    )
    // All seven V19 linkage columns stay NULL. These V14/V17 shapes cannot authorize a TEST epoch sealer.
}

internal fun seedTestTerminalNoticeAndResource(sql: JdbcTemplate) {
    for (id in listOf(TEST_TERMINAL_NOTICE_ID, TEST_TERMINAL_RESOURCE_ID)) {
        sql.update(
            "INSERT INTO complaint_resource_ids (id, data_scope_id, test_only, state, created_at) VALUES (?, ?, true, 'LIVE', ?)",
            id, TEST_TERMINAL_CAPACITY_SCOPE, sizingTime,
        )
    }
    sql.update(
        "INSERT INTO complaints (id, data_scope_id, test_only, ownership, kind, status, notice_key, created_at, updated_at, version) " +
            "VALUES (?, ?, true, 'SYSTEM', 'NOTICE', 'PINNED', repeat('n',96), ?, ?, 9223372036854775807)",
        TEST_TERMINAL_NOTICE_ID, TEST_TERMINAL_CAPACITY_SCOPE, sizingTime, sizingTime,
    )
}

internal fun seedTestTerminalPublication(sql: JdbcTemplate, kind: String, targetCount: Int, eventId: String) {
    val bytes = sizingBytes(65536)
    sql.update(
        "INSERT INTO complaint_journal_publications (event_id, data_scope_id, test_only, writer_generation, journal_epoch, event_kind, target_count, " +
            "routing_key_id, object_key, canonicalizer, event_bytes, semantic_hash, state, created_at) " +
            "VALUES (?, ?, true, ?, 9223372036854775807, ?, ?, repeat('r',128), ?, 'kcj-1', ?, ?, 'PREPARED', ?)",
        eventId, TEST_TERMINAL_CAPACITY_SCOPE, TEST_TERMINAL_CAPACITY_TOKEN, kind, targetCount,
        "k".repeat(981) + eventId, bytes, sizingHash(bytes), sizingTime,
    )
    // Twenty-two synthetic fixed-width longs size only this physical RESERVED row, not a guessed future promise.
    sql.update(
        "INSERT INTO complaint_recovery_capacity_reservations (event_id, data_scope_id, test_only, publication_ref, state, accounting_version, " +
            "reserved_amounts, created_at) VALUES (?, ?, true, ?, 'RESERVED', 1, array_fill(9223372036854775807::bigint, ARRAY[22]), ?)",
        eventId, TEST_TERMINAL_CAPACITY_SCOPE, eventId, sizingTime,
    )
}

internal fun verifyTestTerminalPublication(sql: JdbcTemplate, eventId: String) {
    val evidence = sizingBytes(65536)
    sql.update(
        "UPDATE complaint_journal_publications SET state = 'VERIFIED', object_version = repeat('v',1024), ciphertext_hash = ?, " +
            "object_created_at = ?, retain_until = ?, verified_at = ?, verification_bytes = ?, verification_hash = ? WHERE event_id = ?",
        sizingHash(evidence), sizingTime, sizingTime, sizingTime, evidence, sizingHash(evidence), eventId,
    )
}
