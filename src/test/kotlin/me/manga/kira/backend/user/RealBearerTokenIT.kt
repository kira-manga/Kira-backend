package me.manga.kira.backend.user

import com.fasterxml.jackson.databind.ObjectMapper
import me.manga.kira.backend.support.AbstractIntegrationTest
import me.manga.kira.backend.support.JwtTestSupport
import me.manga.kira.backend.user.domain.UserRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Duration
import java.time.Instant

/**
 * Test 36 (PLAN §11) — `RealBearerTokenIT`: obtain a token via a real `POST /auth/login` and call a
 * protected endpoint through the REAL `NimbusJwtDecoder` (not the mocked `jwt()` post-processor);
 * tampered audience / issuer / expiry / signature variants → 401 (PLAN §6).
 */
class RealBearerTokenIT
@Autowired
constructor(private val mockMvc: MockMvc, private val objectMapper: ObjectMapper, private val users: UserRepository) :
    AbstractIntegrationTest() {

    private val email = "bearer@example.com"
    private val password = "correct horse battery staple"

    private fun registerAndLogin(): String {
        val body = objectMapper.writeValueAsString(mapOf("email" to email, "password" to password))
        mockMvc.post("/api/v1/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = body
        }.andExpect { status { isCreated() } }
        val response =
            mockMvc
                .post("/api/v1/auth/login") {
                    contentType = MediaType.APPLICATION_JSON
                    content = body
                }.andExpect { status { isOk() } }
                .andReturn()
        return objectMapper.readTree(response.response.contentAsString).get("accessToken").asText()
    }

    @Test
    fun `a real login token authenticates against the real decoder`() {
        val token = registerAndLogin()
        mockMvc
            .get("/api/v1/auth/me") { header("Authorization", "Bearer $token") }
            .andExpect {
                status { isOk() }
                jsonPath("$.email") { value(email) }
                jsonPath("$.role") { value("USER") }
            }
    }

    @Test
    fun `tampered audience, issuer, expiry, and signature are all 401`() {
        val realToken = registerAndLogin()
        val subject = users.findByEmail(email)!!.id

        val wrongAudience = JwtTestSupport.mint(subject = subject, email = email, audience = "someone-else")
        val wrongIssuer = JwtTestSupport.mint(subject = subject, email = email, issuer = "evil-issuer")
        val expired =
            JwtTestSupport.mint(
                subject = subject,
                email = email,
                issuedAt = Instant.now().minus(Duration.ofHours(2)),
                expiresAt = Instant.now().minus(Duration.ofHours(1)),
            )
        val tampered = JwtTestSupport.tamperSignature(realToken)

        listOf(wrongAudience, wrongIssuer, expired, tampered).forEach { badToken ->
            mockMvc
                .get("/api/v1/auth/me") { header("Authorization", "Bearer $badToken") }
                .andExpect { status { isUnauthorized() } }
        }
    }
}
