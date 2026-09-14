package me.manga.kira.backend.security

import me.manga.kira.backend.common.exception.TooManyRequestsException
import me.manga.kira.backend.config.KiraSecurityProperties
import me.manga.kira.backend.support.MutableClock
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import java.time.Duration

/**
 * Test 50 (PLAN §11) — `ClientIpResolutionIT`: throttling cannot be spoofed or grow unbounded.
 * Drives [ClientIpResolver] and [AuthThrottleService] directly (with a [MutableClock] and tailored
 * [KiraSecurityProperties]) so every documented behavior — spoof-ignored default mode, trusted-proxy
 * resolution, malformed/oversized fallback, eviction, TTL expiry, reset-after-success — is
 * deterministic without wall-clock sleeps (PLAN §6 trusted client-IP resolution).
 */
class ClientIpResolutionIT {

    private fun request(remoteAddr: String, headers: Map<String, String> = emptyMap()): MockHttpServletRequest = MockHttpServletRequest().apply {
        this.remoteAddr = remoteAddr
        headers.forEach { (name, value) -> addHeader(name, value) }
    }

    // ---- client-IP resolution ----

    @Test
    fun `default mode ignores a spoofed X-Forwarded-For`() {
        val resolver = ClientIpResolver(KiraSecurityProperties(trustForwardedHeaders = false))
        val req = request("203.0.113.9", mapOf("X-Forwarded-For" to "10.0.0.1, 8.8.8.8"))

        // Neither escapes the sender's own bucket nor poisons the spoofed victim's.
        assertEquals("203.0.113.9", resolver.resolve(req))
    }

    @Test
    fun `trusted-proxy mode resolves the rightmost non-trusted hop`() {
        val resolver =
            ClientIpResolver(
                KiraSecurityProperties(trustForwardedHeaders = true, trustedProxies = listOf("203.0.113.0/24")),
            )
        // Peer 203.0.113.9 is trusted; XFF = "client, trusted-proxy" → real client is 9.9.9.9.
        val req = request("203.0.113.9", mapOf("X-Forwarded-For" to "9.9.9.9, 203.0.113.5"))

        assertEquals("9.9.9.9", resolver.resolve(req))
    }

    @Test
    fun `trusted-proxy mode ignores forwarding from an untrusted peer`() {
        val resolver =
            ClientIpResolver(
                KiraSecurityProperties(trustForwardedHeaders = true, trustedProxies = listOf("203.0.113.0/24")),
            )
        // Peer 198.51.100.7 is NOT in trusted-proxies → header ignored, remote address used.
        val req = request("198.51.100.7", mapOf("X-Forwarded-For" to "9.9.9.9"))

        assertEquals("198.51.100.7", resolver.resolve(req))
    }

    @Test
    fun `oversized forwarding header falls back safely to the remote address`() {
        val resolver =
            ClientIpResolver(
                KiraSecurityProperties(trustForwardedHeaders = true, trustedProxies = listOf("203.0.113.0/24")),
            )
        val huge = (1..400).joinToString(", ") { "9.9.9.9" } // > 1 KiB
        val req = request("203.0.113.9", mapOf("X-Forwarded-For" to huge))

        assertEquals("203.0.113.9", resolver.resolve(req))
    }

    // ---- bounded throttle store ----

    private fun throttleProps(
        maxEntries: Int = 100_000,
        threshold: Int = 3,
        window: Duration = Duration.ofMinutes(15),
        initialBlock: Duration = Duration.ofMinutes(1),
    ): KiraSecurityProperties = KiraSecurityProperties(
        throttle =
        KiraSecurityProperties.Throttle(
            maxEntries = maxEntries,
            loginFailureThreshold = threshold,
            loginInitialBlock = initialBlock,
            loginFailureWindow = window,
        ),
    )

    @Test
    fun `login throttle engages after the threshold and clears after the block window`() {
        val clock = MutableClock()
        val throttle = AuthThrottleService(throttleProps(threshold = 3, initialBlock = Duration.ofMinutes(1)), clock)

        repeat(3) { throttle.fail("victim@example.com", "203.0.113.9") }
        assertThrows(TooManyRequestsException::class.java) {
            throttle.succeed("victim@example.com", "203.0.113.9")
        }

        clock.advance(Duration.ofMinutes(2)) // past the 1-minute block
        assertDoesNotThrow { throttle.succeed("victim@example.com", "203.0.113.9") }
    }

    @Test
    fun `success resets the failure counter`() {
        val throttle = AuthThrottleService(throttleProps(threshold = 3), MutableClock())

        repeat(2) { throttle.fail("a@example.com", "1.1.1.1") }
        throttle.succeed("a@example.com", "1.1.1.1")
        // Two more failures must NOT trip the threshold (counter restarted at zero).
        repeat(2) { throttle.fail("a@example.com", "1.1.1.1") }

        assertDoesNotThrow { throttle.succeed("a@example.com", "1.1.1.1") }
    }

    @Test
    fun `stale window resets the counter (TTL expiry)`() {
        val clock = MutableClock()
        val throttle = AuthThrottleService(throttleProps(threshold = 3, window = Duration.ofMinutes(15)), clock)

        repeat(2) { throttle.fail("b@example.com", "2.2.2.2") }
        clock.advance(Duration.ofMinutes(16)) // window elapsed → counter is stale
        throttle.fail("b@example.com", "2.2.2.2") // resets, then counts as 1

        assertDoesNotThrow { throttle.succeed("b@example.com", "2.2.2.2") }
    }

    @Test
    fun `store enforces max-entries with deterministic eviction`() {
        val clock = MutableClock()
        val throttle = AuthThrottleService(throttleProps(maxEntries = 4, threshold = 2), clock)

        throttle.fail("k1@example.com", "9.0.0.1")
        clock.advance(Duration.ofSeconds(1))
        throttle.fail("k2@example.com", "9.0.0.2")
        clock.advance(Duration.ofSeconds(1))
        throttle.fail("k3@example.com", "9.0.0.3") // Evicts the oldest identity/IP pair, not k2's history.

        assertEquals(4, throttle.size())
        throttle.fail("k2@example.com", "9.0.0.2")
        assertThrows(TooManyRequestsException::class.java) { throttle.beginLoginAttempt("k2@example.com", "9.0.0.2") }
    }

    @Test
    fun `dead entries are reclaimed without evicting the replacement live pair`() {
        val clock = MutableClock()
        val throttle = AuthThrottleService(throttleProps(maxEntries = 2, threshold = 10, window = Duration.ofMinutes(15)), clock)

        throttle.fail("dead@example.com", "9.0.0.1")
        clock.advance(Duration.ofMinutes(16))
        throttle.beginLoginAttempt("fresh@example.com", "9.0.0.2").use { fresh ->
            assertEquals(2, throttle.size()) // Both expired dimensions made room for the new pair.
            assertThrows(TooManyRequestsException::class.java) { throttle.checkRegistrationAllowed("9.0.0.3") }
            assertThrows(TooManyRequestsException::class.java) { throttle.beginLoginAttempt("dead@example.com", "9.0.0.1") }
            fresh.complete(true) // Rejected capacity requests did not evict either live dimension.
        }
    }

    private fun AuthThrottle.fail(email: String, ip: String) = beginLoginAttempt(email, ip).complete(false)

    private fun AuthThrottle.succeed(email: String, ip: String) = beginLoginAttempt(email, ip).complete(true)
}
