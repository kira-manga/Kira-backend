package me.manga.kira.backend.security

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import me.manga.kira.backend.common.ApiError
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.stereotype.Component

/**
 * Wires the security chain's 401/403 into the SAME `application/problem+json` [ApiError] envelope
 * the [me.manga.kira.backend.common.GlobalExceptionHandler] produces (PLAN §2 / handoff): the
 * `@RestControllerAdvice` does not see Spring Security exceptions, so these bridge them. Bodies stay
 * generic — no credential/token/header echo (PLAN §6 log-hygiene). Details go to secured logs, not
 * the response.
 */
@Component
class ProblemAuthenticationEntryPoint(private val objectMapper: ObjectMapper) : AuthenticationEntryPoint {

    override fun commence(request: HttpServletRequest, response: HttpServletResponse, authException: AuthenticationException) {
        writeProblem(response, HttpStatus.UNAUTHORIZED, "Authentication is required or the token is invalid.")
    }

    private fun writeProblem(response: HttpServletResponse, status: HttpStatus, detail: String) = writeProblemEnvelope(objectMapper, response, status, detail)
}

/** 403 handler — an authenticated caller without the required authority (PLAN §6 matrix). */
@Component
class ProblemAccessDeniedHandler(private val objectMapper: ObjectMapper) : AccessDeniedHandler {

    override fun handle(request: HttpServletRequest, response: HttpServletResponse, accessDeniedException: AccessDeniedException) {
        writeProblemEnvelope(objectMapper, response, HttpStatus.FORBIDDEN, "Access is denied.")
    }
}

/** Shared writer so 401 and 403 are byte-shaped identically to the rest of the API's errors. */
private fun writeProblemEnvelope(objectMapper: ObjectMapper, response: HttpServletResponse, status: HttpStatus, detail: String) {
    response.status = status.value()
    response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
    response.characterEncoding = Charsets.UTF_8.name()
    val body =
        ApiError(
            title = status.reasonPhrase,
            status = status.value(),
            detail = detail,
        )
    objectMapper.writeValue(response.outputStream, body)
}
