package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.jdbc.core.JdbcTemplate
import java.security.MessageDigest
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.Base64
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

internal val TEST_TERMINAL_SCAN_PAIR: UUID = UUID.fromString("00000000-0000-4000-8000-000000000005")

/** Exact raw V14 declarations; the maximum counts are storage inputs, not authenticated inventory totals. */
internal fun testTerminalScanRun(pass: Int): Map<String, Any?> = linkedMapOf(
    "scan_id" to TEST_TERMINAL_SCAN_PAIR, "pass" to pass, "data_scope_id" to TEST_TERMINAL_CAPACITY_SCOPE, "test_only" to true,
    "restore_identity" to TEST_TERMINAL_CAPACITY_TOKEN, "desired_generation" to Long.MAX_VALUE, "fencing_token" to Long.MAX_VALUE,
    "writer_generation" to TEST_TERMINAL_CAPACITY_TOKEN, "cutoff_epoch" to Long.MAX_VALUE,
    "maximum_entries" to Long.MAX_VALUE, "maximum_bytes" to Long.MAX_VALUE, "entry_count" to Long.MAX_VALUE, "entry_bytes" to Long.MAX_VALUE,
    "state" to "SCANNING", "manifest_hash" to null, "started_at" to sizingTime, "finished_at" to null,
)

internal fun testTerminalScanEntry(pass: Int, kind: String, seed: Int): Map<String, Any?> {
    require(kind in setOf("INSTALLATION_MANIFEST", "TEST_RUN_PURGE", "EPOCH_SEAL"))
    // 768 deterministic independent bytes encode to exactly 1024 ASCII bytes; no repeated padding or provider randomness.
    fun text(offset: Int): String = Base64.getUrlEncoder().withoutPadding().encodeToString(testTerminalDurableIncompressible(768, seed * 10 + offset))
    return linkedMapOf(
        "scan_id" to TEST_TERMINAL_SCAN_PAIR, "pass" to pass, "data_scope_id" to TEST_TERMINAL_CAPACITY_SCOPE, "test_only" to true,
        "object_key" to text(0), "object_version" to text(1), "ciphertext_hash" to testTerminalDurableHash(byteArrayOf(seed.toByte())),
        "semantic_hash" to testTerminalDurableHash(byteArrayOf(seed.toByte(), 1)), "event_id" to if (kind == "EPOCH_SEAL") null else testTerminalDurableOpaque(seed),
        "event_kind" to kind, "writer_generation" to TEST_TERMINAL_CAPACITY_TOKEN, "journal_epoch" to Long.MAX_VALUE,
        "entry_bytes" to Long.MAX_VALUE, "replay_state" to "PENDING",
    )
}

internal fun insertTestTerminalScan(sql: JdbcTemplate, table: String, values: Map<String, Any?>): Int {
    require(values.keys == TEST_TERMINAL_SCAN_PROFILES.single { it.table == table }.types.keys)
    return sql.update("INSERT INTO $table (${values.keys.joinToString(",")}) VALUES (${values.keys.joinToString(",") { "?" }})", *values.values.toTypedArray())
}

internal fun populateTestTerminalScanRun(sql: JdbcTemplate, pass: Int, state: String): Int {
    require(state in setOf("SCANNING", "COMPLETE", "ABANDONED"))
    return sql.update(
        "UPDATE complaint_journal_scan_runs SET state = ?, manifest_hash = CASE WHEN ? = 'COMPLETE' THEN ?::bytea ELSE NULL END, " +
            "finished_at = CASE WHEN ? = 'SCANNING' THEN NULL ELSE ?::timestamptz END WHERE scan_id = ? AND pass = ?",
        state, state, testTerminalDurableHash(byteArrayOf(101)), state, sizingTime, TEST_TERMINAL_SCAN_PAIR, pass,
    )
}

internal fun testTerminalScanSnapshot(sql: JdbcTemplate): Map<String, List<String>> = testTerminalDurableSnapshot(sql) +
    TEST_TERMINAL_SCAN_PROFILES.associate { profile ->
        profile.table to sql.query("SELECT encode(sha256(convert_to(to_jsonb(r)::text, 'UTF8')), 'hex') FROM ${profile.table} r ORDER BY 1", { row, _ -> row.getString(1) })
    }

/** Boundary probes always reach PostgreSQL, and always roll back to the caller's original rows. */
internal fun assertTestTerminalScanInputBounds(sql: JdbcTemplate, connection: Connection) = testTerminalDurableSavepoint(connection) {
    val runTable = "complaint_journal_scan_runs"
    val entryTable = "complaint_journal_scan_entries"
    val run = testTerminalScanRun(1)
    fun rejectRun(group: String, vararg changes: Pair<String, Any?>) = assertTestTerminalDurableSqlRejected(
        connection, constraint = "chk_complaint_scan_$group",
    ) { insertTestTerminalScan(sql, runTable, run + changes.toMap()) }
    val nonV4 = UUID.fromString("00000000-0000-1000-8000-000000000001")
    val live = UUID.fromString("00000000-0000-0000-0000-000000000000")
    rejectRun("scope", "test_only" to false)
    rejectRun("scope", "data_scope_id" to nonV4)
    for (column in listOf("scan_id", "restore_identity", "writer_generation")) rejectRun("identity", column to nonV4)
    for (pass in listOf(0, 3)) rejectRun("identity", "pass" to pass)
    for (column in listOf("desired_generation", "fencing_token", "cutoff_epoch")) rejectRun("identity", column to 0L)
    for (column in listOf("maximum_entries", "maximum_bytes")) rejectRun("capacity", column to 0L)
    for (column in listOf("entry_count", "entry_bytes")) rejectRun("capacity", column to -1L)
    rejectRun("capacity", "maximum_entries" to 1L, "entry_count" to 2L)
    rejectRun("capacity", "maximum_bytes" to 1L, "entry_bytes" to 2L)
    rejectRun("state", "manifest_hash" to ByteArray(32))
    rejectRun("state", "finished_at" to sizingTime)
    rejectRun("state", "state" to "UNKNOWN")
    rejectRun("state", "state" to "COMPLETE", "finished_at" to sizingTime)
    rejectRun("state", "state" to "COMPLETE", "manifest_hash" to ByteArray(32))
    for (size in listOf(0, 31, 33)) rejectRun("state", "state" to "COMPLETE", "manifest_hash" to ByteArray(size), "finished_at" to sizingTime)
    rejectRun("state", "state" to "ABANDONED")
    rejectRun("state", "state" to "ABANDONED", "manifest_hash" to ByteArray(32), "finished_at" to sizingTime)
    assertTestTerminalDurableSqlRejected(connection, state = "22001") { insertTestTerminalScan(sql, runTable, run + ("state" to "X".repeat(17))) }
    for (column in run.keys - setOf("manifest_hash", "finished_at")) {
        assertTestTerminalDurableSqlRejected(connection, state = "23502") { insertTestTerminalScan(sql, runTable, run + (column to null)) }
    }
    for (pass in 1..2) assertEquals(1, insertTestTerminalScan(sql, runTable, testTerminalScanRun(pass)))
    assertTestTerminalDurableSqlRejected(connection, state = "23505") { insertTestTerminalScan(sql, runTable, run) }
    for (column in listOf("started_at", "finished_at")) for (infinite in listOf("infinity", "-infinity")) {
        assertTestTerminalDurableSqlRejected(connection, constraint = "chk_complaint_scan_times") {
            val assignment = if (column == "finished_at") "finished_at = ?::timestamptz" else "finished_at = started_at,started_at = ?::timestamptz"
            sql.update("UPDATE complaint_journal_scan_runs SET state = 'ABANDONED',$assignment " +
                "WHERE scan_id = ? AND pass = 1", infinite, TEST_TERMINAL_SCAN_PAIR)
        }
    }
    val entry = testTerminalScanEntry(1, "INSTALLATION_MANIFEST", 710)
    fun rejectEntry(group: String, vararg changes: Pair<String, Any?>) = assertTestTerminalDurableSqlRejected(
        connection, constraint = "chk_complaint_scan_entry_$group",
    ) { insertTestTerminalScan(sql, entryTable, entry + changes.toMap()) }
    rejectEntry("scope", "data_scope_id" to live)
    // Keep every other CHECK true so a terminal TEST-only kind cannot mask the intended scope rejection.
    rejectEntry("scope", "test_only" to false, "event_kind" to "OWNER_DELETE")
    for (column in listOf("object_key", "object_version")) {
        for (bad in listOf("", "x".repeat(1025), "é".repeat(513))) rejectEntry("identity", column to bad)
    }
    rejectEntry("identity", "object_version" to "null")
    for (column in listOf("ciphertext_hash", "semantic_hash")) for (size in listOf(0, 31, 33)) rejectEntry("identity", column to ByteArray(size))
    rejectEntry("identity", "writer_generation" to nonV4)
    for (column in listOf("journal_epoch", "entry_bytes")) rejectEntry("identity", column to 0L)
    rejectEntry("kind", "event_id" to null)
    rejectEntry("kind", "event_id" to "A".repeat(42))
    rejectEntry("kind", "event_id" to "A".repeat(42) + "B")
    rejectEntry("kind", "event_kind" to "EPOCH_SEAL")
    rejectEntry("kind", "event_kind" to "UNKNOWN")
    for (kind in listOf("INSTALLATION_MANIFEST", "TEST_RUN_PURGE")) {
        rejectEntry("kind", "event_kind" to kind, "data_scope_id" to live, "test_only" to false)
    }
    rejectEntry("replay", "replay_state" to "UNKNOWN")
    for ((column, size) in listOf("event_id" to 44, "event_kind" to 33, "replay_state" to 17)) {
        assertTestTerminalDurableSqlRejected(connection, state = "22001") { insertTestTerminalScan(sql, entryTable, entry + (column to "A".repeat(size))) }
    }
    for (column in entry.keys - "event_id") {
        assertTestTerminalDurableSqlRejected(connection, state = "23502") { insertTestTerminalScan(sql, entryTable, entry + (column to null)) }
    }
    for (change in listOf("pass" to 3, "scan_id" to TEST_TERMINAL_CAPACITY_TOKEN, "data_scope_id" to TEST_TERMINAL_CAPACITY_TOKEN)) {
        assertTestTerminalDurableSqlRejected(connection, state = "23503", constraint = "fk_complaint_scan_entry_run") { insertTestTerminalScan(sql, entryTable, entry + change) }
    }
    assertEquals(1, insertTestTerminalScan(sql, entryTable, entry))
    assertTestTerminalDurableSqlRejected(connection, state = "23505", constraint = "pk_complaint_scan_entries") { insertTestTerminalScan(sql, entryTable, entry) }
    assertEquals(1, insertTestTerminalScan(sql, entryTable, entry + mapOf("pass" to 2)))
    assertEquals(1, insertTestTerminalScan(sql, entryTable, entry + mapOf("object_key" to "k", "object_version" to "v", "journal_epoch" to 1L, "entry_bytes" to 1L)))
    assertEquals(1, insertTestTerminalScan(sql, entryTable, entry + mapOf("object_key" to "é".repeat(512), "object_version" to "é".repeat(512))))
    assertEquals(1, insertTestTerminalScan(sql, runTable, run + mapOf(
        "scan_id" to TEST_TERMINAL_CAPACITY_TOKEN, "desired_generation" to 1L, "fencing_token" to 1L, "cutoff_epoch" to 1L,
        "maximum_entries" to 1L, "maximum_bytes" to 1L, "entry_count" to 0L, "entry_bytes" to 0L,
    )))
}

internal fun assertTestTerminalScanStoredRuns(sql: JdbcTemplate, state: String) {
    require(state in setOf("SCANNING", "COMPLETE", "ABANDONED"))
    for (pass in 1..2) {
        sql.queryForObject(
            "SELECT scan_id,pass,data_scope_id,test_only,restore_identity,desired_generation,fencing_token,writer_generation,cutoff_epoch," +
                "maximum_entries,maximum_bytes,entry_count,entry_bytes,state,manifest_hash,started_at,finished_at " +
                "FROM complaint_journal_scan_runs WHERE scan_id = ? AND pass = ?",
            { row, _ ->
                assertEquals(TEST_TERMINAL_SCAN_PAIR, row.getObject(1, UUID::class.java))
                assertEquals(pass, row.getInt(2))
                assertEquals(TEST_TERMINAL_CAPACITY_SCOPE, row.getObject(3, UUID::class.java))
                assertTrue(row.getBoolean(4))
                for (column in listOf(5, 8)) assertEquals(TEST_TERMINAL_CAPACITY_TOKEN, row.getObject(column, UUID::class.java))
                for (column in listOf(6, 7, 9, 10, 11, 12, 13)) assertEquals(Long.MAX_VALUE, row.getLong(column))
                assertEquals(state, row.getString(14))
                assertArrayEquals(if (state == "COMPLETE") testTerminalDurableHash(byteArrayOf(101)) else null, row.getBytes(15))
                assertEquals(sizingTime.toInstant(), row.getTimestamp(16).toInstant())
                assertEquals(if (state == "SCANNING") null else sizingTime.toInstant(), row.getTimestamp(17)?.toInstant())
                true
            }, TEST_TERMINAL_SCAN_PAIR, pass,
        )
    }
}

internal fun assertTestTerminalScanStoredEntries(sql: JdbcTemplate, expected: List<Map<String, Any?>>) {
    expected.forEach { entry ->
        for (column in listOf("object_key", "object_version")) assertEquals(1024, entry.getValue(column).toString().toByteArray(Charsets.UTF_8).size)
        sql.queryForObject(
            "SELECT object_key,object_version,encode(ciphertext_hash,'hex'),encode(semantic_hash,'hex'),event_id,event_kind," +
                "pg_column_compression(object_key),pg_column_compression(object_version),pg_column_size(object_key),pg_column_size(object_version)," +
                "data_scope_id,test_only,writer_generation,journal_epoch,entry_bytes,replay_state " +
                "FROM complaint_journal_scan_entries WHERE scan_id = ? AND pass = ? AND object_key = ? AND object_version = ?",
            { row, _ ->
                assertEquals(entry["object_key"], row.getString(1))
                assertEquals(entry["object_version"], row.getString(2))
                assertEquals(java.util.HexFormat.of().formatHex(entry["ciphertext_hash"] as ByteArray), row.getString(3))
                assertEquals(java.util.HexFormat.of().formatHex(entry["semantic_hash"] as ByteArray), row.getString(4))
                assertEquals(entry["event_id"], row.getString(5))
                assertEquals(entry["event_kind"], row.getString(6))
                assertEquals(null, row.getString(7), "Maximum key must not be silently compressed")
                assertEquals(null, row.getString(8), "Maximum version must not be silently compressed")
                assertTrue(row.getLong(9) in 1024L..1028L && row.getLong(10) in 1024L..1028L)
                assertEquals(entry["data_scope_id"], row.getObject(11, UUID::class.java))
                assertEquals(entry["test_only"], row.getBoolean(12))
                assertEquals(entry["writer_generation"], row.getObject(13, UUID::class.java))
                assertEquals(entry["journal_epoch"], row.getLong(14))
                assertEquals(entry["entry_bytes"], row.getLong(15))
                assertEquals(entry["replay_state"], row.getString(16))
                true
            }, TEST_TERMINAL_SCAN_PAIR, entry["pass"], entry["object_key"], entry["object_version"],
        )
    }
}
