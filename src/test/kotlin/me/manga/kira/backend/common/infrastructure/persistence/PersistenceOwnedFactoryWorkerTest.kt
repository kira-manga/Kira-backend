package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.withLock

/** This tests the actual closed F1 worker, not the still-unimplemented complete D1 resource root. */
internal class PersistenceOwnedFactoryWorkerTest {
    @Test
    fun `REAL_THREAD owned construction is inert and seal before start never creates a worker`() = OwnedFactoryWorkerTestScope().use { scope ->
        assertEquals(Thread.State.NEW, scope.binding.rendezvous.thread?.state)
        assertEquals(PersistenceThreadTermination.PENDING, scope.worker.ownedThreadTermination())
        assertFalse(scope.worker.isOwnedReceiverReady())
        assertTrue(scope.worker.requestOwnedStop())
        assertFalse(scope.worker.requestOwnedStop())
        assertEquals(PersistenceOwnedFactoryStart.CLOSED, scope.worker.startOwned())
        assertEquals(PersistenceOwnedFactoryStart.ALREADY_CLAIMED, scope.worker.startOwned())
        assertEquals(PersistenceThreadTermination.INERT, scope.worker.ownedThreadTermination())
        assertEquals(0, scope.createCalls.get())
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["START", "REQUEST", "SEAL", "READY", "TERMINATION"])
    fun `REAL_THREAD owned worker rejects every legacy entry before caller methods or waiting`(method: String) = OwnedFactoryWorkerTestScope().use { scope ->
        val behavior = OwnedCallerTestBehavior()
        scope.binding.rendezvous.lock.withLock {
            val caller = scope.callers.launch(OwnedCallerTestKind.OVERRIDING, behavior) {
                assertThrows(IllegalStateException::class.java) {
                    when (method) {
                        "START" -> scope.worker.start()
                        "REQUEST" -> scope.worker.request(PersistencePhysicalRecord(0))
                        "SEAL" -> scope.worker.seal()
                        "READY" -> scope.worker.awaitReady(5_000)
                        else -> scope.worker.awaitTermination(5_000)
                    }
                }
                true
            }
            assertTrue(caller.value())
            assertFalse(scope.binding.rendezvous.lock.hasQueuedThread(caller.thread))
            assertEquals(0, behavior.samples.get())
            assertEquals(0, behavior.restores.get())
        }
        assertSame(PersistenceFactorySnapshot.Unavailable, scope.worker.snapshot())
        assertEquals(Thread.State.NEW, scope.binding.rendezvous.thread?.state)
    }

    @Test
    fun `REAL_THREAD F contention does not claim or enqueue an owned start`() = OwnedFactoryWorkerTestScope().use { scope ->
        scope.binding.rendezvous.lock.withLock {
            val starter = scope.callers.launch { scope.worker.startOwned() }
            assertEquals(PersistenceOwnedFactoryStart.CONTENDED, starter.value())
            assertFalse(scope.binding.rendezvous.lock.hasQueuedThread(starter.thread))
            assertEquals(PersistenceFactoryGeneration.NEW, scope.binding.rendezvous.generation)
        }
        scope.start()
        assertEquals(PersistenceOwnedFactoryStart.ALREADY_CLAIMED, scope.worker.startOwned())
    }

    @Test
    fun `REAL_THREAD exact receiver transfers opaque results and reuses only its authentic worker`() = OwnedFactoryWorkerTestScope().use { scope ->
        scope.start()
        val first = scope.binding.request(5_000) as PersistenceFactoryResult.Success<PersistenceJdbcCandidate>
        val actual = scope.callbackThread.get()
        scope.awaitReady()
        val second = scope.binding.request(5_000) as PersistenceFactoryResult.Success<PersistenceJdbcCandidate>
        scope.awaitReady()
        assertSame(actual, scope.callbackThread.get())
        assertSame(actual, scope.binding.rendezvous.thread)
        assertNotSame(first.value, second.value)
        assertEquals(PersistenceFactoryProcessing.PROCESSING_ENDED, first.receipt.state())
        assertEquals(PersistenceFactoryProcessing.PROCESSING_ENDED, second.receipt.state())
        assertEquals(0, scope.discardCalls.get())
        assertEquals(0, scope.failedCleanupCalls.get())
        val entries = scope.binding.ledger.entries.filterNotNull()
        assertEquals(2, entries.size)
        entries.forEach { assertEquals(PersistenceOwnedCallerDisposition.TAKEN, it.control?.state()) }
        // The request's fallback finally must remain inert after successful TAKEN, not abandon it.
        assertFalse(first.value.isRetirementRequested())
        assertFalse(second.value.isRetirementRequested())
    }

    @Test
    fun `REAL_THREAD create failure cannot settle F1 while its MODEL resource phase is still running`() = OwnedFactoryWorkerTestScope().use { scope ->
        val cleanup = scope.callers.gate()
        scope.createAction = { _, _ -> error("Synthetic creation failure.") }
        scope.failureAction = { cleanup.hold() }
        scope.start()
        val caller = scope.callers.launch { scope.binding.request(5_000) }
        cleanup.awaitEntered()
        val failure = caller.value() as PersistenceFactoryResult.Failed
        assertEquals(PersistenceFactoryFailure.CREATE_FAILED, failure.reason)
        assertEquals(PersistenceFactoryProcessing.PENDING, failure.receipt.state())
        assertFalse(scope.worker.isOwnedReceiverReady())
        assertTrue(scope.binding.reconcileCallers())
        assertTrue(scope.binding.ledger.entries.filterNotNull().single().retiring)
        assertEquals(PersistenceFactoryProcessing.PENDING, failure.receipt.state())
        cleanup.release()
        scope.awaitReady()
        assertEquals(PersistenceFactoryProcessing.PROCESSING_ENDED, failure.receipt.state())
        assertEquals(1, scope.failedCleanupCalls.get())
        assertEquals(0, scope.discardCalls.get())
    }

    @Test
    fun `REAL_THREAD self-run cannot settle F1 while original create and discard remain active`() = OwnedFactoryWorkerTestScope().use { scope ->
        val creation = scope.callers.gate()
        val discard = scope.callers.gate()
        scope.createAction = { input, _ ->
            Thread.currentThread().run()
            creation.hold()
            scope.modelOpeningReturn(input)
        }
        scope.discardAction = { _, _ -> discard.hold() }
        scope.start()
        val caller = scope.callers.launch {
            try {
                scope.binding.request(5_000)
            } finally {
                Thread.interrupted()
            }
        }
        creation.awaitEntered()
        // This exact owned platform caller abandons while the original create stays gated.
        caller.thread.interrupt()
        val failure = caller.value() as PersistenceFactoryResult.Failed
        awaitOwnedTestFact { scope.binding.reconcileCallers() }
        val entry = scope.binding.ledger.entries.filterNotNull().single()
        assertEquals(PersistenceFactoryProcessing.PENDING, failure.receipt.state())
        assertEquals(PersistenceFactoryFailure.INTERRUPTED, failure.reason)
        scope.binding.rendezvous.lock.withLock {
            assertFalse(requireNotNull(entry.attempt).workerSettled)
            assertTrue(requireNotNull(entry.attempt).callerDetached)
            assertEquals(PersistenceFactoryGeneration.ACTIVE, scope.binding.rendezvous.generation)
        }
        assertTrue(entry.retirementRequested.get())
        assertEquals(PersistenceThreadTermination.PENDING, scope.worker.ownedThreadTermination())
        assertEquals(1, scope.createCalls.get())
        creation.release()
        discard.awaitEntered()
        assertEquals(PersistenceFactoryProcessing.PENDING, failure.receipt.state())
        assertFalse(scope.worker.isOwnedReceiverReady())
        discard.release()
        scope.awaitReady()
        assertEquals(PersistenceFactoryProcessing.PROCESSING_ENDED, failure.receipt.state())
        assertEquals(1, scope.discardCalls.get())
        assertEquals(0, scope.failedCleanupCalls.get())
        assertSame(scope.binding.rendezvous.thread, scope.callbackThread.get())
        assertSame(entry, scope.binding.ledger.entries.first(), "F1 completion is not physical reclamation.")
    }

    @Test
    fun `MODEL pre-work abandonment still waits actual owned failure cleanup before processing completion`() = OwnedFactoryWorkerTestScope().use { scope ->
        val cleanup = scope.callers.gate()
        scope.failureAction = { cleanup.hold() }
        scope.start()
        val control = PersistenceOwnedCallerControl.prepare(5_000)
        val entry = requireNotNull(scope.binding.reserve(control))
        scope.binding.rendezvous.lock.withLock {
            assertTrue(scope.binding.admit(entry))
            assertTrue(control.fail(PersistenceFactoryFailure.TIMEOUT))
            assertTrue(scope.binding.reconcileCallers())
        }
        cleanup.awaitEntered()
        assertEquals(0, scope.createCalls.get())
        assertEquals(1, scope.failedCleanupCalls.get())
        assertEquals(PersistenceFactoryProcessing.PENDING, control.receipt.state())
        cleanup.release()
        scope.awaitReady()
        assertEquals(PersistenceFactoryProcessing.PROCESSING_ENDED, control.receipt.state())
        assertSame(entry, scope.binding.ledger.entries.first(), "F1 completion alone must not remove physical ownership.")
    }

    @Test
    fun `REAL_THREAD timeout keeps its exact late result and discard on the original worker`() = OwnedFactoryWorkerTestScope().use { scope ->
        val creation = scope.callers.gate()
        val discard = scope.callers.gate()
        val late = AtomicReference<PersistenceJdbcCandidate>()
        scope.createAction = { input, _ ->
            scope.modelOpeningReturn(input).also {
                late.set(it)
                creation.hold()
            }
        }
        scope.discardAction = { _, result ->
            assertSame(late.get(), result)
            discard.hold()
        }
        scope.start()
        val caller = scope.callers.launch { scope.binding.request(500) }
        creation.awaitEntered()
        val failure = caller.value() as PersistenceFactoryResult.Failed
        assertEquals(PersistenceFactoryFailure.TIMEOUT, failure.reason)
        assertEquals(PersistenceFactoryProcessing.PENDING, failure.receipt.state())
        creation.release()
        discard.awaitEntered()
        assertEquals(PersistenceFactoryProcessing.PENDING, failure.receipt.state())
        assertFalse(scope.worker.isOwnedReceiverReady())
        discard.release()
        scope.awaitReady()
        assertEquals(PersistenceFactoryProcessing.PROCESSING_ENDED, failure.receipt.state())
        assertEquals(1, scope.discardCalls.get())
        assertEquals(0, scope.failedCleanupCalls.get())
        assertSame(scope.binding.rendezvous.thread, scope.callbackThread.get())
    }

    @Test
    fun `REAL_THREAD failed cleanup breaks the generation instead of replacing the worker`() = OwnedFactoryWorkerTestScope().use { scope ->
        scope.createAction = { _, _ -> error("Synthetic creation failure.") }
        scope.failureAction = { error("Synthetic resource-phase failure.") }
        scope.start()
        val result = scope.binding.request(5_000) as PersistenceFactoryResult.Failed
        awaitOwnedTestFact {
            scope.binding.reconcileCallers()
            scope.worker.ownedThreadTermination() == PersistenceThreadTermination.TERMINATED
        }
        assertEquals(PersistenceFactoryGeneration.BROKEN, scope.binding.rendezvous.generation)
        assertEquals(PersistenceFactoryProcessing.PROCESSING_ENDED_UNRESOLVED, result.receipt.state())
        assertEquals(PersistenceOwnedFactoryStart.ALREADY_CLAIMED, scope.worker.startOwned())
        assertFalse(scope.worker.isOwnedReceiverReady())
        assertEquals(1, scope.createCalls.get())
        assertEquals(1, scope.failedCleanupCalls.get())
        assertEquals(1, scope.binding.ledger.entries.count { it != null })
    }
}
