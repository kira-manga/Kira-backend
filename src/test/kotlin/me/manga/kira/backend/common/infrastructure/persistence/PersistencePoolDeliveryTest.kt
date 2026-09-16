package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.Connection
import java.sql.SQLException

/** MODEL opening facts, real typed F→G final claim. Not Hikari, driver or database-disposal evidence. */
class PersistencePoolDeliveryTest {
    @Test
    fun `an unselected cold facade cannot borrow the winning preparation's exact epoch or request its retirement`() {
        val raw = PhysicalTestConnection()
        val fixture = PersistenceOwnershipTestFixture(raw = raw.raw)
        val selected = PreparedPoolConnection.prepare(fixture.entry, fixture.binding)
        val unselected = PreparedPoolConnection.prepare(fixture.entry, fixture.binding)
        assertTrue(fixture.binding.takePoolConnection(fixture.entry, selected))
        assertFalse(fixture.binding.takePoolConnection(fixture.entry, unselected))
        assertThrows<SQLException> { unselected.result.value.unwrap(Connection::class.java) }
        assertThrows<SQLException> { unselected.result.value.autoCommit }
        assertThrows<SQLException> { unselected.result.value.close() }
        assertFalse(fixture.entry.retirementRequested.get())
        assertSame(selected.epoch, fixture.entry.jdbc.poolEpoch(fixture.binding.poolIdentity))
        assertSame(selected.result.value, selected.result.value.unwrap(Connection::class.java))
        assertEquals(0, raw.calls.get())
    }

    @Test
    fun `REAL_THREAD original request and F1 deliver the real lower facade then preserve the opaque route on the same worker`() =
        OwnedFactoryWorkerTestScope().use { scope ->
            scope.start()
            val first = scope.binding.requestPoolConnection(5_000) as PersistenceFactoryResult.Success<PhysicalJdbcFacade>
            assertSame(first.value, first.value.unwrap(Connection::class.java))
            val worker = scope.callbackThread.get()
            scope.awaitReady()
            val opaque = scope.binding.request(5_000) as PersistenceFactoryResult.Success<PersistenceJdbcCandidate>
            scope.awaitReady()
            assertSame(worker, scope.callbackThread.get())
            assertEquals(PersistenceFactoryProcessing.PROCESSING_ENDED, first.receipt.state())
            assertEquals(PersistenceFactoryProcessing.PROCESSING_ENDED, opaque.receipt.state())
            assertFalse(opaque.value.isRetirementRequested())
            assertEquals(2, scope.createCalls.get())
            assertEquals(0, scope.discardCalls.get())
            assertEquals(0, scope.failedCleanupCalls.get())
        }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["rawMissing", "openingActive", "scopePending", "retiring", "sealed", "unknown", "wrongEntry", "wrongOffer"])
    fun `typed final claim preserves original identity liveness and failure precedence`(mode: String) {
        val raw = PhysicalTestConnection()
        val fixture = PersistenceOwnershipTestFixture(raw = raw.raw)
        var prepared = PreparedPoolConnection.prepare(fixture.entry, fixture.binding)
        when (mode) {
            "rawMissing" -> fixture.entry.raw.set(null)

            "openingActive" -> fixture.entry.opening = PersistencePhysicalOpeningPhase.ACTIVE

            "scopePending" -> fixture.entry.scopeEnded = false

            "retiring" -> fixture.entry.retiring = true

            "sealed" -> fixture.binding.ledger.sealed = true

            "unknown" -> fixture.entry.unknown = true

            "wrongEntry" -> {
                val other = PersistenceOwnershipTestFixture()
                prepared = PreparedPoolConnection.prepare(other.entry, other.binding)
            }

            "wrongOffer" -> {
                val wrong = PersistenceFactoryAttempt<PersistencePhysicalRecord, PersistenceJdbcCandidate>(
                    fixture.entry.record,
                    fixture.control.budget,
                    fixture.control,
                )
                wrong.beginWork()
                wrong.retain(PersistenceJdbcCandidate(java.util.concurrent.atomic.AtomicBoolean()))
                wrong.offer()
                fixture.entry.attempt = wrong
                fixture.binding.rendezvous.current = wrong
            }
        }
        assertFalse(fixture.binding.takePoolConnection(fixture.entry, prepared))
        val expected = when (mode) {
            "rawMissing", "unknown" -> PersistenceFactoryFailure.CREATE_FAILED
            "openingActive", "scopePending" -> PersistenceFactoryFailure.NOT_READY
            "retiring", "sealed" -> PersistenceFactoryFailure.CLOSED
            else -> PersistenceFactoryFailure.COORDINATION_FAILED
        }
        assertEquals(expected, (fixture.control.failureResult() as PersistenceFactoryResult.Failed).reason)
        assertFalse(requireNotNull(fixture.entry.attempt).transferred)
        assertSame(fixture.entry, fixture.binding.ledger.entries.single())
        assertEquals(0, raw.calls.get())
    }

    @Test
    fun `typed packaging cannot restart the original request budget`() {
        val binding = modelReadyBinding()
        val control = PersistenceOwnedCallerControl.prepare(500)
        val entry = requireNotNull(binding.reserve(control))
        assertTrue(binding.admit(entry))
        val attempt = requireNotNull(entry.attempt)
        attempt.beginWork()
        entry.opening = PersistencePhysicalOpeningPhase.SETTLED
        entry.scopeEnded = true
        entry.raw.set(PhysicalTestConnection().raw)
        attempt.retain(entry.candidate)
        attempt.offer()
        val prepared = PreparedPoolConnection.prepare(entry, binding)
        awaitOwnedTestExpiry(control.budget)
        assertFalse(binding.takePoolConnection(entry, prepared))
        assertEquals(PersistenceOwnedCallerDisposition.ABANDONED_TIMEOUT, control.state())
        assertSame(control.budget, attempt.budget)
        assertFalse(attempt.transferred)
        assertNull(entry.jdbc.poolEpoch(binding.poolIdentity))
    }

    @Test
    fun `foreign caller cannot consume the prepared typed claim or original caller disposition`() = OwnedCallerTestScope().use { scope ->
        val fixture = PersistenceOwnershipTestFixture()
        val prepared = PreparedPoolConnection.prepare(fixture.entry, fixture.binding)
        assertFalse(scope.launch { fixture.binding.takePoolConnection(fixture.entry, prepared) }.value())
        assertEquals(PersistenceOwnedCallerDisposition.ATTACHED, fixture.control.state())
        assertFalse(fixture.attempt.transferred)
        assertTrue(fixture.binding.takePoolConnection(fixture.entry, prepared))
        assertSame(prepared.epoch, fixture.entry.jdbc.poolEpoch(fixture.binding.poolIdentity))
    }

    @Test
    fun `typed final claim rechecks the authentic caller interrupt flag without publishing pool ownership`() = OwnedCallerTestScope().use { scope ->
        assertTrue(
            scope.launch {
                val fixture = PersistenceOwnershipTestFixture()
                val prepared = PreparedPoolConnection.prepare(fixture.entry, fixture.binding)
                Thread.currentThread().interrupt()
                try {
                    assertFalse(fixture.binding.takePoolConnection(fixture.entry, prepared))
                    assertEquals(PersistenceOwnedCallerDisposition.ABANDONED_INTERRUPTED, fixture.control.state())
                    assertFalse(fixture.attempt.transferred)
                    assertNull(fixture.entry.jdbc.poolEpoch(fixture.binding.poolIdentity))
                    true
                } finally {
                    Thread.interrupted()
                }
            }.value(),
        )
    }
}
