package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException
import java.net.SocketException
import java.util.concurrent.atomic.AtomicReference

/** Actual acceptor/accepted-session capture and cleanup; all sockets stay unbound or inert MODEL identities. */
internal class PgLifecycleDatabaseRelayAcceptorTest {
    @Test
    fun `MODEL an unbound acceptor failure is separate and has no invented accepted index`() {
        PgLifecycleDatabaseRelayEvidenceFixture().use { fixture ->
            // Start only the retained actor, not Relay.start(): accept on its unbound listener fails without opening a service.
            val actor = lifecycleField(fixture.relay, "actor") as Thread
            actor.start()
            awaitLifecycleFact(5_000) { !actor.isAlive }
            val event = requireNotNull(acceptorFailure(fixture.relay))
            assertEquals(null, event.acceptedIndex)
            assertEquals(PgLifecycleDatabaseRelayAssociation.UNREGISTERED, event.association)
            assertEquals(PgLifecycleDatabaseRelayStage.ACCEPT, event.stage)
            assertEquals(PgLifecycleDatabaseRelayFailureType.SOCKET, event.type)
            assertTrue(fixture.relay.diagnostic(0).contains("accept_event=UNAVAILABLE/UNREGISTERED/ACCEPTOR/ACCEPT/SOCKET/false"))
            assertThrows(IllegalStateException::class.java) { fixture.relay.progress() }
            assertThrows(IllegalStateException::class.java) { fixture.relay.close() }
            assertSame(event, acceptorFailure(fixture.relay))
        }
    }

    @Test
    fun `MODEL accepted constructor failure is captured before unretained finally replaces the thrown object`() {
        PgLifecycleDatabaseRelayEvidenceFixture().use { fixture ->
            val original = PhysicalHostileError()
            val closing = IOException()
            val socket = PgLifecycleDatabaseRelayModelSocket(closing = { throw closing })
            val returned = assertThrows(IOException::class.java) {
                fixture.relay.acceptSession(socket) { index, _ ->
                    assertEquals(1, index)
                    throw original
                }
            }
            assertSame(closing, returned) // Preserve the original finally semantics; only the bounded evidence keeps the earlier failure.
            val event = requireNotNull(acceptorFailure(fixture.relay))
            assertEquals(1, event.acceptedIndex)
            assertEquals(PgLifecycleDatabaseRelayAssociation.UNREGISTERED, event.association)
            assertEquals(PgLifecycleDatabaseRelayStage.SESSION_CONSTRUCT, event.stage)
            assertEquals(PgLifecycleDatabaseRelayFailureType.ERROR, event.type)
            assertFalse(event.fixtureClosing)
            assertEquals(1, socket.closes.get())
            assertEquals(0, original.reads.get())
            assertTrue(fixture.relay.diagnostic(0).contains("accepted=1 retained=0 registered=0"))
            assertThrows(IllegalStateException::class.java) { fixture.relay.close() }
            assertSame(event, acceptorFailure(fixture.relay))
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `MODEL acceptor ignores only expected closing SocketException and labels a later retained cleanup span`(closeFails: Boolean) {
        val expected = if (closeFails) IllegalStateException::class.java else null
        PgLifecycleDatabaseRelayEvidenceFixture(expectedCloseFailure = expected).use { fixture ->
            val original = SocketException()
            val closing = PhysicalHostileFailure()
            val socket = PgLifecycleDatabaseRelayModelSocket(closing = { if (closeFails) throw closing })
            val returned = assertThrows(if (closeFails) PhysicalHostileFailure::class.java else SocketException::class.java) {
                fixture.relay.acceptSession(socket) { _, _ ->
                    fixture.relay.close() // Models cleanup winning immediately after accept, before construction fails.
                    throw original
                }
            }
            assertSame(if (closeFails) closing else original, returned)
            val event = acceptorFailure(fixture.relay)
            if (closeFails) {
                requireNotNull(event)
                assertEquals(PgLifecycleDatabaseRelayStage.UNRETAINED_CLOSE, event.stage)
                assertEquals(PgLifecycleDatabaseRelayFailureType.OTHER, event.type)
                assertTrue(event.fixtureClosing)
                assertEquals(1, event.acceptedIndex)
            } else {
                assertEquals(null, event)
                assertTrue(fixture.relay.diagnostic(0).contains("accept_event=NOT_RECORDED"))
            }
            assertEquals(1, socket.closes.get())
            assertEquals(0, closing.reads.get())
        }
    }

    @Test
    fun `MODEL seventh retained overflow offender never starts and is still owned through failed cleanup`() {
        PgLifecycleDatabaseRelayEvidenceFixture().use { fixture ->
            repeat(6) { fixture.accept { session, _ -> session.close() } }
            val client = PgLifecycleDatabaseRelayModelSocket()
            val upstream = PgLifecycleDatabaseRelayModelSocket()
            assertThrows(IllegalStateException::class.java) { fixture.accept(client, upstream) }
            val offender = fixture.sessions.last()
            val event = requireNotNull(acceptorFailure(fixture.relay))
            assertEquals(7, fixture.sessions.size)
            assertEquals(7, offender.state.acceptedIndex)
            assertEquals(7, event.acceptedIndex)
            assertEquals(PgLifecycleDatabaseRelayStage.SESSION_RETAIN, event.stage)
            assertEquals(PgLifecycleDatabaseRelayFailureType.STATE_CHECK, event.type)
            assertEquals(PgLifecycleDatabaseRelayAssociation.UNREGISTERED, event.association)
            assertFalse(event.fixtureClosing)
            assertFalse(offender.actorsEnded())
            assertEquals(0, client.operations.get())
            assertEquals(0, upstream.operations.get())
            val before = fixture.relay.diagnostic(1)
            assertTrue(before.contains("accepted=7 retained=7"))
            assertTrue(before.contains("accept_event=7/UNREGISTERED/ACCEPTOR/SESSION_RETAIN/STATE_CHECK/false"))
            assertTrue(before.contains("event_overflow=true events_omitted=0"))
            assertThrows(IllegalStateException::class.java) { fixture.relay.progress() }
            assertThrows(IllegalStateException::class.java) { fixture.relay.close() }
            assertTrue(offender.actorsEnded())
            assertEquals(1, client.closes.get())
            assertEquals(1, upstream.closes.get())
            assertEquals(0, upstream.connects.get())
            assertEquals(null, offender.state.clientEnd.get())
            offender.start()
            assertTrue(offender.actorsEnded())
            assertSame(event, acceptorFailure(fixture.relay))
        }
    }

    private fun acceptorFailure(relay: PgLifecycleDatabaseRelay): PgLifecycleDatabaseRelayFailure? =
        (lifecycleField(relay, "failed") as AtomicReference<*>).get() as PgLifecycleDatabaseRelayFailure?
}
