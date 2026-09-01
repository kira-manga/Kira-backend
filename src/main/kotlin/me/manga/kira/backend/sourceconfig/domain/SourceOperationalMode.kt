package me.manga.kira.backend.sourceconfig.domain

private const val SITE_STATE_WORKING = "WORKING"
private const val SITE_STATE_UNDER_MAINTENANCE = "UNDER_MAINTENANCE"

/**
 * The deliberately small operator-facing source switch. It combines the server lifecycle and the
 * app-facing `siteState` without exposing unrelated terminal/adult states through the quick control.
 */
enum class SourceOperationalMode(val wire: String, val targetStatus: SourceLifecycleStatus, val targetSiteState: String) {
    ENABLED("enabled", SourceLifecycleStatus.ACTIVE, SITE_STATE_WORKING),
    DISABLED("disabled", SourceLifecycleStatus.DISABLED, SITE_STATE_WORKING),
    UNDER_MAINTENANCE("under_maintenance", SourceLifecycleStatus.ACTIVE, SITE_STATE_UNDER_MAINTENANCE),
    ;

    companion object {
        fun fromWire(value: String): SourceOperationalMode? = entries.firstOrNull { it.wire == value }

        /** Returns null for drafts, terminal states, and site states owned by another workflow. */
        fun current(status: SourceLifecycleStatus, siteState: String): SourceOperationalMode? {
            if (siteState != SITE_STATE_WORKING && siteState != SITE_STATE_UNDER_MAINTENANCE) return null
            return when (status) {
                SourceLifecycleStatus.ACTIVE ->
                    if (siteState == SITE_STATE_UNDER_MAINTENANCE) UNDER_MAINTENANCE else ENABLED

                SourceLifecycleStatus.DISABLED -> DISABLED

                SourceLifecycleStatus.DRAFT,
                SourceLifecycleStatus.WITHHELD,
                SourceLifecycleStatus.RETIRED,
                SourceLifecycleStatus.REMOVED,
                -> null
            }
        }
    }
}
