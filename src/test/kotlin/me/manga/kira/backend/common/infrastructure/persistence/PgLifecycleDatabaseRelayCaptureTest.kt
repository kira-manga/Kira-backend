package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.ConnectException
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** MODEL I/O, actual relay/session capture and validation paths. No listener, driver, SQL or PostgreSQL is started. */
internal class PgLifecycleDatabaseRelayCaptureTest {
    @ParameterizedTest
    @EnumSource(value = PgLifecycleDatabaseRelayStage::class, names = ["CLIENT_CONFIGURE", "STARTUP_READ"])
    fun `MODEL early coordinator failure retains accepted identity without a registered association`(stage: PgLifecycleDatabaseRelayStage) {
        PgLifecycleDatabaseRelayEvidenceFixture().use { fixture ->
            val thrown = PhysicalHostileFailure()
            val client = PgLifecycleDatabaseRelayModelSocket(
                input = relayEvidenceInput { throw thrown },
                configure = { if (stage === PgLifecycleDatabaseRelayStage.CLIENT_CONFIGURE) throw thrown },
            )
            val upstream = PgLifecycleDatabaseRelayModelSocket()
            val session = fixture.accept(client, upstream)
            val event = requireNotNull(fixture.ended(session))
            assertEquals(1, event.acceptedIndex)
            assertEquals(PgLifecycleDatabaseRelayAssociation.UNREGISTERED, event.association)
            assertEquals(stage, event.stage)
            assertEquals(PgLifecycleDatabaseRelayFailureType.OTHER, event.type)
            assertFalse(event.fixtureClosing)
            assertEquals(-1, session.state.ordinal)
            assertEquals(0, upstream.connects.get())
            assertEquals(0, thrown.reads.get())
            assertThrows(IllegalStateException::class.java) { fixture.relay.progress() }
            assertThrows(IllegalStateException::class.java) { fixture.relay.close() }
            assertSame(event, session.state.failure.get())
        }
    }

    @ParameterizedTest
    @EnumSource(value = PgLifecycleDatabaseRelayStage::class, names = ["UPSTREAM_CONFIGURE", "UPSTREAM_CONNECT", "STARTUP_FORWARD"])
    fun `MODEL validated primary precedes the independent upstream capture spans`(stage: PgLifecycleDatabaseRelayStage) {
        PgLifecycleDatabaseRelayEvidenceFixture().use { fixture ->
            val thrown = ConnectException()
            val output = object : OutputStream() {
                override fun write(value: Int) = throw thrown
            }
            val upstream = PgLifecycleDatabaseRelayModelSocket(
                output = output,
                configure = { if (stage === PgLifecycleDatabaseRelayStage.UPSTREAM_CONFIGURE) throw thrown },
                connect = { if (stage === PgLifecycleDatabaseRelayStage.UPSTREAM_CONNECT) throw thrown },
            )
            val session = fixture.accept(upstream = upstream)
            val event = requireNotNull(fixture.ended(session))
            assertEquals(PgLifecycleDatabaseRelayAssociation.PRIMARY_0, session.state.association)
            assertEquals(PgLifecycleDatabaseRelayAssociation.PRIMARY_0, event.association)
            assertEquals(stage, event.stage)
            assertEquals(PgLifecycleDatabaseRelayFailureType.CONNECT, event.type)
            assertFalse(event.fixtureClosing)
            assertEquals(if (stage === PgLifecycleDatabaseRelayStage.UPSTREAM_CONFIGURE) 0 else 1, upstream.connects.get())
        }
    }

    @Test
    fun `MODEL primary bind rejection after tentative ordinal does not publish PRIMARY`() {
        PgLifecycleDatabaseRelayEvidenceFixture().use { fixture ->
            val session = fixture.accept { accepted, _ -> accepted.state.readyFault.bind(0) }
            val event = requireNotNull(fixture.ended(session))
            assertEquals(0, session.state.ordinal)
            assertEquals(PgLifecycleDatabaseRelayAssociation.UNREGISTERED, session.state.association)
            assertEquals(PgLifecycleDatabaseRelayAssociation.UNREGISTERED, event.association)
            assertEquals(PgLifecycleDatabaseRelayStage.REGISTER, event.stage)
            assertEquals(PgLifecycleDatabaseRelayFailureType.STATE_CHECK, event.type)
            assertTrue(fixture.relay.diagnostic(0).contains("primary_registered=false"))
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1])
    fun `MODEL valid PRIMARY and AUX remain distinct from accepted index and rejected third AUX flags`(ordinal: Int) {
        PgLifecycleDatabaseRelayEvidenceFixture().use { fixture ->
            val unregistered = fixture.accept(PgLifecycleDatabaseRelayModelSocket(configure = { throw EOFException() }))
            assertEquals(PgLifecycleDatabaseRelayAssociation.UNREGISTERED, requireNotNull(fixture.ended(unregistered)).association)
            repeat(ordinal + 1) { position ->
                val primary = fixture.accept()
                val association = if (position == 0) PgLifecycleDatabaseRelayAssociation.PRIMARY_0 else PgLifecycleDatabaseRelayAssociation.PRIMARY_1
                assertEquals(association, requireNotNull(fixture.ended(primary)).association)
                assertEquals(position + 2, primary.state.acceptedIndex)
                assertEquals(position, primary.state.ordinal)
                primary.state.backendPid.set(29 + position) // MODEL registration key only, never a server/backend-key witness.
            }
            repeat(2) {
                val input = relayEvidenceInput(PgLifecycleDatabaseRelayEvidenceFixture.cancel(29 + ordinal))
                val auxiliary = fixture.accept(PgLifecycleDatabaseRelayModelSocket(input))
                val event = requireNotNull(fixture.ended(auxiliary))
                val association = if (ordinal == 0) PgLifecycleDatabaseRelayAssociation.AUX_0 else PgLifecycleDatabaseRelayAssociation.AUX_1
                assertEquals(association, event.association)
                assertEquals(PgLifecycleDatabaseRelayStage.UPSTREAM_CONNECT, event.stage)
            }
            val input = relayEvidenceInput(PgLifecycleDatabaseRelayEvidenceFixture.cancel(29 + ordinal))
            val rejected = fixture.accept(PgLifecycleDatabaseRelayModelSocket(input))
            val event = requireNotNull(fixture.ended(rejected))
            assertEquals(ordinal + 5, event.acceptedIndex)
            assertEquals(ordinal, rejected.state.ordinal)
            assertTrue(rejected.state.auxiliary)
            assertEquals(PgLifecycleDatabaseRelayAssociation.UNREGISTERED, rejected.state.association)
            assertEquals(PgLifecycleDatabaseRelayAssociation.UNREGISTERED, event.association)
            assertEquals(PgLifecycleDatabaseRelayStage.REGISTER, event.stage)
            assertEquals(PgLifecycleDatabaseRelayFailureType.STATE_CHECK, event.type)
            // Selecting the unrelated ordinal cannot hide an unregistered or AUX failure.
            val diagnostic = fixture.relay.diagnostic(1 - ordinal)
            assertTrue(diagnostic.contains("1/UNREGISTERED/COORDINATOR/CLIENT_CONFIGURE/EOF/false"))
            assertTrue(diagnostic.contains("${ordinal + 3}/AUX($ordinal)/COORDINATOR/UPSTREAM_CONNECT/CONNECT/false"))
            assertTrue(diagnostic.contains("${ordinal + 5}/UNREGISTERED/COORDINATOR/REGISTER/STATE_CHECK/false"))
        }
    }

    @Test
    fun `MODEL later successful registration and cleanup cannot relabel the already winning failure`() {
        PgLifecycleDatabaseRelayEvidenceFixture().use { fixture ->
            var registration: ((PgLifecycleDatabaseRelaySession, ByteArray) -> Unit)? = null
            val session = fixture.accept(PgLifecycleDatabaseRelayModelSocket(relayEvidenceInput { throw EOFException() })) { _, callback ->
                registration = callback
            }
            val event = requireNotNull(fixture.ended(session))
            requireNotNull(registration)(session, PgLifecycleDatabaseRelayEvidenceFixture.startup())
            assertEquals(PgLifecycleDatabaseRelayAssociation.PRIMARY_0, session.state.association)
            session.state.cleanupRelease()
            assertSame(event, session.state.failure.get())
            assertEquals(PgLifecycleDatabaseRelayAssociation.UNREGISTERED, event.association)
            assertFalse(event.fixtureClosing)
            assertTrue(fixture.relay.diagnostic(0).contains("1/UNREGISTERED/COORDINATOR/STARTUP_READ/EOF/false"))
        }
    }

    @ParameterizedTest
    @EnumSource(value = PgLifecycleDatabaseRelayStage::class, names = ["CLIENT_PUMP", "SERVER_PUMP", "ACTOR_JOIN"])
    fun `MODEL simultaneous pump and coordinator spans keep one coherent winning cell`(first: PgLifecycleDatabaseRelayStage) {
        PgLifecycleDatabaseRelayEvidenceFixture().use { fixture ->
            val entered = CountDownLatch(2)
            val clientRelease = CountDownLatch(1).also(fixture.releases::add)
            val serverRelease = CountDownLatch(1).also(fixture.releases::add)
            val clientFailure = PhysicalHostileFailure()
            val client = PgLifecycleDatabaseRelayModelSocket(
                relayEvidenceInput(PgLifecycleDatabaseRelayEvidenceFixture.startup()) {
                    entered.countDown()
                    check(clientRelease.await(5, TimeUnit.SECONDS))
                    throw clientFailure
                },
            )
            val upstream = PgLifecycleDatabaseRelayModelSocket(
                relayEvidenceInput {
                    entered.countDown()
                    check(serverRelease.await(5, TimeUnit.SECONDS))
                    throw IOException()
                },
            )
            val session = fixture.accept(client, upstream)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val coordinator = lifecycleField(session, "coordinator") as Thread
            awaitLifecycleFact(5_000) { coordinator.state === Thread.State.TIMED_WAITING }
            when (first) {
                PgLifecycleDatabaseRelayStage.CLIENT_PUMP -> clientRelease.countDown()
                PgLifecycleDatabaseRelayStage.SERVER_PUMP -> serverRelease.countDown()
                PgLifecycleDatabaseRelayStage.ACTOR_JOIN -> coordinator.interrupt()
                else -> error("Unlisted MODEL actor.")
            }
            awaitLifecycleFact(5_000) { session.state.failure.get() != null }
            val event = requireNotNull(session.state.failure.get())
            assertEquals(first, event.stage)
            val expected = when (first) {
                PgLifecycleDatabaseRelayStage.CLIENT_PUMP -> PgLifecycleDatabaseRelayFailureType.OTHER
                PgLifecycleDatabaseRelayStage.SERVER_PUMP -> PgLifecycleDatabaseRelayFailureType.IO
                else -> PgLifecycleDatabaseRelayFailureType.INTERRUPTED
            }
            assertEquals(expected, event.type)
            assertEquals(PgLifecycleDatabaseRelayAssociation.PRIMARY_0, event.association)
            assertEquals(1, event.acceptedIndex)
            assertFalse(event.fixtureClosing)
            clientRelease.countDown()
            serverRelease.countDown()
            assertSame(event, fixture.ended(session))
            assertEquals(first !== PgLifecycleDatabaseRelayStage.ACTOR_JOIN, session.state.completed.get())
            session.state.cleanupRelease()
            assertSame(event, session.state.failure.get())
            assertEquals(0, clientFailure.reads.get())
            assertEquals(null, session.state.clientEnd.get())
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `MODEL late registered AUX cleanup close failure survives fixture closing and closes both identities`(clientFails: Boolean) {
        val closeFailure = IOException()
        PgLifecycleDatabaseRelayEvidenceFixture(expectedCloseFailure = IOException::class.java).use { fixture ->
            val primary = fixture.accept()
            fixture.ended(primary)
            primary.state.backendPid.set(29)
            // Own-project fixture flag only: exercise the existing delayed-predecessor branch on the real coordinator.
            (lifecycleField(fixture.relay, "weakCleanupThrough") as AtomicInteger).set(0)
            val client = PgLifecycleDatabaseRelayModelSocket(
                input = relayEvidenceInput(PgLifecycleDatabaseRelayEvidenceFixture.cancel(29)),
                closing = { if (clientFails) throw closeFailure },
            )
            val upstream = PgLifecycleDatabaseRelayModelSocket(closing = { if (!clientFails) throw closeFailure })
            val session = fixture.accept(client, upstream)
            val event = requireNotNull(fixture.ended(session))
            assertEquals(PgLifecycleDatabaseRelayAssociation.AUX_0, event.association)
            val stage = if (clientFails) PgLifecycleDatabaseRelayStage.CLEANUP_CLIENT_CLOSE else PgLifecycleDatabaseRelayStage.CLEANUP_UPSTREAM_CLOSE
            assertEquals(stage, event.stage)
            assertEquals(PgLifecycleDatabaseRelayFailureType.IO, event.type)
            assertTrue(event.fixtureClosing)
            assertEquals(1, client.closes.get())
            assertEquals(1, upstream.closes.get())
            assertEquals(0, upstream.connects.get())
            assertEquals(null, session.state.clientEnd.get())
            assertSame(closeFailure, assertThrows(IOException::class.java) { fixture.relay.close() })
            assertSame(event, session.state.failure.get())
            assertThrows(IllegalStateException::class.java) { fixture.relay.progress() }
        }
    }

    @Test
    fun `MODEL guarded teardown failure remains filtered without a fabricated client ending`() {
        PgLifecycleDatabaseRelayEvidenceFixture(expectedCloseFailure = null).use { fixture ->
            lateinit var retained: PgLifecycleDatabaseRelaySession
            val client = PgLifecycleDatabaseRelayModelSocket(
                relayEvidenceInput {
                    retained.state.cleanupRelease()
                    throw SocketException()
                },
            )
            val upstream = PgLifecycleDatabaseRelayModelSocket()
            val session = fixture.accept(client, upstream) { accepted, _ -> retained = accepted }
            assertEquals(null, fixture.ended(session))
            assertTrue(session.state.fixtureClosing.get())
            assertEquals(null, session.state.clientEnd.get())
            assertEquals(0, upstream.connects.get())
            assertTrue(fixture.relay.diagnostic(0).contains("session_failure=false"))
        }
    }
}
