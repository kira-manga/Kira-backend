package me.manga.kira.backend.common

import jakarta.validation.ConstraintViolationException
import me.manga.kira.backend.common.exception.ApiException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException

/**
 * The single problem-envelope mapper (PLAN §2). Every response it produces is
 * `application/problem+json` shaped as [ApiError]. This is the **owning boundary** for these
 * errors (PLAN §6 "log once at the boundary"): a handled 4xx is turned into a clean envelope
 * without a stack trace; only the unexpected-5xx path logs the full trace, and only here — never
 * in a client-visible channel.
 *
 * Feature controllers arrive in later phases; the handlers are inert until then but establish the
 * contract now. Spring Security's 401/403 are produced by the security entry point in the filter
 * chain (Phase 3), not by this advice.
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(ApiException::class)
    fun handleApi(ex: ApiException): ResponseEntity<ApiError> {
        val errors = ex.errors.ifEmpty { listOf(ApiFieldError(code = ex.code, message = ex.detail)) }
        return problem(ex.status, ex.detail, errors)
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleBeanValidation(ex: MethodArgumentNotValidException): ResponseEntity<ApiError> {
        val errors =
            ex.bindingResult.fieldErrors.map {
                // Only the field name + constraint message — never the rejected value (§6/§4.5).
                ApiFieldError(
                    code = it.code ?: "INVALID",
                    path = it.field,
                    message = it.defaultMessage ?: "invalid value",
                )
            }
        return problem(HttpStatus.BAD_REQUEST, "Request validation failed.", errors)
    }

    @ExceptionHandler(ConstraintViolationException::class)
    fun handleConstraintViolation(ex: ConstraintViolationException): ResponseEntity<ApiError> {
        val errors =
            ex.constraintViolations.map {
                ApiFieldError(code = "INVALID", path = it.propertyPath.toString(), message = it.message)
            }
        return problem(HttpStatus.BAD_REQUEST, "Request validation failed.", errors)
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadable(ex: HttpMessageNotReadableException): ResponseEntity<ApiError> =
        // Deliberately generic — the malformed body is never echoed back (§4.5 / §6).
        problem(HttpStatus.BAD_REQUEST, "Malformed or unreadable request body.", emptyList())

    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatus(ex: ResponseStatusException): ResponseEntity<ApiError> {
        val status = HttpStatus.valueOf(ex.statusCode.value())
        return problem(status, ex.reason ?: status.reasonPhrase, emptyList())
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception): ResponseEntity<ApiError> {
        // §6 ERROR: the full stack trace is recorded HERE and only here (secured server log),
        // correlated by the request-id MDC set by RequestDiagnosticsFilter. It is NEVER returned.
        log.error("Unhandled exception", ex)
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.", emptyList())
    }

    private fun problem(
        status: HttpStatus,
        detail: String?,
        errors: List<ApiFieldError>,
    ): ResponseEntity<ApiError> =
        ResponseEntity
            .status(status)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(
                ApiError(
                    title = status.reasonPhrase,
                    status = status.value(),
                    detail = detail,
                    errors = errors,
                ),
            )

    companion object {
        private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)
    }
}
