package me.manga.kira.backend.sourceconfig.domain

import me.manga.kira.backend.sourceconfig.validation.ValidationError
import me.manga.kira.backend.sourceconfig.validation.ValidationWarning
import java.time.Instant
import java.util.UUID

/**
 * Immutable domain view of a `source_validation_results` row (PLAN §5). Wraps the stored validation
 * outcome for a revision — invalid drafts ARE stored so admins can inspect them; publish always
 * re-validates live. [errors]/[warnings] are the parsed `{code, path, message}` arrays (the pure
 * validation types from the sibling `validation` package). [rulesVersion] lets a stored "valid" be
 * recognized as stale after a rule change.
 */
data class StoredValidationResult(
    val id: UUID,
    val revisionId: UUID,
    val valid: Boolean,
    val errors: List<ValidationError>,
    val warnings: List<ValidationWarning>,
    val rulesVersion: String,
    val validatedAt: Instant,
)
