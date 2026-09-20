package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Real request failure stacks and locks; opening/resource disposal facts remain explicitly unmodeled. */
class PersistenceOwnedFactoryFailureTest {
    @Test
    fun `fatal caller sample before reservation is propagated without dispatch or throwable graph access`() = OwnedCallerTestScope().use { scope ->
        val failure = OwnedCallerTestFatal()
        val behavior = OwnedCallerTestBehavior().apply { sampleFailure = failure }
        val binding = modelReadyBinding()
        val call = scope.launch(OwnedCallerTestKind.OVERRIDING, behavior) { binding.request(5_000) }
        assertSame(failure, call.problem())
        assertNull(binding.ledger.entries.single())
        assertNull(binding.rendezvous.current)
        assertEquals(0, failure.reads.get())
        assertEquals(0, behavior.restores.get())
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @CsvSource("F,RUNTIME", "G,RUNTIME", "F,FATAL", "G,FATAL", "F,BARE", "G,BARE")
    fun `every throwing accepted sample detaches before result handling despite a contended ownership lock`(held: String, kind: String) =
        OwnedCallerTestScope().use { scope ->
            val binding = modelReadyBinding()
            val gate = scope.gate()
            val behavior = OwnedCallerTestBehavior().apply {
                sampleGate = gate
                gateAtSample = 2
            }
            val runtime = OwnedCallerTestFailure()
            val fatal = OwnedCallerTestFatal()
            val bare = OwnedCallerTestBareFailure()
            val failure = when (kind) {
                "FATAL" -> fatal
                "BARE" -> bare
                else -> runtime
            }
            val call = scope.launch(OwnedCallerTestKind.OVERRIDING, behavior) { binding.request(5_000) }
            gate.awaitEntered()
            val entry = requireNotNull(binding.ledger.entries.single())
            val control = requireNotNull(entry.control)
            val attempt = requireNotNull(entry.attempt)
            assertEquals(PersistenceOwnedCallerDisposition.ATTACHED, control.state())
            val lock = if (held == "F") binding.rendezvous.lock else binding.ledger.lock
            assertTrue(lock.tryLock())
            try {
                behavior.sampleFailure = failure
                gate.release()
                if (kind == "FATAL") {
                    assertSame(failure, call.problem())
                } else {
                    val result = call.value() as PersistenceFactoryResult.Failed
                    assertEquals(PersistenceFactoryFailure.COORDINATION_FAILED, result.reason)
                    assertSame(control.receipt, result.receipt)
                }
                assertEquals(PersistenceOwnedCallerDisposition.ABANDONED_COORDINATION_FAILED, control.state())
                assertTrue(attempt.cancellation.isRequested())
                assertFalse(attempt.callerDetached, "F/G projection has not run; logical failure alone is authoritative.")
                assertEquals(PersistenceFactoryProcessing.PENDING, attempt.receipt.state())
                assertSame(entry, binding.ledger.entries.single())
            } finally {
                lock.unlock()
            }
            assertTrue(binding.reconcileCallers())
            assertTrue(attempt.callerDetached)
            assertTrue(entry.retirementRequested.get())
            assertEquals(PersistenceFactoryProcessing.PENDING, attempt.receipt.state())
            assertEquals(0, runtime.reads.get() + fatal.reads.get() + bare.reads.get())
        }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["RUNTIME", "FATAL", "IGNORE"])
    fun `request restoration failure cannot undo interruption abandonment or render a supplied throwable`(mode: String) = OwnedCallerTestScope().use { scope ->
        val binding = modelReadyBinding()
        val sampleGate = scope.gate()
        val runtime = OwnedCallerTestFailure()
        val fatal = OwnedCallerTestFatal()
        val behavior = OwnedCallerTestBehavior().apply {
            this.sampleGate = sampleGate
            gateAtSample = 2
            restoreFailure = when (mode) {
                "RUNTIME" -> runtime
                "FATAL" -> fatal
                else -> null
            }
            ignoreRestore = mode == "IGNORE"
        }
        val restoredFlag = AtomicBoolean(true)
        val call = scope.launch(OwnedCallerTestKind.OVERRIDING, behavior) {
            try {
                binding.request(5_000).also { restoredFlag.set((Thread.currentThread() as OverridingCallerThread).actualFlag()) }
            } finally {
                Thread.interrupted()
            }
        }
        sampleGate.awaitEntered()
        val entry = requireNotNull(binding.ledger.entries.single())
        val control = requireNotNull(entry.control)
        // An owned synthetic platform Thread; the interruption is real and the sample gate holds no F/G/T lock.
        (call.thread as OverridingCallerThread).setActualFlag()
        sampleGate.release()
        if (mode == "FATAL") {
            assertSame(fatal, call.problem())
        } else {
            val result = call.value() as PersistenceFactoryResult.Failed
            assertEquals(PersistenceFactoryFailure.INTERRUPTED, result.reason)
            assertSame(control.receipt, result.receipt)
            assertFalse(restoredFlag.get(), "The fixture deliberately throws or ignores restoration before setting the real flag.")
        }
        assertEquals(PersistenceOwnedCallerDisposition.ABANDONED_INTERRUPTED, control.state())
        assertTrue(requireNotNull(entry.attempt).cancellation.isRequested())
        assertEquals(PersistenceFactoryProcessing.PENDING, control.receipt.state())
        assertTrue(binding.reconcileCallers())
        assertTrue(requireNotNull(entry.attempt).callerDetached)
        assertEquals(1, behavior.restores.get())
        assertEquals(0, runtime.reads.get() + fatal.reads.get())
    }

    @Test
    fun `a request held in restoration leaves locks free and its original failure available for reconciliation`() = OwnedCallerTestScope().use { scope ->
        val binding = modelReadyBinding()
        val sampleGate = scope.gate()
        val restoreGate = scope.gate()
        val behavior = OwnedCallerTestBehavior().apply {
            this.sampleGate = sampleGate
            this.restoreGate = restoreGate
            gateAtSample = 2
        }
        val call = scope.launch(OwnedCallerTestKind.OVERRIDING, behavior) {
            try {
                binding.request(5_000)
            } finally {
                Thread.interrupted()
            }
        }
        sampleGate.awaitEntered()
        val entry = requireNotNull(binding.ledger.entries.single())
        (call.thread as OverridingCallerThread).setActualFlag()
        sampleGate.release()
        restoreGate.awaitEntered()
        assertTrue(call.thread.isAlive)
        assertEquals(PersistenceOwnedCallerDisposition.ABANDONED_INTERRUPTED, entry.control?.state())
        assertTrue(binding.reconcileCallers(), "Held caller restoration must not own F or G.")
        assertTrue(requireNotNull(entry.attempt).callerDetached)
        assertTrue(entry.retirementRequested.get())
        assertEquals(PersistenceFactoryProcessing.PENDING, entry.control?.receipt?.state(), "No worker/resource completion is fabricated.")
        restoreGate.release()
        assertEquals(PersistenceFactoryFailure.INTERRUPTED, (call.value() as PersistenceFactoryResult.Failed).reason)
        assertEquals(1, behavior.restores.get())
    }
}

internal class OwnedCallerTestFatal : Error(null, null, false, false) {
    val reads = AtomicInteger()
    override val message: String
        get() {
            reads.incrementAndGet()
            return "synthetic-owned-caller-fatal"
        }
    override val cause: Throwable?
        get() {
            reads.incrementAndGet()
            return null
        }

    override fun toString(): String {
        reads.incrementAndGet()
        return "OwnedCallerTestFatal"
    }
}

internal class OwnedCallerTestBareFailure : Throwable(null, null, false, false) {
    val reads = AtomicInteger()
    override val message: String
        get() {
            reads.incrementAndGet()
            return "synthetic-owned-caller-bare-failure"
        }
    override val cause: Throwable?
        get() {
            reads.incrementAndGet()
            return null
        }

    override fun toString(): String {
        reads.incrementAndGet()
        return "OwnedCallerTestBareFailure"
    }
}
