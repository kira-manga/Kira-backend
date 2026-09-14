package me.manga.kira.backend.common.infrastructure.persistence

import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.withLock

/**
 * Actual platform Threads/stock executor Workers and actual inert/invalid-config Hikari startup.
 * Synthetic creator calls are explicitly MODEL; none of these substitutes for connected pgjdbc/native qualification.
 */
class PoolActorCustodyTest {
    @Test
    fun `installed inert pool has a closed population only after the one actual close and native owner end`() = ActorFixture().use { fixture ->
        assertTrue(fixture.lifecycle.businessReady())
        assertFalse(fixture.lifecycle.isAuthenticPoolCaller())
        val receipt = requireNotNull(fixture.lifecycle.requestShutdown())
        assertFalse(fixture.lifecycle.businessAdmissionOpen())
        assertEquals(PoolShutdownObservation.PENDING, receipt.observe())
        assertFalse(fixture.lifecycle.actorSnapshot().factorySealed, "Requesting close must not forbid shutdown's late assassin.")
        assertEquals(PoolShutdownInvocation.RETURNED, fixture.lifecycle.closePool())
        assertEquals(PoolShutdownObservation.TRACKED_LOCAL_ENDED, receipt.observe())
        assertTrue(fixture.lifecycle.actorSnapshot().factorySealed)
        assertEquals(0, fixture.lifecycle.actorSnapshot().retainedGenerations)
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["factory", "failFast", "jmx", "suspension"])
    fun `unsupported actor profile refuses before replacing any extension or starting a pool`(mode: String) = ActorFixture(install = false).use { fixture ->
        val original = ThreadFactory { task -> Thread(task) }
        when (mode) {
            "factory" -> fixture.pool.threadFactory = original
            "failFast" -> fixture.pool.initializationFailTimeout = 1
            "jmx" -> fixture.pool.isRegisterMbeans = true
            "suspension" -> fixture.pool.isAllowPoolSuspension = true
        }
        assertFalse(fixture.lifecycle.installThreadFactory())
        assertFalse(fixture.lifecycle.installThreadFactory(), "No second actor/factory generation repairs refusal.")
        assertFalse(fixture.pool.isRunning)
        assertFalse(fixture.lifecycle.businessReady())
        assertEquals(PoolActorFault.UNSUPPORTED_PROFILE, fixture.lifecycle.actorSnapshot().firstFailure)
        assertEquals(0, fixture.lifecycle.actorSnapshot().retainedGenerations)
        if (mode == "factory") assertSame(original, fixture.pool.threadFactory)
    }

    @Test
    fun `unauthenticated or null factory invocation refuses with sticky owner fault rather than an untracked Thread`() = ActorFixture().use { fixture ->
        val calls = AtomicInteger()
        assertNull(fixture.emit(Runnable { calls.incrementAndGet() }))
        assertNull(fixture.pool.threadFactory.newThread(null))
        assertFalse(fixture.lifecycle.businessAdmissionOpen())
        assertEquals(PoolActorFault.UNAUTHENTICATED_CREATION, fixture.lifecycle.actorSnapshot().firstFailure)
        assertTrue(fixture.lifecycle.actorSnapshot().factorySealed)
        assertEquals(0, fixture.lifecycle.actorSnapshot().retainedGenerations)
        assertEquals(0, calls.get())
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["incident-first", "hard-first", "operation-hard"])
    fun `MODEL RETURN incident preserves authentic creation and generic or factory hard faults stay dominant`(order: String) = ActorFixture().use { fixture ->
        val acquisition = fixture.creator()
        try {
            assertTrue(acquisition.capture(PhysicalTestConnection().raw)) // MODEL handle; the enclosing creator remains genuinely active.
            val entitlement = requireNotNull(acquisition.prepareLeaseEntitlement())
            val returning = requireNotNull(entitlement.prepareReturn(PersistenceTimeBudget.start(30_000)))
            assertTrue(returning.enter())
            try {
                assertNull(fixture.lifecycle.actorSnapshot().firstFailure)
                assertFalse(fixture.lifecycle.actorSnapshot().factorySealed)
                if (order == "hard-first") assertNull(fixture.pool.threadFactory.newThread(null))
                assertTrue(returning.recordReturnIncidentBeforeEnd())
                assertFalse(fixture.lifecycle.businessReady())
                assertFalse(fixture.lifecycle.businessAdmissionOpen())
                if (order == "incident-first") {
                    assertFalse(fixture.lifecycle.actorSnapshot().factorySealed)
                    val calls = AtomicInteger()
                    val actor = requireNotNull(
                        fixture.emit(
                            Runnable {
                                assertTrue(fixture.lifecycle.isAuthenticPoolCaller())
                                assertFalse(fixture.lifecycle.businessAdmissionOpen())
                                calls.incrementAndGet()
                            },
                        ),
                    )
                    actor.start()
                    awaitActorTermination(actor)
                    assertEquals(1, calls.get())
                    assertNull(fixture.pool.threadFactory.newThread(null)) // Genuine later hard refusal, not another incident classification.
                    assertEquals(1L, fixture.lifecycle.actorSnapshot().retiredGenerations)
                } else if (order == "operation-hard") {
                    assertFalse(fixture.lifecycle.actorSnapshot().factorySealed)
                    assertTrue(returning.failBeforeEnd(), "The exact generic hard API must still seal despite an earlier incident.")
                }
                val first = if (order == "hard-first") PoolActorFault.UNAUTHENTICATED_CREATION else PoolActorFault.BOOKKEEPING_FAILED
                assertEquals(first, fixture.lifecycle.actorSnapshot().firstFailure)
                assertTrue(fixture.lifecycle.actorSnapshot().factorySealed)
                assertTrue(returning.recordReturnIncidentBeforeEnd())
                assertNull(fixture.emit(Runnable { error("An incident must never reopen a hard-sealed factory.") }))
                assertEquals(first, fixture.lifecycle.actorSnapshot().firstFailure)
                assertTrue(fixture.lifecycle.actorSnapshot().factorySealed)
                assertEquals(0, fixture.lifecycle.actorSnapshot().retainedGenerations)
                assertEquals(1L, fixture.lifecycle.actorSnapshot().activeOperations)
                assertSame(returning.frame, PoolCallFrames.current())
                assertFalse(returning.actualFrameEnded() || returning.frame.completion.hasEnded())
            } finally {
                assertTrue(returning.end())
            }
        } finally {
            // Only now may this uninitialized MODEL acquisition record its separate hard startup failure.
            assertTrue(acquisition.end())
        }
    }

    @Test
    fun `ordinary unstarted platform Thread has no inherited app context and foreign or reentrant run cannot execute its delegate twice`() =
        ActorFixture().use { fixture ->
            val inherited = InheritableThreadLocal<String>()
            val inheritedValue = AtomicReference<String?>()
            val calls = AtomicInteger()
            val acquisition = fixture.creator()
            try {
                inherited.set("MODEL application context")
                val actor = requireNotNull(
                    fixture.emit(
                        Runnable {
                            inheritedValue.set(inherited.get())
                            calls.incrementAndGet()
                            Thread.currentThread().run() // Deliberate same-Thread reentry; not a second Worker body.
                        },
                    ),
                )
                assertSame(Thread::class.java, actor.javaClass)
                assertEquals(Thread.State.NEW, actor.state)
                assertFalse(actor.isAlive)
                assertTrue(actor.isDaemon)
                assertFalse(actor.isVirtual)
                actor.run() // Deliberate foreign/direct invocation before its real start.
                assertEquals(0, calls.get())
                actor.start()
                awaitActorTermination(actor)
                assertEquals(1, calls.get())
                assertNull(inheritedValue.get())
                assertEquals(PoolActorFault.UNAUTHENTICATED_ENTRY, fixture.lifecycle.actorSnapshot().firstFailure)
            } finally {
                inherited.remove()
                assertTrue(acquisition.end())
            }
        }

    @Test
    fun `capacity64 reserves every published NEW identity and refuses65 before publication without declaring any NEW inert`() = ActorFixture().use { fixture ->
        val acquisition = fixture.creator()
        lateinit var receipt: PoolLifecycle.ShutdownReceipt
        try {
            repeat(64) {
                val actor = requireNotNull(fixture.emit(Runnable { error("This MODEL published NEW actor must not run.") }))
                assertEquals(Thread.State.NEW, actor.state)
            }
            assertNull(fixture.emit(Runnable { error("Capacity refusal must not emit.") }))
            assertEquals(64, fixture.lifecycle.actorSnapshot().capacity)
            assertEquals(64, fixture.lifecycle.actorSnapshot().retainedGenerations)
            assertEquals(0L, fixture.lifecycle.actorSnapshot().retiredGenerations)
            assertEquals(PoolActorFault.CAPACITY_EXHAUSTED, fixture.lifecycle.actorSnapshot().firstFailure)
            assertFalse(fixture.lifecycle.businessReady())
            receipt = requireNotNull(fixture.lifecycle.requestShutdown())
            assertEquals(PoolShutdownObservation.ACTIVE_ACQUISITION, receipt.observe())
        } finally {
            assertTrue(acquisition.end())
        }
        assertEquals(PoolShutdownInvocation.RETURNED, fixture.lifecycle.closePool())
        assertEquals(PoolShutdownObservation.POOL_ACTORS_UNPROVEN, receipt.observe())
        assertEquals(64, fixture.lifecycle.actorSnapshot().retainedGenerations)
        assertEquals(0L, fixture.lifecycle.actorSnapshot().retiredGenerations, "!isAlive and creator end cannot retire published NEW.")
    }

    @Test
    fun `finite cells can retire more than64 completed generations only after authentic TERMINATED and not alive`() = ActorFixture().use { fixture ->
        val acquisition = fixture.creator()
        val calls = AtomicInteger()
        try {
            repeat(96) {
                val actor = requireNotNull(fixture.emit(Runnable { calls.incrementAndGet() }))
                actor.start()
                awaitActorTermination(actor)
                assertTrue(fixture.lifecycle.actorSnapshot().retainedGenerations <= 1)
            }
            assertEquals(96, calls.get())
            assertEquals(95L, fixture.lifecycle.actorSnapshot().retiredGenerations)
            assertNull(fixture.lifecycle.actorSnapshot().firstFailure)
        } finally {
            // No actual pool initialized in this MODEL creator test, so ending the first call truthfully seals startup failure.
            assertTrue(acquisition.end())
        }
        val receipt = requireNotNull(fixture.lifecycle.requestShutdown())
        assertEquals(PoolShutdownInvocation.RETURNED, fixture.lifecycle.closePool())
        assertEquals(PoolShutdownObservation.UNKNOWN, receipt.observe())
        assertEquals(96L, fixture.lifecycle.actorSnapshot().retiredGenerations)
        assertEquals(0, fixture.lifecycle.actorSnapshot().retainedGenerations)
    }

    @Test
    fun `a late authentic creator may still publish after shutdown admission seal and cannot observe its own actor closure`() = ActorFixture().use { fixture ->
        val acquisition = fixture.creator()
        val observed = AtomicReference<PoolShutdownObservation>()
        val invocation = AtomicReference<PoolShutdownInvocation>()
        try {
            val receipt = requireNotNull(fixture.lifecycle.requestShutdown())
            val actor = requireNotNull(
                fixture.emit(
                    Runnable {
                        assertTrue(fixture.lifecycle.isAuthenticPoolCaller())
                        observed.set(receipt.observe())
                        invocation.set(fixture.lifecycle.closePool())
                    },
                ),
            )
            assertFalse(fixture.lifecycle.businessAdmissionOpen())
            assertFalse(fixture.lifecycle.actorSnapshot().factorySealed)
            actor.start()
            awaitActorTermination(actor)
            assertEquals(PoolShutdownObservation.ACTIVE_POOL_ACTOR, observed.get())
            assertEquals(PoolShutdownInvocation.ACTIVE_POOL_ACTOR, invocation.get())
        } finally {
            assertTrue(acquisition.end())
        }
    }

    @Test
    fun `published before seal and started after creator end stays unproved until the exact emitted Thread actually terminates`() =
        ActorFixture().use { fixture ->
            val acquisition = fixture.creator()
            val calls = AtomicInteger()
            val observed = AtomicReference<PoolShutdownObservation>()
            lateinit var receipt: PoolLifecycle.ShutdownReceipt
            val actor = try {
                val emitted = requireNotNull(
                    fixture.emit(
                        Runnable {
                            assertTrue(fixture.lifecycle.isAuthenticPoolCaller())
                            calls.incrementAndGet()
                            observed.set(receipt.observe())
                        },
                    ),
                )
                assertEquals(Thread.State.NEW, emitted.state)
                assertTrue(fixture.lifecycle.businessAdmissionOpen(), "Publication really precedes the admission seal in this separate case.")
                receipt = requireNotNull(fixture.lifecycle.requestShutdown())
                assertFalse(fixture.lifecycle.businessAdmissionOpen())
                emitted
            } finally {
                // MODEL creator did not initialize Hikari: its genuine end seals an initialization fault, not an actor disposition.
                assertTrue(acquisition.end())
            }
            assertTrue(fixture.lifecycle.actorSnapshot().factorySealed)
            assertEquals(PoolShutdownInvocation.RETURNED, fixture.lifecycle.closePool())
            assertEquals(PoolShutdownObservation.POOL_ACTORS_UNPROVEN, receipt.observe())
            assertEquals(1, fixture.lifecycle.actorSnapshot().retainedGenerations)
            assertEquals(0L, fixture.lifecycle.actorSnapshot().retiredGenerations)
            actor.start() // Neither creator end, !isAlive nor either seal revoked this previously published start entitlement.
            awaitActorTermination(actor)
            assertEquals(1, calls.get())
            assertEquals(PoolShutdownObservation.ACTIVE_POOL_ACTOR, observed.get())
            assertEquals(PoolShutdownObservation.UNKNOWN, receipt.observe(), "The authentic termination does not erase the earlier MODEL startup fault.")
            assertEquals(1L, fixture.lifecycle.actorSnapshot().retiredGenerations)
            assertEquals(0, fixture.lifecycle.actorSnapshot().retainedGenerations)
        }

    @Test
    fun `stock executor replacement from complete Worker finally retains its authentic creator even after the task throws`() = ActorFixture().use { fixture ->
        OwnedCallerTestScope().use { scope ->
            val firstHeld = scope.gate()
            val secondHeld = scope.gate()
            val firstThread = AtomicReference<Thread>()
            val secondThread = AtomicReference<Thread>()
            val executor = ThreadPoolExecutor(1, 1, 5, TimeUnit.SECONDS, LinkedBlockingQueue(), fixture.pool.threadFactory)
            fixture.own(executor)
            val acquisition = fixture.creator()
            try {
                executor.execute {
                    firstThread.set(Thread.currentThread())
                    firstHeld.hold()
                    error("MODEL task failure, real Worker replacement finally.")
                }
                firstHeld.awaitEntered()
                executor.execute {
                    secondThread.set(Thread.currentThread())
                    assertTrue(fixture.lifecycle.isAuthenticPoolCaller())
                    secondHeld.hold()
                }
                firstHeld.release()
                secondHeld.awaitEntered()
                awaitActorTermination(requireNotNull(firstThread.get()))
                assertNotNull(secondThread.get(), "A complete Worker, not just the throwing task, authenticates replacement creation.")
                assertFalse(firstThread.get() === secondThread.get())
                assertEquals(2, fixture.lifecycle.actorSnapshot().retainedGenerations)
                assertEquals(PoolActorFault.WORKER_FAILED, fixture.lifecycle.actorSnapshot().firstFailure)
                assertFalse(fixture.lifecycle.businessAdmissionOpen())
            } finally {
                firstHeld.release()
                secondHeld.release()
                assertTrue(acquisition.end())
            }
        }
    }

    @Test
    fun `stock caller-runs work stays in its actual acquisition extent even though no factory callback occurs`() = ActorFixture().use { fixture ->
        val executor = ThreadPoolExecutor(1, 1, 5, TimeUnit.SECONDS, LinkedBlockingQueue(), fixture.pool.threadFactory, ThreadPoolExecutor.CallerRunsPolicy())
        fixture.own(executor)
        val acquisition = fixture.creator()
        try {
            val caller = Thread.currentThread()
            var ran = false
            // Exercise the stock rejection handler's actual inline route without replacing any product executor.
            executor.rejectedExecutionHandler.rejectedExecution(
                Runnable {
                    assertSame(caller, Thread.currentThread())
                    assertTrue(fixture.lifecycle.isAuthenticPoolCaller())
                    assertEquals(1L, fixture.lifecycle.activeAcquisitions())
                    ran = true
                },
                executor,
            )
            assertTrue(ran)
            assertEquals(0, fixture.lifecycle.actorSnapshot().retainedGenerations)
            executor.shutdown()
            executor.rejectedExecutionHandler.rejectedExecution(Runnable { error("Stock shutdown caller-runs must discard this task.") }, executor)
            assertEquals(1L, fixture.lifecycle.activeAcquisitions(), "A handler return is not a task completion/creator receipt.")
        } finally {
            assertTrue(acquisition.end())
        }
    }

    @Test
    fun `real stock lazy initialization must finish before the first close and failure cannot retry a fresh constructor`() = ActorFixture().use { fixture ->
        OwnedCallerTestScope().use { scope ->
            val beforeInitialize = scope.gate()
            val caller = scope.launch {
                val acquisition = fixture.creator()
                try {
                    beforeInitialize.hold()
                    // Actual stock Hikari validation failure, no DataSource/driver/service, not a MODEL close override.
                    assertThrows<IllegalArgumentException> { fixture.pool.connection }
                    true
                } finally {
                    check(acquisition.end())
                }
            }
            beforeInitialize.awaitEntered()
            val receipt = requireNotNull(fixture.lifecycle.requestShutdown())
            assertEquals(PoolShutdownInvocation.INITIALIZATION_PENDING, fixture.lifecycle.closePool())
            assertFalse(fixture.pool.isClosed, "Do not consume Hikari's one-shot flag before the admitted lazy call actually ends.")
            assertEquals(PersistenceTerminalCall.NOT_INVOKED, fixture.lifecycle.firstCloseOutcome())
            assertEquals(PoolShutdownObservation.PENDING, receipt.observe())
            beforeInitialize.release()
            assertTrue(caller.value())
            assertEquals(PoolActorFault.INITIALIZATION_FAILED, fixture.lifecycle.actorSnapshot().firstFailure)
            assertFalse(fixture.lifecycle.prepareAcquisition(PersistenceTimeBudget.start(30_000)).enter())
            assertEquals(PoolShutdownInvocation.RETURNED, fixture.lifecycle.closePool())
            assertTrue(fixture.pool.isClosed)
            assertEquals(PoolShutdownObservation.UNKNOWN, receipt.observe())
        }
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["F", "G"])
    fun `factory never publishes under a held ownership lock and its seal recheck needs no lock or profile callback`(held: String) =
        ActorFixture().use { fixture ->
            val root = actorField(fixture.owner, "root") as PersistenceJdbcDriverRoot
            val binding = actorField(root.ordinary, "binding") as PersistencePhysicalFactoryBinding
            val lock = if (held == "F") binding.rendezvous.lock else binding.ledger.lock
            val acquisition = fixture.creator()
            try {
                lock.withLock {
                    assertTrue(fixture.lifecycle.businessAdmissionOpen())
                    assertFalse(fixture.lifecycle.isAuthenticPoolCaller())
                    assertNull(fixture.emit(Runnable { error("Ownership-locked factory callback must not publish.") }))
                    assertFalse(fixture.lifecycle.businessAdmissionOpen())
                }
                assertEquals(PoolActorFault.UNAUTHENTICATED_CREATION, fixture.lifecycle.actorSnapshot().firstFailure)
                assertEquals(0, fixture.lifecycle.actorSnapshot().retainedGenerations)
            } finally {
                assertTrue(acquisition.end())
            }
        }
}

/** All cleanup ownership is retained before any explicit fixture start/submit. NEW is never started by cleanup to obtain a receipt. */
private class ActorFixture(install: Boolean = true) : AutoCloseable {
    val pool = HikariDataSource().apply { initializationFailTimeout = -1 }
    val owner = PersistenceJdbcLifecycleOwner(pgProbeEndpoint(1), 1, PersistencePathStyle.POSIX)
    val lifecycle = PoolLifecycle(pool, owner)
    private val explicitThreads = CopyOnWriteArrayList<Thread>()
    private val executors = mutableListOf<ThreadPoolExecutor>()

    init {
        if (install) check(lifecycle.installThreadFactory())
    }

    fun creator(): PoolLifecycle.Acquisition = lifecycle.prepareAcquisition(PersistenceTimeBudget.start(30_000)).also { check(it.enter()) }

    fun emit(body: Runnable): Thread? = pool.threadFactory.newThread(body)?.also { explicitThreads.add(it) }

    fun own(executor: ThreadPoolExecutor) {
        executors.add(executor)
    }

    override fun close() {
        val stopResults = executors.map { runCatching { it.shutdownNow() } }
        val executorResults = executors.map { runCatching { check(it.awaitTermination(5, TimeUnit.SECONDS)) } }
        val threadResults = explicitThreads.map { thread ->
            runCatching { if (thread.state !== Thread.State.NEW) awaitActorTermination(thread) }
        }
        val poolResult = runCatching {
            val invocation = lifecycle.closePool()
            check(invocation === PoolShutdownInvocation.RETURNED || invocation === PoolShutdownInvocation.ALREADY_CLAIMED)
        }
        val ownerResult = runCatching {
            owner.requestShutdown() // Still attempt owned native-shell cleanup if a pool/Thread cleanup failed.
            check(owner.observeShutdown() === PersistenceLifecycleObservation.TRACKED_LOCAL_ENDED)
        }
        val actorResult = runCatching {
            val receipt = requireNotNull(lifecycle.requestShutdown())
            val budget = PersistenceTimeBudget.start(5_000)
            var observation = receipt.observe()
            while (observation === PoolShutdownObservation.PENDING) {
                check(persistenceFactoryRemainingMillis(budget) > 0L) { "Owned test actor completion stayed pending." }
                LockSupport.parkNanos(1_000_000)
                observation = receipt.observe() // Each product observation still uses the original, never-replenished shutdown budget.
            }
            check(
                observation === PoolShutdownObservation.TRACKED_LOCAL_ENDED || observation === PoolShutdownObservation.UNKNOWN ||
                    observation === PoolShutdownObservation.POOL_ACTORS_UNPROVEN,
            )
            // Executor termination alone is insufficient: its last Worker/handler tail must have actually terminated too.
            // NEW remains retained/unstarted; conservative failed/unproved receipts are not promoted to pool/native success.
        }
        stopResults.forEach { it.getOrThrow() }
        executorResults.forEach { it.getOrThrow() }
        threadResults.forEach { it.getOrThrow() }
        poolResult.getOrThrow()
        ownerResult.getOrThrow()
        actorResult.getOrThrow()
    }
}

private fun awaitActorTermination(thread: Thread) {
    val budget = PersistenceTimeBudget.start(5_000)
    while (thread.state !== Thread.State.TERMINATED || thread.isAlive) {
        check(persistenceFactoryRemainingMillis(budget) > 0L) { "Owned test actor did not terminate." }
        LockSupport.parkNanos(1_000_000)
    }
}

private fun actorField(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).let { field ->
    field.isAccessible = true // Test-only access to our own owner locks, never JDK/Hikari/native private state.
    field.get(target)
}
