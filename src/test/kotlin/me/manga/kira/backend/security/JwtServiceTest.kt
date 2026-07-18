package me.manga.kira.backend.security

import me.manga.kira.backend.config.KiraSecurityProperties
import me.manga.kira.backend.support.JwtTestSupport
import me.manga.kira.backend.user.domain.Role
import me.manga.kira.backend.user.domain.User
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwtClaimNames
import org.springframework.security.oauth2.jwt.JwtClaimValidator
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.security.oauth2.jwt.JwtIssuerValidator
import org.springframework.security.oauth2.jwt.JwtTimestampValidator
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Test 6 (PLAN §11) — `JwtServiceTest`: issue→decode round-trip; expired token rejected; tampered
 * signature rejected; role claim mapped. A pure unit test — no Spring context. The decoder is built
 * exactly as [SecurityConfig] builds it (HS256 + timestamp/issuer/audience validators).
 */
class JwtServiceTest {

    private val properties = KiraSecurityProperties(jwtSecret = JwtTestSupport.TEST_JWT_SECRET_BASE64)
    private val keyProvider = JwtKeyProvider(properties)
    private val jwtService = JwtService(keyProvider, properties)
    private val decoder = buildDecoder()

    private fun user(role: Role = Role.USER): User = User(
        id = UUID.randomUUID(),
        email = "person@example.com",
        passwordHash = "{bcrypt}irrelevant",
        role = role,
        enabled = true,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    private fun buildDecoder(): NimbusJwtDecoder {
        val d = NimbusJwtDecoder.withSecretKey(keyProvider.secretKey).macAlgorithm(MacAlgorithm.HS256).build()
        d.setJwtValidator(
            DelegatingOAuth2TokenValidator(
                JwtTimestampValidator(properties.clockSkew),
                JwtIssuerValidator(properties.issuer),
                JwtClaimValidator<List<String>?>(JwtClaimNames.AUD) { it?.contains(properties.audience) == true },
            ),
        )
        return d
    }

    @Test
    fun `issue then decode round-trips subject, email, issuer, audience`() {
        val user = user()
        val token = jwtService.issue(user)

        val decoded = decoder.decode(token.value)

        assertEquals(user.id.toString(), decoded.subject)
        assertEquals(user.email, decoded.getClaimAsString("email"))
        // Read `iss` as a raw string claim — the issuer is "kira-backend" (not a URL), so the
        // URL-typed Jwt.getIssuer() accessor must not be used.
        assertEquals(properties.issuer, decoded.getClaimAsString(JwtClaimNames.ISS))
        assertEquals(listOf(properties.audience), decoded.audience)
        assertEquals(properties.accessTokenTtl.seconds, token.expiresInSeconds)
    }

    @Test
    fun `role claim reflects the user role`() {
        val adminToken = jwtService.issue(user(Role.ADMIN))
        assertEquals("ADMIN", decoder.decode(adminToken.value).getClaimAsString("role"))

        val userToken = jwtService.issue(user(Role.USER))
        assertEquals("USER", decoder.decode(userToken.value).getClaimAsString("role"))
    }

    @Test
    fun `expired token is rejected`() {
        // Issued two hours ago with a 60m TTL → exp is an hour in the past → outside the 60s skew.
        val token = jwtService.issue(user(), issuedAt = Instant.now().minus(Duration.ofHours(2)))
        assertThrows(JwtException::class.java) { decoder.decode(token.value) }
    }

    @Test
    fun `tampered signature is rejected`() {
        val token = jwtService.issue(user())
        val tampered = JwtTestSupport.tamperSignature(token.value)
        assertThrows(JwtException::class.java) { decoder.decode(tampered) }
    }
}
