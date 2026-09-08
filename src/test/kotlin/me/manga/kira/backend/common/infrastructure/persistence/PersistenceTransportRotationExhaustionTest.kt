package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class PersistenceTransportRotationExhaustionTest {
    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["EMPTY_FIRST", "EMPTY_FINAL", "OCCUPIED_FIRST", "OCCUPIED_FINAL"])
    fun `MODEL MAX minus one refuses before installation and never drops an occupied predecessor`(mode: String) = TransportTestScope("MODEL").use { scope ->
        val owner = PersistenceTransportOwner<TransportModelResource>()
        val source = PersistenceTransportExtentSource()
        val previous = if (mode.startsWith("OCCUPIED")) {
            modelBoundTransport(scope, owner, source).also {
                it.resource.close()
                it.completeBody()
            }
        } else {
            null
        }
        val next = owner.prepareBoundConstruction(PersistenceTransportExtent(source, PersistenceTransportRole.AUX_CANCEL))
        if (mode.endsWith("FINAL")) assertNull(owner.prepareBoundReservation(next))
        setTransportTestRevision(owner, Long.MAX_VALUE - 1)
        val refusal = if (mode.endsWith("FINAL")) owner.reserveBoundConstruction(next) else owner.prepareBoundReservation(next)
        assertEquals(PersistenceTransportRefusal.EXHAUSTED, refusal)
        assertSame(previous?.record, transportSnapshot(owner).auxiliary?.record)
        assertEquals(Long.MAX_VALUE, transportSnapshot(owner).revision)
        assertTrue(transportSnapshot(owner).revisionExhausted)
        assertBoundConstructorRefused(owner, next)
    }

    @Test
    fun `MODEL exhaustion after primary predecessor fence still preserves exact old membership`() = TransportTestScope("MODEL").use { scope ->
        val owner = PersistenceTransportOwner<TransportModelResource>()
        val source = PersistenceTransportExtentSource()
        val first = modelBoundTransport(scope, owner, source, PersistenceTransportRole.PRIMARY)
        first.resource.close()
        setTransportTestRevision(owner, Long.MAX_VALUE - 2)
        val next = owner.prepareBoundConstruction(PersistenceTransportExtent(source, PersistenceTransportRole.PRIMARY))
        assertNull(owner.prepareBoundReservation(next))
        assertTrue(first.ticket.entry.extentEnded && first.ticket.entry.allCallsSealed)
        assertEquals(Long.MAX_VALUE - 1, transportSnapshot(owner).revision)
        assertEquals(PersistenceTransportRefusal.EXHAUSTED, owner.reserveBoundConstruction(next))
        assertSame(first.record, transportSnapshot(owner).primary?.record)
        assertSame(first.resource, retainedTransportTestRaw(owner, first.record))
        assertBoundConstructorRefused(owner, next)
    }

    @Test
    fun `MODEL close accounting reaching exhaustion acknowledges only the original producer and denies successor`() = TransportTestScope("MODEL").use { scope ->
        val owner = PersistenceTransportOwner<TransportModelResource>()
        val source = PersistenceTransportExtentSource()
        val first = modelBoundTransport(scope, owner, source, PersistenceTransportRole.PRIMARY)
        val next = owner.prepareBoundConstruction(PersistenceTransportExtent(source, PersistenceTransportRole.PRIMARY))
        assertNull(owner.prepareBoundReservation(next))
        setTransportTestRevision(owner, Long.MAX_VALUE - 2)
        first.resource.close()
        assertEquals(PersistenceTransportClosePhase.API_CLOSE_ACKNOWLEDGED, first.ticket.entry.firstClose)
        assertEquals(PersistenceTransportRefusal.EXHAUSTED, owner.reserveBoundConstruction(next))
        assertSame(first.record, transportSnapshot(owner).primary?.record)
        assertEquals(1, first.resource.closes.get())
        assertBoundConstructorRefused(owner, next)
    }

    @Test
    fun `MODEL real call and close completions remain reliable after exhaustion without granting new allocations`() = TransportTestScope("MODEL").use { scope ->
        val owner = PersistenceTransportOwner<TransportModelResource>()
        val source = PersistenceTransportExtentSource()
        val first = modelBoundTransport(scope, owner, source)
        val call = requireNotNull(owner.tryBeginCall(first.record, PersistenceTransportCallKind.OBSERVATION))
        val claim = requireNotNull(owner.claimClose(first.record, first.resource))
        val receipt = requireNotNull(owner.captureAuxiliaryClose(first.record, requireNotNull(first.ticket.entry.extent), first.resource))
        setTransportTestRevision(owner, Long.MAX_VALUE - 1)
        assertTrue(owner.completeAuxiliaryClose(receipt))
        assertTrue(transportSnapshot(owner).revisionExhausted)
        assertTrue(owner.completeCall(call))
        assertTrue(owner.completeClose(claim, acknowledged = true))
        assertFalse(owner.completeCall(call))
        assertFalse(owner.completeClose(claim, acknowledged = true))
        val next = owner.prepareBoundConstruction(PersistenceTransportExtent(source, PersistenceTransportRole.AUX_CANCEL))
        assertEquals(PersistenceTransportRefusal.EXHAUSTED, owner.prepareBoundReservation(next))
        assertBoundConstructorRefused(owner, next)
        assertEquals(Long.MAX_VALUE, transportSnapshot(owner).revision)
        assertSame(first.record, transportSnapshot(owner).auxiliary?.record)
    }
}
