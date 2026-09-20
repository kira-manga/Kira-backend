package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.withLock

class PersistenceFactoryConditionTest {
    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(booleans = [false, true])
    fun `actual offer Condition interruption including signal race retains the old result`(signalFirst: Boolean) = FactoryWorkerTestScope().use { scope ->
        val rendezvous = PersistenceFactoryRendezvous<FactoryTestValue, FactoryTestValue>()
        val offered = AtomicBoolean()
        val resource = FactoryTestValue()
        val receiver = scope.launch {
            try {
                val attempt = requireNotNull(rendezvous.awaitWork())
                assertTrue(rendezvous.beginWork(attempt))
                rendezvous.retainResult(attempt, resource)
                assertTrue(rendezvous.offer(attempt))
                offered.set(true)
                rendezvous.awaitDisposition(attempt)
                error("Interrupted offered wait must not complete normally.")
            } catch (_: InterruptedException) {
                rendezvous.workerFailed(PersistenceFactoryFailure.INTERRUPTED)
                Thread.currentThread().interrupt()
                Thread.currentThread().isInterrupted
            }
        }
        try {
            assertEquals(PersistenceFactoryObservation.READY, rendezvous.awaitReady(PersistenceTimeBudget.start(5_000)))
            val attempt = requireNotNull(rendezvous.admit(FactoryTestValue(), PersistenceTimeBudget.start(20_000)).attempt)
            awaitFactoryTestFact { offered.get() && receiver.thread.state == Thread.State.TIMED_WAITING }
            if (signalFirst) rendezvous.signalWaiters()
            receiver.thread.interrupt() // Actual synthetic Condition, never a probe supplying an interrupt exception.
            assertTrue(receiver.join())
            assertFalse(receiver.thread.isAlive)
            assertSame(resource, attempt.result)
            assertEquals(PersistenceFactoryProcessing.PENDING, attempt.receipt.state())
            val failure = factoryFailure(rendezvous.awaitResult(attempt))
            assertEquals(PersistenceFactoryFailure.BROKEN, failure.reason)
            assertEquals(PersistenceFactoryProcessing.PROCESSING_ENDED_UNRESOLVED, failure.receipt.state())
            assertSame(resource, attempt.result)
            assertFalse(attempt.transferred)
        } finally {
            rendezvous.seal()
        }
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(booleans = [false, true])
    fun `real caller predicate loop survives false notifications then deadline or interrupt`(interrupt: Boolean) = FactoryWorkerTestScope().use { scope ->
        val rendezvous = PersistenceFactoryRendezvous<FactoryTestValue, FactoryTestValue>()
        val clock = FactoryTestClock()
        val gate = scope.gate()
        val discards = AtomicInteger()
        val receiver = scope.launch {
            val attempt = requireNotNull(rendezvous.awaitWork())
            assertTrue(rendezvous.beginWork(attempt))
            gate.hold()
            val result = FactoryTestValue()
            rendezvous.retainResult(attempt, result)
            assertFalse(rendezvous.offer(attempt))
            assertSame(result, rendezvous.awaitDisposition(attempt))
            discards.incrementAndGet()
            rendezvous.discardReturned(attempt)
            rendezvous.settleWorker(attempt)
            assertNull(rendezvous.awaitWork())
            true
        }
        try {
            assertEquals(PersistenceFactoryObservation.READY, rendezvous.awaitReady(PersistenceTimeBudget.start(5_000)))
            val attempt = requireNotNull(rendezvous.admit(FactoryTestValue(), PersistenceTimeBudget.start(20_000, clock)).attempt)
            val caller = scope.launch {
                try {
                    rendezvous.awaitResult(attempt) to Thread.currentThread().isInterrupted
                } finally {
                    Thread.interrupted()
                }
            }
            gate.awaitEntered()
            awaitFactoryTestFact { caller.thread.state == Thread.State.TIMED_WAITING }
            repeat(8) { rendezvous.signalWaiters() }
            assertEquals(PersistenceFactoryProcessing.PENDING, attempt.receipt.state())
            if (interrupt) {
                caller.thread.interrupt()
            } else {
                clock.advanceNanos(20_000_000_000)
                rendezvous.signalWaiters()
            }
            val (outcome, flag) = caller.join()
            val failure = factoryFailure(outcome)
            assertEquals(if (interrupt) PersistenceFactoryFailure.INTERRUPTED else PersistenceFactoryFailure.TIMEOUT, failure.reason)
            assertEquals(interrupt, flag)
            assertEquals(PersistenceFactoryProcessing.PENDING, failure.receipt.state())
            gate.release()
            assertEquals(PersistenceFactoryObservation.READY, rendezvous.awaitReady(PersistenceTimeBudget.start(5_000)))
            assertEquals(PersistenceFactoryProcessing.PROCESSING_ENDED, failure.receipt.state())
            assertEquals(1, discards.get())
            rendezvous.seal()
            assertTrue(receiver.join())
        } finally {
            rendezvous.seal()
        }
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(booleans = [false, true])
    fun `submillisecond remainder refuses before admission including signed nanoTime wrap`(wrap: Boolean) = FactoryWorkerTestScope().use { scope ->
        val rendezvous = PersistenceFactoryRendezvous<FactoryTestValue, FactoryTestValue>()
        val receiver = scope.launch { rendezvous.awaitWork() == null }
        try {
            assertEquals(PersistenceFactoryObservation.READY, rendezvous.awaitReady(PersistenceTimeBudget.start(5_000)))
            var now = if (wrap) Long.MAX_VALUE - 800_000 else 0L
            val budget = PersistenceTimeBudget.start(2, PersistenceNanoClock { now })
            now += 1_500_000
            val submission = rendezvous.admit(FactoryTestValue(), budget)
            assertNull(submission.attempt)
            assertEquals(PersistenceFactoryFailure.TIMEOUT, requireNotNull(submission.refusal).reason)
            rendezvous.seal()
            assertTrue(receiver.join())
        } finally {
            rendezvous.seal()
        }
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(booleans = [false, true])
    fun `idle Condition interrupted before or after assignment signal never begins accepted work`(interruptFirst: Boolean) =
        FactoryWorkerTestScope().use { scope ->
            val rendezvous = PersistenceFactoryRendezvous<FactoryTestValue, FactoryTestValue>()
            val lock = factoryTestLock(rendezvous)
            val input = FactoryTestValue()
            val workCalls = AtomicInteger()
            val interruptGate = scope.gate()
            val receiver = scope.launch {
                try {
                    val attempt = requireNotNull(rendezvous.awaitWork())
                    workCalls.incrementAndGet()
                    rendezvous.beginWork(attempt)
                    error("Interrupted idle receiver must not begin accepted work.")
                } catch (_: InterruptedException) {
                    rendezvous.workerFailed(PersistenceFactoryFailure.INTERRUPTED)
                    Thread.currentThread().interrupt()
                    Thread.currentThread().isInterrupted
                }
            }
            val interruptor = scope.launch {
                interruptGate.hold()
                receiver.thread.interrupt() // This distinct owned thread never acquires the state lock.
                true
            }
            try {
                assertEquals(PersistenceFactoryObservation.READY, rendezvous.awaitReady(PersistenceTimeBudget.start(5_000)))
                awaitFactoryTestFact { receiver.thread.state == Thread.State.WAITING }
                val attempt = lock.withLock {
                    if (interruptFirst) {
                        interruptGate.release()
                        assertTrue(interruptor.join())
                    }
                    val accepted = requireNotNull(rendezvous.admit(input, PersistenceTimeBudget.start(20_000)).attempt)
                    if (!interruptFirst) {
                        interruptGate.release()
                        assertTrue(interruptor.join())
                    }
                    assertEquals(PersistenceFactoryGeneration.ACTIVE, factorySnapshot(rendezvous).generation)
                    assertFalse(accepted.workerSettled)
                    accepted
                }
                assertTrue(receiver.join())
                assertFalse(receiver.thread.isAlive)
                assertEquals(0, workCalls.get())
                assertSame(input, attempt.input)
                assertNull(attempt.result)
                assertFalse(attempt.transferred)
                val snapshot = factorySnapshot(rendezvous)
                assertEquals(PersistenceFactoryGeneration.BROKEN, snapshot.generation)
                assertTrue(snapshot.attemptRetained)
                assertFalse(snapshot.resultRetained)
                assertTrue(snapshot.callerAttached)
                assertTrue(snapshot.workerSettled)
                assertTrue(snapshot.cancellationRequested)
                assertEquals(PersistenceFactoryProcessing.PENDING, attempt.receipt.state())
                repeat(8) {
                    rendezvous.signalWaiters()
                    val refusal = requireNotNull(rendezvous.admit(FactoryTestValue(), PersistenceTimeBudget.start(20_000)).refusal)
                    assertEquals(PersistenceFactoryFailure.BROKEN, refusal.reason)
                }
                val failure = factoryFailure(rendezvous.awaitResult(attempt))
                assertEquals(PersistenceFactoryFailure.BROKEN, failure.reason)
                assertSame(attempt.receipt, failure.receipt)
                assertEquals(PersistenceFactoryProcessing.PROCESSING_ENDED_UNRESOLVED, failure.receipt.state())
                assertTrue(attempt.callerDetached)
                assertSame(input, attempt.input)
                assertTrue(factorySnapshot(rendezvous).attemptRetained)
                assertEquals(0, workCalls.get())
            } finally {
                rendezvous.seal()
                interruptGate.release()
            }
        }

    @Test
    fun `contended actual state lock refuses snapshot and admission without queued callers`() = FactoryWorkerTestScope().use { scope ->
        val rendezvous = PersistenceFactoryRendezvous<FactoryTestValue, FactoryTestValue>()
        val lock = factoryTestLock(rendezvous)
        try {
            assertEquals(PersistenceFactoryGeneration.NEW, factorySnapshot(rendezvous).generation)
            lock.withLock {
                val caller = scope.launch {
                    assertSame(PersistenceFactorySnapshot.Unavailable, rendezvous.snapshot())
                    val admission = rendezvous.admit(FactoryTestValue(), PersistenceTimeBudget.start(20_000))
                    assertNull(admission.attempt)
                    assertEquals(PersistenceFactoryFailure.BUSY, requireNotNull(admission.refusal).reason)
                    true
                }
                // Completion while this other thread still holds the lock proves no blocking acquisition.
                assertTrue(caller.join())
                assertFalse(caller.thread.isAlive)
                assertFalse(lock.hasQueuedThread(caller.thread))
                assertFalse(lock.hasQueuedThreads())
            }
            val snapshot = factorySnapshot(rendezvous)
            assertEquals(PersistenceFactoryGeneration.NEW, snapshot.generation)
            assertFalse(snapshot.attemptRetained)
            assertFalse(snapshot.resultRetained)
            val afterUnlock = rendezvous.admit(FactoryTestValue(), PersistenceTimeBudget.start(20_000))
            assertNull(afterUnlock.attempt)
            assertEquals(PersistenceFactoryFailure.NOT_READY, requireNotNull(afterUnlock.refusal).reason)
        } finally {
            rendezvous.seal()
        }
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["SEAL", "TIMEOUT"])
    fun `offered unclaimed result is discarded once on seal or worker expiry before caller detachment`(mode: String) = FactoryWorkerTestScope().use { scope ->
        val rendezvous = PersistenceFactoryRendezvous<FactoryTestValue, FactoryTestValue>()
        val clock = FactoryTestClock()
        val input = FactoryTestValue()
        val resource = FactoryTestValue()
        val offered = AtomicBoolean()
        val discards = AtomicInteger()
        val receiver = scope.launch {
            val attempt = requireNotNull(rendezvous.awaitWork())
            assertSame(input, attempt.input)
            assertTrue(rendezvous.beginWork(attempt))
            rendezvous.retainResult(attempt, resource)
            assertTrue(rendezvous.offer(attempt))
            offered.set(true)
            assertSame(resource, rendezvous.awaitDisposition(attempt))
            assertEquals(1, discards.incrementAndGet())
            rendezvous.discardReturned(attempt)
            rendezvous.settleWorker(attempt)
            assertNull(rendezvous.awaitWork())
            true
        }
        try {
            assertEquals(PersistenceFactoryObservation.READY, rendezvous.awaitReady(PersistenceTimeBudget.start(5_000)))
            val attempt = requireNotNull(rendezvous.admit(input, PersistenceTimeBudget.start(20_000, clock)).attempt)
            awaitFactoryTestFact { offered.get() && receiver.thread.state == Thread.State.TIMED_WAITING }
            assertEquals(PersistenceFactoryAttemptPhase.OFFERED, attempt.phase)
            assertFalse(attempt.callerDetached)
            assertSame(resource, attempt.result)
            assertEquals(0, discards.get())
            if (mode == "SEAL") {
                rendezvous.seal()
            } else {
                clock.advanceNanos(20_000_000_000)
                rendezvous.signalWaiters() // The worker observes expiry while the caller remains attached.
            }
            awaitFactoryTestFact { (rendezvous.snapshot() as? PersistenceFactorySnapshot.Available)?.workerSettled == true }
            if (mode == "SEAL") {
                assertTrue(receiver.join())
                assertFalse(receiver.thread.isAlive)
            } else {
                awaitFactoryTestFact { receiver.thread.state == Thread.State.WAITING }
                assertTrue(receiver.thread.isAlive)
            }
            val readsAfterSettlement = clock.reads.get()
            repeat(8) {
                rendezvous.signalWaiters()
                val snapshot = factorySnapshot(rendezvous)
                assertEquals(if (mode == "SEAL") PersistenceFactoryGeneration.SEALED else PersistenceFactoryGeneration.ACTIVE, snapshot.generation)
                assertTrue(snapshot.attemptRetained)
                assertTrue(snapshot.resultRetained)
                assertTrue(snapshot.callerAttached)
                assertTrue(snapshot.workerSettled)
                assertTrue(snapshot.cancellationRequested)
                assertEquals(PersistenceFactoryProcessing.PENDING, attempt.receipt.state())
                val refusal = requireNotNull(rendezvous.admit(FactoryTestValue(), PersistenceTimeBudget.start(20_000)).refusal)
                assertEquals(if (mode == "SEAL") PersistenceFactoryFailure.CLOSED else PersistenceFactoryFailure.BUSY, refusal.reason)
                assertEquals(1, discards.get())
            }
            assertEquals(readsAfterSettlement, clock.reads.get())
            assertSame(input, attempt.input)
            assertSame(resource, attempt.result)
            assertFalse(attempt.transferred)
            val failure = factoryFailure(rendezvous.awaitResult(attempt))
            assertEquals(if (mode == "SEAL") PersistenceFactoryFailure.CLOSED else PersistenceFactoryFailure.TIMEOUT, failure.reason)
            assertSame(attempt.receipt, failure.receipt)
            assertEquals(PersistenceFactoryProcessing.PROCESSING_ENDED, failure.receipt.state())
            assertTrue(attempt.callerDetached)
            assertNull(attempt.result)
            if (mode == "TIMEOUT") assertEquals(PersistenceFactoryObservation.READY, rendezvous.awaitReady(PersistenceTimeBudget.start(5_000)))
            assertFalse(factorySnapshot(rendezvous).attemptRetained)
            rendezvous.seal()
            assertTrue(receiver.join())
            assertEquals(1, discards.get())
            assertFalse(attempt.transferred)
        } finally {
            rendezvous.seal()
        }
    }

    @Test
    fun `on time complete worker handoff survives a signed monotonic clock wrap`() = FactoryWorkerTestScope().use { scope ->
        val clock = FactoryTestClock(Long.MAX_VALUE - 100_000_000_000)
        val resource = FactoryTestValue()
        val worker = scope.worker(
            loginMillis = 1_000_000,
            clock = clock,
            create = { _, _ -> resource },
            probe = { if (it == PersistenceFactoryProbePoint.BEFORE_WORK) clock.advanceNanos(200_000_000_000) },
        )
        startFactory(worker)
        val success = factorySuccess(worker.request(FactoryTestValue()))
        assertSame(resource, success.value)
        assertTrue(clock.nanoTime() < 0)
        assertEquals(PersistenceFactoryObservation.READY, worker.awaitReady(5_000))
        assertEquals(PersistenceFactoryProcessing.PROCESSING_ENDED, success.receipt.state())
    }
}
