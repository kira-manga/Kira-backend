package me.manga.kira.backend.security

import me.manga.kira.backend.common.exception.TooManyRequestsException
import me.manga.kira.backend.config.KiraSecurityProperties
import me.manga.kira.backend.support.MutableClock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.time.Duration

class AuthThrottleServiceTest {
    private val clock = MutableClock()

    @Test
    fun `aggregate IP bucket blocks failures spread across email identifiers`() {
        val service = service(KiraSecurityProperties.Throttle(loginIpFailureThreshold = 3))
        listOf("first", "second", "third").forEach { service.fail("$it@example.com") }

        assertThrows<TooManyRequestsException> { service.beginLoginAttempt("fourth@example.com", IP) }
        assertDoesNotThrow { service.beginLoginAttempt("fourth@example.com", "203.0.113.10").complete(true) }
    }

    @Test
    fun `success preserves other live reservations and a failure completed after success`() {
        val service = service(KiraSecurityProperties.Throttle(loginFailureThreshold = 2, loginIpFailureThreshold = 100))
        val first = service.beginLoginAttempt(EMAIL, IP)
        val second = service.beginLoginAttempt(EMAIL, IP)
        first.complete(true)
        service.beginLoginAttempt(EMAIL, IP).use { third ->
            assertThrows<TooManyRequestsException> { service.beginLoginAttempt(EMAIL, IP) }
            second.complete(false)
            assertThrows<TooManyRequestsException> { service.beginLoginAttempt(EMAIL, IP) } // One failure + third's live slot.
            third.complete(true)
        }
        assertDoesNotThrow { service.beginLoginAttempt(EMAIL, IP).complete(true) }
    }

    @Test
    fun `failure completed before success is reset only for identity not IP`() {
        val service = service(KiraSecurityProperties.Throttle(loginFailureThreshold = 2, loginIpFailureThreshold = 3))
        val failure = service.beginLoginAttempt(EMAIL, IP)
        val success = service.beginLoginAttempt(EMAIL, IP)
        failure.complete(false)
        success.complete(true)
        val first = service.beginLoginAttempt(EMAIL, IP)
        service.beginLoginAttempt(EMAIL, IP).use { second ->
            // Two slots in identity prove its history reset. The IP still has one failure + two slots.
            assertThrows<TooManyRequestsException> { service.beginLoginAttempt("other@example.com", IP) }
            first.complete(true)
            second.complete(true)
        }
    }

    @Test
    fun `IP failure history ages since failure while success and live reservations continue`() {
        val service = service(
            KiraSecurityProperties.Throttle(loginIpFailureThreshold = 2, loginFailureWindow = Duration.ofSeconds(10)),
        )
        service.fail()
        clock.advance(Duration.ofSeconds(6))
        service.beginLoginAttempt("success@example.com", IP).complete(true)
        service.beginLoginAttempt("held@example.com", IP).use { held ->
            assertThrows<TooManyRequestsException> { service.beginLoginAttempt("before-expiry@example.com", IP) }
            clock.advance(Duration.ofSeconds(4))
            service.beginLoginAttempt("after-expiry@example.com", IP).use { fresh ->
                assertThrows<TooManyRequestsException> { service.beginLoginAttempt("third@example.com", IP) }
                held.complete(true)
                fresh.complete(true)
            }
        }
    }

    @Test
    fun `block breaches reset counts double to the cap and idle failures reset escalation`() {
        val service = service(
            KiraSecurityProperties.Throttle(
                loginFailureThreshold = 2, loginIpFailureThreshold = 100,
                loginInitialBlock = Duration.ofSeconds(1), loginMaxBlock = Duration.ofSeconds(4),
                loginFailureWindow = Duration.ofSeconds(20),
            ),
        )
        listOf(1L, 2L, 4L, 4L).forEach { expectedBlock ->
            service.fail()
            service.fail()
            val blocked = assertThrows<TooManyRequestsException> { service.beginLoginAttempt(EMAIL, IP) }
            assertEquals(expectedBlock, blocked.retryAfterSeconds)
            clock.advance(Duration.ofSeconds(expectedBlock))
        }
        clock.advance(Duration.ofSeconds(20))
        service.fail()
        service.fail()
        assertEquals(1L, assertThrows<TooManyRequestsException> { service.beginLoginAttempt(EMAIL, IP) }.retryAfterSeconds)
    }

    @Test
    fun `lease equality expires attempts and late completion cannot change a successor`() {
        val service = service(KiraSecurityProperties.Throttle(loginFailureThreshold = 1, loginAttemptTtl = Duration.ofSeconds(1)))
        val staleSuccess = service.beginLoginAttempt(EMAIL, IP)
        clock.advance(Duration.ofSeconds(1))
        val staleFailure = service.beginLoginAttempt(EMAIL, IP)
        clock.advance(Duration.ofSeconds(1))
        val successor = service.beginLoginAttempt(EMAIL, IP)
        assertThrows<TooManyRequestsException> { staleSuccess.complete(true) }
        staleFailure.complete(false) // Harmless; no new failure and no successor token consumed.
        assertThrows<TooManyRequestsException> { service.beginLoginAttempt(EMAIL, IP) }
        successor.complete(false)
        staleSuccess.close()
        staleFailure.close()
        assertThrows<TooManyRequestsException> { staleFailure.complete(true) }
        assertThrows<TooManyRequestsException> { service.beginLoginAttempt(EMAIL, IP) }
    }

    @Test
    fun `completion and close are once-only and unknown success cannot recreate state`() {
        val service = service(KiraSecurityProperties.Throttle(loginFailureThreshold = 2))
        val failed = service.beginLoginAttempt(EMAIL, IP)
        failed.close()
        failed.close()
        assertThrows<TooManyRequestsException> { failed.complete(true) }
        val succeeded = service.beginLoginAttempt(EMAIL, IP)
        succeeded.complete(true)
        assertThrows<TooManyRequestsException> { succeeded.complete(true) }
        val unknown = service.beginLoginAttempt(EMAIL, IP)
        service.clearAll()
        assertThrows<TooManyRequestsException> { unknown.complete(true) }
        assertEquals(0, service.size())
    }

    @Test
    fun `capacity preflight is all-or-neither and protects live attempts blocks and registration`() {
        val service = service(
            KiraSecurityProperties.Throttle(
                maxEntries = 3, loginFailureThreshold = 1, loginIpFailureThreshold = 1,
                loginFailureWindow = Duration.ofMillis(100),
                registrationMaxPerWindow = 2, registrationWindow = Duration.ofSeconds(1),
            ),
        )
        service.checkRegistrationAllowed("192.0.2.2") // Eligible, but insufficient to fit another two-bucket login.
        val held = service.beginLoginAttempt(EMAIL, IP)
        assertThrows<TooManyRequestsException> { service.beginLoginAttempt("other@example.com", "192.0.2.3") }
        service.checkRegistrationAllowed("192.0.2.2")
        assertThrows<TooManyRequestsException> { service.checkRegistrationAllowed("192.0.2.2") } // Not evicted by failed preflight.
        assertThrows<TooManyRequestsException> { service.beginLoginAttempt("other@example.com", IP) }
        held.complete(false) // Both buckets become active blocks.
        assertThrows<TooManyRequestsException> { service.checkRegistrationAllowed("192.0.2.4") }
        clock.advance(Duration.ofSeconds(1))
        service.checkRegistrationAllowed("192.0.2.4") // Reclaims expired registration only, not the blocks.
        assertEquals(3, service.size())
        assertThrows<TooManyRequestsException> { service.beginLoginAttempt(EMAIL, IP) }
    }

    @Test
    fun `direct construction enforces shared capacity thresholds and a millisecond bounded lease`() {
        assertThrows<IllegalArgumentException> { KiraSecurityProperties.Throttle(maxEntries = 1) }
        assertThrows<IllegalArgumentException> { KiraSecurityProperties.Throttle(loginFailureThreshold = 0) }
        assertThrows<IllegalArgumentException> { KiraSecurityProperties.Throttle(loginIpFailureThreshold = 0) }
        assertThrows<IllegalArgumentException> { KiraSecurityProperties.Throttle(registrationMaxPerWindow = 0) }
        listOf(Duration.ZERO, Duration.ofMillis(-1), Duration.ofNanos(1), Duration.ofSeconds(1).plusNanos(1), Duration.ofMinutes(5).plusMillis(1))
            .forEach { ttl -> assertThrows<IllegalArgumentException> { KiraSecurityProperties.Throttle(loginAttemptTtl = ttl) } }
        assertEquals(Duration.ofSeconds(30), KiraSecurityProperties.Throttle().loginAttemptTtl)
        assertDoesNotThrow { KiraSecurityProperties.Throttle(maxEntries = 2, loginAttemptTtl = Duration.ofMillis(1)) }
        assertDoesNotThrow { KiraSecurityProperties.Throttle(loginAttemptTtl = Duration.ofMinutes(5)) }
    }

    private fun service(config: KiraSecurityProperties.Throttle) = AuthThrottleService(KiraSecurityProperties(throttle = config), clock)

    private fun AuthThrottle.fail(email: String = EMAIL) = beginLoginAttempt(email, IP).complete(false)

    private companion object {
        const val EMAIL = "reader@example.com"
        const val IP = "203.0.113.9"
    }
}
