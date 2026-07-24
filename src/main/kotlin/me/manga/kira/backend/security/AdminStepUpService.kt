package me.manga.kira.backend.security

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.exception.UnauthorizedException
import me.manga.kira.backend.config.KiraAdminStudioProperties
import me.manga.kira.backend.user.domain.Role
import me.manga.kira.backend.user.domain.UserRepository
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Password step-up for high-impact source catalog mutations.
 *
 * The opaque proof is generated from 256 random bits, returned once, and stored only as a SHA-256
 * hash. Proofs are scoped, short-lived, and atomically one-time-use.
 */
@Service
class AdminStepUpService(
    private val users: UserRepository,
    private val passwords: PasswordEncoder,
    private val grants: AdminStepUpGrantRepository,
    private val throttle: AuthThrottle,
    private val properties: KiraAdminStudioProperties,
    private val clock: Clock,
) {
    @Transactional
    fun issue(userId: UUID, rawPassword: String, clientIp: String): IssuedAdminStepUp {
        val user = users.findById(userId)
        val identity = user?.email ?: userId.toString()
        throttle.checkLoginAllowed(identity, clientIp)
        val accepted =
            user != null &&
                user.enabled &&
                user.role == Role.ADMIN &&
                passwords.matches(rawPassword, user.passwordHash)
        if (!accepted) {
            throttle.recordLoginFailure(identity, clientIp)
            throw UnauthorizedException(
                "Password verification failed.",
                code = "INVALID_STEP_UP_CREDENTIALS",
            )
        }
        throttle.recordLoginSuccess(identity, clientIp)
        val now = clock.instant()
        val expiresAt = now.plus(properties.stepUpTtl)
        val token = ByteArray(TOKEN_BYTES).also(random::nextBytes).let(encoder::encodeToString)
        grants.deleteExpiredOrUsed(now)
        grants.create(
            NewAdminStepUpGrant(
                id = UUID.randomUUID(),
                userId = userId,
                tokenHash = Sha256.hexUtf8(token),
                scope = SOURCE_ADMIN_MUTATION_SCOPE,
                createdAt = now,
                expiresAt = expiresAt,
            ),
        )
        return IssuedAdminStepUp(token, expiresAt)
    }

    @Transactional
    fun requireSourceMutation(userId: UUID, token: String?) {
        val accepted =
            !token.isNullOrBlank() &&
                token.length <= MAX_TOKEN_CHARS &&
                grants.consume(
                    userId = userId,
                    tokenHash = Sha256.hexUtf8(token),
                    scope = SOURCE_ADMIN_MUTATION_SCOPE,
                    usedAt = clock.instant(),
                )
        if (!accepted) {
            throw UnauthorizedException(
                "A fresh admin password verification is required.",
                code = "ADMIN_STEP_UP_REQUIRED",
            )
        }
    }

    companion object {
        const val SOURCE_ADMIN_MUTATION_SCOPE = "source-admin-mutation"
        const val HEADER = "X-Kira-Admin-Step-Up"
        private const val TOKEN_BYTES = 32
        private const val MAX_TOKEN_CHARS = 128
        private val random = SecureRandom()
        private val encoder = Base64.getUrlEncoder().withoutPadding()
    }
}

data class IssuedAdminStepUp(val token: String, val expiresAt: Instant)
