package me.manga.kira.backend.user.application

import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.audit.domain.AuditAction
import me.manga.kira.backend.common.exception.ForbiddenException
import me.manga.kira.backend.common.exception.UnauthorizedException
import me.manga.kira.backend.config.KiraAuthProperties
import me.manga.kira.backend.security.AuthThrottleService
import me.manga.kira.backend.security.IssuedToken
import me.manga.kira.backend.security.JwtService
import me.manga.kira.backend.user.domain.Role
import me.manga.kira.backend.user.domain.User
import me.manga.kira.backend.user.domain.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service

/**
 * Registration + login (PLAN §4.2, §6). Login enforces the throttle BEFORE verifying credentials
 * and returns an **identical generic 401** for unknown-user / wrong-password / disabled account (no
 * account-existence oracle). Registration is gated by `kira.auth.registration-enabled` and a per-IP
 * throttle. No password/token is ever logged (PLAN §6).
 */
@Service
class AuthService(
    private val users: UserRepository,
    private val userService: UserService,
    private val passwordEncoder: PasswordEncoder,
    private val jwtService: JwtService,
    private val throttle: AuthThrottleService,
    private val authProperties: KiraAuthProperties,
    private val audit: AuditService,
) {
    /** Public self-registration → a USER. 403 when disabled; 429 when the per-IP limit is hit. */
    fun register(
        email: String,
        rawPassword: String,
        clientIp: String,
    ): User {
        if (!authProperties.registrationEnabled) {
            throw ForbiddenException("Registration is disabled.", code = "REGISTRATION_DISABLED")
        }
        throttle.checkRegistrationAllowed(clientIp)
        val user = userService.createUser(email, rawPassword, Role.USER)
        log.info("User registered id={}", user.id)
        // Self-registration has no authenticated actor (PLAN §5 — actor NULL = anonymous/system).
        audit.record(
            AuditAction.USER_REGISTERED,
            AuditService.ENTITY_USER,
            user.id.toString(),
            mapOf("role" to user.role.name),
            actorUserId = null,
        )
        return user
    }

    /** Verify credentials and issue an access token. Generic 401 / 429 on failure/throttle. */
    fun login(
        email: String,
        rawPassword: String,
        clientIp: String,
    ): LoginResult {
        val normalized = userService.normalizeEmail(email)
        throttle.checkLoginAllowed(normalized, clientIp)

        val user = users.findByEmail(normalized)
        // A single generic 401 for unknown-user / disabled / wrong-password — no existence oracle.
        if (user == null || !user.enabled || !passwordEncoder.matches(rawPassword, user.passwordHash)) {
            throttle.recordLoginFailure(normalized, clientIp)
            log.warn("Login failed (generic reason category)")
            // Audit the failed attempt (no credential material — the normalized email is an identifier,
            // never the password; PLAN §5/§6). Anonymous actor.
            audit.record(AuditAction.LOGIN_FAILED, AuditService.ENTITY_USER, normalized, actorUserId = null)
            throw UnauthorizedException("Invalid email or password.", code = "INVALID_CREDENTIALS")
        }

        throttle.recordLoginSuccess(normalized, clientIp)
        val token = jwtService.issue(user)
        log.info("Login success id={} role={}", user.id, user.role)
        return LoginResult(token = token, role = user.role)
    }

    private companion object {
        val log = LoggerFactory.getLogger(AuthService::class.java)
    }
}

/** Result of a successful login — the controller maps it to the API DTO (PLAN §4.2). */
data class LoginResult(
    val token: IssuedToken,
    val role: Role,
)
