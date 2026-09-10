package me.manga.kira.backend.security

import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.common.exception.TooManyRequestsException
import me.manga.kira.backend.common.exception.UnauthorizedException
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.spy
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.security.crypto.password.PasswordEncoder
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AuthAdmissionConcurrencyTest {
    @Test
    fun `same identity wave is bounded before any real login verifier is released`() = wave(AuthWavePath.LOGIN)

    @Test
    fun `distinct unknown email wave is bounded by IP before any decoy verifier is released`() = wave(AuthWavePath.SPRAY)

    @Test
    fun `admin step-up wave is bounded before any password verifier is released`() = wave(AuthWavePath.STEP_UP)

    private fun wave(path: AuthWavePath) {
        val config = KiraSecurityProperties.Throttle(
            loginFailureThreshold = if (path == AuthWavePath.SPRAY) 5 else 2,
            loginIpFailureThreshold = if (path == AuthWavePath.SPRAY) 2 else 100,
        )
        assertAuthAdmissionWave(listOf(AuthThrottleService(KiraSecurityProperties(throttle = config), MutableClock())), path)
    }
}

internal enum class AuthWavePath {
    LOGIN,
    SPRAY,
    STEP_UP,
}

/** One small fixed cohort, reused verbatim with memory and two real Redis adapters. Not a load harness. */
internal fun assertAuthAdmissionWave(throttles: List<AuthThrottle>, path: AuthWavePath) {
    val workers = 6
    val limit = 2
    val ready = CountDownLatch(workers)
    val start = CountDownLatch(1)
    val classified = CountDownLatch(workers)
    val release = CountDownLatch(1)
    val hashes = ConcurrentLinkedQueue<String>()
    val rejections = ConcurrentLinkedQueue<String>()
    val clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
    val user = User(UUID.randomUUID(), "reader@example.com", "eligible-hash", Role.ADMIN, true, clock.instant(), clock.instant())
    val users = mock(UserRepository::class.java)
    `when`(users.findByEmail(user.email)).thenReturn(user)
    `when`(users.findById(user.id)).thenReturn(user)
    val encoder = object : PasswordEncoder {
        override fun encode(rawPassword: CharSequence): String = "decoy-hash"

        override fun matches(rawPassword: CharSequence, encodedPassword: String): Boolean {
            hashes += encodedPassword
            classified.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "verifier was not released" }
            return encodedPassword == user.passwordHash
        }
    }
    // Synthetic test-only key; use the real JWT service so Kotlin default arguments and issuance stay real.
    val jwtProperties = KiraSecurityProperties(jwtSecret = Base64.getEncoder().encodeToString(ByteArray(32) { 42 }))
    val tokens = spy(JwtService(JwtKeyProvider(jwtProperties), jwtProperties, clock))
    val grants = mock(AdminStepUpGrantRepository::class.java)
    val audit = mock(AuditService::class.java)
    val userService = UserService(users, encoder, mock(PasswordPolicy::class.java))
    val logins = throttles.map { AuthService(users, userService, CredentialVerifier(encoder), tokens, it, KiraAuthProperties(), audit) }
    val steps = throttles.map { AdminStepUpService(users, encoder, grants, it, KiraAdminStudioProperties(), clock) }
    val pool = Executors.newFixedThreadPool(workers)
    try {
        val futures = (0 until workers).map { worker ->
            pool.submit<Int> {
                ready.countDown()
                check(start.await(10, TimeUnit.SECONDS))
                try {
                    if (path == AuthWavePath.STEP_UP) {
                        steps[worker % steps.size].issue(user.id, "submitted-password", "192.0.2.1")
                    } else {
                        val email = if (path == AuthWavePath.SPRAY) "absent-$worker@example.com" else "  READER@example.com  "
                        logins[worker % logins.size].login(email, "submitted-password", "192.0.2.1")
                    }
                    200
                } catch (ex: TooManyRequestsException) {
                    rejections += ex.code
                    classified.countDown()
                    429
                } catch (ex: UnauthorizedException) {
                    401
                }
            }
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS))
        start.countDown()
        // Every worker must enter OR be rejected while *all* entrants are still held. No recycled slots.
        assertTrue(classified.await(10, TimeUnit.SECONDS), "every worker classified before releasing verifiers")
        assertEquals(limit, hashes.size)
        assertEquals(workers - limit, rejections.size)
        assertTrue(rejections.all { it == "TOO_MANY_REQUESTS" })
        assertEquals(setOf(if (path == AuthWavePath.SPRAY) "decoy-hash" else "eligible-hash"), hashes.toSet())
        verifyNoInteractions(tokens, grants, audit)
        release.countDown()
        val results = futures.map { it.get(10, TimeUnit.SECONDS) }
        assertEquals(workers - limit, results.count { it == 429 })
        assertEquals(limit, results.count { it == if (path == AuthWavePath.SPRAY) 401 else 200 })
        assertEquals(if (path == AuthWavePath.LOGIN) limit else 0, mockingDetails(tokens).invocations.count { it.method.name == "issue" })
        assertEquals(if (path == AuthWavePath.STEP_UP) limit else 0, mockingDetails(grants).invocations.count { it.method.name == "create" })
    } finally {
        start.countDown()
        release.countDown()
        pool.shutdownNow()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "all auth workers stopped")
    }
}
