package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.SocketException

class TrackedPersistencePreparedSocketTest {
    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(booleans = [false, true])
    fun `REAL_SOCKET_API standard and direct prepared sockets require one reservation`(direct: Boolean) = TransportTestScope("REAL_SOCKET_API").use { scope ->
        val owner = PersistenceTransportOwner<TrackedPersistenceSocket>()
        val prepared = if (direct) {
            TrackedPersistenceSocket.prepareApprovedDirect(owner, PersistenceTransportRole.PRIMARY)
        } else {
            TrackedPersistenceSocket.prepare(owner, PersistenceTransportRole.PRIMARY)
        }
        scope.own(AutoCloseable { owner.requestClose(prepared.record) })
        assertEquals(PersistenceTransportRefusal.INVALID_CONSTRUCTION, (prepared.construct() as PersistenceTransportCreation.Refused).reason)
        assertNull(transportSnapshot(owner).primary)
        assertNull(prepared.reserve())
        assertFalse(transportRecordSnapshot(owner, prepared.record).rawReturned)
        val created = prepared.construct() as PersistenceTransportCreation.Created<TrackedPersistenceSocket>
        scope.own(created.resource)
        assertSame(prepared.record, created.record)
        assertSame(created.resource, retainedTransportTestRaw(owner, prepared.record))
        assertFalse(created.resource.isClosed)
        assertEquals(PersistenceTransportRefusal.INVALID_CONSTRUCTION, (prepared.construct() as PersistenceTransportCreation.Refused).reason)
        created.resource.close()
        assertTrue(created.resource.isClosed)
        assertEquals(PersistenceTransportClosePhase.API_CLOSE_ACKNOWLEDGED, transportRecordSnapshot(owner, prepared.record).firstClose)
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(booleans = [false, true])
    fun `REAL_SOCKET_API a pre-fence grant retains its late literal socket`(direct: Boolean) = TransportTestScope("REAL_SOCKET_API").use { scope ->
        val owner = PersistenceTransportOwner<TrackedPersistenceSocket>()
        val prepared = if (direct) {
            TrackedPersistenceSocket.prepareApprovedDirect(owner, PersistenceTransportRole.PRIMARY)
        } else {
            TrackedPersistenceSocket.prepare(owner, PersistenceTransportRole.PRIMARY)
        }
        scope.own(AutoCloseable { owner.requestClose(prepared.record) })
        assertNull(prepared.reserve())
        assertTrue(owner.trySealForRetirement())
        assertTrue(owner.tryFenceCalls(prepared.record))
        val retained = prepared.construct() as PersistenceTransportCreation.Retained
        assertSame(prepared.record, retained.record)
        val socket = scope.own(retainedTransportTestRaw(owner, prepared.record) as TrackedPersistenceSocket)
        assertTransportBusinessRefused { socket.getInputStream() }
        assertTransportObservationRefused { socket.port }
        assertEquals(PersistenceTransportCloseRequest.REQUESTED, owner.requestClose(prepared.record))
        assertTrue(socket.isClosed)
        assertEquals(PersistenceTransportClosePhase.API_CLOSE_ACKNOWLEDGED, transportRecordSnapshot(owner, prepared.record).firstClose)
    }

    @Test
    fun `REAL_SOCKET_API permanently fenced socket and stream aliases cannot perform further work`() = TransportTestScope("REAL_SOCKET_API").use { scope ->
        val fixture = scope.socket()
        val peer = scope.connect(fixture)
        val input = fixture.socket.getInputStream()
        val output = fixture.socket.getOutputStream()
        assertTrue(fixture.owner.tryFenceCalls(fixture.record))
        assertTransportBusinessRefused { input.read() }
        assertTransportBusinessRefused { input.available() }
        assertTransportBusinessRefused { output.write(1) }
        val beforeNoOp = transportSnapshot(fixture.owner).revision
        output.flush() // Inherited OutputStream no-op: it must not delegate or acquire any call token.
        assertEquals(beforeNoOp, transportSnapshot(fixture.owner).revision)
        assertNoTransportCalls(fixture)
        assertTransportObservationRefused { fixture.socket.port }
        assertTransportObservationRefused { fixture.socket.localSocketAddress }
        assertTransportObservationRefused { fixture.socket.supportedOptions() }
        output.close()
        input.close()
        fixture.socket.close()
        assertEquals(-1, peer.getInputStream().read())
        assertTrue(fixture.socket.isClosed)
        assertEquals(PersistenceTransportClosePhase.API_CLOSE_ACKNOWLEDGED, transportRecordSnapshot(fixture.owner, fixture.record).firstClose)
        assertNoTransportCalls(fixture)
    }

    @Test
    fun `REAL_SOCKET_API fenced reads stay charged until close-induced exit`() = TransportTestScope("REAL_SOCKET_API").use { scope ->
        val fixture = scope.socket()
        scope.connect(fixture)
        val input = fixture.socket.getInputStream()
        val reading = scope.launch { runCatching { input.read() }.exceptionOrNull() is SocketException }
        awaitTransportSocketRead(reading.thread)
        assertTrue(fixture.owner.tryFenceCalls(fixture.record))
        assertEquals(1, transportRecordSnapshot(fixture.owner, fixture.record).activeBusiness)
        assertTransportObservationRefused { fixture.socket.port }
        fixture.socket.close()
        assertTrue(reading.join())
        assertNoTransportCalls(fixture)
    }
}
