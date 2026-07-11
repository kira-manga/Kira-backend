package me.manga.kira.backend.security

import me.manga.kira.backend.user.domain.Role

/**
 * Spring Security authority naming (PLAN §6). Spring's `hasRole("ADMIN")` matches the authority
 * `ROLE_ADMIN`, so authorities are the role name with the conventional `ROLE_` prefix. Authorities
 * are always derived from the **DB** [Role] on each request, never from the JWT claim (PLAN §6).
 */
object Roles {
    const val PREFIX = "ROLE_"
    const val ADMIN = "ROLE_ADMIN"
    const val USER = "ROLE_USER"

    fun authority(role: Role): String = PREFIX + role.name
}
