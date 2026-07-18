package me.manga.kira.backend.user

import com.fasterxml.jackson.databind.ObjectMapper
import me.manga.kira.backend.support.AbstractIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post

/**
 * Test 38 (PLAN §11) — `AuthenticationRateLimitIT`: repeated failed logins for one account/IP → 429
 * with the generic body; success resets the counter; a different account is unaffected (no
 * cross-account lockout). Default threshold is 5 consecutive failures (PLAN §6).
 */
class AuthenticationRateLimitIT
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val objectMapper: ObjectMapper,
    ) : AbstractIntegrationTest() {

        private val password = "correct horse battery staple"

        private fun register(email: String) {
            mockMvc.post("/api/v1/auth/register") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(mapOf("email" to email, "password" to password))
            }.andExpect { status { isCreated() } }
        }

        private fun login(
            email: String,
            pw: String,
        ): ResultActionsDsl =
            mockMvc.post("/api/v1/auth/login") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(mapOf("email" to email, "password" to pw))
            }

        @Test
        fun `five failures then throttled with 429`() {
            val email = "throttle-me@example.com"
            register(email)

            repeat(5) { login(email, "wrong password value").andExpect { status { isUnauthorized() } } }
            login(email, "wrong password value").andExpect { status { isEqualTo(429) } }
            // Even a correct password is blocked while throttled (check runs before verification).
            login(email, password).andExpect { status { isEqualTo(429) } }
        }

        @Test
        fun `a different account is unaffected by another account's throttle`() {
            val victim = "victim@example.com"
            val other = "other@example.com"
            register(victim)
            register(other)

            repeat(5) { login(victim, "wrong password value").andExpect { status { isUnauthorized() } } }
            login(victim, "wrong password value").andExpect { status { isEqualTo(429) } }

            // Same IP (127.0.0.1), different account → its own bucket → NOT throttled.
            login(other, "wrong password value").andExpect { status { isUnauthorized() } }
            login(other, password).andExpect { status { isOk() } }
        }

        @Test
        fun `a success resets the failure counter`() {
            val email = "reset-me@example.com"
            register(email)

            repeat(4) { login(email, "wrong password value").andExpect { status { isUnauthorized() } } }
            login(email, password).andExpect { status { isOk() } } // resets the counter
            // Four more failures stay below the threshold → still 401, never 429.
            repeat(4) { login(email, "wrong password value").andExpect { status { isUnauthorized() } } }
        }

        @Test
        fun `one IP spraying many account identifiers is throttled`() {
            repeat(25) { attempt ->
                login("missing-$attempt@example.com", "wrong password value")
                    .andExpect { status { isUnauthorized() } }
            }

            login("missing-next@example.com", "wrong password value")
                .andExpect { status { isEqualTo(429) } }
        }
    }
