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
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.withLock

/** Real C6 caller stacks/locks with explicitly MODEL driver/terminal state; not completed D1. */
class PersistenceOwnedFactoryBindingTest {
    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["F", "G"])
    fun `contended final locks cannot prevent expiry abandonment or release an accepted record`(held: String) = OwnedCallerTestScope().use { scope ->
        val binding = modelReadyBinding()
        val gate = scope.gate()
        val behavior = OwnedCallerTestBehavior().apply {
            sampleGate = gate
            gateAtSample = 2 // The actual request's first accepted observation, after F→G admission.
        }
        val call = scope.launch(OwnedCallerTestKind.OVERRIDING, behavior) { binding.request(1_000) }
        gate.awaitEntered()
        val entry = requireNotNull(binding.ledger.entries.single())
        val control = requireNotNull(entry.control)
        assertEquals(PersistenceOwnedCallerDisposition.ATTACHED, control.state())
        val lock = if (held == "F") binding.rendezvous.lock else binding.ledger.lock
        assertTrue(lock.tryLock())
        try {
            awaitOwnedTestExpiry(control.budget)
            gate.release()
            val failure = call.value() as PersistenceFactoryResult.Failed
            assertEquals(PersistenceFactoryFailure.TIMEOUT, failure.reason)
            assertSame(control.receipt, failure.receipt)
            assertEquals(PersistenceOwnedCallerDisposition.ABANDONED_TIMEOUT, control.state())
            assertFalse(requireNotNull(entry.attempt).callerDetached)
            assertTrue(requireNotNull(entry.attempt).cancellation.isRequested())
            assertEquals(PersistenceFactoryProcessing.PENDING, failure.receipt.state())
            assertSame(entry, binding.ledger.entries.single())
            assertFalse(scope.launch { binding.reconcileCallers() }.value(), "A contended scanner must retain, not forge, projection.")
        } finally {
            lock.unlock()
        }
        assertTrue(binding.reconcileCallers())
        assertTrue(requireNotNull(entry.attempt).callerDetached)
        assertTrue(entry.retiring)
        assertTrue(entry.retirementRequested.get())
        assertSame(entry, binding.ledger.entries.single())
        assertEquals(PersistenceFactoryProcessing.PENDING, control.receipt.state())
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["F", "G"])
    fun `one admission attempt refuses on contention and only proven unused membership is released`(held: String) = OwnedCallerTestScope().use { scope ->
        val binding = modelReadyBinding()
        val prepared = scope.gate()
        val retained = AtomicReference<PersistencePhysicalEntry>()
        val lock = if (held == "F") binding.rendezvous.lock else binding.ledger.lock
        val call = scope.launch {
            val control = PersistenceOwnedCallerControl.prepare(5_000)
            val entry = requireNotNull(binding.reserve(control))
            retained.set(entry)
            prepared.hold()
            assertFalse(binding.admit(entry))
            val result = control.failureResult() as PersistenceFactoryResult.Refused
            result.reason to binding.releaseRefused(entry)
        }
        prepared.awaitEntered()
        assertTrue(lock.tryLock())
        try {
            prepared.release()
            val (reason, released) = call.value()
            assertEquals(PersistenceFactoryFailure.BUSY, reason)
            assertEquals(held == "F", released)
            assertFalse(retained.get().dispatched)
            assertNull(binding.rendezvous.current)
            assertEquals(PersistencePhysicalOpeningPhase.UNCLAIMED, retained.get().opening)
            if (held == "G") assertSame(retained.get(), binding.ledger.entries.single())
        } finally {
            lock.unlock()
        }
        assertTrue(binding.reconcileCallers())
        assertNull(binding.ledger.entries.single())
        assertEquals(PersistenceOwnedCallerDisposition.REFUSED_BUSY, retained.get().control?.state())
    }

    @Test
    fun `worker first failure retains current until scanner projects abandonment and wakes the authentic receiver`() = OwnedCallerTestScope().use { scope ->
        val binding = PersistencePhysicalFactoryBinding(1, AtomicBoolean())
        val receiverStart = scope.gate()
        val callerGate = scope.gate()
        val behavior = OwnedCallerTestBehavior().apply {
            sampleGate = callerGate
            gateAtSample = 2
        }
        scope.beforeClose { sealOwnedModelReceiver(binding) }
        val receiver = scope.launch {
            receiverStart.hold()
            val attempt = requireNotNull(binding.rendezvous.awaitWork())
            assertTrue(binding.rendezvous.beginWork(attempt))
            // MODEL create failure, but real owned rendezvous worker settlement and Condition park.
            binding.rendezvous.creationFailed(attempt)
            binding.rendezvous.settleWorker(attempt)
            assertNull(binding.rendezvous.awaitWork())
            true
        }
        binding.rendezvous.thread = receiver.thread
        receiverStart.awaitEntered()
        receiverStart.release()
        awaitOwnedTestFact { binding.rendezvous.lock.withLock { binding.rendezvous.generation == PersistenceFactoryGeneration.WAITING } }
        binding.admissionOpen.set(true)
        val caller = scope.launch(OwnedCallerTestKind.OVERRIDING, behavior) { binding.request(5_000) }
        callerGate.awaitEntered()
        val entry = requireNotNull(binding.ledger.entries.single())
        val attempt = requireNotNull(entry.attempt)
        awaitOwnedTestFact {
            binding.rendezvous.lock.withLock {
                attempt.workerSettled && binding.rendezvous.lock.hasWaiters(binding.rendezvous.changed)
            }
        }
        assertEquals(PersistenceFactoryProcessing.PENDING, attempt.receipt.state())
        binding.rendezvous.lock.withLock {
            assertSame(attempt, binding.rendezvous.current)
            assertEquals(PersistenceFactoryGeneration.ACTIVE, binding.rendezvous.generation)
        }
        assertTrue(binding.reconcileCallers())
        assertEquals(PersistenceFactoryProcessing.PENDING, attempt.receipt.state())
        callerGate.release()
        assertEquals(PersistenceFactoryFailure.CREATE_FAILED, (caller.value() as PersistenceFactoryResult.Failed).reason)
        // No caller notification was necessary to retain failure. The scanner must wake the real receiver.
        binding.rendezvous.lock.withLock {
            // The authentic receiver is already in its occupied-ACK Condition wait. Hold F so it cannot
            // perform the scanner's finalization itself or publish WAITING before these assertions.
            assertTrue(binding.rendezvous.lock.hasWaiters(binding.rendezvous.changed))
            assertSame(attempt, binding.rendezvous.current)
            assertTrue(binding.reconcileCallers())
            assertNull(binding.rendezvous.current)
            assertEquals(PersistenceFactoryProcessing.PROCESSING_ENDED, attempt.receipt.state())
            assertEquals(PersistenceFactoryGeneration.ACTIVE, binding.rendezvous.generation)
        }
        awaitOwnedTestFact { binding.rendezvous.lock.withLock { binding.rendezvous.generation == PersistenceFactoryGeneration.WAITING } }
        assertEquals(PersistenceFactoryProcessing.PROCESSING_ENDED, attempt.receipt.state())
        assertSame(entry, binding.ledger.entries.single(), "F1 processing completion is not physical disposal.")
        assertTrue(entry.retiring)
        sealOwnedModelReceiver(binding)
        assertTrue(receiver.value())
    }

    @Test
    fun `a control cannot be installed into two records or reused after the original refusal`() {
        val binding = modelReadyBinding()
        val control = PersistenceOwnedCallerControl.prepare(5_000)
        val original = requireNotNull(binding.reserve(control))
        assertNull(binding.reserve(control))
        assertTrue(control.matchesRecord(original.record))
        assertFalse(control.matchesRecord(PersistencePhysicalRecord(0)))
        assertEquals(PersistenceOwnedCallerDisposition.REFUSED_BUSY, control.state())
        assertFalse(binding.admit(original))
        assertFalse(original.dispatched)
        assertTrue(binding.releaseRefused(original))
        assertNull(binding.reserve(control))
        assertNull(binding.ledger.entries.single())
    }

    @Test
    fun `seal before any reservation keeps the real request inert and does not invoke a worker`() {
        val shutdown = AtomicBoolean(true)
        val binding = PersistencePhysicalFactoryBinding(1, shutdown)
        val result = binding.request(5_000) as PersistenceFactoryResult.Refused
        assertEquals(PersistenceFactoryFailure.CLOSED, result.reason)
        assertNull(binding.ledger.entries.single())
        assertNull(binding.rendezvous.current)
        assertEquals(PersistenceFactoryGeneration.NEW, binding.rendezvous.generation)
        assertNull(binding.rendezvous.thread)
    }
}

/** Explicit MODEL readiness: no real factory start/Driver/terminal proof is inferred from this setup. */
internal fun modelReadyBinding(capacity: Int = 1): PersistencePhysicalFactoryBinding = PersistencePhysicalFactoryBinding(capacity, AtomicBoolean()).also {
    it.rendezvous.generation = PersistenceFactoryGeneration.WAITING
    it.admissionOpen.set(true)
}

internal fun sealOwnedModelReceiver(binding: PersistencePhysicalFactoryBinding) {
    binding.rendezvous.lock.withLock {
        binding.rendezvous.generation = PersistenceFactoryGeneration.SEALED
        binding.rendezvous.changed.signalAll()
    }
}

internal fun awaitOwnedTestExpiry(budget: PersistenceTimeBudget) = awaitOwnedTestFact { persistenceFactoryRemainingMillis(budget) == 0L }

internal fun awaitOwnedTestFact(predicate: () -> Boolean) {
    val started = System.nanoTime()
    while (!predicate()) {
        check(System.nanoTime() - started < 5_000_000_000) { "Owned model fact was not observed." }
        LockSupport.parkNanos(1_000_000)
    }
}
