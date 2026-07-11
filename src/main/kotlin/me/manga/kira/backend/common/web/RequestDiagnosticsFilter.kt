package me.manga.kira.backend.common.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.HandlerMapping
import java.util.UUID

/**
 * Correlation-ID + access-logging foundation (PLAN §6, Appendix C #3/#4 / S5).
 *
 * Correlation: an inbound `X-Request-Id` is accepted **only** when it matches
 * [REQUEST_ID_PATTERN] (`[A-Za-z0-9._-]{1,64}`); anything else (control chars, over-length,
 * absent) is discarded and a server-generated UUID is used instead — an unvalidated header value
 * must never reach MDC or a log line (log-injection + unbounded-value guard). The effective id is
 * echoed in the `X-Request-Id` response header and placed in the MDC so every log line during the
 * request carries it.
 *
 * Access logging: on completion the filter logs one line at INFO with the HTTP method, the
 * **normalized route pattern** (`/api/v1/sources/{api}` — never the raw URL, which is
 * uncontrolled input) resolved from Spring's best-matching-pattern attribute, the response
 * status, and the request duration. Those, plus the method, are put in the MDC so the prod
 * structured-JSON encoder emits them as fields; the readable dev/console format shows the message.
 *
 * The MDC is fully cleared in `finally`. The per-feature INFO/WARN/ERROR event catalog (PLAN §6)
 * lands with each feature phase; this filter is only the cross-cutting foundation.
 */
class RequestDiagnosticsFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val requestId = resolveRequestId(request.getHeader(REQUEST_ID_HEADER))
        response.setHeader(REQUEST_ID_HEADER, requestId)
        MDC.put(MDC_REQUEST_ID, requestId)
        MDC.put(MDC_HTTP_METHOD, request.method)
        val startNanos = System.nanoTime()
        try {
            filterChain.doFilter(request, response)
        } finally {
            val durationMs = (System.nanoTime() - startNanos) / 1_000_000
            val route = routePattern(request)
            MDC.put(MDC_ROUTE, route)
            MDC.put(MDC_STATUS, response.status.toString())
            MDC.put(MDC_DURATION_MS, durationMs.toString())
            log.info("http {} {} -> {} ({} ms)", request.method, route, response.status, durationMs)
            MDC.clear()
        }
    }

    private fun resolveRequestId(inbound: String?): String =
        if (inbound != null && REQUEST_ID_PATTERN.matches(inbound)) inbound else UUID.randomUUID().toString()

    /** The matched route template (safe), or a constant for unmatched requests — never the raw URI. */
    private fun routePattern(request: HttpServletRequest): String =
        (request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) as? String) ?: UNMATCHED_ROUTE

    companion object {
        const val REQUEST_ID_HEADER = "X-Request-Id"
        val REQUEST_ID_PATTERN = Regex("^[A-Za-z0-9._-]{1,64}$")
        const val UNMATCHED_ROUTE = "(unmatched)"

        const val MDC_REQUEST_ID = "requestId"
        const val MDC_HTTP_METHOD = "httpMethod"
        const val MDC_ROUTE = "route"
        const val MDC_STATUS = "status"
        const val MDC_DURATION_MS = "durationMs"

        private val log = LoggerFactory.getLogger(RequestDiagnosticsFilter::class.java)
    }
}
