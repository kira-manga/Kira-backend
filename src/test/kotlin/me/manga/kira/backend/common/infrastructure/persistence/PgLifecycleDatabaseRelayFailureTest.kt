package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicInteger

internal class PgLifecycleDatabaseRelayFailureTest {
    @Test
    fun `MODEL closed family order and immutable record never inspect hostile Throwable getters`() {
        val sql = HostileSql()
        val runtime = PhysicalHostileFailure()
        val fatal = PhysicalHostileError()
        val cases = listOf(
            SocketTimeoutException() to PgLifecycleDatabaseRelayFailureType.SOCKET_TIMEOUT,
            ConnectException() to PgLifecycleDatabaseRelayFailureType.CONNECT,
            SocketException() to PgLifecycleDatabaseRelayFailureType.SOCKET,
            EOFException() to PgLifecycleDatabaseRelayFailureType.EOF,
            IOException() to PgLifecycleDatabaseRelayFailureType.IO,
            sql to PgLifecycleDatabaseRelayFailureType.SQL,
            InterruptedException() to PgLifecycleDatabaseRelayFailureType.INTERRUPTED,
            PersistenceBoundaryException(PersistenceBoundaryFailureCode.TIME_BUDGET_EXHAUSTED) to PgLifecycleDatabaseRelayFailureType.BOUNDARY,
            IllegalStateException() to PgLifecycleDatabaseRelayFailureType.STATE_CHECK,
            fatal to PgLifecycleDatabaseRelayFailureType.ERROR,
            runtime to PgLifecycleDatabaseRelayFailureType.OTHER,
        )
        cases.forEach { (thrown, type) -> assertEquals(type, PgLifecycleDatabaseRelayFailureType.of(thrown)) }
        PgLifecycleDatabaseRelayEvidenceFixture().use { fixture ->
            val session = fixture.accept(PgLifecycleDatabaseRelayModelSocket(configure = { throw sql }))
            val event = requireNotNull(fixture.ended(session))
            assertEquals(PgLifecycleDatabaseRelayFailureType.SQL, event.type)
            val diagnostic = fixture.relay.diagnostic(0)
            assertTrue(diagnostic.contains("1/UNREGISTERED/COORDINATOR/CLIENT_CONFIGURE/SQL/false"))
            assertFalse(diagnostic.contains("HostileSql"))
            session.state.captureFailure(PgLifecycleDatabaseRelayStage.SERVER_PUMP, fatal)
            assertSame(event, session.state.failure.get())
        }
        assertEquals(0, sql.reads.get())
        assertEquals(0, runtime.reads.get())
        assertEquals(0, fatal.reads.get())
    }

    @Test
    fun `MODEL seven longest events both primary selections and unavailable index remain bounded read only ASCII`() {
        val case = PgLifecycleDatabaseCase(
            PgLifecycleDatabaseRecipe.QUALIFIED_BINARY_BOX_BINARY_DISABLED,
            1,
            PgLifecycleDatabaseLane.ORDINARY,
            PgLifecycleDatabaseMode.ORIGINAL_MATRIX,
        )
        pgLifecycleDatabaseWorstRelayEvidence(case).use { fixture ->
            val operations = fixture.sockets.map { it.operations.get() }
            val events = fixture.sessions.map { it.state.failure.get() }
            repeat(2) { ordinal ->
                val diagnostic = fixture.relay.diagnostic(ordinal)
                assertTrue(diagnostic.all { it.code in 32..126 })
                assertTrue(diagnostic.length <= 1_335) // Conservative full-Int/Long-width bound; full two-line batch is tested by Diagnostics' owner.
                assertTrue(diagnostic.contains("primary_registered=true"))
                assertTrue(diagnostic.contains("1/UNREGISTERED/COORDINATOR/CLEANUP_UPSTREAM_CLOSE/SOCKET_TIMEOUT/false"))
                assertTrue(diagnostic.contains("7/UNREGISTERED/COORDINATOR/CLEANUP_UPSTREAM_CLOSE/SOCKET_TIMEOUT/false"))
                assertTrue(diagnostic.contains("event_overflow=true events_omitted=0"))
            }
            assertEquals(operations, fixture.sockets.map { it.operations.get() })
            events.forEachIndexed { index, event -> assertSame(event, fixture.sessions[index].state.failure.get()) }
            // Beyond the real loop's seventh-offender bound, a defensive extra MODEL entry must be explicitly omitted.
            assertThrows(IllegalStateException::class.java) { fixture.accept { session, _ -> session.close() } }
            val overflow = fixture.relay.diagnostic(1)
            assertTrue(overflow.contains("event_overflow=true events_omitted=1"))
            assertTrue(overflow.length <= 1_335 && overflow.all { it.code in 32..126 })
        }
        val unknown = PgLifecycleDatabaseRelayState(case)
        unknown.captureFailure(PgLifecycleDatabaseRelayStage.CLEANUP_UPSTREAM_CLOSE, SocketTimeoutException())
        val event = requireNotNull(unknown.failure.get()).diagnostic()
        assertEquals("UNAVAILABLE/UNREGISTERED/COORDINATOR/CLEANUP_UPSTREAM_CLOSE/SOCKET_TIMEOUT/false", event)
        assertEquals(80, event.length)
    }

    private class HostileSql : SQLException() {
        val reads = AtomicInteger()

        private fun forbidden(): Nothing {
            reads.incrementAndGet()
            error("A closed relay family must not inspect Throwable/JDBC data.")
        }

        override val message: String get() = forbidden()
        override val cause: Throwable get() = forbidden()
        override fun getSQLState(): String = forbidden()
        override fun getErrorCode(): Int = forbidden()
        override fun getNextException(): SQLException = forbidden()
        override fun getStackTrace(): Array<StackTraceElement> = forbidden()
        override fun toString(): String = forbidden()
        override fun hashCode(): Int = forbidden()
        override fun equals(other: Any?): Boolean = forbidden()
    }
}
