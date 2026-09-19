package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.actualPool
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationFrozenV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationHistoryV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.sql.ResultSet
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Supplied compact rows only: no authenticated 65k history, SQL-fingerprint or real JDBC-buffering claim. */
class CatalogTestRunActivationHistoryBoundTest {
    @Test
    fun compactHistoryKeepsPackedBoundsAndRejectsOverflowWidthsOrChangedPrefixes() {
        withActivationEvidence(testActivation = true) { f ->
            val input = capturedAfterColdSnapshotRefusal(f)
            val jdbc = ownedCutField(f.pools.catalogCoordinator.testRunActivation, "jdbc") as JdbcTemplate
            assertEquals(128, jdbc.fetchSize, "Actual named template configuration, not evidence of driver cursor buffering.")
            assertEquals(65_536, input.maximumGenerations)
            val predecessors = input.maximumGenerations - 1

            val initialRows = CompactFingerprintRows(predecessors, input)
            val initial = initialRows.read(retainRaw = true)
            initial.requireExpected(input.generation, prepared = false)
            assertEquals(predecessors + 1, initialRows.nextCalls, "Consume the final false next, not a silently truncated list.")
            assertEquals(setOf("valid", "successor_generation", "row_digest", "prepared_matches", "raw_binding", "retain_until_wire"), initialRows.columns)
            val bindings = ownedCutField(initial, "rawBindings") as ByteArray
            val retention = ownedCutField(initial, "retainedUntilMicros") as LongArray
            assertEquals(predecessors * 32, bindings.size)
            assertEquals(predecessors, retention.size)
            assertEquals(40L * predecessors, bindings.size + 8L * retention.size)
            assertEquals(32, (ownedCutField(initial, "digest") as ByteArray).size)
            assertEquals(32, (ownedCutField(initial, "predecessorDigest") as ByteArray).size)
            // The cursor reused these same three tiny buffers and zeroed them at close; retained data must be detached.
            assertEquals(1L, ByteBuffer.wrap(bindings).getLong(0))
            assertEquals(predecessors.toLong(), ByteBuffer.wrap(bindings).getLong((predecessors - 1) * 32))
            assertEquals(100L, retention.first())
            assertEquals(100L * predecessors, retention.last())

            val summary = CompactFingerprintRows(predecessors, input).read(retainRaw = false)
            summary.requireSame(initial)
            summary.requireUnchangedPrefix(initial, appended = false)
            assertNull(ownedCutField(summary, "rawBindings"))
            assertNull(ownedCutField(summary, "retainedUntilMicros"))
            val appended = CompactFingerprintRows(input.maximumGenerations, input).read(retainRaw = false)
            appended.requireExpected(input.generation, prepared = true)
            appended.requireUnchangedPrefix(initial, appended = true)
            assertThrows<IllegalStateException> { appended.requireSame(initial) }
            val changed = CompactFingerprintRows(input.maximumGenerations, input) { row, column, value ->
                if (row == 1 && column == "row_digest") (value as ByteArray).copyOf().also { it[31] = 1 } else value
            }.read(retainRaw = false)
            assertThrows<IllegalStateException> { changed.requireUnchangedPrefix(initial, appended = true) }

            val overflow = CompactFingerprintRows(input.maximumGenerations + 1, input)
            assertThrows<IllegalStateException> { overflow.read(retainRaw = false) }
            assertEquals(65_537, overflow.nextCalls)
            assertEquals(65_536, overflow.lastFieldRow, "Reject the actual overflow sentinel before materializing any of its fields.")
            assertThrows<IllegalStateException> { CompactFingerprintRows(predecessors - 1, input).read(retainRaw = true) }
            for ((column, width) in mapOf("row_digest" to 32, "raw_binding" to 32, "retain_until_wire" to 8)) {
                for (badWidth in listOf(null, width - 1, width + 1)) {
                    val rows = CompactFingerprintRows(predecessors, input) { row, name, value ->
                        if (row == 1 && name == column) badWidth?.let { ByteArray(it) } else value
                    }
                    assertThrows<IllegalStateException>("$column width $badWidth") { rows.read(retainRaw = true) }
                    assertEquals(1, rows.nextCalls)
                }
            }
            for ((column, replacement) in listOf("valid" to null, "prepared_matches" to true, "successor_generation" to 2L)) {
                val rows = CompactFingerprintRows(predecessors, input) { row, name, value ->
                    if (row == 1 && name == column) replacement else value
                }
                assertThrows<IllegalStateException>(column) { rows.read(retainRaw = true) }
                assertEquals(1, rows.nextCalls)
            }
            requireConnectionFree()
        }
    }

    private fun capturedAfterColdSnapshotRefusal(f: CatalogTestRunActivationEvidenceFixture): CatalogTestRunActivationFrozenV1 {
        // Valid unsigned intent is not a supplied signed 65,535-predecessor chain. The actual cold owner must still refuse SQL.
        val record = f.manifest.activationRecord.copy(generation = 65_536)
        val intent = f.manifest.copy(generation = record.generation, activationRecord = record, history = CatalogTestRunActivationEvidenceFixture.history(record))
        var httpFactories = 0
        val original = CatalogTestRunActivationV1.withHttpFixture(
            f.process, CatalogTestRunActivationEvidenceFixture.INSTALLATION_LIMIT,
            { httpFactories++; error("A cold snapshot refusal must not construct HTTP.") },
            Clock.fixed(Instant.ofEpochSecond(CatalogReadbackFixture.EVALUATED_AT), ZoneOffset.UTC),
        )
        try {
            assertThrows<CatalogTestRunActivationExceptionV1> {
                original.prepare(CatalogTestRunActivationEvidenceFixture.manifestBytes(intent), S3CatalogReadbackFixture.credentials, S3CatalogReadbackFixture.credentials)
            }
            original.requireActualCleanup()
            assertEquals(0, httpFactories)
            assertNull(ownedCutField(original, "snapshotOperation"))
            assertEquals(false, ownedCutField(original, "allowedResult"))
            assertTrue(listOf(f.pools.ordinary, f.pools.deletion, f.pools.catalogCoordinator.dataSource).none { actualPool(it).isRunning })
            assertEquals(0, f.pools.catalogCoordinator.activeSnapshotOwners())
            requireConnectionFree()
            // Read-only observation of the genuinely captured object; no owner state or private constructor is bypassed.
            return (ownedCutField(original, "frozen") as CatalogTestRunActivationFrozenV1).also { assertEquals(65_536L, it.generation) }
        } finally {
            original.close()
        }
    }
}

/** O(1) supplied row generator for the data materializer only; never installed into production JDBC or an owner. */
private class CompactFingerprintRows(
    private val count: Int,
    private val input: CatalogTestRunActivationFrozenV1,
    private val alter: (Int, String, Any?) -> Any? = { _, _, value -> value },
) {
    var nextCalls = 0
        private set
    var lastFieldRow = 0
        private set
    val columns = mutableSetOf<String>()
    private var row = 0
    private var wasNull = false
    private var closed = false
    private val hash = ByteArray(32)
    private val raw = ByteArray(32)
    private val time = ByteArray(8)
    private val hashBuffer = ByteBuffer.wrap(hash)
    private val rawBuffer = ByteBuffer.wrap(raw)
    private val timeBuffer = ByteBuffer.wrap(time)
    private val rows = Proxy.newProxyInstance(ResultSet::class.java.classLoader, arrayOf(ResultSet::class.java)) { _, method, args ->
        check(!closed)
        when (method.name) {
            "next" -> {
                check(row <= count)
                nextCalls++
                row++
                hashBuffer.putLong(0, row.toLong())
                rawBuffer.putLong(0, row.toLong())
                timeBuffer.putLong(0, row * 100L)
                row <= count
            }
            "wasNull" -> wasNull
            "getBoolean", "getLong", "getBytes" -> {
                check(row in 1..count)
                val column = args!!.single() as String
                columns.add(column)
                lastFieldRow = row
                val value: Any = when ("${method.name}:$column") {
                    "getBoolean:valid" -> true
                    "getBoolean:prepared_matches" -> row.toLong() == input.generation
                    "getLong:successor_generation" -> row.toLong()
                    "getBytes:row_digest" -> hash
                    "getBytes:raw_binding" -> raw
                    "getBytes:retain_until_wire" -> time
                    else -> error("Unexpected compact history field: $column")
                }
                val supplied = alter(row, column, value)
                wasNull = supplied == null
                supplied ?: when (method.name) { "getBoolean" -> false; "getLong" -> 0L; else -> null }
            }
            "close" -> {
                closed = true
                hash.fill(0)
                raw.fill(0)
                time.fill(0)
                null
            }
            else -> error("Unexpected compact history cursor method: ${method.name}")
        }
    } as ResultSet

    fun read(retainRaw: Boolean): CatalogTestRunActivationHistoryV1 {
        assertFalse(closed)
        try {
            return rows.use { CatalogTestRunActivationHistoryV1.read(it, input, retainRaw) }
        } finally {
            assertTrue(closed)
        }
    }
}
