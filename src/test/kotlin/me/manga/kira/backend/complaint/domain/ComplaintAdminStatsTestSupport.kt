package me.manga.kira.backend.complaint.domain

/** Detached synthetic counts only; no signer, ADMIN observation, ingress handoff or persistence authority. */
internal fun adminStatsTestValue(
    scope: ComplaintDataScope,
    total: Long = 0,
    notices: Long = 0,
    versions: List<ComplaintAdminStats.VersionBucket> = if (total == 0L) emptyList() else listOf(ComplaintAdminStats.VersionBucket(null, total)),
    otherCount: Long = 0,
): ComplaintAdminStats = ComplaintAdminStats(
    scope, total,
    ComplaintAdminStats.STATUS_ORDER.map { status ->
        val count = when (status) {
            ComplaintStatus.OPEN -> total - notices
            ComplaintStatus.PINNED -> notices
            else -> 0L
        }
        ComplaintAdminStats.StatusBucket(status, count)
    },
    ComplaintAdminStats.TYPE_ORDER.map { type ->
        val count = when (type) {
            null -> notices
            ComplaintType.TECHNICAL -> total - notices
            else -> 0L
        }
        ComplaintAdminStats.TypeBucket(type, count)
    },
    listOf(
        ComplaintAdminStats.OwnershipBucket(ComplaintOwnership.INSTALLATION, total - notices),
        ComplaintAdminStats.OwnershipBucket(ComplaintOwnership.SYSTEM, notices),
    ),
    ComplaintAdminStats.AppVersions(versions, otherCount),
)
