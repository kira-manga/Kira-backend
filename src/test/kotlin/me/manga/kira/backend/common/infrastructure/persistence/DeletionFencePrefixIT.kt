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
import java.util.concurrent.atomic.AtomicReference

/** Real PostgreSQL fence/holder/rollback checks; no controls, writer authority or production activation. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class DeletionFencePrefixIT {
    private val database = lazy { PgLifecycleDatabaseFixture(DeletionFencePrefixIT::class.java).also { it.start() } }

    @AfterAll
    fun closeDatabase() {
        if (database.isInitialized()) database.value.close()
    }

    @Test
    fun `real shared fence uses the retained deletion transaction and restores normal remaining limits`() = withFixture { f ->
        val before = f.base.state()
        var selectedPid = 0
        val phase = runPrefix(f) {
            val holder = selectedHolder(f)
            val first = f.observations.single().second
            selectedPid = first.identity.first
            val held = OwnedCallerTestScope().use { readers ->
                readers.launch {
                    requireConnectionFree()
                    val observed = sharedFenceHeld(f, selectedPid)
                    requireConnectionFree()
                    observed
                }.value()
            }
            assertTrue(held)
            assertSame(first.lease, ownedPoolLease(holder.connection))
            assertEquals(setOf(f.pool), TransactionSynchronizationManager.getResourceMap().keys)
            assertEquals(0L, f.base.ordinary.ownedPool.lifecycle.activeAcquisitions())
            val limits = currentLimits(holder.connection)
            assertTrue(millis(limits[0]) in 1L..2_000L)
            assertEquals(listOf("1s", "100ms"), limits.drop(1))
            assertEquals(1_000, holder.connection.networkTimeout)
            val second = identity(holder.connection)
            assertEquals(first.identity, second)
        }
        assertEquals(PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome())
        assertFalse(sharedFenceHeld(f, selectedPid))
        assertEquals(before, f.base.state())
    }

    @Test
    fun `exclusive epoch fence refuses before downstream work and rolls back the one real false try`() {
        val clock = FenceProbeClock()
        withFixture(clock) { f ->
            val before = f.base.state()
            var body = false
            var observed: PersistencePhaseContext? = null
            clock.at("OBSERVED") { phase, fence ->
                observed = phase
                assertEquals(false, ownedCutField(fence, "observedLock"))
            }
            // A separately owned observer transaction, never an ordinary application-pool borrow.
            f.base.observer.execute(
                ConnectionCallback<Unit> { connection ->
                    connection.autoCommit = false
                    try {
                        connection.createStatement().use { statement ->
                            statement.executeQuery(EXCLUSIVE_FENCE).use { result -> assertTrue(result.next()) }
                        }
                        val failure = assertThrows<PersistencePhaseException> { runPrefix(f, clock) { body = true } }
                        assertEquals(PersistencePhaseFailureCode.ENTRY_REFUSED, failure.code)
                        assertRollback(failure)
                    } finally {
                        connection.rollback()
                        connection.autoCommit = true
                    }
                },
            )
            assertFalse(body)
            assertTrue(observed != null)
            assertEquals(before, f.base.state())
            f.assertReleased()
        }
    }

    @Test
    fun `settings response consumes the same fence budget and expired settings never execute the try`() {
        val clock = FenceProbeClock()
        withFixture(clock) { f ->
            val before = f.base.state()
            var selectedPid = 0
            var fence: Any? = null
            clock.at("SETTINGS_RETURNED") { _, current ->
                fence = current
                val selected = selectedHolder(f).connection
                selectedPid = identity(selected).first
                val limits = currentLimits(selected)
                assertTrue(millis(limits[1]) in 1L..75L)
                assertTrue(millis(limits[2]) in 1L..75L)
                assertTrue(selected.networkTimeout in 1..75)
                assertFalse(sharedFenceHeld(f, selectedPid))
                clock.advanceFenceExpiry()
            }
            val failure = assertThrows<PersistencePhaseException> { runPrefix(f, clock) { error("Expired settings reached downstream work") } }
            assertEquals(PersistencePhaseFailureCode.TIME_BUDGET_EXHAUSTED, failure.code)
            assertRollback(failure)
            assertNull(ownedCutField(checkNotNull(fence), "observedLock"))
            assertFalse(sharedFenceHeld(f, selectedPid))
            assertEquals(before, f.base.state())
            f.assertReleased()
        }
    }

    @Test
    fun `actually acquired late true fence is refused and the same rollback releases its real PostgreSQL lock`() {
        val clock = FenceProbeClock()
        withFixture(clock) { f ->
            val before = f.base.state()
            var lease: PersistenceJdbcLease? = null
            var selectedPid = 0
            clock.at("OBSERVED") { _, fence ->
                assertEquals(true, ownedCutField(fence, "observedLock"))
                val holder = selectedHolder(f)
                lease = ownedPoolLease(holder.connection)
                selectedPid = identity(holder.connection).first
                assertTrue(sharedFenceHeld(f, selectedPid))
                assertFalse(checkNotNull(lease).completion.quiescent())
                assertEquals(1, f.admission.activeOwners().totalOwners)
                clock.advanceFenceExpiry()
            }
            val failure = assertThrows<PersistencePhaseException> { runPrefix(f, clock) { error("Late true reached downstream work") } }
            assertEquals(PersistencePhaseFailureCode.TIME_BUDGET_EXHAUSTED, failure.code)
            assertRollback(failure)
            assertTrue(checkNotNull(lease).completion.quiescent())
            assertFalse(sharedFenceHeld(f, selectedPid))
            assertEquals(before, f.base.state())
            f.assertReleased()
        }
    }

    @Test
    fun `interruption after real fence settings preserves the flag refuses business and completes owned rollback`() {
        val clock = FenceProbeClock()
        val interruptedOnEntry = Thread.currentThread().isInterrupted
        try {
            withFixture(clock) { f ->
                val before = f.base.state()
                var observedFence: Any? = null
                clock.at("SETTINGS_RETURNED") { _, fence ->
                    observedFence = fence
                    Thread.currentThread().interrupt()
                }
                val failure = assertThrows<PersistencePhaseException> { runPrefix(f, clock) { error("Interrupted fence reached business") } }
                assertEquals(PersistencePhaseFailureCode.INTERRUPTED, failure.code)
                assertRollback(failure)
                assertTrue(Thread.currentThread().isInterrupted)
                Thread.interrupted() // Only this test's own interruption; do not pass it into fixture observer/teardown work.
                assertNull(ownedCutField(checkNotNull(observedFence), "observedLock"))
                assertEquals(before, f.base.state())
                f.assertReleased()
            }
        } finally {
            Thread.interrupted()
            if (interruptedOnEntry) Thread.currentThread().interrupt()
        }
    }

    @Test
    fun `missing selected holder between settings and try cannot repair borrow or commit after the caught refusal`() {
        val clock = FenceProbeClock()
        withFixture(clock) { f ->
            val before = f.base.state()
            var original: ConnectionHolder? = null
            var observedFence: Any? = null
            var failedPhase: PersistencePhaseContext? = null
            clock.at("SETTINGS_RETURNED") { phase, fence ->
                original = selectedHolder(f)
                observedFence = fence
                failedPhase = phase
                assertSame(original, TransactionSynchronizationManager.unbindResource(f.pool))
            }
            val failure = assertThrows<PersistencePhaseException> {
                runPrefix(f, clock, beforeFinish = {
                    assertFalse(TransactionSynchronizationManager.hasResource(f.pool))
                    assertThrows<PersistencePhaseException> { checkNotNull(failedPhase).commit() }
                    // Restore only the original test-unbound holder so Spring can perform its genuine rollback/cleanup.
                    TransactionSynchronizationManager.bindResource(f.pool, checkNotNull(original))
                }) { error("Missing holder reached business") }
            }
            assertEquals(PersistencePhaseFailureCode.RESOURCE_REFUSED, failure.code)
            assertRollback(failure)
            assertNull(ownedCutField(checkNotNull(observedFence), "observedLock"))
            assertEquals(before, f.base.state())
            assertEquals(0L, f.base.ordinary.ownedPool.lifecycle.activeAcquisitions())
            f.assertReleased()
        }
    }

    private fun withFixture(test: (DeletionComplaintAuditFixture) -> Unit) = withDeletionComplaintAudit(database.value, test)

    private fun withFixture(clock: FenceProbeClock, test: (DeletionComplaintAuditFixture) -> Unit) = withDeletionComplaintAudit(database.value, clock, test)

    /** Test-only composition around the real named owner; no production lambda or alternate holder is installed. */
    private fun runPrefix(
        f: DeletionComplaintAuditFixture,
        clock: FenceProbeClock? = null,
        beforeFinish: () -> Unit = {},
        body: () -> Unit,
    ): PersistencePhaseContext {
        val phase = f.ownership.enterComplaintDeletionFencePrefix()
        var problem: Throwable? = null
        try {
            phase.begin()
            f.checkpoint(DeletionAuditStep.BEGIN)
            body()
            phase.commit()
        } catch (failure: Throwable) {
            problem = failure
            phase.recordFailure(failure)
        } finally {
            try {
                beforeFinish()
            } finally {
                phase.finish()
            }
        }
        clock?.requireHealthy()
        if (problem is AssertionError) throw problem
        if (problem != null) throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
        assertEquals(PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome())
        assertNull((ownedCutField(phase, "failure") as AtomicReference<*>).get())
        f.assertReleased()
        return phase
    }

    private fun selectedHolder(f: DeletionComplaintAuditFixture): ConnectionHolder = TransactionSynchronizationManager.getResource(f.pool) as ConnectionHolder

    private fun identity(connection: Connection): Pair<Int, Long> = connection.prepareStatement("SELECT pg_backend_pid(), txid_current()").use { statement ->
        statement.executeQuery().use { result ->
            assertTrue(result.next())
            (result.getInt(1) to result.getLong(2)).also { assertFalse(result.next()) }
        }
    }

    private fun currentLimits(connection: Connection): List<String> = connection.prepareStatement(
        "SELECT current_setting('transaction_timeout'), current_setting('statement_timeout'), current_setting('lock_timeout')",
    ).use { statement ->
        statement.executeQuery().use { result ->
            assertTrue(result.next())
            List(3) { result.getString(it + 1) }.also { assertFalse(result.next()) }
        }
    }

    private fun sharedFenceHeld(f: DeletionComplaintAuditFixture, pid: Int): Boolean = checkNotNull(
        f.base.observer.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM pg_locks WHERE pid = ? AND locktype = 'advisory' AND mode = 'ShareLock' AND granted)",
            Boolean::class.java,
            pid,
        ),
    )

    private fun millis(value: String): Long = when {
        value.endsWith("ms") -> value.removeSuffix("ms").toLong()
        value.endsWith("s") -> value.removeSuffix("s").toLong() * 1_000
        else -> error("Unexpected synthetic timeout unit")
    }

    private fun assertRollback(failure: PersistencePhaseException) {
        assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, failure.databaseOutcome)
        assertTrue(failure.cleanupProven)
    }

    companion object {
        private const val EXCLUSIVE_FENCE = "SELECT pg_advisory_xact_lock(hashtextextended('complaint-journal-epoch', 0))"
    }
}

/** Read-only private-stage observation; only the injected monotonic clock changes, never SQL/results/driver bytes. */
private class FenceProbeClock : PersistenceNanoClock {
    private val actions = mutableMapOf<String, (PersistencePhaseContext, Any) -> Unit>()
    private var advance = 0L
    private var problem: Throwable? = null

    fun at(stage: String, action: (PersistencePhaseContext, Any) -> Unit) {
        check(actions.put(stage, action) == null)
    }

    fun advanceFenceExpiry() {
        advance += 100_000_000L
    }

    override fun nanoTime(): Long {
        val phase = PersistencePhaseOwnership.current()
        val holder = phase?.let { ownedCutField(it, "selectedHolder") }
        val fence = holder?.let { ownedCutField(it, "deletionFence") }
        if (phase != null && fence != null) {
            val stage = ownedCutField(fence, "stage").toString()
            val action = actions.remove(stage) // Remove before observation SQL samples this same injected clock.
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
        problem?.let { throw it } // A production sanitizer cannot turn a failed test observation into an expected refusal.
        assertTrue(actions.isEmpty(), "The actual fixed prefix did not reach its required observation point")
    }
}
