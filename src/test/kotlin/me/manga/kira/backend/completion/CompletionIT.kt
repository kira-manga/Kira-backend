package me.manga.kira.backend.completion

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import me.manga.kira.backend.security.JwtService
import me.manga.kira.backend.support.AbstractIntegrationTest
import me.manga.kira.backend.user.domain.Role
import me.manga.kira.backend.user.domain.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID

/**
 * PLAN §11 test 18 — `CompletionIT`. Anonymous → 401; a USER posts a prompt → 201 with a row in BOTH
 * tables (provider `echo`, result `echo: …`); the owner can GET it; a DIFFERENT USER gets 404 (never
 * 403 — no id probing); an ADMIN can read it. Also covers the §4.5/§4.6 boundary: blank prompt → 400,
 * over-max prompt → 413, list pagination + newest-first, ADMIN `?userId=`, and bad pagination → 400.
 */
class CompletionIT
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
        mockMvc
            .post("/api/v1/auth/register") {
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

    private fun adminToken(email: String): String {
        val admin = users.create(email, "{bcrypt}\$2a\$10\$notarealhashjustforcompletionit.....", Role.ADMIN)
        return jwtService.issue(admin).value
    }

    private fun post(token: String, prompt: String, model: String? = null): JsonNode {
        val payload =
            if (model == null) mapOf("prompt" to prompt) else mapOf("prompt" to prompt, "model" to model)
        val result =
            mockMvc
                .post("/api/v1/completions") {
                    header("Authorization", "Bearer $token")
                    contentType = MediaType.APPLICATION_JSON
                    content = objectMapper.writeValueAsString(payload)
                }.andExpect { status { isCreated() } }
                .andReturn()
        return objectMapper.readTree(result.response.contentAsString)
    }

    @Test
    fun `anonymous is 401`() {
        mockMvc
            .post("/api/v1/completions") {
                contentType = MediaType.APPLICATION_JSON
                content = "{\"prompt\":\"hi\"}"
            }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `a USER posts a prompt and both tables get a row`() {
        val token = registerAndLogin("completion-happy@example.com")
        val json = post(token, "hello")

        assertEquals("SUCCEEDED", json.get("status").asText())
        assertEquals("echo", json.get("provider").asText())
        assertEquals("echo-1", json.get("model").asText()) // default model recorded (PLAN §4.6)
        assertEquals("echo: hello", json.get("result").asText())
        assertFalse(json.hasNonNull("errorCode"))
        assertFalse(json.hasNonNull("error"))

        val id = UUID.fromString(json.get("id").asText())
        assertEquals(
            1,
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM completion_requests " +
                    "WHERE id = ? AND status = 'SUCCEEDED' AND provider = 'echo' AND model = 'echo-1'",
                Int::class.java,
                id,
            ),
        )
        assertEquals(
            "echo: hello",
            jdbcTemplate.queryForObject(
                "SELECT result FROM completion_results WHERE request_id = ?",
                String::class.java,
                id,
            ),
        )
        assertEquals(
            1,
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM completion_results " +
                    "WHERE request_id = ? AND error IS NULL AND error_code IS NULL",
                Int::class.java,
                id,
            ),
        )
    }

    @Test
    fun `an explicit model is recorded on the request row`() {
        val token = registerAndLogin("completion-model@example.com")
        val json = post(token, "hi", model = "custom-model-9")

        assertEquals("custom-model-9", json.get("model").asText())
        val id = UUID.fromString(json.get("id").asText())
        assertEquals(
            "custom-model-9",
            jdbcTemplate.queryForObject(
                "SELECT model FROM completion_requests WHERE id = ?",
                String::class.java,
                id,
            ),
        )
    }

    @Test
    fun `owner can read, a different user gets 404, admin can read`() {
        val ownerToken = registerAndLogin("completion-owner@example.com")
        val id = post(ownerToken, "secret prompt").get("id").asText()

        mockMvc
            .get("/api/v1/completions/$id") { header("Authorization", "Bearer $ownerToken") }
            .andExpect {
                status { isOk() }
                jsonPath("$.result") { value("echo: secret prompt") }
            }

        val otherToken = registerAndLogin("completion-other@example.com")
        mockMvc
            .get("/api/v1/completions/$id") { header("Authorization", "Bearer $otherToken") }
            .andExpect { status { isNotFound() } }

        val admin = adminToken("completion-admin@example.com")
        mockMvc
            .get("/api/v1/completions/$id") { header("Authorization", "Bearer $admin") }
            .andExpect {
                status { isOk() }
                jsonPath("$.result") { value("echo: secret prompt") }
            }
    }

    @Test
    fun `an unknown id is 404`() {
        val token = registerAndLogin("completion-unknown@example.com")
        mockMvc
            .get("/api/v1/completions/${UUID.randomUUID()}") { header("Authorization", "Bearer $token") }
            .andExpect { status { isNotFound() } }
    }

    @Test
    fun `a blank prompt is 400`() {
        val token = registerAndLogin("completion-blank@example.com")
        mockMvc
            .post("/api/v1/completions") {
                header("Authorization", "Bearer $token")
                contentType = MediaType.APPLICATION_JSON
                content = "{\"prompt\":\"   \"}"
            }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `an over-max prompt is 413`() {
        val token = registerAndLogin("completion-toolong@example.com")
        val hugePrompt = "a".repeat(8_001) // default kira.completion.prompt-max-length is 8000
        mockMvc
            .post("/api/v1/completions") {
                header("Authorization", "Bearer $token")
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(mapOf("prompt" to hugePrompt))
            }.andExpect { status { isPayloadTooLarge() } }
    }

    @Test
    fun `a model longer than the database bound is rejected with 400 before persistence`() {
        val token = registerAndLogin("completion-model-too-long@example.com")
        mockMvc
            .post("/api/v1/completions") {
                header("Authorization", "Bearer $token")
                contentType = MediaType.APPLICATION_JSON
                content =
                    objectMapper.writeValueAsString(
                        mapOf("prompt" to "hi", "model" to "m".repeat(129)),
                    )
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.errors[0].code") { value("MODEL_TOO_LONG") }
            }

        assertEquals(
            0,
            jdbcTemplate.queryForObject("SELECT count(*) FROM completion_requests", Int::class.java),
        )
    }

    @Test
    fun `list is paginated and newest first`() {
        val token = registerAndLogin("completion-list@example.com")
        post(token, "first")
        Thread.sleep(5) // distinct created_at so DESC ordering is deterministic
        post(token, "second")
        Thread.sleep(5)
        post(token, "third")

        val page0 = list(token, "?page=0&size=2")
        assertEquals(3, page0.get("total").asLong())
        assertEquals(2, page0.get("items").size())
        assertEquals("echo: third", page0.get("items").get(0).get("result").asText())
        assertEquals("echo: second", page0.get("items").get(1).get("result").asText())

        val page1 = list(token, "?page=1&size=2")
        assertEquals(1, page1.get("items").size())
        assertEquals("echo: first", page1.get("items").get(0).get("result").asText())
    }

    @Test
    fun `admin can list another users completions with userId`() {
        val userToken = registerAndLogin("completion-target@example.com")
        post(userToken, "user prompt")
        val targetId = users.findByEmail("completion-target@example.com")!!.id

        val admin = adminToken("completion-lister-admin@example.com")
        val json = list(admin, "?userId=$targetId")
        assertEquals(1, json.get("total").asLong())
        assertEquals("echo: user prompt", json.get("items").get(0).get("result").asText())
    }

    @Test
    fun `bad pagination params are 400`() {
        val bearer = "Bearer ${registerAndLogin("completion-badpage@example.com")}"
        mockMvc.get("/api/v1/completions?page=-1") { header("Authorization", bearer) }
            .andExpect { status { isBadRequest() } }
        mockMvc.get("/api/v1/completions?size=0") { header("Authorization", bearer) }
            .andExpect { status { isBadRequest() } }
        mockMvc.get("/api/v1/completions?size=101") { header("Authorization", bearer) }
            .andExpect { status { isBadRequest() } }
        mockMvc.get("/api/v1/completions?size=abc") { header("Authorization", bearer) }
            .andExpect { status { isBadRequest() } }
    }

    private fun list(token: String, query: String): JsonNode {
        val result =
            mockMvc
                .get("/api/v1/completions$query") { header("Authorization", "Bearer $token") }
                .andExpect { status { isOk() } }
                .andReturn()
        return objectMapper.readTree(result.response.contentAsString)
    }
}
