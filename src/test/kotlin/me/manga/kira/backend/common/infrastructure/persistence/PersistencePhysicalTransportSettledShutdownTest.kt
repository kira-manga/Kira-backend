package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.withLock

/** Real Socket/lock operations; receiver readiness, Driver opening and worker resource settlement are MODEL only. */
class PersistencePhysicalTransportSettledShutdownTest {
    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @CsvSource(
        "ORDINARY_WEAK, false",
        "ORDINARY_WEAK, true",
        "ORDINARY_STRONG, false",
        "ORDINARY_STRONG, true",
        "DELETION, false",
        "DELETION, true",
    )
    fun `REAL_SOCKET_API shutdown fences the settled TAKEN entry after T contention ends`(policyName: String, rootShutdown: Boolean) =
        TransportTestScope("REAL_SOCKET_API").use { scope ->
            val shutdown = AtomicBoolean()
            val binding = PersistencePhysicalFactoryBinding(1, shutdown).also {
                // MODEL readiness, not a running controller or factory worker.
                it.rendezvous.generation = PersistenceFactoryGeneration.WAITING
                it.admissionOpen.set(true)
            }
            val policy = physicalTransportTestPolicy(policyName)
            val fixture = PhysicalTransportTestFixture(policy, binding)
            val construction = fixture.prepare(scope)
            assertNull(construction.reserve())
            val socket = scope.own((construction.construct() as PersistenceTransportCreation.Created<TrackedPersistenceSocket>).resource)
            val raw = PhysicalTestConnection()
            val success = modelTakeAndSettle(fixture, raw)
            assertSuccessfulOwnership(fixture, success, raw)
            assertFalse(binding.isClosed())
            assertFalse(binding.ledger.sealed)
            assertFalse(fixture.entry.retirementRequested.get())
            assertFalse(fixture.entry.retiring)
            assertFalse(transportSnapshot(fixture.owner).sealed)

            val lock = transportTestLock(fixture.owner)
            lock.withLock {
                if (rootShutdown) assertTrue(shutdown.compareAndSet(false, true)) else assertTrue(binding.requestOwnedStop())
                assertTrue(binding.isClosed())
                val scan = scope.launch { binding.reconcileCallers() }
                // Joining before releasing T proves this scan cannot depend on obtaining T.
                assertTrue(scan.join())
                assertFalse(lock.hasQueuedThread(scan.thread))
                assertTrue(binding.ledger.sealed)
                assertTrue(fixture.entry.retirementRequested.get())
                assertFalse(fixture.entry.retiring)
                assertFalse(transportSnapshot(fixture.owner).sealed)
                assertSuccessfulOwnership(fixture, success, raw)
                assertSame(socket, retainedTransportTestRaw(fixture.owner, construction.record))
                assertEquals(PersistenceTransportClosePhase.NOT_STARTED, transportRecordSnapshot(fixture.owner, construction.record).firstClose)
                assertFalse(socket.isClosed)
            }

            assertTrue(binding.reconcileCallers())
            assertTrue(fixture.entry.retiring)
            assertTrue(transportSnapshot(fixture.owner).sealed)
            assertEquals(PersistenceTransportRefusal.SEALED, fixture.prepare(scope, PersistenceTransportRole.AUX_CANCEL).reserve())
            assertSuccessfulOwnership(fixture, success, raw)
            assertSame(policy, fixture.entry.policy)
            assertSame(socket, retainedTransportTestRaw(fixture.owner, construction.record))
            val transport = transportRecordSnapshot(fixture.owner, construction.record)
            assertTrue(transport.businessSealed)
            assertFalse(transport.allCallsSealed, "Shutdown's BUSINESS fence is not a permanent all-call or disposal receipt.")
            assertEquals(PersistenceTransportClosePhase.NOT_STARTED, transport.firstClose)
            assertFalse(socket.isClosed)
            val revision = transportSnapshot(fixture.owner).revision
            assertTrue(binding.reconcileCallers())
            assertEquals(revision, transportSnapshot(fixture.owner).revision)
            assertSuccessfulOwnership(fixture, success, raw)
        }

    private fun modelTakeAndSettle(
        fixture: PhysicalTransportTestFixture,
        raw: PhysicalTestConnection,
    ): PersistenceFactoryResult.Success<PersistenceJdbcCandidate> {
        val binding = fixture.binding
        val attempt = requireNotNull(fixture.entry.attempt)
        // No real Driver/terminal operations: only the actual claim/finish transitions are exercised here.
        binding.ledger.lock.withLock {
            fixture.entry.opening = PersistencePhysicalOpeningPhase.SETTLED
            fixture.entry.scopeEnded = true
            fixture.entry.raw.set(raw.raw)
        }
        binding.rendezvous.lock.withLock {
            attempt.beginWork()
            attempt.retain(fixture.entry.candidate)
            attempt.offer()
        }
        val success = PersistenceFactoryResult.Success(fixture.entry.candidate, fixture.control.receipt)
        assertTrue(binding.take(fixture.entry, success))
        binding.rendezvous.lock.withLock {
            attempt.settleWorker()
            binding.rendezvous.finishIfBoth(attempt)
        }
        return success
    }

    private fun assertSuccessfulOwnership(
        fixture: PhysicalTransportTestFixture,
        success: PersistenceFactoryResult.Success<PersistenceJdbcCandidate>,
        raw: PhysicalTestConnection,
    ) {
        val attempt = requireNotNull(fixture.entry.attempt)
        assertSame(fixture.entry, fixture.binding.ledger.entries.single())
        assertSame(fixture.entry.record, attempt.input)
        assertSame(fixture.control, fixture.entry.control)
        assertSame(fixture.control, attempt.ownedControl)
        assertSame(fixture.entry.candidate, success.value)
        assertSame(fixture.control.receipt, success.receipt)
        assertSame(success.receipt, attempt.receipt)
        assertSame(raw.raw, fixture.entry.raw.get())
        assertEquals(PersistenceOwnedCallerDisposition.TAKEN, fixture.control.state())
        assertEquals(PersistenceFactoryAttemptPhase.FINISHED, attempt.phase)
        assertTrue(attempt.transferred && attempt.callerDetached && attempt.workerSettled)
        assertFalse(attempt.unresolved)
        assertFalse(attempt.cancellation.isRequested())
        assertNull(attempt.failure)
        assertNull(fixture.control.state().reason)
        assertNull(fixture.binding.rendezvous.current)
        assertEquals(PersistenceFactoryProcessing.PROCESSING_ENDED, success.receipt.state())
        assertEquals(0, raw.calls.get())
    }
}
