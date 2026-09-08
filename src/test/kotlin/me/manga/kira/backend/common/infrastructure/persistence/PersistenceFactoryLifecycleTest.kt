package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class PersistenceFactoryLifecycleTest {
    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["START_CLAIMED", "THREAD_RETAINED"])
    fun `pending start cannot be reported terminated and duplicate starts never queue`(boundary: String) = FactoryWorkerTestScope().use { scope ->
        val gate = scope.gate()
        val entered = AtomicInteger()
        val worker = scope.worker(probe = {
            if (it.name == boundary) gate.hold()
            if (it == PersistenceFactoryProbePoint.WORKER_ENTERED) entered.incrementAndGet()
        })
        val starter = scope.launch { worker.start() }
        gate.awaitEntered()
        val peers = List(8) { scope.launch { worker.start() } }
        peers.forEach { assertEquals(PersistenceFactoryStart.ALREADY_CLAIMED, it.join()) }
        assertTrue(factorySnapshot(worker).startInProgress)
        assertFactoryRefused(worker.request(FactoryTestValue()), PersistenceFactoryFailure.NOT_READY)
        worker.seal()
        assertEquals(PersistenceFactoryObservation.TIMEOUT, worker.awaitTermination(10))
        assertTrue(factorySnapshot(worker).startInProgress)
        gate.release()
        assertEquals(PersistenceFactoryStart.CLOSED, starter.join())
        assertEquals(PersistenceFactoryObservation.TERMINATED, worker.awaitTermination(5_000))
        assertEquals(0, entered.get())
    }

    @Test
    fun `seal entry claim is not its state transition and duplicate seals cannot accumulate waiters`() = FactoryWorkerTestScope().use { scope ->
        val gate = scope.gate()
        val worker = scope.worker(probe = { if (it == PersistenceFactoryProbePoint.SEAL_CLAIMED) gate.hold() })
        startFactory(worker)
        val sealer = scope.launch { worker.seal() }
        gate.awaitEntered()
        val duplicates = List(8) { scope.launch { worker.seal() } }
        duplicates.forEach { assertFalse(it.join()) }
        factorySuccess(worker.request(FactoryTestValue()))
        gate.release()
        assertTrue(sealer.join())
        assertEquals(PersistenceFactoryObservation.TERMINATED, worker.awaitTermination(5_000))
    }

    @Test
    fun `one permit bounds combined readiness and termination observers and is reusable`() = FactoryWorkerTestScope().use { scope ->
        val gate = scope.gate()
        val entries = AtomicInteger()
        val worker = scope.worker(probe = {
            if (it == PersistenceFactoryProbePoint.OBSERVER_ENTERED) {
                entries.incrementAndGet()
                gate.hold()
            }
        })
        assertEquals(PersistenceFactoryStart.STARTED, worker.start())
        val first = scope.launch { worker.awaitReady(5_000) }
        gate.awaitEntered()
        assertEquals(PersistenceFactoryObservation.BUSY, worker.awaitTermination(5_000))
        assertEquals(PersistenceFactoryObservation.BUSY, worker.awaitReady(5_000))
        assertEquals(1, entries.get())
        gate.release()
        assertEquals(PersistenceFactoryObservation.READY, first.join())
        assertEquals(PersistenceFactoryObservation.READY, worker.awaitReady(5_000))
        assertEquals(2, entries.get())
    }

    @Test
    fun `observer failure releases its permit and closes the generation without manufacturing work completion`() = FactoryWorkerTestScope().use { scope ->
        val fail = AtomicBoolean(true)
        val worker = scope.worker(probe = {
            if (it == PersistenceFactoryProbePoint.OBSERVER_ENTERED && fail.getAndSet(false)) error("Synthetic observer failure.")
        })
        assertEquals(PersistenceFactoryStart.STARTED, worker.start())
        assertEquals(PersistenceFactoryObservation.FAILED, worker.awaitReady(5_000))
        assertEquals(PersistenceFactoryObservation.TERMINATED, worker.awaitTermination(5_000))
        assertEquals(PersistenceFactoryGeneration.BROKEN, factorySnapshot(worker).generation)
        assertFactoryRefused(worker.request(FactoryTestValue()), PersistenceFactoryFailure.BROKEN)
    }

    @Test
    fun `repeated actual join interruptions preserve one original observer allowance and restore its flag`() = FactoryWorkerTestScope().use { scope ->
        val gate = scope.gate()
        val clock = FactoryTestClock()
        val worker = scope.worker(clock = clock, create = { input, _ ->
            gate.hold()
            input
        })
        startFactory(worker)
        val caller = scope.launch { worker.request(FactoryTestValue()) }
        gate.awaitEntered()
        worker.seal()
        factoryFailure(caller.join())
        val observer = scope.launch {
            try {
                worker.awaitTermination(2_000) to Thread.currentThread().isInterrupted
            } finally {
                Thread.interrupted()
            }
        }
        awaitFactoryTestFact { observer.thread.state == Thread.State.TIMED_WAITING }
        val beforeFirst = clock.reads.get()
        clock.advanceNanos(1_000_000_000)
        observer.thread.interrupt()
        awaitFactoryTestFact { clock.reads.get() > beforeFirst && observer.thread.state == Thread.State.TIMED_WAITING }
        clock.advanceNanos(1_000_000_000)
        observer.thread.interrupt()
        val (result, interrupted) = observer.join()
        assertEquals(PersistenceFactoryObservation.TIMEOUT, result)
        assertTrue(interrupted)
        assertTrue(factorySnapshot(worker).attemptRetained)
        gate.release()
        assertEquals(PersistenceFactoryObservation.TERMINATED, worker.awaitTermination(5_000))
    }

    @Test
    fun `interrupting the actual idle Condition permanently breaks the sole worker`() = FactoryWorkerTestScope().use { scope ->
        val actual = AtomicReference<Thread>()
        val calls = AtomicInteger()
        val worker = scope.worker(
            create = { input, _ ->
                calls.incrementAndGet()
                input
            },
            probe = { if (it == PersistenceFactoryProbePoint.WORKER_ENTERED) actual.set(Thread.currentThread()) },
        )
        startFactory(worker)
        awaitFactoryTestFact { actual.get().state == Thread.State.WAITING }
        actual.get().interrupt() // Harness-owned synthetic worker, outside the rendezvous lock.
        awaitFactoryTestFact { factorySnapshot(worker).generation == PersistenceFactoryGeneration.BROKEN }
        assertEquals(PersistenceFactoryObservation.TERMINATED, worker.awaitTermination(5_000))
        assertFalse(actual.get().isAlive)
        assertEquals(PersistenceFactoryStart.ALREADY_CLAIMED, worker.start())
        assertFactoryRefused(worker.request(FactoryTestValue()), PersistenceFactoryFailure.BROKEN)
        assertEquals(0, calls.get())
    }

    @Test
    fun `retained platform worker does not inherit caller inheritable thread locals`() = FactoryWorkerTestScope().use { scope ->
        val parent = InheritableThreadLocal<FactoryTestValue>()
        val observed = AtomicReference<FactoryTestValue>()
        parent.set(FactoryTestValue())
        try {
            val worker = scope.worker(create = { input, _ ->
                observed.set(parent.get())
                input
            })
            startFactory(worker)
            factorySuccess(worker.request(FactoryTestValue()))
            assertEquals(PersistenceFactoryObservation.READY, worker.awaitReady(5_000))
            assertNull(observed.get())
        } finally {
            parent.remove()
        }
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["START_CLAIMED", "THREAD_RETAINED"])
    fun `startup failure is sticky and never permits a replacement or future start`(boundary: String) = FactoryWorkerTestScope().use { scope ->
        val worker = scope.worker(probe = { if (it.name == boundary) error("Synthetic startup failure.") })
        assertEquals(PersistenceFactoryStart.FAILED, worker.start())
        assertEquals(PersistenceFactoryGeneration.BROKEN, factorySnapshot(worker).generation)
        assertFalse(factorySnapshot(worker).startInProgress)
        assertEquals(PersistenceFactoryObservation.TERMINATED, worker.awaitTermination(5_000))
        assertEquals(PersistenceFactoryStart.ALREADY_CLAIMED, worker.start())
    }
}
