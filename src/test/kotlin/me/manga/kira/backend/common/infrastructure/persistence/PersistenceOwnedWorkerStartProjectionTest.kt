package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.concurrent.withLock

/** Real start/locks/Condition transitions; no injected start receipt or native resource claim. */
internal class PersistenceOwnedWorkerStartProjectionTest {
    @Test
    fun `REAL_THREAD G contention can delay start projection after actual receiver readiness`() = OwnedFactoryWorkerTestScope().use { scope ->
        val heldG = scope.callers.gate()
        val holder = scope.callers.launch {
            scope.binding.ledger.lock.withLock { heldG.hold() }
            true
        }
        heldG.awaitEntered()
        assertEquals(PersistenceOwnedFactoryStart.STARTED, scope.worker.startOwned())
        awaitOwnedTestFact { scope.worker.isOwnedReceiverReady() }
        assertFalse(scope.binding.reconcileCallers())
        scope.binding.rendezvous.lock.withLock {
            assertTrue(scope.binding.rendezvous.startInProgress)
            assertEquals(PersistenceFactoryGeneration.WAITING, scope.binding.rendezvous.generation)
        }
        heldG.release()
        assertTrue(holder.value())
        scope.awaitReady()
        scope.binding.rendezvous.lock.withLock { assertFalse(scope.binding.rendezvous.startInProgress) }
        scope.awaitQueuedReceiver()
    }

    @Test
    fun `REAL_THREAD F contention after an actual blocked start retains projection until reconciliation`() = OwnedFactoryWorkerTestScope().use { scope ->
        val monitor = scope.callers.gate()
        val actual = requireNotNull(scope.binding.rendezvous.thread)
        val holder = scope.callers.launch {
            synchronized(actual) { monitor.hold() }
            true
        }
        monitor.awaitEntered()
        val starter = scope.callers.launch { scope.worker.startOwned() }
        awaitOwnedTestFact { starter.thread.state == Thread.State.BLOCKED }
        // Actual Thread.start is blocked on the retained target monitor, after its F start claim.
        scope.binding.rendezvous.lock.withLock {
            assertTrue(scope.binding.rendezvous.startInProgress)
            assertEquals(Thread.State.NEW, actual.state)
            monitor.release()
            assertTrue(holder.value())
            assertEquals(PersistenceOwnedFactoryStart.STARTED, starter.value())
            // The finally's tryLock ran on the other starter Thread while this exact F was held.
            assertTrue(scope.binding.rendezvous.startInProgress)
        }
        awaitOwnedTestFact { scope.worker.isOwnedReceiverReady() }
        scope.binding.rendezvous.lock.withLock { assertTrue(scope.binding.rendezvous.startInProgress) }
        scope.awaitReady()
        scope.binding.rendezvous.lock.withLock { assertFalse(scope.binding.rendezvous.startInProgress) }
        scope.awaitQueuedReceiver()
    }

    @Test
    fun `REAL_THREAD signal transfers the receiver off the Condition queue without invalidating READY`() = OwnedFactoryWorkerTestScope().use { scope ->
        scope.start()
        scope.binding.rendezvous.lock.withLock {
            assertTrue(scope.binding.rendezvous.lock.hasWaiters(scope.binding.rendezvous.changed))
            scope.binding.rendezvous.changed.signalAll()
            // Holding F keeps the actual signalled receiver in its acquisition queue at this cut.
            assertFalse(scope.binding.rendezvous.lock.hasWaiters(scope.binding.rendezvous.changed))
            assertTrue(scope.worker.isOwnedReceiverReady())
            assertFalse(scope.binding.rendezvous.startInProgress)
        }
        scope.awaitQueuedReceiver()
        assertEquals(0, scope.createCalls.get())
    }
}
