package me.manga.kira.backend.sourceconfig.domain

import me.manga.kira.backend.sourceconfig.validation.ValidationError
import me.manga.kira.backend.sourceconfig.validation.ValidationWarning
import java.util.UUID

/**
 * Persistence **port** for `source_validation_results` (PLAN §2/§3). Pure Kotlin. The adapter
 * serializes [ValidationError]/[ValidationWarning] into the `errors`/`warnings` jsonb arrays and back.
 * History is kept (a revision may accumulate several results); [findLatestForRevision] returns the most
 * recently validated one.
 */
interface ValidationResultRepository {

    /** Store a validation outcome for a revision; returns the stored model. */
    fun save(spec: NewValidationResult): StoredValidationResult

    /** The most recently validated result for [revisionId], or null if never validated. */
    fun findLatestForRevision(revisionId: UUID): StoredValidationResult?
}

/** The fields needed to insert a `source_validation_results` row (PLAN §5). */
data class NewValidationResult(
    val revisionId: UUID,
    val valid: Boolean,
    val errors: List<ValidationError>,
    val warnings: List<ValidationWarning>,
    val rulesVersion: String,
)
