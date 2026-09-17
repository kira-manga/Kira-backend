package me.manga.kira.backend.database.complaint

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import java.security.MessageDigest
import java.sql.Connection
import java.util.HexFormat

/**
 * Actual PostgreSQL logical composite-row/key projections on the ALREADY owned migration schema.
 * These maximal CHECK/FK-legal sizing tuples are not canonical evidence or protocol transitions.
 * Detoasted logical bytes do not measure heap/index pages, TOAST overhead, MVCC, WAL, vacuum or
 * physical reserve P. This test-only ceiling is neither a LIVE baseline charge nor a TEST producer.
 * Every change rolls back; no configuration, capacity counter or activation authority is produced.
 */
internal fun assertRotationControlFootprint(sql: Connection) = sql.withRollbackPoint {
    assertControlFootprintInventory(sql)
    val maximum = CONTROL_FOOTPRINT_COLUMNS.filterKeys { it !in setOf("data_scope_id", "test_only") }
        .mapValues { (column, type) -> maximumControlValue(column, type) }
    fun absent(columns: Iterable<String>): Map<String, String> = columns.associateWith { "NULL" }
    val emptyRotation = absent(CONTROL_FOOTPRINT_COLUMNS.keys.filter { it.startsWith("rotation_") }) + ("rotation_sequence" to "0")
    val noSeal = absent(CONTROL_FOOTPRINT_COLUMNS.keys.filter { it.startsWith("seal_") })
    val noCheckpoint = absent(CONTROL_FOOTPRINT_COLUMNS.keys.filter { it.startsWith("checkpoint_") })
    val noLeases = absent(listOf("lease_owner", "lease_expires_at", "retention_lease_owner", "retention_lease_expires_at"))
    val requested = absent(listOf("rotation_capture_owner", "rotation_capture_token", "rotation_captured_at", "rotation_epoch_after")) + mapOf(
        "rotation_state" to "'REQUESTED'", "publication_epoch" to (Long.MAX_VALUE - 1).toString(),
    )
    val preparedSeal = absent(
        listOf("seal_object_version", "seal_ciphertext_hash", "seal_retain_until", "seal_verified_at", "seal_verification_bytes", "seal_verification_hash"),
    ) + ("seal_state" to "'SEAL_PREPARED'")
    val unconfigured = absent(
        listOf(
            "desired_configuration_hash", "database_identity", "restore_identity", "event_writer_generation", "accepted_catalog_generation",
            "accepted_catalog_hash", "trust_bundle_hash", "catalog_writer_generation", "pending_projection_token",
        ),
    )
    val phases = listOf(
        "unconfigured closed" to (unconfigured + noLeases + noSeal + noCheckpoint + emptyRotation),
        "configured leased with empty slots" to (noSeal + noCheckpoint + emptyRotation),
        "prepared seal and complete checkpoint with empty rotation" to (preparedSeal + emptyRotation),
        "verified seal and complete checkpoint with empty rotation" to emptyRotation,
        "maximal requested rotation" to requested,
        "maximal captured rotation" to mapOf("scan_requested" to "false"),
        "retained history after lease release and projection" to (noLeases + mapOf(
            "pending_projection_token" to "NULL", "maintenance_closed" to "false", "creation_closed" to "false",
        )),
    )
    for ((phase, changes) in phases) {
        val values = maximum + changes
        sql.createStatement().use { statement ->
            statement.queryTimeout = 30
            assertEquals(
                2,
                statement.executeUpdate(
                    "UPDATE complaint_journal_control SET " + values.entries.joinToString(",") { (column, value) -> "$column=$value" } +
                        " WHERE data_scope_id IN ('$LIVE_SCOPE','$TEST_SCOPE')",
                ),
                phase,
            )
        }
        measureControlFootprint(sql, phase, values)
    }
}

private fun assertControlFootprintInventory(sql: Connection) {
    // Freeze the source bounds as well as the column inventory: changing a CHECK/helper without
    // adding a column must not silently reuse this ceiling. V15/V16 do not alter this table.
    for ((name, digest) in mapOf(
        "V14__backend_owned_complaints.sql" to "68bf2e7e5e5baf80dbaaeeff1ba8dc743c6e473778ab8df6ed8bd4f1f619cb75",
        "V17__journal_rotation_slot.sql" to "6ae3213476dd66bb62cd610505ffb1e0f3200aeb3a2be64e7b7bd5210a8141fe",
    )) {
        assertEquals(digest, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(complaintResourceBytes("db/migration/$name"))), name)
    }
    assertEquals(listOf("UTF8"), sql.strings("SHOW server_encoding"))
    assertEquals(75, CONTROL_FOOTPRINT_COLUMNS.size) // V14's 54 plus V17's 21, not just the empty seed.
    assertEquals(
        CONTROL_FOOTPRINT_COLUMNS.map { (column, type) -> "$column:$type" },
        sql.strings(
            "SELECT attname || ':' || format_type(atttypid,atttypmod) FROM pg_attribute " +
                "WHERE attrelid='complaint_journal_control'::regclass AND attnum>0 AND NOT attisdropped ORDER BY attnum",
        ),
    )
    // Both indexes have exactly one ordinary key, no INCLUDE columns/predicate, and must be usable.
    assertEquals(
        listOf(
            "idx_complaint_control_projection|pending_projection_token|btree|false|1|1|true|true",
            "pk_complaint_journal_control|data_scope_id|btree|true|1|1|true|true",
        ),
        sql.strings(
            "SELECT c.relname || '|' || pg_get_indexdef(i.indexrelid,1,true) || '|' || am.amname || '|' || i.indisunique::text || '|' || " +
                "i.indnkeyatts::text || '|' || i.indnatts::text || '|' || (i.indpred IS NULL)::text || '|' || " +
                "(i.indisvalid AND i.indisready AND i.indislive)::text " +
                "FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid JOIN pg_am am ON am.oid=c.relam " +
                "WHERE i.indrelid='complaint_journal_control'::regclass ORDER BY c.relname",
        ),
    )
}

private fun maximumControlValue(column: String, type: String): String = when (type) {
    "uuid" -> if (column == "pending_projection_token") "'63000000-0000-4000-8000-000000000001'" else "'$CONTROL_FOOTPRINT_UUID'"
    "boolean" -> "true"
    "bigint" -> when {
        column.endsWith("catalog_generation") -> "65536"
        column == "rotation_epoch_before" -> (Long.MAX_VALUE - 1).toString()
        // Fixed-width cutoffs can precede both maximal rotation epochs without reducing their size.
        column in setOf("seal_epoch", "checkpoint_cutoff_epoch") -> (Long.MAX_VALUE - 2).toString()
        else -> Long.MAX_VALUE.toString()
    }
    "integer" -> "1"
    "timestamp with time zone" -> FIXTURE_INSTANT // All finite timestamps have the same fixed width.
    "bytea" -> when {
        column in CONTROL_FOOTPRINT_DOCUMENTS -> CONTROL_FOOTPRINT_DOCUMENT
        column.removeSuffix("_hash") + "_bytes" in CONTROL_FOOTPRINT_DOCUMENTS -> "sha256($CONTROL_FOOTPRINT_DOCUMENT)"
        else -> FIXTURE_DIGEST
    }
    else -> when (column) {
        "seal_state" -> "'SEAL_VERIFIED'"
        "checkpoint_result" -> "'SUCCESS'"
        "rotation_state" -> "'CAPTURED'"
        "seal_object_key" -> "repeat('k',1024)"
        "seal_object_version" -> "repeat('😀',256)" // The opaque maximum is UTF-8 bytes, not characters.
        else -> error("Unaccounted control field $column:$type")
    }
}

private fun measureControlFootprint(sql: Connection, phase: String, values: Map<String, String>) {
    fun valueBytes(column: String): Long = when {
        values[column] == "NULL" -> 0
        column == "rotation_state" && values[column] == "'CAPTURED'" -> 8
        else -> controlMaximumPayloadBytes(column)
    }
    val fullFields = CONTROL_FOOTPRINT_COLUMNS.keys
    // Concatenation allocates each complete value: repeated fixture bytes must not hide behind
    // heap compression or TOAST pointers. ROW(index-key) is a logical key projection, not a page.
    fun inline(column: String): String = when (CONTROL_FOOTPRINT_COLUMNS.getValue(column)) {
        "bytea" -> "$column || ''::bytea"
        "text", "character varying(16)" -> "$column || ''::text"
        else -> column
    }
    val projections = listOf(fullFields.toList()) + CONTROL_FOOTPRINT_INDEX_KEYS.map { listOf(it) }
    val expressions = projections.joinToString(",") { columns -> "pg_column_size(ROW(${columns.joinToString(",", transform = ::inline)}))" }
    val maximalShape = fullFields.joinToString(" AND ") { column ->
        when {
            values[column] == "NULL" -> "$column IS NULL"
            variableControlField(column) -> "octet_length($column)=${valueBytes(column)}"
            else -> "$column IS NOT NULL"
        }
    }
    val totalCeiling = projections.sumOf(::controlTupleCeiling)
    sql.createStatement().use { statement ->
        statement.queryTimeout = 30
        statement.executeQuery(
            "SELECT data_scope_id::text,($maximalShape) IS TRUE,$expressions FROM complaint_journal_control " +
                "WHERE data_scope_id IN ('$LIVE_SCOPE','$TEST_SCOPE') ORDER BY data_scope_id",
        ).use { result ->
            val scopes = mutableListOf<String>()
            val measurements = mutableListOf<List<Long>>()
            while (result.next()) {
                val scope = result.getString(1)
                scopes += scope
                assertTrue(result.getBoolean(2), "$phase/$scope must store the complete maximal legal fields")
                val sizes = projections.mapIndexed { index, columns ->
                    val size = result.getLong(index + 3)
                    val minimum = columns.sumOf(::valueBytes).coerceAtLeast(1)
                    assertTrue(size in minimum..controlTupleCeiling(columns), "$phase/$scope projection $index logical bytes=$size")
                    size
                }
                assertTrue(sizes.sum() <= totalCeiling, "$phase/$scope exceeds the source-derived logical ceiling")
                measurements += sizes
            }
            assertEquals(listOf(LIVE_SCOPE, TEST_SCOPE), scopes)
            assertEquals(measurements[0], measurements[1], "LIVE/TEST row shapes have the same logical envelope, not the same charging rule")
        }
    }
}

private fun variableControlField(column: String): Boolean = CONTROL_FOOTPRINT_COLUMNS.getValue(column) in
    setOf("bytea", "text", "character varying(16)")

private fun controlMaximumPayloadBytes(column: String): Long = when (CONTROL_FOOTPRINT_COLUMNS.getValue(column)) {
    "uuid" -> 16
    "boolean" -> 1
    "integer" -> 4
    "bigint", "timestamp with time zone" -> 8
    "bytea" -> if (column in CONTROL_FOOTPRINT_DOCUMENTS) 65536 else 32
    else -> when (column) {
        "seal_object_key", "seal_object_version" -> 1024
        "seal_state" -> 13 // Both legal seal names; varchar(16) is not permission for other values.
        "checkpoint_result" -> 7
        "rotation_state" -> 9 // REQUESTED is longer; CAPTURED adds the complete capture tuple.
        else -> error("Unbounded control field $column")
    }
}

/**
 * Source ceiling, NOT an observed size or production charge: full payload plus <=4-byte varlena
 * headers, <=7 alignment bytes per attribute, and 64 bytes for composite header/null bitmap/alignment
 * (75 attributes need 10 bitmap bytes). Also charge that same conservative header to each key ROW.
 * The payload includes all three 64-KiB documents, both 1024-byte strings and twelve 32-byte hashes.
 * The payload ceiling deliberately combines the longer REQUESTED name with CAPTURED's populated
 * fields, overestimating a legal row by one byte. No mutable storage-size heuristic supplies a bound.
 */
private fun controlTupleCeiling(columns: Collection<String>): Long = 64L + columns.sumOf { column ->
    controlMaximumPayloadBytes(column) + (if (variableControlField(column)) 4 else 0) + 7
}

private const val CONTROL_FOOTPRINT_UUID = "64000000-0000-4000-8000-000000000001"
private const val CONTROL_FOOTPRINT_DOCUMENT = "decode(repeat('ab',65536),'hex')"
private val CONTROL_FOOTPRINT_DOCUMENTS = setOf("seal_bytes", "seal_verification_bytes", "checkpoint_bytes")
private val CONTROL_FOOTPRINT_INDEX_KEYS = listOf("data_scope_id", "pending_projection_token")
private val CONTROL_FOOTPRINT_COLUMNS = (
    "data_scope_id:uuid,test_only:boolean,publication_epoch:bigint,desired_generation:bigint,implementation_schema:integer," +
        "desired_configuration_hash:bytea,database_identity:uuid,restore_identity:uuid,event_writer_generation:uuid," +
        "accepted_catalog_generation:bigint,accepted_catalog_hash:bytea,trust_bundle_hash:bytea,catalog_writer_generation:uuid," +
        "pending_projection_token:uuid,maintenance_closed:boolean,creation_closed:boolean,scan_requested:boolean," +
        "lease_owner:uuid,lease_token:bigint,lease_expires_at:timestamp with time zone,retention_lease_owner:uuid,retention_lease_token:bigint," +
        "retention_lease_expires_at:timestamp with time zone,seal_state:character varying(16),seal_epoch:bigint,seal_writer_generation:uuid," +
        "seal_operation_token:uuid,seal_object_key:text,seal_bytes:bytea,seal_hash:bytea,seal_object_version:text,seal_ciphertext_hash:bytea," +
        "seal_retain_until:timestamp with time zone,seal_verified_at:timestamp with time zone,seal_verification_bytes:bytea,seal_verification_hash:bytea," +
        "checkpoint_generation:bigint,checkpoint_fencing_token:bigint,checkpoint_catalog_generation:bigint,checkpoint_catalog_hash:bytea," +
        "checkpoint_writer_generation:uuid,checkpoint_cutoff_epoch:bigint,checkpoint_configuration_hash:bytea,checkpoint_database_identity:uuid," +
        "checkpoint_restore_identity:uuid,checkpoint_schema:integer,checkpoint_started_at:timestamp with time zone," +
        "checkpoint_completed_at:timestamp with time zone,checkpoint_object_count:bigint,checkpoint_byte_count:bigint," +
        "checkpoint_result:character varying(16),checkpoint_bytes:bytea,checkpoint_hash:bytea,updated_at:timestamp with time zone," +
        "rotation_sequence:bigint,rotation_id:uuid,rotation_state:text,rotation_epoch_before:bigint,rotation_implementation_schema:integer," +
        "rotation_desired_generation:bigint,rotation_desired_configuration_hash:bytea,rotation_database_identity:uuid,rotation_restore_identity:uuid," +
        "rotation_event_writer_generation:uuid,rotation_accepted_catalog_generation:bigint,rotation_accepted_catalog_hash:bytea," +
        "rotation_trust_bundle_hash:bytea,rotation_catalog_writer_generation:uuid,rotation_request_owner:uuid,rotation_request_token:bigint," +
        "rotation_requested_at:timestamp with time zone,rotation_capture_owner:uuid,rotation_capture_token:bigint," +
        "rotation_captured_at:timestamp with time zone,rotation_epoch_after:bigint"
    ).split(',').associate { it.substringBefore(':') to it.substringAfter(':') }
