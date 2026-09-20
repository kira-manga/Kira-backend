package me.manga.kira.backend.complaint.domain

import java.util.Arrays
import java.util.Collections

/** An unfiltered TEST scope request, not a search total, activation switch or current-ADMIN grant. */
internal class ComplaintAdminStatsQuery(override val scope: ComplaintDataScope) : ComplaintAdminReadQuery {
    init {
        if (!scope.testOnly) rejectAdminRead(ComplaintAdminReadFailure.INVALID_REQUEST)
    }

    override fun toString(): String = "ComplaintAdminStatsQuery(redacted)"
}

/** Closed detached counts only. All arithmetic is checked by subtraction, never an overflowing sum. */
internal class ComplaintAdminStats(
    val scope: ComplaintDataScope,
    val total: Long,
    byStatus: List<StatusBucket>,
    byType: List<TypeBucket>,
    byOwnership: List<OwnershipBucket>,
    val appVersions: AppVersions,
) : ComplaintAdminReadResult {
    val byStatus: List<StatusBucket> = Collections.unmodifiableList(byStatus.toList())
    val byType: List<TypeBucket> = Collections.unmodifiableList(byType.toList())
    val byOwnership: List<OwnershipBucket> = Collections.unmodifiableList(byOwnership.toList())

    init {
        require(scope.testOnly && total >= 0) { INVALID_STATS }
        require(this.byStatus.map { it.status } == STATUS_ORDER) { INVALID_STATS }
        require(this.byType.map { it.type } == TYPE_ORDER) { INVALID_STATS }
        require(this.byOwnership.map { it.ownership } == OWNERSHIP_ORDER) { INVALID_STATS }
        require(remainder(total, this.byStatus.map { it.count }) == 0L) { INVALID_STATS }
        require(remainder(total, this.byType.map { it.count }) == 0L) { INVALID_STATS }
        require(remainder(total, this.byOwnership.map { it.count }) == 0L) { INVALID_STATS }
        require(this.byType.last().count == this.byOwnership.last().count) { INVALID_STATS }
        require(remainder(total, appVersions.buckets.map { it.count }) == appVersions.otherCount) { INVALID_STATS }
    }

    class StatusBucket(val status: ComplaintStatus, val count: Long) {
        init {
            require(status in STATUS_ORDER && count >= 0) { INVALID_STATS }
        }

        override fun toString(): String = "ComplaintAdminStats.StatusBucket(redacted)"
    }

    class TypeBucket(val type: ComplaintType?, val count: Long) {
        init {
            require(type in TYPE_ORDER && count >= 0) { INVALID_STATS }
        }

        override fun toString(): String = "ComplaintAdminStats.TypeBucket(redacted)"
    }

    class OwnershipBucket(val ownership: ComplaintOwnership, val count: Long) {
        init {
            require(ownership in OWNERSHIP_ORDER && count >= 0) { INVALID_STATS }
        }

        override fun toString(): String = "ComplaintAdminStats.OwnershipBucket(redacted)"
    }

    class VersionBucket(val appVersion: String?, val count: Long) {
        init {
            require(count > 0 && ComplaintTextRules.appVersion(appVersion) == appVersion) { INVALID_STATS }
        }

        override fun toString(): String = "ComplaintAdminStats.VersionBucket(redacted)"
    }

    class AppVersions(buckets: List<VersionBucket>, val otherCount: Long) {
        val buckets: List<VersionBucket> = Collections.unmodifiableList(buckets.toList())

        init {
            require(this.buckets.size <= MAX_VERSION_BUCKETS && otherCount >= 0) { INVALID_STATS }
            require(this.buckets.size == MAX_VERSION_BUCKETS || otherCount == 0L) { INVALID_STATS }
            require(this.buckets.map { it.appVersion }.toSet().size == this.buckets.size) { INVALID_STATS }
            require(
                this.buckets.zipWithNext().all { (a, b) ->
                    a.count > b.count || (a.count == b.count && compareVersionKeys(a.appVersion, b.appVersion) < 0)
                },
            ) { INVALID_STATS }
        }

        override fun toString(): String = "ComplaintAdminStats.AppVersions(redacted)"
    }

    override fun toString(): String = "ComplaintAdminStats(redacted)"

    companion object {
        const val MAX_VERSION_BUCKETS = 50
        private const val INVALID_STATS = "Invalid Admin stats."

        // Explicit contract order: a future legacy/category enum addition must not silently widen this DTO.
        val STATUS_ORDER: List<ComplaintStatus> = Collections.unmodifiableList(
            listOf(
                ComplaintStatus.OPEN, ComplaintStatus.IN_PROGRESS, ComplaintStatus.RESOLVED, ComplaintStatus.CLOSED,
                ComplaintStatus.PLANNED, ComplaintStatus.PINNED, ComplaintStatus.NOT_PLANNED,
            ),
        )
        val TYPE_ORDER: List<ComplaintType?> = Collections.unmodifiableList(
            listOf(
                ComplaintType.TECHNICAL, ComplaintType.LANGUAGES, ComplaintType.SITES_ADD, ComplaintType.SITE_ERROR,
                ComplaintType.FEATURES, ComplaintType.CUSTOM, null,
            ),
        )
        val OWNERSHIP_ORDER: List<ComplaintOwnership> = Collections.unmodifiableList(listOf(ComplaintOwnership.INSTALLATION, ComplaintOwnership.SYSTEM))

        /** PostgreSQL COLLATE "C" on a UTF-8 database, not Java/Kotlin UTF-16 or a locale collation. */
        private fun compareVersionKeys(left: String?, right: String?): Int = when {
            left == null -> if (right == null) 0 else -1
            right == null -> 1
            else -> Arrays.compareUnsigned(left.toByteArray(Charsets.UTF_8), right.toByteArray(Charsets.UTF_8))
        }

        private fun remainder(total: Long, counts: List<Long>): Long {
            var remaining = total
            for (count in counts) {
                require(count >= 0 && count <= remaining) { INVALID_STATS }
                remaining -= count
            }
            return remaining
        }
    }
}
