package me.manga.kira.backend.sourceconfig.domain

/**
 * The server's five source-lifecycle states (PLAN §9). Distinct from the app's 3-value vocabulary
 * (`active`/`disabled`/`removed`): `draft` and `retired` are server-only statuses, mapped to the app
 * vocabulary at document-assembly time (Phase 6). Server lifecycle lives ONLY here (in
 * `source_configs.status`) — never in stored revision content, which is lifecycle-neutral (PLAN §9).
 *
 * [wire] is the lowercase DB representation stored in `source_configs.status`
 * (`chk_source_configs_status`); the JPA converter maps to/from it. Pure Kotlin — no framework types.
 */
enum class SourceLifecycleStatus(val wire: String) {
    /** Authored, never published. Not in the served document. */
    DRAFT("draft"),

    /** Normal operation. Served with `lifecycle:"active"` (rendered as an absent key by kcj-1). */
    ACTIVE("active"),

    /** Force-disabled but kept on disk. Served with `lifecycle:"disabled"`. */
    DISABLED("disabled"),

    /** App deletes the row; stanza stays in the document as `lifecycle:"removed"` for a grace window. */
    RETIRED("retired"),

    /** Terminal. Stanza dropped from the document entirely; `removed -> *` is always refused. */
    REMOVED("removed"),
    ;

    companion object {
        /** Parse a stored wire value, or fail loudly on an unknown one (a schema/data defect). */
        fun fromWire(wire: String): SourceLifecycleStatus = entries.firstOrNull { it.wire == wire }
            ?: error("Unknown source lifecycle status wire value: '$wire'")
    }
}
