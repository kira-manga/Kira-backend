package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class PersistenceFactoryWorkerTest {
    @Test
    fun `construction is inert and sealing before start permanently prevents creation`() = FactoryWorkerTestScope().use { scope ->
        val calls = AtomicInteger()
        val worker = scope.worker(create = { input, _ ->
            calls.incrementAndGet()
            input
        })
        val before = factorySnapshot(worker)
        assertEquals(PersistenceFactoryGeneration.NEW, before.generation)
        assertFalse(before.startInProgress)
        assertFalse(before.attemptRetained)
        assertFactoryRefused(worker.request(FactoryTestValue()), PersistenceFactoryFailure.NOT_READY)
        assertEquals(PersistenceFactoryObservation.NOT_SEALED, worker.awaitTermination(1_000))
        assertTrue(worker.seal())
        assertFalse(worker.seal())
        assertEquals(PersistenceFactoryStart.CLOSED, worker.start())
        assertEquals(PersistenceFactoryStart.ALREADY_CLAIMED, worker.start())
        assertEquals(PersistenceFactoryObservation.TERMINATED, worker.awaitTermination(1_000))
        assertEquals(PersistenceFactoryObservation.CLOSED, worker.awaitReady(1_000))
        assertEquals(0, calls.get())
    }

    @Test
    fun `success transfers the exact result and reuses the same actual worker without discard`() = FactoryWorkerTestScope().use { scope ->
        val threads = CopyOnWriteArrayList<Thread>()
        val results = listOf(FactoryTestValue(), FactoryTestValue())
        val calls = AtomicInteger()
        val discards = AtomicInteger()
        val worker = scope.worker(
            create = { _, _ ->
                threads.add(Thread.currentThread())
                results[calls.getAndIncrement()]
            },
            discard = { _, _ -> discards.incrementAndGet() },
        )
        startFactory(worker)
        val first = factorySuccess(worker.request(FactoryTestValue()))
        assertSame(results[0], first.value)
        assertEquals(PersistenceFactoryObservation.READY, worker.awaitReady(5_000))
        assertEquals(PersistenceFactoryProcessing.PROCESSING_ENDED, first.receipt.state())
        val second = factorySuccess(worker.request(FactoryTestValue()))
        assertSame(results[1], second.value)
        assertEquals(PersistenceFactoryObservation.READY, worker.awaitReady(5_000))
        assertSame(threads[0], threads[1])
        assertEquals(PersistenceFactoryProcessing.PROCESSING_ENDED, first.receipt.state())
        assertEquals(PersistenceFactoryProcessing.PROCESSING_ENDED, second.receipt.state())
        assertTrue(worker.seal())
        assertEquals(0, discards.get())
        assertEquals(2, calls.get())
    }

    @Test
    fun `blocked and recursive submissions refuse without another callback or queued job`() = FactoryWorkerTestScope().use { scope ->
        val gate = scope.gate()
        val calls = AtomicInteger()
        val recursive = AtomicReference<PersistenceFactoryResult<FactoryTestValue>>()
        lateinit var worker: PersistenceFactoryWorker<FactoryTestValue, FactoryTestValue>
        worker = scope.worker(create = { input, _ ->
            calls.incrementAndGet()
            recursive.set(worker.request(FactoryTestValue()))
            gate.hold()
            input
        })
        startFactory(worker)
        val original = scope.launch { worker.request(FactoryTestValue()) }
        gate.awaitEntered()
        assertFactoryRefused(recursive.get(), PersistenceFactoryFailure.BUSY)
        val peers = List(8) { scope.launch { worker.request(FactoryTestValue()) } }
        peers.forEach { assertFactoryRefused(it.join(), PersistenceFactoryFailure.BUSY) }
        assertEquals(1, calls.get())
        assertEquals(PersistenceFactoryGeneration.ACTIVE, factorySnapshot(worker).generation)
        assertEquals(PersistenceFactoryObservation.TIMEOUT, worker.awaitReady(1))
        gate.release()
        factorySuccess(original.join())
        assertEquals(PersistenceFactoryObservation.READY, worker.awaitReady(5_000))
        assertEquals(1, calls.get())
    }

    @Test
    fun `timeout requests cancellation while blocked create stays owned and later discards exactly once`() = FactoryWorkerTestScope().use { scope ->
        val gate = scope.gate()
        val input = FactoryTestValue()
        val result = FactoryTestValue()
        val token = AtomicReference<PersistenceFactoryCancellation>()
        val createThread = AtomicReference<Thread>()
        val discardThread = AtomicReference<Thread>()
        val discards = AtomicInteger()
        val worker = scope.worker(
            loginMillis = 2_000,
            create = { actual, cancellation ->
                assertSame(input, actual)
                token.set(cancellation)
                createThread.set(Thread.currentThread())
                gate.hold() // Deliberately does not honor the request token.
                result
            },
            discard = { actual, late ->
                assertSame(input, actual)
                assertSame(result, late)
                discardThread.set(Thread.currentThread())
                discards.incrementAndGet()
            },
        )
        startFactory(worker)
        val caller = scope.launch { worker.request(input) }
        gate.awaitEntered()
        val failure = factoryFailure(caller.join())
        assertEquals(PersistenceFactoryFailure.TIMEOUT, failure.reason)
        assertTrue(token.get().isRequested())
        assertEquals(PersistenceFactoryProcessing.PENDING, failure.receipt.state())
        assertFactoryRefused(worker.request(FactoryTestValue()), PersistenceFactoryFailure.BUSY)
        assertEquals(0, discards.get())
        gate.release()
        assertEquals(PersistenceFactoryObservation.READY, worker.awaitReady(5_000))
        assertEquals(1, discards.get())
        assertSame(createThread.get(), discardThread.get())
        assertEquals(PersistenceFactoryProcessing.PROCESSING_ENDED, failure.receipt.state())
    }

    @Test
    fun `pre interrupted caller refuses without clearing its flag or invoking create`() = FactoryWorkerTestScope().use { scope ->
        val calls = AtomicInteger()
        val worker = scope.worker(create = { input, _ ->
            calls.incrementAndGet()
            input
        })
        startFactory(worker)
        val caller = scope.launch {
            Thread.currentThread().interrupt()
            try {
                worker.request(FactoryTestValue()) to Thread.currentThread().isInterrupted
            } finally {
                Thread.interrupted()
            }
        }
        val (result, interrupted) = caller.join()
        assertFactoryRefused(result, PersistenceFactoryFailure.INTERRUPTED)
        assertTrue(interrupted)
        assertEquals(0, calls.get())
        assertEquals(PersistenceFactoryGeneration.WAITING, factorySnapshot(worker).generation)
    }

    @Test
    fun `recoverable create failure is fixed and does not replace the worker`() = FactoryWorkerTestScope().use { scope ->
        val calls = AtomicInteger()
        val threads = CopyOnWriteArrayList<Thread>()
        val discards = AtomicInteger()
        val worker = scope.worker(
            create = { input, _ ->
                threads.add(Thread.currentThread())
                if (calls.getAndIncrement() == 0) error("Synthetic creation failure.")
                input
            },
            discard = { _, _ -> discards.incrementAndGet() },
        )
        startFactory(worker)
        val failure = factoryFailure(worker.request(FactoryTestValue()))
        assertEquals(PersistenceFactoryFailure.CREATE_FAILED, failure.reason)
        assertEquals(PersistenceFactoryObservation.READY, worker.awaitReady(5_000))
        assertEquals(PersistenceFactoryProcessing.PROCESSING_ENDED, failure.receipt.state())
        factorySuccess(worker.request(FactoryTestValue()))
        assertEquals(PersistenceFactoryObservation.READY, worker.awaitReady(5_000))
        assertSame(threads[0], threads[1])
        assertEquals(0, discards.get())
        assertEquals(2, calls.get())
    }

    @Test
    fun `readiness observation does not reserve a subsequent request`() = FactoryWorkerTestScope().use { scope ->
        val gate = scope.gate()
        val worker = scope.worker(create = { input, _ ->
            gate.hold()
            input
        })
        startFactory(worker)
        assertEquals(PersistenceFactoryObservation.READY, worker.awaitReady(5_000))
        val winner = scope.launch { worker.request(FactoryTestValue()) }
        gate.awaitEntered()
        assertFactoryRefused(worker.request(FactoryTestValue()), PersistenceFactoryFailure.BUSY)
        gate.release()
        factorySuccess(winner.join())
        assertEquals(PersistenceFactoryObservation.READY, worker.awaitReady(5_000))
    }
}
