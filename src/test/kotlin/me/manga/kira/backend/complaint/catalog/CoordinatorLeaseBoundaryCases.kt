package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.OwnedCallerTestScope
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.awaitLifecycleFact
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogCoordinatorLeaseAcquisitionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogCoordinatorLeaseTransitionV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/** Genuine independent PG holders and actual Spring/driver completion cuts; no replacement clock, commit or release receipt. */
internal class CoordinatorLeaseBoundaryCases(private val f: CoordinatorLeaseTestFixture) {
    fun postLockClockAndFenceIndependence() {
        postLockClock()
        exclusiveFenceAndLaterClassLocks()
        f.released()
    }

    fun sealedResultsCommitAndReleaseFailures() {
        val steps = sealedResultWaitsForActualRelease()
        for (step in steps.distinct()) {
            for (after in listOf(false, true)) {
                var fired = false
                val cut: (CoordinatorLeaseSqlStep) -> Unit = { reached ->
                    if (reached == step) {
                        fired = true
                        error("Synthetic coordinator lease SQL cut.")
                    }
                }
                if (after) f.jdbc.afterSql = cut else f.jdbc.beforeSql = cut
                try {
                    f.refused(PersistenceDatabaseOutcome.ROLLED_BACK) { f.phases.acquire(f.binding) }
                    assertTrue(fired)
                    assertThrows<PersistencePhaseException> { f.retainedOperation().receipt }
                } finally {
                    f.jdbc.beforeSql = {}
                    f.jdbc.afterSql = {}
                }
            }
        }
        deferredCommitFailure()
        committedCompletionFailure()
        unresolvedOriginalCleanup()
        f.released()
    }

    private fun postLockClock() = independentTransaction { blocker, observer ->
        val holder = identity(observer)
        assertEquals(
            listOf(ComplaintDataScope.LIVE.id),
            observer.queryForList(
                "SELECT data_scope_id FROM complaint_journal_control WHERE data_scope_id = ? FOR UPDATE",
                java.util.UUID::class.java,
                ComplaintDataScope.LIVE.id,
            ),
        )
        OwnedCallerTestScope().use { callers ->
            val entered = callers.gate()
            f.jdbc.beforeSql = { if (it === CoordinatorLeaseSqlStep.LOCK_CONTROL) entered.hold() }
            val worker = callers.launch { f.phases.acquire(f.binding) }
            var marker: Instant? = null
            try {
                entered.awaitEntered()
                val waiter = checkNotNull(f.jdbc.observation).identity
                assertNotEquals(holder.first, waiter.first)
                assertNotEquals(holder.second, waiter.second)
                entered.release()
                // Real pg_locks/blocking identity, not a sleep or a before-query callback mistaken for lock acquisition.
                awaitLifecycleFact(50) {
                    observer.queryForObject(
                        "SELECT ? = ANY(pg_blocking_pids(?)) AND EXISTS " +
                            "(SELECT 1 FROM pg_locks WHERE pid = ? AND NOT granted AND locktype = 'transactionid')",
                        Boolean::class.java,
                        holder.first,
                        waiter.first,
                        waiter.first,
                    ) == true
                }
                marker = checkNotNull(observer.queryForObject("SELECT clock_timestamp()", { row, _ -> row.getTimestamp(1).toInstant() }))
                blocker.commit()
            } finally {
                entered.release()
                blocker.rollback()
                f.jdbc.beforeSql = {}
            }
            val acquired = worker.value()
            try {
                f.assertReceipt(acquired.receipt, CatalogCoordinatorLeaseTransitionV1.ACQUIRED)
                assertFalse(acquired.receipt.sampledAt.isBefore(checkNotNull(marker)), "The lease clock must be sampled after the real control-row wait.")
            } finally {
                acquired.campaign.close()
            }
            f.assertReceipt(f.phases.relinquish(acquired.campaign), CatalogCoordinatorLeaseTransitionV1.RELINQUISHED)
        }
    }

    private fun exclusiveFenceAndLaterClassLocks() = independentTransaction { blocker, observer ->
        observer.execute("SELECT pg_advisory_xact_lock(hashtextextended('complaint-journal-epoch', 0))")
        observer.execute("SELECT pg_advisory_xact_lock(hashtextextended('complaint-catalog-mutation', 0))")
        assertTrue(observer.query("SELECT operation_token FROM complaint_catalog_mutations FOR UPDATE", { _, _ -> true }).isNotEmpty())
        assertTrue(observer.query("SELECT name FROM complaint_capacity_counters ORDER BY name COLLATE \"C\" FOR UPDATE", { _, _ -> true }).isNotEmpty())
        val foreign = identity(observer)
        val unchanged = f.unchangedOutsideLease()
        OwnedCallerTestScope().use { callers ->
            var checks = 0
            f.jdbc.afterSql = { step ->
                if (step === CoordinatorLeaseSqlStep.READ_CONTROL) {
                    val owned = checkNotNull(f.jdbc.observation).identity
                    assertNotEquals(foreign.first, owned.first)
                    assertNotEquals(foreign.second, owned.second)
                    assertTrue(
                        callers.launch {
                            requireConnectionFree()
                            assertEquals(
                                0L,
                                observer.queryForObject(
                                    "SELECT count(*) FROM pg_locks l LEFT JOIN pg_class c ON c.oid = l.relation " +
                                        "WHERE l.pid = ? AND (l.locktype = 'advisory' OR c.relname IN " +
                                        "('complaint_catalog_mutations','complaint_capacity_counters'," +
                                        "'complaint_journal_scan_runs','complaint_journal_scan_entries'))",
                                    Long::class.java,
                                    owned.first,
                                ),
                            )
                            assertControlLocked(blocker)
                            requireConnectionFree()
                            true
                        }.value(),
                    )
                    checks++
                }
            }
            try {
                val acquired = f.phases.acquire(f.binding)
                f.assertReceipt(acquired.receipt, CatalogCoordinatorLeaseTransitionV1.ACQUIRED)
                f.assertReceipt(f.phases.renew(acquired.campaign), CatalogCoordinatorLeaseTransitionV1.RENEWED)
                f.assertReceipt(f.phases.relinquish(acquired.campaign), CatalogCoordinatorLeaseTransitionV1.RELINQUISHED)
                assertEquals(3, checks)
            } finally {
                f.jdbc.afterSql = {}
            }
        }
        assertEquals(unchanged, f.unchangedOutsideLease())
    }

    private fun sealedResultWaitsForActualRelease(): List<CoordinatorLeaseSqlStep> {
        val returned = AtomicReference<CatalogCoordinatorLeaseAcquisitionV1?>()
        var beforeChecked = false
        var afterChecked = false
        f.jdbc.steps.clear()
        val acquired = OwnedCallerTestScope().use { callers ->
            val held = callers.gate()
            f.jdbc.afterSql = { step ->
                if (step === CoordinatorLeaseSqlStep.READ_CONTROL) {
                    TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                        override fun beforeCommit(readOnly: Boolean) = f.jdbc.preserveAssertions {
                            val early = assertThrows<PersistencePhaseException> { f.retainedOperation().receipt }
                            assertEquals(PersistenceDatabaseOutcome.NONE, early.databaseOutcome)
                            assertFalse(early.cleanupProven)
                            beforeChecked = true
                        }

                        override fun afterCommit() = f.jdbc.preserveAssertions {
                            val unreleased = assertThrows<PersistencePhaseException> { f.retainedOperation().receipt }
                            assertEquals(PersistenceDatabaseOutcome.COMMITTED, unreleased.databaseOutcome)
                            assertFalse(unreleased.cleanupProven)
                            afterChecked = true
                            held.hold()
                        }
                    })
                }
            }
            val worker = callers.launch { f.phases.acquire(f.binding).also(returned::set) }
            try {
                held.awaitEntered()
                val observed = checkNotNull(f.jdbc.observation)
                assertEquals(PersistenceDatabaseOutcome.COMMITTED, observed.lease.completion.databaseOutcome())
                assertFalse(observed.lease.completion.quiescent())
                assertEquals(1, f.coordinator.activeSnapshotOwners())
                assertNotNull(f.row().owner)
                assertNull(returned.get(), "A known commit is not an actual Spring/JDBC release receipt.")
            } finally {
                held.release()
                f.jdbc.afterSql = {}
            }
            worker.value()
        }
        assertTrue(beforeChecked && afterChecked)
        assertSame(acquired, returned.get())
        f.assertReceipt(acquired.receipt, CatalogCoordinatorLeaseTransitionV1.ACQUIRED)
        val steps = f.jdbc.steps.toList()
        assertEquals(
            listOf(CoordinatorLeaseSqlStep.LOCK_CONTROL, CoordinatorLeaseSqlStep.WRITE_CONTROL, CoordinatorLeaseSqlStep.READ_CONTROL),
            steps,
        )
        f.assertReceipt(f.phases.relinquish(acquired.campaign), CatalogCoordinatorLeaseTransitionV1.RELINQUISHED)
        return steps
    }

    private fun deferredCommitFailure() {
        var armed = false
        f.jdbc.afterSql = { step ->
            if (step === CoordinatorLeaseSqlStep.READ_CONTROL) {
                val owned = JdbcTemplate(f.coordinator.dataSource)
                owned.execute("CREATE TEMP TABLE kira_coordinator_lease_commit (id int UNIQUE DEFERRABLE INITIALLY DEFERRED)")
                assertEquals(2, owned.update("INSERT INTO kira_coordinator_lease_commit VALUES (1), (1)"))
                armed = true
            }
        }
        try {
            f.refused(PersistenceDatabaseOutcome.UNKNOWN) { f.phases.acquire(f.binding) }
            assertTrue(armed)
            assertThrows<PersistencePhaseException> { f.retainedOperation().receipt }
        } finally {
            f.jdbc.afterSql = {}
        }
    }

    private fun committedCompletionFailure() {
        var fired = false
        f.jdbc.afterSql = { step ->
            if (step === CoordinatorLeaseSqlStep.READ_CONTROL) {
                TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun afterCommit() {
                        fired = true
                        error("Synthetic coordinator lease completion-tail failure.")
                    }
                })
            }
        }
        val failure = try {
            assertThrows<PersistencePhaseException> { f.phases.acquire(f.binding) }
        } finally {
            f.jdbc.afterSql = {}
        }
        assertTrue(fired)
        assertEquals(PersistenceDatabaseOutcome.COMMITTED, failure.databaseOutcome)
        assertTrue(failure.cleanupProven)
        val original = f.retainedOperation()
        assertThrows<PersistencePhaseException> { original.receipt }
        f.released()
        assertNotNull(f.row().owner)
        f.refused(PersistenceDatabaseOutcome.ROLLED_BACK) { f.phases.acquire(f.binding) }
        recoverExpired()
        assertThrows<PersistencePhaseException> { original.receipt }
    }

    private fun unresolvedOriginalCleanup() {
        val key = Any()
        val sentinel = Any()
        var bound = false
        f.jdbc.afterSql = { step ->
            if (step === CoordinatorLeaseSqlStep.READ_CONTROL) {
                TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun afterCommit() {
                        TransactionSynchronizationManager.bindResource(key, sentinel)
                        bound = true
                        error("Synthetic coordinator lease completion failure with unresolved Spring state.")
                    }
                })
            }
        }
        try {
            val failure = assertThrows<PersistencePhaseException> { f.phases.acquire(f.binding) }
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, failure.databaseOutcome)
            assertFalse(failure.cleanupProven)
            assertSame(f.jdbc.phase, PersistencePhaseOwnership.current())
            assertTrue(checkNotNull(f.jdbc.phase).quarantined())
            assertEquals(1, f.coordinator.activeSnapshotOwners())
            val getter = assertThrows<PersistencePhaseException> { f.retainedOperation().receipt }
            assertFalse(getter.cleanupProven)
            assertThrows<PersistencePhaseException> { f.phases.acquire(f.binding) }
        } finally {
            f.jdbc.afterSql = {}
            if (bound) assertSame(sentinel, TransactionSynchronizationManager.unbindResource(key))
            requireConnectionFree() // Only the original caller can settle this actual retained quarantine.
        }
        val original = f.retainedOperation()
        val later = assertThrows<PersistencePhaseException> { original.receipt }
        assertEquals(PersistencePhaseFailureCode.CLEANUP_UNRESOLVED, later.code)
        assertTrue(later.cleanupProven)
        f.released()
        recoverExpired()
        assertThrows<PersistencePhaseException> { original.receipt }
    }

    private fun recoverExpired() {
        f.expireForTest()
        val recovered = f.phases.acquire(f.binding)
        f.assertReceipt(f.phases.relinquish(recovered.campaign), CatalogCoordinatorLeaseTransitionV1.RELINQUISHED)
    }

    private fun identity(jdbc: JdbcTemplate): Pair<Int, Long> =
        checkNotNull(jdbc.queryForObject("SELECT pg_backend_pid(), txid_current()", { row, _ -> row.getInt(1) to row.getLong(2) }))

    private fun <T> independentTransaction(action: (Connection, JdbcTemplate) -> T): T = checkNotNull(f.observer.dataSource).connection.use { connection ->
        connection.autoCommit = false
        val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true)).apply { exceptionTranslator = SQLExceptionSubclassTranslator() }
        try {
            action(connection, jdbc)
        } finally {
            connection.rollback()
        }
    }

    private fun assertControlLocked(connection: Connection) {
        val savepoint = connection.setSavepoint()
        val failure = try {
            assertThrows<SQLException> {
                connection.prepareStatement("SELECT data_scope_id FROM complaint_journal_control WHERE data_scope_id = ? FOR UPDATE NOWAIT").use { statement ->
                    statement.queryTimeout = 1
                    statement.setObject(1, ComplaintDataScope.LIVE.id)
                    statement.executeQuery().use { assertTrue(it.next()) }
                }
            }
        } finally {
            connection.rollback(savepoint)
            connection.releaseSavepoint(savepoint)
        }
        assertEquals("55P03", failure.sqlState)
    }
}
