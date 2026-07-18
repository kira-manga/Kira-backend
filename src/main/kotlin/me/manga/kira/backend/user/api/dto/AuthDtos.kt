package me.manga.kira.backend.user.api.dto

import jakarta.validation.constraints.NotBlank
import me.manga.kira.backend.user.domain.Role
import java.time.Instant
import java.util.UUID

/**
 * Auth API DTOs (PLAN §4.2). Request bodies carry only `@NotBlank` structural checks here — the
 * password *policy* (length/byte bounds) is enforced by the application layer with a clean 400, and
 * the email is trim+lowercased by the service (not validated for RFC shape at the edge, so leading
 * spaces normalize rather than 400). No response ever contains password material (PLAN §4.4/§6).
 */
data class RegisterRequest(@field:NotBlank val email: String = "", @field:NotBlank val password: String = "")

data class RegisterResponse(val id: UUID, val email: String, val role: Role)

data class LoginRequest(@field:NotBlank val email: String = "", @field:NotBlank val password: String = "")

data class LoginResponse(val accessToken: String, val tokenType: String, val expiresInSeconds: Long, val role: Role)

data class MeResponse(val id: UUID, val email: String, val role: Role, val createdAt: Instant)
