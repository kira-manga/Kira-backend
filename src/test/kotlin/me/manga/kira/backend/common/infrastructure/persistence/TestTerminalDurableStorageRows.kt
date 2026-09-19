package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableStorageProfileV1
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

/** Direct synthetic storage shapes for the existing rollback IT, not a writer, new harness or authority fixture. */
internal const val TEST_TERMINAL_DURABLE_TABLE = "complaint_test_terminal_intents"
internal val TEST_TERMINAL_DURABLE_CREATED: Instant = Instant.parse("2024-02-29T12:00:00Z")
internal val TEST_TERMINAL_DURABLE_FLOOR: Instant = Instant.parse("2034-02-28T12:00:00Z")
internal val TEST_TERMINAL_DURABLE_LAST: Instant = Instant.parse("9999-12-31T23:59:59Z")
internal val TEST_TERMINAL_DURABLE_FROZEN_COLUMNS = listOf(
    "wire_bytes", "wire_hash", "checksum_sha256", "content_type", "object_lock_mode",
    "retain_until", "metadata_bytes", "metadata_hash", "frozen_at",
)

private val durableTypes = (
    "schema_version:smallint,operation_token:uuid,data_scope_id:uuid,test_only:boolean," +
        "object_kind:character varying(32),object_ordinal:integer,object_id:character varying(43),object_key:text," +
        "routing_key_id:character varying(64),writer_generation:uuid,epoch_start:bigint,epoch_end:bigint," +
        "preparing_fencing_token:bigint,activation_catalog_generation:bigint,activation_catalog_hash:bytea," +
        "configuration_hash:bytea,journal_configuration_hash:bytea,terminal_encoding_hash:bytea," +
        "publication_ref:character varying(43),canonicalizer:character varying(16),canonical_bytes:bytea,canonical_hash:bytea," +
        "retention_floor:timestamp with time zone,created_at:timestamp with time zone,state:character varying(16)," +
        "wire_bytes:bytea,wire_hash:bytea,checksum_sha256:character varying(44),content_type:character varying(24)," +
        "object_lock_mode:character varying(10),retain_until:timestamp with time zone,metadata_bytes:bytea," +
        "metadata_hash:bytea,frozen_at:timestamp with time zone"
    ).split(',').associate { it.substringBefore(':') to it.substringAfter(':') }

// Kept separate from the closed, already-qualified pre-V21 capacity registry.
private val durableIndexes = linkedMapOf(
    "pk_complaint_test_terminal_intents" to (listOf("operation_token") to 48L),
    "uq_complaint_test_terminal_slot" to (listOf("data_scope_id", "object_kind", "object_ordinal") to 96L),
    "uq_complaint_test_terminal_key" to (listOf("object_key") to 1064L),
    "uq_complaint_test_terminal_id" to (listOf("object_id") to 80L),
    "idx_complaint_test_terminal_publication" to (listOf("publication_ref", "data_scope_id") to 96L),
)

internal fun testTerminalDurableHash(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
internal fun testTerminalDurableOpaque(seed: Int): String = Base64.getUrlEncoder().withoutPadding()
    .encodeToString(testTerminalDurableHash("durable-storage-test-$seed".toByteArray(Charsets.US_ASCII)))
internal fun testTerminalDurableToken(seed: Int): UUID = UUID.fromString("00000000-0000-4000-8000-${seed.toString().padStart(12, '0')}")

/** Deterministic independent SHA-256 blocks, not repeated compressible padding or runtime/provider randomness. */
internal fun testTerminalDurableIncompressible(size: Int, seed: Int): ByteArray = ByteArray(size).also { output ->
    var offset = 0
    var block = 0
    while (offset < size) {
        val bytes = testTerminalDurableHash(ByteBuffer.allocate(8).putInt(seed).putInt(block++).array())
        val count = minOf(bytes.size, size - offset)
        bytes.copyInto(output, offset, 0, count)
        offset += count
    }
}

internal fun testTerminalDurableCanonical(
    seed: Int,
    kind: String = "EPOCH_SEAL",
    ordinal: Int = 0,
    maximumFields: Boolean = false,
    canonical: ByteArray = byteArrayOf(33),
): Map<String, Any?> {
    val path = when (kind) {
        "INSTALLATION_MANIFEST" -> "installation-manifest"
        "TEST_RUN_PURGE" -> "test-run-purge"
        "EPOCH_SEAL" -> "epoch-seal"
        else -> error("Unknown synthetic storage kind")
    }
    val id = testTerminalDurableOpaque(seed)
    val epoch = if (maximumFields) Long.MAX_VALUE else 3L
    val route = if (maximumFields) "R".repeat(64) else "route_1"
    val declarationHash = testTerminalDurableHash(byteArrayOf(71))
    return linkedMapOf<String, Any?>(
        "schema_version" to 1, "operation_token" to testTerminalDurableToken(seed),
        "data_scope_id" to TEST_TERMINAL_CAPACITY_SCOPE, "test_only" to true,
        "object_kind" to kind, "object_ordinal" to ordinal, "object_id" to id,
        // The basename is a distinct routing token, not a false object-ID equality oracle.
        "object_key" to "complaints/journal/v1/$TEST_TERMINAL_CAPACITY_TOKEN/test/$TEST_TERMINAL_CAPACITY_SCOPE/seal-terminal/$epoch/$route/$path/${testTerminalDurableOpaque(seed + 10000)}.kjev",
        "routing_key_id" to route, "writer_generation" to TEST_TERMINAL_CAPACITY_TOKEN,
        "epoch_start" to if (kind == "EPOCH_SEAL") 1L else epoch, "epoch_end" to epoch,
        "preparing_fencing_token" to if (maximumFields) Long.MAX_VALUE else 1L,
        "activation_catalog_generation" to 65536L, "activation_catalog_hash" to declarationHash,
        "configuration_hash" to declarationHash, "journal_configuration_hash" to declarationHash,
        "terminal_encoding_hash" to declarationHash, "publication_ref" to if (kind == "EPOCH_SEAL") null else id,
        "canonicalizer" to "kcj-1", "canonical_bytes" to canonical, "canonical_hash" to testTerminalDurableHash(canonical),
        "retention_floor" to Timestamp.from(if (maximumFields) TEST_TERMINAL_DURABLE_LAST else TEST_TERMINAL_DURABLE_FLOOR),
        "created_at" to Timestamp.from(TEST_TERMINAL_DURABLE_CREATED), "state" to "CANONICAL",
    ).apply { TEST_TERMINAL_DURABLE_FROZEN_COLUMNS.forEach { put(it, null) } }
}

/** Independent expected metadata/checksum; deliberately never calls the SQL or MAIN metadata generator. */
internal fun testTerminalDurableFrozenFields(canonical: Map<String, Any?>, wire: ByteArray = byteArrayOf(34)): Map<String, Any?> {
    val hash = testTerminalDurableHash(wire)
    val until = (canonical.getValue("retention_floor") as Timestamp).toInstant()
    val at = (canonical.getValue("created_at") as Timestamp).toInstant().plusSeconds(1)
    val metadata = ("{\"kira-journal-ciphertext-sha256\":\"${HexFormat.of().formatHex(hash)}\"," +
        "\"kira-journal-event-id\":\"${canonical.getValue("object_id")}\",\"kira-journal-retain-until\":\"$until\"," +
        "\"kira-journal-schema\":\"1\"}").toByteArray(Charsets.US_ASCII)
    return linkedMapOf(
        "state" to "WIRE_FROZEN", "wire_bytes" to wire, "wire_hash" to hash,
        "checksum_sha256" to Base64.getEncoder().encodeToString(hash), "content_type" to "application/octet-stream",
        "object_lock_mode" to "COMPLIANCE", "retain_until" to Timestamp.from(until),
        "metadata_bytes" to metadata, "metadata_hash" to testTerminalDurableHash(metadata), "frozen_at" to Timestamp.from(at),
    )
}

internal fun insertTestTerminalDurable(sql: JdbcTemplate, values: Map<String, Any?>): Int {
    require(values.keys == durableTypes.keys)
    return sql.update(
        "INSERT INTO $TEST_TERMINAL_DURABLE_TABLE (${values.keys.joinToString(",")}) VALUES (${values.keys.joinToString(",") { "?" }})",
        *values.values.toTypedArray(),
    )
}

internal fun updateTestTerminalDurable(sql: JdbcTemplate, operation: Any?, values: Map<String, Any?>): Int {
    require(values.isNotEmpty() && values.keys.all { it in durableTypes })
    return sql.update(
        "UPDATE $TEST_TERMINAL_DURABLE_TABLE SET ${values.keys.joinToString(",") { "$it = ?" }} WHERE operation_token = ?",
        *(values.values + operation).toTypedArray(),
    )
}

internal fun <T> testTerminalDurableSavepoint(connection: Connection, action: () -> T): T {
    val savepoint = connection.setSavepoint()
    return try {
        action()
    } finally {
        connection.rollback(savepoint)
        connection.releaseSavepoint(savepoint)
    }
}

internal fun assertTestTerminalDurableSqlRejected(
    connection: Connection,
    state: String = "23514",
    constraint: String? = null,
    guard: Boolean = false,
    action: () -> Unit,
) = testTerminalDurableSavepoint(connection) {
    val failure = assertThrows<DataIntegrityViolationException> { action() }
    // The owned PostgreSQL driver is intentionally runtimeOnly. Inspect its actual structured
    // server error through the same JDBC/reflection seam used by existing wire-failure tests.
    val postgres = failure.mostSpecificCause as SQLException
    assertEquals("org.postgresql.util.PSQLException", postgres.javaClass.name)
    assertEquals(state, postgres.sqlState)
    val server = requireNotNull(postgres.javaClass.getMethod("getServerErrorMessage").invoke(postgres))
    assertEquals("org.postgresql.util.ServerErrorMessage", server.javaClass.name)
    val actualConstraint = server.javaClass.getMethod("getConstraint").invoke(server)
    if (constraint != null) assertEquals(constraint, actualConstraint)
    if (guard) {
        assertNull(actualConstraint, "The current-row transition guard, not a later CHECK, must reject this rewrite")
        assertEquals("Invalid TEST terminal storage transition", server.javaClass.getMethod("getMessage").invoke(server))
    }
}

internal fun assertTestTerminalDurableCanonicalConstraints(sql: JdbcTemplate, connection: Connection, row: Map<String, Any?>) {
    fun reject(group: String, vararg changes: Pair<String, Any?>) = assertTestTerminalDurableSqlRejected(
        connection, constraint = "chk_complaint_test_terminal_$group",
    ) { insertTestTerminalDurable(sql, row + changes.toMap()) }
    reject("scope", "schema_version" to 2)
    reject("scope", "test_only" to false)
    val live = UUID.fromString("00000000-0000-0000-0000-000000000000")
    reject("scope", "data_scope_id" to live, "object_key" to row.getValue("object_key").toString().replace(TEST_TERMINAL_CAPACITY_SCOPE.toString(), live.toString()))
    val nonV4 = UUID.fromString("00000000-0000-1000-8000-000000000001")
    reject("identity", "operation_token" to nonV4)
    reject("identity", "writer_generation" to nonV4, "object_key" to row.getValue("object_key").toString().replace(TEST_TERMINAL_CAPACITY_TOKEN.toString(), nonV4.toString()))
    for (id in listOf("A".repeat(42), "A".repeat(42) + "B")) reject("identity", "object_id" to id)
    reject("identity", "epoch_start" to 0L)
    reject("identity", "epoch_end" to 0L, "object_key" to row.getValue("object_key").toString().replace("/3/", "/0/"))
    reject("identity", "epoch_start" to 4L)
    reject("identity", "preparing_fencing_token" to 0L)
    for (generation in listOf(0L, 65537L)) reject("identity", "activation_catalog_generation" to generation)
    for (column in listOf("activation_catalog_hash", "configuration_hash", "journal_configuration_hash", "terminal_encoding_hash")) {
        reject("identity", column to ByteArray(31))
    }
    for (ordinal in listOf(-1, 16)) reject("kind", "object_ordinal" to ordinal)
    reject("kind", "publication_ref" to testTerminalDurableOpaque(110))
    reject("kind", "object_kind" to "OWNER_DELETE", "object_key" to row.getValue("object_key").toString().replace("/epoch-seal/", "//"))
    for (key in listOf(
        "", "k".repeat(1025), row.getValue("object_key").toString().replace("/seal-terminal/", "/ordinary/"),
        row.getValue("object_key").toString().replace("/3/", "/03/"),
        row.getValue("object_key").toString().replace("route_1", "routé"),
        row.getValue("object_key").toString().removeSuffix(".kjev"),
        row.getValue("object_key").toString().substringBeforeLast('/') + "/" + "A".repeat(42) + "B.kjev",
    )) reject("key", "object_key" to key)
    reject("key", "routing_key_id" to "mismatch")
    reject("key", "routing_key_id" to "bad/route", "object_key" to row.getValue("object_key").toString().replace("route_1", "bad/route"))
    assertTestTerminalDurableSqlRejected(connection, state = "22001") {
        insertTestTerminalDurable(sql, row + ("routing_key_id" to "R".repeat(65)))
    }
    reject("canonical", "canonicalizer" to "other")
    reject("canonical", "canonical_hash" to ByteArray(32))
    for (size in listOf(0, 65537)) {
        val bytes = testTerminalDurableIncompressible(size, 101)
        reject("canonical", "canonical_bytes" to bytes, "canonical_hash" to testTerminalDurableHash(bytes))
    }
    // PostgreSQL preserves microseconds, not arbitrary nanoseconds; avoid a value its input conversion rounds to a legal second.
    for (time in listOf(Instant.ofEpochSecond(-1), TEST_TERMINAL_DURABLE_CREATED.plusNanos(1000), TEST_TERMINAL_DURABLE_LAST.plusSeconds(1))) {
        reject("times", "created_at" to Timestamp.from(time))
        reject("times", "retention_floor" to Timestamp.from(time))
    }
    reject("times", "retention_floor" to Timestamp.from(TEST_TERMINAL_DURABLE_FLOOR.minusSeconds(1)))
    val frozen = testTerminalDurableFrozenFields(row)
    TEST_TERMINAL_DURABLE_FROZEN_COLUMNS.forEach { reject("state", it to frozen.getValue(it)) }
    for (column in durableTypes.keys - (TEST_TERMINAL_DURABLE_FROZEN_COLUMNS + listOf("publication_ref", "state"))) {
        assertTestTerminalDurableSqlRejected(connection, state = "23502") { insertTestTerminalDurable(sql, row + (column to null)) }
    }
    assertTestTerminalDurableSqlRejected(connection, guard = true) { insertTestTerminalDurable(sql, row + frozen) }
    assertTestTerminalDurableSqlRejected(connection, guard = true) { insertTestTerminalDurable(sql, row + ("state" to "UNKNOWN")) }
}

internal fun assertTestTerminalDurableFrozenConstraints(sql: JdbcTemplate, connection: Connection, row: Map<String, Any?>) {
    val operation = row.getValue("operation_token")
    val frozen = testTerminalDurableFrozenFields(row)
    fun reject(values: Map<String, Any?>) = assertTestTerminalDurableSqlRejected(connection, constraint = "chk_complaint_test_terminal_state") {
        updateTestTerminalDurable(sql, operation, values)
    }
    TEST_TERMINAL_DURABLE_FROZEN_COLUMNS.forEach { reject(frozen + (it to null)) }
    for (size in listOf(0, 98305)) reject(testTerminalDurableFrozenFields(row, testTerminalDurableIncompressible(size, 102)))
    reject(frozen + ("wire_hash" to ByteArray(32)))
    reject(frozen + ("checksum_sha256" to frozen.getValue("checksum_sha256").toString().removeSuffix("=")))
    reject(frozen + ("content_type" to "application/json"))
    reject(frozen + ("object_lock_mode" to "GOVERNANCE"))
    reject(testTerminalDurableFrozenFields(row + ("retention_floor" to Timestamp.from(TEST_TERMINAL_DURABLE_FLOOR.minusSeconds(1)))))
    for (time in listOf(TEST_TERMINAL_DURABLE_CREATED.minusSeconds(1), TEST_TERMINAL_DURABLE_FLOOR, TEST_TERMINAL_DURABLE_CREATED.plusNanos(1000))) {
        reject(frozen + ("frozen_at" to Timestamp.from(time)))
    }
    for (time in listOf(TEST_TERMINAL_DURABLE_FLOOR.plusNanos(1000), TEST_TERMINAL_DURABLE_LAST.plusSeconds(1))) {
        reject(frozen + ("retain_until" to Timestamp.from(time)))
    }
    for (metadata in listOf(ByteArray(0), ByteArray(513) { 120 }, (frozen.getValue("metadata_bytes") as ByteArray) + byteArrayOf(32))) {
        reject(frozen + mapOf("metadata_bytes" to metadata, "metadata_hash" to testTerminalDurableHash(metadata)))
    }
    reject(frozen + ("metadata_hash" to ByteArray(32)))

    val immutable = linkedMapOf<String, Any?>(
        "schema_version" to 2, "operation_token" to testTerminalDurableToken(999), "data_scope_id" to testTerminalDurableToken(998),
        "test_only" to false, "object_kind" to "TEST_RUN_PURGE", "object_ordinal" to 1, "object_id" to testTerminalDurableOpaque(999),
        "object_key" to testTerminalDurableCanonical(999).getValue("object_key"), "routing_key_id" to "different",
        "writer_generation" to testTerminalDurableToken(997), "epoch_start" to 2L, "epoch_end" to 4L, "preparing_fencing_token" to 2L,
        "activation_catalog_generation" to 65535L, "activation_catalog_hash" to ByteArray(32), "configuration_hash" to ByteArray(32),
        "journal_configuration_hash" to ByteArray(32), "terminal_encoding_hash" to ByteArray(32), "publication_ref" to testTerminalDurableOpaque(997),
        "canonicalizer" to "other", "canonical_bytes" to byteArrayOf(35), "canonical_hash" to testTerminalDurableHash(byteArrayOf(35)),
        "retention_floor" to Timestamp.from(TEST_TERMINAL_DURABLE_FLOOR.plusSeconds(1)),
        "created_at" to Timestamp.from(TEST_TERMINAL_DURABLE_CREATED.minusSeconds(1)),
    )
    assertEquals(durableTypes.keys.take(24), immutable.keys.toList())
    immutable.forEach { (column, changed) ->
        assertTestTerminalDurableSqlRejected(connection, guard = true) { updateTestTerminalDurable(sql, operation, frozen + (column to changed)) }
    }
    assertTestTerminalDurableSqlRejected(connection, guard = true) { updateTestTerminalDurable(sql, operation, mapOf("canonical_bytes" to byteArrayOf(35))) }
    assertTestTerminalDurableSqlRejected(connection, guard = true) { sql.update("DELETE FROM $TEST_TERMINAL_DURABLE_TABLE WHERE operation_token = ?", operation) }
}

internal fun assertTestTerminalDurableSchema(sql: JdbcTemplate) {
    val migration = checkNotNull(TestTerminalCapacityIT::class.java.getResourceAsStream("/db/migration/V21__test_terminal_durable_intents.sql")).use { it.readBytes() }
    assertEquals("a2c32d617157de93964112cc5aeb7b00f0a8370aad92072f8611404fba51f4f2", HexFormat.of().formatHex(testTerminalDurableHash(migration)))
    assertEquals(34, durableTypes.size)
    val nullable = TEST_TERMINAL_DURABLE_FROZEN_COLUMNS + "publication_ref"
    assertEquals(durableTypes.map { (name, type) -> Triple(name, type, name !in nullable) }, sql.query(
        "SELECT attname, format_type(atttypid,atttypmod), attnotnull FROM pg_attribute " +
            "WHERE attrelid = ?::regclass AND attnum > 0 AND NOT attisdropped ORDER BY attnum",
        { row, _ -> Triple(row.getString(1), row.getString(2), row.getBoolean(3)) }, TEST_TERMINAL_DURABLE_TABLE,
    ))
    assertEquals(durableIndexes.keys.sorted(), sql.query(
        "SELECT idx.relname FROM pg_index i JOIN pg_class idx ON idx.oid = i.indexrelid JOIN pg_am am ON am.oid = idx.relam " +
            "WHERE i.indrelid = ?::regclass AND am.amname = 'btree' AND i.indisvalid AND i.indisready " +
            "AND i.indpred IS NULL AND i.indexprs IS NULL AND i.indnatts = i.indnkeyatts ORDER BY idx.relname",
        { row, _ -> row.getString(1) }, TEST_TERMINAL_DURABLE_TABLE,
    ))
    assertEquals(5L, sql.queryForObject("SELECT count(*) FROM pg_index WHERE indrelid = ?::regclass", Long::class.java, TEST_TERMINAL_DURABLE_TABLE))
    durableIndexes.forEach { (name, shape) ->
        assertEquals(shape.first.joinToString(","), sql.queryForObject(
            "SELECT string_agg(a.attname::text, ',' ORDER BY k.n) FROM pg_index i " +
                "CROSS JOIN LATERAL unnest(i.indkey::smallint[]) WITH ORDINALITY k(attnum,n) " +
                "JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.attnum WHERE i.indexrelid = ?::regclass",
            String::class.java, name,
        ))
        assertEquals(!name.startsWith("idx_"), sql.queryForObject("SELECT indisunique FROM pg_index WHERE indexrelid = ?::regclass", Boolean::class.java, name))
    }
    assertEquals(listOf(
        "fk_complaint_test_terminal_control:data_scope_id:complaint_journal_control:data_scope_id:r:r:false:false",
        "fk_complaint_test_terminal_publication:publication_ref,data_scope_id:complaint_journal_publications:event_id,data_scope_id:r:r:false:false",
        "fk_complaint_test_terminal_run:data_scope_id:complaint_test_runs:data_scope_id:r:r:false:false",
    ), sql.query(
        "SELECT f.conname, (SELECT string_agg(a.attname::text, ',' ORDER BY k.n) FROM unnest(f.conkey) WITH ORDINALITY k(attnum,n) " +
            "JOIN pg_attribute a ON a.attrelid = f.conrelid AND a.attnum = k.attnum), f.confrelid::regclass::text, " +
            "(SELECT string_agg(a.attname::text, ',' ORDER BY k.n) FROM unnest(f.confkey) WITH ORDINALITY k(attnum,n) " +
            "JOIN pg_attribute a ON a.attrelid = f.confrelid AND a.attnum = k.attnum), " +
            "f.confupdtype::text, f.confdeltype::text, f.condeferrable, f.condeferred " +
            "FROM pg_constraint f WHERE f.conrelid = ?::regclass AND f.contype = 'f' ORDER BY f.conname",
        { row, _ -> (1..6).joinToString(":") { row.getString(it) } + ":${row.getBoolean(7)}:${row.getBoolean(8)}" }, TEST_TERMINAL_DURABLE_TABLE,
    ))
    assertTestTerminalDurableForeignKeyIndexes(sql)
}

/** Regression for the independently found missing leading publication_ref,data_scope_id index. */
internal fun assertTestTerminalDurableForeignKeyIndexes(sql: JdbcTemplate) {
    assertEquals(emptyList<String>(), sql.query(
        "SELECT f.conname FROM pg_constraint f WHERE f.conrelid = ?::regclass AND f.contype = 'f' AND NOT EXISTS (" +
            "SELECT 1 FROM pg_index i JOIN pg_class idx ON idx.oid = i.indexrelid JOIN pg_am am ON am.oid = idx.relam " +
            "WHERE i.indrelid = f.conrelid AND am.amname = 'btree' AND i.indisvalid AND i.indisready " +
            "AND i.indpred IS NULL AND i.indexprs IS NULL AND i.indnkeyatts >= cardinality(f.conkey) " +
            "AND (SELECT array_agg(k.attnum ORDER BY k.n) FROM unnest(i.indkey::smallint[]) WITH ORDINALITY k(attnum,n) " +
            "WHERE k.n <= cardinality(f.conkey)) = f.conkey) ORDER BY f.conname",
        { row, _ -> row.getString(1) }, TEST_TERMINAL_DURABLE_TABLE,
    ), "Every V21 FK requires a real leading, nonpartial valid B-tree index; slot and object-ID indexes cannot cover the composite publication FK")
}

internal fun testTerminalDurableRows(sql: JdbcTemplate): List<String> = sql.query(
    "SELECT ctid::text || ':' || xmin::text || ':' || to_jsonb(r)::text FROM $TEST_TERMINAL_DURABLE_TABLE r ORDER BY operation_token",
    { row, _ -> row.getString(1) },
)

/** Hash every preexisting row in every touched table, including all LIVE control/counter rows, not merely counts. */
internal fun testTerminalDurableSnapshot(sql: JdbcTemplate): Map<String, List<String>> = listOf(
    "complaint_catalog_mutations", "complaint_test_runs", "complaint_journal_control", "complaint_journal_publications",
    "complaint_recovery_capacity_reservations", "complaint_capacity_counters", TEST_TERMINAL_DURABLE_TABLE,
).associateWith { table ->
    sql.query("SELECT encode(sha256(convert_to(to_jsonb(r)::text, 'UTF8')), 'hex') FROM $table r ORDER BY 1", { row, _ -> row.getString(1) })
}

internal fun assertTestTerminalDurableStoredBytes(sql: JdbcTemplate, expected: Map<String, Any?>) {
    sql.queryForObject(
        "SELECT canonical_bytes,canonical_hash,wire_bytes,wire_hash,checksum_sha256,metadata_bytes,metadata_hash " +
            "FROM $TEST_TERMINAL_DURABLE_TABLE WHERE operation_token = ?",
        { row, _ ->
            listOf("canonical_bytes" to 1, "canonical_hash" to 2, "wire_bytes" to 3, "wire_hash" to 4, "metadata_bytes" to 6, "metadata_hash" to 7).forEach { (name, column) ->
                assertArrayEquals(expected[name] as ByteArray?, row.getBytes(column), name)
            }
            assertEquals(expected["checksum_sha256"], row.getString(5))
            true
        }, expected["operation_token"],
    )
}

/** Explicitly detoasted logical row/key inputs, separately bounded from physical allocation observations below. */
internal fun measureTestTerminalDurableLogical(sql: JdbcTemplate, operation: Any?, minimumPayload: Long): List<Long> {
    fun inline(name: String): String = when (val type = durableTypes.getValue(name)) {
        "bytea" -> "$name || ''::bytea"
        else -> if (type == "text" || type.startsWith("character varying")) "$name || ''::text" else name
    }
    val fields = listOf(durableTypes.keys.toList()) + durableIndexes.values.map { it.first }
    val projections = fields.joinToString(",") { names -> "pg_column_size(ROW(${names.joinToString(",", transform = ::inline)}))" }
    val sizes = checkNotNull(sql.queryForObject(
        "SELECT $projections FROM $TEST_TERMINAL_DURABLE_TABLE WHERE operation_token = ?",
        { row, _ -> fields.indices.map { row.getLong(it + 1) } }, operation,
    ))
    assertTrue(sizes.first() in minimumPayload..166_208L, "Measure detoasted payloads, never just external TOAST pointers")
    sizes.drop(1).zip(durableIndexes.entries).forEach { (size, index) ->
        assertTrue(size in 1L..index.value.second, "Unqualified V21 index input: ${index.key}")
    }
    assertTrue(sizes.sum() * 8 <= TestTerminalDurableStorageProfileV1.LIFECYCLE_MAX_STORAGE_BYTES)
    return sizes
}

internal data class TestTerminalDurablePhysical(
    val heapMain: Long,
    val heapAuxiliary: Long,
    val indexes: Map<String, Long>,
    val toastHeapMain: Long,
    val toastAuxiliary: Long,
    val toastIndexes: Map<String, Long>,
    val total: Long,
    val visibleToastValues: Long,
    val visibleToastChunks: Long,
    val visibleToastPayload: Long,
) {
    fun record(stage: String) {
        // Only bounded synthetic size observations; never row contents, keys, connection details or authority evidence.
        println("TEST_TERMINAL_DURABLE_STORAGE_V1 stage=$stage heap=$heapMain heap_aux=$heapAuxiliary indexes=$indexes " +
            "toast_heap=$toastHeapMain toast_aux=$toastAuxiliary toast_indexes=$toastIndexes total=$total " +
            "toast_values=$visibleToastValues toast_chunks=$visibleToastChunks toast_payload=$visibleToastPayload")
    }
}

/** Main/FSM/VM/init and every parent/TOAST index are accounted, not only pg_column_size or pg_relation_size(parent). */
internal fun measureTestTerminalDurablePhysical(sql: JdbcTemplate): TestTerminalDurablePhysical {
    val toast = checkNotNull(sql.queryForObject("SELECT reltoastrelid::regclass::text FROM pg_class WHERE oid = ?::regclass", String::class.java, TEST_TERMINAL_DURABLE_TABLE))
    require(toast.matches(Regex("pg_toast\\.pg_toast_[0-9]+")))
    fun relation(table: String): List<Long> = checkNotNull(sql.queryForObject(
        "SELECT pg_relation_size(?::regclass), pg_table_size(?::regclass), pg_total_relation_size(?::regclass)",
        { row, _ -> (1..3).map { row.getLong(it) } }, table, table, table,
    ))
    fun indexes(table: String): Map<String, Long> = sql.query(
        "SELECT idx.relname, pg_relation_size(idx.oid) + pg_relation_size(idx.oid,'fsm') + " +
            "pg_relation_size(idx.oid,'vm') + pg_relation_size(idx.oid,'init') FROM pg_index i " +
            "JOIN pg_class idx ON idx.oid = i.indexrelid WHERE i.indrelid = ?::regclass ORDER BY idx.relname",
        { row, _ -> row.getString(1) to row.getLong(2) }, table,
    ).toMap()
    val parentSize = relation(TEST_TERMINAL_DURABLE_TABLE)
    val toastSize = relation(toast)
    val parentIndexes = indexes(TEST_TERMINAL_DURABLE_TABLE)
    val toastIndexes = indexes(toast)
    val chunks = checkNotNull(sql.queryForObject(
        "SELECT count(DISTINCT chunk_id),count(*),coalesce(sum(octet_length(chunk_data)),0) FROM $toast",
        { row, _ -> (1..3).map { row.getLong(it) } },
    ))
    assertEquals(durableIndexes.keys.sorted(), parentIndexes.keys.toList())
    assertEquals(1, toastIndexes.size)
    assertEquals(parentSize[2], parentSize[1] + parentIndexes.values.sum())
    assertEquals(toastSize[2], toastSize[1] + toastIndexes.values.sum())
    return TestTerminalDurablePhysical(
        parentSize[0], parentSize[1] - parentSize[0] - toastSize[2], parentIndexes,
        toastSize[0], toastSize[1] - toastSize[0], toastIndexes, parentSize[2], chunks[0], chunks[1], chunks[2],
    ).also { measured ->
        assertTrue(listOf(measured.heapMain, measured.heapAuxiliary, measured.toastHeapMain, measured.toastAuxiliary).all { it >= 0 })
        assertEquals(measured.total, measured.heapMain + measured.heapAuxiliary + measured.indexes.values.sum() +
            measured.toastHeapMain + measured.toastAuxiliary + measured.toastIndexes.values.sum())
    }
}
