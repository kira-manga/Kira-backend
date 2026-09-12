package me.manga.kira.backend.completion

import me.manga.kira.backend.common.exception.ServiceUnavailableException
import me.manga.kira.backend.completion.application.CompletionAdmission
import me.manga.kira.backend.completion.application.CompletionPermit
import me.manga.kira.backend.completion.application.CompletionPersistence
import me.manga.kira.backend.completion.application.CompletionPublication
import me.manga.kira.backend.completion.application.CompletionService
import me.manga.kira.backend.completion.application.RedisCompletionAdmission
import me.manga.kira.backend.completion.domain.CompletionErrorCode
import me.manga.kira.backend.completion.domain.CompletionOutcome
import me.manga.kira.backend.completion.domain.CompletionProvider
import me.manga.kira.backend.completion.domain.CompletionStatus
import me.manga.kira.backend.completion.domain.CompletionView
import me.manga.kira.backend.config.KiraCompletionProperties
import me.manga.kira.backend.observability.KiraMetrics
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Answers
import org.mockito.Mockito.mock
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Service/executor controls only; real persistence/commit proof is in CompletionLifecycleIT. */
@Timeout(10)
class CompletionLifecycleTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `only committed overload ownership can turn an equal-looking winner into 503`(won: Boolean) {
        val view = failedView(CompletionErrorCode.PROVIDER_UNAVAILABLE)
        val persistence = mock(CompletionPersistence::class.java) { invocation ->
            when (invocation.method.name) {
                "createPending" -> view.id
                "storeOutcome" -> CompletionPublication(view, won)
                else -> Answers.RETURNS_DEFAULTS.answer(invocation)
            }
        }
        val providerCalls = AtomicInteger()
        val releaseCalls = AtomicInteger()
        val provider = recordingProvider(providerCalls)
        val service = service(provider, persistence, failingReleaseAdmission(releaseCalls))
        // Actual executor rejection; no worker/provider was started, and no fake exception mapping.
        service.shutdown()

        if (won) {
            val error = assertThrows<ServiceUnavailableException> { service.create(view.userId, "synthetic", null) }
            assertEquals("COMPLETION_OVERLOADED", error.code)
            assertEquals(1L, error.retryAfterSeconds)
            assertEquals(0, error.suppressed.size)
        } else {
            assertSame(view, service.create(view.userId, "synthetic", null))
        }
        assertEquals(0, providerCalls.get())
        assertEquals(1, releaseCalls.get())
    }

    @Test
    fun `persistence failure remains primary when Redis release is unconfirmed`() {
        val id = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val sentinel = DataAccessResourceFailureException("synthetic persistence failure")
        val persistence = mock(CompletionPersistence::class.java) { invocation ->
            when (invocation.method.name) {
                "createPending" -> id
                "storeOutcome" -> throw sentinel
                else -> Answers.RETURNS_DEFAULTS.answer(invocation)
            }
        }
        val providerCalls = AtomicInteger()
        val releaseCalls = AtomicInteger()
        val service = service(recordingProvider(providerCalls), persistence, failingReleaseAdmission(releaseCalls))
        service.shutdown()

        val error = assertThrows<DataAccessResourceFailureException> { service.create(userId, "synthetic", null) }
        assertSame(sentinel, error)
        assertEquals(0, error.suppressed.size)
        assertEquals(0, providerCalls.get())
        assertEquals(1, releaseCalls.get())
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `failed or rejected RUNNING startup resolves before the queue deadline without provider work`(throwFromClaim: Boolean) {
        val view = failedView(CompletionErrorCode.INTERNAL_COMPLETION_ERROR)
        val calls = AtomicInteger()
        val worker = AtomicReference<Thread>()
        val persistence = mock(CompletionPersistence::class.java) { invocation ->
            when (invocation.method.name) {
                "createPending" -> view.id

                "markRunning" -> {
                    worker.set(Thread.currentThread())
                    if (throwFromClaim) error("synthetic startup failure")
                    false
                }

                "storeOutcome" -> {
                    assertEquals(CompletionStatus.FAILED, invocation.getArgument<CompletionStatus>(1))
                    assertEquals(CompletionErrorCode.INTERNAL_COMPLETION_ERROR, invocation.getArgument<CompletionErrorCode>(4))
                    CompletionPublication(view, won = true)
                }

                else -> Answers.RETURNS_DEFAULTS.answer(invocation)
            }
        }
        val service = service(recordingProvider(calls), persistence)
        try {
            // A lost early signal would wait for the one-day queue timeout, not pass this bounded test.
            assertEquals(view, service.create(view.userId, "synthetic", null))
            assertEquals(0, calls.get())
        } finally {
            service.shutdown()
            worker.get()?.let {
                it.join(2_000)
                assertFalse(it.isAlive, "Owned completion worker did not stop")
            }
        }
    }

    private fun service(
        provider: CompletionProvider,
        persistence: CompletionPersistence,
        admission: CompletionAdmission = object : CompletionAdmission {
            override fun acquire(userId: UUID): CompletionPermit = CompletionPermit {}
        },
    ): CompletionService = CompletionService(
        listOf(provider),
        KiraCompletionProperties(
            provider = provider.name,
            defaultModel = "configured-model",
            executorThreads = 1,
            queueCapacity = 1,
            queueTimeout = Duration.ofDays(1),
        ),
        persistence,
        admission,
        mock(KiraMetrics::class.java),
    )

    private fun failingReleaseAdmission(releaseCalls: AtomicInteger): CompletionAdmission {
        val redis = mock(StringRedisTemplate::class.java) { invocation ->
            if (invocation.method.name == "execute") {
                when (invocation.getArgument<List<String>>(1).size) {
                    4 -> 0L

                    1 -> {
                        releaseCalls.incrementAndGet()
                        throw DataAccessResourceFailureException("private Redis connection detail")
                    }

                    else -> error("Unexpected Redis operation")
                }
            } else {
                Answers.RETURNS_DEFAULTS.answer(invocation)
            }
        }
        return RedisCompletionAdmission(redis, KiraCompletionProperties())
    }

    private fun recordingProvider(calls: AtomicInteger): CompletionProvider = object : CompletionProvider {
        override val name = "lifecycle-test"

        override fun complete(prompt: String, model: String): CompletionOutcome {
            calls.incrementAndGet()
            return CompletionOutcome.Success("synthetic", 0)
        }
    }

    private fun failedView(code: CompletionErrorCode): CompletionView = CompletionView(
        id = UUID.randomUUID(),
        userId = UUID.randomUUID(),
        provider = "lifecycle-test",
        model = "configured-model",
        status = CompletionStatus.FAILED,
        result = null,
        error = "The completion request could not be completed.",
        errorCode = code,
        createdAt = Instant.EPOCH,
    )
}
