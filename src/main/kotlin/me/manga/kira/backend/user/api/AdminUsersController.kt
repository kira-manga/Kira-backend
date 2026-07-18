package me.manga.kira.backend.user.api

import jakarta.validation.Valid
import me.manga.kira.backend.common.PageResponse
import me.manga.kira.backend.common.exception.BadRequestException
import me.manga.kira.backend.user.api.dto.AdminUserResponse
import me.manga.kira.backend.user.api.dto.CreateUserRequest
import me.manga.kira.backend.user.api.dto.ResetPasswordRequest
import me.manga.kira.backend.user.application.UserAdminService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Admin user management (PLAN §4.4) — `ROLE_ADMIN` only (enforced by the security matrix). Prod
 * onboarding path since open registration is disabled. Every response omits password material.
 * Pagination bounds follow §4.5 (`size` 1..100, `page` >= 0; violations → 400).
 */
@RestController
@RequestMapping("/api/v1/admin/users")
class AdminUsersController(private val userAdminService: UserAdminService) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody request: CreateUserRequest): AdminUserResponse {
        val user = userAdminService.createUser(request.email, request.password, request.role)
        return AdminUserResponse.from(user)
    }

    @GetMapping
    fun list(@RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "20") size: Int): PageResponse<AdminUserResponse> {
        if (page < 0) throw BadRequestException("page must be >= 0.", code = "INVALID_PAGE")
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw BadRequestException("size must be between 1 and $MAX_PAGE_SIZE.", code = "INVALID_PAGE_SIZE")
        }
        val paged = userAdminService.list(page, size)
        return PageResponse(
            items = paged.items.map { AdminUserResponse.from(it) },
            page = page,
            size = size,
            total = paged.total,
        )
    }

    @PostMapping("/{id}/enable")
    fun enable(@PathVariable id: UUID) {
        userAdminService.enable(id)
    }

    @PostMapping("/{id}/disable")
    fun disable(@PathVariable id: UUID) {
        userAdminService.disable(id)
    }

    @PostMapping("/{id}/reset-password")
    fun resetPassword(@PathVariable id: UUID, @Valid @RequestBody request: ResetPasswordRequest) {
        userAdminService.resetPassword(id, request.newPassword)
    }

    private companion object {
        const val MAX_PAGE_SIZE = 100
    }
}
