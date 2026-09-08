package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.SocketException
import kotlin.concurrent.withLock

/** Actual G→T fences and local Socket APIs; factory readiness/opening/terminal receipts remain explicitly MODEL. */
class PersistencePhysicalTransportRetirementTest {
    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["ORDINARY_WEAK", "ORDINARY_STRONG", "DELETION"])
    fun `REAL_SOCKET_API pre-fence constructor grant retains a late socket on the original physical entry`(name: String) =
        TransportTestScope("REAL_SOCKET_API").use { scope ->
            val fixture = PhysicalTransportTestFixture(physicalTransportTestPolicy(name))
            val construction = fixture.prepare(scope)
            assertNull(construction.reserve())
            fixture.retire()
            assertTrue(fixture.entry.retiring)
            assertTrue(transportSnapshot(fixture.owner).sealed)
            assertFalse(transportRecordSnapshot(fixture.owner, construction.record).rawReturned)
            assertSame(construction.record, (construction.construct() as PersistenceTransportCreation.Retained).record)
            val raw = scope.own(retainedTransportTestRaw(fixture.owner, construction.record) as TrackedPersistenceSocket)
            assertTransportBusinessRefused { raw.soTimeout = 100 }
            assertEquals(0, raw.port, "BUSINESS retirement does not silently change the OBSERVATION contract.")
            assertEquals(PersistenceTransportRefusal.SEALED, fixture.prepare(scope, PersistenceTransportRole.AUX_CANCEL).reserve())
            assertEquals(PersistenceTransportClosePhase.NOT_STARTED, transportRecordSnapshot(fixture.owner, construction.record).firstClose)
            raw.close()
            assertEquals(PersistenceTransportClosePhase.API_CLOSE_ACKNOWLEDGED, transportRecordSnapshot(fixture.owner, construction.record).firstClose)
            assertSame(fixture.entry, fixture.binding.ledger.entries.single())
            assertEquals(PersistenceFactoryProcessing.PENDING, fixture.control.receipt.state())
            assertSame(physicalTransportTestPolicy(name), fixture.entry.policy)
        }

    @Test
    fun `MODEL T contention delays only the physical fence and cannot lose projection after F clears current`() = TransportTestScope("MODEL").use { scope ->
        val fixture = PhysicalTransportTestFixture()
        val attempt = requireNotNull(fixture.entry.attempt)
        // Explicit MODEL ended-resource/worker-first cut. This is not an executed native terminal path.
        fixture.binding.ledger.lock.withLock {
            fixture.entry.opening = PersistencePhysicalOpeningPhase.SETTLED
            fixture.entry.scopeEnded = true
        }
        fixture.binding.rendezvous.lock.withLock {
            attempt.abandon(PersistenceFactoryFailure.CREATE_FAILED)
            attempt.settleWorker()
        }
        fixture.control.fail(PersistenceFactoryFailure.TIMEOUT)
        val lock = transportTestLock(fixture.owner)
        lock.withLock {
            val scan = scope.launch { fixture.binding.reconcileCallers() }
            assertTrue(scan.join())
            assertFalse(lock.hasQueuedThread(scan.thread))
            assertTrue(attempt.callerDetached)
            assertEquals(PersistenceFactoryFailure.CREATE_FAILED, attempt.failure, "An earlier independent failure is preserved.")
            assertNull(fixture.binding.rendezvous.current)
            assertEquals(PersistenceFactoryProcessing.PROCESSING_ENDED, fixture.control.receipt.state())
            assertTrue(fixture.entry.retirementRequested.get())
            assertFalse(fixture.entry.retiring)
            assertFalse(transportSnapshot(fixture.owner).sealed)
            assertSame(fixture.entry, fixture.binding.ledger.entries.single())
        }
        assertTrue(fixture.binding.reconcileCallers())
        assertTrue(fixture.entry.retiring)
        assertTrue(transportSnapshot(fixture.owner).sealed)
        assertSame(fixture.entry, fixture.binding.ledger.entries.single())
        assertEquals(PersistenceFactoryProcessing.PROCESSING_ENDED, fixture.control.receipt.state())
    }

    @Test
    fun `MODEL a TAKEN candidate remains retireable while a different factory attempt is current`() = TransportTestScope("MODEL").use { scope ->
        val fixture = PhysicalTransportTestFixture(binding = modelReadyBinding(2))
        val attempt = requireNotNull(fixture.entry.attempt)
        // MODEL opening result; physical Connection operations remain uninvoked by the opaque handle.
        val raw = PhysicalTestConnection()
        fixture.binding.ledger.lock.withLock {
            fixture.entry.opening = PersistencePhysicalOpeningPhase.SETTLED
            fixture.entry.scopeEnded = true
            fixture.entry.raw.set(raw.raw)
        }
        fixture.binding.rendezvous.lock.withLock {
            attempt.beginWork()
            attempt.retain(fixture.entry.candidate)
            attempt.offer()
        }
        assertTrue(fixture.binding.take(fixture.entry, PersistenceFactoryResult.Success(fixture.entry.candidate, fixture.control.receipt)))
        fixture.binding.rendezvous.lock.withLock {
            attempt.settleWorker()
            fixture.binding.rendezvous.finishIfBoth(attempt)
        }
        assertEquals(PersistenceOwnedCallerDisposition.TAKEN, fixture.control.state())
        assertNull(fixture.binding.rendezvous.current)
        // MODEL authentic next receiver entry; no real worker-start/Condition-readiness proof is asserted.
        fixture.binding.rendezvous.lock.withLock { fixture.binding.rendezvous.generation = PersistenceFactoryGeneration.WAITING }
        val next = PhysicalTransportTestFixture(binding = fixture.binding)
        assertSame(next.entry.attempt, fixture.binding.rendezvous.current)
        assertTrue(fixture.entry.candidate.requestRetirement())
        transportTestLock(fixture.owner).withLock {
            assertTrue(scope.launch { fixture.binding.reconcileCallers() }.join())
            assertFalse(fixture.entry.retiring)
        }
        assertTrue(fixture.binding.reconcileCallers())
        assertTrue(fixture.entry.retiring)
        assertTrue(transportSnapshot(fixture.owner).sealed)
        assertEquals(PersistenceOwnedCallerDisposition.TAKEN, fixture.control.state())
        assertTrue(attempt.transferred)
        assertEquals(PersistenceFactoryProcessing.PROCESSING_ENDED, fixture.control.receipt.state())
        assertEquals(0, raw.calls.get())
        assertSame(fixture.entry, fixture.binding.ledger.entries[fixture.entry.record.slotHint])
        assertSame(next.entry.attempt, fixture.binding.rendezvous.current)
        assertEquals(PersistenceOwnedCallerDisposition.ATTACHED, next.control.state())
        assertFalse(next.entry.retiring)
        assertFalse(transportSnapshot(next.owner).sealed)
    }

    @Test
    fun `MODEL shutdown keeps a contended physical fence pending without weakening the fixed policy`() = TransportTestScope("MODEL").use { scope ->
        val fixture = PhysicalTransportTestFixture(PersistenceDriverAttemptPolicy.TRACKED_ORDINARY_CONJUNCTION)
        assertTrue(fixture.binding.requestOwnedStop())
        transportTestLock(fixture.owner).withLock {
            assertTrue(scope.launch { fixture.binding.reconcileCallers() }.join())
            assertTrue(fixture.binding.ledger.sealed)
            assertTrue(fixture.entry.retirementRequested.get())
            assertFalse(fixture.entry.retiring)
            assertFalse(transportSnapshot(fixture.owner).sealed)
        }
        assertTrue(fixture.binding.reconcileCallers())
        assertTrue(fixture.entry.retiring)
        assertTrue(transportSnapshot(fixture.owner).sealed)
        val revision = transportSnapshot(fixture.owner).revision
        assertTrue(fixture.binding.reconcileCallers())
        assertEquals(revision, transportSnapshot(fixture.owner).revision)
        assertSame(PersistenceDriverAttemptPolicy.TRACKED_ORDINARY_CONJUNCTION, fixture.entry.policy)
        assertSame(fixture.entry, fixture.binding.ledger.entries.single())
    }

    @Test
    fun `REAL_SOCKET_API admitted read stays charged across physical retirement until its actual close induced exit`() =
        TransportTestScope("REAL_SOCKET_API").use { scope ->
            val fixture = PhysicalTransportTestFixture()
            val construction = fixture.prepare(scope)
            assertNull(construction.reserve())
            val socket = scope.own((construction.construct() as PersistenceTransportCreation.Created<TrackedPersistenceSocket>).resource)
            val listener = scope.listener()
            socket.connect(listener.localSocketAddress, 3_000)
            val peer = scope.own(listener.accept())
            peer.soTimeout = 3_000
            val input = socket.getInputStream()
            val read = scope.launch { runCatching { input.read() }.exceptionOrNull() is SocketException }
            awaitTransportSocketRead(read.thread)
            assertEquals(1, transportRecordSnapshot(fixture.owner, construction.record).activeBusiness)
            fixture.retire()
            assertTrue(fixture.entry.retiring)
            assertEquals(1, transportRecordSnapshot(fixture.owner, construction.record).activeBusiness)
            assertEquals(PersistenceTransportClosePhase.NOT_STARTED, transportRecordSnapshot(fixture.owner, construction.record).firstClose)
            assertTransportBusinessRefused { socket.getOutputStream() }
            socket.close()
            assertTrue(read.join())
            assertEquals(-1, peer.getInputStream().read())
            assertEquals(0, transportRecordSnapshot(fixture.owner, construction.record).activeBusiness)
            assertEquals(PersistenceTransportClosePhase.API_CLOSE_ACKNOWLEDGED, transportRecordSnapshot(fixture.owner, construction.record).firstClose)
            assertSame(fixture.entry, fixture.binding.ledger.entries.single(), "Call/close exit alone cannot reclaim physical membership.")
        }

    @Test
    fun `MODEL physical fence requires the actual logical request and does not mutate an unrelated active entry`() {
        val fixture = PhysicalTransportTestFixture()
        fixture.binding.ledger.lock.withLock { assertFalse(fixture.transports.fenceRetirementLocked()) }
        assertFalse(fixture.entry.retiring)
        assertFalse(transportSnapshot(fixture.owner).sealed)
    }
}
