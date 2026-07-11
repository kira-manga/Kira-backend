package me.manga.kira.backend.sourceconfig.validation

/**
 * A manually-versioned copy of the key set in the app's `SourceIconRegistry` (PLAN §8 rule 33).
 * Used ONLY for the **advisory** `UNKNOWN_ICON_KEY` warning: an `icon.resourceKey` not in this
 * catalog AND with no `remoteUrl` fallback surfaces a [ValidationWarning] — **never** an error,
 * because the app degrades gracefully (a missing/unknown key resolves to `null`; the UI falls
 * through to the remote URL, then a deterministic initials avatar — "never a crash"). A hard reject
 * would let a stale catalog block otherwise-valid publishes.
 *
 * FRAMEWORK-FREE (PLAN §3): pure data. The backend never depends on the mobile Gradle project.
 *
 * **Update procedure (PLAN §8 rule 33):** when the app adds a packaged icon, add the same key here
 * in the same release train.
 *
 * **Provenance / maintenance note (this backend is read-only w.r.t. the mobile repo):** the seed
 * below is the exact set of `icon.resourceKey`s referenced by the real production
 * `CONFIG_BACKED_SOURCES_JSON` (the committed `bundled-full.json` fixture, 12 distinct keys) — so no
 * real bundled stanza ever produces a spurious warning. The app's full `SourceIconRegistry` ships
 * **44+ keys**; the remaining packaged-only keys (not referenced by any current stanza) should be
 * reconciled into [KEYS] in a maintenance pass with mobile-repo access. Because the rule is
 * warning-only, an under-populated catalog is safe (it can only over-warn, never over-block).
 */
class PackagedIconCatalog(
    private val keys: Set<String> = KEYS,
) {
    fun hasKey(resourceKey: String): Boolean = resourceKey in keys

    val size: Int get() = keys.size

    companion object {
        /** Verbatim from the real bundled document's referenced `icon.resourceKey` values. */
        val KEYS: Set<String> =
            linkedSetOf(
                "3asq",
                "azora",
                "demonicscans",
                "dilar",
                "lekmanga",
                "mangabuddy",
                "mangamello",
                "mangamello_plus",
                "swatmanga",
                "tapas",
                "team_x",
                "zazamanga",
            )
    }
}
