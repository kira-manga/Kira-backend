package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.withLock

/** MODEL graph calls atop real typed delivery/producer tokens, not native resource-disposal tests. */
class PersistenceJdbcGuardProtocolTest {
    @Test
    fun `original work expiry does not manufacture a new budget or revoke preauthenticated cleanup before terminal seal`() {
        val fixture = GuardCoreFixture()
        val clock = AtomicLong()
        val budget = PersistenceTimeBudget.start(10, clock::get)
        val cleanup = requireNotNull(fixture.prepared.epoch.prepareCleanup())
        clock.set(10_000_000)
        assertNull(fixture.prepared.epoch.enterForeground(budget))
        val actual = requireNotNull(fixture.prepared.epoch.enterCleanup(cleanup))
        fixture.model.retire()
        assertNull(fixture.prepared.epoch.enterCleanup(cleanup))
        assertFalse(fixture.model.entry.jdbc.postOpeningCallsEnded())
        assertTrue(actual.finish(PersistenceJdbcCallOutcome.RETURNED))
        assertTrue(fixture.model.entry.jdbc.postOpeningCallsEnded())
    }

    @Test
    fun `different calls authenticate the same owner tuple without equating resource objects or consulting them`() {
        val fixture = GuardCoreFixture()
        val first = fixture.context.enterRoot(PersistenceJdbcGuardCallKind.BUSINESS)
        val identity = first.identity
        first.finish()
        val next = fixture.context.enterRoot(PersistenceJdbcGuardCallKind.BUSINESS)
        assertNotSame(identity, next.identity)
        assertTrue(fixture.context.sameOwner(identity, next.identity))
        fixture.context.requireCurrent(identity)
        next.finish()
    }

    @Test
    fun `foreign context identity and foreign foreground use are refused without an admission count`() = OwnedCallerTestScope().use { scope ->
        val fixture = GuardCoreFixture()
        val other = GuardCoreFixture()
        val call = fixture.context.enterRoot(PersistenceJdbcGuardCallKind.BUSINESS)
        assertThrows<SQLException> { other.context.enter(call.identity, PersistenceJdbcGuardCallKind.BUSINESS) }
        assertTrue(
            scope.launch {
                assertThrows<SQLException> { fixture.context.enter(call.identity, PersistenceJdbcGuardCallKind.BUSINESS) }
                assertThrows<SQLException> { fixture.context.requireCurrent(call.identity) }
                true
            }.value(),
        )
        assertTrue(fixture.prepared.epoch.foregroundActive())
        assertFalse(other.prepared.epoch.foregroundActive())
        call.finish()
    }

    @Test
    fun `foreign cancellation checks the exact open child without gaining foreground authority and ends under G contention`() =
        OwnedCallerTestScope().use { scope ->
            val fixture = GuardCoreFixture()
            val foreground = fixture.context.enterRoot(PersistenceJdbcGuardCallKind.BUSINESS)
            val child = fixture.context.registerChild(foreground.identity)
            fixture.model.binding.ledger.lock.withLock {
                assertTrue(
                    scope.launch {
                        val cancel = fixture.context.enter(foreground.identity, PersistenceJdbcGuardCallKind.CANCELLATION)
                        try {
                            child.requireOpen()
                            assertThrows<SQLException> { fixture.context.enter(foreground.identity, PersistenceJdbcGuardCallKind.BUSINESS) }
                        } finally {
                            cancel.finish()
                        }
                        true
                    }.value(),
                )
                assertEquals(0L, fixture.prepared.epoch.activeCancellations())
                assertTrue(fixture.prepared.epoch.foregroundActive())
            }
            foreground.finish()
            val cleanup = fixture.context.enter(foreground.identity, PersistenceJdbcGuardCallKind.CLEANUP)
            assertTrue(child.beginClose())
            child.closeReturned()
            cleanup.finish()
            assertEquals(0L, fixture.context.liveChildren())
        }

    @Test
    fun `business stop and poison leave only existing cleanup authority until permanent terminal seal`() {
        val fixture = GuardCoreFixture()
        val work = fixture.context.enterRoot(PersistenceJdbcGuardCallKind.BUSINESS)
        val identity = work.identity
        assertTrue(fixture.prepared.epoch.stopBusiness(identity.cleanup))
        work.finish()
        assertThrows<SQLException> { fixture.context.enter(identity, PersistenceJdbcGuardCallKind.BUSINESS) }
        val cleanup = fixture.context.enter(identity, PersistenceJdbcGuardCallKind.CLEANUP)
        val safe = cleanup.failure(IllegalStateException("model reset failure")) as SQLException
        assertNull(safe.cause)
        assertTrue(fixture.model.entry.retirementRequested.get())
        assertTrue(fixture.prepared.epoch.poisoned())
        val nestedCleanup = fixture.context.enter(identity, PersistenceJdbcGuardCallKind.CLEANUP)
        nestedCleanup.finish()
        fixture.model.retire()
        assertThrows<SQLException> { fixture.context.enter(identity, PersistenceJdbcGuardCallKind.CLEANUP) }
        assertFalse(fixture.model.entry.jdbc.postOpeningCallsEnded())
        cleanup.finish()
        assertTrue(fixture.model.entry.jdbc.postOpeningCallsEnded())
    }

    @ParameterizedTest(name = "{displayName} [{index}] explicitWrapping={0}")
    @ValueSource(booleans = [false, true])
    fun `explicit wrapping or uncleared output poisons before boxing and actual end without unsafe Throwable reads`(explicitWrapping: Boolean) {
        val fixture = GuardCoreFixture()
        val call = fixture.context.enterRoot(PersistenceJdbcGuardCallKind.BUSINESS)
        val rawOutput = Any()
        val hostile = PhysicalHostileFailure()
        call.captureOutput(rawOutput)
        call.failedBeforeBoxing(wrapping = explicitWrapping)
        assertTrue(fixture.prepared.epoch.poisoned())
        assertTrue(fixture.prepared.epoch.foregroundActive())
        val safe = call.failure(hostile, wrapping = explicitWrapping) as SQLException
        assertNull(safe.cause)
        assertEquals(0, safe.suppressed.size)
        assertEquals(0, hostile.reads.get())
        call.finish()
        assertTrue(fixture.context.graphFailed())
        assertFalse(fixture.prepared.epoch.foregroundActive())
        fixture.model.retire()
        assertTrue(fixture.model.entry.jdbc.postOpeningCallsEnded(), "Failed wrapping is not a permanently executing producer.")
    }

    @Test
    fun `failed first child close cannot become clean through a duplicate and does not fake a forever executing native call`() {
        val fixture = GuardCoreFixture()
        val creating = fixture.context.enterRoot(PersistenceJdbcGuardCallKind.BUSINESS)
        val child = fixture.context.registerChild(creating.identity)
        creating.finish()
        val closing = fixture.context.enter(creating.identity, PersistenceJdbcGuardCallKind.CLEANUP)
        assertTrue(child.beginClose())
        child.closeFailed()
        closing.failure(SQLException("model child close failure", "58000"))
        closing.finish()
        assertFalse(child.beginClose())
        assertThrows<IllegalStateException> { child.closeReturned() }
        assertEquals(0L, fixture.context.liveChildren())
        assertTrue(fixture.context.graphFailed())
        fixture.model.retire()
        assertFalse(child.beginClose())
        assertTrue(fixture.model.entry.jdbc.postOpeningCallsEnded())
    }

    @Test
    fun `foreign duplicate and out of order guard ends cannot release or poison another actual frame`() = OwnedCallerTestScope().use { scope ->
        val fixture = GuardCoreFixture()
        val outer = fixture.context.enterRoot(PersistenceJdbcGuardCallKind.BUSINESS)
        val inner = fixture.context.enterRoot(PersistenceJdbcGuardCallKind.BUSINESS)
        assertThrows<IllegalStateException> { outer.finish() }
        assertTrue(
            scope.launch {
                assertThrows<IllegalStateException> { inner.finish() }
                true
            }.value(),
        )
        inner.finish()
        outer.finish()
        val next = fixture.context.enterRoot(PersistenceJdbcGuardCallKind.BUSINESS)
        assertThrows<IllegalStateException> { outer.finish() }
        assertThrows<IllegalStateException> { outer.failure(IllegalStateException(), wrapping = true) }
        assertFalse(fixture.prepared.epoch.poisoned())
        assertTrue(fixture.prepared.epoch.foregroundActive())
        next.finish()
    }

    @Test
    fun `fabricated child cannot decrement the genuine private child summary`() {
        val fixture = GuardCoreFixture()
        val call = fixture.context.enterRoot(PersistenceJdbcGuardCallKind.BUSINESS)
        val genuine = fixture.context.registerChild(call.identity)
        val fabricated = PersistenceJdbcChild.prepare(fixture.context, call.identity, Any())
        assertThrows<SQLException> { fabricated.requireOpen() }
        assertThrows<SQLException> { fabricated.beginClose() }
        assertThrows<IllegalStateException> { fabricated.closeReturned() }
        assertEquals(1L, fixture.context.liveChildren())
        assertTrue(genuine.beginClose())
        genuine.closeReturned()
        assertEquals(0L, fixture.context.liveChildren())
        call.finish()
    }

    @Test
    fun `ordinary business failure remains exact while compatibility-only provenance never certifies strict reuse`() {
        val fixture = GuardCoreFixture()
        val call = fixture.context.enterRoot(PersistenceJdbcGuardCallKind.BUSINESS)
        val failure = SQLException("model ordinary SQLState", "23505")
        assertSame(failure, call.failure(failure))
        fixture.context.ordinaryCompatibilityOnly()
        call.finish()
        assertFalse(fixture.prepared.epoch.poisoned())
        assertTrue(fixture.context.hasOrdinaryOnlyProvenance())
        assertFalse(fixture.context.graphFailed())
    }
}

private class GuardCoreFixture {
    val model = PersistenceOwnershipTestFixture()
    val prepared = PreparedPoolConnection.prepare(model.entry, model.binding)

    // Test a separate MODEL graph without exposing the real lower facade's private context/raw state.
    val context = PersistenceJdbcGuardContext.prepare(model.entry.jdbc, model.binding.poolIdentity, prepared.epoch)

    init {
        check(model.binding.takePoolConnection(model.entry, prepared))
    }
}
