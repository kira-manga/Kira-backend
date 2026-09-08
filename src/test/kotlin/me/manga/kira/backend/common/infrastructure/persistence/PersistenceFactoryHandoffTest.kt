package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class PersistenceFactoryHandoffTest {
    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["BEFORE_WORK", "RESULT_RETAINED"])
    fun `expiry before work or before offer cannot transfer a result`(boundary: String) = FactoryWorkerTestScope().use { scope ->
        val gate = scope.gate()
        val clock = FactoryTestClock()
        val calls = AtomicInteger()
        val discards = AtomicInteger()
        val worker = scope.worker(
            clock = clock,
            create = { input, _ ->
                calls.incrementAndGet()
                input
            },
            discard = { _, _ -> discards.incrementAndGet() },
            probe = { if (it.name == boundary) gate.hold() },
        )
        startFactory(worker)
        val caller = scope.launch { worker.request(FactoryTestValue()) }
        gate.awaitEntered()
        clock.advanceNanos(20_000_000_000)
        gate.release()
        val failure = factoryFailure(caller.join())
        assertEquals(PersistenceFactoryFailure.TIMEOUT, failure.reason)
        assertEquals(PersistenceFactoryObservation.READY, worker.awaitReady(5_000))
        val expected = if (boundary == "BEFORE_WORK") 0 else 1
        assertEquals(expected, calls.get())
        assertEquals(expected, discards.get())
        assertEquals(PersistenceFactoryProcessing.PROCESSING_ENDED, failure.receipt.state())
    }

    @Test
    fun `interrupted admitted caller before create keeps provenance and its flag`() = FactoryWorkerTestScope().use { scope ->
        val gate = scope.gate()
        val calls = AtomicInteger()
        val worker = scope.worker(
            create = { input, _ ->
                calls.incrementAndGet()
                input
            },
            probe = { if (it == PersistenceFactoryProbePoint.BEFORE_WORK) gate.hold() },
        )
        startFactory(worker)
        val caller = scope.launch {
            try {
                worker.request(FactoryTestValue()) to Thread.currentThread().isInterrupted
            } finally {
                Thread.interrupted()
            }
        }
        gate.awaitEntered()
        caller.thread.interrupt()
        val (result, flag) = caller.join()
        val failure = factoryFailure(result)
        assertEquals(PersistenceFactoryFailure.INTERRUPTED, failure.reason)
        assertTrue(flag)
        assertEquals(PersistenceFactoryProcessing.PENDING, failure.receipt.state())
        assertFactoryRefused(worker.request(FactoryTestValue()), PersistenceFactoryFailure.BUSY)
        gate.release()
        assertEquals(PersistenceFactoryObservation.READY, worker.awaitReady(5_000))
        assertEquals(PersistenceFactoryProcessing.PROCESSING_ENDED, failure.receipt.state())
        assertEquals(0, calls.get())
    }

    @Test
    fun `blocked late discard retains occupancy and seal cannot manufacture thread exit`() = FactoryWorkerTestScope().use { scope ->
        val creation = scope.gate()
        val disposal = scope.gate()
        val clock = FactoryTestClock()
        val input = FactoryTestValue()
        val result = FactoryTestValue()
        val discards = AtomicInteger()
        val worker = scope.worker(
            clock = clock,
            create = { _, _ ->
                creation.hold()
                result
            },
            discard = { actual, late ->
                assertSame(input, actual)
                assertSame(result, late)
                discards.incrementAndGet()
                disposal.hold()
            },
        )
        startFactory(worker)
        val caller = scope.launch { worker.request(input) }
        creation.awaitEntered()
        clock.advanceNanos(20_000_000_000)
        creation.release()
        disposal.awaitEntered()
        val failure = factoryFailure(caller.join())
        assertEquals(PersistenceFactoryProcessing.PENDING, failure.receipt.state())
        val snapshot = factorySnapshot(worker)
        assertTrue(snapshot.attemptRetained)
        assertTrue(snapshot.resultRetained)
        assertFalse(snapshot.workerSettled)
        assertFactoryRefused(worker.request(FactoryTestValue()), PersistenceFactoryFailure.BUSY)
        assertTrue(worker.seal())
        assertEquals(PersistenceFactoryObservation.TIMEOUT, worker.awaitTermination(10))
        assertEquals(1, discards.get())
        disposal.release()
        assertEquals(PersistenceFactoryObservation.TERMINATED, worker.awaitTermination(5_000))
        assertEquals(PersistenceFactoryProcessing.PROCESSING_ENDED, failure.receipt.state())
        assertEquals(1, discards.get())
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["BEFORE_WORK", "RESULT_RETAINED"])
    fun `seal prevents unclaimed handoff at assigned and retained return boundaries`(boundary: String) = FactoryWorkerTestScope().use { scope ->
        val gate = scope.gate()
        val calls = AtomicInteger()
        val discards = AtomicInteger()
        val worker = scope.worker(
            create = { input, _ ->
                calls.incrementAndGet()
                input
            },
            discard = { _, _ -> discards.incrementAndGet() },
            probe = { if (it.name == boundary) gate.hold() },
        )
        startFactory(worker)
        val caller = scope.launch { worker.request(FactoryTestValue()) }
        gate.awaitEntered()
        worker.seal()
        val failure = factoryFailure(caller.join())
        assertEquals(PersistenceFactoryFailure.CLOSED, failure.reason)
        assertEquals(PersistenceFactoryProcessing.PENDING, failure.receipt.state())
        assertFactoryRefused(worker.request(FactoryTestValue()), PersistenceFactoryFailure.CLOSED)
        gate.release()
        assertEquals(PersistenceFactoryObservation.TERMINATED, worker.awaitTermination(5_000))
        assertEquals(if (boundary == "BEFORE_WORK") 0 else 1, calls.get())
        assertEquals(calls.get(), discards.get())
        assertEquals(PersistenceFactoryProcessing.PROCESSING_ENDED, failure.receipt.state())
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["SEAL", "FAIL", "INTERRUPT"])
    fun `seal or later worker failure cannot revoke an already transferred result or token`(mode: String) = FactoryWorkerTestScope().use { scope ->
        val gate = scope.gate()
        val token = AtomicReference<PersistenceFactoryCancellation>()
        val result = FactoryTestValue()
        val discards = AtomicInteger()
        val worker = scope.worker(
            create = { _, cancellation ->
                token.set(cancellation)
                result
            },
            discard = { _, _ -> discards.incrementAndGet() },
            probe = {
                if (it == PersistenceFactoryProbePoint.OFFER_PUBLISHED) {
                    gate.hold()
                    if (mode == "FAIL") error("Synthetic post-transfer failure.")
                    if (mode == "INTERRUPT") Thread.currentThread().interrupt()
                }
            },
        )
        startFactory(worker)
        val caller = scope.launch { worker.request(FactoryTestValue()) }
        gate.awaitEntered()
        val success = factorySuccess(caller.join())
        assertSame(result, success.value)
        assertEquals(PersistenceFactoryProcessing.PENDING, success.receipt.state())
        worker.seal()
        assertFalse(token.get().isRequested())
        gate.release()
        assertEquals(PersistenceFactoryObservation.TERMINATED, worker.awaitTermination(5_000))
        assertFalse(token.get().isRequested())
        assertEquals(0, discards.get())
        val expected = if (mode != "SEAL") {
            PersistenceFactoryProcessing.PROCESSING_ENDED_UNRESOLVED
        } else {
            PersistenceFactoryProcessing.PROCESSING_ENDED
        }
        assertEquals(expected, success.receipt.state())
        assertEquals(0, result.renders.get())
        assertEquals(0, result.comparisons.get())
    }
}
