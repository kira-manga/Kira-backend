package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.IOException
import java.sql.SQLException

/** Actual capture wiring on an owned worker, with explicitly MODEL Driver and scope/settlement faults. */
internal class PersistencePgOpeningEvidenceTest {
    @ParameterizedTest
    @EnumSource(PersistenceFailureType::class)
    fun `MODEL direct Driver throws retain their safe type through fallback and preserve interruption or Error behavior`(type: PersistenceFailureType) {
        val failure = openingEvidenceFailure(type)
        OpeningEvidenceModelFixture { throw failure }.use { fixture ->
            val result = fixture.invoke()
            if (failure is Error) {
                assertSame(failure, result.exceptionOrNull())
                assertTrue(fixture.entry.openingFacts.fatal.get())
            } else {
                assertEquals(
                    if (failure is InterruptedException) PersistencePhysicalOpening.INTERRUPTED else PersistencePhysicalOpening.FAILED,
                    result.getOrThrow(),
                )
            }
            assertEquals(failure is InterruptedException, fixture.interruptedAtExit.get())
            assertEquals(1, fixture.driver.calls.get())
            assertTrue(fixture.entry.openingFacts.driverEntered.get() && fixture.entry.openingFacts.driverEnded.get())
            assertTrue(fixture.entry.openingFacts.scopeCallEnded.get())
            assertNull(fixture.entry.raw.get())
            assertEquals(PersistenceOpeningFailureSite.DRIVER_CONNECT, fixture.entry.openingFacts.failure()?.site)
            assertEquals(type, fixture.entry.openingFacts.failure()?.type)
            when (failure) {
                is PhysicalHostileFailure -> assertEquals(0, failure.reads.get())
                is PhysicalHostileError -> assertEquals(0, failure.reads.get())
                is OpeningHostileSqlFailure -> assertEquals(0, failure.reads.get())
            }
        }
    }

    @Test
    fun `MODEL nullable Driver return is recorded as no raw not a thrown failure`() = OpeningEvidenceModelFixture { null }.use { fixture ->
        assertEquals(PersistencePhysicalOpening.NO_RAW_RETURN, fixture.invoke().getOrThrow())
        assertNull(fixture.entry.raw.get())
        assertNull(fixture.entry.openingFacts.failure())
        assertTrue(fixture.entry.openingFacts.driverEnded.get())
        assertTrue(fixture.entry.scopeEnded)
    }

    @Test
    fun `MODEL literal normal return retains the raw before fallible settlement and fallback is named separately`() {
        val candidate = PhysicalTestConnection()
        OpeningEvidenceModelFixture { candidate.raw }.use { fixture ->
            fixture.lock.settlementFailure = IllegalStateException()
            assertEquals(PersistencePhysicalOpening.FAILED, fixture.invoke().getOrThrow())
            assertSame(candidate.raw, fixture.entry.raw.get())
            assertEquals(0, candidate.calls.get())
            assertEquals(PersistenceOpeningFailureSite.OPENING_FALLBACK, fixture.entry.openingFacts.failure()?.site)
            assertEquals(PersistenceFailureType.STATE_CHECK, fixture.entry.openingFacts.failure()?.type)
        }
    }

    @Test
    fun `MODEL direct failure evidence survives a later settlement replacement without repairing finally precedence`() {
        OpeningEvidenceModelFixture { throw SQLException() }.use { fixture ->
            fixture.lock.settlementFailure = IllegalStateException()
            assertEquals(PersistencePhysicalOpening.FAILED, fixture.invoke().getOrThrow())
            assertEquals(PersistenceOpeningFailureSite.DRIVER_CONNECT, fixture.entry.openingFacts.failure()?.site)
            assertEquals(PersistenceFailureType.SQL, fixture.entry.openingFacts.failure()?.type)
        }
    }

    @Test
    fun `MODEL scope leave throwing after a normal return is named as scope not Driver failure`() {
        OpeningEvidenceModelFixture(scoped = true) { null }.use { fixture ->
            fixture.lock.scopeFailure = IOException()
            assertEquals(PersistencePhysicalOpening.FAILED, fixture.invoke().getOrThrow())
            assertFalse(fixture.entry.scopeEnded)
            assertTrue(fixture.entry.openingFacts.scopeCallEnded.get())
            assertEquals(PersistenceOpeningFailureSite.SCOPE_LEAVE, fixture.entry.openingFacts.failure()?.site)
            assertEquals(PersistenceFailureType.IO, fixture.entry.openingFacts.failure()?.type)
        }
    }

    @Test
    fun `MODEL scope Error still propagates but cannot replace an earlier direct diagnostic`() {
        val later = PhysicalHostileError()
        OpeningEvidenceModelFixture(scoped = true) { throw SQLException() }.use { fixture ->
            fixture.lock.scopeFailure = later
            fixture.lock.armScopeAfter = 2 // Direct throw settles once in finally, then once in failOpening.
            assertSame(later, fixture.invoke().exceptionOrNull())
            assertEquals(PersistenceOpeningFailureSite.DRIVER_CONNECT, fixture.entry.openingFacts.failure()?.site)
            assertEquals(PersistenceFailureType.SQL, fixture.entry.openingFacts.failure()?.type)
            assertTrue(fixture.entry.openingFacts.scopeCallEnded.get())
            assertFalse(fixture.entry.scopeEnded)
            assertEquals(0, later.reads.get())
        }
    }

    @Test
    fun `MODEL interrupt flag is preserved while type evidence still identifies the directly thrown SQL family`() {
        OpeningEvidenceModelFixture {
            Thread.currentThread().interrupt()
            throw SQLException()
        }.use { fixture ->
            assertEquals(PersistencePhysicalOpening.INTERRUPTED, fixture.invoke().getOrThrow())
            assertTrue(fixture.interruptedAtExit.get())
            assertEquals(PersistenceFailureType.SQL, fixture.entry.openingFacts.failure()?.type)
        }
    }
}
