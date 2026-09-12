package me.manga.kira.backend.completion

import me.manga.kira.backend.common.exception.ServiceUnavailableException
import me.manga.kira.backend.completion.application.CompletionActivation
import me.manga.kira.backend.completion.application.CompletionAdmission
import me.manga.kira.backend.completion.application.CompletionPermit
import me.manga.kira.backend.completion.application.CompletionPersistence
import me.manga.kira.backend.completion.application.CompletionPublication
import me.manga.kira.backend.completion.application.CompletionService
import me.manga.kira.backend.completion.application.InMemoryCompletionAdmission
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
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Answers
import org.mockito.Mockito.mock
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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

    @ParameterizedTest(name = "two-worker ownership [{index}] interruptCaller={0}")
    @ValueSource(booleans = [false, true])
    fun `two workers cannot replace physically running work after caller timeout or interruption`(interruptCaller: Boolean) {
        val userId = UUID.randomUUID()
        val workers = CopyOnWriteArraySet<Thread>()
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val heldReleased = CountDownLatch(1)
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()
        val calls = AtomicInteger()
        val activations = AtomicInteger()
        val closes = AtomicInteger()
        val releasedWhileActive = AtomicBoolean()
        val provider = object : CompletionProvider {
            override val name = "lifecycle-test"
            // Every path performs only this synchronous, explicitly gated local work.
            override val lifetime = CompletionProviderLifetime.SYNCHRONOUS

            override fun complete(prompt: String, model: String): CompletionOutcome {
                workers.add(Thread.currentThread())
                calls.incrementAndGet()
                maximumActive.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                try {
                    if (prompt == "held") {
                        started.countDown()
                        awaitIgnoringInterrupt(finish) { cancelled.countDown() }
                    }
                    return CompletionOutcome.Success("synthetic", 0)
                } finally {
                    active.decrementAndGet()
                    if (prompt == "held") exited.countDown()
                }
            }
        }
        val properties = serviceProperties(provider.name).copy(
            executorThreads = 2,
            globalConcurrency = 1,
            perUserPerMinute = 0,
            globalPerMinute = 0,
            perUserDailyQuota = 0,
            queueTimeout = Duration.ofSeconds(1),
            timeout = if (interruptCaller) Duration.ofSeconds(3) else Duration.ofMillis(200),
        )
        val memory = InMemoryCompletionAdmission(properties, Clock.systemUTC())
        val admission = object : CompletionAdmission {
            override fun acquire(userId: UUID): CompletionPermit {
                val raw = memory.acquire(userId)
                return object : CompletionPermit {
                    override fun activate(): CompletionActivation = raw.activate().also { activations.incrementAndGet() }

                    override fun close() {
                        if (active.get() != 0) releasedWhileActive.set(true)
                        raw.close()
                        if (closes.incrementAndGet() == 2) heldReleased.countDown()
                    }
                }
            }
        }
        val service = service(provider, returningPersistence(userId), admission, properties)
        val result = AtomicReference<CompletionView?>()
        val failure = AtomicReference<Throwable?>()
        val interruptRestored = AtomicBoolean()
        val caller = Thread {
            try {
                result.set(service.create(userId, "held", null))
                interruptRestored.set(Thread.currentThread().isInterrupted)
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        try {
            // The next submission starts a second core worker even while this first worker is idle.
            assertEquals(CompletionStatus.SUCCEEDED, service.create(userId, "warmup", null).status)
            assertEquals(1, closes.get())
            caller.start()
            assertTrue(started.await(2, TimeUnit.SECONDS))
            assertEquals(2, workers.size, "Two actual provider workers, not a single-worker serialization oracle")
            if (interruptCaller) caller.interrupt()
            caller.join(2_000)
            assertFalse(caller.isAlive)
            assertNull(failure.get())
            assertTrue(cancelled.await(2, TimeUnit.SECONDS), "A ignores actual cancellation while retaining physical work")
            val failed = requireNotNull(result.get())
            assertEquals(CompletionStatus.FAILED, failed.status)
            assertEquals(if (interruptCaller) CompletionErrorCode.INTERNAL_COMPLETION_ERROR else CompletionErrorCode.PROVIDER_TIMEOUT, failed.errorCode)
            assertEquals("The completion request could not be completed.", failed.error)
            assertNull(failed.result)
            assertEquals(interruptCaller, interruptRestored.get())
            assertEquals(1, active.get())
            assertEquals(1L, exited.count)
            assertEquals(1, closes.get(), "Only warmup is released; A still owns capacity after its caller exits")
            val denied = assertThrows<ServiceUnavailableException> { service.create(userId, "successor", null) }
            assertEquals("COMPLETION_CONCURRENCY_LIMIT", denied.code)
            assertEquals(1L, denied.retryAfterSeconds)
            assertEquals(2, calls.get())
            assertEquals(2, activations.get())

            finish.countDown()
            assertTrue(exited.await(2, TimeUnit.SECONDS))
            assertTrue(heldReleased.await(2, TimeUnit.SECONDS), "Actual body exit, not canceled Future completion, releases A")
            assertEquals(CompletionStatus.SUCCEEDED, service.create(userId, "successor", null).status)
            assertEquals(3, calls.get())
            assertEquals(3, activations.get())
            assertEquals(3, closes.get())
            assertEquals(0, active.get())
            assertEquals(1, maximumActive.get())
            assertFalse(releasedWhileActive.get())
        } finally {
            finish.countDown()
            if (caller.isAlive) caller.interrupt()
            caller.join(2_000)
            service.shutdown()
            workers.forEach {
                it.join(2_000)
                assertFalse(it.isAlive, "Owned provider worker did not stop")
            }
            assertFalse(caller.isAlive)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `normal publication precedes slow release even when its acknowledgement fails`(releaseFailure: Boolean) {
        val userId = UUID.randomUUID()
        val worker = AtomicReference<Thread>()
        val published = AtomicReference<CompletionView?>()
        val releaseEntered = CountDownLatch(1)
        val releaseReturn = CountDownLatch(1)
        val releaseThread = AtomicReference<Thread>()
        val calls = AtomicInteger()
        val token = AtomicReference<String>()
        val operations = mutableListOf<String>()
        val redis = mock(StringRedisTemplate::class.java) { invocation ->
            if (invocation.method.name == "execute") {
                val args = invocation.rawArguments[2] as Array<*>
                val operation = args[0] as String
                synchronized(operations) { operations.add(operation) }
                when (operation) {
                    "acquire" -> {
                        token.set(args[1] as String)
                        0L
                    }

                    "activate" -> {
                        assertEquals(token.get(), args[1])
                        1L
                    }

                    "release" -> {
                        assertEquals(token.get(), args[1])
                        releaseThread.set(Thread.currentThread())
                        releaseEntered.countDown()
                        releaseReturn.await()
                        if (releaseFailure) throw DataAccessResourceFailureException("private Redis connection detail")
                        1L
                    }

                    else -> error("Unexpected Redis operation")
                }
            } else {
                Answers.RETURNS_DEFAULTS.answer(invocation)
            }
        }
        val properties = serviceProperties("lifecycle-test").copy(queueTimeout = Duration.ofSeconds(1), timeout = Duration.ofMillis(200))
        val provider = recordingProvider(calls) { worker.set(Thread.currentThread()) }
        val service = service(provider, returningPersistence(userId) { published.set(it) }, RedisCompletionAdmission(redis, properties), properties)
        val result = AtomicReference<CompletionView?>()
        val failure = AtomicReference<Throwable?>()
        val caller = Thread {
            try {
                result.set(service.create(userId, "synthetic", null))
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        try {
            caller.start()
            assertTrue(releaseEntered.await(2, TimeUnit.SECONDS))
            val committedCandidate = requireNotNull(published.get()) // Real committed-pair observer remains in the HTTP IT.
            assertEquals(CompletionStatus.SUCCEEDED, committedCandidate.status)
            assertNull(committedCandidate.errorCode)
            assertSame(caller, releaseThread.get(), "Exit-first records body settlement; caller publishes before doing raw release")
            Thread.sleep(properties.timeout.toMillis() + 100)
            assertTrue(caller.isAlive, "Only release is slow, after the provider result was already published")
            assertSame(committedCandidate, published.get())
            releaseReturn.countDown()
            caller.join(2_000)
            assertFalse(caller.isAlive)
            assertNull(failure.get())
            assertSame(committedCandidate, result.get())
            assertEquals(1, calls.get())
            assertEquals(listOf("acquire", "activate", "release"), synchronized(operations) { operations.toList() })
        } finally {
            releaseReturn.countDown()
            caller.join(2_000)
            service.shutdown()
            worker.get()?.let {
                it.join(2_000)
                assertFalse(it.isAlive, "Owned provider worker did not stop")
            }
            assertFalse(caller.isAlive)
        }
    }

    @ParameterizedTest
    @CsvSource("EXPIRED,true", "EXPIRED,false", "UNAVAILABLE,true", "UNAVAILABLE,false")
    fun `activation denial maps 503 only when its sanitized candidate wins`(activation: CompletionActivation, won: Boolean) {
        val candidate = failedView(CompletionErrorCode.PROVIDER_UNAVAILABLE)
        val winner = if (won) candidate else candidate.copy(status = CompletionStatus.SUCCEEDED, result = "earlier winner", error = null, errorCode = null)
        val calls = AtomicInteger()
        val activations = AtomicInteger()
        val closes = AtomicInteger()
        val claimed = AtomicBoolean()
        val worker = AtomicReference<Thread>()
        val released = CountDownLatch(1)
        val persistence = mock(CompletionPersistence::class.java) { invocation ->
            when (invocation.method.name) {
                "createPending" -> candidate.id

                "markRunning" -> {
                    claimed.set(true)
                    true
                }

                "storeOutcome" -> {
                    assertEquals(CompletionStatus.FAILED, invocation.getArgument<CompletionStatus>(1))
                    assertNull(invocation.getArgument<String?>(2))
                    assertEquals(candidate.error, invocation.getArgument<String>(3))
                    assertEquals(CompletionErrorCode.PROVIDER_UNAVAILABLE, invocation.getArgument<CompletionErrorCode>(4))
                    CompletionPublication(winner, won)
                }

                else -> Answers.RETURNS_DEFAULTS.answer(invocation)
            }
        }
        val admission = object : CompletionAdmission {
            override fun acquire(userId: UUID): CompletionPermit = object : CompletionPermit {
                override fun activate(): CompletionActivation {
                    worker.set(Thread.currentThread())
                    assertTrue(claimed.get(), "Activation follows the completed RUNNING persistence call")
                    activations.incrementAndGet()
                    return activation
                }

                override fun close() {
                    closes.incrementAndGet()
                    released.countDown()
                }
            }
        }
        val service = service(recordingProvider(calls), persistence, admission)
        try {
            if (won) {
                val error = assertThrows<ServiceUnavailableException> { service.create(candidate.userId, "synthetic", null) }
                assertEquals(503, error.status.value())
                assertEquals(
                    if (activation == CompletionActivation.EXPIRED) "COMPLETION_CONCURRENCY_LIMIT" else "COMPLETION_COORDINATION_UNAVAILABLE",
                    error.code,
                )
                assertEquals(if (activation == CompletionActivation.EXPIRED) 1L else 5L, error.retryAfterSeconds)
                assertEquals(0, error.suppressed.size)
            } else {
                assertSame(winner, service.create(candidate.userId, "synthetic", null))
            }
            assertTrue(released.await(2, TimeUnit.SECONDS))
            assertEquals(1, activations.get())
            assertEquals(1, closes.get())
            assertEquals(0, calls.get())
        } finally {
            service.shutdown()
            worker.get()?.let {
                it.join(2_000)
                assertFalse(it.isAlive, "Owned provider worker did not stop")
            }
        }
    }

    @ParameterizedTest(name = "activation startup deadline [{index}] interruptCaller={0}")
    @ValueSource(booleans = [false, true])
    fun `activation delay shares the original startup deadline and cancellation still forbids invocation`(interruptCaller: Boolean) {
        val userId = UUID.randomUUID()
        val entered = CountDownLatch(1)
        val finishActivation = CountDownLatch(1)
        val released = CountDownLatch(1)
        val calls = AtomicInteger()
        val activations = AtomicInteger()
        val closes = AtomicInteger()
        val worker = AtomicReference<Thread>()
        val admission = object : CompletionAdmission {
            override fun acquire(userId: UUID): CompletionPermit = object : CompletionPermit {
                override fun activate(): CompletionActivation {
                    worker.set(Thread.currentThread())
                    activations.incrementAndGet()
                    entered.countDown()
                    awaitIgnoringInterrupt(finishActivation)
                    return CompletionActivation.ACTIVATED
                }

                override fun close() {
                    closes.incrementAndGet()
                    released.countDown()
                }
            }
        }
        val properties = serviceProperties("lifecycle-test").copy(
            queueTimeout = if (interruptCaller) Duration.ofSeconds(3) else Duration.ofMillis(200),
            timeout = Duration.ofSeconds(2),
        )
        val service = service(recordingProvider(calls), returningPersistence(userId), admission, properties)
        val result = AtomicReference<CompletionView?>()
        val failure = AtomicReference<Throwable?>()
        val interruptRestored = AtomicBoolean()
        val caller = Thread {
            try {
                result.set(service.create(userId, "synthetic", null))
                interruptRestored.set(Thread.currentThread().isInterrupted)
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        try {
            caller.start()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            if (interruptCaller) caller.interrupt()
            caller.join(2_000)
            assertFalse(caller.isAlive, "The original startup budget must not restart or wait for activation I/O")
            if (interruptCaller) {
                assertNull(failure.get())
                assertEquals(CompletionErrorCode.INTERNAL_COMPLETION_ERROR, requireNotNull(result.get()).errorCode)
                assertTrue(interruptRestored.get())
            } else {
                val error = failure.get() as ServiceUnavailableException
                assertEquals("COMPLETION_OVERLOADED", error.code)
                assertEquals(1L, error.retryAfterSeconds)
            }
            assertEquals(0, closes.get(), "An entered activation boundary still owns the body until it actually exits")
            finishActivation.countDown()
            assertTrue(released.await(2, TimeUnit.SECONDS))
            assertEquals(1, activations.get())
            assertEquals(1, closes.get())
            assertEquals(0, calls.get(), "Even acknowledged activation cannot undo cancellation before authorization")
        } finally {
            finishActivation.countDown()
            if (caller.isAlive) caller.interrupt()
            caller.join(2_000)
            service.shutdown()
            worker.get()?.let {
                it.join(2_000)
                assertFalse(it.isAlive, "Owned provider worker did not stop")
            }
            assertFalse(caller.isAlive)
        }
    }

    private fun service(
        provider: CompletionProvider,
        persistence: CompletionPersistence,
        admission: CompletionAdmission = InMemoryCompletionAdmission(KiraCompletionProperties(globalConcurrency = 1), Clock.systemUTC()),
        properties: KiraCompletionProperties = serviceProperties(provider.name),
    ): CompletionService = CompletionService(
        listOf(provider),
        properties,
        persistence,
        admission,
        mock(KiraMetrics::class.java),
    )

    private fun failingReleaseAdmission(releaseCalls: AtomicInteger): CompletionAdmission {
        val token = AtomicReference<String>()
        val redis = mock(StringRedisTemplate::class.java) { invocation ->
            if (invocation.method.name == "execute") {
                val args = invocation.rawArguments[2] as Array<*>
                when (args[0]) {
                    "acquire" -> {
                        token.set(args[1] as String)
                        0L
                    }

                    "activate" -> {
                        assertEquals(token.get(), args[1])
                        1L
                    }

                    "release" -> {
                        assertEquals(token.get(), args[1])
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

    private fun recordingProvider(calls: AtomicInteger, onCall: () -> Unit = {}): CompletionProvider = object : CompletionProvider {
        override val name = "lifecycle-test"
        // Audited test fake: every return or throw ends all local work.
        override val lifetime = CompletionProviderLifetime.SYNCHRONOUS

        override fun complete(prompt: String, model: String): CompletionOutcome {
            onCall()
            calls.incrementAndGet()
            return CompletionOutcome.Success("synthetic", 0)
        }
    }

    private fun returningPersistence(userId: UUID, onPublication: (CompletionView) -> Unit = {}): CompletionPersistence =
        mock(CompletionPersistence::class.java) { invocation ->
            when (invocation.method.name) {
                "createPending" -> UUID.randomUUID()
                "markRunning" -> true
                "storeOutcome" -> {
                    val view = CompletionView(
                        id = invocation.getArgument(0),
                        userId = userId,
                        provider = "lifecycle-test",
                        model = "configured-model",
                        status = invocation.getArgument(1),
                        result = invocation.getArgument(2),
                        error = invocation.getArgument(3),
                        errorCode = invocation.getArgument(4),
                        createdAt = Instant.EPOCH,
                    )
                    onPublication(view)
                    CompletionPublication(view, won = true)
                }

                else -> Answers.RETURNS_DEFAULTS.answer(invocation)
            }
        }

    private fun awaitIgnoringInterrupt(gate: CountDownLatch, onInterrupt: () -> Unit = {}) {
        var interrupted = false
        while (true) {
            try {
                gate.await()
                if (interrupted) Thread.currentThread().interrupt()
                return
            } catch (_: InterruptedException) {
                interrupted = true
                onInterrupt()
            }
        }
    }

    private fun serviceProperties(provider: String) = KiraCompletionProperties(
        provider = provider,
        defaultModel = "configured-model",
        executorThreads = 1,
        queueCapacity = 1,
        queueTimeout = Duration.ofDays(1),
    )

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
