package me.manga.kira.backend.security

import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt

/**
 * The authenticated token produced by [DbUserJwtAuthenticationConverter]. Its **principal** is the
 * [AuthenticatedUser] loaded from the DB (so `@AuthenticationPrincipal` and [CurrentUser] read it
 * directly) and its **authorities** are derived from the DB role (PLAN §6). The verified [Jwt] is
 * retained as the credentials for diagnostics.
 */
class BearerUserAuthenticationToken(
    private val jwt: Jwt,
    private val user: AuthenticatedUser,
    authorities: Collection<GrantedAuthority>,
) : AbstractAuthenticationToken(authorities) {

    init {
        isAuthenticated = true
    }

    override fun getPrincipal(): AuthenticatedUser = user

    override fun getCredentials(): Jwt = jwt

    override fun getName(): String = user.id.toString()
}
