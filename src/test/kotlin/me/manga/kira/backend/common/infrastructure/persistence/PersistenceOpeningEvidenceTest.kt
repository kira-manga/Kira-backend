package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicInteger

/** MODEL immutable value/cell controls, not invocation-wiring, native construction or DB acceptance evidence. */
internal class PersistenceOpeningEvidenceTest {
    @ParameterizedTest
    @EnumSource(PersistenceFailureType::class)
    fun `MODEL closed type tests select specific families before broad superclasses`(type: PersistenceFailureType) {
        PersistenceOpeningEvidence.prepareRuntime()
        val facts = PersistenceOpeningFacts()
        assertTrue(facts.recordFailure(PersistenceOpeningFailureSite.DRIVER_CONNECT, openingEvidenceFailure(type)))
        val first = requireNotNull(facts.failure())
        assertEquals(PersistenceOpeningFailureSite.DRIVER_CONNECT, first.site)
        assertEquals(type, first.type)
        assertFalse(facts.recordFailure(PersistenceOpeningFailureSite.OPENING_FALLBACK, IllegalStateException()))
        assertFalse(facts.recordFailure(PersistenceOpeningFailureSite.SCOPE_LEAVE, IOException()))
        assertSame(first, facts.failure())
        assertFalse(facts.driverEntered.get() || facts.driverEnded.get() || facts.fatal.get())
        assertNull(facts.outcome.get())
    }

    @ParameterizedTest
    @EnumSource(PersistenceTransportRole::class)
    fun `MODEL refusal retained and thrown construction observations are first-per-role and never overwrite an opening`(role: PersistenceTransportRole) {
        PersistenceOpeningEvidence.prepareRuntime()
        val other = if (role === PersistenceTransportRole.PRIMARY) PersistenceTransportRole.AUX_CANCEL else PersistenceTransportRole.PRIMARY
        for (refusal in PersistenceTransportRefusal.entries) {
            for (site in PersistenceConstructionSite.entries) {
                val facts = PersistenceOpeningFacts()
                assertTrue(facts.recordConstructionRefusal(role, site, refusal))
                val first = requireNotNull(facts.construction(role))
                assertEquals(site, first.site)
                assertEquals(refusal, first.refusal)
                assertEquals(PersistenceConstructionDisposition.REFUSED, first.disposition)
                assertNull(first.type)
                assertTrue(facts.recordConstructionRetained(other))
                assertFalse(facts.recordConstructionThrow(role, SocketException()))
                assertFalse(facts.recordConstructionRetained(role))
                assertSame(first, facts.construction(role))
                val retained = requireNotNull(facts.construction(other))
                assertEquals(PersistenceConstructionDisposition.RETAINED, retained.disposition)
                assertNull(retained.type)
                assertNull(retained.refusal)
                assertTrue(facts.recordFailure(PersistenceOpeningFailureSite.DRIVER_CONNECT, SQLException()))
                assertSame(first, facts.construction(role))
                assertSame(retained, facts.construction(other))
                assertEquals(PersistenceFailureType.SQL, facts.failure()?.type)
            }
        }
    }

    @Test
    fun `MODEL competing failure writers cannot combine one site with the other type`() = OwnedCallerTestScope().use { scope ->
        PersistenceOpeningEvidence.prepareRuntime()
        val facts = PersistenceOpeningFacts()
        val release = scope.gate()
        val first = scope.launch {
            release.hold()
            facts.recordFailure(PersistenceOpeningFailureSite.DRIVER_CONNECT, SQLException())
        }
        val second = scope.launch {
            release.hold()
            facts.recordFailure(PersistenceOpeningFailureSite.SCOPE_LEAVE, SocketException())
        }
        release.awaitEntered()
        release.release()
        assertTrue(first.value() xor second.value())
        val winner = requireNotNull(facts.failure())
        val expected = if (winner.site === PersistenceOpeningFailureSite.DRIVER_CONNECT) PersistenceFailureType.SQL else PersistenceFailureType.SOCKET
        assertEquals(expected, winner.type)
        assertFalse(facts.recordFailure(PersistenceOpeningFailureSite.OPENING_FALLBACK, IllegalStateException()))
        assertSame(winner, facts.failure())
    }

    @Test
    fun `MODEL absent fresh evidence is not inherited and hostile failures are never inspected`() {
        PersistenceOpeningEvidence.prepareRuntime()
        val failures = listOf(PhysicalHostileFailure(), PhysicalHostileError(), OpeningHostileSqlFailure())
        failures.forEach { failure ->
            val facts = PersistenceOpeningFacts()
            assertTrue(facts.recordFailure(PersistenceOpeningFailureSite.DRIVER_CONNECT, failure))
            assertTrue(facts.recordConstructionThrow(PersistenceTransportRole.PRIMARY, failure))
            val fields = PgLifecycleDatabaseDiagnostics.openingEvidenceFields(facts)
            assertTrue(fields.contains("CONSTRUCTION_SPAN/THREW/"))
            assertEquals(facts.failure()?.type, facts.construction(PersistenceTransportRole.PRIMARY)?.type)
        }
        assertEquals(0, (failures[0] as PhysicalHostileFailure).reads.get())
        assertEquals(0, (failures[1] as PhysicalHostileError).reads.get())
        assertEquals(0, (failures[2] as OpeningHostileSqlFailure).reads.get())
        val fresh = PersistenceOpeningFacts()
        assertNull(fresh.failure())
        assertNull(fresh.construction(PersistenceTransportRole.PRIMARY))
        assertNull(fresh.construction(PersistenceTransportRole.AUX_CANCEL))
    }
}

internal fun openingEvidenceFailure(type: PersistenceFailureType): Throwable = when (type) {
    PersistenceFailureType.SOCKET_TIMEOUT -> SocketTimeoutException()
    PersistenceFailureType.CONNECT -> ConnectException()
    PersistenceFailureType.SOCKET -> SocketException()
    PersistenceFailureType.EOF -> EOFException()
    PersistenceFailureType.IO -> IOException()
    PersistenceFailureType.SQL -> OpeningHostileSqlFailure()
    PersistenceFailureType.INTERRUPTED -> InterruptedException()
    PersistenceFailureType.BOUNDARY -> PersistenceBoundaryException(PersistenceBoundaryFailureCode.JDBC_CONFIGURATION_FAILED)
    PersistenceFailureType.STATE_CHECK -> IllegalStateException()
    PersistenceFailureType.ERROR -> PhysicalHostileError()
    PersistenceFailureType.OTHER -> PhysicalHostileFailure()
}

internal class OpeningHostileSqlFailure : SQLException() {
    val reads = AtomicInteger()
    override val message: String
        get() = unread()
    override val cause: Throwable?
        get() = unread()

    override fun getSQLState(): String = unread()

    override fun getErrorCode(): Int = unread()

    override fun getNextException(): SQLException? = unread()

    override fun getStackTrace(): Array<StackTraceElement> = unread()

    override fun toString(): String = unread()

    private fun unread(): Nothing {
        reads.incrementAndGet()
        error("Synthetic SQL failure accessor must not be read.")
    }
}
