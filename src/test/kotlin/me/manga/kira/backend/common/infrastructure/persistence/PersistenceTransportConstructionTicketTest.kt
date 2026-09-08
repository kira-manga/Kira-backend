package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.withLock

class PersistenceTransportConstructionTicketTest {
    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["PRIMARY", "AUX_CANCEL"])
    fun `MODEL preparation is inert and only one reservation authorizes construction`(roleName: String) = TransportTestScope("MODEL").use {
        val owner = PersistenceTransportOwner<TransportModelResource>()
        val ticket = owner.prepareConstruction(PersistenceTransportRole.valueOf(roleName))
        val calls = AtomicInteger()
        val construct = {
            owner.constructReserved(ticket) { record ->
                calls.incrementAndGet()
                TransportModelResource(owner, record)
            }
        }
        assertNull(transportSnapshot(owner).primary)
        assertNull(transportSnapshot(owner).auxiliary)
        assertInvalidConstruction(construct())
        assertEquals(0, calls.get())
        assertNull(owner.reserveConstruction(ticket))
        assertFalse(transportRecordSnapshot(owner, ticket.record).rawReturned)
        assertEquals(PersistenceTransportRefusal.INVALID_CONSTRUCTION, owner.reserveConstruction(ticket))
        val created = construct() as PersistenceTransportCreation.Created<TransportModelResource>
        assertSame(ticket.record, created.record)
        assertSame(created.resource, retainedTransportTestRaw(owner, ticket.record))
        assertInvalidConstruction(construct())
        assertEquals(1, calls.get())
    }

    @Test
    fun `MODEL foreign owner and forged ticket cannot consume the exact original grant`() = TransportTestScope("MODEL").use {
        val owner = PersistenceTransportOwner<TransportModelResource>()
        val other = PersistenceTransportOwner<TransportModelResource>()
        val ticket = owner.prepareConstruction(PersistenceTransportRole.PRIMARY)
        val forged = PersistenceTransportConstructionTicket(owner, ticket.entry)
        val calls = AtomicInteger()
        val constructor = { record: PersistenceTransportRecord ->
            calls.incrementAndGet()
            TransportModelResource(owner, record)
        }
        assertEquals(PersistenceTransportRefusal.INVALID_CONSTRUCTION, other.reserveConstruction(ticket))
        assertEquals(PersistenceTransportRefusal.INVALID_CONSTRUCTION, owner.reserveConstruction(forged))
        assertNull(owner.reserveConstruction(ticket))
        assertInvalidConstruction(other.constructReserved(ticket, constructor))
        assertInvalidConstruction(owner.constructReserved(forged, constructor))
        assertEquals(0, calls.get())
        assertTrue(owner.constructReserved(ticket, constructor) is PersistenceTransportCreation.Created<*>)
        assertEquals(1, calls.get())
        assertNull(transportSnapshot(other).primary)
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["FULL", "SEALED", "EXHAUSTED", "CONTENDED"])
    fun `MODEL refused ticket cannot be resubmitted or allocate after contention ends`(reason: String) = TransportTestScope("MODEL").use { scope ->
        val owner = PersistenceTransportOwner<TransportModelResource>()
        val ticket = owner.prepareConstruction(PersistenceTransportRole.PRIMARY)
        val refusal = when (reason) {
            "FULL" -> {
                modelTransport(owner)
                owner.reserveConstruction(ticket)
            }

            "SEALED" -> {
                owner.seal()
                owner.reserveConstruction(ticket)
            }

            "EXHAUSTED" -> {
                setTransportTestRevision(owner, Long.MAX_VALUE - 1)
                owner.seal()
                owner.reserveConstruction(ticket)
            }

            else -> transportTestLock(owner).withLock {
                val caller = scope.launch { requireNotNull(owner.reserveConstruction(ticket)) }
                val result = caller.join()
                assertFalse(transportTestLock(owner).hasQueuedThread(caller.thread))
                result
            }
        }
        assertEquals(PersistenceTransportRefusal.valueOf(reason), refusal)
        assertEquals(PersistenceTransportRefusal.INVALID_CONSTRUCTION, owner.reserveConstruction(ticket))
        val calls = AtomicInteger()
        val result = owner.constructReserved(ticket) { record ->
            calls.incrementAndGet()
            TransportModelResource(owner, record)
        }
        assertInvalidConstruction(result)
        assertEquals(0, calls.get())
    }

    @Test
    fun `MODEL real concurrent and same-thread reentry cannot rerun or fail an active constructor`() = TransportTestScope("MODEL").use { scope ->
        val owner = PersistenceTransportOwner<TransportModelResource>()
        val ticket = owner.prepareConstruction(PersistenceTransportRole.PRIMARY)
        val gate = scope.gate()
        val calls = AtomicInteger()
        val denied = {
            owner.constructReserved(ticket) { record ->
                calls.incrementAndGet()
                TransportModelResource(owner, record)
            }
        }
        assertNull(owner.reserveConstruction(ticket))
        val original = scope.launch {
            owner.constructReserved(ticket) { record ->
                calls.incrementAndGet()
                assertInvalidConstruction(denied())
                gate.hold()
                TransportModelResource(owner, record)
            }
        }
        gate.awaitEntered()
        assertInvalidConstruction(scope.launch(action = denied).join())
        assertEquals(PersistenceTransportConstruction.ACTIVE, transportRecordSnapshot(owner, ticket.record).construction)
        assertFalse(transportRecordSnapshot(owner, ticket.record).unknown)
        gate.release()
        assertTrue(original.join() is PersistenceTransportCreation.Created<*>)
        assertEquals(1, calls.get())
    }

    @Test
    fun `MODEL reserved invocation refuses a held T lock before entering raw constructor`() = TransportTestScope("MODEL").use {
        val owner = PersistenceTransportOwner<TransportModelResource>()
        val ticket = owner.prepareConstruction(PersistenceTransportRole.PRIMARY)
        val calls = AtomicInteger()
        assertNull(owner.reserveConstruction(ticket))
        val constructor = { record: PersistenceTransportRecord ->
            assertFalse(transportTestLock(owner).isHeldByCurrentThread)
            calls.incrementAndGet()
            TransportModelResource(owner, record)
        }
        transportTestLock(owner).withLock { assertInvalidConstruction(owner.constructReserved(ticket, constructor)) }
        assertEquals(0, calls.get())
        assertTrue(owner.constructReserved(ticket, constructor) is PersistenceTransportCreation.Created<*>)
        assertEquals(1, calls.get())
    }

    @Test
    fun `MODEL raw retention precedes contended settlement and a fence cannot lose a granted constructor`() = TransportTestScope("MODEL").use { scope ->
        val owner = PersistenceTransportOwner<TransportModelResource>()
        val ticket = owner.prepareConstruction(PersistenceTransportRole.PRIMARY)
        val gate = scope.gate()
        val raw = AtomicReference<TransportModelResource>()
        assertNull(owner.reserveConstruction(ticket))
        val caller = scope.launch {
            owner.constructReserved(ticket) { record ->
                val resource = TransportModelResource(owner, record)
                raw.set(resource)
                gate.hold()
                resource
            }
        }
        gate.awaitEntered()
        val lock = transportTestLock(owner)
        lock.withLock {
            assertTrue(owner.trySealForRetirement())
            assertTrue(owner.tryFenceCalls(ticket.record))
            gate.release()
            awaitTransportTestFact { lock.hasQueuedThread(caller.thread) }
            assertSame(raw.get(), retainedTransportTestRaw(owner, ticket.record))
            assertEquals(PersistenceTransportConstruction.ACTIVE, transportRecordSnapshot(owner, ticket.record).construction)
            assertEquals(PersistenceTransportInvocation.RETURNED, ticket.entry.invocation.get())
        }
        val result = caller.join() as PersistenceTransportCreation.Retained
        assertSame(ticket.record, result.record)
        assertEquals(PersistenceTransportCloseRequest.REQUESTED, owner.requestClose(ticket.record))
        assertEquals(PersistenceTransportClosePhase.API_CLOSE_ACKNOWLEDGED, transportRecordSnapshot(owner, ticket.record).firstClose)
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["IO", "RUNTIME", "ERROR"])
    fun `MODEL failed invocation keeps the original failure and permanently consumes its grant`(kind: String) = TransportTestScope("MODEL").use {
        val owner = PersistenceTransportOwner<TransportModelResource>()
        val ticket = owner.prepareConstruction(PersistenceTransportRole.PRIMARY)
        val failure = when (kind) {
            "IO" -> IOException("Synthetic split-construction failure.")
            "RUNTIME" -> IllegalArgumentException("Synthetic split-construction failure.")
            else -> AssertionError("Synthetic split-construction failure.")
        }
        assertNull(owner.reserveConstruction(ticket))
        assertSame(failure, runCatching { owner.constructReserved(ticket) { throw failure } }.exceptionOrNull())
        assertEquals(PersistenceTransportInvocation.THREW, ticket.entry.invocation.get())
        assertEquals(PersistenceTransportConstruction.FAILED, transportRecordSnapshot(owner, ticket.record).construction)
        assertTrue(transportRecordSnapshot(owner, ticket.record).unknown)
        assertInvalidConstruction(owner.constructReserved(ticket) { error("A failed constructor must not be retried.") })
    }

    private fun assertInvalidConstruction(result: PersistenceTransportCreation<*>) {
        assertEquals(PersistenceTransportRefusal.INVALID_CONSTRUCTION, (result as PersistenceTransportCreation.Refused).reason)
    }
}
