package me.manga.kira.backend.complaint.infrastructure

import me.manga.kira.backend.complaint.domain.ComplaintAdminStats
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintStatus
import me.manga.kira.backend.complaint.domain.ComplaintType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.sql.ResultSet
import java.util.UUID

/** Synthetic mapper rows only, never installed into an adapter, JDBC owner or authentication path. */
class ComplaintAdminStatsRowMapperTest {
    private val scope = ComplaintDataScope.of(UUID.randomUUID())

    @Test
    fun emptyAndLongMaxTaggedRowsFillFixedZerosWithoutRoundingOrFabricatedNoticeTypes() {
        val empty = map(listOf(Row("TOTAL", null, 0), Row("OTHER", null, 0)))
        assertTrue(empty.contractValid)
        val emptyStats = checkNotNull(empty.stats)
        assertEquals(0L, emptyStats.total)
        assertEquals(7, emptyStats.byStatus.size)
        assertTrue(emptyStats.byStatus.all { it.count == 0L })
        assertTrue(emptyStats.appVersions.buckets.isEmpty())
        val result = map(ordinaryRows(Long.MAX_VALUE))
        assertTrue(result.contractValid)
        assertEquals(ComplaintAdminReadVerdict.ALLOWED, result.verdict)
        assertTrue(result.items.isEmpty())
        val stats = checkNotNull(result.stats)
        assertEquals(Long.MAX_VALUE, stats.total)
        assertEquals(Long.MAX_VALUE, stats.byType.first().count)
        assertNull(stats.byType.last().type)
        assertEquals(0L, stats.byType.last().count)
        assertEquals(Long.MAX_VALUE, stats.appVersions.buckets.single().count)
    }

    @Test
    fun malformedCountsCategoriesOrderAndDeniedDataCannotBecomeZeroOrPartialSuccess() {
        val valid = ordinaryRows(1)
        val malformed = listOf(
            emptyList(), valid.drop(1), valid + valid.first(),
            valid.map { if (it.family == "TOTAL") it.copy(count = -1) else it },
            valid.map { if (it.family == "TOTAL") it.copy(count = null) else it },
            valid.map { if (it.family == "STATUS") it.copy(key = "UNKNOWN") else it },
            valid + valid.first { it.family == "TYPE" },
            valid.map { if (it.family == "APP_VERSION") it.copy(key = " padded") else it },
            valid.map { it.copy(bounded = false) }, valid.map { it.copy(bounded = null) },
            valid.map { if (it.family == "TYPE") it.copy(key = null) else it },
            listOf(Row(null, null, null, "ALLOWED", null)),
            listOf(Row("TOTAL", null, 0, "NOT_FOUND", null)),
        )
        for (rows in malformed) {
            val result = map(rows)
            assertFalse(result.contractValid)
            assertNull(result.stats)
            assertTrue(result.items.isEmpty())
        }
        for (verdict in listOf("UNAUTHORIZED", "FORBIDDEN", "NOT_FOUND")) {
            val denied = Row(null, null, null, verdict, null)
            val result = map(listOf(denied))
            assertTrue(result.contractValid)
            assertEquals(verdict, result.verdict.name)
            assertNull(result.stats)
            assertFalse(map(listOf(denied, denied)).contractValid)
        }
        val badRank = listOf(
            Row("TOTAL", null, 2), Row("OTHER", null, 0), Row("STATUS", "OPEN", 2),
            Row("TYPE", "TECHNICAL", 2), Row("OWNERSHIP", "INSTALLATION", 2), Row("APP_VERSION", "z", 1), Row("APP_VERSION", "a", 1),
        )
        assertFalse(map(badRank).contractValid)
    }

    @Test
    fun exactSixtyEightRowCeilingStopsBeforeReadingTheSixtyNinthProjection() {
        val maximum = buildList {
            add(Row("TOTAL", null, 50))
            add(Row("OTHER", null, 0))
            ComplaintAdminStats.STATUS_ORDER.forEach { add(Row("STATUS", it.name, if (it == ComplaintStatus.OPEN) 44 else 1)) }
            ComplaintAdminStats.TYPE_ORDER.forEach { add(Row("TYPE", it?.name, if (it == ComplaintType.TECHNICAL) 44 else 1)) }
            add(Row("OWNERSHIP", "INSTALLATION", 49))
            add(Row("OWNERSHIP", "SYSTEM", 1))
            add(Row("APP_VERSION", null, 1))
            repeat(49) { add(Row("APP_VERSION", "v${it.toString().padStart(2, '0')}", 1)) }
        }
        assertEquals(68, maximum.size)
        assertTrue(map(maximum).contractValid)
        val extra = SyntheticRows(maximum + Row("APP_VERSION", "never project the extra row", 1))
        extra.rows.use { assertFalse(ComplaintAdminStatsRowMapper.read(it, scope).contractValid) }
        assertEquals(69, extra.nextCalls)
        assertEquals(68, extra.lastColumnRow)
    }

    private fun ordinaryRows(count: Long): List<Row> = listOf(
        Row("TOTAL", null, count), Row("OTHER", null, 0), Row("STATUS", "OPEN", count),
        Row("TYPE", "TECHNICAL", count), Row("OWNERSHIP", "INSTALLATION", count), Row("APP_VERSION", null, count),
    )

    private fun map(values: List<Row>): ComplaintAdminReadRows = SyntheticRows(values).rows.use { ComplaintAdminStatsRowMapper.read(it, scope) }

    private data class Row(val family: String?, val key: String?, val count: Long?, val verdict: String = "ALLOWED", val bounded: Boolean? = true)

    private class SyntheticRows(private val values: List<Row>) {
        var nextCalls = 0
            private set
        var lastColumnRow = 0
            private set
        private var position = -1
        private var wasNull = false
        val rows = Proxy.newProxyInstance(ResultSet::class.java.classLoader, arrayOf(ResultSet::class.java)) { _, method, args ->
            when (method.name) {
                "next" -> {
                    nextCalls++
                    ++position < values.size
                }
                "wasNull" -> wasNull
                "close" -> null
                "getString", "getBoolean", "getLong", "getObject" -> {
                    val row = values[position]
                    lastColumnRow = position + 1
                    val value: Any? = when (args!!.single() as String) {
                        "verdict" -> row.verdict
                        "contract_valid" -> row.bounded
                        "family" -> row.family
                        "bucket_key" -> row.key
                        "bucket_count" -> row.count
                        else -> error("Unexpected synthetic stats column.")
                    }
                    wasNull = value == null
                    when (method.name) {
                        "getBoolean" -> value ?: false
                        "getLong" -> value ?: 0L
                        else -> value
                    }
                }
                else -> error("Unexpected synthetic stats method.")
            }
        } as ResultSet
    }
}
