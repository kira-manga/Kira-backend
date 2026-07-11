package me.manga.kira.backend.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Adds the authenticated `userId` + `role` to the MDC once authentication has run (PLAN §6 /
 * handoff). It is registered AFTER the Spring Security filter chain (so the SecurityContext is
 * populated) and complements [me.manga.kira.backend.common.web.RequestDiagnosticsFilter], which sets
 * the request-scoped fields BEFORE security and therefore cannot see the principal.
 *
 * The keys are removed in `finally` so they never leak across pooled threads; anonymous requests add
 * nothing. Only the id and role are logged — never the email/token/authorities values.
 */
class AuthenticatedMdcFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val principal = SecurityContextHolder.getContext().authentication?.principal
        val added = mutableListOf<String>()
        if (principal is AuthenticatedUser) {
            MDC.put(MDC_USER_ID, principal.id.toString())
            MDC.put(MDC_ROLE, principal.role.name)
            added += MDC_USER_ID
            added += MDC_ROLE
        }
        try {
            filterChain.doFilter(request, response)
        } finally {
            added.forEach { MDC.remove(it) }
        }
    }

    companion object {
        const val MDC_USER_ID = "userId"
        const val MDC_ROLE = "role"
    }
}
