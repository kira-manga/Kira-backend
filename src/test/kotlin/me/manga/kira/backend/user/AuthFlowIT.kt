package me.manga.kira.backend.user

import com.fasterxml.jackson.databind.ObjectMapper
import me.manga.kira.backend.support.AbstractIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.junit.jupiter.api.Assertions.assertEquals
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

/**
 * Test 9 (PLAN §11) — `AuthFlowIT`: register → login success returns a decodable token with USER
 * role; login with wrong password → 401; duplicate register → 409.
 */
class AuthFlowIT
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val objectMapper: ObjectMapper,
        private val jwtDecoder: JwtDecoder,
    ) : AbstractIntegrationTest() {

        private val email = "flow@example.com"
        private val password = "correct horse battery staple"

        private fun body(
            email: String,
            password: String,
        ): String = objectMapper.writeValueAsString(mapOf("email" to email, "password" to password))

        @Test
        fun `register then login returns a decodable USER token`() {
            mockMvc
                .post("/api/v1/auth/register") {
                    contentType = MediaType.APPLICATION_JSON
                    content = body(email, password)
                }.andExpect {
                    status { isCreated() }
                    jsonPath("$.email") { value(email) }
                    jsonPath("$.role") { value("USER") }
                    jsonPath("$.id") { exists() }
                }

            val response =
                mockMvc
                    .post("/api/v1/auth/login") {
                        contentType = MediaType.APPLICATION_JSON
                        content = body(email, password)
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.tokenType") { value("Bearer") }
                        jsonPath("$.role") { value("USER") }
                        jsonPath("$.expiresInSeconds") { value(3600) }
                    }.andReturn()

            val token = objectMapper.readTree(response.response.contentAsString).get("accessToken").asText()
            val decoded = jwtDecoder.decode(token)
            assertEquals("USER", decoded.getClaimAsString("role"))
            assertEquals(email, decoded.getClaimAsString("email"))
        }

        @Test
        fun `login with wrong password is 401`() {
            mockMvc.post("/api/v1/auth/register") {
                contentType = MediaType.APPLICATION_JSON
                content = body(email, password)
            }.andExpect { status { isCreated() } }

            mockMvc
                .post("/api/v1/auth/login") {
                    contentType = MediaType.APPLICATION_JSON
                    content = body(email, "totally wrong password!!")
                }.andExpect { status { isUnauthorized() } }
        }

        @Test
        fun `duplicate registration is 409`() {
            mockMvc.post("/api/v1/auth/register") {
                contentType = MediaType.APPLICATION_JSON
                content = body(email, password)
            }.andExpect { status { isCreated() } }

            mockMvc
                .post("/api/v1/auth/register") {
                    contentType = MediaType.APPLICATION_JSON
                    content = body(email.uppercase(), password) // case-insensitive duplicate
                }.andExpect {
                    status { isConflict() }
                    jsonPath("$.errors[0].code") { value("EMAIL_ALREADY_EXISTS") }
                }
        }
    }
