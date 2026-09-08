package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.util.concurrent.atomic.AtomicInteger

/** Tests the actual locked transitions, alongside the separate complete real-worker tests. */
class PersistenceFactoryAcknowledgementTest {
    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @CsvSource(
        "CREATE_FAILED,true",
        "CREATE_FAILED,false",
        "BEFORE_CREATE,true",
        "BEFORE_CREATE,false",
        "LATE_RESULT,true",
        "LATE_RESULT,false",
    )
    fun `both party orders retain exactly one admitted caller through failure and cancellation`(mode: String, callerFirst: Boolean) =
        FactoryWorkerTestScope().use { scope ->
            val rendezvous = PersistenceFactoryRendezvous<FactoryTestValue, FactoryTestValue>()
            val clock = FactoryTestClock()
            val gate = scope.gate()
            val discards = AtomicInteger()
            val receiver = scope.launch { settleScenario(rendezvous, gate, mode, discards) }
            try {
                assertEquals(PersistenceFactoryObservation.READY, rendezvous.awaitReady(PersistenceTimeBudget.start(5_000)))
                val admitted = rendezvous.admit(FactoryTestValue(), PersistenceTimeBudget.start(20_000, clock))
                val attempt = requireNotNull(admitted.attempt)
                gate.awaitEntered()
                if (callerFirst) {
                    val caller = scope.launch {
                        Thread.currentThread().interrupt()
                        try {
                            rendezvous.awaitResult(attempt) to Thread.currentThread().isInterrupted
                        } finally {
                            Thread.interrupted()
                        }
                    }
                    val (outcome, flag) = caller.join()
                    assertEquals(PersistenceFactoryFailure.INTERRUPTED, factoryFailure(outcome).reason)
                    assertTrue(flag)
                    assertEquals(PersistenceFactoryProcessing.PENDING, attempt.receipt.state())
                    assertBusy(rendezvous)
                    gate.release()
                } else {
                    if (mode != "CREATE_FAILED") clock.advanceNanos(20_000_000_000)
                    gate.release()
                    awaitFactoryTestFact { (rendezvous.snapshot() as? PersistenceFactorySnapshot.Available)?.workerSettled == true }
                    assertEquals(PersistenceFactoryProcessing.PENDING, attempt.receipt.state())
                    repeat(8) {
                        rendezvous.signalWaiters()
                        assertBusy(rendezvous)
                    }
                    val outcome = factoryFailure(rendezvous.awaitResult(attempt))
                    assertEquals(if (mode == "CREATE_FAILED") PersistenceFactoryFailure.CREATE_FAILED else PersistenceFactoryFailure.TIMEOUT, outcome.reason)
                }
                assertEquals(PersistenceFactoryObservation.READY, rendezvous.awaitReady(PersistenceTimeBudget.start(5_000)))
                assertEquals(PersistenceFactoryProcessing.PROCESSING_ENDED, attempt.receipt.state())
                assertEquals(if (mode == "LATE_RESULT") 1 else 0, discards.get())
                assertFalse(factorySnapshot(rendezvous).attemptRetained)
                rendezvous.seal()
                assertTrue(receiver.join())
            } finally {
                rendezvous.seal()
            }
        }

    @Test
    fun `seal may end the acknowledgement parked worker while old caller receipt remains pending`() = FactoryWorkerTestScope().use { scope ->
        val rendezvous = PersistenceFactoryRendezvous<FactoryTestValue, FactoryTestValue>()
        val receiver = scope.launch {
            val attempt = requireNotNull(rendezvous.awaitWork())
            assertTrue(rendezvous.beginWork(attempt))
            rendezvous.creationFailed(attempt)
            rendezvous.settleWorker(attempt)
            assertNull(rendezvous.awaitWork())
            true
        }
        try {
            assertEquals(PersistenceFactoryObservation.READY, rendezvous.awaitReady(PersistenceTimeBudget.start(5_000)))
            val attempt = requireNotNull(rendezvous.admit(FactoryTestValue(), PersistenceTimeBudget.start(20_000)).attempt)
            awaitFactoryTestFact { (rendezvous.snapshot() as? PersistenceFactorySnapshot.Available)?.workerSettled == true }
            assertBusy(rendezvous)
            rendezvous.seal()
            assertTrue(receiver.join())
            assertFalse(receiver.thread.isAlive)
            assertEquals(PersistenceFactoryProcessing.PENDING, attempt.receipt.state())
            val snapshot = factorySnapshot(rendezvous)
            assertTrue(snapshot.callerAttached)
            assertTrue(snapshot.attemptRetained)
            val failure = factoryFailure(rendezvous.awaitResult(attempt))
            assertEquals(PersistenceFactoryFailure.CLOSED, failure.reason)
            assertEquals(PersistenceFactoryProcessing.PROCESSING_ENDED, failure.receipt.state())
        } finally {
            rendezvous.seal()
        }
    }

    @Test
    fun `successful offer cannot settle before caller transfer and packaging expiry leaves old owner intact`() = FactoryWorkerTestScope().use { scope ->
        val rendezvous = PersistenceFactoryRendezvous<FactoryTestValue, FactoryTestValue>()
        val gate = scope.gate()
        val clock = FactoryTestClock()
        val resource = FactoryTestValue()
        val receiver = scope.launch {
            val attempt = requireNotNull(rendezvous.awaitWork())
            assertTrue(rendezvous.beginWork(attempt))
            rendezvous.retainResult(attempt, resource)
            assertTrue(rendezvous.offer(attempt))
            gate.hold()
            assertSame(resource, rendezvous.awaitDisposition(attempt))
            rendezvous.discardReturned(attempt)
            rendezvous.settleWorker(attempt)
            assertNull(rendezvous.awaitWork())
            true
        }
        try {
            assertEquals(PersistenceFactoryObservation.READY, rendezvous.awaitReady(PersistenceTimeBudget.start(5_000)))
            val attempt = requireNotNull(rendezvous.admit(FactoryTestValue(), PersistenceTimeBudget.start(20_000, clock)).attempt)
            gate.awaitEntered()
            assertThrows(IllegalStateException::class.java) { rendezvous.settleWorker(attempt) }
            assertEquals(PersistenceFactoryProcessing.PENDING, attempt.receipt.state())
            // The receiver is gated. These are exactly the caller's before/after-packaging budget reads.
            clock.expireAfterReads(1, 20_000_000_000)
            val failure = factoryFailure(rendezvous.awaitResult(attempt))
            assertEquals(PersistenceFactoryFailure.TIMEOUT, failure.reason)
            assertSame(resource, attempt.result)
            assertFalse(attempt.transferred)
            assertEquals(PersistenceFactoryProcessing.PENDING, attempt.receipt.state())
            gate.release()
            assertEquals(PersistenceFactoryObservation.READY, rendezvous.awaitReady(PersistenceTimeBudget.start(5_000)))
            assertEquals(PersistenceFactoryProcessing.PROCESSING_ENDED, failure.receipt.state())
            rendezvous.seal()
            assertTrue(receiver.join())
        } finally {
            rendezvous.seal()
        }
    }

    private fun settleScenario(
        rendezvous: PersistenceFactoryRendezvous<FactoryTestValue, FactoryTestValue>,
        gate: FactoryTestGate,
        mode: String,
        discards: AtomicInteger,
    ): Boolean {
        val attempt = requireNotNull(rendezvous.awaitWork())
        if (mode != "BEFORE_CREATE") assertTrue(rendezvous.beginWork(attempt))
        gate.hold()
        when (mode) {
            "BEFORE_CREATE" -> assertFalse(rendezvous.beginWork(attempt))

            "CREATE_FAILED" -> rendezvous.creationFailed(attempt)

            "LATE_RESULT" -> {
                val result = FactoryTestValue()
                rendezvous.retainResult(attempt, result)
                assertFalse(rendezvous.offer(attempt))
                assertSame(result, rendezvous.awaitDisposition(attempt))
                discards.incrementAndGet()
                rendezvous.discardReturned(attempt)
            }

            else -> error("Unsupported synthetic scenario.")
        }
        rendezvous.settleWorker(attempt)
        assertNull(rendezvous.awaitWork())
        return true
    }

    private fun assertBusy(rendezvous: PersistenceFactoryRendezvous<FactoryTestValue, FactoryTestValue>) {
        val refusal = requireNotNull(rendezvous.admit(FactoryTestValue(), PersistenceTimeBudget.start(20_000)).refusal)
        assertEquals(PersistenceFactoryFailure.BUSY, refusal.reason)
    }
}
