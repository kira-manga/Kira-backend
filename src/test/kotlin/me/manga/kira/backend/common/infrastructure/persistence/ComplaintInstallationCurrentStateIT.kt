package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintInstallationCurrentStateAssessment
import me.manga.kira.backend.complaint.domain.ComplaintInstallationDesiredSettings
import me.manga.kira.backend.complaint.domain.ComplaintInstallationRunState
import me.manga.kira.backend.complaint.infrastructure.admission.JdbcInstallationCurrentStateReader
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintInstallationCurrentStatePhaseExecutor
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

/** Five focused real-PG groups. Synthetic matching rows still require provenance; no HTTP, TEST activation or installation grant. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class ComplaintInstallationCurrentStateIT {
    private val database = lazy { PgLifecycleDatabaseFixture(ComplaintInstallationCurrentStateIT::class.java).also { it.start() } }

    @AfterAll
    fun closeDatabase() {
        if (database.isInitialized()) database.value.close()
    }

    @Test
    fun `disabled missing control and nullable closed seeds stay diagnostic without writes`() = withFixture { f ->
        val before = f.state()
        val noDataSource = JdbcInstallationCurrentStateReader(JdbcTemplate())
        assertEquals(
            ComplaintInstallationCurrentStateAssessment.DISABLED,
            ComplaintInstallationCurrentStatePhaseExecutor(f.ordinary.ownership, noDataSource)
                .assess(ComplaintInstallationDesiredSettings.Disabled, f.testScope),
        )
        f.assertReleased()
        assertEquals(before, f.state())

        val desired = f.desired()
        f.seedClosedControl()
        f.assertAssessment(ComplaintInstallationCurrentStateAssessment.CURRENT_STATE_UNAVAILABLE, desired)
        f.assertAssessment(ComplaintInstallationCurrentStateAssessment.CURRENT_STATE_UNAVAILABLE, desired, f.testScope)
        f.deleteControl(ComplaintDataScope.LIVE)
        f.assertAssessment(ComplaintInstallationCurrentStateAssessment.CURRENT_STATE_UNAVAILABLE, desired)
        f.assertAssessment(ComplaintInstallationCurrentStateAssessment.CURRENT_STATE_UNAVAILABLE, f.desired(f.testScope), f.testScope)

        f.seedControl(desired)
        assertEquals(1, f.observer.update("UPDATE complaint_journal_control SET desired_configuration_hash = NULL WHERE data_scope_id = ?", desired.scope.id))
        f.assertAssessment(ComplaintInstallationCurrentStateAssessment.CURRENT_STATE_UNAVAILABLE, desired)
        f.seedControl(desired)
        assertEquals(
            1,
            f.observer.update(
                "UPDATE complaint_journal_control SET database_identity = NULL, restore_identity = NULL WHERE data_scope_id = ?",
                desired.scope.id,
            ),
        )
        f.assertAssessment(ComplaintInstallationCurrentStateAssessment.CURRENT_STATE_UNAVAILABLE, desired)
    }

    @Test
    fun `independent desired bindings mismatch before requested run classification and matching stays untrusted`() = withFixture { f ->
        val desired = f.desired()
        f.seedControl(desired)
        val mismatches = listOf(
            f.desired(generation = desired.desiredGeneration + 1),
            f.desired(hash = ByteArray(32) { 41 }),
            f.desired(database = UUID.randomUUID()),
            f.desired(restore = UUID.randomUUID()),
        )
        for (changed in mismatches) {
            f.assertAssessment(ComplaintInstallationCurrentStateAssessment.DESIRED_BINDING_MISMATCH, changed)
            f.assertAssessment(ComplaintInstallationCurrentStateAssessment.DESIRED_BINDING_MISMATCH, changed, f.testScope)
        }
        f.assertAssessment(ComplaintInstallationCurrentStateAssessment.PROVENANCE_REQUIRED, desired)
    }

    @Test
    fun `exact requested test run observations preserve absent active retired and binding precedence`() = withFixture { f ->
        val live = f.desired()
        val test = f.desired(f.testScope)
        f.seedControl(live)
        f.seedControl(test)
        f.assertAssessment(ComplaintInstallationCurrentStateAssessment.INSTALLATION_SCOPE_RETIRED, live, f.testScope)
        f.assertAssessment(ComplaintInstallationCurrentStateAssessment.INSTALLATION_SCOPE_RETIRED, test, f.testScope)

        f.seedRun(f.otherScope) // A genuine foreign ACTIVE row cannot stand in for the exact requested absence.
        f.assertAssessment(ComplaintInstallationCurrentStateAssessment.INSTALLATION_SCOPE_RETIRED, live, f.testScope)
        f.assertAssessment(ComplaintInstallationCurrentStateAssessment.INSTALLATION_SCOPE_RETIRED, test, f.testScope)
        f.deleteRun(f.otherScope)
        f.seedRun()
        f.assertAssessment(ComplaintInstallationCurrentStateAssessment.INSTALLATION_SCOPE_MISMATCH, live, f.testScope)
        f.assertAssessment(ComplaintInstallationCurrentStateAssessment.PROVENANCE_REQUIRED, test, f.testScope)
        f.assertAssessment(ComplaintInstallationCurrentStateAssessment.INSTALLATION_SCOPE_MISMATCH, test, ComplaintDataScope.LIVE)

        assertEquals(
            1,
            f.observer.update("UPDATE complaint_test_runs SET configuration_hash = ? WHERE data_scope_id = ?", ByteArray(32) { 43 }, f.testScope.id),
        )
        f.assertAssessment(ComplaintInstallationCurrentStateAssessment.INSTALLATION_SCOPE_MISMATCH, live, f.testScope)
        f.assertAssessment(ComplaintInstallationCurrentStateAssessment.DESIRED_BINDING_MISMATCH, test, f.testScope)
        for (state in listOf(ComplaintInstallationRunState.SEALED, ComplaintInstallationRunState.PURGING, ComplaintInstallationRunState.PURGED)) {
            f.retireRun(state)
            f.assertAssessment(ComplaintInstallationCurrentStateAssessment.INSTALLATION_SCOPE_RETIRED, live, f.testScope)
            f.assertAssessment(ComplaintInstallationCurrentStateAssessment.INSTALLATION_SCOPE_RETIRED, test, f.testScope)
        }
        f.assertAssessment(
            ComplaintInstallationCurrentStateAssessment.DESIRED_BINDING_MISMATCH,
            f.desired(f.testScope, generation = test.desiredGeneration + 1),
            f.testScope,
        )
    }

    @Test
    fun `only the exact retained read may release a result after known commit and actual cleanup`() = withFixture { f ->
        val desired = f.desired()
        f.seedControl(desired)
        val before = f.state()
        assertEquals(PersistencePhaseFailureCode.ENTRY_REFUSED, assertThrows<PersistencePhaseException> { f.reader.read(desired, desired.scope) }.code)

        f.withPhase(enter = f.ordinary.ownership::enterComplaintInstallationSessionPreflight) { phase ->
            assertThrows<PersistencePhaseException> { f.reader.read(desired, desired.scope) }
            assertThrows<PersistencePhaseException> { phase.commit() }
        }
        f.withPhase { phase ->
            val foreign = JdbcInstallationCurrentStateReader(f.ordinary.foreignTemplate())
            assertThrows<PersistencePhaseException> { foreign.read(desired, desired.scope) }
            assertThrows<PersistencePhaseException> { phase.commit() }
        }
        f.withPhase { phase ->
            phase.installationCurrentState.requireOperation(f.ordinary.jdbc)
            assertFalse(phase.installationCurrentState.completed())
            assertThrows<PersistencePhaseException> { phase.commit() } // No enum or manually issued check completes the retained operation.
        }
        val repeated = f.withPhase { phase ->
            val read = f.reader.read(desired, desired.scope)
            assertThrows<PersistencePhaseException> { f.reader.read(desired, desired.scope) }
            assertThrows<PersistencePhaseException> { phase.commit() }
            read
        }
        assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, assertThrows<PersistencePhaseException> { repeated.assessment }.databaseOutcome)

        val rolledBack = f.withPhase { f.reader.read(desired, desired.scope) }
        val rollbackFailure = assertThrows<PersistencePhaseException> { rolledBack.assessment }
        assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, rollbackFailure.databaseOutcome)
        assertTrue(rollbackFailure.cleanupProven)

        val committed = f.withPhase { phase ->
            val read = f.reader.read(desired, desired.scope)
            assertTrue(phase.installationCurrentState.completed())
            val early = assertThrows<PersistencePhaseException> { read.assessment }
            assertEquals(PersistenceDatabaseOutcome.NONE, early.databaseOutcome)
            assertFalse(early.cleanupProven)
            phase.commit()
            val unreleased = assertThrows<PersistencePhaseException> { read.assessment }
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, unreleased.databaseOutcome)
            assertFalse(unreleased.cleanupProven)
            read
        }
        val released = committed.assessment
        assertEquals(ComplaintInstallationCurrentStateAssessment.PROVENANCE_REQUIRED, released)
        assertEquals(released, committed.assessment) // Repeatable diagnostic equality is not a one-use authority token.
        f.withPhase { phase ->
            assertThrows<PersistencePhaseException> { phase.installationCurrentState.requireCommitted(committed) }
            assertThrows<PersistencePhaseException> { phase.commit() }
        }
        OwnedCallerTestScope().use { callers ->
            val foreign = callers.launch { assertThrows<PersistencePhaseException> { committed.assessment } }.value()
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, foreign.databaseOutcome)
            assertTrue(foreign.cleanupProven)
        }
        assertThrows<PersistencePhaseException> { committed.assessment } // Wrong-caller use is sticky, not an alternate result-release port.
        f.assertReleased()
        assertEquals(before, f.state())
    }

    @Test
    fun `read only exact snapshots do not lock rows or turn changing gates into authority`() = withFixture { f ->
        val desired = f.desired(f.testScope)
        f.seedControl(desired)
        f.seedRun()
        val retained = f.withPhase { phase ->
            assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            val read = f.reader.read(desired, desired.scope)
            assertEquals("on", f.ordinary.jdbc.queryForObject("SHOW transaction_read_only", String::class.java))
            OwnedCallerTestScope().use { observers ->
                observers.launch { f.mutateUnlockedRows() }.value()
            }
            phase.commit()
            read
        }
        assertEquals(ComplaintInstallationCurrentStateAssessment.PROVENANCE_REQUIRED, retained.assessment)
        f.assertAssessment(ComplaintInstallationCurrentStateAssessment.DESIRED_BINDING_MISMATCH, desired, desired.scope)
        f.seedControl(desired) // Only desired binding is restored; the independently changed SEALED run remains retired.
        f.assertAssessment(ComplaintInstallationCurrentStateAssessment.INSTALLATION_SCOPE_RETIRED, desired, desired.scope)
        f.seedRun()
        val flags = listOf(Triple(true, true, true), Triple(false, true, false), Triple(true, false, true), Triple(false, false, false))
        for ((maintenance, creation, scan) in flags) {
            assertEquals(
                1,
                f.observer.update(
                    "UPDATE complaint_journal_control SET maintenance_closed = ?, creation_closed = ?, scan_requested = ? WHERE data_scope_id = ?",
                    maintenance,
                    creation,
                    scan,
                    desired.scope.id,
                ),
            )
            f.assertAssessment(ComplaintInstallationCurrentStateAssessment.PROVENANCE_REQUIRED, desired, desired.scope)
        }
    }

    private fun withFixture(work: (ComplaintInstallationCurrentStateFixture) -> Unit) {
        withOrdinarySourceGrantCleanup(database.value, maximumPoolSize = 2) { ordinary ->
            ComplaintInstallationCurrentStateFixture(ordinary).use(work)
        }
    }
}
