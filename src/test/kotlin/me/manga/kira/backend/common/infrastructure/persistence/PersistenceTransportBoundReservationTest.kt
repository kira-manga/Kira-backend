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
import kotlin.concurrent.withLock

internal class PersistenceTransportBoundReservationTest {
    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @EnumSource(PersistenceTransportRole::class)
    fun `MODEL bound preparation and first cut are inert until exact final reservation`(role: PersistenceTransportRole) = TransportTestScope("MODEL").use {
        val owner = PersistenceTransportOwner<TransportModelResource>()
        val ticket = owner.prepareBoundConstruction(PersistenceTransportExtent(PersistenceTransportExtentSource(), role))
        assertBoundConstructorRefused(owner, ticket)
        assertNull(owner.prepareBoundReservation(ticket))
        assertEquals(PersistenceTransportInvocation.ROTATION_READY, ticket.entry.invocation.get())
        assertNull(ticket.predecessor)
        assertNull(transportSnapshot(owner).primary)
        assertNull(transportSnapshot(owner).auxiliary)
        assertBoundConstructorRefused(owner, ticket)
        assertEquals(PersistenceTransportRefusal.INVALID_CONSTRUCTION, owner.prepareBoundReservation(ticket))
        assertNull(owner.reserveBoundConstruction(ticket))
        assertTrue(owner.constructReserved(ticket) { TransportModelResource(owner, it) } is PersistenceTransportCreation.Created<*>)
        assertBoundConstructorRefused(owner, ticket)
        assertEquals(PersistenceTransportRefusal.INVALID_CONSTRUCTION, owner.reserveBoundConstruction(ticket))
    }

    @Test
    fun `MODEL legacy and bound APIs reject each others tickets and foreign forgeries do not consume originals`() = TransportTestScope("MODEL").use {
        val owner = PersistenceTransportOwner<TransportModelResource>()
        val other = PersistenceTransportOwner<TransportModelResource>()
        val legacy = owner.prepareConstruction(PersistenceTransportRole.PRIMARY)
        val bound = owner.prepareBoundConstruction(PersistenceTransportExtent(PersistenceTransportExtentSource(), PersistenceTransportRole.PRIMARY))
        val forged = PersistenceTransportConstructionTicket(owner, bound.entry)
        assertEquals(PersistenceTransportRefusal.INVALID_CONSTRUCTION, owner.prepareBoundReservation(legacy))
        assertEquals(PersistenceTransportRefusal.INVALID_CONSTRUCTION, owner.reserveConstruction(bound))
        assertEquals(PersistenceTransportRefusal.INVALID_CONSTRUCTION, other.prepareBoundReservation(bound))
        assertEquals(PersistenceTransportRefusal.INVALID_CONSTRUCTION, owner.prepareBoundReservation(forged))
        assertNull(owner.prepareBoundReservation(bound))
        assertEquals(PersistenceTransportRefusal.INVALID_CONSTRUCTION, other.reserveBoundConstruction(bound))
        assertEquals(PersistenceTransportRefusal.INVALID_CONSTRUCTION, owner.reserveBoundConstruction(forged))
        assertNull(owner.reserveBoundConstruction(bound))
        assertEquals(PersistenceTransportInvocation.PREPARED, legacy.entry.invocation.get())
        assertNull(transportSnapshot(other).primary)
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["LEGACY", "FOREIGN_SOURCE", "ACTIVE_AUX"])
    fun `MODEL bound replacement refuses nonmatching or active predecessors without altering them`(mode: String) = TransportTestScope("MODEL").use { scope ->
        val owner = PersistenceTransportOwner<TransportModelResource>()
        val source = PersistenceTransportExtentSource()
        val previous = if (mode == "LEGACY") {
            modelTransport(owner, PersistenceTransportRole.AUX_CANCEL).also { scope.own(it.resource) }.record
        } else {
            modelBoundTransport(scope, owner, if (mode == "FOREIGN_SOURCE") PersistenceTransportExtentSource() else source).record
        }
        val ticket = owner.prepareBoundConstruction(PersistenceTransportExtent(source, PersistenceTransportRole.AUX_CANCEL))
        val revision = transportSnapshot(owner).revision
        assertEquals(PersistenceTransportRefusal.FULL, owner.prepareBoundReservation(ticket))
        assertSame(previous, transportSnapshot(owner).auxiliary?.record)
        assertEquals(revision, transportSnapshot(owner).revision)
        assertFalse(requireNotNull(transportSnapshot(owner).auxiliary).allCallsSealed)
        assertBoundConstructorRefused(owner, ticket)
        assertEquals(PersistenceTransportRefusal.INVALID_CONSTRUCTION, owner.prepareBoundReservation(ticket))
    }

    @Test
    fun `MODEL primary successor fences first then needs original close and drain before replacement`() = TransportTestScope("MODEL").use { scope ->
        val owner = PersistenceTransportOwner<TransportModelResource>()
        val source = PersistenceTransportExtentSource()
        val first = modelBoundTransport(scope, owner, source, PersistenceTransportRole.PRIMARY) {
            assertFalse(owner.ownershipLockHeld())
        }
        val next = owner.prepareBoundConstruction(PersistenceTransportExtent(source, PersistenceTransportRole.PRIMARY))
        assertNull(owner.prepareBoundReservation(next))
        assertSame(first.record, next.predecessor)
        assertTrue(first.ticket.entry.extentEnded && first.ticket.entry.allCallsSealed)
        assertEquals(0, first.resource.closes.get())
        assertEquals(PersistenceTransportRefusal.FULL, owner.reserveBoundConstruction(next))
        assertBoundConstructorRefused(owner, next)
        val replaced = modelBoundTransport(scope, owner, source, PersistenceTransportRole.PRIMARY)
        assertEquals(1, first.resource.closes.get())
        assertSame(replaced.record, transportSnapshot(owner).primary?.record)
        assertNull(owner.tryBeginCall(first.record, PersistenceTransportCallKind.OBSERVATION))
        assertEquals(PersistenceTransportCloseRequest.REFUSED, owner.requestClose(first.record))
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(booleans = [false, true])
    fun `MODEL two selected contenders cannot both replace an empty or disposed auxiliary role`(occupied: Boolean) = TransportTestScope("MODEL").use { scope ->
        val owner = PersistenceTransportOwner<TransportModelResource>()
        val source = PersistenceTransportExtentSource()
        if (occupied) {
            modelBoundTransport(scope, owner, source).also {
                it.resource.close()
                it.completeBody()
            }
        }
        val extent = { PersistenceTransportExtent(source, PersistenceTransportRole.AUX_CANCEL) }
        val first = owner.prepareBoundConstruction(extent())
        val second = owner.prepareBoundConstruction(extent())
        assertNull(owner.prepareBoundReservation(first))
        assertNull(owner.prepareBoundReservation(second))
        assertSame(first.predecessor, second.predecessor)
        assertNull(owner.reserveBoundConstruction(first))
        assertEquals(PersistenceTransportRefusal.INVALID_CONSTRUCTION, owner.reserveBoundConstruction(second))
        assertSame(first.record, transportSnapshot(owner).auxiliary?.record)
        assertBoundConstructorRefused(owner, second)
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["PREPARED", "ROTATION_READY"])
    fun `MODEL abandoning either prepared phase is permanent and cannot allocate`(phase: String) = TransportTestScope("MODEL").use {
        val owner = PersistenceTransportOwner<TransportModelResource>()
        val ticket = owner.prepareBoundConstruction(PersistenceTransportExtent(PersistenceTransportExtentSource(), PersistenceTransportRole.AUX_CANCEL))
        if (phase == "ROTATION_READY") assertNull(owner.prepareBoundReservation(ticket))
        owner.abandonBoundConstruction(ticket)
        owner.abandonBoundConstruction(ticket)
        assertEquals(PersistenceTransportInvocation.REFUSED, ticket.entry.invocation.get())
        assertEquals(PersistenceTransportRefusal.INVALID_CONSTRUCTION, owner.prepareBoundReservation(ticket))
        assertEquals(PersistenceTransportRefusal.INVALID_CONSTRUCTION, owner.reserveBoundConstruction(ticket))
        assertBoundConstructorRefused(owner, ticket)
        assertNull(transportSnapshot(owner).auxiliary)
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["FIRST_REENTRANT", "FINAL_REENTRANT", "FIRST_CONTENDED", "FINAL_CONTENDED"])
    fun `MODEL both bound cuts refuse preheld or contended T and consume only that attempt`(mode: String) = TransportTestScope("MODEL").use { scope ->
        val owner = PersistenceTransportOwner<TransportModelResource>()
        val ticket = owner.prepareBoundConstruction(PersistenceTransportExtent(PersistenceTransportExtentSource(), PersistenceTransportRole.AUX_CANCEL))
        val final = mode.startsWith("FINAL")
        if (final) assertNull(owner.prepareBoundReservation(ticket))
        val operation = { requireNotNull(if (final) owner.reserveBoundConstruction(ticket) else owner.prepareBoundReservation(ticket)) }
        val lock = transportTestLock(owner)
        lock.withLock {
            if (mode.endsWith("CONTENDED")) {
                val call = scope.launch(action = operation)
                assertEquals(PersistenceTransportRefusal.CONTENDED, call.join())
                assertFalse(lock.hasQueuedThread(call.thread))
            } else {
                assertEquals(PersistenceTransportRefusal.INVALID_CONSTRUCTION, operation())
            }
        }
        assertBoundConstructorRefused(owner, ticket)
        assertEquals(PersistenceTransportInvocation.REFUSED, ticket.entry.invocation.get())
    }
}
