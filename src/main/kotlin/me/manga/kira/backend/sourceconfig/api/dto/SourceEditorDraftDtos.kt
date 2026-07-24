package me.manga.kira.backend.sourceconfig.api.dto

import jakarta.validation.constraints.NotNull
import me.manga.kira.backend.sourceconfig.application.FinalizedSourceDraft
import me.manga.kira.backend.sourceconfig.application.PublishedSourceDraft
import me.manga.kira.backend.sourceconfig.domain.SourceEditorDraft
import java.time.Instant
import java.util.UUID

data class OpenSourceDraftRequest(val fromRevision: Int? = null)

data class SaveSourceDraftRequest(
    @field:NotNull
    val content: String? = null,
)

data class SourceDraftResponse(
    val id: UUID,
    val basedOnRevisionNumber: Int,
    val content: String,
    val version: Long,
    val createdBy: UUID,
    val updatedBy: UUID,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun of(draft: SourceEditorDraft) = SourceDraftResponse(
            id = draft.id,
            basedOnRevisionNumber = draft.basedOnRevisionNumber,
            content = draft.contentJson,
            version = draft.version,
            createdBy = draft.createdBy,
            updatedBy = draft.updatedBy,
            createdAt = draft.createdAt,
            updatedAt = draft.updatedAt,
        )
    }
}

data class FinalizeSourceDraftResponse(val draft: SourceDraftResponse, val revision: SourceMutationResponse) {
    companion object {
        fun of(result: FinalizedSourceDraft) = FinalizeSourceDraftResponse(
            draft = SourceDraftResponse.of(result.draft),
            revision = SourceMutationResponse.of(result.revision),
        )
    }
}

data class PublishSourceDraftResponse(val draft: SourceDraftResponse, val revision: SourceMutationResponse, val publication: PublishResponse) {
    companion object {
        fun of(result: PublishedSourceDraft) = PublishSourceDraftResponse(
            draft = SourceDraftResponse.of(result.draft),
            revision = SourceMutationResponse.of(result.publication.revision),
            publication = PublishResponse.of(result.publication.publication),
        )
    }
}
