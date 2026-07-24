package me.manga.kira.backend.sourceconfig.domain

import java.time.Instant
import java.util.UUID

/**
 * Mutable admin-editor workspace. This is never a public source revision and is never considered by
 * document/catalog assembly. [version] is the compare-and-swap token used by autosave.
 */
data class SourceEditorDraft(
    val id: UUID,
    val sourceConfigId: UUID,
    val basedOnRevisionNumber: Int,
    val contentJson: String,
    val version: Long,
    val createdBy: UUID,
    val updatedBy: UUID,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class NewSourceEditorDraft(val sourceConfigId: UUID, val basedOnRevisionNumber: Int, val contentJson: String, val actorId: UUID, val at: Instant)
