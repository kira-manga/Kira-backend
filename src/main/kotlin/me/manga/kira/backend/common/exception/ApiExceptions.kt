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
abstract class ApiException(val status: HttpStatus, val code: String, val detail: String, val errors: List<ApiFieldError> = emptyList()) :
    RuntimeException(detail)

/**
 * 400 — a structurally/semantically invalid request the caller must fix (PLAN §4.5). Used for the
 * password-policy violation and (in later phases) the Tier-1 structural authoring gate (§8). The
 * [detail]/[code] never echo the submitted value (§6 log-hygiene).
 */
class BadRequestException(detail: String, code: String = "BAD_REQUEST", errors: List<me.manga.kira.backend.common.ApiFieldError> = emptyList()) :
    ApiException(HttpStatus.BAD_REQUEST, code, detail, errors)

/**
 * 401 — authentication failed at an application boundary (e.g. bad login credentials). The message
 * is deliberately generic and identical for unknown-user / wrong-password / disabled account so it
 * cannot be used as an account-existence oracle (PLAN §4.2 / §6). Distinct from the security
 * pipeline's own 401 for missing/invalid bearer tokens.
 */
class UnauthorizedException(detail: String, code: String = "UNAUTHORIZED") : ApiException(HttpStatus.UNAUTHORIZED, code, detail)

/** 403 — the request is understood and authenticated-or-anonymous but not permitted (PLAN §4.2). */
class ForbiddenException(detail: String, code: String = "FORBIDDEN") : ApiException(HttpStatus.FORBIDDEN, code, detail)

/**
 * 429 — the caller is throttled (PLAN §6 auth throttling). The body is the SAME generic shape as an
 * auth failure so throttling reveals no username-exists oracle.
 */
class TooManyRequestsException(detail: String, code: String = "TOO_MANY_REQUESTS", val retryAfterSeconds: Long = 60) :
    ApiException(HttpStatus.TOO_MANY_REQUESTS, code, detail)

/** 503 — bounded backend capacity or a required dependency is temporarily unavailable. */
class ServiceUnavailableException(detail: String, code: String = "SERVICE_UNAVAILABLE", val retryAfterSeconds: Long = 5) :
    ApiException(HttpStatus.SERVICE_UNAVAILABLE, code, detail)

/**
 * 413 — the request body exceeds the endpoint's size limit (PLAN §4.5). The `import-bundled` endpoint
 * caps the body at 5 MiB (the §4.5 default elsewhere is 256 KB); an over-limit body is one consistent
 * status, never a 400. The [detail] never echoes any submitted content (§6 log-hygiene).
 */
class PayloadTooLargeException(detail: String, code: String = "PAYLOAD_TOO_LARGE") : ApiException(HttpStatus.PAYLOAD_TOO_LARGE, code, detail)

/** 404 — a requested entity does not exist (PLAN §2). `open` so feature domains can subclass it. */
open class NotFoundException(detail: String, code: String = "NOT_FOUND") : ApiException(HttpStatus.NOT_FOUND, code, detail)

/** 409 — a request conflicts with current state (e.g. duplicate `api`/email) (PLAN §2). `open` for subclassing. */
open class ConflictException(detail: String, code: String = "CONFLICT") : ApiException(HttpStatus.CONFLICT, code, detail)

/**
 * 410 — the resource existed but is permanently gone (PLAN §4.1). Used for a terminally `removed`
 * source at `GET /sources/{api}` — distinct from a 404 (unknown/never-published), so the app can tell
 * "this source is gone for good" from "no such source". `open` so feature domains can subclass it.
 */
open class GoneException(detail: String, code: String = "GONE") : ApiException(HttpStatus.GONE, code, detail)

/**
 * 409 — a source-config lifecycle transition is not permitted by the §9 state machine
 * (`INVALID_LIFECYCLE_TRANSITION`). The state machine itself lands in Phase 5; this is its
 * boundary error type.
 */
class InvalidLifecycleTransitionException(detail: String) : ApiException(HttpStatus.CONFLICT, "INVALID_LIFECYCLE_TRANSITION", detail)

/**
 * 422 — semantic validation failed (PLAN §2, §4.3 publish gate). Carries the collected
 * `errors[]` (all-or-nothing; §8). Distinct from a Tier-1 structural 400 (malformed/identity
 * defects), which later phases raise as a 400-status [ApiException] instead.
 */
class ValidationFailedException(errors: List<ApiFieldError>, detail: String = "Validation failed.") :
    ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", detail, errors)
