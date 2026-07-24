package me.manga.kira.backend.sourceconfig.domain

import java.time.Instant
import java.util.UUID

/** Persistence port for the single collaborative editor draft associated with each source. */
interface SourceEditorDraftRepository {
    fun findBySourceConfigId(sourceConfigId: UUID): SourceEditorDraft?

    fun create(spec: NewSourceEditorDraft): SourceEditorDraft

    /**
     * Compare-and-swap content update. Returns null when [expectedVersion] is stale or the draft no
     * longer exists.
     */
    fun updateContent(id: UUID, expectedVersion: Long, contentJson: String, actorId: UUID, updatedAt: Instant): SourceEditorDraft?

    /**
     * Move the workspace baseline to a newly-created immutable revision while retaining the canonical
     * content in the editor. Returns null on a concurrent edit.
     */
    fun updateBaseline(id: UUID, expectedVersion: Long, basedOnRevisionNumber: Int, contentJson: String, actorId: UUID, updatedAt: Instant): SourceEditorDraft?

    /** Compare-and-swap delete. */
    fun delete(id: UUID, expectedVersion: Long): Boolean
}
