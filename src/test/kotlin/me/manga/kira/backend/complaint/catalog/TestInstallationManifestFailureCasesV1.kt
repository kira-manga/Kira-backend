package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.infrastructure.terminal.TestInstallationManifestExceptionV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestInstallationManifestSqlV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestInstallationManifestStepV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestInstallationSourceSqlV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinarySealSqlV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunInstallationManifestResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunInstallationManifestV1
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

/** Failure seams change real SQL/commit behavior only, never supply a successful original or outcome. */
internal object TestInstallationManifestFailureCasesV1 {
    fun sourceDrift(tls: VersionBoundPersistenceConnectedFixture, duringPrepare: Boolean) = withInstallationManifestPredecessor(tls) { f, drain, probe ->
        val observed = TestOrdinaryDrainAccountingObservationV1(f)
        var starts = 0
        var selected: PersistencePhaseContext? = null
        var before: TestOrdinaryDrainAccountingStateV1? = null
        var image: Map<String, List<String>>? = null
        probe.before = { call ->
            val secondPass = !duringPrepare && call.step === TestInstallationManifestStepV1.CAPTURE &&
                call.sql == TestInstallationSourceSqlV1.page && call.arguments[1] == null && ++starts == 2
            val chunk = duringPrepare && call.step === TestInstallationManifestStepV1.PREPARE &&
                call.sql == TestInstallationManifestSqlV1.chunkPage && call.arguments[1] == null
            if (selected == null && (secondPass || chunk)) {
                selected = call.phase; before = observed.state(); image = observed.image()
                // Same actual bound transaction. The final RETIRED target is unchanged; raw source
                // metadata differs, and the producer must roll back this hostile mutation too.
                assertEquals(1, JdbcTemplate(checkNotNull(probe.dataSource)).update(
                    "UPDATE app_installations SET version = version + 1 WHERE id = ? AND data_scope_id = ?",
                    f.history.actor.id, f.scope))
            }
        }
        val original = TestRunInstallationManifestV1.begin(drain).also { probe.original = it }
        assertThrows<TestInstallationManifestExceptionV1> { original.prepare() }
        probe.before = {}; probe.assertReleased(requireCommitted = false)
        assertTrue(selected != null, "The real complete source/selected chunk read must reach the chosen mutation seam.")
        assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, checkNotNull(selected).databaseOutcome())
        assertEquals(before, observed.state()); assertEquals(image, observed.image())
        assertTrue(manifestRowsImage(f).values.all { it.isEmpty() })
        assertTrue(probe.calls.none { it.sql in TestInstallationManifestPrepareCasesV1.manifestAtomicStatements && it.step === TestInstallationManifestStepV1.PREPARE })
        if (!duringPrepare) assertTrue(probe.calls.all { it.step === TestInstallationManifestStepV1.CAPTURE })
    }

    fun staleFence(tls: VersionBoundPersistenceConnectedFixture) = withInstallationManifestPredecessor(tls) { f, drain, probe ->
        val observed = TestOrdinaryDrainAccountingObservationV1(f)
        var selected: PersistencePhaseContext? = null
        var before: TestOrdinaryDrainAccountingStateV1? = null
        val foreign = UUID.randomUUID()
        probe.after = { call ->
            if (selected == null && call.step === TestInstallationManifestStepV1.CHUNK && call.sql == TestOrdinarySealSqlV1.lease) {
                selected = call.phase
                TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun afterCommit() {
                        before = observed.state()
                        // A separate raw connection changes actual authority only after CHUNK's commit.
                        checkNotNull(f.observer.dataSource).connection.use { connection ->
                            connection.prepareStatement("UPDATE complaint_journal_control SET lease_owner = ?, lease_token = lease_token + 1 WHERE data_scope_id = ?").use {
                                it.setObject(1, foreign); it.setObject(2, f.scope); assertEquals(1, it.executeUpdate())
                            }
                        }
                    }
                })
            }
        }
        val original = TestRunInstallationManifestV1.begin(drain).also { probe.original = it }
        assertThrows<TestInstallationManifestExceptionV1> { original.prepare() }
        probe.after = {}; probe.assertReleased(requireCommitted = false)
        assertTrue(selected != null && before != null)
        assertEquals(PersistenceDatabaseOutcome.COMMITTED, checkNotNull(selected).databaseOutcome())
        assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, probe.calls.last().phase.databaseOutcome())
        assertEquals(before, observed.state())
        assertTrue(manifestRowsImage(f).values.all { it.isEmpty() })
        assertTrue(probe.calls.any { it.step === TestInstallationManifestStepV1.PREPARE })
        assertTrue(probe.calls.none { it.sql == TestInstallationManifestSqlV1.insertPublication || it.step === TestInstallationManifestStepV1.COMPLETE })
        assertEquals(foreign, f.observer.queryForObject("SELECT lease_owner FROM complaint_journal_control WHERE data_scope_id = ?", UUID::class.java, f.scope))
    }

    fun completion(tls: VersionBoundPersistenceConnectedFixture, cut: TestRegistrationCompletionCut) = withInstallationManifestPredecessor(tls) { f, drain, probe ->
        val observed = TestOrdinaryDrainAccountingObservationV1(f)
        val initial = observed.state()
        val history = observed.previousHistory()
        val seals = observed.runBytes("seal_set_bytes")
        var selected: PersistencePhaseContext? = null
        var before: TestOrdinaryDrainAccountingStateV1? = null
        var image: Map<String, List<String>>? = null
        val resource = Any(); val sentinel = Any(); var bound = false
        probe.after = { call ->
            if (selected == null && call.step === TestInstallationManifestStepV1.PREPARE && call.sql == TestInstallationManifestSqlV1.insertSidecar) {
                selected = call.phase; before = observed.state(); image = observed.image()
                if (cut === TestRegistrationCompletionCut.DEFERRED_COMMIT_UNKNOWN) {
                    val jdbc = JdbcTemplate(checkNotNull(probe.dataSource))
                    jdbc.execute("CREATE TEMP TABLE kira_manifest_prepare_commit_cut (id int UNIQUE DEFERRABLE INITIALLY DEFERRED)")
                    assertEquals(2, jdbc.update("INSERT INTO kira_manifest_prepare_commit_cut VALUES (1), (1)"))
                } else TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun beforeCommit(readOnly: Boolean) {
                        if (cut === TestRegistrationCompletionCut.BEFORE_COMMIT) error("Synthetic manifest PREPARE beforeCommit refusal.")
                    }
                    override fun afterCommit() {
                        if (cut === TestRegistrationCompletionCut.UNRESOLVED_RELEASE) {
                            TransactionSynchronizationManager.bindResource(resource, sentinel); bound = true
                        }
                        if (cut !== TestRegistrationCompletionCut.BEFORE_COMMIT) error("Synthetic manifest PREPARE acknowledgment loss.")
                    }
                })
            }
        }
        val original = TestRunInstallationManifestV1.begin(drain).also { probe.original = it }
        try {
            assertThrows<TestInstallationManifestExceptionV1> { original.prepare() }
            if (cut === TestRegistrationCompletionCut.UNRESOLVED_RELEASE) {
                assertTrue(bound); assertSame(selected, PersistencePhaseOwnership.current())
                assertTrue(checkNotNull(selected).quarantined())
                assertFalse(checkNotNull(selected).testInstallationManifestCleanupProven(original))
                assertThrows<RuntimeException> { TestRunInstallationManifestV1.begin(drain) }
            }
        } finally {
            probe.after = {}
            if (bound) assertSame(sentinel, TransactionSynchronizationManager.unbindResource(resource))
            requireConnectionFree() // Actual quarantine retirement, never rehabilitation of a failed invocation.
        }
        probe.assertReleased(requireCommitted = false)
        assertTrue(selected != null, "The actual transaction must reach the three-row PREPARE cut.")
        val outcome = when (cut) {
            TestRegistrationCompletionCut.BEFORE_COMMIT -> PersistenceDatabaseOutcome.ROLLED_BACK
            TestRegistrationCompletionCut.DEFERRED_COMMIT_UNKNOWN -> PersistenceDatabaseOutcome.UNKNOWN
            else -> PersistenceDatabaseOutcome.COMMITTED
        }
        assertEquals(outcome, checkNotNull(selected).databaseOutcome())
        assertTrue(probe.calls.none { it.step === TestInstallationManifestStepV1.COMPLETE })
        assertEquals(history, observed.previousHistory()); assertArrayEquals(seals, observed.runBytes("seal_set_bytes"))
        val paid = outcome === PersistenceDatabaseOutcome.COMMITTED
        observed.assertTransfer(initial, observed.state(), reserveSpend = if (paid) TestInstallationManifestPrepareCasesV1.MANIFEST_LITERAL else ComplaintCapacityVector.ZERO)
        assertEquals(if (paid) setOf(1) else setOf(0), manifestRowsImage(f).values.map { it.size }.toSet())
        if (!paid) {
            assertEquals(before, observed.state()); assertEquals(image, observed.image(), "All three inserted rows, counters and unused debit roll back atomically.")
        }
        val calls = probe.calls.size
        assertThrows<TestInstallationManifestExceptionV1> { original.prepare() }
        assertEquals(calls, probe.calls.size)
        if (cut === TestRegistrationCompletionCut.BEFORE_COMMIT || cut === TestRegistrationCompletionCut.AFTER_COMMIT) {
            val rows = manifestRowsImage(f)
            val progress = observed.runBytes("permanent_denial_bytes")
            observed.expireLeaseForRetry(); probe.reset()
            val retry = TestRunInstallationManifestV1.begin(drain).also { probe.original = it }
            assertEquals(TestRunInstallationManifestResultV1.ALL_CHUNKS_PREPARED_NO_NETWORK, retry.prepare())
            probe.assertReleased()
            observed.assertTransfer(initial, observed.state(), reserveSpend = TestInstallationManifestPrepareCasesV1.MANIFEST_LITERAL)
            assertArrayEquals(progress, observed.runBytes("permanent_denial_bytes"))
            assertPreparedManifest(f, retry, if (paid) original.leaseToken else retry.leaseToken)
            if (paid) {
                assertEquals(rows, manifestRowsImage(f))
                assertTrue(probe.calls.none { it.sql in TestInstallationManifestPrepareCasesV1.manifestAtomicStatements })
            }
        }
    }

    fun unpaidWinnerRefuses(tls: VersionBoundPersistenceConnectedFixture) = withInstallationManifestPredecessor(tls) { f, drain, probe ->
        val first = TestRunInstallationManifestV1.begin(drain).also { probe.original = it }
        assertEquals(TestRunInstallationManifestResultV1.ALL_CHUNKS_PREPARED_NO_NETWORK, first.prepare())
        probe.assertReleased()
        val observed = TestOrdinaryDrainAccountingObservationV1(f)
        val charge = TestInstallationManifestPrepareCasesV1.MANIFEST_LITERAL
        val wrong = (observed.state().unused + charge).toLongArray().joinToString(",", "{", "}")
        // Explicit corrupt/legacy UNPAID fixture: retain the real canonical winner but undo its exact
        // charge. Presence of rows must never be accepted as proof of paid accounting or backfilled.
        checkNotNull(f.observer.dataSource).connection.use { connection ->
            connection.autoCommit = false
            try {
                val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
                assertEquals(1, jdbc.update("UPDATE complaint_test_runs SET unused_reserve = ?::bigint[] WHERE data_scope_id = ?", wrong, f.scope))
                for (counter in listOf(ComplaintCapacityCounter.JOURNAL_PUBLICATIONS, ComplaintCapacityCounter.RECOVERY_RESERVATIONS, ComplaintCapacityCounter.STORAGE_BYTES)) {
                    assertEquals(1, jdbc.update("UPDATE complaint_capacity_counters SET actual_units = actual_units - ?, test_reserved_units = test_reserved_units + ? WHERE name = ?",
                        charge[counter], charge[counter], counter.storedName))
                }
                connection.commit()
            } catch (problem: Throwable) { connection.rollback(); throw problem }
        }
        probe.reset()
        val damaged = observed.image()
        val retry = TestRunInstallationManifestV1.begin(drain).also { probe.original = it }
        assertThrows<TestInstallationManifestExceptionV1> { retry.prepare() }
        probe.assertReleased(requireCommitted = false)
        assertEquals(damaged, observed.image())
        assertTrue(probe.calls.all { it.step === TestInstallationManifestStepV1.CAPTURE })
        assertTrue(probe.calls.none { it.sql in TestInstallationManifestPrepareCasesV1.manifestAtomicStatements || it.sql.startsWith("UPDATE complaint_capacity_counters") })
    }
}
