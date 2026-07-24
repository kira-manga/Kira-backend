package me.manga.kira.backend.sourceconfig.admin

import me.manga.kira.backend.common.exception.UnauthorizedException
import me.manga.kira.backend.security.AdminStepUpService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.post

class AdminStepUpIT : AbstractAdminSourceIT() {
    @Autowired
    private lateinit var stepUp: AdminStepUpService

    @Test
    fun `password step-up stores only a hash and proof is one-time`() {
        val response =
            mockMvc.post("/api/v1/admin/step-up") {
                header("Authorization", "Bearer $adminToken")
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(mapOf("password" to ADMIN_PASSWORD))
            }.andExpect {
                status { isOk() }
                jsonPath("$.scope") { value("source-admin-mutation") }
                jsonPath("$.token") { isNotEmpty() }
                jsonPath("$.expiresAt") { exists() }
            }.andReturn().response
        val token = objectMapper.readTree(response.contentAsString).get("token").asText()
        val storedHash =
            jdbcTemplate.queryForObject(
                "SELECT token_hash FROM admin_step_up_grants WHERE user_id = ?",
                String::class.java,
                admin.id,
            )
        assertEquals(64, storedHash?.length)
        assertNotEquals(token, storedHash)

        stepUp.requireSourceMutation(admin.id, token)
        val second =
            assertThrows(UnauthorizedException::class.java) {
                stepUp.requireSourceMutation(admin.id, token)
            }
        assertEquals("ADMIN_STEP_UP_REQUIRED", second.code)
    }

    @Test
    fun `wrong password and expired proof fail closed`() {
        mockMvc.post("/api/v1/admin/step-up") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("password" to "wrong-password"))
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.errors[0].code") { value("INVALID_STEP_UP_CREDENTIALS") }
        }
        assertEquals(0L, jdbcTemplate.queryForObject("SELECT count(*) FROM admin_step_up_grants", Long::class.java))

        val issued = stepUp.issue(admin.id, ADMIN_PASSWORD, "127.0.0.1")
        jdbcTemplate.update(
            "UPDATE admin_step_up_grants " +
                "SET created_at = now() - interval '2 seconds', expires_at = now() - interval '1 second'",
        )
        val expired =
            assertThrows(UnauthorizedException::class.java) {
                stepUp.requireSourceMutation(admin.id, issued.token)
            }
        assertEquals("ADMIN_STEP_UP_REQUIRED", expired.code)
    }
}
