package me.manga.kira.backend.completion

import me.manga.kira.backend.completion.application.CompletionPersistence
import me.manga.kira.backend.completion.application.CompletionPublication
import me.manga.kira.backend.completion.application.CompletionService
import me.manga.kira.backend.completion.application.RedisCompletionAdmission
import me.manga.kira.backend.completion.domain.CompletionErrorCode
import me.manga.kira.backend.completion.domain.CompletionOutcome
import me.manga.kira.backend.completion.domain.CompletionProvider
import me.manga.kira.backend.completion.domain.CompletionProviderLifetime
import me.manga.kira.backend.completion.domain.CompletionStatus
import me.manga.kira.backend.completion.domain.CompletionView
import me.manga.kira.backend.config.KiraCompletionProperties
import me.manga.kira.backend.observability.KiraMetrics
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Answers
import org.mockito.Mockito.mock
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class CompletionInterruptionTest {
    @Test
    fun `request interruption persists a sanitized terminal outcome before restoring interrupt`() {
        val id = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val providerStarted = CountDownLatch(1)
        val providerRelease = CountDownLatch(1)
        val releaseObserved = CountDownLatch(1)
        val worker = AtomicReference<Thread>()
        val persistedWithoutInterrupt = AtomicBoolean(false)
        val provider =
            object : CompletionProvider {
                override val name = "interrupt-test"

                // Audited test fake: every return or throw ends all local work.
                override val lifetime = CompletionProviderLifetime.SYNCHRONOUS

                override fun complete(prompt: String, model: String): CompletionOutcome {
                    worker.set(Thread.currentThread())
                    providerStarted.countDown()
                    return try {
                        CountDownLatch(1).await()
                        CompletionOutcome.Success("unreachable", 0)
                    } catch (_: InterruptedException) {
                        while (true) {
                            try {
                                providerRelease.await()
                                break
                            } catch (_: InterruptedException) {
                                // Deliberately retain actual local work after caller cancellation.
                            }
                        }
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

                    "markRunning" -> true

                    "storeOutcome" -> {
                        persistedWithoutInterrupt.set(!Thread.currentThread().isInterrupted)
                        CompletionPublication(failedView, won = true)
                    }

                    else -> Answers.RETURNS_DEFAULTS.answer(invocation)
                }
            }
        val releaseCalls = AtomicInteger()
        val activationCalls = AtomicInteger()
        val token = AtomicReference<String>()
        val releaseEnteredWithInterrupt = AtomicBoolean(false)
        val redis = redisTemplate(releaseCalls, activationCalls, token, releaseEnteredWithInterrupt, releaseObserved)
        val properties = KiraCompletionProperties(provider = provider.name, defaultModel = "model", executorThreads = 1, queueCapacity = 1)
        val admission = RedisCompletionAdmission(redis, properties)
        val service =
            CompletionService(
                listOf(provider),
                properties,
                persistence,
                admission,
                mock(KiraMetrics::class.java),
            )
        val interruptRestored = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>()
        val returnedView = AtomicReference<CompletionView?>()
        val requestThread =
            Thread {
                try {
                    returnedView.set(service.create(userId, "prompt", "model"))
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
            assertSame(failedView, returnedView.get())
            assertTrue(persistedWithoutInterrupt.get())
            assertEquals(1, activationCalls.get())
            assertEquals(0, releaseCalls.get(), "Caller exit cannot release still-running provider work")
            assertTrue(interruptRestored.get())
            providerRelease.countDown()
            assertTrue(releaseObserved.await(2, TimeUnit.SECONDS), "Actual body exit owes the deferred release")
            assertEquals(1, releaseCalls.get())
            assertFalse(releaseEnteredWithInterrupt.get(), "Owed cleanup runs with a cleared preexisting interrupt")
        } finally {
            providerRelease.countDown()
            service.shutdown()
            if (requestThread.isAlive) requestThread.interrupt()
            requestThread.join(2_000)
            worker.get()?.let {
                it.join(2_000)
                assertFalse(it.isAlive, "Owned completion worker did not stop")
            }
            assertFalse(requestThread.isAlive)
        }
    }

    private fun redisTemplate(
        releaseCalls: AtomicInteger,
        activationCalls: AtomicInteger,
        token: AtomicReference<String>,
        releaseEnteredWithInterrupt: AtomicBoolean,
        releaseObserved: CountDownLatch,
    ): StringRedisTemplate = mock(StringRedisTemplate::class.java) { invocation ->
        if (invocation.method.name == "execute") {
            val args = invocation.rawArguments[2] as Array<*>
            when (args[0]) {
                "acquire" -> {
                    assertEquals(4, invocation.getArgument<List<String>>(1).size)
                    token.set(args[1] as String)
                    0L
                }

                "activate" -> {
                    assertEquals(token.get(), args[1])
                    activationCalls.incrementAndGet()
                    1L
                }

                "release" -> {
                    assertEquals(token.get(), args[1])
                    releaseCalls.incrementAndGet()
                    releaseEnteredWithInterrupt.set(Thread.currentThread().isInterrupted)
                    releaseObserved.countDown()
                    throw DataAccessResourceFailureException("private Redis connection detail")
                }

                else -> error("Unexpected Redis operation")
            }
        } else {
            Answers.RETURNS_DEFAULTS.answer(invocation)
        }
    }
}
