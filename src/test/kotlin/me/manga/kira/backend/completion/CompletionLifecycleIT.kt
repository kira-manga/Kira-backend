package me.manga.kira.backend.completion

import jakarta.persistence.EntityManager
import me.manga.kira.backend.common.exception.ServiceUnavailableException
import me.manga.kira.backend.completion.application.BoundedCompletionExecutor
import me.manga.kira.backend.completion.application.CompletionAdmission
import me.manga.kira.backend.completion.application.CompletionPermit
import me.manga.kira.backend.completion.application.CompletionPersistence
import me.manga.kira.backend.completion.application.CompletionPublication
import me.manga.kira.backend.completion.application.CompletionRetentionService
import me.manga.kira.backend.completion.application.CompletionService
import me.manga.kira.backend.completion.domain.CompletionErrorCode
import me.manga.kira.backend.completion.domain.CompletionOutcome
import me.manga.kira.backend.completion.domain.CompletionProvider
import me.manga.kira.backend.completion.domain.CompletionRequestPage
import me.manga.kira.backend.completion.domain.CompletionRequestRecord
import me.manga.kira.backend.completion.domain.CompletionRequestRepository
import me.manga.kira.backend.completion.domain.CompletionResultRecord
import me.manga.kira.backend.completion.domain.CompletionResultRepository
import me.manga.kira.backend.completion.domain.CompletionStatus
import me.manga.kira.backend.completion.domain.CompletionView
import me.manga.kira.backend.config.KiraCompletionProperties
import me.manga.kira.backend.observability.KiraMetrics
import me.manga.kira.backend.support.AbstractIntegrationTest
import me.manga.kira.backend.user.domain.Role
import me.manga.kira.backend.user.domain.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Answers
import org.mockito.Mockito.mock
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Gates delegate actual writes to the Spring proxy; no test method owns an enclosing transaction. */
@Import(CompletionLifecycleTestConfig::class)
@Timeout(30)
class CompletionLifecycleIT : AbstractIntegrationTest() {
    @Autowired
    private lateinit var persistence: CompletionPersistence

    @Autowired
    private lateinit var retention: CompletionRetentionService

    @Autowired
    private lateinit var users: UserRepository

    @Autowired
    private lateinit var requests: LifecycleRequests

    @Autowired
    private lateinit var results: LifecycleResults

    enum class CancellationPoint { STARTUP_TIMEOUT, BEFORE_CLAIM, AFTER_COMMIT }

    @ParameterizedTest
    @EnumSource(CancellationPoint::class)
    fun `cancellation wins delayed RUNNING work without relying on its interrupt bit`(point: CancellationPoint) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val id = AtomicReference<UUID>()
        val claimed = AtomicReference<Boolean>()
        val failure = AtomicReference<Throwable>()
        val restored = AtomicBoolean()
        val persistedClear = AtomicBoolean()
        lateinit var owned: ServiceFixture
        val forwarding = forwardingPersistence(
            onPending = id::set,
            claim = { requestId ->
                owned.worker.set(Thread.currentThread())
                if (point == CancellationPoint.AFTER_COMMIT) claimed.set(persistence.markRunning(requestId))
                entered.countDown()
                awaitIgnoringInterrupt(release)
                if (point != CancellationPoint.AFTER_COMMIT) claimed.set(persistence.markRunning(requestId))
                claimed.get()
            },
            onOutcome = { persistedClear.set(!Thread.currentThread().isInterrupted) },
        )
        owned = ServiceFixture(forwarding, Duration.ofSeconds(2))
        val user = newUser()
        val caller = Thread {
            try {
                owned.service.create(user, "synthetic", null)
            } catch (ex: Throwable) {
                failure.set(ex)
            } finally {
                restored.set(Thread.currentThread().isInterrupted)
                done.countDown()
            }
        }
        try {
            caller.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            if (point != CancellationPoint.STARTUP_TIMEOUT) caller.interrupt()
            assertTrue(done.await(10, TimeUnit.SECONDS), "Caller did not finish its terminal transaction")
            if (point == CancellationPoint.STARTUP_TIMEOUT) {
                assertTrue(failure.get() is ServiceUnavailableException)
            } else {
                assertNull(failure.get())
                assertTrue(restored.get())
            }
            assertTrue(persistedClear.get())
            val requestId = checkNotNull(id.get())
            val terminal = checkNotNull(snapshot(requestId))
            assertEquals("FAILED", terminal["status"])
            assertEquals(
                if (point == CancellationPoint.STARTUP_TIMEOUT) "PROVIDER_UNAVAILABLE" else "INTERNAL_COMPLETION_ERROR",
                terminal["error_code"],
            )

            release.countDown()
            owned.drain() // A same-worker sentinel acknowledges actual Callable exit, not canceled Future completion.
            assertEquals(point == CancellationPoint.AFTER_COMMIT, claimed.get())
            assertEquals(0, owned.calls.get())
            assertEquals(terminal, snapshot(requestId))
            assertPair(requestId)
        } finally {
            release.countDown()
            if (caller.isAlive) caller.interrupt()
            owned.close()
            caller.join(5_000)
            assertFalse(caller.isAlive)
        }
    }

    @Test
    fun `one legal terminal publisher wins and a stale start cannot regress it`() {
        val id = pending()
        assertTrue(persistence.markRunning(id))
        val start = CyclicBarrier(3)
        val callers = Executors.newFixedThreadPool(2)
        try {
            val success = callers.submit(Callable {
                start.await()
                publish(id, CompletionStatus.SUCCEEDED)
            })
            val failure = callers.submit(Callable {
                start.await()
                publish(id, CompletionStatus.FAILED)
            })
            start.await(5, TimeUnit.SECONDS)
            val publications = listOf(success.get(5, TimeUnit.SECONDS), failure.get(5, TimeUnit.SECONDS))

            assertEquals(1, publications.count { it.won })
            assertEquals(publications[0].view, publications[1].view)
            assertPair(id)
            val terminal = snapshot(id)
            assertFalse(persistence.markRunning(id))
            assertFalse(publish(id, CompletionStatus.SUCCEEDED).won)
            assertFalse(publish(id, CompletionStatus.FAILED).won)
            assertEquals(terminal, snapshot(id))
        } finally {
            callers.shutdownNow()
            assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `pending failure is legal but pending success is not`() {
        val id = pending()
        val before = snapshot(id)
        assertThrows<IllegalStateException> { publish(id, CompletionStatus.SUCCEEDED) }
        assertEquals(before, snapshot(id))
        assertPair(id)

        assertTrue(publish(id, CompletionStatus.FAILED).won)
        val terminal = snapshot(id)
        assertFalse(persistence.markRunning(id))
        assertEquals(terminal, snapshot(id))
        assertPair(id)
    }

    @Test
    fun `a delayed duplicate start loses to a committed legal success`() {
        val id = pending()
        assertTrue(persistence.markRunning(id)) // Success is never manufactured directly from PENDING.
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val caller = Executors.newSingleThreadExecutor()
        try {
            val lateStart = caller.submit(Callable {
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS)) // Outside the real transaction and row lock.
                persistence.markRunning(id)
            })
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(publish(id, CompletionStatus.SUCCEEDED).won)
            val terminal = snapshot(id)
            release.countDown()

            assertFalse(lateStart.get(5, TimeUnit.SECONDS))
            assertEquals(terminal, snapshot(id))
            assertPair(id)
        } finally {
            release.countDown()
            caller.shutdownNow()
            assertTrue(caller.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `failure after a real flushed outcome rolls back both terminal state and result`() {
        val id = pending()
        assertTrue(persistence.markRunning(id))
        val before = snapshot(id)
        results.failAfterFlush.set(true)
        try {
            assertThrows<SyntheticResultFailure> { publish(id, CompletionStatus.SUCCEEDED) }
            assertEquals(before, snapshot(id))
            assertPair(id)
            assertTrue(publish(id, CompletionStatus.SUCCEEDED).won)
        } finally {
            results.failAfterFlush.set(false)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `a missing or mismatched preexisting winner is rejected without repair`(missingResult: Boolean) {
        val id = pending()
        assertTrue(persistence.markRunning(id))
        assertTrue(publish(id, CompletionStatus.SUCCEEDED).won)
        // Explicit legacy-corruption fixtures, not legal lifecycle transitions or a repair protocol.
        if (missingResult) {
            jdbcTemplate.update("DELETE FROM completion_results WHERE request_id = ?", id)
        } else {
            jdbcTemplate.update("UPDATE completion_requests SET status = 'FAILED' WHERE id = ?", id)
        }
        val before = snapshot(id)

        assertThrows<IllegalStateException> { publish(id, CompletionStatus.FAILED) }
        assertEquals(before, snapshot(id))
    }

    @Test
    fun `retention deletion defeats a worker paused before the real startup proxy`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val id = AtomicReference<UUID>()
        val failure = AtomicReference<Throwable>()
        lateinit var owned: ServiceFixture
        owned = ServiceFixture(
            forwardingPersistence(
                onPending = id::set,
                claim = {
                    owned.worker.set(Thread.currentThread())
                    entered.countDown()
                    awaitIgnoringInterrupt(release)
                    persistence.markRunning(it)
                },
            ),
            Duration.ofMinutes(1),
        )
        val user = newUser()
        val caller = Thread {
            try {
                owned.service.create(user, "synthetic", null)
            } catch (ex: Throwable) {
                failure.set(ex)
            }
        }
        try {
            caller.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val requestId = checkNotNull(id.get())
            age(requestId)
            assertEquals(1, retention.cleanExpired())
            release.countDown()
            caller.join(5_000)
            assertFalse(caller.isAlive)
            owned.drain()

            assertTrue(failure.get() is IllegalStateException) // Missing is not a fabricated terminal winner.
            assertEquals(0, owned.calls.get())
            assertNull(snapshot(requestId))
            assertPair(requestId)
        } finally {
            release.countDown()
            if (caller.isAlive) caller.interrupt()
            owned.close()
            caller.join(5_000)
            assertFalse(caller.isAlive)
        }
    }

    @Test
    fun `retention and a terminal publisher finish without inverted result-request locks`() {
        val id = pending()
        assertTrue(persistence.markRunning(id))
        age(id)
        val start = CyclicBarrier(3)
        val callers = Executors.newFixedThreadPool(2)
        try {
            val publisher = callers.submit(Callable {
                start.await()
                runCatching { publish(id, CompletionStatus.SUCCEEDED) }
            })
            val cleanup = callers.submit(Callable {
                start.await()
                retention.cleanExpired()
            })
            start.await(5, TimeUnit.SECONDS)
            val publication = publisher.get(5, TimeUnit.SECONDS)
            assertEquals(1, cleanup.get(5, TimeUnit.SECONDS))
            if (publication.isSuccess) {
                assertTrue(publication.getOrThrow().won)
            } else {
                assertTrue(publication.exceptionOrNull() is IllegalStateException)
            }
            assertNull(snapshot(id))
            assertPair(id)
        } finally {
            callers.shutdownNow()
            assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `real proxy read preserves one request-outcome snapshot across a concurrent commit`(list: Boolean) {
        val user = newUser()
        val id = persistence.createPending(user, "test", "model", "synthetic")
        assertTrue(persistence.markRunning(id))
        assertTrue(AopUtils.isAopProxy(persistence))
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
        val firstRead = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        requests.afterRead.set {
            firstRead.countDown()
            assertTrue(releaseRead.await(5, TimeUnit.SECONDS))
        }
        val caller = Executors.newSingleThreadExecutor()
        fun read(): CompletionView = if (list) persistence.listViews(user, 0, 20).items.single() else checkNotNull(persistence.findView(id))
        try {
            val observed = caller.submit(Callable { read() })
            assertTrue(firstRead.await(5, TimeUnit.SECONDS))
            assertTrue(publish(id, CompletionStatus.SUCCEEDED).won) // Commits while the reader still holds its snapshot.
            releaseRead.countDown()
            val oldView = observed.get(5, TimeUnit.SECONDS)

            assertEquals(CompletionStatus.RUNNING, oldView.status)
            assertNull(oldView.result)
            assertNull(oldView.error)
            assertNull(oldView.errorCode)
            val fresh = read()
            assertEquals(CompletionStatus.SUCCEEDED, fresh.status)
            assertEquals("synthetic result", fresh.result)
        } finally {
            requests.afterRead.set(null)
            releaseRead.countDown()
            caller.shutdownNow()
            assertTrue(caller.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun pending(): UUID {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
        return persistence.createPending(newUser(), "test", "model", "synthetic")
    }

    private fun newUser(): UUID = users.create("lifecycle-${UUID.randomUUID()}@example.test", "{noop}unused", Role.USER).id

    private fun publish(id: UUID, status: CompletionStatus): CompletionPublication = persistence.storeOutcome(
        id,
        status,
        result = if (status == CompletionStatus.SUCCEEDED) "synthetic result" else null,
        error = if (status == CompletionStatus.FAILED) "The completion request could not be completed." else null,
        errorCode = if (status == CompletionStatus.FAILED) CompletionErrorCode.PROVIDER_TIMEOUT else null,
        latencyMs = 1,
    )

    private fun age(id: UUID) {
        jdbcTemplate.update("UPDATE completion_requests SET created_at = ? WHERE id = ?", Timestamp.from(Instant.now().minus(Duration.ofDays(8))), id)
    }

    private fun snapshot(id: UUID): Map<String, Any?>? = jdbcTemplate.queryForList(
        "SELECT q.status, q.updated_at, r.id AS result_id, r.result, r.error, r.error_code, r.created_at AS result_at " +
            "FROM completion_requests q LEFT JOIN completion_results r ON r.request_id = q.id WHERE q.id = ?",
        id,
    ).singleOrNull()

    private fun assertPair(id: UUID) {
        val row = snapshot(id)
        val count = jdbcTemplate.queryForObject("SELECT count(*) FROM completion_results WHERE request_id = ?", Int::class.java, id)
        if (row == null) {
            assertEquals(0, count)
            return
        }
        when (row["status"]) {
            "PENDING", "RUNNING" -> assertEquals(0, count)
            "SUCCEEDED" -> {
                assertEquals(1, count)
                assertNotNull(row["result"])
                assertNull(row["error"])
                assertNull(row["error_code"])
            }
            "FAILED" -> {
                assertEquals(1, count)
                assertNull(row["result"])
                assertNotNull(row["error"])
                assertNotNull(row["error_code"])
            }
            else -> error("Unexpected completion state")
        }
    }

    private fun forwardingPersistence(
        onPending: (UUID) -> Unit = {},
        claim: (UUID) -> Boolean = persistence::markRunning,
        onOutcome: () -> Unit = {},
    ): CompletionPersistence = mock(CompletionPersistence::class.java) { invocation ->
        when (invocation.method.name) {
            "createPending" -> persistence.createPending(
                invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2), invocation.getArgument(3),
            ).also(onPending)
            "markRunning" -> claim(invocation.getArgument(0))
            "storeOutcome" -> {
                onOutcome()
                persistence.storeOutcome(
                    invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2),
                    invocation.getArgument(3), invocation.getArgument(4), invocation.getArgument(5),
                )
            }
            else -> Answers.RETURNS_DEFAULTS.answer(invocation)
        }
    }

    private fun awaitIgnoringInterrupt(release: CountDownLatch) {
        while (true) {
            try {
                release.await()
                Thread.interrupted()
                return
            } catch (ignored: InterruptedException) {
                // Model a JDBC/scheduling boundary that does not abort and clears cancellation.
            }
        }
    }

    private class ServiceFixture(persistence: CompletionPersistence, queueTimeout: Duration) : AutoCloseable {
        val calls = AtomicInteger()
        val worker = AtomicReference<Thread>()
        private lateinit var executor: BoundedCompletionExecutor
        private val provider = object : CompletionProvider {
            override val name = "lifecycle-test"

            override fun complete(prompt: String, model: String): CompletionOutcome {
                check(!TransactionSynchronizationManager.isActualTransactionActive()) { "Provider must run outside a transaction" }
                calls.incrementAndGet()
                return CompletionOutcome.Success("synthetic", 0)
            }
        }
        val service = CompletionService(
            listOf(provider),
            KiraCompletionProperties(
                provider = provider.name,
                defaultModel = "configured-model",
                executorThreads = 1,
                queueCapacity = 1,
                queueTimeout = queueTimeout,
            ),
            persistence,
            object : CompletionAdmission {
                override fun acquire(userId: UUID): CompletionPermit = CompletionPermit {}
            },
            mock(KiraMetrics::class.java) { invocation ->
                if (invocation.method.name == "bindCompletionExecutor") executor = invocation.getArgument(0)
                Answers.RETURNS_DEFAULTS.answer(invocation)
            },
        )

        fun drain() {
            worker.set(executor.submit(Callable { Thread.currentThread() }).get(5, TimeUnit.SECONDS))
        }

        override fun close() {
            service.shutdown()
            worker.get()?.let {
                it.join(5_000)
                assertFalse(it.isAlive, "Owned provider worker did not exit")
            }
        }
    }
}

@TestConfiguration
class CompletionLifecycleTestConfig {
    @Bean
    @Primary
    fun lifecycleRequests(@Qualifier("jpaCompletionRequestRepositoryAdapter") delegate: CompletionRequestRepository) = LifecycleRequests(delegate)

    @Bean
    @Primary
    fun lifecycleResults(
        @Qualifier("jpaCompletionResultRepositoryAdapter") delegate: CompletionResultRepository,
        entityManager: EntityManager,
    ) = LifecycleResults(delegate, entityManager)
}

class LifecycleRequests(private val delegate: CompletionRequestRepository) : CompletionRequestRepository by delegate {
    val afterRead = AtomicReference<(() -> Unit)?>()

    override fun findById(id: UUID): CompletionRequestRecord? = delegate.findById(id).also { afterRead.getAndSet(null)?.invoke() }

    override fun findPageByUser(userId: UUID, page: Int, size: Int): CompletionRequestPage =
        delegate.findPageByUser(userId, page, size).also { afterRead.getAndSet(null)?.invoke() }
}

class LifecycleResults(private val delegate: CompletionResultRepository, private val entityManager: EntityManager) : CompletionResultRepository by delegate {
    val failAfterFlush = AtomicBoolean()

    override fun insert(result: CompletionResultRecord) {
        delegate.insert(result)
        if (failAfterFlush.compareAndSet(true, false)) {
            entityManager.flush()
            throw SyntheticResultFailure()
        }
    }
}

class SyntheticResultFailure : RuntimeException("Synthetic failure after flushed completion outcome")
