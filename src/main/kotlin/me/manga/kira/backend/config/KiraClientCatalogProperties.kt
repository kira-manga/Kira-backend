package me.manga.kira.backend.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** Explicit signed-client routing only. Disabled defaults provide NO compatibility rollout protection. */
@ConfigurationProperties(prefix = "kira.client-catalog", ignoreUnknownFields = false)
data class KiraClientCatalogProperties(
    val enabled: Boolean = false,
    val baseline: SnapshotPin? = null,
    val currentVersions: List<String> = emptyList(),
    val pinnedVersions: List<VersionPin> = emptyList(),
) {
    /** Independently admitted hashes of two different original stored artifacts, not values discovered from history. */
    data class SnapshotPin(val revision: Long = 0, val documentChecksum: String = "", val manifestChecksum: String = "")

    /** A formerly current client may retain its last compatible pair when a newer release becomes current. */
    data class VersionPin(val appVersion: String = "", val snapshot: SnapshotPin = SnapshotPin())
}
