package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource

/** C6 model transitions and actual caller identities. This is not real Driver/transport disposal proof. */
internal class PersistenceOwnedCallerControlTest {
    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @EnumSource(PersistenceFactoryFailure::class)
    fun `pre-admission refusal keeps its first reason and cannot dispatch or transfer`(reason: PersistenceFactoryFailure) {
        val control = PersistenceOwnedCallerControl.prepare(5_000)
        assertEquals(PersistenceOwnedCallerDisposition.PREPARED, control.state())
        assertNull(control.failureResult())
        assertFalse(control.take())
        assertTrue(control.fail(reason))
        val outcome = control.failureResult() as PersistenceFactoryResult.Refused
        assertEquals(reason, outcome.reason)
        assertEquals(PersistenceOwnedCallerPhase.REFUSED, control.state().phase)
        assertFalse(control.attach())
        assertFalse(control.take())
        for (later in PersistenceFactoryFailure.entries) assertFalse(control.fail(later))
        assertSame(outcome, control.failureResult())
        assertEquals(PersistenceFactoryProcessing.PENDING, control.receipt.state())
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @EnumSource(PersistenceFactoryFailure::class)
    fun `accepted abandonment is immediate cancellation but is not a processing or physical receipt`(reason: PersistenceFactoryFailure) {
        val control = PersistenceOwnedCallerControl.prepare(5_000)
        val attempt = PersistenceFactoryAttempt<FactoryTestValue, FactoryTestValue>(FactoryTestValue(), control.budget, control)
        assertTrue(control.attach())
        assertFalse(control.attach())
        assertFalse(attempt.cancellation.isRequested())
        assertTrue(control.fail(reason))
        val outcome = control.failureResult() as PersistenceFactoryResult.Failed
        assertEquals(reason, outcome.reason)
        assertSame(control.receipt, outcome.receipt)
        assertSame(attempt.receipt, outcome.receipt)
        assertTrue(attempt.cancellation.isRequested())
        assertFalse(attempt.callerDetached, "F's projection must not be silently credited by the atomic logical action.")
        assertFalse(attempt.workerSettled)
        assertFalse(attempt.finishIfBoth())
        assertEquals(PersistenceFactoryProcessing.PENDING, outcome.receipt.state())
        assertFalse(control.take())
        assertFalse(control.attach())
        for (later in PersistenceFactoryFailure.entries) assertFalse(control.fail(later))
        assertSame(outcome, control.failureResult())
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(booleans = [true, false])
    fun `both settlement orders share the original receipt and preserve an earlier independent failure`(workerFirst: Boolean) {
        val control = PersistenceOwnedCallerControl.prepare(5_000)
        val attempt = PersistenceFactoryAttempt<FactoryTestValue, FactoryTestValue>(FactoryTestValue(), control.budget, control)
        assertTrue(control.attach())
        attempt.beginWork()
        attempt.abandon(PersistenceFactoryFailure.CREATE_FAILED)
        if (workerFirst) attempt.settleWorker()
        assertTrue(control.fail(PersistenceFactoryFailure.TIMEOUT))
        assertTrue(attempt.projectOwnedAbandonment())
        assertFalse(attempt.projectOwnedAbandonment())
        assertEquals(PersistenceFactoryFailure.CREATE_FAILED, attempt.failure)
        if (!workerFirst) {
            assertFalse(attempt.finishIfBoth())
            assertEquals(PersistenceFactoryProcessing.PENDING, control.receipt.state())
            attempt.settleWorker()
        }
        assertTrue(attempt.finishIfBoth())
        assertEquals(PersistenceFactoryProcessing.PROCESSING_ENDED, control.receipt.state())
        assertEquals(PersistenceFactoryFailure.TIMEOUT, (control.failureResult() as PersistenceFactoryResult.Failed).reason)
        assertEquals(PersistenceOwnedCallerPhase.ABANDONED, control.state().phase)
    }

    @Test
    fun `projection of a broken worker stays unresolved rather than relabeling an earlier failure`() {
        val control = PersistenceOwnedCallerControl.prepare(5_000)
        val attempt = PersistenceFactoryAttempt<FactoryTestValue, FactoryTestValue>(FactoryTestValue(), control.budget, control)
        assertTrue(control.attach())
        attempt.breakProcessing(PersistenceFactoryFailure.BROKEN)
        attempt.settleWorker()
        assertTrue(control.fail(PersistenceFactoryFailure.CLOSED))
        assertTrue(attempt.projectOwnedAbandonment())
        assertEquals(PersistenceFactoryAttemptPhase.UNRESOLVED, attempt.phase)
        assertTrue(attempt.finishIfBoth())
        assertEquals(PersistenceFactoryFailure.BROKEN, attempt.failure)
        assertEquals(PersistenceFactoryProcessing.PROCESSING_ENDED_UNRESOLVED, control.receipt.state())
    }

    @Test
    fun `taken is terminal and does not manufacture factory processing completion`() {
        val control = PersistenceOwnedCallerControl.prepare(5_000)
        assertTrue(control.attach())
        assertTrue(control.take())
        assertFalse(control.take())
        assertFalse(control.attach())
        for (reason in PersistenceFactoryFailure.entries) assertFalse(control.fail(reason))
        assertEquals(PersistenceOwnedCallerDisposition.TAKEN, control.state())
        assertNull(control.failureResult())
        assertEquals(PersistenceFactoryProcessing.PENDING, control.receipt.state())
    }

    @Test
    fun `foreign current caller cannot bind attach abandon or take another callers cell`() = OwnedCallerTestScope().use { scope ->
        val control = PersistenceOwnedCallerControl.prepare(5_000)
        val record = PersistencePhysicalRecord(0)
        val foreign = scope.launch {
            listOf(control.bindRecord(record), control.attach(), control.fail(PersistenceFactoryFailure.CLOSED), control.take())
        }
        assertEquals(listOf(false, false, false, false), foreign.value())
        assertEquals(PersistenceOwnedCallerDisposition.PREPARED, control.state())
        assertTrue(control.bindRecord(record))
        assertTrue(control.matchesRecord(record))
        assertFalse(control.bindRecord(record))
        assertFalse(control.matchesRecord(PersistencePhysicalRecord(0)))
        assertTrue(control.attach())
        val second = scope.launch { control.fail(PersistenceFactoryFailure.CLOSED) || control.take() }
        assertFalse(second.value())
        assertEquals(PersistenceOwnedCallerDisposition.ATTACHED, control.state())
        assertTrue(control.fail(PersistenceFactoryFailure.TIMEOUT))
    }

    @Test
    fun `owned attempt rejects a replacement budget and never shares another controls receipt`() {
        val first = PersistenceOwnedCallerControl.prepare(5_000)
        val second = PersistenceOwnedCallerControl.prepare(5_000)
        assertThrows(IllegalArgumentException::class.java) {
            PersistenceFactoryAttempt<FactoryTestValue, FactoryTestValue>(FactoryTestValue(), second.budget, first)
        }
        val attempt = PersistenceFactoryAttempt<FactoryTestValue, FactoryTestValue>(FactoryTestValue(), first.budget, first)
        assertSame(first.budget, attempt.budget)
        assertSame(first.receipt, attempt.receipt)
        assertNotSame(second.receipt, attempt.receipt)
    }
}
