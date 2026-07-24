package me.manga.kira.backend.sourceconfig.api.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import me.manga.kira.backend.sourceconfig.application.CatalogChange
import me.manga.kira.backend.sourceconfig.application.CatalogChangeType
import me.manga.kira.backend.sourceconfig.application.ChangesetApplyOutcome
import me.manga.kira.backend.sourceconfig.application.ChangesetValidation
import me.manga.kira.backend.sourceconfig.domain.SourceChangeset
import java.time.Instant
import java.util.UUID

data class CreateSourceChangesetRequest(@field:NotBlank @field:Size(max = 120) val name: String?, @field:Size(max = 1000) val description: String? = null)

data class SaveSourceChangesetRequest(
    @field:NotBlank @field:Size(max = 120) val name: String?,
    @field:Size(max = 1000) val description: String? = null,
    @field:Size(max = 500) @field:Valid val operations: List<SourceChangeRequest> = emptyList(),
)

data class SourceChangeRequest(
    @field:NotBlank val type: String?,
    @field:Size(max = 160) val api: String? = null,
    val revisionNumber: Int? = null,
    @field:Size(max = 160) val confirm: String? = null,
    @field:Size(max = 500) val orderedApis: List<
        @NotBlank
        @Size(max = 160)
        String,
        >? = null,
) {
    fun toDomain(): CatalogChange {
        val parsed =
            CatalogChangeType.entries.firstOrNull { it.wire == type }
                ?: throw me.manga.kira.backend.sourceconfig.application.InvalidChangesetException(
                    "unknown source change type.",
                )
        return CatalogChange(parsed, api, revisionNumber, confirm, orderedApis)
    }

    companion object {
        fun of(change: CatalogChange): SourceChangeRequest =
            SourceChangeRequest(change.type.wire, change.api, change.revisionNumber, change.confirm, change.orderedApis)
    }
}

data class SourceChangesetResponse(
    val id: UUID,
    val name: String,
    val description: String?,
    val operations: List<SourceChangeRequest>,
    val status: String,
    val version: Long,
    val appliedDocumentRevision: Long?,
    val createdBy: UUID,
    val updatedBy: UUID,
    val createdAt: Instant,
    val updatedAt: Instant,
    val appliedAt: Instant?,
) {
    companion object {
        fun of(changeset: SourceChangeset, operations: List<CatalogChange>) = SourceChangesetResponse(
            id = changeset.id,
            name = changeset.name,
            description = changeset.description,
            operations = operations.map(SourceChangeRequest::of),
            status = changeset.status.wire,
            version = changeset.version,
            appliedDocumentRevision = changeset.appliedDocumentRevision,
            createdBy = changeset.createdBy,
            updatedBy = changeset.updatedBy,
            createdAt = changeset.createdAt,
            updatedAt = changeset.updatedAt,
            appliedAt = changeset.appliedAt,
        )
    }
}

data class SourceChangesetValidationResponse(val valid: Boolean, val operationCount: Int, val affectedApis: List<String>) {
    companion object {
        fun of(value: ChangesetValidation) = SourceChangesetValidationResponse(value.valid, value.operationCount, value.affectedApis)
    }
}

data class SourceChangesetApplyResponse(val documentRevision: Long, val checksum: String, val affectedApis: List<String>) {
    companion object {
        fun of(value: ChangesetApplyOutcome) = SourceChangesetApplyResponse(value.documentRevision, value.checksum, value.affectedApis)
    }
}
