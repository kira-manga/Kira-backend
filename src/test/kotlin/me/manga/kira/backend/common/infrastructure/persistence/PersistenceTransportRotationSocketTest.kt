package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** Real loopback Socket/stream adapters with explicitly MODEL extent identities and scheduling. */
class PersistenceTransportRotationSocketTest {
    @Test
    fun `REAL_SOCKET stale socket input output and tracked observation aliases cannot delegate after MODEL rotation`() =
        TransportTestScope("REAL_SOCKET_API").use { scope ->
            val owner = PersistenceTransportOwner<TrackedPersistenceSocket>()
            val source = PersistenceTransportExtentSource()
            val first = boundSocket(scope, owner, source)
            scope.connect(first)
            val input = first.socket.getInputStream()
            val output = first.socket.getOutputStream()
            val next = boundSocket(scope, owner, source)
            assertSame(next.record, transportSnapshot(owner).primary?.record)
            assertTrue(first.socket.isClosed) // Supplementary API observation; not the rotation authority.
            assertTransportBusinessRefused { input.read() }
            assertTransportBusinessRefused { input.available() }
            assertTransportBusinessRefused { output.write(1) }
            assertTransportBusinessRefused { first.socket.getInputStream() }
            assertTransportObservationRefused { first.socket.inetAddress }
            assertTransportObservationRefused { first.socket.supportedOptions() }
            input.close()
            output.close()
            first.socket.close()
            assertEquals(PersistenceTransportClosePhase.NOT_STARTED, transportRecordSnapshot(owner, next.record).firstClose)
            assertFalse(next.socket.isClosed)
        }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["BUSINESS", "OBSERVATION"])
    fun `REAL_SOCKET close does not erase an actual admitted MODEL-held binding body`(kind: String) = TransportTestScope("REAL_SOCKET_API").use { scope ->
        val owner = PersistenceTransportOwner<TrackedPersistenceSocket>()
        val source = PersistenceTransportExtentSource()
        val first = boundSocket(scope, owner, source)
        val held = scope.gate()
        val binding = first.modelBinding()
        val call = scope.launch {
            if (kind == "BUSINESS") binding.business { held.hold() } else binding.observation { held.hold() }
            true
        }
        held.awaitEntered()
        val next = owner.prepareBoundConstruction(PersistenceTransportExtent(source, PersistenceTransportRole.PRIMARY))
        assertNull(owner.prepareBoundReservation(next))
        assertEquals(PersistenceTransportCloseRequest.REQUESTED, owner.requestClose(first.record))
        val state = transportRecordSnapshot(owner, first.record)
        assertEquals(PersistenceTransportClosePhase.API_CLOSE_ACKNOWLEDGED, state.firstClose)
        assertEquals(1, state.activeBusiness + state.activeObservations)
        assertEquals(PersistenceTransportRefusal.FULL, owner.reserveBoundConstruction(next))
        assertBoundConstructorRefused(owner, next)
        held.release()
        assertTrue(call.join())
        val replacement = boundSocket(scope, owner, source)
        assertSame(replacement.record, transportSnapshot(owner).primary?.record)
    }

    private fun boundSocket(
        scope: TransportTestScope,
        owner: PersistenceTransportOwner<TrackedPersistenceSocket>,
        source: PersistenceTransportExtentSource,
    ): TransportSocketFixture {
        val ticket = owner.prepareBoundConstruction(PersistenceTransportExtent(source, PersistenceTransportRole.PRIMARY))
        val binding = PersistenceTransportBinding(owner, ticket.record)
        // Own-project private constructor only, not JDK/driver reflection. Actual factory provenance is tested in fresh driver children.
        val constructor = TrackedPersistenceSocket::class.java.getDeclaredConstructor(PersistenceTransportBinding::class.java)
        check(constructor.trySetAccessible())
        scope.own(AutoCloseable { owner.requestClose(ticket.record) })
        assertNull(owner.prepareBoundReservation(ticket))
        ticket.predecessor?.let { owner.requestClose(it) }
        assertNull(owner.reserveBoundConstruction(ticket))
        val created = owner.constructReserved(ticket) { constructor.newInstance(binding) } as PersistenceTransportCreation.Created<TrackedPersistenceSocket>
        return TransportSocketFixture(owner, created.record, created.resource)
    }
}
