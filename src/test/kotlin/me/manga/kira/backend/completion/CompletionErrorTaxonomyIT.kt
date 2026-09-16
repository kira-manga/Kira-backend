package me.manga.kira.backend.completion

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import me.manga.kira.backend.completion.domain.CompletionOutcome
import me.manga.kira.backend.completion.domain.CompletionProvider
import me.manga.kira.backend.security.JwtService
import me.manga.kira.backend.support.AbstractIntegrationTest
import me.manga.kira.backend.user.domain.Role
import me.manga.kira.backend.user.domain.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.util.UUID

/** The single sanitized, generic, client-visible failure message (PLAN §10). */
private const val SANITIZED_MESSAGE = "The completion request could not be completed."

/** A provider-internal token that must NEVER reach a client-visible channel or a stored row (PLAN §10). */
private const val SENSITIVE_TOKEN = "SENSITIVE_INTERNAL_TOKEN_9f3c"

/**
 * PLAN §11 test 49 — `CompletionErrorTaxonomyIT`: failures map to stable codes and sanitized messages.
 * A controllable test provider (name `test`, keyed off the prompt) is selected via `@TestPropertySource`
 * + `@Import`, entirely in test sources. A shortened `kira.completion.timeout` makes the timeout path
 * fast. Proven: timeout → `PROVIDER_TIMEOUT`; refusal → `PROVIDER_REJECTED`; unexpected exception →
 * `INTERNAL_COMPLETION_ERROR` with a generic bounded message and NO stack trace / class name in the
 * response OR the row; success → NULL `error`/`error_code`; and the DB CHECK forbids both result+error.
 */
@Import(TaxonomyTestProviderConfig::class)
@TestPropertySource(properties = ["kira.completion.provider=test", "kira.completion.timeout=PT1S"])
class CompletionErrorTaxonomyIT
@Autowired
constructor(
    private val mockMvc: MockMvc,
    private val objectMapper: ObjectMapper,
    private val users: UserRepository,
    private val jwtService: JwtService,
) : AbstractIntegrationTest() {

    private fun userToken(email: String): String {
        val user = users.create(email, "{bcrypt}\$2a\$10\$notarealhashjustfortaxonomyit......", Role.USER)
        return jwtService.issue(user).value
    }

    private fun post(token: String, prompt: String): JsonNode {
        val result =
            mockMvc
                .post("/api/v1/completions") {
                    header("Authorization", "Bearer $token")
                    contentType = MediaType.APPLICATION_JSON
                    content = objectMapper.writeValueAsString(mapOf("prompt" to prompt))
                }.andExpect { status { isCreated() } }
                .andReturn()
        return objectMapper.readTree(result.response.contentAsString)
    }

    private fun storedResult(id: String): Map<String, Any?> = jdbcTemplate.queryForMap(
        "SELECT result, error, error_code FROM completion_results WHERE request_id = ?",
        UUID.fromString(id),
    )

    @Test
    fun `a provider timeout maps to PROVIDER_TIMEOUT`() {
        val json = post(userToken("taxonomy-timeout@example.com"), "SLEEP")

        assertEquals("FAILED", json.get("status").asText())
        assertEquals("PROVIDER_TIMEOUT", json.get("errorCode").asText())
        assertEquals(SANITIZED_MESSAGE, json.get("error").asText())
        assertFalse(json.hasNonNull("result"))

        val row = storedResult(json.get("id").asText())
        assertEquals("PROVIDER_TIMEOUT", row["error_code"])
        assertEquals(SANITIZED_MESSAGE, row["error"])
        assertEquals(null, row["result"])
    }

    @Test
    fun `a provider refusal maps to PROVIDER_REJECTED`() {
        val json = post(userToken("taxonomy-reject@example.com"), "REJECT")

        assertEquals("FAILED", json.get("status").asText())
        assertEquals("PROVIDER_REJECTED", json.get("errorCode").asText())
        assertEquals(SANITIZED_MESSAGE, json.get("error").asText())
        assertFalse(json.hasNonNull("result"))
        assertEquals("PROVIDER_REJECTED", storedResult(json.get("id").asText())["error_code"])
    }

    @Test
    fun `an unexpected provider exception maps to INTERNAL_COMPLETION_ERROR and never leaks internals`() {
        val response =
            mockMvc
                .post("/api/v1/completions") {
                    header("Authorization", "Bearer ${userToken("taxonomy-throw@example.com")}")
                    contentType = MediaType.APPLICATION_JSON
                    content = objectMapper.writeValueAsString(mapOf("prompt" to "THROW"))
                }.andExpect { status { isCreated() } }
                .andReturn()
        val body = response.response.contentAsString
        val json = objectMapper.readTree(body)

        assertEquals("FAILED", json.get("status").asText())
        assertEquals("INTERNAL_COMPLETION_ERROR", json.get("errorCode").asText())
        assertEquals(SANITIZED_MESSAGE, json.get("error").asText())
        assertFalse(json.hasNonNull("result"))

        // No stack trace, exception class name, or the provider's internal token in the RESPONSE …
        assertFalse(body.contains(SENSITIVE_TOKEN))
        assertFalse(body.contains("RuntimeException"))
        assertFalse(body.contains("at me.manga"))

        // … nor in the stored ROW (the DB `error` is the sanitized message only).
        val row = storedResult(json.get("id").asText())
        assertEquals("INTERNAL_COMPLETION_ERROR", row["error_code"])
        assertEquals(SANITIZED_MESSAGE, row["error"])
        assertEquals(null, row["result"])
    }

    @Test
    fun `a success carries NULL error and error_code`() {
        val json = post(userToken("taxonomy-ok@example.com"), "hello")

        assertEquals("SUCCEEDED", json.get("status").asText())
        assertEquals("ok: hello", json.get("result").asText())
        assertFalse(json.hasNonNull("error"))
        assertFalse(json.hasNonNull("errorCode"))

        val row = storedResult(json.get("id").asText())
        assertEquals("ok: hello", row["result"])
        assertEquals(null, row["error"])
        assertEquals(null, row["error_code"])
    }

    @Test
    fun `the DB CHECK forbids a row with both a result and an error`() {
        // A real request row is needed for the FK; insert one directly, then attempt an illegal outcome.
        val user = users.create("taxonomy-check@example.com", "{bcrypt}\$2a\$10\$notarealhashforcheck.......", Role.USER)
        val requestId = UUID.randomUUID()
        // Timestamps use SQL now() — raw JdbcTemplate cannot infer a JDBC type for java.time.Instant.
        jdbcTemplate.update(
            "INSERT INTO completion_requests (id, user_id, provider, model, prompt, status, created_at, updated_at) " +
                "VALUES (?, ?, 'test', 'm', 'p', 'RUNNING', now(), now())",
            requestId,
            user.id,
        )

        assertThrows(DataIntegrityViolationException::class.java) {
            jdbcTemplate.update(
                "INSERT INTO completion_results (request_id, result, error, error_code, created_at) " +
                    "VALUES (?, 'a result', 'an error', 'PROVIDER_REJECTED', now())",
                requestId,
            )
        }
    }
}

/** Test-only config: registers the controllable provider selected by `kira.completion.provider=test`. */
@TestConfiguration
class TaxonomyTestProviderConfig {
    @Bean
    fun testCompletionProvider(): CompletionProvider = ControllableTestCompletionProvider()
}

/** A controllable [CompletionProvider] (name `test`) whose behavior is keyed off the prompt (test-only). */
class ControllableTestCompletionProvider : CompletionProvider {
    override val name: String = "test"

    override fun complete(prompt: String, model: String): CompletionOutcome = when (prompt.trim()) {
        // Sleeps well past the shortened timeout → the orchestrator times out and interrupts it.
        "SLEEP" -> {
            Thread.sleep(SLEEP_MILLIS)
            CompletionOutcome.Success("late", SLEEP_MILLIS.toInt())
        }

        // A deliberate refusal — the reason is provider-internal; never surfaced to the client.
        "REJECT" -> CompletionOutcome.Failure("provider refused: internal policy $SENSITIVE_TOKEN")

        // An unexpected transport/bug throwable → INTERNAL_COMPLETION_ERROR, fully sanitized.
        "THROW" -> throw RuntimeException(SENSITIVE_TOKEN)

        else -> CompletionOutcome.Success("ok: $prompt", 1)
    }

    private companion object {
        const val SLEEP_MILLIS = 10_000L
    }
}
