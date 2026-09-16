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
import java.util.concurrent.atomic.AtomicReference

class PersistenceTransportCloseTest {
    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["SUCCESS", "FAILURE"])
    fun `MODEL first close retains independent capacity and duplicates return without acknowledging the winner`(outcome: String) =
        TransportTestScope("MODEL").use { scope ->
            val owner = PersistenceTransportOwner<TransportModelResource>()
            val gate = scope.gate()
            val failure = IOException("Synthetic held close failure.")
            val created = modelTransport(owner) {
                gate.hold()
                if (outcome == "FAILURE") throw failure
            }
            val business = List(32) { requireNotNull(owner.tryBeginCall(created.record, PersistenceTransportCallKind.BUSINESS)) }
            val observations = List(8) { requireNotNull(owner.tryBeginCall(created.record, PersistenceTransportCallKind.OBSERVATION)) }
            val winner = scope.launch { runCatching { owner.requestClose(created.record) } }
            gate.awaitEntered()
            val running = transportRecordSnapshot(owner, created.record)
            assertEquals(PersistenceTransportClosePhase.RUNNING, running.firstClose)
            assertEquals(32, running.activeBusiness)
            assertEquals(8, running.activeObservations)
            val duplicate = scope.launch { owner.requestClose(created.record) }
            assertEquals(PersistenceTransportCloseRequest.REQUESTED, duplicate.join())
            assertTrue(winner.thread.isAlive)
            assertEquals(PersistenceTransportClosePhase.RUNNING, transportRecordSnapshot(owner, created.record).firstClose)
            assertEquals(1, created.resource.closes.get())
            gate.release()
            val result = winner.join()
            if (outcome == "FAILURE") assertSame(failure, result.exceptionOrNull()) else assertTrue(result.isSuccess)
            val expected = if (outcome == "FAILURE") PersistenceTransportClosePhase.FAILED else PersistenceTransportClosePhase.API_CLOSE_ACKNOWLEDGED
            assertEquals(expected, transportRecordSnapshot(owner, created.record).firstClose)
            owner.requestClose(created.record)
            assertEquals(expected, transportRecordSnapshot(owner, created.record).firstClose)
            assertEquals(32, transportRecordSnapshot(owner, created.record).activeBusiness)
            assertEquals(8, transportRecordSnapshot(owner, created.record).activeObservations)
            (business + observations).forEach { assertTrue(owner.completeCall(it)) }
            assertEquals(1, created.resource.closes.get())
        }

    @Test
    fun `MODEL same thread nested close coalesces while the enclosing call remains active`() = TransportTestScope("MODEL").use {
        val owner = PersistenceTransportOwner<TransportModelResource>()
        val record = AtomicReference<PersistenceTransportRecord>()
        val created = modelTransport(owner) {
            assertEquals(PersistenceTransportCloseRequest.REQUESTED, owner.requestClose(record.get()))
            assertEquals(PersistenceTransportClosePhase.RUNNING, transportRecordSnapshot(owner, record.get()).firstClose)
            assertEquals(1, transportRecordSnapshot(owner, record.get()).activeBusiness)
        }
        record.set(created.record)
        val outer = requireNotNull(owner.tryBeginCall(created.record, PersistenceTransportCallKind.BUSINESS))
        created.resource.close()
        assertEquals(1, created.resource.closes.get())
        assertEquals(PersistenceTransportClosePhase.API_CLOSE_ACKNOWLEDGED, transportRecordSnapshot(owner, created.record).firstClose)
        assertEquals(1, transportRecordSnapshot(owner, created.record).activeBusiness)
        assertNull(owner.tryBeginCall(created.record, PersistenceTransportCallKind.BUSINESS))
        assertTrue(owner.completeCall(outer))
    }

    @Test
    fun `MODEL only the exact close claim settles and no duplicate can upgrade a failed outcome`() = TransportTestScope("MODEL").use {
        val owner = PersistenceTransportOwner<TransportModelResource>()
        val created = modelTransport(owner)
        val claim = requireNotNull(owner.claimClose(created.record, created.resource))
        assertNull(owner.claimClose(created.record, created.resource))
        assertFalse(owner.completeClose(PersistenceTransportCloseClaim(created.record), true))
        val otherOwner = PersistenceTransportOwner<TransportModelResource>()
        val other = modelTransport(otherOwner)
        val foreign = requireNotNull(otherOwner.claimClose(other.record, other.resource))
        assertFalse(owner.completeClose(foreign, true))
        assertEquals(PersistenceTransportClosePhase.RUNNING, transportRecordSnapshot(owner, created.record).firstClose)
        assertTrue(owner.completeClose(claim, false))
        assertFalse(owner.completeClose(claim, true))
        assertFalse(owner.completeClose(claim, false))
        assertEquals(PersistenceTransportClosePhase.FAILED, transportRecordSnapshot(owner, created.record).firstClose)
        assertTrue(transportRecordSnapshot(owner, created.record).unknown)
        assertTrue(otherOwner.completeClose(foreign, true))
        // These manually produced state transitions are explicitly MODEL-only, not raw-close execution evidence.
        assertEquals(0, created.resource.closes.get())
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["IO", "RUNTIME", "ERROR", "HOSTILE"])
    fun `MODEL first close failure identity survives and remains failed across later no ops`(kind: String) = TransportTestScope("MODEL").use {
        val owner = PersistenceTransportOwner<TransportModelResource>()
        val failure = when (kind) {
            "IO" -> IOException("Synthetic close failure.")
            "RUNTIME" -> IllegalStateException("Synthetic close failure.")
            "ERROR" -> AssertionError("Synthetic close failure.")
            else -> TransportTestFailure()
        }
        val created = modelTransport(owner) { throw failure }
        assertSame(failure, runCatching { created.resource.close() }.exceptionOrNull())
        created.resource.close()
        assertEquals(1, created.resource.closes.get())
        assertEquals(PersistenceTransportClosePhase.FAILED, transportRecordSnapshot(owner, created.record).firstClose)
        assertSame(created.resource, retainedTransportTestRaw(owner, created.record))
        if (failure is TransportTestFailure) assertEquals(0, failure.renders.get())
        assertEquals(0, created.resource.renders.get())
        assertEquals(0, created.resource.comparisons.get())
    }

    @Test
    fun `MODEL first close seals its own role without silently sealing the independent role`() = TransportTestScope("MODEL").use {
        val owner = PersistenceTransportOwner<TransportModelResource>()
        val primary = modelTransport(owner)
        val auxiliary = modelTransport(owner, PersistenceTransportRole.AUX_CANCEL)
        primary.resource.close()
        assertNull(owner.tryBeginCall(primary.record, PersistenceTransportCallKind.BUSINESS))
        val independent = requireNotNull(owner.tryBeginCall(auxiliary.record, PersistenceTransportCallKind.BUSINESS))
        assertFalse(transportSnapshot(owner).sealed)
        assertEquals(PersistenceTransportClosePhase.NOT_STARTED, transportRecordSnapshot(owner, auxiliary.record).firstClose)
        assertTrue(owner.completeCall(independent))
    }
}
