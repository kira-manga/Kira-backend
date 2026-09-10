package me.manga.kira.backend.security

import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.common.exception.TooManyRequestsException
import me.manga.kira.backend.config.KiraAdminStudioProperties
import me.manga.kira.backend.config.KiraAuthProperties
import me.manga.kira.backend.config.KiraSecurityProperties
import me.manga.kira.backend.support.MutableClock
import me.manga.kira.backend.user.application.AuthService
import me.manga.kira.backend.user.application.CredentialVerifier
import me.manga.kira.backend.user.application.PasswordPolicy
import me.manga.kira.backend.user.application.UserService
import me.manga.kira.backend.user.domain.Role
import me.manga.kira.backend.user.domain.User
import me.manga.kira.backend.user.domain.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.security.crypto.password.PasswordEncoder
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException

class AuthAttemptCallerTest {
    private val clock = MutableClock()
    private val users = mock(UserRepository::class.java)
    private val encoder = mock(PasswordEncoder::class.java)
    private val tokens = mock(JwtService::class.java)
    private val grants = mock(AdminStepUpGrantRepository::class.java)
    private val audit = mock(AuditService::class.java)
    private val user = User(UUID.randomUUID(), EMAIL, "eligible-hash", Role.ADMIN, true, Instant.EPOCH, Instant.EPOCH)

    init {
        `when`(users.findByEmail(EMAIL)).thenReturn(user)
        `when`(users.findById(user.id)).thenReturn(user)
        `when`(encoder.encode("kira-login-decoy-password-not-an-account")).thenReturn("decoy-hash")
    }

    @Test
    fun `ordinary rejection completes failure before audit and does not suppress unavailable behind 401`() {
        listOf(false, true).forEach { stepUp ->
            var completions = 0
            val throttle = attemptThrottle {
                assertEquals(false, it)
                completions += 1
                throw unavailable()
            }
            val error = assertThrows<TooManyRequestsException> { call(throttle, stepUp) }
            assertUnavailable(error)
            assertEquals(1, completions) // use.close must not retry the already-claimed remote completion.
        }
        verifyNoInteractions(tokens, grants, audit)
    }

    @Test
    fun `successful credentials cannot issue JWT or grant without completion acknowledgement`() {
        doReturn(true).`when`(encoder).matches(PASSWORD, user.passwordHash)
        listOf(false, true).forEach { stepUp ->
            val error = assertThrows<TooManyRequestsException> {
                call(
                    attemptThrottle { success ->
                        assertEquals(true, success)
                        throw unavailable()
                    },
                    stepUp,
                )
            }
            assertUnavailable(error)
        }
        verifyNoInteractions(tokens, grants, audit)
    }

    @Test
    fun `an already finalized reservation cannot authorize a caller even with correct credentials`() {
        doReturn(true).`when`(encoder).matches(PASSWORD, user.passwordHash)
        listOf(false, true).forEach { stepUp ->
            listOf(false, true).forEach { previousSuccess ->
                val attempt = AuthLoginAttempt { }.apply { complete(previousSuccess) }
                val throttle = object : AuthThrottle {
                    override fun beginLoginAttempt(normalizedEmail: String, clientIp: String) = attempt

                    override fun checkRegistrationAllowed(clientIp: String) = Unit
                }
                assertThrows<TooManyRequestsException> { call(throttle, stepUp) }
            }
        }
        verifyNoInteractions(tokens, grants, audit)
    }

    @Test
    fun `expired and unknown success completions from real memory adapter cannot issue`() {
        listOf(false, true).forEach { stepUp ->
            listOf(false, true).forEach { unknown ->
                val throttle = memory()
                doAnswer {
                    if (unknown) throttle.clearAll() else clock.advance(Duration.ofSeconds(1))
                    true
                }.`when`(encoder).matches(PASSWORD, user.passwordHash)
                assertThrows<TooManyRequestsException> { call(throttle, stepUp) }
                if (unknown) assertEquals(0, throttle.size())
            }
        }
        verifyNoInteractions(tokens, grants, audit)
    }

    @Test
    fun `exception and cancellation exits close real reservations as one failure`() {
        listOf(false, true).forEach { stepUp ->
            listOf(IllegalStateException("injected verifier failure"), CancellationException("injected cancellation")).forEach { original ->
                val throttle = memory()
                doAnswer { throw original }.`when`(encoder).matches(PASSWORD, user.passwordHash)
                assertSame(original, assertThrows<RuntimeException> { call(throttle, stepUp) })
                assertThrows<TooManyRequestsException> { throttle.beginLoginAttempt(EMAIL, IP) }
            }
        }
        verifyNoInteractions(tokens, grants, audit)
    }

    @Test
    fun `unexpected cleanup failure is suppressed behind original verifier exception and is once-only`() {
        listOf(false, true).forEach { stepUp ->
            val original = CancellationException("injected cancellation")
            var completions = 0
            val attempt = AuthLoginAttempt {
                completions += 1
                throw unavailable()
            }
            val throttle = object : AuthThrottle {
                override fun beginLoginAttempt(normalizedEmail: String, clientIp: String) = attempt

                override fun checkRegistrationAllowed(clientIp: String) = Unit
            }
            doAnswer { throw original }.`when`(encoder).matches(PASSWORD, user.passwordHash)
            assertSame(original, assertThrows<CancellationException> { call(throttle, stepUp) })
            assertEquals(1, original.suppressed.size)
            assertUnavailable(original.suppressed.single() as TooManyRequestsException)
            attempt.close()
            assertThrows<TooManyRequestsException> { attempt.complete(true) }
            assertEquals(1, completions)
        }
        verifyNoInteractions(tokens, grants, audit)
    }

    private fun call(throttle: AuthThrottle, stepUp: Boolean) {
        if (stepUp) {
            AdminStepUpService(users, encoder, grants, throttle, KiraAdminStudioProperties(), clock).issue(user.id, PASSWORD, IP)
        } else {
            val userService = UserService(users, encoder, mock(PasswordPolicy::class.java))
            AuthService(users, userService, CredentialVerifier(encoder), tokens, throttle, KiraAuthProperties(), audit).login(EMAIL, PASSWORD, IP)
        }
    }

    private fun memory() = AuthThrottleService(
        KiraSecurityProperties(throttle = KiraSecurityProperties.Throttle(loginFailureThreshold = 1, loginAttemptTtl = Duration.ofSeconds(1))),
        clock,
    )

    private fun attemptThrottle(finish: (Boolean) -> Unit) = object : AuthThrottle {
        override fun beginLoginAttempt(normalizedEmail: String, clientIp: String) = AuthLoginAttempt(finish)

        override fun checkRegistrationAllowed(clientIp: String) = Unit
    }

    private fun assertUnavailable(error: TooManyRequestsException) {
        assertEquals("AUTH_THROTTLE_UNAVAILABLE", error.code)
        assertEquals(429, error.status.value())
        assertEquals(5L, error.retryAfterSeconds)
    }

    private fun unavailable() = TooManyRequestsException("Authentication is temporarily unavailable.", "AUTH_THROTTLE_UNAVAILABLE", 5)

    private companion object {
        const val EMAIL = "reader@example.com"
        const val IP = "192.0.2.1"
        const val PASSWORD = "submitted-password"
    }
}
