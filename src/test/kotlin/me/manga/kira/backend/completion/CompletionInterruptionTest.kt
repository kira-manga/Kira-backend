package me.manga.kira.backend.completion

import me.manga.kira.backend.completion.application.CompletionAdmission
import me.manga.kira.backend.completion.application.CompletionPermit
import me.manga.kira.backend.completion.application.CompletionPersistence
import me.manga.kira.backend.completion.application.CompletionService
import me.manga.kira.backend.completion.domain.CompletionErrorCode
import me.manga.kira.backend.completion.domain.CompletionOutcome
import me.manga.kira.backend.completion.domain.CompletionProvider
import me.manga.kira.backend.completion.domain.CompletionStatus
import me.manga.kira.backend.completion.domain.CompletionView
import me.manga.kira.backend.config.KiraCompletionProperties
import me.manga.kira.backend.observability.KiraMetrics
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Answers
import org.mockito.Mockito.mock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class CompletionInterruptionTest {
    @Test
    fun `request interruption persists a sanitized terminal outcome before restoring interrupt`() {
        val id = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val providerStarted = CountDownLatch(1)
        val persistedWithoutInterrupt = AtomicBoolean(false)
        val provider =
            object : CompletionProvider {
                override val name = "interrupt-test"

                override fun complete(prompt: String, model: String): CompletionOutcome {
                    providerStarted.countDown()
                    return try {
                        CountDownLatch(1).await()
                        CompletionOutcome.Success("unreachable", 0)
                    } catch (_: InterruptedException) {
                        CompletionOutcome.Failure("worker cancelled")
                    }
                }
            }
        val failedView =
            CompletionView(
                id = id,
                userId = userId,
                provider = provider.name,
                model = "model",
                status = CompletionStatus.FAILED,
                result = null,
                error = "The completion request could not be completed.",
                errorCode = CompletionErrorCode.INTERNAL_COMPLETION_ERROR,
                createdAt = Instant.EPOCH,
            )
        val persistence =
            mock(CompletionPersistence::class.java) { invocation ->
                when (invocation.method.name) {
                    "createPending" -> id

                    "markRunning" -> null

                    "storeOutcome" -> {
                        persistedWithoutInterrupt.set(!Thread.currentThread().isInterrupted)
                        failedView
                    }

                    else -> Answers.RETURNS_DEFAULTS.answer(invocation)
                }
            }
        val admission =
            object : CompletionAdmission {
                override fun acquire(userId: UUID): CompletionPermit = CompletionPermit {}
            }
        val service =
            CompletionService(
                listOf(provider),
                KiraCompletionProperties(provider = provider.name, executorThreads = 1, queueCapacity = 1),
                persistence,
                admission,
                mock(KiraMetrics::class.java),
            )
        val interruptRestored = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>()
        val requestThread =
            Thread {
                try {
                    service.create(userId, "prompt", "model")
                    interruptRestored.set(Thread.currentThread().isInterrupted)
                } catch (ex: Throwable) {
                    failure.set(ex)
                }
            }
        try {
            requestThread.start()
            assertTrue(providerStarted.await(2, TimeUnit.SECONDS))
            requestThread.interrupt()
            requestThread.join(2_000)

            assertFalse(requestThread.isAlive)
            assertNull(failure.get())
            assertTrue(persistedWithoutInterrupt.get())
            assertTrue(interruptRestored.get())
        } finally {
            service.shutdown()
        }
    }
}
