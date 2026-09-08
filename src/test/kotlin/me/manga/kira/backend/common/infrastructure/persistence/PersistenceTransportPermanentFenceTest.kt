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
import kotlin.concurrent.withLock

class PersistenceTransportPermanentFenceTest {
    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["PRIMARY", "AUX_CANCEL"])
    fun `MODEL permanent fence preserves admitted calls and first-close ownership`(roleName: String) = TransportTestScope("MODEL").use {
        val owner = PersistenceTransportOwner<TransportModelResource>()
        val created = modelTransport(owner, PersistenceTransportRole.valueOf(roleName))
        val business = requireNotNull(owner.tryBeginCall(created.record, PersistenceTransportCallKind.BUSINESS))
        val observation = requireNotNull(owner.tryBeginCall(created.record, PersistenceTransportCallKind.OBSERVATION))
        assertTrue(owner.tryFenceCalls(created.record))
        val fenced = transportRecordSnapshot(owner, created.record)
        assertTrue(fenced.allCallsSealed)
        assertTrue(fenced.businessSealed)
        assertEquals(1, fenced.activeBusiness)
        assertEquals(1, fenced.activeObservations)
        assertEquals(PersistenceTransportClosePhase.NOT_STARTED, fenced.firstClose)
        assertNull(owner.tryBeginCall(created.record, PersistenceTransportCallKind.BUSINESS))
        assertNull(owner.tryBeginCall(created.record, PersistenceTransportCallKind.OBSERVATION))
        val revision = transportSnapshot(owner).revision
        assertTrue(owner.tryFenceCalls(created.record))
        assertEquals(revision, transportSnapshot(owner).revision)
        created.resource.close()
        assertEquals(PersistenceTransportClosePhase.API_CLOSE_ACKNOWLEDGED, transportRecordSnapshot(owner, created.record).firstClose)
        assertEquals(1, transportRecordSnapshot(owner, created.record).activeObservations)
        assertTrue(owner.completeCall(business))
        assertTrue(owner.completeCall(observation))
        assertFalse(owner.completeCall(observation))
        assertSame(created.resource, retainedTransportTestRaw(owner, created.record))
    }

    @Test
    fun `MODEL business retirement still permits observations until the separate permanent fence`() = TransportTestScope("MODEL").use {
        val owner = PersistenceTransportOwner<TransportModelResource>()
        val created = modelTransport(owner)
        assertTrue(owner.trySealForRetirement())
        assertTrue(owner.trySealForRetirement())
        assertTrue(transportSnapshot(owner).sealed)
        assertFalse(transportRecordSnapshot(owner, created.record).allCallsSealed)
        assertNull(owner.tryBeginCall(created.record, PersistenceTransportCallKind.BUSINESS))
        val observation = requireNotNull(owner.tryBeginCall(created.record, PersistenceTransportCallKind.OBSERVATION))
        val ticket = owner.prepareConstruction(PersistenceTransportRole.AUX_CANCEL)
        assertEquals(PersistenceTransportRefusal.SEALED, owner.reserveConstruction(ticket))
        assertTrue(owner.completeCall(observation))
        assertTrue(owner.tryFenceCalls(created.record))
        assertNull(owner.tryBeginCall(created.record, PersistenceTransportCallKind.OBSERVATION))
    }

    @Test
    fun `MODEL contended retirement and permanent fences do not queue or invent completion`() = TransportTestScope("MODEL").use { scope ->
        val owner = PersistenceTransportOwner<TransportModelResource>()
        val created = modelTransport(owner)
        val lock = transportTestLock(owner)
        lock.withLock {
            val caller = scope.launch {
                assertFalse(owner.trySealForRetirement())
                assertFalse(owner.tryFenceCalls(created.record))
                true
            }
            assertTrue(caller.join())
            assertFalse(lock.hasQueuedThread(caller.thread))
            assertFalse(transportSnapshot(owner).sealed)
            assertFalse(transportRecordSnapshot(owner, created.record).allCallsSealed)
        }
        assertTrue(owner.trySealForRetirement())
        assertTrue(owner.tryFenceCalls(created.record))
    }

    @Test
    fun `MODEL a forged role or a foreign record cannot fence either actual transport`() = TransportTestScope("MODEL").use {
        val owner = PersistenceTransportOwner<TransportModelResource>()
        val primary = modelTransport(owner)
        val auxiliary = modelTransport(owner, PersistenceTransportRole.AUX_CANCEL)
        val foreign = modelTransport(PersistenceTransportOwner())
        for (record in listOf(PersistenceTransportRecord(PersistenceTransportRole.PRIMARY), foreign.record)) {
            assertFalse(owner.tryFenceCalls(record))
        }
        assertFalse(transportRecordSnapshot(owner, primary.record).allCallsSealed)
        assertFalse(transportRecordSnapshot(owner, auxiliary.record).allCallsSealed)
    }

    @Test
    fun `MODEL permanent fencing does not promote a failed first close or release its role`() = TransportTestScope("MODEL").use {
        val owner = PersistenceTransportOwner<TransportModelResource>()
        val failure = IOException("Synthetic first-close failure.")
        val created = modelTransport(owner, closeAction = { throw failure })
        assertSame(failure, runCatching { created.resource.close() }.exceptionOrNull())
        assertTrue(owner.tryFenceCalls(created.record))
        created.resource.close()
        assertEquals(1, created.resource.closes.get())
        assertEquals(PersistenceTransportClosePhase.FAILED, transportRecordSnapshot(owner, created.record).firstClose)
        assertTrue(transportRecordSnapshot(owner, created.record).unknown)
        assertEquals(PersistenceTransportRefusal.FULL, owner.reserveConstruction(owner.prepareConstruction(PersistenceTransportRole.PRIMARY)))
    }
}
