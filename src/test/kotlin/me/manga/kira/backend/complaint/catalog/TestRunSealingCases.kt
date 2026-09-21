package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintCapacityEncoding
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalCapacityChargesV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunSealingExceptionV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunSealingResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunSealingV1
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Connection
import java.sql.Timestamp

/** Existing real PROJECT + independent runtime/raw registration fixture; no seeded registration or supplied release proof. */
internal object TestRunSealingCases {
    fun barrierPaidAuditAndReplay(tls: VersionBoundPersistenceConnectedFixture) = ComplaintTestNamespaceRegistrationCases.withRegisteredRun(tls) { p, runtime, registration, probe ->
        // Synthetic unrelated current-ledger activity after genuine registration, not another P or a seal/audit receipt.
        assertEquals(1, p.f.rows.observer.update("UPDATE complaint_capacity_counters SET free_units = free_units - 17, " +
            "recovery_reserved_units = recovery_reserved_units + 17 WHERE name = 'storage_bytes'"))
        val before = p.image()
        val counters = p.counters()
        val reads = p.f.http.read.requests.size
        val original = TestRunSealingV1.begin(registration)
        val lower = p.databaseTime()
        val participation = probe.beforeSql
        var barrier: Map<String, List<String>>? = null
        var atomicAudit = false
        probe.beforeSql = { step ->
            participation(step)
            if (step == "test-run-seal") assertRunOnlyLocks(p.holder(runtime))
            if (probe.calls.last().path === PersistencePhasePath.COMPLAINT_TEST_RUN_SEALED_AUDIT && barrier == null) {
                val first = probe.observations.keys.first()
                assertEquals(PersistenceDatabaseOutcome.COMMITTED, first.databaseOutcome())
                assertTrue(first.testRunSealingCleanupProven(original))
                assertTrue(probe.observations.getValue(first).lease.completion.quiescent())
                // Direct observer JDBC must not enlist a second Spring resource in the new audit transaction.
                checkNotNull(p.f.rows.observer.dataSource).connection.use { connection ->
                    connection.prepareStatement("SELECT state FROM complaint_test_runs WHERE data_scope_id = ?").use { statement ->
                        statement.setObject(1, p.scope)
                        statement.executeQuery().use { rows ->
                            assertTrue(rows.next()); assertEquals("SEALED", rows.getString(1)); assertFalse(rows.next())
                        }
                    }
                }
                barrier = p.image()
                assertEquals(before.getValue("audits"), checkNotNull(barrier).getValue("audits"))
                assertEquals(before.getValue("counters"), checkNotNull(barrier).getValue("counters"),
                    "The run-only transaction cannot consume audit or terminal capacity.")
            }
        }
        probe.afterSql = { step ->
            if (step == "test-run-seal") assertEquals(before, p.image(), "The barrier is not visible before its actual COMMIT.")
            if (step == "test-run-sealed-audit") {
                assertEquals(barrier, p.image(), "The audit, run remainder and two charged counters stay atomic on another connection.")
                atomicAudit = true
            }
        }
        try { assertEquals(TestRunSealingResultV1.SEALED_AND_AUDITED, original.seal()) }
        finally { probe.beforeSql = participation; probe.afterSql = {} }
        assertTrue(atomicAudit)
        assertTrue(sealedAt(p) >= lower && sealedAt(p) <= p.databaseTime())
        assertReleased(original, runtime, probe)
        assertAudited(p, counters)
        val paths = probe.calls.groupBy { it.path }
        assertEquals(listOf("test-run-sealing-authenticate", "test-run-sealing-run-lock", "test-run-seal"),
            paths.getValue(PersistencePhasePath.COMPLAINT_TEST_RUN_SEAL).map { it.step })
        assertEquals(listOf("test-run-sealing-authenticate", "test-run-sealed-gate-lock", "test-run-sealed-gate-lock",
            "test-run-sealed-active-history-read", "test-run-sealed-global-lock", "test-run-sealed-control-lock", "counters",
            "test-run-sealing-run-lock", "test-run-sealed-audit-lock", "charge:audit_rows", "charge:storage_bytes", "test-run-sealed-reserve", "test-run-sealed-audit"),
            paths.getValue(PersistencePhasePath.COMPLAINT_TEST_RUN_SEALED_AUDIT).map { it.step })
        assertEquals(listOf(listOf(ComplaintDataScope.LIVE.id), listOf(p.scope)),
            probe.calls.filter { it.step == "test-run-sealed-gate-lock" }.map { it.arguments }, "Global gate locks before the registered scope.")
        val after = p.image()
        assertEquals(before.filterKeys { it !in changedTables }, after.filterKeys { it !in changedTables }, "No gate, epoch, catalog, notice or resource writes.")
        assertEquals(reads, p.f.http.read.requests.size, "Sealing performs no provider work or renewed registration readback.")
        assertThrows<TestRunSealingExceptionV1> { original.seal() }

        probe.resetObservations()
        val replay = TestRunSealingV1.begin(registration)
        assertEquals(TestRunSealingResultV1.SEALED_AND_AUDITED, replay.seal())
        assertReleased(replay, runtime, probe)
        assertEquals(after, p.image(), "A successful replay changes no timestamp/xmin, unused reserve, counter or audit.")
        assertNoWrites(probe)

        // The existing audit row is the durable marker; two equal-looking rows cannot authorize success or another charge.
        assertEquals(1, p.f.rows.observer.update("INSERT INTO audit_log (actor_user_id, action, entity_type, entity_id, detail, created_at, complaint_data_scope_id, complaint_actor_kind) " +
            "SELECT actor_user_id, action, entity_type, entity_id, detail, created_at, complaint_data_scope_id, complaint_actor_kind FROM audit_log " +
            "WHERE complaint_data_scope_id = ? AND action = 'COMPLAINT_TEST_RUN_SEALED'", p.scope))
        val contradictory = p.image()
        probe.resetObservations()
        assertThrows<TestRunSealingExceptionV1> { TestRunSealingV1.begin(registration).seal() }
        requireConnectionFree()
        assertEquals(contradictory, p.image())
        assertNoWrites(probe)
        registration.close()
        val calls = probe.calls.size
        assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { TestRunSealingV1.begin(registration) }
        assertEquals(calls, probe.calls.size)
    }

    fun completionFailure(tls: VersionBoundPersistenceConnectedFixture, cut: TestRegistrationCompletionCut, auditPhase: Boolean) =
        ComplaintTestNamespaceRegistrationCases.withRegisteredRun(tls) { p, runtime, registration, probe ->
            val before = p.image()
            val counters = p.counters()
            val original = TestRunSealingV1.begin(registration)
            val participation = probe.beforeSql
            val key = Any()
            val sentinel = Any()
            var bound = false
            var selected: PersistencePhaseContext? = null
            var barrier: Map<String, List<String>>? = null
            probe.beforeSql = { step ->
                participation(step)
                if (auditPhase && barrier == null && probe.calls.last().path === PersistencePhasePath.COMPLAINT_TEST_RUN_SEALED_AUDIT) barrier = p.image()
            }
            probe.afterSql = { step ->
                if (selected == null && step == (if (auditPhase) "test-run-sealed-audit" else "test-run-seal")) {
                    selected = checkNotNull(PersistencePhaseOwnership.current())
                    if (cut === TestRegistrationCompletionCut.DEFERRED_COMMIT_UNKNOWN) {
                        val jdbc = JdbcTemplate(runtime.pools.catalogCoordinator.dataSource)
                        jdbc.execute("CREATE TEMP TABLE kira_sealing_commit_cut (id int UNIQUE DEFERRABLE INITIALLY DEFERRED)")
                        assertEquals(2, jdbc.update("INSERT INTO kira_sealing_commit_cut VALUES (1), (1)"))
                    } else TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                        override fun beforeCommit(readOnly: Boolean) {
                            // Spring's beforeCommit rollback contract catches RuntimeException/Error, not checked IOException.
                            if (cut === TestRegistrationCompletionCut.BEFORE_COMMIT) throw IllegalStateException("Synthetic sealing beforeCommit failure.")
                        }
                        override fun afterCommit() {
                            if (cut === TestRegistrationCompletionCut.UNRESOLVED_RELEASE) {
                                TransactionSynchronizationManager.bindResource(key, sentinel)
                                bound = true
                            }
                            if (cut !== TestRegistrationCompletionCut.BEFORE_COMMIT) throw IllegalStateException("Synthetic sealing afterCommit acknowledgment loss.")
                        }
                    })
                }
            }
            try {
                assertThrows<TestRunSealingExceptionV1> { original.seal() }
                if (cut === TestRegistrationCompletionCut.UNRESOLVED_RELEASE) {
                    assertTrue(bound)
                    assertSame(selected, PersistencePhaseOwnership.current())
                    assertTrue(checkNotNull(selected).quarantined())
                    val calls = probe.calls.size
                    assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { TestRunSealingV1.begin(registration) }
                    assertEquals(calls, probe.calls.size, "Unresolved original physical/Spring custody still blocks another attempt.")
                }
            } finally {
                probe.beforeSql = participation
                probe.afterSql = {}
                if (bound) assertSame(sentinel, TransactionSynchronizationManager.unbindResource(key))
                requireConnectionFree() // Same original cleanup can settle; that does not rehabilitate the original failure.
            }
            val outcome = when (cut) {
                TestRegistrationCompletionCut.BEFORE_COMMIT -> PersistenceDatabaseOutcome.ROLLED_BACK
                TestRegistrationCompletionCut.DEFERRED_COMMIT_UNKNOWN -> PersistenceDatabaseOutcome.UNKNOWN
                else -> PersistenceDatabaseOutcome.COMMITTED
            }
            assertEquals(outcome, checkNotNull(selected).databaseOutcome())
            assertEquals(if (auditPhase || outcome === PersistenceDatabaseOutcome.COMMITTED) "SEALED" else "ACTIVE", state(p))
            if (auditPhase && outcome === PersistenceDatabaseOutcome.COMMITTED) assertAudited(p, counters)
            else {
                assertEquals(0L, audits(p))
                assertEquals(counters, p.counters())
                assertEquals(if (auditPhase) checkNotNull(barrier) else before.filterKeys { it != "complaint_test_runs" },
                    if (auditPhase) p.image() else p.image().filterKeys { it != "complaint_test_runs" })
            }
            val afterFailure = p.image()
            val calls = probe.calls.size
            assertThrows<TestRunSealingExceptionV1> { original.seal() }
            assertEquals(calls, probe.calls.size)
            assertEquals(afterFailure, p.image())

            if (outcome === PersistenceDatabaseOutcome.COMMITTED) {
                val originalTime = sealedAt(p)
                probe.resetObservations()
                val resumed = TestRunSealingV1.begin(registration) // SAME registration; never rerun first/fresh registration on a SEALED row.
                assertEquals(TestRunSealingResultV1.SEALED_AND_AUDITED, resumed.seal())
                assertReleased(resumed, runtime, probe)
                assertEquals(originalTime, sealedAt(p))
                assertAudited(p, counters)
                assertFalse(probe.steps.contains("test-run-seal"), "A committed seal is not repeated after acknowledgment loss.")
                if (auditPhase) { assertEquals(afterFailure, p.image()); assertNoWrites(probe) }
                val resumedCalls = probe.calls.size
                assertThrows<TestRunSealingExceptionV1> { original.seal() }
                assertEquals(resumedCalls, probe.calls.size)
            }
            probe.assertNoLostAssertions()
        }

    private fun assertAudited(p: ProjectionActivationObservation, before: Map<String, ProjectionCounterObservation>) {
        assertEquals(1L, audits(p))
        val after = p.counters()
        val charge = TestTerminalCapacityChargesV1.AUDIT
        for (counter in ComplaintCapacityEncoding.lockOrder()) {
            val old = before.getValue(counter.storedName)
            val current = after.getValue(counter.storedName)
            assertEquals(old.free, current.free)
            assertEquals(old.recovery, current.recovery)
            assertEquals(old.actual + charge[counter], current.actual)
            assertEquals(old.reserved - charge[counter], current.reserved)
            assertEquals(old.preserved, current.preserved, "P, daily admission and all unrelated fields are unchanged.")
            if (charge[counter] == 0L) assertEquals(old.full, current.full)
        }
        val unused = p.f.rows.observer.query("SELECT unused_reserve FROM complaint_test_runs WHERE data_scope_id = ?", { row, _ ->
            val array = row.getArray(1)
            try { (array.array as Array<*>).map { it as Long }.toLongArray() } finally { array.free() }
        }, p.scope).single()
        val reserve = p.run.accounting.originalUnusedReserve.toLongArray()
        assertArrayEquals(LongArray(reserve.size) { reserve[it] - charge.toLongArray()[it] }, unused)
        assertEquals(true, p.f.rows.observer.queryForObject("SELECT a.actor_user_id IS NULL AND a.complaint_actor_kind = 'SYSTEM' " +
            "AND a.entity_type = 'complaint_test_run' AND a.entity_id = r.data_scope_id::text AND a.created_at = r.sealed_at " +
            "AND a.detail = jsonb_build_object('generation', r.activation_catalog_generation) " +
            "FROM audit_log a JOIN complaint_test_runs r ON r.data_scope_id = a.complaint_data_scope_id " +
            "WHERE r.data_scope_id = ? AND a.action = 'COMPLAINT_TEST_RUN_SEALED'", Boolean::class.java, p.scope))
    }

    private fun assertReleased(original: TestRunSealingV1, runtime: VersionBoundPersistenceConnectedFixture, probe: CatalogSignerRotationProbeJdbc) {
        requireConnectionFree()
        assertNull(PersistencePhaseOwnership.current())
        assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty())
        assertEquals(0, runtime.pools.catalogCoordinator.activeSnapshotOwners())
        assertNull(SignedActivationObservation.active(runtime.pools.catalogCoordinator))
        assertEquals(2, probe.observations.size)
        probe.observations.forEach { (phase, observed) ->
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome())
            assertTrue(phase.testRunSealingCleanupProven(original))
            assertTrue(observed.lease.completion.quiescent())
        }
        probe.assertNoLostAssertions()
    }

    private fun assertRunOnlyLocks(connection: Connection) = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT count(*) FROM pg_locks WHERE pid = pg_backend_pid() AND granted AND mode IN ('RowShareLock', 'RowExclusiveLock') " +
            "AND relation IN ('complaint_journal_control'::regclass, 'complaint_capacity_counters'::regclass, 'complaint_catalog_mutations'::regclass, 'audit_log'::regclass)").use { rows ->
            assertTrue(rows.next()); assertEquals(0L, rows.getLong(1)); assertFalse(rows.next())
        }
    }

    private fun assertNoWrites(probe: CatalogSignerRotationProbeJdbc) = assertTrue(probe.steps.none {
        it.startsWith("charge:") || it in setOf("test-run-seal", "test-run-sealed-reserve", "test-run-sealed-audit")
    })
    private fun state(p: ProjectionActivationObservation): String = checkNotNull(p.f.rows.observer.queryForObject(
        "SELECT state FROM complaint_test_runs WHERE data_scope_id = ?", String::class.java, p.scope))
    private fun sealedAt(p: ProjectionActivationObservation) = checkNotNull(p.f.rows.observer.queryForObject(
        "SELECT sealed_at FROM complaint_test_runs WHERE data_scope_id = ?", Timestamp::class.java, p.scope)).toInstant()
    private fun audits(p: ProjectionActivationObservation): Long = checkNotNull(p.f.rows.observer.queryForObject(
        "SELECT count(*) FROM audit_log WHERE complaint_data_scope_id = ? AND action = 'COMPLAINT_TEST_RUN_SEALED'", Long::class.java, p.scope))
    private val changedTables = setOf("complaint_test_runs", "counters", "audits")
}
