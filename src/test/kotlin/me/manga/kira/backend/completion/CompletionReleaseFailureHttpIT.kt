package me.manga.kira.backend.completion

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import me.manga.kira.backend.completion.api.dto.CompletionResponse
import me.manga.kira.backend.completion.application.CompletionAdmission
import me.manga.kira.backend.completion.application.RedisCompletionAdmission
import me.manga.kira.backend.completion.domain.CompletionOutcome
import me.manga.kira.backend.completion.domain.CompletionProvider
import me.manga.kira.backend.completion.domain.CompletionProviderLifetime
import me.manga.kira.backend.security.JwtService
import me.manga.kira.backend.support.AbstractIntegrationTest
import me.manga.kira.backend.user.domain.Role
import me.manga.kira.backend.user.domain.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Answers
import org.mockito.Mockito.mock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

private const val RELEASE_PROVIDER = "release-failure-http"
private const val RELEASE_MODEL = "release-model"
private const val REFUSAL_PROMPT = "private refusal prompt"
private const val ACCEPTED_PROMPT = "private accepted prompt"
private const val PUBLIC_RESULT = "completed result"
private const val PRIVATE_PROVIDER_DETAIL = "private provider refusal detail"
private const val PRIVATE_REDIS_DETAIL = "private Redis release connection detail"
private const val PUBLIC_ERROR = "The completion request could not be completed."

/** Real authenticated HTTP and PostgreSQL; only Redis transport and the named provider are test doubles. */
@Import(CompletionReleaseFailureHttpConfig::class)
@TestPropertySource(
    properties = ["kira.completion.coordination-backend=redis", "kira.completion.provider=release-failure-http"],
)
class CompletionReleaseFailureHttpIT
@Autowired
constructor(
    private val mockMvc: MockMvc,
    private val objectMapper: ObjectMapper,
    private val users: UserRepository,
    private val jwtService: JwtService,
    private val admission: CompletionAdmission,
    private val transport: ReleaseFailureHttpTransport,
    private val provider: ReleaseFailureHttpProvider,
    private val dataSource: DataSource,
    private val registry: MeterRegistry,
) : AbstractIntegrationTest() {
    @BeforeEach
    fun resetReleaseFixture() {
        transport.reset()
        provider.calls.set(0)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `committed HTTP outcome survives Redis release failure`(refused: Boolean) {
        assertTrue(admission is RedisCompletionAdmission, "The test profile must not select memory admission")
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
        val user = users.create("release-outcome@example.com", "unused-test-password-hash", Role.USER)
        val token = jwtService.issue(user).value
        val prompt = if (refused) REFUSAL_PROMPT else ACCEPTED_PROMPT
        val unconfirmedBefore = admissionEvents("release_unconfirmed")
        val unavailableBefore = admissionEvents("coordination_unavailable")
        var committed: CompletionResponse? = null

        // Reserve this independent pooled session BEFORE POST, so the writer cannot borrow it.
        dataSource.connection.use { observer ->
            assertTrue(observer.autoCommit)
            transport.onRelease = {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "Release must follow the outcome commit")
                assertTrue(observer.autoCommit)
                committed = observeCommitted(observer, user.id).also { assertOutcome(it, refused) }
            }
            try {
                val response = mockMvc.post("/api/v1/completions") {
                    header("Authorization", "Bearer $token")
                    contentType = MediaType.APPLICATION_JSON
                    content = objectMapper.writeValueAsString(mapOf("prompt" to prompt, "model" to RELEASE_MODEL))
                }.andExpect {
                    status { isCreated() }
                    header { doesNotExist("Retry-After") }
                }.andReturn().response

                assertEquals(listOf(4, 1, 1), transport.keyCounts, "Acquire and activation succeed before the release-only fault")
                assertEquals(listOf("acquire", "activate", "release"), transport.operations)
                assertEquals(1, transport.tokens.toSet().size)
                assertEquals(4, UUID.fromString(transport.tokens.distinct().single()).version())
                val snapshot = checkNotNull(committed) { "Release did not observe the independently committed pair" }
                val expected = objectMapper.valueToTree<JsonNode>(snapshot)
                assertEquals(expected, objectMapper.readTree(response.contentAsString))
                val fetched = mockMvc.get("/api/v1/completions/${snapshot.id}") {
                    header("Authorization", "Bearer $token")
                }.andExpect {
                    status { isOk() }
                    header { doesNotExist("Retry-After") }
                }.andReturn().response
                assertEquals(expected, objectMapper.readTree(fetched.contentAsString))

                val history = mockMvc.get("/api/v1/completions") {
                    header("Authorization", "Bearer $token")
                }.andExpect {
                    status { isOk() }
                    header { doesNotExist("Retry-After") }
                }.andReturn().response
                val page = objectMapper.readTree(history.contentAsString)
                assertEquals(1L, page.path("total").asLong())
                assertEquals(1, page.path("items").size())
                assertEquals(expected, page.path("items").single())
                listOf(response.contentAsString, fetched.contentAsString, history.contentAsString).forEach { body ->
                    listOf(prompt, PRIVATE_PROVIDER_DETAIL, PRIVATE_REDIS_DETAIL, "DataAccessResourceFailureException").forEach { detail ->
                        assertFalse(body.contains(detail))
                    }
                }

                // There is no second POST: reads must not retry admission or invoke another provider.
                assertEquals(1, provider.calls.get())
                assertEquals(1L, jdbcTemplate.queryForObject("SELECT count(*) FROM completion_requests", Long::class.java))
                assertEquals(1L, jdbcTemplate.queryForObject("SELECT count(*) FROM completion_results", Long::class.java))
                assertEquals(listOf(4, 1, 1), transport.keyCounts)
                assertEquals(listOf("acquire", "activate", "release"), transport.operations)
                assertEquals(unconfirmedBefore + 1.0, admissionEvents("release_unconfirmed"))
                assertEquals(unavailableBefore, admissionEvents("coordination_unavailable"))
            } finally {
                transport.onRelease = null
            }
        }
    }

    private fun observeCommitted(observer: Connection, userId: UUID): CompletionResponse {
        // Count requests independently: a join alone could hide an extra request without an outcome.
        val id = observer.prepareStatement("SELECT id FROM completion_requests WHERE user_id = ?").use { statement ->
            statement.setObject(1, userId)
            statement.executeQuery().use { rows ->
                assertTrue(rows.next(), "A committed request must already be independently visible at release")
                rows.getObject("id", UUID::class.java).also {
                    assertFalse(rows.next(), "Exactly one committed request")
                }
            }
        }
        return observer.prepareStatement(
            """
            SELECT q.id, q.status, q.model, q.provider, q.created_at,
                   r.request_id, r.result, r.error_code, r.error
            FROM completion_requests q JOIN completion_results r ON r.request_id = q.id
            WHERE q.id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, id)
            statement.executeQuery().use { rows ->
                assertTrue(rows.next(), "A committed result must already be independently visible at release")
                assertEquals(id, rows.getObject("request_id", UUID::class.java))
                CompletionResponse(
                    id = id,
                    status = rows.getString("status"),
                    model = rows.getString("model"),
                    provider = rows.getString("provider"),
                    result = rows.getString("result"),
                    errorCode = rows.getString("error_code"),
                    error = rows.getString("error"),
                    createdAt = rows.getTimestamp("created_at").toInstant(),
                ).also { assertFalse(rows.next(), "Exactly one committed matching result") }
            }
        }
    }

    private fun assertOutcome(view: CompletionResponse, refused: Boolean) {
        assertEquals(if (refused) "FAILED" else "SUCCEEDED", view.status)
        assertEquals(RELEASE_MODEL, view.model)
        assertEquals(RELEASE_PROVIDER, view.provider)
        assertEquals(if (refused) null else PUBLIC_RESULT, view.result)
        assertEquals(if (refused) "PROVIDER_REJECTED" else null, view.errorCode)
        assertEquals(if (refused) PUBLIC_ERROR else null, view.error)
    }

    private fun admissionEvents(outcome: String): Double = registry
        .find("kira.completion.admission.events")
        .tag("outcome", outcome).counter()?.count() ?: 0.0
}

@TestConfiguration
class CompletionReleaseFailureHttpConfig {
    @Bean
    fun releaseFailureHttpProvider() = ReleaseFailureHttpProvider()

    @Bean
    fun releaseFailureHttpTransport() = ReleaseFailureHttpTransport()

    @Bean
    fun stringRedisTemplate(transport: ReleaseFailureHttpTransport): StringRedisTemplate = transport.redis
}

class ReleaseFailureHttpProvider : CompletionProvider {
    override val name: String = RELEASE_PROVIDER
    // Audited test fake: every return or throw ends all local work.
    override val lifetime = CompletionProviderLifetime.SYNCHRONOUS
    val calls = AtomicInteger()

    override fun complete(prompt: String, model: String): CompletionOutcome {
        calls.incrementAndGet()
        return if (prompt == REFUSAL_PROMPT) {
            CompletionOutcome.Failure(PRIVATE_PROVIDER_DETAIL)
        } else {
            CompletionOutcome.Success(PUBLIC_RESULT, 1)
        }
    }
}

/** The real Redis admission still owns acquisition, result mapping, close and diagnostics. */
class ReleaseFailureHttpTransport {
    val keyCounts = mutableListOf<Int>()
    val operations = mutableListOf<String>()
    val tokens = mutableListOf<String>()
    private var acquiredToken: String? = null
    var onRelease: (() -> Unit)? = null

    fun reset() {
        keyCounts.clear()
        operations.clear()
        tokens.clear()
        acquiredToken = null
        onRelease = null
    }

    val redis: StringRedisTemplate = mock(StringRedisTemplate::class.java) { call ->
        if (call.method.name == "execute") {
            val keys = call.getArgument<List<String>>(1)
            val args = call.rawArguments[2] as Array<*>
            val operation = args[0] as String
            val token = args[1] as String
            keyCounts += keys.size
            operations += operation
            tokens += token
            when (operation) {
                "acquire" -> {
                    assertEquals(4, keys.size)
                    check(acquiredToken == null)
                    acquiredToken = token
                    0L
                }

                "activate" -> {
                    assertEquals(1, keys.size)
                    assertEquals(acquiredToken, token)
                    1L
                }

                "release" -> {
                    assertEquals(1, keys.size)
                    assertEquals(acquiredToken, token)
                    checkNotNull(onRelease) { "Missing commit observer at release" }.invoke()
                    throw DataAccessResourceFailureException(PRIVATE_REDIS_DETAIL)
                }

                else -> error("Unexpected Redis admission operation")
            }
        } else {
            Answers.RETURNS_DEFAULTS.answer(call)
        }
    }
}
