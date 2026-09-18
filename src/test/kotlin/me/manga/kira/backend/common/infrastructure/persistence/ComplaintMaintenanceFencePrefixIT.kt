package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Connection

/** Real SQL exclusion only, not an exclusive production operator, provider drain or activation proof. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class ComplaintMaintenanceFencePrefixIT {
    private val database = lazy { PgLifecycleDatabaseFixture(ComplaintMaintenanceFencePrefixIT::class.java).also { it.start() } }

    @AfterAll
    fun closeDatabase() {
        if (database.isInitialized()) database.value.close()
    }

    @Test
    fun `named writer holds shared M on its original holder restores limits and releases only after its real commit`() = withFixture { f, lockReader ->
        var selectedPid = 0
        var original: StepUpPhaseObservation? = null
        f.afterStep = { step ->
            if (step === DeletionAuditStep.BEGIN) {
                val first = f.observations.single().second
                original = first
                selectedPid = first.identity.first
                val holder = selectedHolder(f)
                assertSame(first.lease, ownedPoolLease(holder.connection))
                assertTrue(fenceHeld(lockReader, selectedPid, MAINTENANCE))
                assertFalse(fenceHeld(lockReader, selectedPid, EPOCH), "This writer must not gain the old epoch fence.")
                assertEquals(setOf(f.pool), TransactionSynchronizationManager.getResourceMap().keys)
                assertEquals(0L, f.base.ordinary.ownedPool.lifecycle.activeAcquisitions())
                val limits = currentLimits(holder.connection)
                assertTrue(millis(limits[0]) in 1L..2_000L)
                assertEquals(listOf("1s", "100ms"), limits.drop(1))
                assertEquals(1_000, holder.connection.networkTimeout)
                assertEquals(first.identity, identity(holder.connection))
                OwnedCallerTestScope().use { readers ->
                    readers.launch {
                        requireConnectionFree()
                        independentTransaction(f) { connection ->
                            assertTrue(tryFence(connection, shared = true))
                            assertFalse(tryFence(connection, shared = false))
                        }
                        requireConnectionFree()
                    }.value()
                }
            }
        }
        val operation = f.execute()
        operation.requireCommitted()
        val first = checkNotNull(original)
        assertTrue(f.observations.all {
            it.second.phase === first.phase && it.second.lease === first.lease && it.second.identity == first.identity
        })
        assertEquals(PersistenceDatabaseOutcome.COMMITTED, first.phase.databaseOutcome())
        assertFalse(fenceHeld(lockReader, selectedPid, MAINTENANCE))
        f.assertReleased()
    }

    @Test
    fun `exclusive M refuses the real writer try before DML while the SQL observation keeps its existing E only prefix`() {
        val clock = MaintenanceProbeClock()
        withFixture(clock) { f, lockReader ->
            val before = f.base.state()
            var observedFence: Any? = null
            clock.at("OBSERVED") { _, fence ->
                observedFence = fence
                assertEquals(false, ownedCutField(fence, "observedLock"))
            }
            independentTransaction(f) { blocker ->
                blocker.createStatement().use { statement ->
                    statement.executeQuery(EXCLUSIVE_M).use { row -> assertTrue(row.next()) }
                }
                val failure = assertThrows<PersistencePhaseException> { f.execute() }
                clock.requireHealthy()
                assertEquals(PersistencePhaseFailureCode.ENTRY_REFUSED, failure.code)
                assertRollback(failure)
                assertTrue(f.observations.isEmpty(), "The real named writer must refuse before its BEGIN checkpoint or any path-specific SQL.")
                assertEquals(before, f.base.state())
                f.assertReleased()

                val observation = f.ownership.enterComplaintDeletionFencePrefix()
                try {
                    observation.begin()
                    val pid = identity(selectedHolder(f).connection).first
                    assertTrue(fenceHeld(lockReader, pid, EPOCH))
                    assertFalse(fenceHeld(lockReader, pid, MAINTENANCE))
                    observation.commit()
                } finally {
                    observation.finish()
                }
                assertEquals(PersistenceDatabaseOutcome.COMMITTED, observation.databaseOutcome())
                f.assertReleased()
            }
            assertEquals("FAILED", ownedCutField(checkNotNull(observedFence), "stage").toString())
            assertNull(ownedCutField(checkNotNull(observedFence), "active"))
            assertEquals(before, f.base.state())
        }
    }

    @Test
    fun `settings and guarded dispatch consume the same prefix and late settings never execute the try`() {
        val clock = MaintenanceProbeClock()
        withFixture(clock) { f, lockReader ->
            val before = f.base.state()
            var observedFence: Any? = null
            var pid = 0
            clock.at("SETTINGS_RETURNED") { phase, fence ->
                observedFence = fence
                val connection = selectedHolder(f).connection
                pid = identity(connection).first
                val limits = currentLimits(connection)
                assertTrue(millis(limits[1]) in 1L..75L)
                assertTrue(millis(limits[2]) in 1L..75L)
                assertTrue(connection.networkTimeout in 1..75)
                assertTrue(phase.callBudget(PersistenceJdbcGuardCallKind.BUSINESS).remainingMillis(2_000) in 1L..75L)
                assertFalse(fenceHeld(lockReader, pid, MAINTENANCE))
                clock.advancePrefixExpiry()
            }
            val failure = assertThrows<PersistencePhaseException> { f.execute() }
            clock.requireHealthy()
            assertEquals(PersistencePhaseFailureCode.TIME_BUDGET_EXHAUSTED, failure.code)
            assertRollback(failure)
            assertNull(ownedCutField(checkNotNull(observedFence), "observedLock"))
            assertTrue(f.observations.isEmpty())
            assertFalse(fenceHeld(lockReader, pid, MAINTENANCE))
            assertEquals(before, f.base.state())
            f.assertReleased()
        }
    }

    @Test
    fun `late real true M remains with the original lease until its own rollback releases the actual lock`() {
        val clock = MaintenanceProbeClock()
        withFixture(clock) { f, lockReader ->
            val before = f.base.state()
            var selectedLease: PersistenceJdbcLease? = null
            var pid = 0
            clock.at("OBSERVED") { _, fence ->
                assertEquals(true, ownedCutField(fence, "observedLock"))
                val connection = selectedHolder(f).connection
                selectedLease = ownedPoolLease(connection)
                pid = identity(connection).first
                assertTrue(fenceHeld(lockReader, pid, MAINTENANCE))
                assertFalse(checkNotNull(selectedLease).completion.quiescent())
                assertEquals(1, f.admission.activeOwners().totalOwners)
                clock.advancePrefixExpiry()
            }
            val failure = assertThrows<PersistencePhaseException> { f.execute() }
            clock.requireHealthy()
            assertEquals(PersistencePhaseFailureCode.TIME_BUDGET_EXHAUSTED, failure.code)
            assertRollback(failure)
            assertTrue(checkNotNull(selectedLease).completion.quiescent())
            assertFalse(fenceHeld(lockReader, pid, MAINTENANCE))
            assertTrue(f.observations.isEmpty())
            assertEquals(before, f.base.state())
            f.assertReleased()
        }
    }

    @Test
    fun `losing the selected holder between settings and try cannot repair borrow or commit after refusal`() {
        val clock = MaintenanceProbeClock()
        withFixture(clock) { f, _ ->
            val before = f.base.state()
            var original: ConnectionHolder? = null
            var observedFence: Any? = null
            clock.at("SETTINGS_RETURNED") { _, fence ->
                observedFence = fence
                original = selectedHolder(f)
                assertSame(original, TransactionSynchronizationManager.unbindResource(f.pool))
            }
            val phase = f.ownership.enterComplaintDeletionMutation()
            try {
                assertEquals(PersistencePhaseFailureCode.RESOURCE_REFUSED, assertThrows<PersistencePhaseException> { phase.begin() }.code)
                clock.requireHealthy()
                assertFalse(TransactionSynchronizationManager.hasResource(f.pool))
                assertThrows<PersistencePhaseException> { phase.commit() }
            } finally {
                // Only the exact holder deliberately unbound by this test; no replacement checkout or repaired business path.
                original?.let { TransactionSynchronizationManager.bindResource(f.pool, it) }
                phase.finish()
            }
            assertRollback(phase.failureException(PersistencePhaseFailureCode.WORK_FAILED))
            assertNull(ownedCutField(checkNotNull(observedFence), "observedLock"))
            assertEquals(before, f.base.state())
            assertEquals(0L, f.base.ordinary.ownedPool.lifecycle.activeAcquisitions())
            f.assertReleased()
        }
    }

    private fun withFixture(test: (DeletionComplaintAuditFixture, Connection) -> Unit) = withFixture(SystemPersistenceNanoClock, test)

    private fun withFixture(clock: PersistenceNanoClock, test: (DeletionComplaintAuditFixture, Connection) -> Unit) =
        withDeletionComplaintAudit(database.value, clock) { fixture ->
            requireConnectionFree()
            // Open before any guarded phase. Raw observer calls cannot enlist a foreign Spring ConnectionHolder.
            checkNotNull(fixture.base.observer.dataSource).connection.use { reader -> test(fixture, reader) }
        }

    private fun selectedHolder(f: DeletionComplaintAuditFixture): ConnectionHolder = TransactionSynchronizationManager.getResource(f.pool) as ConnectionHolder

    private fun identity(connection: Connection): Pair<Int, Long> = connection.prepareStatement("SELECT pg_backend_pid(), txid_current()").use { statement ->
        statement.executeQuery().use { row ->
            assertTrue(row.next())
            (row.getInt(1) to row.getLong(2)).also { assertFalse(row.next()) }
        }
    }

    private fun currentLimits(connection: Connection): List<String> = connection.prepareStatement(
        "SELECT current_setting('transaction_timeout'), current_setting('statement_timeout'), current_setting('lock_timeout')",
    ).use { statement ->
        statement.executeQuery().use { row ->
            assertTrue(row.next())
            List(3) { row.getString(it + 1) }.also { assertFalse(row.next()) }
        }
    }

    private fun fenceHeld(reader: Connection, pid: Int, namespace: String): Boolean = reader.prepareStatement(
        "SELECT EXISTS (SELECT 1 FROM pg_locks WHERE pid = ? AND locktype = 'advisory' AND mode = 'ShareLock' AND granted " +
            "AND classid::bigint = ((hashtextextended(?, 0) >> 32) & 4294967295) " +
            "AND objid::bigint = (hashtextextended(?, 0) & 4294967295) AND objsubid = 1)",
    ).use { statement ->
        statement.queryTimeout = 2
        statement.setInt(1, pid)
        statement.setString(2, namespace)
        statement.setString(3, namespace)
        statement.executeQuery().use { row ->
            assertTrue(row.next())
            row.getBoolean(1).also {
                assertFalse(row.wasNull())
                assertFalse(row.next())
            }
        }
    }

    private fun independentTransaction(f: DeletionComplaintAuditFixture, action: (Connection) -> Unit) {
        f.base.observer.execute(ConnectionCallback<Unit> { connection ->
            connection.autoCommit = false
            try {
                action(connection)
            } finally {
                connection.rollback()
                connection.autoCommit = true
            }
        })
    }

    private fun tryFence(connection: Connection, shared: Boolean): Boolean =
        connection.prepareStatement(if (shared) TRY_SHARED_M else TRY_EXCLUSIVE_M).use { statement ->
            statement.executeQuery().use { row ->
                assertTrue(row.next())
                row.getBoolean(1).also {
                    assertFalse(row.wasNull())
                    assertFalse(row.next())
                }
            }
        }

    private fun millis(value: String): Long = when {
        value.endsWith("ms") -> value.removeSuffix("ms").toLong()
        value.endsWith("s") -> value.removeSuffix("s").toLong() * 1_000
        else -> error("Unexpected synthetic timeout unit")
    }

    private fun assertRollback(failure: PersistencePhaseException) {
        assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, failure.databaseOutcome)
        assertTrue(failure.cleanupProven)
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
    }

    companion object {
        private const val MAINTENANCE = "complaint-maintenance-v1"
        private const val EPOCH = "complaint-journal-epoch"
        private const val EXCLUSIVE_M = "SELECT pg_advisory_xact_lock(hashtextextended('complaint-maintenance-v1', 0))"
        private const val TRY_SHARED_M = "SELECT pg_try_advisory_xact_lock_shared(hashtextextended('complaint-maintenance-v1', 0))"
        private const val TRY_EXCLUSIVE_M = "SELECT pg_try_advisory_xact_lock(hashtextextended('complaint-maintenance-v1', 0))"
    }
}

/** Existing read-only stage/clock observation pattern. No SQL result, holder field, native byte or production hook is changed. */
private class MaintenanceProbeClock : PersistenceNanoClock {
    private val actions = mutableMapOf<String, (PersistencePhaseContext, Any) -> Unit>()
    private var advance = 0L
    private var problem: Throwable? = null

    fun at(stage: String, action: (PersistencePhaseContext, Any) -> Unit) {
        check(actions.put(stage, action) == null)
    }

    fun advancePrefixExpiry() {
        advance += 100_000_000L
    }

    override fun nanoTime(): Long {
        val phase = PersistencePhaseOwnership.current()
        val holder = phase?.let { ownedCutField(it, "selectedHolder") }
        val fence = holder?.let { ownedCutField(it, "maintenanceFence") }
        if (phase != null && fence != null) {
            val stage = ownedCutField(fence, "stage").toString()
            val action = actions.remove(stage)
            if (action != null) {
                try {
                    action(phase, fence)
                } catch (failure: Throwable) {
                    problem = failure
                    throw failure
                }
            }
        }
        return System.nanoTime() + advance
    }

    fun requireHealthy() {
        problem?.let { throw it }
        assertTrue(actions.isEmpty(), "The actual maintenance prefix did not reach its required observation point")
    }
}
