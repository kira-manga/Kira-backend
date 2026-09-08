package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/** Real owned platform starts and termination; explicit MODEL cuts are not native-start fault injection. */
internal class PersistenceRetainedPlatformThreadTest {
    @Test
    fun `REAL_THREAD construction is inert and a prestart fence forbids every future start`() {
        val calls = AtomicInteger()
        val actor = PersistenceRetainedPlatformThread("kira-actor-fixture") { calls.incrementAndGet() }
        assertEquals(PersistenceThreadStartPhase.NEW, actor.startPhase())
        assertEquals(PersistenceThreadTermination.PENDING, actor.termination())
        assertFalse(actor.hasEntered())
        assertFalse(actor.hasBodyEnded())
        actor.forbidStart()
        assertEquals(PersistenceThreadTermination.INERT, actor.termination())
        repeat(3) { assertEquals(PersistenceFactoryStart.CLOSED, actor.start()) }
        assertEquals(Thread.State.NEW, actor.thread.state)
        assertEquals(0, calls.get())
    }

    @Test
    fun `REAL_THREAD foreign direct run cannot counterfeit entry or execute the retained body`() {
        val calls = AtomicInteger()
        val actor = PersistenceRetainedPlatformThread("kira-actor-fixture") { calls.incrementAndGet() }
        actor.thread.run()
        assertEquals(0, calls.get())
        assertFalse(actor.hasEntered())
        assertEquals(PersistenceThreadTermination.PENDING, actor.termination())
        actor.forbidStart()
        assertEquals(PersistenceThreadTermination.INERT, actor.termination())
    }

    @Test
    fun `REAL_THREAD same-thread direct run cannot reenter or publish the outer body exit`() = OwnedCallerTestScope().use { scope ->
        val calls = AtomicInteger()
        val afterDirectRun = scope.gate()
        val actor = scope.actor {
            // A faulty second entry returns instead of recursively calling run again. This keeps
            // the negative control finite and leaves the original body held at the real cut.
            if (calls.incrementAndGet() == 1) {
                Thread.currentThread().run()
                afterDirectRun.hold()
            }
        }
        assertEquals(PersistenceFactoryStart.STARTED, actor.start())
        afterDirectRun.awaitEntered()
        assertEquals(1, calls.get())
        assertTrue(actor.hasEntered())
        assertFalse(actor.hasBodyEnded())
        assertFalse(actor.hasBodyReturned())
        assertEquals(PersistenceThreadTermination.PENDING, actor.termination())
        afterDirectRun.release()
        awaitOwnedTestFact { actor.termination() == PersistenceThreadTermination.TERMINATED }
        assertTrue(actor.hasBodyEnded())
        assertTrue(actor.hasBodyReturned())
        assertEquals(1, calls.get())
    }

    @Test
    fun `REAL_THREAD fast completion requires actual start return and authentic termination`() = OwnedCallerTestScope().use { scope ->
        val calls = AtomicInteger()
        val actor = scope.actor { calls.incrementAndGet() }
        assertSame(Thread::class.java, actor.thread.javaClass)
        assertFalse(actor.thread.isVirtual)
        assertEquals(PersistenceFactoryStart.STARTED, actor.start())
        awaitOwnedTestFact { actor.termination() == PersistenceThreadTermination.TERMINATED }
        assertEquals(PersistenceThreadStartPhase.RETURNED, actor.startPhase())
        assertTrue(actor.hasEntered())
        assertTrue(actor.hasBodyEnded())
        assertTrue(actor.hasBodyReturned())
        assertFalse(actor.thread.isAlive)
        assertEquals(PersistenceFactoryStart.ALREADY_CLAIMED, actor.start())
        assertEquals(1, calls.get())
    }

    @Test
    fun `REAL_THREAD no inheritable locals enter the owned actor and self observation stays pending`() = OwnedCallerTestScope().use { scope ->
        val local = InheritableThreadLocal<Any>()
        val inherited = AtomicReference<Any?>()
        val self = AtomicReference<PersistenceThreadTermination>()
        lateinit var actor: PersistenceRetainedPlatformThread
        local.set(Any())
        try {
            actor = scope.actor {
                inherited.set(local.get())
                self.set(actor.termination())
            }
            assertEquals(PersistenceFactoryStart.STARTED, actor.start())
            awaitOwnedTestFact { actor.termination() == PersistenceThreadTermination.TERMINATED }
            assertEquals(null, inherited.get())
            assertEquals(PersistenceThreadTermination.PENDING, self.get())
        } finally {
            local.remove()
        }
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["RUNTIME", "ERROR", "THROWABLE"])
    fun `REAL_THREAD exceptional body exit is retained without rendering its failure`(kind: String) = OwnedCallerTestScope().use { scope ->
        val failures = PhysicalTestFailures()
        val failure = when (kind) {
            "RUNTIME" -> failures.runtime
            "ERROR" -> failures.fatal
            else -> failures.direct
        }
        val actor = scope.actor { throw failure }
        assertEquals(PersistenceFactoryStart.STARTED, actor.start())
        awaitOwnedTestFact { actor.termination() == PersistenceThreadTermination.TERMINATED }
        assertTrue(actor.hasEntered())
        assertTrue(actor.hasBodyEnded())
        assertFalse(actor.hasBodyReturned())
        assertEquals(PersistenceFactoryStart.ALREADY_CLAIMED, actor.start())
        assertEquals(0, failures.runtime.reads.get())
        assertEquals(0, failures.fatal.reads.get())
    }

    @Test
    fun `REAL_THREAD seal during actual blocked start cannot turn a retained NEW thread into inert proof`() = OwnedCallerTestScope().use { scope ->
        val monitor = scope.gate()
        val body = scope.gate()
        val actor = scope.actor { body.hold() }
        val holder = scope.launch {
            synchronized(actor.thread) { monitor.hold() }
            true
        }
        monitor.awaitEntered()
        val starter = scope.launch { actor.start() }
        awaitOwnedTestFact { starter.thread.state == Thread.State.BLOCKED && actor.startPhase() == PersistenceThreadStartPhase.INVOKING }
        assertEquals(Thread.State.NEW, actor.thread.state)
        actor.forbidStart()
        assertEquals(PersistenceThreadTermination.PENDING, actor.termination())
        assertEquals(PersistenceFactoryStart.ALREADY_CLAIMED, actor.start())
        // No target monitor acquisition occurs in termination(), even while Thread.start is blocked.
        assertEquals(PersistenceThreadTermination.PENDING, scope.launch { actor.termination() }.value())
        monitor.release()
        assertTrue(holder.value())
        assertEquals(PersistenceFactoryStart.STARTED, starter.value())
        body.awaitEntered()
        assertEquals(PersistenceThreadTermination.PENDING, actor.termination())
        body.release()
        awaitOwnedTestFact { actor.termination() == PersistenceThreadTermination.TERMINATED }
    }

    @Test
    fun `REAL_THREAD a running actor can hold its own monitor without blocking termination observation`() = OwnedCallerTestScope().use { scope ->
        val gate = scope.gate()
        val actor = scope.actor { synchronized(Thread.currentThread()) { gate.hold() } }
        assertEquals(PersistenceFactoryStart.STARTED, actor.start())
        gate.awaitEntered()
        val observer = scope.launch {
            val budget = PersistenceTimeBudget.start(80, SystemPersistenceNanoClock)
            while (persistenceFactoryRemainingMillis(budget) != 0L) {
                assertEquals(PersistenceThreadTermination.PENDING, actor.termination())
                LockSupport.parkNanos(1_000_000)
            }
            true
        }
        assertTrue(observer.value())
        assertTrue(actor.thread.isAlive)
        gate.release()
        awaitOwnedTestFact { actor.termination() == PersistenceThreadTermination.TERMINATED }
    }

    @Test
    fun `MODEL failed start without actual entry remains unknown despite false isAlive`() {
        val actor = PersistenceRetainedPlatformThread("kira-actor-fixture") { error("Must never run.") }
        actor.forbidStart()
        modelStartPhase(actor, PersistenceThreadStartPhase.THREW)
        assertFalse(actor.thread.isAlive)
        assertFalse(actor.hasEntered())
        assertEquals(PersistenceThreadTermination.UNKNOWN, actor.termination())
        assertEquals(PersistenceFactoryStart.ALREADY_CLAIMED, actor.start())
        assertEquals(Thread.State.NEW, actor.thread.state)
        // MODEL only: no real start or other resource was attempted by this fixture.
    }

    @Test
    fun `MODEL actual fast thread exit cannot hide a still pending start extent`() = OwnedCallerTestScope().use { scope ->
        val actor = scope.actor { }
        assertEquals(PersistenceFactoryStart.STARTED, actor.start())
        awaitOwnedTestFact { actor.termination() == PersistenceThreadTermination.TERMINATED }
        modelStartPhase(actor, PersistenceThreadStartPhase.INVOKING)
        try {
            assertTrue(actor.hasBodyEnded())
            assertFalse(actor.thread.isAlive)
            assertEquals(PersistenceThreadTermination.PENDING, actor.termination())
        } finally {
            modelStartPhase(actor, PersistenceThreadStartPhase.RETURNED)
        }
    }

    @Test
    fun `MODEL a body flag never substitutes for the real still running thread`() = OwnedCallerTestScope().use { scope ->
        val gate = scope.gate()
        val actor = scope.actor { gate.hold() }
        assertEquals(PersistenceFactoryStart.STARTED, actor.start())
        gate.awaitEntered()
        val field = PersistenceRetainedPlatformThread::class.java.getDeclaredField("bodyEnded").apply { isAccessible = true }
        (field.get(actor) as AtomicBoolean).set(true)
        assertTrue(actor.hasBodyEnded())
        assertEquals(PersistenceThreadTermination.PENDING, actor.termination())
        gate.release()
        awaitOwnedTestFact { actor.termination() == PersistenceThreadTermination.TERMINATED }
    }
}

private fun OwnedCallerTestScope.actor(body: () -> Unit): PersistenceRetainedPlatformThread {
    val actor = PersistenceRetainedPlatformThread("kira-actor-fixture", body)
    beforeClose {
        actor.forbidStart()
        awaitOwnedTestFact { actor.termination() in setOf(PersistenceThreadTermination.INERT, PersistenceThreadTermination.TERMINATED) }
        assertFalse(actor.thread.isAlive)
        println("RETAINED_ACTOR_SCOPE_EXIT thread_id=${actor.thread.threadId()} all_terminated=true")
    }
    return actor
}

private fun modelStartPhase(actor: PersistenceRetainedPlatformThread, phase: PersistenceThreadStartPhase) {
    val field = PersistenceRetainedPlatformThread::class.java.getDeclaredField("start").apply { isAccessible = true }
    val state = field.get(actor) as AtomicReference<*>
    // Test-only MODEL receipt cut; production offers no setter or injectable Thread/start operation.
    AtomicReference::class.java.getMethod("set", Any::class.java).invoke(state, phase)
}
