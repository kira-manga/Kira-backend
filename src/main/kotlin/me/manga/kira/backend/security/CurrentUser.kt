package me.manga.kira.backend.security

import me.manga.kira.backend.common.exception.UnauthorizedException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component

/**
 * `CurrentUser` SecurityContext resolver (PLAN §6). Reads the [AuthenticatedUser] principal that
 * [DbUserJwtAuthenticationConverter] placed on the authentication — a pure context read, no DB
 * query. Services use it for the acting user (e.g. audit actor, completion ownership in later
 * phases); controllers may equivalently use `@AuthenticationPrincipal AuthenticatedUser`.
 */
@Component
class CurrentUser {

    /** The authenticated principal, or null when the request is anonymous. */
    fun getOrNull(): AuthenticatedUser? = SecurityContextHolder.getContext().authentication?.principal as? AuthenticatedUser

    /** The authenticated principal; throws 401 when absent (a programming error on a protected path). */
    fun require(): AuthenticatedUser = getOrNull() ?: throw UnauthorizedException("Authentication required.")
}
