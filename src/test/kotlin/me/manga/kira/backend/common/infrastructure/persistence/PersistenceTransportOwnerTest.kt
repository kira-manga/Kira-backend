package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
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

class PersistenceTransportOwnerTest {
    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["PRIMARY", "AUX_CANCEL"])
    fun `MODEL cold owner has exactly two distinct permanently charged roles`(roleName: String) = TransportTestScope("MODEL").use {
        val owner = PersistenceTransportOwner<TransportModelResource>()
        val cold = transportSnapshot(owner)
        assertNull(cold.primary)
        assertNull(cold.auxiliary)
        assertEquals(0, cold.revision)
        val role = PersistenceTransportRole.valueOf(roleName)
        val first = modelTransport(owner, role)
        val otherRole = PersistenceTransportRole.entries.single { candidate -> candidate != role }
        val second = modelTransport(owner, otherRole)
        assertNotSame(first.record, second.record)
        assertSame(first.resource, retainedTransportTestRaw(owner, first.record))
        assertEquals(PersistenceTransportConstruction.RETURNED, transportRecordSnapshot(owner, first.record).construction)
        first.resource.close()
        val calls = AtomicInteger()
        val refused = owner.tryCreate(role) { record ->
            calls.incrementAndGet()
            TransportModelResource(owner, record)
        }
        assertEquals(PersistenceTransportRefusal.FULL, (refused as PersistenceTransportCreation.Refused).reason)
        assertEquals(0, calls.get())
        assertSame(first.resource, retainedTransportTestRaw(owner, first.record))
        assertEquals(0, first.resource.renders.get())
        assertEquals(0, first.resource.comparisons.get())
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["FULL", "SEALED", "EXHAUSTED", "CONTENDED"])
    fun `MODEL denied reservation invokes no constructor`(reason: String) = TransportTestScope("MODEL").use { scope ->
        val owner = PersistenceTransportOwner<TransportModelResource>()
        val calls = AtomicInteger()
        val create = {
            owner.tryCreate(PersistenceTransportRole.PRIMARY) { record ->
                calls.incrementAndGet()
                TransportModelResource(owner, record)
            }
        }
        val result = when (reason) {
            "FULL" -> {
                modelTransport(owner)
                create()
            }

            "SEALED" -> {
                assertTrue(owner.seal())
                create()
            }

            "EXHAUSTED" -> {
                setTransportTestRevision(owner, Long.MAX_VALUE - 1)
                owner.seal()
                create()
            }

            else -> transportTestLock(owner).withLock {
                val caller = scope.launch(action = create)
                val value = caller.join()
                assertFalse(transportTestLock(owner).hasQueuedThread(caller.thread))
                value
            }
        }
        assertEquals(PersistenceTransportRefusal.valueOf(reason), (result as PersistenceTransportCreation.Refused).reason)
        assertEquals(0, calls.get())
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["IO", "RUNTIME", "ERROR"])
    fun `MODEL constructor failure preserves thrown identity and unknown ownership`(kind: String) = TransportTestScope("MODEL").use {
        val owner = PersistenceTransportOwner<TransportModelResource>()
        val failure = when (kind) {
            "IO" -> IOException("Synthetic constructor failure.")
            "RUNTIME" -> IllegalArgumentException("Synthetic constructor failure.")
            else -> AssertionError("Synthetic constructor failure.")
        }
        val record = AtomicReference<PersistenceTransportRecord>()
        val thrown = runCatching {
            owner.tryCreate(PersistenceTransportRole.PRIMARY) {
                record.set(it)
                throw failure
            }
        }.exceptionOrNull()
        assertSame(failure, thrown)
        val current = requireNotNull(record.get())
        val snapshot = transportRecordSnapshot(owner, current)
        assertEquals(PersistenceTransportConstruction.FAILED, snapshot.construction)
        assertTrue(snapshot.unknown)
        assertTrue(snapshot.businessSealed)
        assertFalse(snapshot.rawReturned)
        assertEquals(PersistenceTransportCloseRequest.NO_RAW_RETURNED, owner.requestClose(current))
        assertEquals(PersistenceTransportClosePhase.NOT_STARTED, snapshot.firstClose)
    }

    @Test
    fun `MODEL late direct raw is retained before queued settlement and never exposed after seal`() = TransportTestScope("MODEL").use { scope ->
        val owner = PersistenceTransportOwner<TransportModelResource>()
        val gate = scope.gate()
        val raw = AtomicReference<TransportModelResource>()
        val caller = scope.launch {
            owner.tryCreate(PersistenceTransportRole.PRIMARY) { record ->
                val resource = TransportModelResource(owner, record)
                raw.set(resource)
                gate.hold()
                resource
            }
        }
        gate.awaitEntered()
        val resource = requireNotNull(raw.get())
        assertEquals(PersistenceTransportConstruction.ACTIVE, transportRecordSnapshot(owner, resource.record).construction)
        // This different actual Thread can reserve the other role while the constructor is gated.
        val other = modelTransport(owner, PersistenceTransportRole.AUX_CANCEL)
        assertEquals(PersistenceTransportConstruction.RETURNED, transportRecordSnapshot(owner, other.record).construction)
        val lock = transportTestLock(owner)
        lock.withLock {
            gate.release()
            awaitTransportTestFact { lock.hasQueuedThread(caller.thread) }
            assertSame(resource, retainedTransportTestRaw(owner, resource.record))
            assertEquals(PersistenceTransportConstruction.ACTIVE, transportRecordSnapshot(owner, resource.record).construction)
            assertTrue(owner.seal())
        }
        val retained = caller.join() as PersistenceTransportCreation.Retained
        assertSame(resource.record, retained.record)
        assertEquals(PersistenceTransportConstruction.RETURNED, transportRecordSnapshot(owner, retained.record).construction)
        assertEquals(PersistenceTransportCloseRequest.REQUESTED, owner.requestClose(retained.record))
        assertEquals(1, resource.closes.get())
    }

    @Test
    fun `MODEL completed creation can be sealed later without hiding an admitted result`() = TransportTestScope("MODEL").use {
        val owner = PersistenceTransportOwner<TransportModelResource>()
        val created = modelTransport(owner)
        assertTrue(owner.seal(created.record))
        assertFalse(owner.seal(created.record))
        assertSame(created.resource, retainedTransportTestRaw(owner, created.record))
        assertNull(owner.tryBeginCall(created.record, PersistenceTransportCallKind.BUSINESS))
        val observation = requireNotNull(owner.tryBeginCall(created.record, PersistenceTransportCallKind.OBSERVATION))
        assertTrue(owner.completeCall(observation))
    }

    @Test
    fun `MODEL request before raw waits and must explicitly revisit the retained result`() = TransportTestScope("MODEL").use { scope ->
        val owner = PersistenceTransportOwner<TransportModelResource>()
        val gate = scope.gate()
        val record = AtomicReference<PersistenceTransportRecord>()
        val caller = scope.launch {
            owner.tryCreate(PersistenceTransportRole.PRIMARY) {
                record.set(it)
                gate.hold()
                TransportModelResource(owner, it)
            }
        }
        gate.awaitEntered()
        assertEquals(PersistenceTransportCloseRequest.WAITING_FOR_RAW, owner.requestClose(record.get()))
        gate.release()
        val created = caller.join() as PersistenceTransportCreation.Created<TransportModelResource>
        assertEquals(0, created.resource.closes.get())
        assertEquals(PersistenceTransportCloseRequest.REQUESTED, owner.requestClose(created.record))
        assertEquals(1, created.resource.closes.get())
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["PRIMARY", "AUX_CANCEL"])
    fun `MODEL forged role hints foreign owners and different raw references never grant authority`(roleName: String) = TransportTestScope("MODEL").use {
        val role = PersistenceTransportRole.valueOf(roleName)
        val owner = PersistenceTransportOwner<TransportModelResource>()
        val created = modelTransport(owner, role)
        val other = modelTransport(PersistenceTransportOwner(), role)
        for (record in listOf(PersistenceTransportRecord(role), other.record)) {
            assertNull(owner.tryBeginCall(record, PersistenceTransportCallKind.BUSINESS))
            assertNull(owner.tryBeginCall(record, PersistenceTransportCallKind.OBSERVATION))
            assertFalse(owner.seal(record))
            assertNull(owner.claimClose(record, created.resource))
            assertEquals(PersistenceTransportCloseRequest.REFUSED, owner.requestClose(record))
        }
        assertNull(owner.claimClose(created.record, other.resource))
        assertEquals(PersistenceTransportClosePhase.NOT_STARTED, transportRecordSnapshot(owner, created.record).firstClose)
        assertEquals(0, created.resource.comparisons.get())
        assertEquals(0, created.resource.renders.get())
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["BUSINESS", "OBSERVATION"])
    fun `MODEL fixed call partitions refuse overflow without consuming the other partition`(kindName: String) = TransportTestScope("MODEL").use {
        val owner = PersistenceTransportOwner<TransportModelResource>()
        val record = modelTransport(owner).record
        val kind = PersistenceTransportCallKind.valueOf(kindName)
        val maximum = if (kind == PersistenceTransportCallKind.BUSINESS) 32 else 8
        val calls = List(maximum) { requireNotNull(owner.tryBeginCall(record, kind)) }
        assertNull(owner.tryBeginCall(record, kind))
        val other = PersistenceTransportCallKind.entries.single { it != kind }
        val separate = requireNotNull(owner.tryBeginCall(record, other))
        val snapshot = transportRecordSnapshot(owner, record)
        assertEquals(if (kind == PersistenceTransportCallKind.BUSINESS) 32 else 1, snapshot.activeBusiness)
        assertEquals(if (kind == PersistenceTransportCallKind.OBSERVATION) 8 else 1, snapshot.activeObservations)
        calls.forEach { assertTrue(owner.completeCall(it)) }
        assertTrue(owner.completeCall(separate))
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["BUSINESS", "OBSERVATION"])
    fun `MODEL duplicate foreign and stale call tickets cannot clear a reused cell`(kindName: String) = TransportTestScope("MODEL").use {
        val owner = PersistenceTransportOwner<TransportModelResource>()
        val record = modelTransport(owner).record
        val kind = PersistenceTransportCallKind.valueOf(kindName)
        val old = requireNotNull(owner.tryBeginCall(record, kind))
        assertTrue(owner.completeCall(old))
        val replacement = requireNotNull(owner.tryBeginCall(record, kind))
        assertEquals(old.slotHint, replacement.slotHint)
        assertNotSame(old, replacement)
        val revision = transportSnapshot(owner).revision
        for (ticket in listOf(old, PersistenceTransportCall(record, kind, old.slotHint), PersistenceTransportCall(record, kind, -1))) {
            assertFalse(owner.completeCall(ticket))
        }
        val otherOwner = PersistenceTransportOwner<TransportModelResource>()
        val otherRecord = modelTransport(otherOwner).record
        val foreign = requireNotNull(otherOwner.tryBeginCall(otherRecord, kind))
        assertFalse(owner.completeCall(foreign))
        assertEquals(revision, transportSnapshot(owner).revision)
        assertTrue(owner.completeCall(replacement))
        assertFalse(owner.completeCall(replacement))
        assertTrue(otherOwner.completeCall(foreign))
    }

    @Test
    fun `MODEL actual completion waits for its short transition instead of losing accounting`() = TransportTestScope("MODEL").use { scope ->
        val owner = PersistenceTransportOwner<TransportModelResource>()
        val record = modelTransport(owner).record
        val call = requireNotNull(owner.tryBeginCall(record, PersistenceTransportCallKind.BUSINESS))
        val lock = transportTestLock(owner)
        val completing = lock.withLock {
            val worker = scope.launch { owner.completeCall(call) }
            awaitTransportTestFact { lock.hasQueuedThread(worker.thread) }
            assertEquals(1, transportRecordSnapshot(owner, record).activeBusiness)
            worker
        }
        assertTrue(completing.join())
        assertEquals(0, transportRecordSnapshot(owner, record).activeBusiness)
    }

    @Test
    fun `MODEL contended calls and snapshots refuse without queueing`() = TransportTestScope("MODEL").use { scope ->
        val owner = PersistenceTransportOwner<TransportModelResource>()
        val record = modelTransport(owner).record
        val lock = transportTestLock(owner)
        lock.withLock {
            val worker = scope.launch {
                assertNull(owner.tryBeginCall(record, PersistenceTransportCallKind.BUSINESS))
                assertNull(owner.tryBeginCall(record, PersistenceTransportCallKind.OBSERVATION))
                assertSame(PersistenceTransportSnapshot.Unavailable, owner.snapshot())
                true
            }
            assertTrue(worker.join())
            assertFalse(lock.hasQueuedThread(worker.thread))
        }
        assertEquals(0, transportRecordSnapshot(owner, record).activeBusiness)
    }

    @Test
    fun `MODEL later observation invalidates a zero count snapshot without claiming permanent disposal`() = TransportTestScope("MODEL").use {
        val owner = PersistenceTransportOwner<TransportModelResource>()
        val created = modelTransport(owner)
        created.resource.close()
        val earlier = transportSnapshot(owner)
        val observation = requireNotNull(owner.tryBeginCall(created.record, PersistenceTransportCallKind.OBSERVATION))
        val during = transportSnapshot(owner)
        assertTrue(during.revision > earlier.revision)
        assertEquals(1, during.primary?.activeObservations)
        assertTrue(owner.completeCall(observation))
        assertTrue(transportSnapshot(owner).revision > during.revision)
        assertEquals("PersistenceTransportSnapshot.Available(redacted)", earlier.toString())
        assertTrue(PersistenceTransportOwner::class.java.methods.none { it.name in listOf("release", "rotate", "reclaim", "getRaw") })
    }

    @Test
    fun `MODEL revision exhaustion never wraps and still records first close and actual exits`() = TransportTestScope("MODEL").use {
        val owner = PersistenceTransportOwner<TransportModelResource>()
        val created = modelTransport(owner)
        val business = requireNotNull(owner.tryBeginCall(created.record, PersistenceTransportCallKind.BUSINESS))
        val observation = requireNotNull(owner.tryBeginCall(created.record, PersistenceTransportCallKind.OBSERVATION))
        setTransportTestRevision(owner, Long.MAX_VALUE - 1)
        assertTrue(owner.completeCall(business))
        val exhausted = transportSnapshot(owner)
        assertEquals(Long.MAX_VALUE, exhausted.revision)
        assertTrue(exhausted.revisionExhausted)
        assertTrue(exhausted.sealed)
        assertTrue(requireNotNull(exhausted.primary).unknown)
        assertNull(owner.tryBeginCall(created.record, PersistenceTransportCallKind.BUSINESS))
        assertNull(owner.tryBeginCall(created.record, PersistenceTransportCallKind.OBSERVATION))
        created.resource.close()
        assertEquals(PersistenceTransportClosePhase.API_CLOSE_ACKNOWLEDGED, transportRecordSnapshot(owner, created.record).firstClose)
        assertEquals(1, transportRecordSnapshot(owner, created.record).activeObservations)
        assertTrue(owner.completeCall(observation))
        assertEquals(Long.MAX_VALUE, transportSnapshot(owner).revision)
        assertSame(created.resource, retainedTransportTestRaw(owner, created.record))
    }

    @Test
    fun `MODEL settlement that exhausts revision retains rather than delivers the new resource`() = TransportTestScope("MODEL").use {
        val owner = PersistenceTransportOwner<TransportModelResource>()
        val result = owner.tryCreate(PersistenceTransportRole.PRIMARY) { record ->
            setTransportTestRevision(owner, Long.MAX_VALUE - 1)
            TransportModelResource(owner, record)
        }
        val retained = result as PersistenceTransportCreation.Retained
        assertTrue(transportSnapshot(owner).revisionExhausted)
        assertTrue(transportRecordSnapshot(owner, retained.record).rawReturned)
        assertEquals(PersistenceTransportCloseRequest.REQUESTED, owner.requestClose(retained.record))
    }
}
