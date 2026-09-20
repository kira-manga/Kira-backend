package me.manga.kira.backend.complaint.infrastructure

import me.manga.kira.backend.complaint.domain.ComplaintAdminStats
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintOwnership
import me.manga.kira.backend.complaint.domain.ComplaintStatus
import me.manga.kira.backend.complaint.domain.ComplaintType
import me.manga.kira.backend.complaint.domain.ComplaintValidationException
import java.sql.ResultSet

/** Comparison-only mapping, never a released result. The original retained operation still owns commit and cleanup. */
internal object ComplaintAdminStatsRowMapper {
    // total1 + statuses7 + types7 + ownerships2 + version keys50 + remainder1, including corrupt-category guards in SQL.
    const val MAX_ROWS = 68

    @Suppress("SwallowedException")
    fun read(rows: ResultSet, scope: ComplaintDataScope): ComplaintAdminReadRows = try {
        readChecked(rows, scope)
    } catch (failure: ComplaintValidationException) {
        invalid()
    } catch (failure: IllegalArgumentException) {
        invalid()
    } catch (failure: IllegalStateException) {
        invalid()
    }

    private fun readChecked(rows: ResultSet, scope: ComplaintDataScope): ComplaintAdminReadRows {
        val counts = Counts()
        var verdict: ComplaintAdminReadVerdict? = null
        var rowCount = 0
        while (rows.next()) {
            check(++rowCount <= MAX_ROWS)
            val current = ComplaintAdminReadVerdict.valueOf(checkNotNull(rows.getString("verdict")))
            check(verdict == null || verdict == current)
            verdict = current
            if (current !== ComplaintAdminReadVerdict.ALLOWED) {
                check(rowCount == 1)
                check(listOf("contract_valid", "family", "bucket_key", "bucket_count").all { rows.getObject(it) == null })
                continue
            }
            check(rows.getBoolean("contract_valid") && !rows.wasNull())
            val count = rows.getLong("bucket_count")
            check(!rows.wasNull() && count >= 0)
            counts.add(checkNotNull(rows.getString("family")), rows.getString("bucket_key"), count)
        }
        val current = checkNotNull(verdict)
        return if (current === ComplaintAdminReadVerdict.ALLOWED) {
            ComplaintAdminReadRows(current, emptyList(), stats = counts.result(scope))
        } else {
            ComplaintAdminReadRows(current, emptyList())
        }
    }

    private fun invalid(): ComplaintAdminReadRows = ComplaintAdminReadRows(ComplaintAdminReadVerdict.UNAUTHORIZED, emptyList(), contractValid = false)

    private class Counts {
        private var total: Long? = null
        private var other: Long? = null
        private val statuses = HashMap<ComplaintStatus, Long>()
        private val types = HashMap<ComplaintType?, Long>()
        private val ownerships = HashMap<ComplaintOwnership, Long>()
        private val versions = ArrayList<ComplaintAdminStats.VersionBucket>(ComplaintAdminStats.MAX_VERSION_BUCKETS)

        fun add(family: String, key: String?, count: Long) {
            when (family) {
                "TOTAL" -> {
                    check(key == null && total == null)
                    total = count
                }
                "OTHER" -> {
                    check(key == null && other == null)
                    other = count
                }
                "STATUS" -> {
                    val status = ComplaintStatus.valueOf(checkNotNull(key))
                    check(count > 0 && status in ComplaintAdminStats.STATUS_ORDER && statuses.put(status, count) == null)
                }
                "TYPE" -> {
                    val type = key?.let(ComplaintType::valueOf)
                    check(count > 0 && type in ComplaintAdminStats.TYPE_ORDER && types.put(type, count) == null)
                }
                "OWNERSHIP" -> {
                    val ownership = ComplaintOwnership.valueOf(checkNotNull(key))
                    check(count > 0 && ownership in ComplaintAdminStats.OWNERSHIP_ORDER && ownerships.put(ownership, count) == null)
                }
                "APP_VERSION" -> {
                    check(versions.size < ComplaintAdminStats.MAX_VERSION_BUCKETS)
                    versions.add(ComplaintAdminStats.VersionBucket(key, count))
                }
                else -> error("Invalid Admin stats family.")
            }
        }

        fun result(scope: ComplaintDataScope): ComplaintAdminStats = ComplaintAdminStats(
            scope, checkNotNull(total),
            ComplaintAdminStats.STATUS_ORDER.map { ComplaintAdminStats.StatusBucket(it, statuses[it] ?: 0L) },
            ComplaintAdminStats.TYPE_ORDER.map { ComplaintAdminStats.TypeBucket(it, types[it] ?: 0L) },
            ComplaintAdminStats.OWNERSHIP_ORDER.map { ComplaintAdminStats.OwnershipBucket(it, ownerships[it] ?: 0L) },
            ComplaintAdminStats.AppVersions(versions, checkNotNull(other)),
        )
    }
}
