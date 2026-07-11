package me.manga.kira.backend.sourceconfig.domain

import java.time.Instant
import java.util.UUID

/**
 * Immutable domain view of a `source_configs` row (PLAN §5) — a source's identity + lifecycle head.
 * Pure Kotlin; the infrastructure adapter maps entity↔this model so entities never escape that layer
 * (PLAN §2). Denormalized fields ([displayName]/[language]/[engine]/[baseUrl]/[adult]) mirror the
 * current revision for cheap listing; [position] is the normative document order (PLAN §5).
 *
 * [currentPublishedRevisionId] is NULL until first publish; it points (via a composite FK that makes a
 * cross-source pointer unrepresentable) at this source's own published revision.
 */
data class SourceConfigHead(
    val id: UUID,
    val api: String,
    val displayName: String,
    val language: String,
    val engine: String,
    val status: SourceLifecycleStatus,
    val position: Int,
    val baseUrl: String,
    val adult: Boolean,
    val currentPublishedRevisionId: UUID?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val publishedAt: Instant?,
)
