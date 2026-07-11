package me.manga.kira.backend.user

import com.fasterxml.jackson.databind.ObjectMapper
import me.manga.kira.backend.security.JwtService
import me.manga.kira.backend.support.AbstractIntegrationTest
import me.manga.kira.backend.user.domain.Role
import me.manga.kira.backend.user.domain.User
import me.manga.kira.backend.user.domain.UserRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID

/**
 * Test 37 (PLAN §11) — `DisabledUserAuthIT`: disable is effective immediately, on EVERY protected
 * endpoint, and authorities come from the DB. Because the enabled-check + authority derivation live
 * in the authentication pipeline ([me.manga.kira.backend.security.DbUserJwtAuthenticationConverter]),
 * a disabled user's still-valid-signature token is rejected even on endpoints whose controllers
 * arrive later (completions) — the 401 happens before dispatch. Login is likewise refused, and a
 * DB-side role change takes effect against a token carrying the old role claim.
 */
class DisabledUserAuthIT
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val objectMapper: ObjectMapper,
        private val users: UserRepository,
        private val jwtService: JwtService,
    ) : AbstractIntegrationTest() {

        private val password = "correct horse battery staple"

        private fun registerAndLogin(email: String): String {
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
                    }.andReturn()
            return objectMapper.readTree(response.response.contentAsString).get("accessToken").asText()
        }

        private fun createUser(
            email: String,
            role: Role,
        ): User = users.create(email, "{bcrypt}\$2a\$10\$notarealhashjustfortokentests............", role)

        @Test
        fun `a disabled USER token is 401 on me and on completion read and write`() {
            val email = "disabled-user@example.com"
            val token = registerAndLogin(email)
            users.setEnabled(users.findByEmail(email)!!.id, false)

            val bearer = "Bearer $token"
            mockMvc.get("/api/v1/auth/me") { header("Authorization", bearer) }
                .andExpect { status { isUnauthorized() } }
            mockMvc
                .post("/api/v1/completions") {
                    header("Authorization", bearer)
                    contentType = MediaType.APPLICATION_JSON
                    content = "{\"prompt\":\"hi\"}"
                }.andExpect { status { isUnauthorized() } }
            mockMvc
                .get("/api/v1/completions/${UUID.randomUUID()}") { header("Authorization", bearer) }
                .andExpect { status { isUnauthorized() } }
        }

        @Test
        fun `a disabled ADMIN token is 401 on admin endpoints`() {
            val admin = createUser("disabled-admin@example.com", Role.ADMIN)
            val token = jwtService.issue(admin).value
            users.setEnabled(admin.id, false)

            mockMvc
                .get("/api/v1/admin/users") { header("Authorization", "Bearer $token") }
                .andExpect { status { isUnauthorized() } }
        }

        @Test
        fun `login is refused for a disabled account with the generic message`() {
            val email = "login-refused@example.com"
            registerAndLogin(email)
            users.setEnabled(users.findByEmail(email)!!.id, false)

            mockMvc
                .post("/api/v1/auth/login") {
                    contentType = MediaType.APPLICATION_JSON
                    content = objectMapper.writeValueAsString(mapOf("email" to email, "password" to password))
                }.andExpect {
                    status { isUnauthorized() }
                    jsonPath("$.errors[0].code") { value("INVALID_CREDENTIALS") }
                }
        }

        @Test
        fun `authorities follow the DB role, not the token claim`() {
            val email = "role-change@example.com"
            val token = registerAndLogin(email) // token carries role claim USER
            val id = users.findByEmail(email)!!.id

            // Promote in the DB: the SAME old token now grants ADMIN access.
            users.updateRole(id, Role.ADMIN)
            mockMvc
                .get("/api/v1/admin/users") { header("Authorization", "Bearer $token") }
                .andExpect { status { isOk() } }

            // Demote: the same token is now forbidden — authorities came from the DB all along.
            users.updateRole(id, Role.USER)
            mockMvc
                .get("/api/v1/admin/users") { header("Authorization", "Bearer $token") }
                .andExpect { status { isForbidden() } }
        }
    }
