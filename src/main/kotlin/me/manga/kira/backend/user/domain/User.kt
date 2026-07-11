package me.manga.kira.backend.user.domain

import java.time.Instant
import java.util.UUID

/**
 * Immutable user domain model (PLAN §2 domain layer, §5 `users`). Pure Kotlin — no JPA/Spring
 * types. The [passwordHash] is a `DelegatingPasswordEncoder` `{id}hash` string; it is never
 * serialized into any API DTO and never logged (PLAN §6 log-hygiene).
 */
data class User(
    val id: UUID,
    val email: String,
    val passwordHash: String,
    val role: Role,
    val enabled: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)
