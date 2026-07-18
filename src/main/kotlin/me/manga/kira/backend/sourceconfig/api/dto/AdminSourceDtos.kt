package me.manga.kira.backend.sourceconfig.api.dto

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonRawValue
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import me.manga.kira.backend.sourceconfig.application.BundledImportResult
import me.manga.kira.backend.sourceconfig.application.LifecycleConflict
import me.manga.kira.backend.sourceconfig.application.PublishOutcome
import me.manga.kira.backend.sourceconfig.application.RevisionView
import me.manga.kira.backend.sourceconfig.application.RollbackOutcome
import me.manga.kira.backend.sourceconfig.application.SourceAdminView
import me.manga.kira.backend.sourceconfig.application.SourceMutationResult
import me.manga.kira.backend.sourceconfig.domain.PublishedDocument
import me.manga.kira.backend.sourceconfig.validation.ValidationError
import me.manga.kira.backend.sourceconfig.validation.ValidationResult
import me.manga.kira.backend.sourceconfig.validation.ValidationWarning
import java.time.Instant
import java.util.UUID

/*
 * Admin source-management API DTOs (PLAN §4.3). Jackson-serialized (the source-config MODEL uses
 * kotlinx; these are thin API views mapped from application results, never leaking entities). Lifecycle
 * and revision statuses are exposed as their lowercase wire values (the plan/DB vocabulary).
 */

/** One `{code, path, message}` validation finding (PLAN §4.3 / §8). */
data class FindingDto(val code: String, val path: String, val message: String) {
    companion object {
        fun of(e: ValidationError) = FindingDto(e.code, e.path, e.message)

        fun of(w: ValidationWarning) = FindingDto(w.code, w.path, w.message)
    }
}

/** `{valid, errors[], warnings[]}` (PLAN §4.3 create/validate response). */
data class ValidationResultDto(val valid: Boolean, val errors: List<FindingDto>, val warnings: List<FindingDto>) {
    companion object {
        fun of(result: ValidationResult) = ValidationResultDto(
            valid = result.isValid,
            errors = result.errors.map { FindingDto.of(it) },
            warnings = result.warnings.map { FindingDto.of(it) },
        )
    }
}

/** `POST /admin/sources` + `.../revisions` response: the stored draft + its inline validation. */
data class SourceMutationResponse(val api: String, val status: String, val revisionNumber: Int, val validation: ValidationResultDto) {
    companion object {
        fun of(result: SourceMutationResult) = SourceMutationResponse(
            api = result.api,
            status = result.status.wire,
            revisionNumber = result.revisionNumber,
            validation = ValidationResultDto.of(result.validation),
        )
    }
}

/** `GET /admin/sources` list item + `GET /admin/sources/{api}` — the head + revision pointers. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class AdminSourceResponse(
    val api: String,
    val displayName: String,
    val language: String,
    val engine: String,
    val status: String,
    val position: Int,
    val baseUrl: String,
    val adult: Boolean,
    val currentPublishedRevisionNumber: Int?,
    val latestRevisionNumber: Int?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val publishedAt: Instant?,
) {
    companion object {
        fun of(view: SourceAdminView): AdminSourceResponse {
            val h = view.head
            return AdminSourceResponse(
                api = h.api,
                displayName = h.displayName,
                language = h.language,
                engine = h.engine,
                status = h.status.wire,
                position = h.position,
                baseUrl = h.baseUrl,
                adult = h.adult,
                currentPublishedRevisionNumber = view.currentPublishedRevisionNumber,
                latestRevisionNumber = view.latestRevisionNumber,
                createdAt = h.createdAt,
                updatedAt = h.updatedAt,
                publishedAt = h.publishedAt,
            )
        }
    }
}

/** `GET /admin/sources/{api}/revisions` list item (PLAN §4.3). */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class RevisionSummaryResponse(
    val revisionNumber: Int,
    val status: String,
    val checksum: String,
    val createdBy: UUID,
    val createdAt: Instant,
    val publishedAt: Instant?,
    val valid: Boolean?,
) {
    companion object {
        fun of(view: RevisionView): RevisionSummaryResponse {
            val r = view.revision
            return RevisionSummaryResponse(
                revisionNumber = r.revisionNumber,
                status = r.status.wire,
                checksum = r.checksum,
                createdBy = r.createdBy,
                createdAt = r.createdAt,
                publishedAt = r.publishedAt,
                valid = view.valid,
            )
        }
    }
}

/** `GET /admin/sources/{api}/revisions/{n}` — full stored config JSON (raw) + metadata (PLAN §4.3). */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class RevisionDetailResponse(
    val revisionNumber: Int,
    val status: String,
    // The stored canonical (lifecycle-neutral) bytes, emitted verbatim as JSON (not a re-serialized string).
    @get:JsonRawValue val config: String,
    val checksum: String,
    val canonVersion: String,
    val createdBy: UUID,
    val createdAt: Instant,
    val publishedAt: Instant?,
    val valid: Boolean?,
) {
    companion object {
        fun of(view: RevisionView): RevisionDetailResponse {
            val r = view.revision
            return RevisionDetailResponse(
                revisionNumber = r.revisionNumber,
                status = r.status.wire,
                config = r.configCanonicalJson,
                checksum = r.checksum,
                canonVersion = r.canonVersion,
                createdBy = r.createdBy,
                createdAt = r.createdAt,
                publishedAt = r.publishedAt,
                valid = view.valid,
            )
        }
    }
}

/** `POST .../publish` / lifecycle response `{documentRevision, checksum}` (PLAN §4.3). */
data class PublishResponse(val documentRevision: Long, val checksum: String) {
    companion object {
        fun of(outcome: PublishOutcome) = PublishResponse(outcome.documentRevision, outcome.checksum)
    }
}

/** `POST .../rollback` response `{newRevisionNumber, documentRevision}` (PLAN §4.3). */
data class RollbackResponse(val newRevisionNumber: Int, val documentRevision: Long, val checksum: String) {
    companion object {
        fun of(outcome: RollbackOutcome) = RollbackResponse(outcome.newRevisionNumber, outcome.documentRevision, outcome.checksum)
    }
}

/** `GET /admin/documents` list item (PLAN §4.3). */
data class DocumentSummaryResponse(
    val documentRevision: Long,
    val schemaVersion: Int,
    val checksum: String,
    val sourceCount: Int,
    val createdBy: UUID,
    val createdAt: Instant,
) {
    companion object {
        fun of(doc: PublishedDocument) = DocumentSummaryResponse(
            documentRevision = doc.documentRevision,
            schemaVersion = doc.schemaVersion,
            checksum = doc.checksum,
            sourceCount = doc.sourceCount,
            createdBy = doc.createdBy,
            createdAt = doc.createdAt,
        )
    }
}

/** `POST /admin/documents/validate` response `{valid, errors[]}` (PLAN §4.3). */
data class DocumentValidationResponse(val valid: Boolean, val errors: List<FindingDto>) {
    companion object {
        fun of(result: ValidationResult) = DocumentValidationResponse(result.isValid, result.errors.map { FindingDto.of(it) })
    }
}

/** One `{api, payloadLifecycle, serverLifecycle}` lifecycle conflict (PLAN §12.2). */
data class LifecycleConflictDto(val api: String, val payloadLifecycle: String, val serverLifecycle: String) {
    companion object {
        fun of(c: LifecycleConflict) = LifecycleConflictDto(c.api, c.payloadLifecycle, c.serverLifecycle)
    }
}

/**
 * `POST /admin/sources/import-bundled` response (PLAN §4.3 / §12.2). The `created/updated/unchanged/
 * skippedRemoved/skippedRetired/skippedDraft` arrays are api strings (payload order); `lifecycleConflicts` are the
 * informational payload-vs-server lifecycle mismatches; `warnings` mirrors the validation-DTO finding
 * shape. `documentRevision` is **absent on the no-op case** (`NON_NULL` — nothing changed → no snapshot).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ImportBundledResponse(
    val created: List<String>,
    val updated: List<String>,
    val unchanged: List<String>,
    val skippedRemoved: List<String>,
    val skippedRetired: List<String>,
    val skippedDraft: List<String>,
    val lifecycleConflicts: List<LifecycleConflictDto>,
    val warnings: List<FindingDto>,
    val documentRevision: Long?,
) {
    companion object {
        fun of(result: BundledImportResult) = ImportBundledResponse(
            created = result.created,
            updated = result.updated,
            unchanged = result.unchanged,
            skippedRemoved = result.skippedRemoved,
            skippedRetired = result.skippedRetired,
            skippedDraft = result.skippedDraft,
            lifecycleConflicts = result.lifecycleConflicts.map { LifecycleConflictDto.of(it) },
            warnings = result.warnings.map { FindingDto.of(it) },
            documentRevision = result.documentRevision,
        )
    }
}

/** `POST .../rollback` request body `{toRevision}` (PLAN §4.3). */
data class RollbackRequest(@field:Positive val toRevision: Int = 0)

/** `POST .../remove` request body `{confirm}` — foot-gun guard, must equal the api (PLAN §4.3). */
data class RemoveRequest(@field:NotBlank val confirm: String = "")
