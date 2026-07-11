package me.manga.kira.backend.user.application

import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.audit.domain.AuditAction
import me.manga.kira.backend.security.CurrentUser
import me.manga.kira.backend.user.domain.LastAdminException
import me.manga.kira.backend.user.domain.PagedUsers
import me.manga.kira.backend.user.domain.Role
import me.manga.kira.backend.user.domain.SecurityStateRepository
import me.manga.kira.backend.user.domain.User
import me.manga.kira.backend.user.domain.UserNotFoundException
import me.manga.kira.backend.user.domain.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Admin user management (PLAN §4.4) — a minimal surface (create / list / enable / disable /
 * reset-password), NOT an IdM platform. Every mutation is logged (audit rows arrive in Phase 6).
 *
 * The **last-admin guard** ([disable]) serializes on the `security_state` row lock (PLAN §4.4/§5):
 * enable/disable lock the singleton `FOR UPDATE` *before* counting enabled admins, so two concurrent
 * disables can never each observe "2 enabled admins" and drop different ones to zero. Passwords are
 * never echoed and never logged (PLAN §6).
 */
@Service
class UserAdminService(
    private val users: UserRepository,
    private val securityState: SecurityStateRepository,
    private val userService: UserService,
    private val passwordPolicy: PasswordPolicy,
    private val passwordEncoder: org.springframework.security.crypto.password.PasswordEncoder,
    private val currentUser: CurrentUser,
    private val audit: AuditService,
) {
    /** Admin-create a user with an explicit role (prod onboarding path — PLAN §4.4). */
    @Transactional
    fun createUser(
        email: String,
        rawPassword: String,
        role: Role,
    ): User {
        val user = userService.createUser(email, rawPassword, role)
        log.info("Admin created user id={} role={} actor={}", user.id, role, actor())
        audit.record(AuditAction.USER_CREATED, AuditService.ENTITY_USER, user.id.toString(), mapOf("role" to role.name))
        return user
    }

    @Transactional(readOnly = true)
    fun list(
        page: Int,
        size: Int,
    ): PagedUsers = users.findPage(page, size)

    /** Enable a user. Locks `security_state` first for consistency with the disable guard (PLAN §4.4). */
    @Transactional
    fun enable(id: UUID) {
        securityState.lockForAdminMutation()
        val user = users.findById(id) ?: throw UserNotFoundException()
        if (!user.enabled) {
            users.setEnabled(id, true)
            log.info("User enabled id={} actor={}", id, actor())
            audit.record(AuditAction.USER_ENABLED, AuditService.ENTITY_USER, id.toString())
        }
    }

    /**
     * Disable a user. Under the `security_state` lock: refuses (409) to disable the last enabled
     * ADMIN (PLAN §4.4). Idempotent when the user is already disabled.
     */
    @Transactional
    fun disable(id: UUID) {
        securityState.lockForAdminMutation()
        val user = users.findById(id) ?: throw UserNotFoundException()
        if (!user.enabled) return
        if (user.role == Role.ADMIN && users.countEnabledAdmins() <= 1) {
            throw LastAdminException()
        }
        users.setEnabled(id, false)
        log.info("User disabled id={} actor={}", id, actor())
        audit.record(AuditAction.USER_DISABLED, AuditService.ENTITY_USER, id.toString())
    }

    /** Explicit admin password reset (PLAN §4.4). Policy-checked; the new password is never logged. */
    @Transactional
    fun resetPassword(
        id: UUID,
        newRawPassword: String,
    ) {
        val user = users.findById(id) ?: throw UserNotFoundException()
        passwordPolicy.validate(newRawPassword)
        users.updatePasswordHash(user.id, passwordEncoder.encode(newRawPassword))
        log.info("User password reset id={} actor={}", id, actor())
        // Never the password — actor + target only (PLAN §4.4/§6).
        audit.record(AuditAction.USER_PASSWORD_RESET, AuditService.ENTITY_USER, id.toString())
    }

    private fun actor(): String = currentUser.getOrNull()?.id?.toString() ?: "system"

    private companion object {
        val log = LoggerFactory.getLogger(UserAdminService::class.java)
    }
}
