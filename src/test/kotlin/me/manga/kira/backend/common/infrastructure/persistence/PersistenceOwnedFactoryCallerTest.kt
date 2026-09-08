package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

/** Actual Java21 current-caller primitives. No private AQS/VirtualThread instrumentation is claimed. */
internal class PersistenceOwnedFactoryCallerTest {
    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @EnumSource(OwnedCallerTestKind::class, names = ["PLATFORM", "INHERITED", "VIRTUAL"])
    fun `direct platform inherited and virtual reads never consume the current flag`(kind: OwnedCallerTestKind) = OwnedCallerTestScope().use { scope ->
        val call = scope.launch(kind) {
            val control = PersistenceOwnedCallerControl.prepare(5_000)
            assertTrue(control.caller.isCurrent())
            assertNull(control.caller.sampleOutsideLocks())
            Thread.currentThread().interrupt()
            try {
                repeat(3) {
                    assertEquals(PersistenceFactoryFailure.INTERRUPTED, control.caller.sampleActualFlag())
                    assertEquals(PersistenceFactoryFailure.INTERRUPTED, control.caller.sampleOutsideLocks())
                    assertTrue(Thread.currentThread().isInterrupted)
                }
                assertTrue(control.fail(PersistenceFactoryFailure.INTERRUPTED))
                control.caller.restoreAfterFailure()
                assertTrue(Thread.currentThread().isInterrupted)
                control.state()
            } finally {
                // Fixture cleanup only, outside ownership locks. Production polling does not clear this flag.
                Thread.interrupted()
            }
        }
        assertEquals(PersistenceOwnedCallerDisposition.REFUSED_INTERRUPTED, call.value())
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["BLOCK", "THROW", "IGNORE"])
    fun `inherited field read does not invoke an overridden self interrupt`(mode: String) = OwnedCallerTestScope().use { scope ->
        val behavior = OwnedCallerTestBehavior().apply {
            if (mode == "BLOCK") restoreGate = scope.gate()
            if (mode == "THROW") restoreFailure = OwnedCallerTestFailure()
            ignoreRestore = mode == "IGNORE"
        }
        val call = scope.launch(OwnedCallerTestKind.INHERITED_FLAG_OVERRIDING_INTERRUPT, behavior) {
            val control = PersistenceOwnedCallerControl.prepare(5_000)
            val current = Thread.currentThread() as InheritedFlagCallerThread
            current.setActualFlag()
            try {
                assertEquals(PersistenceFactoryFailure.INTERRUPTED, control.caller.sampleOutsideLocks())
                assertEquals(PersistenceFactoryFailure.INTERRUPTED, control.caller.sampleActualFlag())
                assertTrue(control.fail(PersistenceFactoryFailure.INTERRUPTED))
                control.caller.restoreAfterFailure()
                assertTrue(current.isInterrupted)
                true
            } finally {
                Thread.interrupted()
            }
        }
        assertTrue(call.value())
        assertEquals(0, behavior.restores.get())
        assertEquals(0, behavior.samples.get())
        assertEquals(0, (behavior.restoreFailure as? OwnedCallerTestFailure)?.reads?.get() ?: 0)
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(booleans = [true, false])
    fun `false override cannot hide real interruption and restoration follows logical abandonment`(ignore: Boolean) = OwnedCallerTestScope().use { scope ->
        val behavior = OwnedCallerTestBehavior().apply { ignoreRestore = ignore }
        val call = scope.launch(OwnedCallerTestKind.OVERRIDING, behavior) {
            val control = PersistenceOwnedCallerControl.prepare(5_000)
            assertEquals(0, behavior.samples.get(), "Public method metadata must not invoke the override.")
            assertTrue(control.attach())
            val current = Thread.currentThread() as OverridingCallerThread
            current.setActualFlag()
            val f = ReentrantLock()
            val g = ReentrantLock()
            assertTrue(f.tryLock())
            try {
                assertTrue(g.tryLock())
                try {
                    assertEquals(PersistenceFactoryFailure.INTERRUPTED, control.caller.sampleActualFlag())
                    assertFalse(current.actualFlag())
                    assertEquals(0, behavior.samples.get())
                    assertEquals(0, behavior.restores.get())
                    assertTrue(control.fail(PersistenceFactoryFailure.INTERRUPTED))
                } finally {
                    g.unlock()
                }
            } finally {
                f.unlock()
            }
            try {
                assertEquals(PersistenceOwnedCallerDisposition.ABANDONED_INTERRUPTED, control.state())
                control.caller.restoreAfterFailure()
                assertEquals(!ignore, current.actualFlag())
                control.caller.restoreAfterFailure()
                assertEquals(1, behavior.restores.get())
                assertEquals(PersistenceFactoryProcessing.PENDING, control.receipt.state())
                true
            } finally {
                Thread.interrupted()
            }
        }
        assertTrue(call.value())
    }

    @Test
    fun `an outside true override remains sticky even with a false real flag and later false report`() = OwnedCallerTestScope().use { scope ->
        val behavior = OwnedCallerTestBehavior().apply { reportedFlag = true }
        val call = scope.launch(OwnedCallerTestKind.OVERRIDING, behavior) {
            val control = PersistenceOwnedCallerControl.prepare(5_000)
            assertFalse((Thread.currentThread() as OverridingCallerThread).actualFlag())
            assertEquals(PersistenceFactoryFailure.INTERRUPTED, control.caller.sampleOutsideLocks())
            behavior.reportedFlag = false
            assertEquals(PersistenceFactoryFailure.INTERRUPTED, control.caller.sampleOutsideLocks())
            assertEquals(PersistenceFactoryFailure.INTERRUPTED, control.caller.sampleActualFlag())
            assertTrue(control.fail(PersistenceFactoryFailure.INTERRUPTED))
            control.caller.restoreAfterFailure()
            assertEquals(0, behavior.restores.get(), "A reported override truth is not a consumed actual interrupt event.")
            control.state()
        }
        assertEquals(PersistenceOwnedCallerDisposition.REFUSED_INTERRUPTED, call.value())
        assertEquals(2, behavior.samples.get())
    }

    @Test
    fun `stuck restoration cannot undo abandonment or prevent independent model processing settlement`() = OwnedCallerTestScope().use { scope ->
        val gate = scope.gate()
        val behavior = OwnedCallerTestBehavior().apply { restoreGate = gate }
        val retained = AtomicReference<PersistenceFactoryAttempt<FactoryTestValue, FactoryTestValue>>()
        val call = scope.launch(OwnedCallerTestKind.OVERRIDING, behavior) {
            val control = PersistenceOwnedCallerControl.prepare(5_000)
            val attempt = PersistenceFactoryAttempt<FactoryTestValue, FactoryTestValue>(FactoryTestValue(), control.budget, control)
            retained.set(attempt)
            assertTrue(control.attach())
            (Thread.currentThread() as OverridingCallerThread).setActualFlag()
            assertEquals(PersistenceFactoryFailure.INTERRUPTED, control.caller.sampleActualFlag())
            control.fail(PersistenceFactoryFailure.INTERRUPTED)
            try {
                control.caller.restoreAfterFailure()
                true
            } finally {
                Thread.interrupted()
            }
        }
        gate.awaitEntered()
        val attempt = retained.get()
        assertEquals(PersistenceOwnedCallerDisposition.ABANDONED_INTERRUPTED, attempt.ownedControl?.state())
        assertTrue(attempt.cancellation.isRequested())
        // Explicit MODEL: these are the two-party state transitions, not real terminal/native disposal.
        assertTrue(attempt.projectOwnedAbandonment())
        attempt.settleWorker()
        assertTrue(attempt.finishIfBoth())
        assertEquals(PersistenceFactoryProcessing.PROCESSING_ENDED, attempt.receipt.state())
        assertTrue(call.thread.isAlive)
        gate.release()
        assertTrue(call.value())
    }

    @Test
    fun `foreign sampling and restoration neither invoke overrides nor clear another caller flag`() = OwnedCallerTestScope().use { scope ->
        val behavior = OwnedCallerTestBehavior()
        val ready = scope.gate()
        val captured = AtomicReference<PersistenceOwnedCallerControl>()
        val call = scope.launch(OwnedCallerTestKind.OVERRIDING, behavior) {
            val control = PersistenceOwnedCallerControl.prepare(5_000)
            val current = Thread.currentThread() as OverridingCallerThread
            try {
                current.setActualFlag()
                assertEquals(PersistenceFactoryFailure.INTERRUPTED, control.caller.sampleActualFlag())
                assertFalse(current.actualFlag(), "A real consumed flag establishes the pending restoration obligation.")
                assertTrue(control.fail(PersistenceFactoryFailure.INTERRUPTED))
                current.setActualFlag() // A fresh real flag must also survive every foreign operation.
                captured.set(control)
                ready.hold()
                assertTrue(current.actualFlag())
                assertEquals(0, behavior.restores.get())
                control.caller.restoreAfterFailure()
                assertTrue(current.actualFlag())
                control.state()
            } finally {
                Thread.interrupted()
            }
        }
        ready.awaitEntered()
        val control = captured.get()
        assertTrue(call.thread.isAlive)
        assertTrue((call.thread as OverridingCallerThread).actualFlag())
        val foreign = scope.launch {
            Thread.currentThread().interrupt()
            try {
                assertFalse(control.caller.isCurrent())
                assertEquals(PersistenceFactoryFailure.COORDINATION_FAILED, control.caller.sampleOutsideLocks())
                assertTrue(Thread.currentThread().isInterrupted)
                assertEquals(PersistenceFactoryFailure.COORDINATION_FAILED, control.caller.sampleActualFlag())
                assertTrue(Thread.currentThread().isInterrupted)
                control.caller.restoreAfterFailure()
                assertTrue(Thread.currentThread().isInterrupted)
                true
            } finally {
                Thread.interrupted()
            }
        }
        assertTrue(foreign.value())
        assertEquals(0, behavior.samples.get())
        assertEquals(0, behavior.restores.get())
        assertTrue((call.thread as OverridingCallerThread).actualFlag())
        ready.release()
        assertEquals(PersistenceOwnedCallerDisposition.REFUSED_INTERRUPTED, call.value())
        assertEquals(1, behavior.restores.get(), "Only the live bound caller may consume its pending restoration obligation.")
    }

    @Test
    fun `request catches a throwing caller sample without rendering its graph or dispatching`() = OwnedCallerTestScope().use { scope ->
        val failure = OwnedCallerTestFailure()
        val behavior = OwnedCallerTestBehavior().apply { sampleFailure = failure }
        val binding = PersistencePhysicalFactoryBinding(1, java.util.concurrent.atomic.AtomicBoolean())
        val call = scope.launch(OwnedCallerTestKind.OVERRIDING, behavior) { binding.request(5_000) }
        val result = call.value() as PersistenceFactoryResult.Refused
        assertEquals(PersistenceFactoryFailure.COORDINATION_FAILED, result.reason)
        assertEquals(0, binding.ledger.entries.count { it != null })
        assertEquals(0, failure.reads.get())
        assertSame(failure, behavior.sampleFailure)
        assertEquals("synthetic-owned-caller-failure", failure.message)
        assertEquals(1, failure.reads.get())
    }
}

internal class OwnedCallerTestFailure : RuntimeException(null, null, false, false) {
    val reads = AtomicInteger()
    override val message: String
        get() {
            reads.incrementAndGet()
            return "synthetic-owned-caller-failure"
        }
    override val cause: Throwable?
        get() {
            reads.incrementAndGet()
            return null
        }

    override fun toString(): String {
        reads.incrementAndGet()
        return "OwnedCallerTestFailure"
    }
}
