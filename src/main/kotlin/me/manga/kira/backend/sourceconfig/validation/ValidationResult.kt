package me.manga.kira.backend.sourceconfig.validation

/**
 * A single blocking validation finding (PLAN §8). [code] is a stable machine identifier (see
 * [ValidationCodes]); [path] is the pinpoint (e.g. `sources[Azora].filters[genres].request.encode`);
 * [message] is human-readable and never echoes submitted secret VALUES (PLAN §6 log-hygiene).
 */
data class ValidationError(
    val code: String,
    val path: String,
    val message: String,
)

/**
 * An advisory finding that NEVER blocks publish (PLAN §8 rule 33) — currently only
 * [ValidationCodes.UNKNOWN_ICON_KEY]. Surfaced in validation results / admin responses so a stale
 * icon catalog is visible without blocking a valid publish.
 */
data class ValidationWarning(
    val code: String,
    val path: String,
    val message: String,
)

/**
 * The outcome of validating one stanza-in-context or a whole document (PLAN §8). Acceptance is
 * **all-or-nothing**: [isValid] is true iff [errors] is empty (exactly like the app — a document
 * with any error is dropped wholesale). Warnings are advisory and do not affect [isValid].
 */
data class ValidationResult(
    val isValid: Boolean,
    val errors: List<ValidationError>,
    val warnings: List<ValidationWarning> = emptyList(),
) {
    companion object {
        fun of(
            errors: List<ValidationError>,
            warnings: List<ValidationWarning> = emptyList(),
        ): ValidationResult = ValidationResult(errors.isEmpty(), errors, warnings)
    }
}
