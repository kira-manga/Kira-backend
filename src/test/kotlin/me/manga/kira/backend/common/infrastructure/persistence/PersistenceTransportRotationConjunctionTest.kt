package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class PersistenceTransportRotationConjunctionTest {
    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(
        strings = [
            "INVOCATION", "CONSTRUCTION", "RAW", "CLOSE_NOT_STARTED", "CLOSE_RUNNING", "CLOSE_FAILED",
            "BUSINESS", "OBSERVATION", "ALL_CALL_FENCE", "BUSINESS_FENCE", "EXTENT",
        ],
    )
    fun `MODEL independently missing disposal facts refuse allocation even when every other cell is satisfied`(missing: String) =
        TransportTestScope("MODEL").use { scope ->
            val owner = PersistenceTransportOwner<TransportModelResource>()
            val source = PersistenceTransportExtentSource()
            val first = modelBoundTransport(scope, owner, source)
            first.resource.close()
            first.completeBody()
            // Explicit MODEL cell cut of this project's entry. This is not a native-failure reproduction.
            val entry = first.ticket.entry
            removeFact(entry, missing)
            val next = owner.prepareBoundConstruction(PersistenceTransportExtent(source, PersistenceTransportRole.AUX_CANCEL))
            val prepared = owner.prepareBoundReservation(next)
            if (missing == "EXTENT") {
                assertEquals(PersistenceTransportRefusal.FULL, prepared)
            } else {
                assertNull(prepared)
                assertEquals(PersistenceTransportRefusal.FULL, owner.reserveBoundConstruction(next))
            }
            assertBoundConstructorRefused(owner, next)
            assertSame(first.record, transportSnapshot(owner).auxiliary?.record)
            assertEquals(1, first.resource.closes.get())
        }

    @Test
    fun `MODEL coalesced body while first close is running fences calls but cannot promote the producer`() = TransportTestScope("MODEL").use { scope ->
        val owner = PersistenceTransportOwner<TransportModelResource>()
        val source = PersistenceTransportExtentSource()
        val held = scope.gate()
        val first = modelBoundTransport(scope, owner, source, closeAction = held::hold)
        val closing = scope.launch {
            first.resource.close()
            true
        }
        held.awaitEntered()
        assertEquals(PersistenceTransportClosePhase.RUNNING, first.ticket.entry.firstClose)
        first.resource.close() // Real coalesced MODEL-resource close body; its normal return does not acknowledge the producer.
        first.completeBody() // MODEL of the separately authenticated final Socket contact.
        val next = owner.prepareBoundConstruction(PersistenceTransportExtent(source, PersistenceTransportRole.AUX_CANCEL))
        assertNull(owner.prepareBoundReservation(next))
        assertEquals(PersistenceTransportRefusal.FULL, owner.reserveBoundConstruction(next))
        assertEquals(PersistenceTransportClosePhase.RUNNING, first.ticket.entry.firstClose)
        assertEquals(1, first.resource.closes.get())
        held.release()
        assertTrue(closing.join())
        assertBoundConstructorRefused(owner, next)
        val later = modelBoundTransport(scope, owner, source)
        assertSame(later.record, transportSnapshot(owner).auxiliary?.record)
        assertEquals(PersistenceTransportClosePhase.API_CLOSE_ACKNOWLEDGED, first.ticket.entry.firstClose)
    }

    @Test
    fun `MODEL failed first close remains failed after a normal coalesced body and exact receipt`() = TransportTestScope("MODEL").use { scope ->
        val owner = PersistenceTransportOwner<TransportModelResource>()
        val source = PersistenceTransportExtentSource()
        val failure = TransportTestFailure()
        val first = modelBoundTransport(scope, owner, source, closeAction = { throw failure })
        assertSame(failure, runCatching { first.resource.close() }.exceptionOrNull())
        first.resource.close()
        first.completeBody()
        val next = owner.prepareBoundConstruction(PersistenceTransportExtent(source, PersistenceTransportRole.AUX_CANCEL))
        assertNull(owner.prepareBoundReservation(next))
        assertEquals(PersistenceTransportRefusal.FULL, owner.reserveBoundConstruction(next))
        assertEquals(PersistenceTransportClosePhase.FAILED, first.ticket.entry.firstClose)
        assertEquals(1, first.resource.closes.get())
        assertEquals(0, failure.renders.get())
        assertBoundConstructorRefused(owner, next)
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["BUSINESS", "OBSERVATION"])
    fun `MODEL genuine admitted tokens survive extent completion and permit replacement only after their exit`(kind: String) =
        TransportTestScope("MODEL").use { scope ->
            val owner = PersistenceTransportOwner<TransportModelResource>()
            val source = PersistenceTransportExtentSource()
            val first = modelBoundTransport(scope, owner, source)
            val call = requireNotNull(owner.tryBeginCall(first.record, PersistenceTransportCallKind.valueOf(kind)))
            first.resource.close()
            first.completeBody()
            val next = owner.prepareBoundConstruction(PersistenceTransportExtent(source, PersistenceTransportRole.AUX_CANCEL))
            assertNull(owner.prepareBoundReservation(next))
            assertEquals(PersistenceTransportRefusal.FULL, owner.reserveBoundConstruction(next))
            assertSame(call, first.ticket.entry.cells(call.kind)[call.slotHint])
            assertTrue(owner.completeCall(call))
            assertBoundConstructorRefused(owner, next)
            val replacement = modelBoundTransport(scope, owner, source)
            assertFalse(owner.completeCall(call))
            assertEquals(PersistenceTransportClosePhase.NOT_STARTED, replacement.ticket.entry.firstClose)
        }

    @Test
    fun `MODEL a pending primary constructor remains charged when successor fencing precedes late raw return`() = TransportTestScope("MODEL").use { scope ->
        val owner = PersistenceTransportOwner<TransportModelResource>()
        val source = PersistenceTransportExtentSource()
        val first = owner.prepareBoundConstruction(PersistenceTransportExtent(source, PersistenceTransportRole.PRIMARY))
        scope.own(AutoCloseable { owner.requestClose(first.record) })
        assertNull(owner.prepareBoundReservation(first))
        assertNull(owner.reserveBoundConstruction(first))
        val held = scope.gate()
        val original = scope.launch {
            owner.constructReserved(first) { record ->
                held.hold()
                TransportModelResource(owner, record)
            }
        }
        held.awaitEntered()
        val next = owner.prepareBoundConstruction(PersistenceTransportExtent(source, PersistenceTransportRole.PRIMARY))
        assertNull(owner.prepareBoundReservation(next))
        assertEquals(PersistenceTransportCloseRequest.WAITING_FOR_RAW, owner.requestClose(first.record))
        assertEquals(PersistenceTransportRefusal.FULL, owner.reserveBoundConstruction(next))
        assertSame(first.record, transportSnapshot(owner).primary?.record)
        held.release()
        assertTrue(original.join() is PersistenceTransportCreation.Retained)
        assertTrue(first.entry.raw.get() != null)
        assertEquals(PersistenceTransportConstruction.RETURNED, first.entry.construction)
        assertBoundConstructorRefused(owner, next)
        val replacement = modelBoundTransport(scope, owner, source, PersistenceTransportRole.PRIMARY)
        assertSame(replacement.record, transportSnapshot(owner).primary?.record)
    }

    @Test
    fun `MODEL failed bound constructor cannot be rotated by a primary successor checkpoint`() = TransportTestScope("MODEL").use {
        val owner = PersistenceTransportOwner<TransportModelResource>()
        val source = PersistenceTransportExtentSource()
        val first = owner.prepareBoundConstruction(PersistenceTransportExtent(source, PersistenceTransportRole.PRIMARY))
        assertNull(owner.prepareBoundReservation(first))
        assertNull(owner.reserveBoundConstruction(first))
        val failure = TransportTestFailure()
        assertSame(failure, runCatching { owner.constructReserved(first) { throw failure } }.exceptionOrNull())
        val next = owner.prepareBoundConstruction(PersistenceTransportExtent(source, PersistenceTransportRole.PRIMARY))
        assertNull(owner.prepareBoundReservation(next))
        assertEquals(PersistenceTransportCloseRequest.NO_RAW_RETURNED, owner.requestClose(first.record))
        assertEquals(PersistenceTransportRefusal.FULL, owner.reserveBoundConstruction(next))
        assertEquals(PersistenceTransportInvocation.THREW, first.entry.invocation.get())
        assertEquals(PersistenceTransportConstruction.FAILED, first.entry.construction)
        assertEquals(0, failure.renders.get())
        assertBoundConstructorRefused(owner, next)
    }

    private fun removeFact(entry: PersistenceTransportEntry<TransportModelResource>, missing: String) {
        when (missing) {
            "INVOCATION" -> entry.invocation.set(PersistenceTransportInvocation.INVOKING)
            "CONSTRUCTION" -> entry.construction = PersistenceTransportConstruction.ACTIVE
            "RAW" -> entry.raw.set(null)
            "CLOSE_NOT_STARTED" -> entry.firstClose = PersistenceTransportClosePhase.NOT_STARTED
            "CLOSE_RUNNING" -> entry.firstClose = PersistenceTransportClosePhase.RUNNING
            "CLOSE_FAILED" -> entry.firstClose = PersistenceTransportClosePhase.FAILED
            "BUSINESS" -> entry.business[0] = PersistenceTransportCall(entry.record, PersistenceTransportCallKind.BUSINESS, 0)
            "OBSERVATION" -> entry.observations[0] = PersistenceTransportCall(entry.record, PersistenceTransportCallKind.OBSERVATION, 0)
            "ALL_CALL_FENCE" -> entry.allCallsSealed = false
            "BUSINESS_FENCE" -> entry.businessSealed = false
            "EXTENT" -> entry.extentEnded = false
            else -> error("Unknown synthetic missing disposal fact.")
        }
    }
}
