package me.manga.kira.backend.complaint.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class ComplaintAdminStatsTest {
    private val scope = ComplaintDataScope.of(UUID.randomUUID())

    @Test
    fun emptyAndLongMaxCountsHaveTheExactClosedFiniteBucketsAndDetachedLists() {
        val empty = adminStatsTestValue(scope)
        assertEquals(listOf("OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED", "PLANNED", "PINNED", "NOT_PLANNED"), empty.byStatus.map { it.status.name })
        assertEquals(listOf("TECHNICAL", "LANGUAGES", "SITES_ADD", "SITE_ERROR", "FEATURES", "CUSTOM", null), empty.byType.map { it.type?.name })
        assertEquals(listOf("INSTALLATION", "SYSTEM"), empty.byOwnership.map { it.ownership.name })
        assertTrue(empty.byStatus.all { it.count == 0L } && empty.byType.all { it.count == 0L } && empty.byOwnership.all { it.count == 0L })
        assertEquals(0L, empty.total)
        assertTrue(empty.appVersions.buckets.isEmpty())
        assertEquals(0L, empty.appVersions.otherCount)
        assertEquals(scope, ComplaintAdminStatsQuery(scope).scope)
        val live = assertThrows<ComplaintAdminReadRejected> { ComplaintAdminStatsQuery(ComplaintDataScope.LIVE) }
        assertEquals(ComplaintAdminReadFailure.INVALID_REQUEST, live.failure)
        for (total in listOf(9_007_199_254_740_993L, Long.MAX_VALUE)) {
            val stats = adminStatsTestValue(scope, total)
            assertEquals(total, stats.total)
            assertEquals(total, stats.byStatus.first().count)
            assertEquals(total, stats.byType.first().count)
            assertEquals(total, stats.byOwnership.first().count)
            assertEquals(total, stats.appVersions.buckets.single().count)
        }
        val statuses = empty.byStatus.toMutableList()
        val versions = mutableListOf(ComplaintAdminStats.VersionBucket(null, 1))
        val copied = ComplaintAdminStats(scope, 0, statuses, empty.byType, empty.byOwnership, empty.appVersions)
        val copiedVersions = ComplaintAdminStats.AppVersions(versions, 0)
        statuses.clear()
        versions.clear()
        assertEquals(7, copied.byStatus.size)
        assertEquals(1, copiedVersions.buckets.size)
        assertThrows<UnsupportedOperationException> { (copied.byStatus as MutableList<*>).clear() }
        assertThrows<UnsupportedOperationException> { (copiedVersions.buckets as MutableList<*>).clear() }
        assertThrows<IllegalArgumentException> { rebuild(empty, selectedScope = ComplaintDataScope.LIVE) }
    }

    @Test
    fun nullableLiteralAndUnicodeVersionKeysStayDistinctInCountThenUtf8Order() {
        val keys = listOf(null, "", "null", "unreported", "\uE000", "\uD800\uDC00")
        val stats = adminStatsTestValue(scope, 42, notices = 1, versions = keys.map { ComplaintAdminStats.VersionBucket(it, 7) })
        assertEquals(keys, stats.appVersions.buckets.map { it.appVersion })
        assertNull(stats.byType.last().type)
        assertEquals(1L, stats.byType.last().count)
        assertEquals(1L, stats.byOwnership.last().count)
        assertTrue("\uD800\uDC00" < "\uE000", "The fixture must differ under UTF-16 ordering.")
        assertThrows<IllegalArgumentException> {
            ComplaintAdminStats.AppVersions(listOf(ComplaintAdminStats.VersionBucket("\uD800\uDC00", 1), ComplaintAdminStats.VersionBucket("\uE000", 1)), 0)
        }
        assertThrows<IllegalArgumentException> {
            ComplaintAdminStats.AppVersions(listOf(ComplaintAdminStats.VersionBucket("", 1), ComplaintAdminStats.VersionBucket(null, 1)), 0)
        }
        // Null is not forcibly returned: a single unreported NOTICE can rank below fifty observed strings.
        val top = List(50) { ComplaintAdminStats.VersionBucket("v${it.toString().padStart(2, '0')}", 2) }
        val omittedNull = adminStatsTestValue(scope, 101, notices = 1, versions = top, otherCount = 1)
        assertEquals(50, omittedNull.appVersions.buckets.size)
        assertTrue(omittedNull.appVersions.buckets.none { it.appVersion == null })
        assertEquals(1L, omittedNull.appVersions.otherCount)
    }

    @Test
    fun finiteAndVersionSumsRejectOverflowMissingDuplicateAndInconsistentBuckets() {
        val one = adminStatsTestValue(scope, 1)
        assertThrows<IllegalArgumentException> { rebuild(one, total = -1) }
        assertThrows<IllegalArgumentException> { rebuild(one, byStatus = one.byStatus.dropLast(1)) }
        assertThrows<IllegalArgumentException> { rebuild(one, byStatus = one.byStatus.reversed()) }
        assertThrows<IllegalArgumentException> { rebuild(one, byType = one.byType.dropLast(1) + one.byType.first()) }
        assertThrows<IllegalArgumentException> { rebuild(one, byOwnership = one.byOwnership.reversed()) }
        assertThrows<IllegalArgumentException> { rebuild(one, total = 2) }
        assertThrows<IllegalArgumentException> {
            rebuild(one, byType = one.byType.map { ComplaintAdminStats.TypeBucket(it.type, if (it.type == null) 1 else 0) })
        }
        // A naive Long sum wraps 2*MAX+3 to1. Every individual count is legal; the aggregate must still reject it.
        assertThrows<IllegalArgumentException> {
            val counts = one.byStatus.mapIndexed { index, bucket ->
                val count = when (index) {
                    0, 1 -> Long.MAX_VALUE
                    2 -> 3L
                    else -> 0L
                }
                ComplaintAdminStats.StatusBucket(bucket.status, count)
            }
            rebuild(one, byStatus = counts)
        }
        assertThrows<IllegalArgumentException> {
            val buckets = listOf(
                ComplaintAdminStats.VersionBucket("a", Long.MAX_VALUE), ComplaintAdminStats.VersionBucket("b", Long.MAX_VALUE),
                ComplaintAdminStats.VersionBucket("c", 3),
            )
            rebuild(one, appVersions = ComplaintAdminStats.AppVersions(buckets, 0))
        }
        assertThrows<IllegalArgumentException> { ComplaintAdminStats.StatusBucket(ComplaintStatus.UNKNOWN, 0) }
        assertThrows<IllegalArgumentException> { ComplaintAdminStats.OwnershipBucket(ComplaintOwnership.LEGACY_UNCLAIMED, 0) }
        assertThrows<IllegalArgumentException> { ComplaintAdminStats.TypeBucket(null, -1) }
        assertThrows<IllegalArgumentException> { ComplaintAdminStats.AppVersions(emptyList(), 1) }
        assertThrows<IllegalArgumentException> { ComplaintAdminStats.AppVersions(emptyList(), -1) }
        assertThrows<IllegalArgumentException> { ComplaintAdminStats.AppVersions(List(51) { ComplaintAdminStats.VersionBucket("$it", 1) }, 0) }
        assertThrows<IllegalArgumentException> { ComplaintAdminStats.AppVersions(List(2) { ComplaintAdminStats.VersionBucket(null, 1) }, 0) }
        assertThrows<IllegalArgumentException> { ComplaintAdminStats.AppVersions(List(2) { ComplaintAdminStats.VersionBucket("null", 1) }, 0) }
        assertThrows<IllegalArgumentException> {
            ComplaintAdminStats.AppVersions(listOf(ComplaintAdminStats.VersionBucket("a", 1), ComplaintAdminStats.VersionBucket("b", 2)), 0)
        }
    }

    @Test
    fun versionTextValidationNeverNormalizesKeysAndPreservesExistingScalarBounds() {
        assertEquals("\uD800\uDC00".repeat(64), ComplaintAdminStats.VersionBucket("\uD800\uDC00".repeat(64), 1).appVersion)
        assertEquals("x\t\nx", ComplaintAdminStats.VersionBucket("x\t\nx", 1).appVersion)
        for (value in listOf(" padded", "padded ", "x\r\nx")) {
            assertThrows<IllegalArgumentException> { ComplaintAdminStats.VersionBucket(value, 1) }
        }
        for (value in listOf("a".repeat(65), "\uD800\uDC00".repeat(65), "\uD800", "\uDC00", "x\u0000x")) {
            assertThrows<ComplaintValidationException> { ComplaintAdminStats.VersionBucket(value, 1) }
        }
        assertThrows<IllegalArgumentException> { ComplaintAdminStats.VersionBucket(null, 0) }
        assertThrows<IllegalArgumentException> { ComplaintAdminStats.VersionBucket("", -1) }
    }

    private fun rebuild(
        source: ComplaintAdminStats,
        selectedScope: ComplaintDataScope = source.scope,
        total: Long = source.total,
        byStatus: List<ComplaintAdminStats.StatusBucket> = source.byStatus,
        byType: List<ComplaintAdminStats.TypeBucket> = source.byType,
        byOwnership: List<ComplaintAdminStats.OwnershipBucket> = source.byOwnership,
        appVersions: ComplaintAdminStats.AppVersions = source.appVersions,
    ): ComplaintAdminStats = ComplaintAdminStats(selectedScope, total, byStatus, byType, byOwnership, appVersions)
}
