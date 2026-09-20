package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.concurrent.withLock

/** REAL_SOCKET_API through the concrete G→T binding; physical opening/provenance remains MODEL. */
class PersistencePhysicalTransportBindingTest {
    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @CsvSource(
        "ORDINARY_WEAK,PRIMARY",
        "ORDINARY_WEAK,AUX_CANCEL",
        "ORDINARY_STRONG,PRIMARY",
        "ORDINARY_STRONG,AUX_CANCEL",
        "DELETION,PRIMARY",
        "DELETION,AUX_CANCEL",
    )
    fun `REAL_SOCKET_API exact entry reserves both roles and constructs once on its selected route`(policy: String, role: String) =
        TransportTestScope("REAL_SOCKET_API").use { scope ->
            val fixture = PhysicalTransportTestFixture(physicalTransportTestPolicy(policy))
            val construction = fixture.prepare(scope, PersistenceTransportRole.valueOf(role))
            assertPhysicalConstructionRefused(construction)
            assertNull(transportSnapshot(fixture.owner).primary)
            assertNull(transportSnapshot(fixture.owner).auxiliary)
            assertNull(construction.reserve())
            assertFalse(transportRecordSnapshot(fixture.owner, construction.record).rawReturned)
            val created = construction.construct() as PersistenceTransportCreation.Created<TrackedPersistenceSocket>
            scope.own(created.resource)
            assertSame(construction.record, created.record)
            assertSame(created.resource, retainedTransportTestRaw(fixture.owner, created.record))
            assertFalse(created.resource.isClosed)
            assertEquals(PersistenceTransportRefusal.INVALID_CONSTRUCTION, construction.reserve())
            assertPhysicalConstructionRefused(construction)
            created.resource.close()
            assertEquals(PersistenceTransportClosePhase.API_CLOSE_ACKNOWLEDGED, transportRecordSnapshot(fixture.owner, created.record).firstClose)
            assertSame(fixture.entry, fixture.binding.ledger.entries.single())
        }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["G", "T"])
    fun `MODEL contended G or T cannot queue or revive the one refused construction`(held: String) = TransportTestScope("MODEL").use { scope ->
        val fixture = PhysicalTransportTestFixture()
        val construction = fixture.prepare(scope)
        val lock = if (held == "G") fixture.binding.ledger.lock else transportTestLock(fixture.owner)
        lock.withLock {
            val caller = scope.launch { requireNotNull(construction.reserve()) }
            assertEquals(PersistenceTransportRefusal.CONTENDED, caller.join())
            assertFalse(lock.hasQueuedThread(caller.thread))
            assertNull(transportSnapshot(fixture.owner).primary)
        }
        assertEquals(PersistenceTransportRefusal.INVALID_CONSTRUCTION, construction.reserve())
        assertPhysicalConstructionRefused(construction)
        assertNull(transportSnapshot(fixture.owner).primary)
        assertSame(fixture.entry, fixture.binding.ledger.entries.single())
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["F", "G", "T"])
    fun `REAL_SOCKET_API constructor refuses an enclosing ownership lock without consuming its grant`(held: String) =
        TransportTestScope("REAL_SOCKET_API").use { scope ->
            val fixture = PhysicalTransportTestFixture()
            val construction = fixture.prepare(scope)
            assertNull(construction.reserve())
            val lock = when (held) {
                "F" -> fixture.binding.rendezvous.lock
                "G" -> fixture.binding.ledger.lock
                else -> transportTestLock(fixture.owner)
            }
            lock.withLock {
                assertPhysicalConstructionRefused(construction)
                assertFalse(transportRecordSnapshot(fixture.owner, construction.record).rawReturned)
            }
            val created = construction.construct() as PersistenceTransportCreation.Created<TrackedPersistenceSocket>
            scope.own(created.resource)
            assertFalse(created.resource.isClosed)
            assertSame(created.resource, retainedTransportTestRaw(fixture.owner, construction.record))
        }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @CsvSource(
        "UNCLAIMED,INVALID_CONSTRUCTION",
        "ABANDONED,SEALED",
        "LOGICAL_RETIREMENT,SEALED",
        "SHUTDOWN,SEALED",
        "WORKER_CANCELLED,SEALED",
        "UNKNOWN,SEALED",
    )
    fun `MODEL invalid physical admission creates no transport`(state: String, expected: String) = TransportTestScope("MODEL").use { scope ->
        val fixture = PhysicalTransportTestFixture()
        val construction = fixture.prepare(scope)
        when (state) {
            "UNCLAIMED" -> fixture.binding.ledger.lock.withLock { fixture.entry.opening = PersistencePhysicalOpeningPhase.UNCLAIMED }
            "ABANDONED" -> fixture.control.fail(PersistenceFactoryFailure.TIMEOUT)
            "LOGICAL_RETIREMENT" -> fixture.entry.candidate.requestRetirement()
            "WORKER_CANCELLED" -> fixture.binding.rendezvous.lock.withLock { requireNotNull(fixture.entry.attempt).abandon(PersistenceFactoryFailure.TIMEOUT) }
            "UNKNOWN" -> fixture.binding.ledger.lock.withLock { fixture.entry.unknown = true }
            else -> fixture.binding.requestOwnedStop()
        }
        assertEquals(PersistenceTransportRefusal.valueOf(expected), construction.reserve())
        assertPhysicalConstructionRefused(construction)
        assertNull(transportSnapshot(fixture.owner).primary)
        assertNull(transportSnapshot(fixture.owner).auxiliary)
        assertSame(fixture.entry, fixture.binding.ledger.entries.single())
    }

    @Test
    fun `MODEL never dispatched reservation cannot construct and remains genuinely releasable`() = TransportTestScope("MODEL").use { scope ->
        val fixture = PhysicalTransportTestFixture(admit = false)
        val construction = fixture.prepare(scope)
        assertEquals(PersistenceTransportRefusal.SEALED, construction.reserve())
        assertPhysicalConstructionRefused(construction)
        assertNull(transportSnapshot(fixture.owner).primary)
        fixture.control.fail(PersistenceFactoryFailure.BUSY)
        assertTrue(fixture.binding.releaseRefused(fixture.entry))
        assertNull(fixture.binding.ledger.entries.single())
    }

    @Test
    fun `MODEL stale binding cannot allocate or fence a different entry that reuses its slot`() = TransportTestScope("MODEL").use { scope ->
        val stale = PhysicalTransportTestFixture(admit = false)
        val construction = stale.prepare(scope)
        stale.control.fail(PersistenceFactoryFailure.BUSY)
        assertTrue(stale.binding.releaseRefused(stale.entry))
        val next = PhysicalTransportTestFixture(binding = stale.binding)
        assertNotSame(stale.entry.record, next.entry.record)
        assertEquals(stale.entry.record.slotHint, next.entry.record.slotHint)
        assertEquals(PersistenceTransportRefusal.INVALID_CONSTRUCTION, construction.reserve())
        assertPhysicalConstructionRefused(construction)
        stale.entry.candidate.requestRetirement()
        stale.binding.ledger.lock.withLock { assertFalse(stale.transports.fenceRetirementLocked()) }
        assertFalse(next.entry.retiring)
        assertFalse(transportSnapshot(next.owner).sealed)
        assertNull(transportSnapshot(stale.owner).primary)
        assertNull(transportSnapshot(next.owner).primary)
    }
}
