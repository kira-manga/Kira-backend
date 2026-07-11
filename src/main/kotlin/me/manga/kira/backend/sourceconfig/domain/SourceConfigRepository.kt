package me.manga.kira.backend.sourceconfig.domain

import java.util.UUID

/**
 * Persistence **port** for `source_configs` (PLAN §2/§3 — domain declares the interface; the
 * infrastructure adapter implements it over Spring Data). Pure Kotlin, narrow methods (ISP).
 *
 * Phase 5 exposes the reads + a `create` sufficient for persistence and the startup/consistency tests;
 * the publish/lifecycle mutations (status change, pointer move, source-row `FOR UPDATE` lock, ordered
 * listing for assembly) are added by Phase 6 when `SourceAdminService` orchestrates them.
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
}

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
