package me.manga.kira.backend.security

import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.common.exception.TooManyRequestsException
import me.manga.kira.backend.common.exception.UnauthorizedException
import me.manga.kira.backend.config.KiraAdminStudioProperties
import me.manga.kira.backend.config.KiraAuthProperties
import me.manga.kira.backend.config.KiraSecurityProperties
import me.manga.kira.backend.sourceconfig.api.AdminStepUpController
import me.manga.kira.backend.sourceconfig.api.AdminStepUpRequest
import me.manga.kira.backend.support.MutableClock
import me.manga.kira.backend.user.api.AuthController
import me.manga.kira.backend.user.api.dto.LoginRequest
import me.manga.kira.backend.user.application.AuthService
import me.manga.kira.backend.user.application.CredentialVerifier
import me.manga.kira.backend.user.application.UserService
import me.manga.kira.backend.user.domain.Role
import me.manga.kira.backend.user.domain.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.crypto.password.PasswordEncoder
import java.time.Instant
import java.util.UUID

/** Security boundary tests, no DB/listener. Matched numeric vectors also exercise the real Admin BFF handlers. */
class TrustedIngressIdentityTest {
    private val properties = KiraSecurityProperties(trustForwardedHeaders = true, trustedProxies = listOf(HOST, ADMIN))
    private val resolver = ClientIpResolver(properties)

    @Test
    fun `numeric spellings agree with the BFF contract and normalize direct peers too`() {
        val vectors = mapOf(
            "192.0.2.1" to "192.0.2.1",
            "2001:0DB8:0:0:0001::0001" to "2001:db8:0:0:1:0:0:1",
            "2001:db8::a" to "2001:db8:0:0:0:0:0:a",
            "::" to "0:0:0:0:0:0:0:0",
            "::1" to "0:0:0:0:0:0:0:1",
            "::ffff:192.0.2.1" to "192.0.2.1",
            "::FFFF:c000:0201" to "192.0.2.1",
            "0:0:0:0:0:FFFF:C000:201" to "192.0.2.1",
            "::192.0.2.1" to "0:0:0:0:0:0:c000:201",
            "::ffff:0:192.0.2.1" to "0:0:0:0:ffff:0:c000:201",
            "2001:db8::192.0.2.1" to "2001:db8:0:0:0:0:c000:201",
            "ffff:ffff:ffff:ffff:ffff:ffff:255.255.255.255" to "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
        )
        val direct = ClientIpResolver(KiraSecurityProperties())
        vectors.forEach { (input, expected) ->
            assertEquals(expected, resolver.resolve(request(input)), input)
            assertEquals(expected, direct.resolve(request("203.0.113.99", input)), input)
        }
        assertEquals("192.0.2.1", resolver.resolve(request("192.0.2.1", "::ffff:$ADMIN")))
    }

    @Test
    fun `present malformed ambiguous or all-trusted XFF never falls through to caller Forwarded`() {
        val malformed = listOf(
            "", " ", HOST, "$ADMIN, $HOST", "203.0.113.1,", ",203.0.113.1", "bad,203.0.113.1",
            "203.0.113.1, bad", "a".repeat(1025), "203.0.113.01", "203.0.113.256", "example.invalid",
            "2001::db8::1", "2001:db8:1", "2001:db8:0:0:0:0:0:0:1", "::1%eth0", "::1%25eth0",
            "[2001:db8::1]:bad", "[2001:db8::1]suffix", "203.0.113.1:-1", "203.0.113.1:65536",
            "[203.0.113.1]:80", "\"203.0.113.1\"", "203.0.113.1\r\n", "203.0.113.\uFF11",
        )
        malformed.forEach { value ->
            val request = request(value).apply { addHeader("Forwarded", "for=198.51.100.66") }
            assertEquals(ADMIN, resolver.resolve(request), value)
        }
        val duplicate = request("192.0.2.1").apply {
            addHeader("X-Forwarded-For", "198.51.100.2")
            addHeader("Forwarded", "for=198.51.100.66")
        }
        assertEquals(ADMIN, resolver.resolve(duplicate))
    }

    @Test
    fun `absent XFF alone permits a fully valid Forwarded chain and documented port forms`() {
        val generic = ClientIpResolver(properties.copy(trustedProxies = listOf("172.31.240.0/24", "2001:db8:feed::/48")))
        assertEquals("192.0.2.1", generic.resolve(request("192.0.2.1:443, $HOST:8080")))
        assertEquals("2001:db8:0:0:0:0:0:1", generic.resolve(request("[2001:db8::1]:443, [2001:db8:feed::2]:8080")))
        val forwarded = request().apply { addHeader("Forwarded", "for=192.0.2.1:443;proto=https, for=\"[$HOST]\"") }
        assertEquals(ADMIN, generic.resolve(forwarded)) // Brackets are IPv6 only; reject the entire malformed chain.
        forwarded.removeHeader("Forwarded")
        forwarded.addHeader("Forwarded", "for=\"[2001:db8::1]:443\";proto=https, For=$HOST:8080")
        assertEquals("2001:db8:0:0:0:0:0:1", generic.resolve(forwarded))
        listOf(
            "for=192.0.2.1;for=198.51.100.1",
            "for=192.0.2.1, proto=https",
            "for=unknown",
            "for=192.0.2.1;broken",
            "ext=\"ignored;for=198.51.100.66;proto=https\"",
            "for=198.51.100.66;proto=\"unterminated",
        ).forEach { value ->
            forwarded.removeHeader("Forwarded")
            forwarded.addHeader("Forwarded", value)
            assertEquals(ADMIN, generic.resolve(forwarded), value)
        }
        forwarded.removeHeader("Forwarded")
        forwarded.addHeader("Forwarded", "for=192.0.2.1")
        forwarded.addHeader("Forwarded", "for=198.51.100.1")
        assertEquals(ADMIN, generic.resolve(forwarded))
    }

    @Test
    fun `trust entries are numeric and CIDR matching remains family and prefix bounded`() {
        listOf("", "backend", "1.2.3", "192.0.2.1/33", "2001:db8::/129", "1.2.3.4/-1", "1.2.3.4//24", "::1%lo").forEach { entry ->
            assertThrows(IllegalArgumentException::class.java) { ClientIpResolver(properties.copy(trustedProxies = listOf(entry))) }
        }
        val generic = ClientIpResolver(properties.copy(trustedProxies = listOf("172.31.240.0/24", "2001:db8:feed::/48")))
        assertEquals("192.0.2.1", generic.resolve(request("192.0.2.1", "2001:db8:feed::abcd")))
        assertEquals("2001:db8:feee:0:0:0:0:abcd", generic.resolve(request("192.0.2.1", "2001:db8:feee::abcd")))
        assertEquals("172.31.241.3", generic.resolve(request("192.0.2.1", "172.31.241.3")))
    }

    @Test
    fun `real login and step-up controllers share the resolved aggregate bucket without blocking a distinct client`() {
        val endpoints = PasswordEndpoints(properties.copy(throttle = KiraSecurityProperties.Throttle(loginIpFailureThreshold = 2)))
        assertThrows(UnauthorizedException::class.java) { endpoints.login("first@example.invalid", request("192.0.2.1")) }
        assertThrows(UnauthorizedException::class.java) { endpoints.stepUp(request("::ffff:c000:201")) }
        assertThrows(TooManyRequestsException::class.java) { endpoints.login("other@example.invalid", request("192.0.2.1")) }
        assertThrows(TooManyRequestsException::class.java) { endpoints.stepUp(request("192.0.2.1")) }
        assertThrows(UnauthorizedException::class.java) { endpoints.login("other@example.invalid", request("2001:db8::2")) }
        assertThrows(UnauthorizedException::class.java) { endpoints.stepUp(request("2001:db8::3")) }
    }

    @Test
    fun `missing metadata remains a degraded shared peer bucket not client isolation`() {
        val endpoints = PasswordEndpoints(properties.copy(throttle = KiraSecurityProperties.Throttle(loginIpFailureThreshold = 2)))
        assertEquals(ADMIN, resolver.resolve(request()))
        assertThrows(UnauthorizedException::class.java) { endpoints.login("one@example.invalid", request()) }
        assertThrows(UnauthorizedException::class.java) { endpoints.login("two@example.invalid", request()) }
        assertThrows(TooManyRequestsException::class.java) { endpoints.login("three@example.invalid", request()) }
    }

    private fun request(xff: String? = null, peer: String = ADMIN): MockHttpServletRequest = MockHttpServletRequest().apply {
        remoteAddr = peer
        if (xff != null) addHeader("X-Forwarded-For", xff)
    }

    private class PasswordEndpoints(properties: KiraSecurityProperties) {
        private val clock = MutableClock()
        private val throttle = AuthThrottleService(properties, clock)
        private val users = mock(UserRepository::class.java)
        private val userService = mock(UserService::class.java)
        private val resolver = ClientIpResolver(properties)
        private val auth = AuthController(
            AuthService(
                users,
                userService,
                mock(CredentialVerifier::class.java),
                mock(JwtService::class.java),
                throttle,
                KiraAuthProperties(),
                mock(AuditService::class.java),
            ),
            resolver,
        )
        private val step = AdminStepUpController(
            AdminStepUpService(
                users,
                mock(PasswordEncoder::class.java),
                mock(AdminStepUpGrantRepository::class.java),
                throttle,
                KiraAdminStudioProperties(),
                clock,
            ),
            resolver,
        )

        fun login(email: String, request: MockHttpServletRequest) {
            `when`(userService.normalizeEmail(email)).thenReturn(email)
            auth.login(LoginRequest(email, "Synthetic-wrong-password"), request)
        }

        fun stepUp(request: MockHttpServletRequest) {
            val admin = AuthenticatedUser(UUID.fromString("11111111-1111-4111-8111-111111111111"), "admin@example.invalid", Role.ADMIN, Instant.EPOCH)
            step.issue(AdminStepUpRequest("Synthetic-wrong-password"), admin, request)
        }
    }

    private companion object {
        const val HOST = "172.31.240.1" // Synthetic peers only; no deployed addresses are inferred.
        const val ADMIN = "172.31.240.3"
    }
}
