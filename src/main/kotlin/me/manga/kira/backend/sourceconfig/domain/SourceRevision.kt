package me.manga.kira.backend.sourceconfig.domain

import java.time.Instant
import java.util.UUID

/**
 * Immutable domain view of a `source_config_revisions` row (PLAN §5) — one entry of a source's
 * immutable history. [configCanonicalJson] is the stanza's canonical (kcj-1) bytes, the source of
 * truth; [checksum] is the SHA-256 hex of those UTF-8 bytes (both produced from one validated parsed
 * model). Content is **lifecycle-neutral** (PLAN §9): the `lifecycle` key is absent from these bytes.
 */
data class SourceRevision(
    val id: UUID,
    val sourceConfigId: UUID,
    val revisionNumber: Int,
    val configCanonicalJson: String,
    val checksum: String,
    val canonVersion: String,
    val status: RevisionStatus,
    val createdBy: UUID,
    val notes: String?,
    val createdAt: Instant,
    val publishedAt: Instant?,
)
