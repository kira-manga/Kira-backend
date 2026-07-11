package me.manga.kira.backend.sourceconfig.domain

/**
 * Per-source revision lifecycle (PLAN §5/§9). Exactly one `published` revision may exist per source
 * (the `uq_one_published_per_source` partial unique index); publishing supersedes the previous one
 * (supersede-then-publish ordering keeps that index valid at every statement — PLAN §9).
 *
 * [wire] is the lowercase DB representation stored in `source_config_revisions.status`
 * (`chk_revision_status`); the JPA converter maps to/from it.
 */
enum class RevisionStatus(val wire: String) {
    /** Authored, not yet published (or never published). */
    DRAFT("draft"),

    /** The single currently-served revision for its source. */
    PUBLISHED("published"),

    /** A previously-published revision, replaced by a newer publish. Restored only via rollback. */
    SUPERSEDED("superseded"),
    ;

    companion object {
        fun fromWire(wire: String): RevisionStatus =
            entries.firstOrNull { it.wire == wire }
                ?: error("Unknown revision status wire value: '$wire'")
    }
}
