package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.concurrent.withLock

/** Actual transport reservation/return/rotation transitions with MODEL resources, not Socket or disposal acceptance. */
internal class PersistenceTransportPublicationTest {
    @ParameterizedTest
    @ValueSource(strings = ["LEGACY_CREATED", "BOUND_CREATED", "LEGACY_RETAINED", "BOUND_RETAINED"])
    fun `MODEL only settled raw return publishes for either installation path and normal result`(mode: String) = TransportTestScope("MODEL").use { scope ->
        val owner = PersistenceTransportOwner<TransportModelResource>()
        val ticket = if (mode.startsWith("BOUND")) {
            owner.prepareBoundConstruction(PersistenceTransportExtent(PersistenceTransportExtentSource(), PersistenceTransportRole.PRIMARY)).also {
                assertNull(owner.prepareBoundReservation(it))
                assertNull(owner.reserveBoundConstruction(it))
            }
        } else {
            owner.prepareConstruction(PersistenceTransportRole.PRIMARY).also { assertNull(owner.reserveConstruction(it)) }
        }
        scope.own(AutoCloseable { owner.requestClose(ticket.record) })
        assertNull(owner.currentReturnedPrimary())
        val beforeReturn = scope.gate()
        val caller = scope.launch {
            owner.constructReserved(ticket) { record ->
                assertFalse(owner.ownershipLockHeld())
                beforeReturn.hold()
                TransportModelResource(owner, record)
            }
        }
        beforeReturn.awaitEntered()
        assertNull(ticket.entry.raw.get())
        assertNull(owner.currentReturnedPrimary())
        val retained = mode.endsWith("RETAINED")
        val lock = transportTestLock(owner)
        lock.withLock {
            if (retained) assertTrue(owner.trySealForRetirement())
            beforeReturn.release()
            awaitTransportTestFact { lock.hasQueuedThread(caller.thread) }
            assertTrue(ticket.entry.raw.get() != null)
            assertSame(PersistenceTransportInvocation.RETURNED, ticket.entry.invocation.get())
            assertSame(PersistenceTransportConstruction.ACTIVE, ticket.entry.construction)
            // Read from another actor while T is actually held: a return invocation is not settled construction.
            assertTrue(scope.launch { owner.currentReturnedPrimary() == null }.join())
        }
        val result = caller.join()
        if (retained) {
            assertSame(ticket.entry.retained, result)
        } else {
            assertTrue(result is PersistenceTransportCreation.Created<*>)
            assertSame(ticket.entry.raw.get(), (result as PersistenceTransportCreation.Created<*>).resource)
        }
        assertSame(PersistenceTransportConstruction.RETURNED, ticket.entry.construction)
        assertSame(ticket.record, owner.currentReturnedPrimary())
        lock.withLock { assertTrue(scope.launch { owner.currentReturnedPrimary() === ticket.record }.join()) }
    }

    @Test
    fun `MODEL bound PRIMARY invalidates only at actual installation and AUX transitions leave it unchanged`() = TransportTestScope("MODEL").use { scope ->
        val owner = PersistenceTransportOwner<TransportModelResource>()
        val source = PersistenceTransportExtentSource()
        val first = modelBoundTransport(scope, owner, source, PersistenceTransportRole.PRIMARY)
        assertSame(first.record, owner.currentReturnedPrimary())
        val auxiliary = modelBoundTransport(scope, owner, source)
        assertSame(first.record, owner.currentReturnedPrimary())
        auxiliary.resource.close()
        auxiliary.completeBody()
        modelBoundTransport(scope, owner, source)
        assertSame(first.record, owner.currentReturnedPrimary())

        val next = owner.prepareBoundConstruction(PersistenceTransportExtent(source, PersistenceTransportRole.PRIMARY))
        scope.own(AutoCloseable { owner.requestClose(next.record) })
        assertNull(owner.prepareBoundReservation(next))
        assertSame(first.record, owner.currentReturnedPrimary())
        assertEquals(PersistenceTransportCloseRequest.REQUESTED, owner.requestClose(first.record))
        assertSame(first.record, owner.currentReturnedPrimary())
        assertNull(owner.reserveBoundConstruction(next))
        assertSame(next.record, transportSnapshot(owner).primary?.record)
        assertNull(owner.currentReturnedPrimary())
        assertTrue(owner.constructReserved(next) { TransportModelResource(owner, it) } is PersistenceTransportCreation.Created<*>)
        assertSame(next.record, owner.currentReturnedPrimary())
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `MODEL late predecessor settlement cannot republish across an unreturned or returned successor`(successorReturned: Boolean) =
        TransportTestScope("MODEL").use { scope ->
            val owner = PersistenceTransportOwner<TransportModelResource>()
            val source = PersistenceTransportExtentSource()
            val first = modelBoundTransport(scope, owner, source, PersistenceTransportRole.PRIMARY)
            val next = owner.prepareBoundConstruction(PersistenceTransportExtent(source, PersistenceTransportRole.PRIMARY))
            scope.own(AutoCloseable { owner.requestClose(next.record) })
            assertNull(owner.prepareBoundReservation(next))
            owner.requestClose(first.record)
            assertNull(owner.reserveBoundConstruction(next))
            if (successorReturned) owner.constructReserved(next) { TransportModelResource(owner, it) }
            val expected = if (successorReturned) next.record else null
            assertSame(expected, owner.currentReturnedPrimary())
            // Deliberately stale MODEL input to the real settlement body; never an invented positive publication.
            assertSame(first.ticket.entry.retained, settle(owner, first.ticket.entry, first.resource))
            assertSame(expected, owner.currentReturnedPrimary())
            assertSame(next.record, transportSnapshot(owner).primary?.record)
        }

    @ParameterizedTest
    @ValueSource(strings = ["PRIMARY", "AUX_CANCEL"])
    fun `MODEL constructor throws never publish and cannot erase another roles PRIMARY fact`(roleName: String) = TransportTestScope("MODEL").use { scope ->
        val owner = PersistenceTransportOwner<TransportModelResource>()
        val role = PersistenceTransportRole.valueOf(roleName)
        val primary = if (role === PersistenceTransportRole.AUX_CANCEL) {
            modelBoundTransport(scope, owner, PersistenceTransportExtentSource(), PersistenceTransportRole.PRIMARY).record
        } else {
            null
        }
        val failure = TransportTestFailure()
        assertSame(failure, runCatching { owner.tryCreate(role) { throw failure } }.exceptionOrNull())
        assertSame(primary, owner.currentReturnedPrimary())
        assertEquals(0, failure.renders.get())
    }

    @Test
    fun `MODEL a missing retained raw suppresses publication without changing settlement result or throwing`() = TransportTestScope("MODEL").use {
        val owner = PersistenceTransportOwner<TransportModelResource>()
        val ticket = owner.prepareConstruction(PersistenceTransportRole.PRIMARY)
        assertNull(owner.reserveConstruction(ticket))
        val raw = TransportModelResource(owner, ticket.record)
        // Contradictory MODEL input exercises the defensive guard; real constructReserved retains raw before this body.
        val result = settle(owner, ticket.entry, raw)
        assertTrue(result is PersistenceTransportCreation.Created<*>)
        assertSame(raw, (result as PersistenceTransportCreation.Created<*>).resource)
        assertSame(PersistenceTransportConstruction.RETURNED, ticket.entry.construction)
        assertNull(ticket.entry.raw.get())
        assertNull(owner.currentReturnedPrimary())
    }

    private fun settle(
        owner: PersistenceTransportOwner<TransportModelResource>,
        entry: PersistenceTransportEntry<TransportModelResource>,
        raw: TransportModelResource,
    ): Any? = PersistenceTransportOwner::class.java.getDeclaredMethod("settleConstruction", PersistenceTransportEntry::class.java, AutoCloseable::class.java)
        .apply { isAccessible = true }.invoke(owner, entry, raw)
}
