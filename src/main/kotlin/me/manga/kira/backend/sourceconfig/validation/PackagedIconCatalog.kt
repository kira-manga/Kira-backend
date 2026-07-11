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
 * **Provenance (this backend is read-only w.r.t. the mobile repo):** [KEYS] is the exact packaged-icon
 * key set of the app's `SourceIconRegistry`, extracted by read-only inspection of the app repo
 * (40 keys as of 2026-07-11). This is a superset of the 12 keys referenced by the current
 * `CONFIG_BACKED_SOURCES_JSON` (the committed `bundled-full.json` fixture), so no real bundled stanza
 * ever produces a spurious warning. Because the rule is warning-only, even a stale catalog is safe (it
 * can only over-warn, never over-block a valid publish).
 */
class PackagedIconCatalog(
    private val keys: Set<String> = KEYS,
) {
    fun hasKey(resourceKey: String): Boolean = resourceKey in keys

    val size: Int get() = keys.size

    companion object {
        /** The app `SourceIconRegistry` packaged-icon key set, verbatim (read-only inspection, 2026-07-11). */
        val KEYS: Set<String> =
            linkedSetOf(
                "3asq",
                "azora",
                "batcave",
                "batoto",
                "comick",
                "demonicscans",
                "desu",
                "dilar",
                "flowermanga",
                "inmanga",
                "komikcast",
                "komiku",
                "lavatoons",
                "lekmanga",
                "manga_origines",
                "mangabuddy",
                "mangahub",
                "mangamello",
                "mangamello_plus",
                "mangapark",
                "mangatuk",
                "mangaworld",
                "manhastro",
                "manhwatop",
                "manhwaweb",
                "mediocretoons",
                "olympus",
                "prochan",
                "promanga",
                "raijinscan",
                "senkuro",
                "sussytoons",
                "swatmanga",
                "tapas",
                "taurusfansub",
                "team_x",
                "timenaight",
                "webtoon_tr",
                "webtoonhatti",
                "zazamanga",
            )
    }
}
