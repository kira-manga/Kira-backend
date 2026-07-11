package me.manga.kira.backend.common.exception

import me.manga.kira.backend.common.ApiFieldError
import org.springframework.http.HttpStatus

/**
 * Typed application exceptions (PLAN §2). Domain/application code throws these; the single
 * [me.manga.kira.backend.common.GlobalExceptionHandler] `@RestControllerAdvice` maps each to the
 * uniform problem envelope. Errors are always typed here — never a bare `String` at a boundary.
 *
 * [detail] is the sanitized, bounded, human-readable message (safe to return — it never echoes
 * submitted secrets); [code] is the stable machine identifier surfaced in `errors[].code`; [errors]
 * carries field-level pinpoints when there is more than one (e.g. a validation result).
 */
abstract class ApiException(
    val status: HttpStatus,
    val code: String,
    val detail: String,
    val errors: List<ApiFieldError> = emptyList(),
) : RuntimeException(detail)

/** 404 — a requested entity does not exist (PLAN §2). */
class NotFoundException(
    detail: String,
    code: String = "NOT_FOUND",
) : ApiException(HttpStatus.NOT_FOUND, code, detail)

/** 409 — a request conflicts with current state (e.g. duplicate `api`/email) (PLAN §2). */
class ConflictException(
    detail: String,
    code: String = "CONFLICT",
) : ApiException(HttpStatus.CONFLICT, code, detail)

/**
 * 409 — a source-config lifecycle transition is not permitted by the §9 state machine
 * (`INVALID_LIFECYCLE_TRANSITION`). The state machine itself lands in Phase 5; this is its
 * boundary error type.
 */
class InvalidLifecycleTransitionException(
    detail: String,
) : ApiException(HttpStatus.CONFLICT, "INVALID_LIFECYCLE_TRANSITION", detail)

/**
 * 422 — semantic validation failed (PLAN §2, §4.3 publish gate). Carries the collected
 * `errors[]` (all-or-nothing; §8). Distinct from a Tier-1 structural 400 (malformed/identity
 * defects), which later phases raise as a 400-status [ApiException] instead.
 */
class ValidationFailedException(
    errors: List<ApiFieldError>,
    detail: String = "Validation failed.",
) : ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", detail, errors)
