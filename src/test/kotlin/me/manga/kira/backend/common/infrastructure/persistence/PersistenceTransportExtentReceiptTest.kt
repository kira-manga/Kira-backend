package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class PersistenceTransportExtentReceiptTest {
    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["RECORD", "EXTENT", "SOURCE", "RAW", "ROLE", "OWNER"])
    fun `MODEL auxiliary capture rejects every wrong binding identity`(wrong: String) = TransportTestScope("MODEL").use { scope ->
        val owner = PersistenceTransportOwner<TransportModelResource>()
        val source = PersistenceTransportExtentSource()
        val first = modelBoundTransport(scope, owner, source)
        val extent = requireNotNull(first.ticket.entry.extent)
        val otherOwner = PersistenceTransportOwner<TransportModelResource>()
        val other = modelBoundTransport(scope, otherOwner, source)
        val actualOwner = if (wrong == "OWNER") otherOwner else owner
        val actualRecord = if (wrong == "RECORD") PersistenceTransportRecord(PersistenceTransportRole.AUX_CANCEL) else first.record
        val actualExtent = when (wrong) {
            "EXTENT" -> PersistenceTransportExtent(source, PersistenceTransportRole.AUX_CANCEL)
            "SOURCE" -> PersistenceTransportExtent(PersistenceTransportExtentSource(), PersistenceTransportRole.AUX_CANCEL)
            "ROLE" -> PersistenceTransportExtent(source, PersistenceTransportRole.PRIMARY)
            else -> extent
        }
        assertNull(actualOwner.captureAuxiliaryClose(actualRecord, actualExtent, if (wrong == "RAW") other.resource else first.resource))
        assertFalse(first.ticket.entry.extentEnded)
        assertSame(first.ticket.entry.auxiliaryCloseReceipt, owner.captureAuxiliaryClose(first.record, extent, first.resource))
    }

    @Test
    fun `MODEL exact preowned receipt publishes once without clearing active call partitions or completing first close`() =
        TransportTestScope("MODEL").use { scope ->
            val owner = PersistenceTransportOwner<TransportModelResource>()
            val first = modelBoundTransport(scope, owner, PersistenceTransportExtentSource())
            val business = requireNotNull(owner.tryBeginCall(first.record, PersistenceTransportCallKind.BUSINESS))
            val observation = requireNotNull(owner.tryBeginCall(first.record, PersistenceTransportCallKind.OBSERVATION))
            val receipt = requireNotNull(owner.captureAuxiliaryClose(first.record, requireNotNull(first.ticket.entry.extent), first.resource))
            assertFalse(owner.completeAuxiliaryClose(PersistenceTransportAuxiliaryCloseReceipt(first.record, receipt.extent)))
            assertFalse(first.ticket.entry.extentEnded)
            assertTrue(owner.completeAuxiliaryClose(receipt))
            val revision = transportSnapshot(owner).revision
            assertFalse(owner.completeAuxiliaryClose(receipt))
            assertEquals(revision, transportSnapshot(owner).revision)
            val state = transportRecordSnapshot(owner, first.record)
            assertTrue(state.allCallsSealed && state.businessSealed)
            assertEquals(1, state.activeBusiness)
            assertEquals(1, state.activeObservations)
            assertEquals(PersistenceTransportClosePhase.NOT_STARTED, state.firstClose)
            assertNull(owner.tryBeginCall(first.record, PersistenceTransportCallKind.OBSERVATION))
            assertTrue(owner.completeCall(business))
            assertTrue(owner.completeCall(observation))
        }

    @Test
    fun `MODEL a stale receipt cannot end a successor extent and no history is retained`() = TransportTestScope("MODEL").use { scope ->
        val owner = PersistenceTransportOwner<TransportModelResource>()
        val source = PersistenceTransportExtentSource()
        val first = modelBoundTransport(scope, owner, source)
        first.resource.close()
        val receipt = first.completeBody()
        val next = modelBoundTransport(scope, owner, source)
        assertFalse(owner.completeAuxiliaryClose(receipt))
        assertNull(owner.captureAuxiliaryClose(first.record, receipt.extent, first.resource))
        assertFalse(next.ticket.entry.extentEnded)
        assertFalse(next.ticket.entry.allCallsSealed)
        assertEquals(PersistenceTransportClosePhase.NOT_STARTED, next.ticket.entry.firstClose)
        assertSame(next.record, transportSnapshot(owner).auxiliary?.record)
        assertSame(first.record, next.ticket.predecessor)
    }

    @Test
    fun `MODEL opening exit neither fences a primary nor ends auxiliary or grants a primary close-body receipt`() = TransportTestScope("MODEL").use { scope ->
        val owner = PersistenceTransportOwner<TransportModelResource>()
        val source = PersistenceTransportExtentSource()
        val primary = modelBoundTransport(scope, owner, source, PersistenceTransportRole.PRIMARY)
        val auxiliary = modelBoundTransport(scope, owner, source)
        source.primaryOpeningEnded.set(true)
        assertFalse(primary.ticket.entry.allCallsSealed || auxiliary.ticket.entry.allCallsSealed || auxiliary.ticket.entry.extentEnded)
        for (transport in listOf(primary, auxiliary)) {
            val call = requireNotNull(owner.tryBeginCall(transport.record, PersistenceTransportCallKind.BUSINESS))
            assertTrue(owner.completeCall(call))
        }
        assertNull(owner.captureAuxiliaryClose(primary.record, requireNotNull(primary.ticket.entry.extent), primary.resource))
        assertFalse(owner.completeAuxiliaryClose(PersistenceTransportAuxiliaryCloseReceipt(primary.record, requireNotNull(primary.ticket.entry.extent))))
        val secondAux = owner.prepareBoundConstruction(PersistenceTransportExtent(source, PersistenceTransportRole.AUX_CANCEL))
        assertEquals(PersistenceTransportRefusal.FULL, owner.prepareBoundReservation(secondAux))
        assertBoundConstructorRefused(owner, secondAux)
    }
}
