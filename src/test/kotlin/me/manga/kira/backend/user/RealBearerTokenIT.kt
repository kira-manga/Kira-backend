package me.manga.kira.backend.user

import com.fasterxml.jackson.databind.ObjectMapper
import me.manga.kira.backend.security.JwtService
import me.manga.kira.backend.support.AbstractIntegrationTest
import me.manga.kira.backend.support.JwtTestSupport
import me.manga.kira.backend.user.domain.Role
import me.manga.kira.backend.user.domain.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Test 36 (PLAN §11) — `RealBearerTokenIT`: obtain a token via a real `POST /auth/login` and call a
 * protected endpoint through the REAL `NimbusJwtDecoder` (not the mocked `jwt()` post-processor);
 * tampered audience / issuer / expiry / signature variants → 401 (PLAN §6). Password reset revokes
 * prior generations on the same real bearer path, including step-up before any proof can be issued.
 */
class RealBearerTokenIT
@Autowired
constructor(
    private val mockMvc: MockMvc,
    private val objectMapper: ObjectMapper,
    private val users: UserRepository,
    private val passwords: PasswordEncoder,
) : AbstractIntegrationTest() {

    private val email = "bearer@example.com"
    private val password = "correct horse battery staple"

    private fun registerAndLogin(): String {
        val body = objectMapper.writeValueAsString(mapOf("email" to email, "password" to password))
        mockMvc.post("/api/v1/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = body
        }.andExpect { status { isCreated() } }
        return login(email, password)
    }

    private fun login(email: String, password: String): String {
        val response =
            mockMvc
                .post("/api/v1/auth/login") {
                    contentType = MediaType.APPLICATION_JSON
                    content = objectMapper.writeValueAsString(mapOf("email" to email, "password" to password))
                }.andExpect { status { isOk() } }
                .andReturn()
        return objectMapper.readTree(response.response.contentAsString).get("accessToken").asText()
    }

    private fun operatorToken(): String {
        val operator = users.create("reset-operator@example.com", passwords.encode(password), Role.ADMIN)
        return login(operator.email, password)
    }

    private fun reset(operatorToken: String, userId: UUID, password: String) {
        mockMvc.post("/api/v1/admin/users/$userId/reset-password") {
            header("Authorization", "Bearer $operatorToken")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("newPassword" to password))
        }.andExpect { status { isOk() } }
    }

    private fun assertMe(token: String, expectedStatus: Int) {
        val response = mockMvc.get("/api/v1/auth/me") { header("Authorization", "Bearer $token") }.andReturn().response
        assertEquals(expectedStatus, response.status)
        if (expectedStatus == 401) {
            assertEquals(
                "Authentication is required or the token is invalid.",
                objectMapper.readTree(response.contentAsString).get("detail").asText(),
            )
        }
    }

    private fun assertLoginRejected(email: String, password: String) {
        mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("email" to email, "password" to password))
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.errors[0].code") { value("INVALID_CREDENTIALS") }
        }
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
        assertMe(JwtTestSupport.mint(subject = subject, email = email), 200)

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

    @Test
    fun `reset revokes USER read and write tokens and a repeated reset revokes the intermediate token`() {
        val operator = operatorToken()
        val original = registerAndLogin()
        val target = users.findByEmail(email)!!
        val created = mockMvc.post("/api/v1/completions") {
            header("Authorization", "Bearer $original")
            contentType = MediaType.APPLICATION_JSON
            content = "{\"prompt\":\"reset revocation control\"}"
        }.andExpect { status { isCreated() } }.andReturn().response
        val completionId = objectMapper.readTree(created.contentAsString).get("id").asText()
        mockMvc.get("/api/v1/completions/$completionId") { header("Authorization", "Bearer $original") }
            .andExpect { status { isOk() } }

        val newPassword = "new correct horse battery staple"
        reset(operator, target.id, newPassword)
        assertEquals(1L, users.findById(target.id)!!.credentialVersion)
        assertMe(original, 401)
        mockMvc.get("/api/v1/completions/$completionId") { header("Authorization", "Bearer $original") }
            .andExpect { status { isUnauthorized() } }
        mockMvc.post("/api/v1/completions") {
            header("Authorization", "Bearer $original")
            contentType = MediaType.APPLICATION_JSON
            content = "{\"prompt\":\"must not be stored\"}"
        }.andExpect { status { isUnauthorized() } }
        assertEquals(1L, jdbcTemplate.queryForObject("SELECT count(*) FROM completion_requests", Long::class.java))
        assertLoginRejected(email, password)
        val intermediate = login(email, newPassword)
        assertMe(intermediate, 200)
        mockMvc.get("/api/v1/completions/$completionId") { header("Authorization", "Bearer $intermediate") }
            .andExpect { status { isOk() } }

        reset(operator, target.id, newPassword) // Even resetting to the same password advances once.
        assertEquals(2L, users.findById(target.id)!!.credentialVersion)
        assertMe(original, 401)
        assertMe(intermediate, 401)
        assertMe(login(email, newPassword), 200)
        mockMvc.get("/api/v1/admin/users") { header("Authorization", "Bearer $operator") }
            .andExpect { status { isOk() } } // Other users' sessions are unaffected.
    }

    @Test
    fun `reset ADMIN bearer cannot access admin routes or obtain step-up even with the new password`() {
        val operator = operatorToken()
        val admin = users.create("reset-admin@example.com", passwords.encode(password), Role.ADMIN)
        val original = login(admin.email, password)
        mockMvc.get("/api/v1/admin/users") { header("Authorization", "Bearer $original") }
            .andExpect { status { isOk() } }
        val newPassword = "replacement admin password phrase"

        reset(operator, admin.id, newPassword)

        assertMe(original, 401)
        mockMvc.get("/api/v1/admin/users") { header("Authorization", "Bearer $original") }
            .andExpect { status { isUnauthorized() } }
        mockMvc.post("/api/v1/admin/step-up") {
            header("Authorization", "Bearer $original")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("password" to newPassword))
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.detail") { value("Authentication is required or the token is invalid.") }
        }
        assertEquals(0L, jdbcTemplate.queryForObject("SELECT count(*) FROM admin_step_up_grants", Long::class.java))
        assertLoginRejected(admin.email, password)
        val current = login(admin.email, newPassword)
        mockMvc.get("/api/v1/admin/users") { header("Authorization", "Bearer $current") }
            .andExpect { status { isOk() } }
        mockMvc.post("/api/v1/admin/step-up") {
            header("Authorization", "Bearer $current")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("password" to newPassword))
        }.andExpect { status { isOk() } }
        assertEquals(1L, jdbcTemplate.queryForObject("SELECT count(*) FROM admin_step_up_grants WHERE user_id = ?", Long::class.java, admin.id))
        assertMe(operator, 200)
    }

    @Test
    fun `legacy null wrong typed and noncanonical credential claims receive the same generic 401`() {
        registerAndLogin()
        val subject = users.findByEmail(email)!!.id
        val legacy = JwtTestSupport.mint(subject, includeCredentialVersion = false)
        val explicitNull = JwtTestSupport.mint(subject, credentialVersion = null)
        fun payload(token: String) = objectMapper.readTree(Base64.getUrlDecoder().decode(token.split(".")[1]))
        assertFalse(payload(legacy).has(JwtService.CLAIM_CREDENTIAL_VERSION))
        assertTrue(payload(explicitNull).get(JwtService.CLAIM_CREDENTIAL_VERSION).isNull)
        assertMe(JwtTestSupport.mint(subject, credentialVersion = "0"), 200)
        assertMe(legacy, 401)
        assertMe(explicitNull, 401)
        val malformed: List<Any> = listOf(
            0L, 0.0, -1L, true, false, listOf("0"), mapOf("version" to "0"),
            "", "00", "+0", "-0", "-1", " 0", "0 ", "0\n", "0.0", "0e0", "０", "٠",
            "9223372036854775808", "18446744073709551616", "1",
        )
        malformed.forEach { claim -> assertMe(JwtTestSupport.mint(subject, credentialVersion = claim), 401) }
        assertMe(JwtTestSupport.mint(subject, credentialVersion = "0"), 200)
    }

    @Test
    fun `nonzero and MAX versions require exact equality and exhausted reset is a generic conflict`() {
        val operator = operatorToken()
        registerAndLogin()
        val subject = users.findByEmail(email)!!.id
        listOf(7L, Long.MAX_VALUE).forEach { version ->
            jdbcTemplate.update("UPDATE users SET credential_version = ? WHERE id = ?", version, subject)
            assertMe(login(email, password), 200)
            assertMe(JwtTestSupport.mint(subject, credentialVersion = version.toString()), 200)
            assertMe(JwtTestSupport.mint(subject, credentialVersion = (version - 1).toString()), 401)
            assertMe(JwtTestSupport.mint(subject, credentialVersion = version), 401)
            assertMe(JwtTestSupport.mint(subject, credentialVersion = "0$version"), 401)
            val future = if (version == Long.MAX_VALUE) "9223372036854775808" else (version + 1).toString()
            assertMe(JwtTestSupport.mint(subject, credentialVersion = future), 401)
        }
        val before = users.findById(subject)!!
        val current = login(email, password)
        mockMvc.post("/api/v1/admin/users/$subject/reset-password") {
            header("Authorization", "Bearer $operator")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("newPassword" to "must not replace the password"))
        }.andExpect {
            status { isConflict() }
            jsonPath("$.errors[0].code") { value("CREDENTIAL_VERSION_EXHAUSTED") }
            jsonPath("$.detail") { value("Password reset is unavailable.") }
        }
        assertEquals(before, users.findById(subject))
        assertMe(current, 200)
        assertEquals(0L, jdbcTemplate.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'USER_PASSWORD_RESET'", Long::class.java))
    }
}
