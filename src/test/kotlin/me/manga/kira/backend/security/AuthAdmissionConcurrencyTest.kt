package me.manga.kira.backend.security

import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.common.exception.TooManyRequestsException
import me.manga.kira.backend.common.exception.UnauthorizedException
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseFixture
import me.manga.kira.backend.common.infrastructure.persistence.ScopedStepUpFixture
import me.manga.kira.backend.common.infrastructure.persistence.SyntheticComplaintCounters
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.common.infrastructure.persistence.withOrdinarySourceGrantCleanup
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
        assertAuthAdmissionWave(listOf(AuthThrottleService(KiraSecurityProperties(throttle = config), MutableClock())), path, javaClass)
    }
}

internal enum class AuthWavePath {
    LOGIN,
    SPRAY,
    STEP_UP,
}

/** Same fixed cohort for memory and two real Redis adapters; step-up reuses the owned scoped-PG fixture under the actual test class. */
internal fun assertAuthAdmissionWave(throttles: List<AuthThrottle>, path: AuthWavePath, fixtureClass: Class<*>) {
    if (path != AuthWavePath.STEP_UP) {
        runAuthAdmissionWave(throttles, path)
        return
    }
    PgLifecycleDatabaseFixture(fixtureClass).use { database ->
        database.start()
        withOrdinarySourceGrantCleanup(database, maximumPoolSize = 2) { ordinary ->
            SyntheticComplaintCounters(ordinary.foreignTemplate(), ordinary.cutoff).use { counters ->
                runAuthAdmissionWave(throttles, path, ScopedStepUpFixture(ordinary, counters))
            }
        }
    }
}

private fun runAuthAdmissionWave(throttles: List<AuthThrottle>, path: AuthWavePath, stepUp: ScopedStepUpFixture? = null) {
    check((path == AuthWavePath.STEP_UP) == (stepUp != null))
    val workers = 6
    val limit = 2
    val ready = CountDownLatch(workers)
    val start = CountDownLatch(1)
    val classified = CountDownLatch(workers)
    val release = CountDownLatch(1)
    val hashes = ConcurrentLinkedQueue<String>()
    val rejections = ConcurrentLinkedQueue<String>()
    val verified = ConcurrentLinkedQueue<VerifiedScopedAdminStepUp>()
    // Actual committed/released snapshots, one per caller, before the isolated password-admission wave.
    val snapshots = stepUp?.let { fixture -> List(workers) { fixture.phases.readSourceSnapshot(fixture.ordinary.userId) } }.orEmpty()
    val clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
    val user = User(UUID.randomUUID(), "reader@example.com", "eligible-hash", Role.ADMIN, true, clock.instant(), clock.instant())
    val acceptedHash = snapshots.firstOrNull()?.let { requireNotNull(it.passwordHash) } ?: user.passwordHash
    val users = mock(UserRepository::class.java)
    `when`(users.findByEmail(user.email)).thenReturn(user)
    `when`(users.findById(user.id)).thenReturn(user)
    val encoder = object : PasswordEncoder {
        override fun encode(rawPassword: CharSequence): String = "decoy-hash"

        override fun matches(rawPassword: CharSequence, encodedPassword: String): Boolean {
            if (stepUp != null) requireConnectionFree()
            hashes += encodedPassword
            classified.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "verifier was not released" }
            return encodedPassword == acceptedHash
        }
    }
    // Synthetic test-only key; use the real JWT service so Kotlin default arguments and issuance stay real.
    val jwtProperties = KiraSecurityProperties(jwtSecret = Base64.getEncoder().encodeToString(ByteArray(32) { 42 }))
    val tokens = spy(JwtService(JwtKeyProvider(jwtProperties), jwtProperties, clock))
    val grants = mock(AdminStepUpGrantRepository::class.java)
    val audit = mock(AuditService::class.java)
    val userService = UserService(users, encoder, mock(PasswordPolicy::class.java))
    val logins = throttles.map { AuthService(users, userService, CredentialVerifier(encoder), tokens, it, KiraAuthProperties(), audit) }
    val pool = Executors.newFixedThreadPool(workers)
    try {
        val futures = (0 until workers).map { worker ->
            pool.submit<Int> {
                ready.countDown()
                check(start.await(10, TimeUnit.SECONDS))
                try {
                    if (path == AuthWavePath.STEP_UP) {
                        verified += VerifiedScopedAdminStepUp.verify(
                            snapshots[worker],
                            ScopedStepUpFixture.PASSWORD,
                            "192.0.2.1",
                            encoder,
                            throttles[worker % throttles.size],
                        )
                    } else {
                        val email = if (path == AuthWavePath.SPRAY) "absent-$worker@example.com" else "  READER@example.com  "
                        logins[worker % logins.size].login(email, "submitted-password", "192.0.2.1")
                    }
                    200
                } catch (ex: TooManyRequestsException) {
                    rejections += ex.code
                    classified.countDown()
                    429
                } catch (ignored: UnauthorizedException) {
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
        assertEquals(setOf(if (path == AuthWavePath.SPRAY) "decoy-hash" else acceptedHash), hashes.toSet())
        verifyNoInteractions(tokens, grants, audit)
        if (stepUp != null) {
            assertEquals(workers, stepUp.jdbc.snapshots.size)
            assertTrue(stepUp.jdbc.snapshots.all { it.lease.completion.quiescent() })
            assertEquals(0, stepUp.ordinary.admission.activeOwners())
            assertTrue(verified.isEmpty() && stepUp.cleanups.isEmpty() && stepUp.ordinary.grantIds().isEmpty())
            assertEquals(0, stepUp.jdbc.insertAttempts)
            requireConnectionFree()
        }
        release.countDown()
        val results = futures.map { it.get(10, TimeUnit.SECONDS) }
        assertEquals(workers - limit, results.count { it == 429 })
        assertEquals(limit, results.count { it == if (path == AuthWavePath.SPRAY) 401 else 200 })
        assertEquals(if (path == AuthWavePath.LOGIN) limit else 0, mockingDetails(tokens).invocations.count { it.method.name == "issue" })
        verifyNoInteractions(grants) // Step-up now writes only through the real scoped store below, never this old repository mock.
        if (stepUp != null) {
            assertEquals(limit, verified.size)
            // Serialize only the post-ACK DB phases; fail-fast DB admission is not the throttle wave under test.
            verified.forEach { stepUp.phases.issueSource(it) }
            assertEquals(limit, stepUp.cleanups.size)
            assertEquals(limit, stepUp.jdbc.insertAttempts)
            assertEquals(limit, stepUp.ordinary.grantIds().size)
            assertTrue(stepUp.jdbc.issuances.all { it.lease.completion.quiescent() })
            assertEquals(0, stepUp.ordinary.admission.activeOwners())
            requireConnectionFree()
        }
    } finally {
        start.countDown()
        release.countDown()
        pool.shutdownNow()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "all auth workers stopped")
    }
}
