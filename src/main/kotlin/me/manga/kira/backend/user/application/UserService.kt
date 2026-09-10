package me.manga.kira.backend.user.application

import me.manga.kira.backend.common.exception.BadRequestException
import me.manga.kira.backend.user.domain.DuplicateEmailException
import me.manga.kira.backend.user.domain.Role
import me.manga.kira.backend.user.domain.User
import me.manga.kira.backend.user.domain.UserRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Shared user operations used by [AuthService], [me.manga.kira.backend.user.application.UserAdminService],
 * and the admin seeder: email normalization, password policy + hashing, duplicate check, creation.
 * Keeps that logic in one place so registration and admin-creation behave identically (PLAN §4.2/§4.4).
 */
@Service
class UserService(private val users: UserRepository, private val passwordEncoder: PasswordEncoder, private val passwordPolicy: PasswordPolicy) {
    /**
     * Email is trim+lowercased, then bounded to 320 Unicode code points before store and matching
     * (PLAN §4.2/§5). Count after lowercase expansion; a supplementary character counts once.
     * Password is NOT normalized.
     */
    fun normalizeEmail(email: String): String {
        val normalized = email.trim().lowercase()
        if (normalized.codePointCount(0, normalized.length) > MAX_EMAIL_CODE_POINTS) {
            throw BadRequestException(
                "Email must be at most $MAX_EMAIL_CODE_POINTS Unicode code points after normalization.",
                code = "EMAIL_TOO_LONG",
            )
        }
        return normalized
    }

    /**
     * Policy-check + hash the password, then insert a user. Case-insensitively unique email:
     * an explicit pre-check plus a catch on the DB `uq_users_email_lower` constraint (the race).
     * The raw password never leaves this method and is never logged (PLAN §6).
     */
    @Transactional
    fun createUser(email: String, rawPassword: String, role: Role): User {
        val normalized = normalizeEmail(email)
        passwordPolicy.validate(rawPassword)
        if (users.existsByEmail(normalized)) throw DuplicateEmailException()
        val hash = passwordEncoder.encode(rawPassword)
        return try {
            users.create(normalized, hash, role)
        } catch (ex: DataIntegrityViolationException) {
            throw DuplicateEmailException()
        }
    }

    /** True if any ADMIN row exists — the seeder's idempotency guard (PLAN §6). */
    fun adminExists(): Boolean = users.adminExists()

    private companion object {
        const val MAX_EMAIL_CODE_POINTS = 320
    }
}
