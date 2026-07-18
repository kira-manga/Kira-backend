package me.manga.kira.backend.security

import me.manga.kira.backend.user.domain.Role
import me.manga.kira.backend.user.domain.User
import java.time.Instant
import java.util.UUID

/**
 * The authentication **principal** exposed by the DB-backed JWT converter (PLAN §6). It carries the
 * loaded domain user's identity — **never the password hash** — so downstream reads (the
 * [CurrentUser] resolver, `@AuthenticationPrincipal`, `/auth/me`) are pure SecurityContext reads
 * with no second DB query per request.
 */
data class AuthenticatedUser(val id: UUID, val email: String, val role: Role, val createdAt: Instant) {
    companion object {
        fun from(user: User): AuthenticatedUser = AuthenticatedUser(id = user.id, email = user.email, role = user.role, createdAt = user.createdAt)
    }
}
