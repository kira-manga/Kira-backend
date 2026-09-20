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
import org.mockito.Mockito.mock
import org.springframework.jdbc.datasource.AbstractDataSource
import java.sql.Connection
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Delayed
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
    @ValueSource(strings = ["factory", "failFast", "jmx", "suspension", "scheduler"])
    fun `unsupported actor profile refuses before replacing any extension or starting a pool`(mode: String) = ActorFixture(install = false).use { fixture ->
        val original = ThreadFactory { task -> Thread(task) }
        when (mode) {
            "factory" -> fixture.pool.threadFactory = original
            "failFast" -> fixture.pool.initializationFailTimeout = 1
            "jmx" -> fixture.pool.isRegisterMbeans = true
            "suspension" -> fixture.pool.isAllowPoolSuspension = true
            "scheduler" -> fixture.pool.scheduledExecutor = ModelPoolScheduler().also(fixture::ownExternal)
        }
        val configuredScheduler = fixture.pool.scheduledExecutor
        assertFalse(fixture.lifecycle.installThreadFactory())
        assertFalse(fixture.lifecycle.installThreadFactory(), "No second actor/factory generation repairs refusal.")
        assertFalse(fixture.pool.isRunning)
        assertFalse(fixture.lifecycle.businessReady())
        assertEquals(PoolActorFault.UNSUPPORTED_PROFILE, fixture.lifecycle.actorSnapshot().firstFailure)
        assertEquals(0, fixture.lifecycle.actorSnapshot().retainedGenerations)
        if (mode == "factory") assertSame(original, fixture.pool.threadFactory)
        if (mode == "scheduler") assertSame(configuredScheduler, fixture.pool.scheduledExecutor)
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

/** External scheduler registrations and callback extents; MODEL seams do not claim connected/native qualification. */
class PoolScheduledTaskCustodyTest {
    @Test
    fun `ordinary scheduler owns callback not shared Worker or afterExecute and leaves unrelated work alive after close`() {
        val lifecycle = AtomicReference<PoolLifecycle>()
        val leakedAuthority = AtomicBoolean()
        val scheduler = object : ScheduledThreadPoolExecutor(1) {
            override fun afterExecute(runnable: Runnable, failure: Throwable?) {
                if (lifecycle.get()?.isAuthenticPoolCaller() == true || PoolActorCustody.currentThreadOwnsActorFrame()) leakedAuthority.set(true)
                super.afterExecute(runnable, failure)
            }
        }.apply { removeOnCancelPolicy = false }
        ActorFixture(ordinary = true, scheduler = scheduler).use { fixture ->
            fixture.ownExternal(scheduler)
            lifecycle.set(fixture.lifecycle)
            val callbackEnded = CountDownLatch(1)
            val callbackThread = AtomicReference<Thread>()
            val emitted = AtomicReference<Thread>()
            val failure = AtomicReference<Throwable>()
            val acquisition = fixture.creator()
            try {
                val scheduled = fixture.pool.scheduledExecutor.scheduleWithFixedDelay(
                    {
                        try {
                            callbackThread.set(Thread.currentThread())
                            assertTrue(fixture.lifecycle.isAuthenticPoolCaller())
                            val receipt = requireNotNull(fixture.lifecycle.requestShutdown())
                            assertEquals(PoolShutdownObservation.ACTIVE_POOL_ACTOR, receipt.observe())
                            assertEquals(PoolShutdownInvocation.ACTIVE_POOL_ACTOR, fixture.lifecycle.closePool())
                            assertFalse(fixture.lifecycle.actorSnapshot().factorySealed)
                            emitted.set(requireNotNull(fixture.emit(Runnable { assertTrue(fixture.lifecycle.isAuthenticPoolCaller()) })))
                            emitted.get().start() // A task's genuine creator extent may emit a Hikari-owned Worker after the business seal.
                        } catch (problem: Throwable) {
                            failure.set(problem)
                            throw problem
                        } finally {
                            callbackEnded.countDown()
                        }
                    },
                    0,
                    1,
                    TimeUnit.DAYS,
                )
                assertTrue(callbackEnded.await(5, TimeUnit.SECONDS))
                failure.get()?.let { throw it }
                assertTrue(scheduled.cancel(false))
                awaitActorTermination(requireNotNull(emitted.get()))
            } finally {
                assertTrue(acquisition.end()) // MODEL creator never initialized Hikari: retain that separate UNKNOWN.
            }
            assertEquals(PoolShutdownInvocation.RETURNED, fixture.lifecycle.closePool())
            val sentinel = scheduler.submit<Thread> {
                assertFalse(fixture.lifecycle.isAuthenticPoolCaller())
                assertFalse(PoolActorCustody.currentThreadOwnsActorFrame())
                Thread.currentThread()
            }.get(5, TimeUnit.SECONDS)
            assertSame(callbackThread.get(), sentinel)
            assertFalse(leakedAuthority.get(), "No task authority may escape into an external Worker's afterExecute.")
            assertFalse(scheduler.isShutdown)
            assertFalse(scheduler.removeOnCancelPolicy)
            assertTrue(scheduler.executeExistingDelayedTasksAfterShutdownPolicy)
            assertEquals(1, scheduler.corePoolSize)
            assertEquals(0, requireNotNull(fixture.lifecycle.actorSnapshot().scheduledTasks).retained)
        }
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["before-entry", "body", "finally"])
    fun `ordinary scheduler cancelled or done Future is not actual callback exit`(mode: String) {
        val scheduler = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = false }
        ActorFixture(ordinary = true, scheduler = scheduler).use { fixture ->
            fixture.ownExternal(scheduler)
            OwnedCallerTestScope().use { scope ->
                val held = scope.gate()
                val calls = AtomicInteger()
                val failure = AtomicReference<Throwable>()
                val acquisition = fixture.creator()
                try {
                    val scheduled = fixture.pool.scheduledExecutor.schedule(
                        Runnable {
                            try {
                                assertTrue(fixture.lifecycle.isAuthenticPoolCaller())
                                calls.incrementAndGet()
                                try {
                                    if (mode == "body") held.hold()
                                } finally {
                                    if (mode == "finally") held.hold()
                                }
                            } catch (problem: Throwable) {
                                failure.set(problem)
                                throw problem
                            }
                        },
                        if (mode == "before-entry") 1 else 0,
                        TimeUnit.DAYS,
                    )
                    if (mode != "before-entry") held.awaitEntered()
                    assertTrue(scheduled.cancel(false))
                    assertTrue(scheduled.isDone)
                    assertTrue(scheduled.isCancelled)
                    assertEquals(if (mode == "before-entry") 0L else 1L, fixture.lifecycle.actorSnapshot().scheduledTasks!!.activeInvocations)
                } finally {
                    assertTrue(acquisition.end())
                }
                val receipt = requireNotNull(fixture.lifecycle.requestShutdown())
                try {
                    assertEquals(PoolShutdownInvocation.RETURNED, fixture.lifecycle.closePool())
                    if (mode != "before-entry") assertEquals(PoolShutdownObservation.PENDING, receipt.observe())
                } finally {
                    held.release()
                }
                scheduler.submit { assertFalse(PoolActorCustody.currentThreadOwnsActorFrame()) }.get(5, TimeUnit.SECONDS)
                failure.get()?.let { throw it }
                assertEquals(if (mode == "before-entry") 0 else 1, calls.get())
                assertEquals(0, fixture.lifecycle.actorSnapshot().scheduledTasks!!.retained)
                assertEquals(PoolShutdownObservation.UNKNOWN, receipt.observe(), "MODEL startup failure is not erased by task exit.")
            }
        }
    }

    @Test
    fun `ordinary scheduler callback can publish before schedule returns and late future is retained across close admission`() {
        val scheduler = ModelPoolScheduler()
        ActorFixture(ordinary = true, scheduler = scheduler).use { fixture ->
            fixture.ownExternal(scheduler)
            OwnedCallerTestScope().use { scope ->
                val publication = scope.gate()
                val childRuns = AtomicInteger()
                val acquisition = fixture.creator()
                val callback = try {
                    fixture.pool.scheduledExecutor.schedule(
                        Runnable { fixture.pool.scheduledExecutor.scheduleWithFixedDelay({ childRuns.incrementAndGet() }, 7, 13, TimeUnit.SECONDS) },
                        0,
                        TimeUnit.SECONDS,
                    )
                    scheduler.afterRetention = { submitted ->
                        // A different real caller enters the admitted wrapper before its Future is returned.
                        assertTrue(
                            scope.launch {
                                submitted.command.run()
                                true
                            }.value(),
                        )
                        publication.hold()
                    }
                    val running = scope.launch {
                        scheduler.submissions[0].command.run()
                        true
                    }
                    publication.awaitEntered()
                    assertEquals(1, childRuns.get())
                    assertEquals(1L, fixture.lifecycle.actorSnapshot().scheduledTasks!!.publishing)
                    assertEquals(1L, fixture.lifecycle.actorSnapshot().scheduledTasks!!.activeInvocations)
                    running
                } finally {
                    assertTrue(acquisition.end())
                }
                try {
                    assertEquals(0L, fixture.lifecycle.activeAcquisitions())
                    assertEquals(PoolShutdownInvocation.INITIALIZATION_PENDING, fixture.lifecycle.closePool())
                    assertFalse(fixture.pool.isClosed, "The one close must not be consumed before an admitted late Future is retained.")
                } finally {
                    publication.release()
                }
                assertTrue(callback.value())
                assertEquals(0L, fixture.lifecycle.actorSnapshot().scheduledTasks!!.publishing)
                assertEquals(PoolShutdownInvocation.RETURNED, fixture.lifecycle.closePool())
                val child = scheduler.submissions[1]
                assertEquals(7L, child.initialDelay)
                assertEquals(13L, child.delay)
                assertSame(TimeUnit.SECONDS, child.unit)
                assertEquals(1, child.future.cancelCalls.get())
                assertEquals(0, fixture.lifecycle.actorSnapshot().scheduledTasks!!.retained)
                child.command.run() // Retained queued wrapper is inert after cancellation, never a second delegate entry.
                assertEquals(1, childRuns.get())
            }
        }
    }

    @Test
    fun `ordinary scheduler retain then throw is sticky UNKNOWN and queued work cannot gain later authority`() {
        val scheduler = ModelPoolScheduler().apply { afterRetention = { error("MODEL schedule retained before throwing.") } }
        ActorFixture(ordinary = true, scheduler = scheduler).use { fixture ->
            fixture.ownExternal(scheduler)
            val calls = AtomicInteger()
            val acquisition = fixture.creator()
            try {
                assertThrows<IllegalStateException> { fixture.pool.scheduledExecutor.schedule(Runnable { calls.incrementAndGet() }, 0, TimeUnit.SECONDS) }
                assertEquals(PoolActorFault.SCHEDULING_FAILED, fixture.lifecycle.actorSnapshot().firstFailure)
                assertFalse(fixture.lifecycle.businessAdmissionOpen())
            } finally {
                assertTrue(acquisition.end())
            }
            val receipt = requireNotNull(fixture.lifecycle.requestShutdown())
            assertEquals(PoolShutdownInvocation.RETURNED, fixture.lifecycle.closePool())
            scheduler.submissions.single().command.run()
            assertEquals(0, calls.get())
            assertEquals(0, scheduler.submissions.single().future.cancelCalls.get(), "An unreturned Future must not be guessed or reflectively recovered.")
            assertEquals(1, fixture.lifecycle.actorSnapshot().scheduledTasks!!.retained)
            assertEquals(PoolShutdownObservation.UNKNOWN, receipt.observe())
        }
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["false", "throw"])
    fun `ordinary scheduler ambiguous cancellation is not retried by another wrapper call or pool close`(mode: String) {
        val scheduler = ModelPoolScheduler()
        ActorFixture(ordinary = true, scheduler = scheduler).use { fixture ->
            fixture.ownExternal(scheduler)
            val acquisition = fixture.creator()
            try {
                val scheduled = fixture.pool.scheduledExecutor.scheduleWithFixedDelay({ error("Sealed MODEL callback must not enter.") }, 1, 1, TimeUnit.DAYS)
                scheduler.submissions.single().future.cancelAction = {
                    if (mode == "throw") error("MODEL external cancellation failure.")
                    false
                }
                if (mode == "throw") assertThrows<IllegalStateException> { scheduled.cancel(false) } else assertFalse(scheduled.cancel(false))
                assertThrows<RejectedExecutionException> { scheduled.cancel(true) }
                assertEquals(PoolActorFault.CANCELLATION_FAILED, fixture.lifecycle.actorSnapshot().firstFailure)
                assertEquals(0L, fixture.lifecycle.actorSnapshot().scheduledTasks!!.activeCancellations)
            } finally {
                assertTrue(acquisition.end())
            }
            val receipt = requireNotNull(fixture.lifecycle.requestShutdown())
            assertEquals(PoolShutdownInvocation.RETURNED, fixture.lifecycle.closePool())
            scheduler.submissions.single().command.run()
            assertEquals(1, scheduler.submissions.single().future.cancelCalls.get())
            assertEquals(PoolShutdownObservation.UNKNOWN, receipt.observe())
        }
    }

    @Test
    fun `ordinary scheduler cancellation call remains owned after Future reports done until its real tail ends`() {
        val scheduler = ModelPoolScheduler()
        ActorFixture(ordinary = true, scheduler = scheduler).use { fixture ->
            fixture.ownExternal(scheduler)
            OwnedCallerTestScope().use { scope ->
                val held = scope.gate()
                val acquisition = fixture.creator()
                val cancelling = try {
                    val scheduled = fixture.pool.scheduledExecutor.scheduleWithFixedDelay({ error("MODEL task must remain queued.") }, 1, 1, TimeUnit.DAYS)
                    val future = scheduler.submissions.single().future
                    future.cancelAction = {
                        future.done.set(true)
                        held.hold()
                        true
                    }
                    val caller = scope.launch { scheduled.cancel(false) }
                    held.awaitEntered()
                    assertTrue(scheduled.isDone)
                    assertEquals(1L, fixture.lifecycle.actorSnapshot().scheduledTasks!!.activeCancellations)
                    caller
                } finally {
                    assertTrue(acquisition.end())
                }
                val receipt = requireNotNull(fixture.lifecycle.requestShutdown())
                try {
                    assertEquals(PoolShutdownInvocation.RETURNED, fixture.lifecycle.closePool())
                    assertEquals(PoolShutdownObservation.PENDING, receipt.observe())
                } finally {
                    held.release()
                }
                assertTrue(cancelling.value())
                assertEquals(1, scheduler.submissions.single().future.cancelCalls.get())
                assertEquals(0, fixture.lifecycle.actorSnapshot().scheduledTasks!!.retained)
                assertEquals(PoolShutdownObservation.UNKNOWN, receipt.observe())
            }
        }
    }

    @Test
    fun `ordinary scheduler duplicate or foreign framed entry never executes an extra callback`() {
        for (mode in listOf("overlap", "foreign-frame")) {
            assertRejectedScheduledEntry(mode)
        }
    }

    private fun assertRejectedScheduledEntry(mode: String) {
        val scheduler = ModelPoolScheduler()
        ActorFixture(ordinary = true, scheduler = scheduler).use { fixture ->
            fixture.ownExternal(scheduler)
            OwnedCallerTestScope().use { scope ->
                val held = scope.gate()
                val calls = AtomicInteger()
                val acquisition = fixture.creator()
                try {
                    fixture.pool.scheduledExecutor.scheduleWithFixedDelay(
                        {
                            calls.incrementAndGet()
                            held.hold()
                        },
                        1,
                        1,
                        TimeUnit.DAYS,
                    )
                    val command = scheduler.submissions.single().command
                    if (mode == "overlap") {
                        assertOverlappingScheduledEntry(fixture, scope, held, calls, command)
                    } else {
                        assertThrows<RejectedExecutionException> { command.run() }
                        assertSame(acquisition.frame, PoolCallFrames.current(), "No foreign caller lineage may be erased or borrowed.")
                        assertEquals(0, calls.get())
                        assertEquals(PoolActorFault.UNAUTHENTICATED_ENTRY, fixture.lifecycle.actorSnapshot().firstFailure)
                    }
                } finally {
                    held.release()
                    assertTrue(acquisition.end())
                }
            }
        }
    }

    private fun assertOverlappingScheduledEntry(
        fixture: ActorFixture,
        scope: OwnedCallerTestScope,
        held: OwnedCallerTestGate,
        calls: AtomicInteger,
        command: Runnable,
    ) {
        val first = scope.launch {
            command.run()
            true
        }
        held.awaitEntered()
        try {
            assertTrue(
                scope.launch {
                    assertThrows<RejectedExecutionException> { command.run() }
                    true
                }.value(),
            )
            assertEquals(1, calls.get())
            assertEquals(1L, fixture.lifecycle.actorSnapshot().scheduledTasks!!.activeInvocations)
            assertEquals(PoolActorFault.DUPLICATE_ENTRY, fixture.lifecycle.actorSnapshot().firstFailure)
        } finally {
            held.release()
        }
        assertTrue(first.value())
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(ints = [1, 65, 512])
    fun `ordinary scheduler retention scales with P and retires replacement waves rather than storing task history`(size: Int) {
        val scheduler = ModelPoolScheduler()
        ActorFixture(ordinary = true, scheduler = scheduler, maximumPoolSize = size).use { fixture ->
            fixture.ownExternal(scheduler)
            val acquisition = fixture.creator()
            try {
                val population = 1 + 3 * size
                assertEquals(4L * population, fixture.lifecycle.actorSnapshot().scheduledTasks!!.capacity)
                val waves = ArrayDeque<List<ScheduledFuture<*>>>()
                repeat(5) {
                    waves.addLast(
                        List(population) {
                            fixture.pool.scheduledExecutor.scheduleWithFixedDelay({ error("MODEL retained task must remain queued.") }, 1, 1, TimeUnit.DAYS)
                        },
                    )
                    if (waves.size == 3) waves.removeFirst().forEach { assertTrue(it.cancel(false)) }
                    assertNull(fixture.lifecycle.actorSnapshot().firstFailure)
                }
                waves.forEach { wave -> wave.forEach { assertTrue(it.cancel(false)) } }
                assertEquals(0, fixture.lifecycle.actorSnapshot().scheduledTasks!!.retained)
                assertEquals(5L * population, fixture.lifecycle.actorSnapshot().scheduledTasks!!.retired)
                assertEquals(size, fixture.pool.maximumPoolSize)
                assertNull(fixture.lifecycle.actorSnapshot().firstFailure)
            } finally {
                assertTrue(acquisition.end())
            }
        }
    }

    @Test
    fun `ordinary scheduler bounds unresolved overlap without imposing a new maximumPoolSize cap`() {
        val large = ModelPoolScheduler()
        ActorFixture(ordinary = true, scheduler = large, maximumPoolSize = Int.MAX_VALUE).use { fixture ->
            fixture.ownExternal(large)
            assertEquals(4L * (1L + 3L * Int.MAX_VALUE), fixture.lifecycle.actorSnapshot().scheduledTasks!!.capacity)
            assertEquals(Int.MAX_VALUE, fixture.pool.maximumPoolSize)
        }
        val scheduler = ModelPoolScheduler()
        ActorFixture(ordinary = true, scheduler = scheduler, maximumPoolSize = 1).use { fixture ->
            fixture.ownExternal(scheduler)
            val acquisition = fixture.creator()
            try {
                val capacity = fixture.lifecycle.actorSnapshot().scheduledTasks!!.capacity.toInt()
                repeat(capacity) { fixture.pool.scheduledExecutor.schedule(Runnable { error("MODEL queued callback must not enter.") }, 1, TimeUnit.DAYS) }
                assertThrows<RejectedExecutionException> { fixture.pool.scheduledExecutor.schedule(Runnable {}, 1, TimeUnit.DAYS) }
                assertEquals(capacity, scheduler.submissions.size)
                assertEquals(capacity, fixture.lifecycle.actorSnapshot().scheduledTasks!!.retained)
                assertEquals(PoolActorFault.CAPACITY_EXHAUSTED, fixture.lifecycle.actorSnapshot().firstFailure)
            } finally {
                assertTrue(acquisition.end())
            }
            assertEquals(PoolShutdownInvocation.RETURNED, fixture.lifecycle.closePool())
            assertTrue(scheduler.submissions.all { it.future.cancelCalls.get() == 1 })
            assertEquals(0, fixture.lifecycle.actorSnapshot().scheduledTasks!!.retained)
        }
    }

    @Test
    fun `ordinary scheduler retained timers are cancelled after actual Hikari constructor fails before pool publication`() {
        val scheduler = ModelPoolScheduler()
        ActorFixture(install = false, ordinary = true, scheduler = scheduler, maximumPoolSize = 1).use { fixture ->
            fixture.ownExternal(scheduler)
            fixture.pool.apply {
                // Actual Hikari constructor, MODEL JDBC only: no connected/native-lifetime claim.
                dataSource = object : AbstractDataSource() {
                    override fun getConnection(): Connection = mock(Connection::class.java).apply {
                        org.mockito.Mockito.`when`(isValid(org.mockito.ArgumentMatchers.anyInt())).thenReturn(true)
                        org.mockito.Mockito.`when`(autoCommit).thenReturn(true)
                        org.mockito.Mockito.`when`(transactionIsolation).thenReturn(Connection.TRANSACTION_READ_COMMITTED)
                    }
                    override fun getConnection(username: String?, password: String?): Connection = connection
                }
                initializationFailTimeout = 1
                minimumIdle = 1
                maxLifetime = 60_000
                keepaliveTime = 30_000
                metricsTrackerFactory = com.zaxxer.hikari.metrics.MetricsTrackerFactory { _, _ ->
                    error("MODEL metrics initialization fault after actual Hikari timer publication.")
                }
            }
            assertTrue(fixture.lifecycle.installThreadFactory())
            val acquisition = fixture.creator()
            try {
                assertThrows<IllegalStateException> { fixture.pool.connection }
                assertFalse(fixture.pool.isRunning)
                assertEquals(2, scheduler.submissions.size, "Actual fail-fast entry must publish EOL and keepalive before metrics construction fails.")
                assertTrue(scheduler.submissions.all { it.future.cancelCalls.get() == 0 })
            } finally {
                assertTrue(acquisition.end())
            }
            assertEquals(PoolShutdownInvocation.RETURNED, fixture.lifecycle.closePool())
            assertTrue(fixture.pool.isClosed)
            assertTrue(scheduler.submissions.all { it.future.cancelCalls.get() == 1 })
            assertEquals(0, fixture.lifecycle.actorSnapshot().scheduledTasks!!.retained)
            assertFalse(scheduler.isShutdown)
        }
    }
}

/** All cleanup ownership is retained before any explicit fixture start/submit. NEW is never started by cleanup to obtain a receipt. */
private class ActorFixture(install: Boolean = true, ordinary: Boolean = false, scheduler: ScheduledExecutorService? = null, maximumPoolSize: Int = 10) :
    AutoCloseable {
    val pool = HikariDataSource().apply {
        initializationFailTimeout = -1
        if (ordinary) this.maximumPoolSize = maximumPoolSize
        scheduledExecutor = scheduler
    }
    val owner = PersistenceJdbcLifecycleOwner(pgProbeEndpoint(1), 1, PersistencePathStyle.POSIX)
    val lifecycle = if (ordinary) PoolLifecycle.sourceOnly(pool, owner) else PoolLifecycle(pool, owner)
    private val explicitThreads = CopyOnWriteArrayList<Thread>()
    private val executors = mutableListOf<ThreadPoolExecutor>()
    private val externalSchedulers = mutableListOf<ScheduledThreadPoolExecutor>()

    init {
        if (install) check(lifecycle.installThreadFactory())
    }

    fun creator(): PoolLifecycle.Acquisition = lifecycle.prepareAcquisition(PersistenceTimeBudget.start(30_000)).also { check(it.enter()) }

    fun emit(body: Runnable): Thread? = pool.threadFactory.newThread(body)?.also { explicitThreads.add(it) }

    fun own(executor: ThreadPoolExecutor) {
        executors.add(executor)
    }

    fun ownExternal(executor: ScheduledThreadPoolExecutor) {
        externalSchedulers.add(executor)
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
        // Only the TEST owner stops shared schedulers, and only after the guarded pool's teardown.
        val externalStops = externalSchedulers.map { runCatching { it.shutdownNow() } }
        val externalEnds = externalSchedulers.map { runCatching { check(it.awaitTermination(5, TimeUnit.SECONDS)) } }
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
                observation === PoolShutdownObservation.TRACKED_LOCAL_ENDED || observation === PoolShutdownObservation.DRIVER_CONTRACT_ONLY_ENDED ||
                    observation === PoolShutdownObservation.UNKNOWN ||
                    observation === PoolShutdownObservation.POOL_ACTORS_UNPROVEN,
            )
            // Executor termination alone is insufficient: its last Worker/handler tail must have actually terminated too.
            // NEW remains retained/unstarted; conservative failed/unproved receipts are not promoted to pool/native success.
        }
        stopResults.forEach { it.getOrThrow() }
        executorResults.forEach { it.getOrThrow() }
        threadResults.forEach { it.getOrThrow() }
        poolResult.getOrThrow()
        externalStops.forEach { it.getOrThrow() }
        externalEnds.forEach { it.getOrThrow() }
        ownerResult.getOrThrow()
        actorResult.getOrThrow()
    }
}

/** Synthetic public scheduler/future seam only; no private Hikari/JDK inspection and no automatic task execution. */
private class ModelPoolScheduler : ScheduledThreadPoolExecutor(1) {
    val submissions: MutableList<ModelScheduledSubmission> = Collections.synchronizedList(mutableListOf())
    var afterRetention: (ModelScheduledSubmission) -> Unit = {}

    override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> = retain(command, delay, null, unit)

    override fun scheduleWithFixedDelay(command: Runnable, initialDelay: Long, delay: Long, unit: TimeUnit): ScheduledFuture<*> =
        retain(command, initialDelay, delay, unit)

    private fun retain(command: Runnable, initialDelay: Long, delay: Long?, unit: TimeUnit): ScheduledFuture<*> {
        val submitted = ModelScheduledSubmission(command, initialDelay, delay, unit)
        submissions.add(submitted)
        afterRetention(submitted)
        return submitted.future
    }
}

private class ModelScheduledSubmission(val command: Runnable, val initialDelay: Long, val delay: Long?, val unit: TimeUnit) {
    val future = ModelScheduledFuture()
}

private class ModelScheduledFuture : ScheduledFuture<Any?> {
    val cancelCalls = AtomicInteger()
    val done = AtomicBoolean()
    private val cancelled = AtomicBoolean()
    var cancelAction: (Boolean) -> Boolean = { true }

    override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
        cancelCalls.incrementAndGet()
        return cancelAction(mayInterruptIfRunning).also { result ->
            if (result) {
                cancelled.set(true)
                done.set(true)
            }
        }
    }

    override fun isDone(): Boolean = done.get()
    override fun isCancelled(): Boolean = cancelled.get()
    override fun get(): Any? = error("The MODEL Future has no completion wait.")
    override fun get(timeout: Long, unit: TimeUnit): Any? = error("The MODEL Future has no completion wait.")
    override fun getDelay(unit: TimeUnit): Long = 0
    override fun compareTo(other: Delayed): Int = 0
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
