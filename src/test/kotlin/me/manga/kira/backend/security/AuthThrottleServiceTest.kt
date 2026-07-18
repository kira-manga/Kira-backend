package me.manga.kira.backend.security

import me.manga.kira.backend.common.exception.TooManyRequestsException
import me.manga.kira.backend.config.KiraSecurityProperties
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class AuthThrottleServiceTest {
    @Test
    fun `aggregate IP bucket blocks failures spread across email identifiers`() {
        val properties =
            KiraSecurityProperties(
                throttle =
                    KiraSecurityProperties.Throttle(
                        loginFailureThreshold = 5,
                        loginIpFailureThreshold = 3,
                    ),
            )
        val service = AuthThrottleService(properties, Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC))

        service.recordLoginFailure("first@example.com", "203.0.113.9")
        service.recordLoginFailure("second@example.com", "203.0.113.9")
        service.recordLoginFailure("third@example.com", "203.0.113.9")

        assertThrows(TooManyRequestsException::class.java) {
            service.checkLoginAllowed("fourth@example.com", "203.0.113.9")
        }
        assertDoesNotThrow {
            service.checkLoginAllowed("fourth@example.com", "203.0.113.10")
        }
    }
}
