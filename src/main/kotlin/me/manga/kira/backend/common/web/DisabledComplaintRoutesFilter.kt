package me.manga.kira.backend.common.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.server.RequestPath
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.pattern.PathPatternParser

/**
 * The current composition supports only disabled complaints. Reject before buffering, authentication
 * or database work; no setting or caller-supplied digest can activate a partial implementation.
 * This is NOT maintenance/quarantine handling for an enabled deployment: that needs the separate
 * recovery/authorized-deletion route matrix and a genuinely qualified installation security chain.
 */
class DisabledComplaintRoutesFilter : OncePerRequestFilter() {
    private val paths = listOf(
        "/api/v1/installations",
        "/api/v1/installations/**",
        "/api/v1/complaints",
        "/api/v1/complaints/**",
        "/api/v1/admin/complaints",
        "/api/v1/admin/complaints/**",
        "/api/v1/complaint-operations/status",
    ).map(PathPatternParser()::parse)

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        if (!isComplaintPath(request)) {
            filterChain.doFilter(request, response)
            return
        }
        response.status = HttpServletResponse.SC_NOT_FOUND
        response.setHeader("X-Kira-Complaint-Contract", "1")
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store, no-transform")
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.setContentLength(PROBLEM.size)
        if (request.method != "HEAD") response.outputStream.write(PROBLEM)
    }

    private fun isComplaintPath(request: HttpServletRequest): Boolean = try {
        val path = RequestPath.parse(request.requestURI, request.contextPath).pathWithinApplication()
        paths.any { it.matches(path) }
    } catch (ex: IllegalArgumentException) {
        // Do not introduce a global malformed-path policy; existing container/security rejection remains.
        false
    }

    private companion object {
        val PROBLEM = """{"type":"about:blank","title":"Not Found","status":404,"detail":"Not found."}""".toByteArray(Charsets.UTF_8)
    }
}
