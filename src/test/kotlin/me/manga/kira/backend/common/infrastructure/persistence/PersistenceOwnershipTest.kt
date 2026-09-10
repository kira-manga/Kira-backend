package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.withLock

/** Producer foundation with MODEL opening facts. These are not JDBC facade, Hikari or database disposal tests. */
class PersistenceOwnershipTest {
    @Test
    fun `opaque final claim permanently refuses epoch promotion without touching the raw connection`() {
        val raw = PhysicalTestConnection()
        val fixture = PersistenceOwnershipTestFixture(raw = raw.raw)
        val prepared = fixture.entry.jdbc.prepareEpoch()
        val opaque = PersistenceFactoryResult.Success(fixture.entry.candidate, fixture.control.receipt)
        assertTrue(fixture.binding.take(fixture.entry, opaque))
        assertFalse(fixture.install(prepared))
        assertFalse(fixture.install(fixture.entry.jdbc.prepareEpoch()))
        assertNull(prepared.enterForeground())
        assertEquals(PersistenceOwnedCallerDisposition.TAKEN, fixture.control.state())
        assertEquals(0, raw.calls.get())
    }

    @Test
    fun `actual retirement projection preserves CLOSED before opaque delivery exclusion`() {
        val raw = PhysicalTestConnection()
        val fixture = PersistenceOwnershipTestFixture(raw = raw.raw)
        val opaque = PersistenceFactoryResult.Success(fixture.entry.candidate, fixture.control.receipt)
        assertSame(fixture.work, fixture.retire())
        assertFalse(fixture.binding.take(fixture.entry, opaque))
        assertEquals(PersistenceOwnedCallerDisposition.ABANDONED_CLOSED, fixture.control.state())
        val failure = fixture.control.failureResult() as PersistenceFactoryResult.Failed
        assertEquals(PersistenceFactoryFailure.CLOSED, failure.reason)
        assertSame(opaque.receipt, failure.receipt)
        assertFalse(fixture.attempt.transferred)
        assertSame(fixture.entry, fixture.binding.ledger.entries.single())
        assertEquals(PersistenceFactoryProcessing.PENDING, opaque.receipt.state())
        assertEquals(0, raw.calls.get())
    }

    @Test
    fun `ordinary foreground nesting has no small depth cap and zero needs a permanent terminal seal`() {
        val fixture = PersistenceOwnershipTestFixture()
        val epoch = fixture.install()
        val calls = List(96) { requireNotNull(epoch.enterForeground()) }
        assertFalse(calls.first().finish(PersistenceJdbcCallOutcome.RETURNED), "An outer frame cannot skip its actual nested tail.")
        assertNull(calls.first().outcome())
        calls.asReversed().forEach { assertTrue(it.finish(PersistenceJdbcCallOutcome.RETURNED)) }
        assertFalse(calls.first().finish(PersistenceJdbcCallOutcome.WRAPPING_FAILURE))
        assertFalse(epoch.foregroundActive())
        assertFalse(epoch.poisoned())
        assertFalse(epoch.sealedAndEnded(), "An unsealed zero is not quiescence.")
        assertTrue(epoch.seal())
        assertTrue(epoch.sealedAndEnded())
        assertFalse(fixture.entry.jdbc.postOpeningCallsEnded(), "A reusable epoch seal alone is not permanent terminal fencing.")
        fixture.retire()
        assertTrue(fixture.entry.jdbc.postOpeningCallsEnded())
        assertNull(epoch.enterForeground())
        assertNull(epoch.enterCancellation())
    }

    @Test
    fun `unended frames block successors and stale duplicate or fabricated ends cannot decrement or poison a successor`() {
        val fixture = PersistenceOwnershipTestFixture()
        val old = fixture.install()
        val retained = requireNotNull(old.enterForeground())
        val next = fixture.entry.jdbc.prepareEpoch()
        assertFalse(fixture.advance(old, next))
        assertTrue(old.seal())
        assertFalse(fixture.advance(old, next), "A lost actual end stays counted even after sealing.")
        assertTrue(retained.finish(PersistenceJdbcCallOutcome.RETURNED))
        assertTrue(fixture.advance(old, next))
        assertFalse(retained.finish(PersistenceJdbcCallOutcome.CLEANUP_FAILURE))
        assertNull(old.enterCancellation())
        val actual = requireNotNull(next.enterCancellation())
        val fabricated = PersistenceProducerEpoch.Call.prepare(next, Any(), Thread.currentThread(), PersistenceProducerEpoch.Kind.CANCELLATION, null)
        assertFalse(fabricated.finish(PersistenceJdbcCallOutcome.OWNED_FAILURE))
        assertNull(fabricated.outcome())
        assertEquals(1L, next.activeCancellations())
        assertFalse(next.poisoned())
        assertFalse(fixture.entry.retirementRequested.get())
        assertTrue(actual.finish(PersistenceJdbcCallOutcome.RETURNED))
        assertEquals(0L, next.activeCancellations())
    }

    @Test
    fun `authentic nested admission and poison end publish while another thread holds G`() = OwnedCallerTestScope().use { scope ->
        val held = scope.gate()
        val retained = AtomicReference<Pair<PersistenceOwnershipTestFixture, PersistenceProducerEpoch>>()
        val caller = scope.launch {
            val fixture = PersistenceOwnershipTestFixture()
            val epoch = fixture.install()
            val outer = requireNotNull(epoch.enterForeground())
            retained.set(fixture to epoch)
            held.hold()
            val nested = requireNotNull(epoch.enterForeground()) // The original caller, but G is now held elsewhere.
            check(nested.finish(PersistenceJdbcCallOutcome.RETURNED))
            outer.finish(PersistenceJdbcCallOutcome.CLEANUP_FAILURE)
        }
        held.awaitEntered()
        val (fixture, epoch) = retained.get()
        fixture.binding.ledger.lock.withLock {
            held.release()
            assertTrue(caller.value(), "G contention must not lose the actual end or require a later retry.")
            assertTrue(fixture.entry.retirementRequested.get())
            assertTrue(epoch.poisoned())
            assertFalse(epoch.foregroundActive())
            assertFalse(fixture.entry.jdbc.postOpeningCallsEnded())
        }
        fixture.retire()
        assertTrue(fixture.entry.jdbc.postOpeningCallsEnded())
    }

    @Test
    fun `simultaneous cancellation callers all end under held G without releasing the foreground lineage`() = OwnedCallerTestScope().use { scope ->
        val fixture = PersistenceOwnershipTestFixture()
        val epoch = fixture.install()
        val foreground = requireNotNull(epoch.enterForeground())
        val gates = List(12) { scope.gate() }
        val callers = gates.map { gate ->
            scope.launch {
                val cancel = requireNotNull(epoch.enterCancellation())
                gate.hold()
                cancel.finish(PersistenceJdbcCallOutcome.RETURNED)
            }
        }
        gates.forEach(OwnedCallerTestGate::awaitEntered)
        assertEquals(12L, epoch.activeCancellations())
        assertTrue(epoch.seal())
        assertNull(epoch.enterCancellation())
        fixture.binding.ledger.lock.withLock {
            gates.forEach(OwnedCallerTestGate::release)
            callers.forEach { assertTrue(it.value()) }
            assertEquals(0L, epoch.activeCancellations())
            assertTrue(epoch.foregroundActive())
            assertFalse(epoch.sealedAndEnded())
        }
        assertTrue(foreground.finish(PersistenceJdbcCallOutcome.RETURNED))
        assertTrue(epoch.sealedAndEnded())
        fixture.retire()
        assertTrue(fixture.entry.jdbc.postOpeningCallsEnded())
    }

    @Test
    fun `foreign foreground entry and foreign token end cannot impersonate the original caller`() = OwnedCallerTestScope().use { scope ->
        val fixture = PersistenceOwnershipTestFixture()
        val epoch = fixture.install()
        val foreground = requireNotNull(epoch.enterForeground())
        assertTrue(
            scope.launch {
                assertNull(epoch.enterForeground())
                assertFalse(foreground.finish(PersistenceJdbcCallOutcome.WRAPPING_FAILURE))
                assertFalse(epoch.seal())
                true
            }.value(),
        )
        assertTrue(epoch.foregroundActive())
        assertNull(foreground.outcome())
        assertFalse(epoch.poisoned())
        assertTrue(foreground.finish(PersistenceJdbcCallOutcome.RETURNED))
    }

    @Test
    fun `supplied budget is not restarted and ordinary no-budget business failure does not poison`() {
        val fixture = PersistenceOwnershipTestFixture()
        val epoch = fixture.install()
        val clock = AtomicLong()
        val budget = PersistenceTimeBudget.start(10, clock::get)
        clock.set(10_000_000)
        assertNull(epoch.enterForeground(budget))
        assertNull(epoch.enterCancellation(budget))
        val ordinary = requireNotNull(epoch.enterForeground())
        assertTrue(ordinary.finish(PersistenceJdbcCallOutcome.ORDINARY_FAILURE))
        assertFalse(epoch.poisoned())
        assertFalse(fixture.entry.retirementRequested.get())
        assertFalse(epoch.foregroundActive())
    }
}
