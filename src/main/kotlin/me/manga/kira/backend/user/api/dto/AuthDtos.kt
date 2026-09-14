package me.manga.kira.backend.user.api.dto

import jakarta.validation.constraints.NotBlank
import me.manga.kira.backend.user.domain.Role
import java.time.Instant
import java.util.UUID

/**
 * Auth API DTOs (PLAN §4.2). Request bodies carry only `@NotBlank` structural checks here — the
 * password *policy* (length/byte bounds) is enforced by the application layer with a clean 400, and
 * the email is trim+lowercased and limited to 320 Unicode code points in that normalized result by
 * the shared service. No raw-size or RFC-shape rule is added at the edge, so padding normalizes
 * before the bound is checked. No response ever contains password material (PLAN §4.4/§6).
 */
data class RegisterRequest(@field:NotBlank val email: String = "", @field:NotBlank val password: String = "")

data class RegisterResponse(val id: UUID, val email: String, val role: Role)

data class LoginRequest(@field:NotBlank val email: String = "", @field:NotBlank val password: String = "")

data class LoginResponse(val accessToken: String, val tokenType: String, val expiresInSeconds: Long, val role: Role)

data class MeResponse(val id: UUID, val email: String, val role: Role, val createdAt: Instant)
