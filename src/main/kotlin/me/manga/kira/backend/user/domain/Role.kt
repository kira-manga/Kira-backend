package me.manga.kira.backend.user.domain

/**
 * The two roles the system knows (PLAN §5 `users.role` CHECK, §6 matrix). Authorization is derived
 * from the DB role on every request (never from the JWT claim — PLAN §6), so this enum is the
 * single source of truth for a user's granted authority.
 */
enum class Role {
    ADMIN,
    USER,
}
