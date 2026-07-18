package me.manga.kira.backend.user.api.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import me.manga.kira.backend.user.domain.Role
import me.manga.kira.backend.user.domain.User
import java.time.Instant
import java.util.UUID

/**
 * Admin user-management DTOs (PLAN §4.4). List/create responses carry **no password material,
 * ever**. Role is a strict enum — an unknown value is a 400 at deserialization.
 */
data class CreateUserRequest(@field:NotBlank val email: String = "", @field:NotBlank val password: String = "", @field:NotNull val role: Role = Role.USER)

data class ResetPasswordRequest(@field:NotBlank val newPassword: String = "")

data class AdminUserResponse(val id: UUID, val email: String, val role: Role, val enabled: Boolean, val createdAt: Instant) {
    companion object {
        fun from(user: User): AdminUserResponse = AdminUserResponse(
            id = user.id,
            email = user.email,
            role = user.role,
            enabled = user.enabled,
            createdAt = user.createdAt,
        )
    }
}
