package me.manga.kira.backend.sourceconfig.domain

import java.time.Instant
import java.util.UUID

/**
 * Persistence **port** for `source_configs` (PLAN §2/§3 — domain declares the interface; the
 * infrastructure adapter implements it over Spring Data + JDBC). Pure Kotlin, narrow methods (ISP).
 *
 * Phase 5 exposed the reads + `create`. Phase 6 adds the publish/lifecycle mutations: the source-row
 * `FOR UPDATE` lock ([lockByApiForUpdate] — under which revision numbers are allocated, PLAN §5), the
 * append position allocator ([nextPosition]), the status/pointer/denormalized mutations
 * ([applyPublishedRevision]/[updateStatus]), and the ordered listing + candidate-assembly reads
 * (`(position ASC, api ASC)`, PLAN §5 source ordering; [findSourcesForAssembly]).
 */
interface SourceConfigRepository {

    /** Find the lifecycle head by its stable [api] key, or null. */
    fun findByApi(api: String): SourceConfigHead?

    /** Find the lifecycle head by primary key, or null. */
    fun findById(id: UUID): SourceConfigHead?

    /** True when a source with this [api] already exists (the DB also enforces `uq_source_configs_api`). */
    fun existsByApi(api: String): Boolean

    /** Create a new source head row and return the stored model (id + timestamps assigned by the adapter). */
    fun create(spec: NewSourceConfig): SourceConfigHead

    /**
     * Lock the source head row `FOR UPDATE` and return it (PLAN §5/§9 — the per-source lock under which
     * revision numbers are allocated and lifecycle/publish mutations serialize). Must run inside a
     * transaction; the lock releases at commit. Returns null when the source does not exist.
     */
    fun lockByApiForUpdate(api: String): SourceConfigHead?

    /** The next append position (`max(position)+1`, or 0 for the first source) — PLAN §5 source ordering. */
    fun nextPosition(): Int

    /**
     * All source heads, optionally filtered by [status], ordered by `(position ASC, api ASC)` (the
     * normative document order; PLAN §5). Includes drafts/retired/removed — the admin list surface.
     */
    fun findAll(status: SourceLifecycleStatus?): List<SourceConfigHead>

    /** Admin listing in one query, including current and latest revision numbers (no per-row lookups). */
    fun findAllWithRevisionNumbers(status: SourceLifecycleStatus?): List<AdminSourceListing>

    /**
     * The sources to assemble into the served document (PLAN §9 steps 4–5): every source in
     * `active|disabled|retired` (NOT draft, NOT removed), ordered by `(position ASC, api ASC)`, each
     * paired with its currently-published revision's **lifecycle-neutral** canonical content.
     */
    fun findSourcesForAssembly(): List<AssemblySource>

    /**
     * Per-source published-revision metadata for the sources in the served document
     * (`active|disabled|retired`): the api, its currently-published revision number, and that revision's
     * publish time. Feeds the `GET /sources` summary's `revisionNumber`/`publishedAt` (PLAN §4.1) — the
     * served-document fields come from the snapshot bytes; these two are not carried there.
     */
    fun findPublishedRevisionMetadata(): List<PublishedRevisionMetadata>

    /**
     * Apply a publish to the head (PLAN §9 step 3): set the published-revision pointer, the resulting
     * [status], `published_at` (first publish), and refresh the denormalized fields from the just
     * published revision's content. Executed as a direct DB update so the change is visible to the
     * subsequent same-transaction assembly read.
     */
    @Suppress("LongParameterList")
    fun applyPublishedRevision(
        id: UUID,
        currentPublishedRevisionId: UUID,
        status: SourceLifecycleStatus,
        publishedAt: Instant,
        displayName: String,
        language: String,
        engine: String,
        baseUrl: String,
        adult: Boolean,
        updatedAt: Instant,
    )

    /** Set the head [status] (a lifecycle transition — disable/enable/retire/remove; PLAN §9). Direct DB update. */
    fun updateStatus(id: UUID, status: SourceLifecycleStatus, updatedAt: Instant)

    /** Update the normative document position as part of an import/reorder transaction. */
    fun updatePosition(id: UUID, position: Int, updatedAt: Instant)
}

data class AdminSourceListing(val head: SourceConfigHead, val currentPublishedRevisionNumber: Int?, val latestRevisionNumber: Int?)

/**
 * The fields needed to create a `source_configs` row (PLAN §5). [position] is the normative document
 * order; [status] is the initial server lifecycle (a fresh admin-created draft is [SourceLifecycleStatus.DRAFT]).
 * The denormalized [displayName]/[language]/[engine]/[baseUrl]/[adult] mirror the current revision.
 */
data class NewSourceConfig(
    val api: String,
    val displayName: String,
    val language: String,
    val engine: String,
    val status: SourceLifecycleStatus,
    val position: Int,
    val baseUrl: String,
    val adult: Boolean,
)

/**
 * One source ready to be rendered into the assembled document (PLAN §9 step 5). [canonicalContent] is
 * the published revision's stored **lifecycle-neutral** canonical bytes; the assembly injects the served
 * lifecycle value derived from [status] (`active`→omitted, `disabled`→`"disabled"`, retired→`"removed"`).
 */
data class AssemblySource(val api: String, val position: Int, val engine: String, val status: SourceLifecycleStatus, val canonicalContent: String)

/**
 * The `GET /sources` summary metadata NOT derivable from the served document bytes (PLAN §4.1):
 * [revisionNumber] is the source's currently-published revision number and [publishedAt] is that
 * revision's publish time (both from `source_config_revisions`).
 */
data class PublishedRevisionMetadata(val api: String, val revisionNumber: Int, val publishedAt: Instant)
