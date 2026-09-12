package me.manga.kira.backend.completion

import com.fasterxml.jackson.databind.ObjectMapper
import me.manga.kira.backend.completion.application.CompletionAdmission
import me.manga.kira.backend.completion.application.CompletionPersistence
import me.manga.kira.backend.completion.application.CompletionPublication
import me.manga.kira.backend.completion.application.CompletionService
import me.manga.kira.backend.completion.application.InMemoryCompletionAdmission
import me.manga.kira.backend.completion.domain.CompletionOutcome
import me.manga.kira.backend.completion.domain.CompletionProvider
import me.manga.kira.backend.completion.domain.CompletionProviderLifetime
import me.manga.kira.backend.completion.domain.CompletionStatus
import me.manga.kira.backend.completion.domain.CompletionView
import me.manga.kira.backend.completion.infrastructure.HttpCompletionProvider
import me.manga.kira.backend.config.KiraCompletionProperties
import me.manga.kira.backend.observability.KiraMetrics
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.mockito.Answers
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.test.util.ReflectionTestUtils
import java.net.http.HttpClient
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Actual service/executor path; persistence is a recording fake and the provider never opens I/O. */
@Timeout(10)
class CompletionDefaultModelTest {
    @Test
    fun `null empty and whitespace requests persist and forward the exact configured model`() {
        listOf(null, "", " \t\r\n", " ".repeat(128)).forEach { requested ->
            assertEffectiveModel(requested, CONFIGURED_MODEL)
        }
    }

    @Test
    fun `explicit nonblank model overrides remain unchanged`() {
        listOf("override-model", "  Caller-Model/V3\t", "m".repeat(128)).forEach { requested ->
            assertEffectiveModel(requested, requested)
        }
    }

    @Test
    fun `invalid defaults reject direct construction before executor metrics admission or provider work`() {
        val invalid = listOf(null, "", " \t\r\n", "m".repeat(129), " ".repeat(129), "\uD83D\uDE00".repeat(64) + "m")
        invalid.forEach { configured ->
            val persistence = mock(CompletionPersistence::class.java)
            val admission = mock(CompletionAdmission::class.java)
            val metrics = mock(KiraMetrics::class.java)
            val provider = object : CompletionProvider {
                override val name = "construction-test"

                override fun complete(prompt: String, model: String): CompletionOutcome = error("No provider work may start during construction")
            }
            // An executor-first regression must hit its invalid-thread sentinel, not allocate workers.
            // The oracle is the default-model-specific failure, never just any IllegalArgumentException.
            val properties = KiraCompletionProperties(provider = provider.name, defaultModel = configured, executorThreads = 0)

            val failure = assertThrows<IllegalArgumentException> {
                CompletionService(listOf(provider), properties, persistence, admission, metrics)
            }

            assertEquals("kira.completion.default-model must be nonblank and at most 128 UTF-16 units", failure.message)
            verifyNoInteractions(persistence, admission, metrics)
        }
    }

    @Test
    fun `UNKNOWN and actual HTTP providers reject before executor metrics admission or invocation`() {
        val unknown = object : CompletionProvider {
            override val name = "unknown-lifetime-test"

            override fun complete(prompt: String, model: String): CompletionOutcome = error("UNKNOWN provider must not be invoked")
        }
        assertUnknownRejected(unknown)

        // Injection may already construct HTTP's client; the service guard does not claim otherwise.
        val http = HttpCompletionProvider(KiraCompletionProperties(), ObjectMapper())
        try {
            assertUnknownRejected(http)
        } finally {
            // No request is sent. Close this test-owned constructor resource, not any shared client.
            (ReflectionTestUtils.getField(http, "client") as HttpClient).close()
        }
    }

    @Test
    fun `disabled service startup needs no model provider credentials or collaborators`() {
        ApplicationContextRunner()
            .withUserConfiguration(CompletionService::class.java)
            .withPropertyValues("kira.completion.enabled=false")
            .run { context ->
                assertNull(context.startupFailure)
                assertTrue(context.getBeansOfType(CompletionService::class.java).isEmpty())
                assertTrue(context.getBeansOfType(CompletionProvider::class.java).isEmpty())
            }
    }

    private fun assertUnknownRejected(provider: CompletionProvider) {
        assertEquals(CompletionProviderLifetime.UNKNOWN, provider.lifetime)
        val persistence = mock(CompletionPersistence::class.java)
        val admission = mock(CompletionAdmission::class.java)
        val metrics = mock(KiraMetrics::class.java)
        val properties = KiraCompletionProperties(provider = provider.name, defaultModel = CONFIGURED_MODEL, executorThreads = 0)

        val failure = assertThrows<IllegalStateException> {
            CompletionService(listOf(provider), properties, persistence, admission, metrics)
        }

        assertEquals("Selected CompletionProvider must end all work on every synchronous method exit", failure.message)
        verifyNoInteractions(persistence, admission, metrics)
    }

    private fun assertEffectiveModel(requested: String?, expected: String) {
        val id = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val pendingModel = AtomicReference<String>()
        val forwardedModel = AtomicReference<String>()
        val worker = AtomicReference<Thread>()
        val calls = AtomicInteger()
        val provider = object : CompletionProvider {
            override val name = "recording-model-test"
            // This fake records values synchronously; no work outlives complete.
            override val lifetime = CompletionProviderLifetime.SYNCHRONOUS

            override fun complete(prompt: String, model: String): CompletionOutcome {
                worker.set(Thread.currentThread())
                forwardedModel.set(model)
                calls.incrementAndGet()
                return CompletionOutcome.Success("synthetic result", 0)
            }
        }
        val persistence = mock(CompletionPersistence::class.java) { invocation ->
            when (invocation.method.name) {
                "createPending" -> {
                    pendingModel.set(invocation.getArgument(2))
                    id
                }

                "markRunning" -> true

                "storeOutcome" -> CompletionPublication(
                    CompletionView(
                        id = id,
                        userId = userId,
                        provider = provider.name,
                        model = requireNotNull(pendingModel.get()),
                        status = invocation.getArgument(1),
                        result = invocation.getArgument(2),
                        error = invocation.getArgument(3),
                        errorCode = invocation.getArgument(4),
                        createdAt = Instant.EPOCH,
                    ),
                    won = true,
                )

                else -> Answers.RETURNS_DEFAULTS.answer(invocation)
            }
        }
        val admission = InMemoryCompletionAdmission(KiraCompletionProperties(globalConcurrency = 1), Clock.systemUTC())
        val service = CompletionService(
            listOf(provider),
            KiraCompletionProperties(
                enabled = true,
                provider = provider.name,
                defaultModel = CONFIGURED_MODEL,
                executorThreads = 1,
                queueCapacity = 1,
                timeout = Duration.ofSeconds(2),
                queueTimeout = Duration.ofSeconds(1),
            ),
            persistence,
            admission,
            mock(KiraMetrics::class.java),
        )
        try {
            val view = service.create(userId, "synthetic prompt", requested)

            assertEquals(CompletionStatus.SUCCEEDED, view.status)
            assertEquals("synthetic result", view.result)
            assertNull(view.errorCode)
            assertEquals(expected, view.model)
            assertEquals(expected, pendingModel.get())
            assertEquals(expected, forwardedModel.get())
            assertEquals(1, calls.get())
            verify(persistence).createPending(userId, provider.name, expected, "synthetic prompt")
        } finally {
            service.shutdown()
            worker.get()?.let { ownedWorker ->
                ownedWorker.join(2_000)
                assertFalse(ownedWorker.isAlive, "Owned completion worker did not stop")
            }
        }
    }

    private companion object {
        const val CONFIGURED_MODEL = "  Synthetic-Production-Model/V2\t"
    }
}
