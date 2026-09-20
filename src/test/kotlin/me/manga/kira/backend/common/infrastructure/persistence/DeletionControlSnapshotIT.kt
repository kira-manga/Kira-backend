package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/** Real locks and private observations only; synthetic open rows never establish W04 writer/catalog authority. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class DeletionControlSnapshotIT {
    private val database = lazy { PgLifecycleDatabaseFixture(DeletionControlSnapshotIT::class.java).also { it.start() } }

    @AfterAll
    fun closeDatabase() {
        if (database.isInitialized()) database.value.close()
    }

    @Test
    fun `global lock precedes exact scope and both snapshots retain the same deletion holder and transaction`() {
        val probe = ControlSnapshotProbeClock()
        withFixture(probe) { f ->
            withOpenControls(f) {
                val before = f.base.state()
                val controlsBefore = controlRows(f)
                var original: ConnectionHolder? = null
                var first: Pair<Int, Long>? = null
                probe.at("GLOBAL_RETURNED") { _, snapshot ->
                    original = selectedHolder(f)
                    first = identity(checkNotNull(original).connection)
                    assertNull(ownedCutField(snapshot, "exact"))
                    assertEquals(listOf(false, true), lockAvailability(f, checkNotNull(first).first))
                    assertNormalLimits(checkNotNull(original).connection)
                    assertEquals(setOf(f.pool), TransactionSynchronizationManager.getResourceMap().keys)
                }
                probe.at("EXACT_RETURNED") { _, _ ->
                    assertSame(original, selectedHolder(f))
                    assertEquals(first, identity(selectedHolder(f).connection))
                    assertEquals(listOf(false, false), lockAvailability(f))
                }
                val phase = runSnapshot(f, probe = probe) {
                    val observed = controlSnapshot(checkNotNull(PersistencePhaseOwnership.current()))
                    val global = checkNotNull(ownedCutField(observed, "global"))
                    val exact = checkNotNull(ownedCutField(observed, "exact"))
                    assertEquals(11L, ownedCutField(global, "publicationEpoch"))
                    assertEquals(12L, ownedCutField(exact, "publicationEpoch"))
                    assertNotEquals(ownedCutField(global, "eventWriterGeneration"), ownedCutField(exact, "eventWriterGeneration"))
                    assertEquals("PersistenceDeletionControlSnapshot(redacted)", observed.toString())
                    assertEquals("DeletionControlRow(redacted)", global.toString())
                    assertEquals(true, ownedCutField(global, "creationClosed"))
                    assertEquals(first, f.observations.single().second.identity)
                    assertSame(ownedPoolLease(checkNotNull(original).connection), f.observations.single().second.lease)
                    assertEquals(0, f.jdbc.counterLocks)
                    assertEquals(0L, f.base.ordinary.ownedPool.lifecycle.activeAcquisitions())
                }
                assertEquals(PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome())
                assertEquals(listOf(true, true), lockAvailability(f))
                assertEquals(controlsBefore, controlRows(f))
                assertEquals(before, f.base.state())
            }
        }
    }

    @Test
    fun `live snapshot locks only global and cannot authorize the existing deletion mutation even after caught refusal`() = withFixture { f ->
        withOpenControls(f) {
            val before = f.base.state()
            val failure = assertThrows<PersistencePhaseException> {
                runSnapshot(f, scope = ComplaintDataScope.LIVE) {
                    val snapshot = controlSnapshot(checkNotNull(PersistencePhaseOwnership.current()))
                    assertEquals("SNAPSHOT_RETAINED", ownedCutField(snapshot, "stage").toString())
                    assertNull(ownedCutField(snapshot, "exact"))
                    assertEquals(listOf(false, true), lockAvailability(f))
                    assertThrows<PersistencePhaseException> { f.prepare() }
                }
            }
            assertRollback(failure, PersistencePhaseFailureCode.RESOURCE_REFUSED)
            assertEquals(0, f.jdbc.counterLocks)
            assertEquals(before, f.base.state())
            assertEquals(listOf(true, true), lockAvailability(f))
        }
    }

    @Test
    fun `unchanged seeded closed global refuses before exact control or downstream work`() = withFixture { f ->
        val before = f.base.state()
        val controlsBefore = controlRows(f)
        var body = false
        val failure = assertThrows<PersistencePhaseException> {
            runSnapshot(f, beforeFinish = { phase ->
                val snapshot = controlSnapshot(phase)
                val global = checkNotNull(ownedCutField(snapshot, "global"))
                assertEquals(true, ownedCutField(global, "maintenanceClosed"))
                assertEquals(true, ownedCutField(global, "scanRequested"))
                assertNull(ownedCutField(global, "eventWriterGeneration"))
                assertNull(ownedCutField(snapshot, "exact"))
                assertThrows<PersistencePhaseException> { phase.commit() }
            }) { body = true }
        }
        assertRollback(failure, PersistencePhaseFailureCode.ENTRY_REFUSED)
        assertFalse(body)
        assertEquals(controlsBefore, controlRows(f))
        assertEquals(before, f.base.state())
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `missing global or exact control refuses without repair and caught begin cannot commit`(exactMissing: Boolean) = withFixture { f ->
        withOpenControls(f) {
            val missing = if (exactMissing) f.base.scope else ComplaintDataScope.LIVE
            assertEquals(1, f.base.observer.update("DELETE FROM complaint_journal_control WHERE data_scope_id = ?", missing.id))
            val before = f.base.state()
            val controlsBefore = controlRows(f)
            var body = false
            val failure = assertThrows<PersistencePhaseException> {
                runSnapshot(f, beforeFinish = { phase ->
                    val snapshot = controlSnapshot(phase)
                    assertEquals(exactMissing, ownedCutField(snapshot, "global") != null)
                    assertNull(ownedCutField(snapshot, "exact"))
                    assertThrows<PersistencePhaseException> { phase.commit() }
                }) { body = true }
            }
            assertRollback(failure, PersistencePhaseFailureCode.ENTRY_REFUSED)
            assertFalse(body)
            assertEquals(controlsBefore, controlRows(f))
            assertEquals(before, f.base.state())
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["global-maintenance", "global-scan", "exact-maintenance", "exact-scan"])
    fun `maintenance and scan refusal is enforced in either locked control`(selected: String) = withFixture { f ->
        withOpenControls(f) {
            val exact = selected.startsWith("exact-")
            val scope = if (exact) f.base.scope else ComplaintDataScope.LIVE
            val column = if (selected.endsWith("-scan")) "scan_requested" else "maintenance_closed"
            assertEquals(1, f.base.observer.update("UPDATE complaint_journal_control SET $column = true WHERE data_scope_id = ?", scope.id))
            val before = f.base.state()
            val controlsBefore = controlRows(f)
            var body = false
            val failure = assertThrows<PersistencePhaseException> {
                runSnapshot(f, beforeFinish = { phase ->
                    val snapshot = controlSnapshot(phase)
                    assertEquals(exact, ownedCutField(snapshot, "exact") != null)
                    assertEquals("FAILED", ownedCutField(snapshot, "stage").toString())
                    assertThrows<PersistencePhaseException> { phase.commit() }
                }) { body = true }
            }
            assertRollback(failure, PersistencePhaseFailureCode.ENTRY_REFUSED)
            assertFalse(body)
            assertEquals(controlsBefore, controlRows(f))
            assertEquals(before, f.base.state())
        }
    }

    @Test
    fun `missing original holder after global lock refuses before exact even if failure is caught`() {
        val probe = ControlSnapshotProbeClock()
        withFixture(probe) { f ->
            withOpenControls(f) {
                val before = f.base.state()
                var original: ConnectionHolder? = null
                probe.at("GLOBAL_RETURNED") { _, _ ->
                    original = selectedHolder(f)
                    assertSame(original, TransactionSynchronizationManager.unbindResource(f.pool))
                }
                val failure = assertThrows<PersistencePhaseException> {
                    runSnapshot(f, probe = probe, beforeFinish = { phase ->
                        assertFalse(TransactionSynchronizationManager.hasResource(f.pool))
                        assertNull(ownedCutField(controlSnapshot(phase), "exact"))
                        assertThrows<PersistencePhaseException> { phase.commit() }
                        // Restore only the exact test-unbound holder so the original finalizer can roll back.
                        TransactionSynchronizationManager.bindResource(f.pool, checkNotNull(original))
                    }) { error("Missing holder reached snapshot body") }
                }
                assertRollback(failure, PersistencePhaseFailureCode.RESOURCE_REFUSED)
                assertEquals(0L, f.base.ordinary.ownedPool.lifecycle.activeAcquisitions())
                assertEquals(listOf(true, true), lockAvailability(f))
                assertEquals(before, f.base.state())
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `real global or exact control contention stops the snapshot and retires its original lease`(exact: Boolean) {
        val probe = ControlSnapshotProbeClock()
        withFixture(probe) { f ->
            withOpenControls(f) {
                val before = f.base.state()
                val controlsBefore = controlRows(f)
                val target = AtomicInteger()
                var observed: ControlLeaseObservation? = null
                var native: ControlNativeFailureObservation? = null
                var failure: PersistencePhaseException? = null
                var body = false
                try {
                    withContendedControl(f, if (exact) f.base.scope else ComplaintDataScope.LIVE, target) { start ->
                        probe.at(if (exact) "EXACT_LOCKING" else "GLOBAL_LOCKING") { phase, snapshot ->
                            assertEquals(exact, ownedCutField(snapshot, "global") != null)
                            val selected = observeControlLease(f, phase)
                            observed = selected
                            native = ControlNativeFailureObservation(selected.lease)
                            target.set(selected.pid)
                            start.release()
                        }
                        failure = assertThrows<PersistencePhaseException> {
                            runSnapshot(f, probe = probe, beforeFinish = { assertStoppedBeforeExact(it, exact) }) { body = true }
                        }
                        probe.requireHealthy()
                    }
                    // Both the independent PostgreSQL wait and the actual failed native query must have happened.
                    checkNotNull(native).requireFailedNativeCall()
                    assertRollback(checkNotNull(failure), PersistencePhaseFailureCode.WORK_FAILED)
                    assertFalse(body)
                    assertDisposedControlLease(f, checkNotNull(observed), checkNotNull(failure), if (exact) "exact-contention" else "global-contention")
                    assertEquals(controlsBefore, controlRows(f))
                    assertEquals(before, f.base.state())
                } finally {
                    native?.close()
                }
            }
        }
    }

    @Test
    fun `interruption during real exact control contention preserves the flag and retires its original lease`() {
        val ownerThread = Thread.currentThread()
        val interruptedOnEntry = Thread.interrupted()
        try {
            val probe = ControlSnapshotProbeClock()
            withFixture(probe) { f ->
                withOpenControls(f) {
                    val before = f.base.state()
                    val controlsBefore = controlRows(f)
                    val target = AtomicInteger()
                    var observed: ControlLeaseObservation? = null
                    var native: ControlNativeFailureObservation? = null
                    var failure: PersistencePhaseException? = null
                    var body = false
                    try {
                        withContendedControl(f, f.base.scope, target, onBlocked = { ownerThread.interrupt() }) { start ->
                            probe.at("EXACT_LOCKING") { phase, snapshot ->
                                assertSame(ownerThread, Thread.currentThread())
                                assertTrue(ownedCutField(snapshot, "global") != null)
                                assertNull(ownedCutField(snapshot, "exact"))
                                val selected = observeControlLease(f, phase)
                                observed = selected
                                native = ControlNativeFailureObservation(selected.lease)
                                target.set(selected.pid)
                                start.release()
                            }
                            failure = assertThrows<PersistencePhaseException> {
                                runSnapshot(f, probe = probe, beforeFinish = { assertStoppedBeforeExact(it, true) }) { body = true }
                            }
                            probe.requireHealthy()
                        }
                        // Real PostgreSQL blocking precedes the interrupt; no claim that the flag preempts native SQL.
                        checkNotNull(native).requireFailedNativeCall(requireInterrupted = true)
                        assertRollback(checkNotNull(failure), PersistencePhaseFailureCode.WORK_FAILED)
                        assertFalse(body)
                        assertTrue(ownerThread.isInterrupted)
                        Thread.interrupted() // Clear only this test's injected flag before independent observer SQL.
                        assertDisposedControlLease(f, checkNotNull(observed), checkNotNull(failure), "exact-contention-interrupted")
                        assertEquals(controlsBefore, controlRows(f))
                        assertEquals(before, f.base.state())
                    } finally {
                        // Do not leak the test flag into withOpenControls observer/teardown SQL on failure either.
                        Thread.interrupted()
                        native?.close()
                    }
                }
            }
        } finally {
            if (interruptedOnEntry) ownerThread.interrupt()
        }
    }

    @Test
    fun `real server loss at exact control stops the native read and releases the retained global lock`() {
        val probe = ControlSnapshotProbeClock()
        withFixture(probe) { f ->
            withOpenControls(f) {
                val before = f.base.state()
                val controlsBefore = controlRows(f)
                var observed: ControlLeaseObservation? = null
                var native: ControlNativeFailureObservation? = null
                var body = false
                try {
                    probe.at("EXACT_LOCKING") { phase, snapshot ->
                        assertTrue(ownedCutField(snapshot, "global") != null)
                        assertNull(ownedCutField(snapshot, "exact"))
                        val selected = observeControlLease(f, phase)
                        observed = selected
                        native = ControlNativeFailureObservation(selected.lease)
                        OwnedCallerTestScope().use { callers ->
                            callers.launch {
                                requireConnectionFree()
                                f.base.ordinary.terminateSession(selected.pid)
                                requireConnectionFree()
                            }.value()
                        }
                        assertEquals(setOf(f.pool), TransactionSynchronizationManager.getResourceMap().keys)
                    }
                    val failure = assertThrows<PersistencePhaseException> {
                        runSnapshot(f, probe = probe, beforeFinish = { assertStoppedBeforeExact(it, true) }) { body = true }
                    }
                    probe.requireHealthy()
                    checkNotNull(native).requireFailedNativeCall()
                    assertFalse(body)
                    assertEquals(PersistencePhaseFailureCode.WORK_FAILED, failure.code)
                    assertDisposedControlLease(f, checkNotNull(observed), failure, "exact-server-loss")
                    assertEquals(controlsBefore, controlRows(f))
                    assertEquals(before, f.base.state())
                    // A genuine server/connection loss is covered here, not an unobserved TCP response stall.
                } finally {
                    native?.close()
                }
            }
        }
    }

    @Test
    fun `real warmed exact control response stall ends its native read and retires the original lease`() {
        val probe = ControlSnapshotProbeClock()
        val warm = PgLifecycleDatabaseWarmControl()
        val case = PgLifecycleDatabaseCase(PgLifecycleDatabaseRecipe.DEFAULT, 0, PgLifecycleDatabaseLane.ORDINARY)
        PgLifecycleDatabaseRelay(database.value, warm.application, case, warm).use { relay ->
            PgLifecycleDatabaseDiagnostics.preservingFailure(
                diagnostic = { println("DELETION_CONTROL_RELAY observation=PRE_RELAY_CLEANUP_MIXED ${relay.diagnostic(-1)}") },
            ) {
                relay.start()
                val endpoint = PgLifecycleDatabaseSettings.endpoint(case, relay.port, warm.application)
                withDeletionComplaintAudit(database.value, probe, endpoint) { f ->
                    database.value.observer().use { observer ->
                        withOpenControls(f) { assertStalledControl(f, probe, relay, warm, observer) }
                    }
                }
            }
        }
    }

    @Test
    fun `real lost COMMIT response preserves committed fixture rows and unknown caller outcome`() = LostCommitResponseCase().run()

    @Test
    fun `real rollback failure after captured controls refuses unsafe return and native auto commit reset`() = withFixture { f ->
        withOpenControls(f) {
            val before = f.base.state()
            val controlsBefore = controlRows(f)
            var observed: ControlLeaseObservation? = null
            var native: ControlNativeFailureObservation? = null
            try {
                val terminateBeforeRollback: (PersistencePhaseContext) -> Unit = { phase ->
                    val selected = checkNotNull(observed)
                    assertSame(selected.phase, phase)
                    assertEquals(PersistenceDatabaseOutcome.NONE, phase.databaseOutcome())
                    assertFalse(selected.lease.state.context.transaction.clean())
                    assertThrows<PersistencePhaseException> { phase.commit() }
                    native = ControlNativeFailureObservation(selected.lease, rollback = true)
                    f.base.ordinary.terminateSession(selected.pid)
                }
                val failure = assertThrows<PersistencePhaseException> {
                    runSnapshot(f, beforeFinish = terminateBeforeRollback) {
                        val phase = checkNotNull(PersistencePhaseOwnership.current())
                        observed = observeControlLease(f, phase)
                        assertEquals(listOf(false, false), lockAvailability(f))
                        // Explicit work refusal selects the real finalizer; only the remote fault fails native rollback.
                        phase.recordFailure(PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED))
                    }
                }
                checkNotNull(native).requireFailedNativeCall()
                val selected = checkNotNull(observed)
                assertEquals(PersistencePhaseFailureCode.ENTRY_REFUSED, failure.code)
                assertEquals(PersistenceDatabaseOutcome.UNKNOWN, failure.databaseOutcome)
                assertEquals(false, ownedCutField(selected.raw, "autoCommit"))
                assertDisposedControlLease(f, selected, failure, "rollback-server-loss")
                assertEquals(controlsBefore, controlRows(f))
                assertEquals(before, f.base.state())
            } finally {
                native?.close()
            }
        }
    }

    private fun assertStalledControl(
        f: DeletionComplaintAuditFixture,
        probe: ControlSnapshotProbeClock,
        relay: PgLifecycleDatabaseRelay,
        warm: PgLifecycleDatabaseWarmControl,
        observer: PgLifecycleDatabaseObserver,
    ) {
        val before = f.base.state()
        val controlsBefore = controlRows(f)
        val selected = AtomicReference<ControlLeaseObservation?>()
        val native = AtomicReference<ControlNativeFailureObservation?>()
        var body = false
        OwnedCallerTestScope().use { callers ->
            val start = callers.gate()
            val witness = callers.launch {
                start.hold()
                requireConnectionFree()
                val observed = checkNotNull(selected.get())
                val pending = checkNotNull(native.get())
                warm.awaitHeld(PgLifecycleDatabaseDeadline(2_000), relay::progress)
                assertEquals(observed.pid, warm.requireHeld().backendPid.get())
                pending.requirePendingNativeCall()
                observer.requireHeldControl(warm.application, observed.session)
                assertEquals(listOf(false, false), listOf(ComplaintDataScope.LIVE, f.base.scope).map { rowLockAvailable(f, it) })
                observer.requireHeldControl(warm.application, observed.session)
                pending.requirePendingNativeCall()
                warm.witnessed()
                requireConnectionFree()
                true
            }
            try {
                start.awaitEntered() // The observer actor and direct observer connection predate the original work budget.
                probe.at("EXACT_LOCKING") { phase, snapshot ->
                    assertTrue(ownedCutField(snapshot, "global") != null)
                    assertNull(ownedCutField(snapshot, "exact"))
                    val observed = observeControlLease(f, phase)
                    selected.set(observed)
                    native.set(ControlNativeFailureObservation(observed.lease))
                    warm.arm(relay.warmedSession(observed.pid), f.base.scope.id)
                    start.release()
                }
                val failure = assertThrows<PersistencePhaseException> {
                    runSnapshot(f, probe = probe, beforeFinish = { assertStoppedBeforeExact(it, true) }) { body = true }
                }
                assertTrue(witness.value())
                probe.requireHealthy()
                checkNotNull(native.get()).requireFailedNativeCall()
                assertFalse(body)
                assertEquals(PersistencePhaseFailureCode.WORK_FAILED, failure.code)
                val ended = warm.requireStopped()
                val observed = checkNotNull(selected.get())
                relay.awaitClientDisposal(ended.ordinal, PgLifecycleDatabaseDeadline(1_000)) {}
                assertDisposedControlLease(f, observed, failure, "exact-warmed-response-stall")
                assertEquals(controlsBefore, controlRows(f))
                assertEquals(before, f.base.state())
            } finally {
                start.release()
                try {
                    witness.awaitExit() // A failing test still joins the observer before restoring the caller's passive TL.
                } finally {
                    native.get()?.close()
                }
            }
        }
    }

    /** One existing owned caller holds the real row and independently witnesses its PID blocking the selected lease. */
    private fun withContendedControl(
        f: DeletionComplaintAuditFixture,
        scope: ComplaintDataScope,
        target: AtomicInteger,
        onBlocked: () -> Unit = {},
        work: (OwnedCallerTestGate) -> Unit,
    ) = OwnedCallerTestScope().use { callers ->
        val start = callers.gate()
        val release = callers.gate()
        val locker = callers.launch {
            requireConnectionFree()
            val blocked = f.base.observer.execute(
                ConnectionCallback<Boolean> { connection -> holdContendedControl(connection, scope, target, start, release, onBlocked) },
            )
            requireConnectionFree()
            checkNotNull(blocked)
        }
        try {
            start.awaitEntered()
            work(start)
        } finally {
            start.release()
            release.release()
        }
        assertTrue(locker.value(), "PostgreSQL must witness the real selected control-row lock wait, not merely a setup refusal")
    }

    private fun observeControlLease(f: DeletionComplaintAuditFixture, phase: PersistencePhaseContext): ControlLeaseObservation {
        val holder = selectedHolder(f)
        val lease = ownedPoolLease(holder.connection)
        val work = ownedCutField(phase, "work") as PersistenceTimeBudget
        val physical = f.base.ordinary.ownedPool.scope.entries(deletion = true).single { it.jdbc.currentPoolState(lease.state) }
        val pid = identity(holder.connection).first
        // A JdbcTemplate on this synchronized caller would bind a second holder and invalidate the control prefix.
        val session = OwnedCallerTestScope().use { callers ->
            callers.launch {
                requireConnectionFree()
                val actual = checkNotNull(f.base.ordinary.session(pid))
                assertTrue(actual.inTransaction)
                requireConnectionFree()
                PgLifecycleDatabaseSession(pid, actual.backendStart)
            }.value()
        }
        assertSame(holder, selectedHolder(f))
        assertEquals(setOf(f.pool), TransactionSynchronizationManager.getResourceMap().keys)
        assertNormalLimits(holder.connection)
        return ControlLeaseObservation(phase, work, lease, physical, checkNotNull(physical.raw.get()), session)
    }

    private fun assertStoppedBeforeExact(phase: PersistencePhaseContext, globalRetained: Boolean) {
        val snapshot = controlSnapshot(phase)
        assertEquals("FAILED", ownedCutField(snapshot, "stage").toString())
        assertEquals(globalRetained, ownedCutField(snapshot, "global") != null)
        assertNull(ownedCutField(snapshot, "exact"))
        assertThrows<PersistencePhaseException> { phase.commit() }
    }

    /** Exact custody plus independent database facts; neither a Future nor isClosed supplies this receipt. */
    private fun assertDisposedControlLease(
        f: DeletionComplaintAuditFixture,
        observed: ControlLeaseObservation,
        failure: PersistencePhaseException,
        case: String,
    ) {
        assertNotEquals(PersistenceDatabaseOutcome.COMMITTED, failure.databaseOutcome)
        assertEquals(failure.databaseOutcome, observed.lease.completion.databaseOutcome())
        assertTrue(failure.cleanupProven && observed.lease.completion.quiescent())
        assertSame(observed.phase, observed.lease.completion.phase)
        assertSame(observed.work, ownedCutField(observed.phase, "work"))
        assertEquals(observed.acceptedAtNanos, ownedCutField(observed.work, "startedAtNanos"))
        assertEquals(2_000_000_000L, ownedCutField(observed.work, "allowanceNanos"))
        val transfer = ownedCutField(observed.lease, "transfer") as PersistenceJdbcPoolTransfer
        assertSame(observed.lease.state, transfer.source)
        assertTrue(transfer.actualEnded())
        assertFalse(transfer.consented())
        assertTrue(observed.physical.retirementRequested.get())
        assertTrue(f.base.ordinary.ownedPool.scope.entries(deletion = true).none { it === observed.physical })
        assertNull(f.base.ordinary.session(observed.pid))
        assertEquals(listOf(true, true), lockAvailability(f))
        f.base.observer.execute(
            ConnectionCallback<Unit> { connection ->
                connection.autoCommit = false
                try {
                    connection.prepareStatement("SELECT pg_try_advisory_xact_lock(hashtextextended('complaint-journal-epoch', 0))").use { statement ->
                        statement.executeQuery().use { result ->
                            assertTrue(result.next())
                            assertTrue(result.getBoolean(1))
                            assertFalse(result.next())
                        }
                    }
                } finally {
                    connection.rollback()
                    connection.autoCommit = true
                }
            },
        )
        assertEquals(0, f.jdbc.counterLocks)
        assertEquals(0L, f.base.ordinary.ownedPool.lifecycle.activeAcquisitions())
        f.assertReleased()
        val elapsed = System.nanoTime() - observed.acceptedAtNanos
        println("DELETION_CONTROL_LIFECYCLE case=$case accepted_to_disposition_nanos=$elapsed db=${failure.databaseOutcome} cleanup_proven=true")
        assertTrue(elapsed in 0..3_000_000_000L, "Accepted lease through real session/locks/native RETURN end must fit 2s work plus 1s cleanup")
    }

    private fun withFixture(test: (DeletionComplaintAuditFixture) -> Unit) = withDeletionComplaintAudit(database.value, test)

    private fun withFixture(probe: ControlSnapshotProbeClock, test: (DeletionComplaintAuditFixture) -> Unit) =
        withDeletionComplaintAudit(database.value, probe, test)

    /** Reuses the existing fixture/finalizer. No production callback or result capability is introduced. */
    private fun runSnapshot(
        f: DeletionComplaintAuditFixture,
        scope: ComplaintDataScope = f.base.scope,
        probe: ControlSnapshotProbeClock? = null,
        beforeFinish: (PersistencePhaseContext) -> Unit = {},
        body: () -> Unit,
    ): PersistencePhaseContext {
        val phase = f.ownership.enterComplaintDeletionControlSnapshot(scope)
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
                beforeFinish(phase)
            } finally {
                phase.finish()
            }
        }
        probe?.requireHealthy()
        f.assertReleased()
        if (problem is AssertionError) throw problem
        if (problem != null) throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
        assertEquals(PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome())
        assertNull((ownedCutField(phase, "failure") as AtomicReference<*>).get())
        return phase
    }

    private fun selectedHolder(f: DeletionComplaintAuditFixture): ConnectionHolder = TransactionSynchronizationManager.getResource(f.pool) as ConnectionHolder

    private fun controlSnapshot(phase: PersistencePhaseContext): Any =
        checkNotNull(ownedCutField(checkNotNull(ownedCutField(phase, "selectedHolder")), "deletionControlSnapshot"))

    private fun identity(connection: Connection): Pair<Int, Long> = connection.prepareStatement("SELECT pg_backend_pid(), txid_current()").use { statement ->
        statement.executeQuery().use { result ->
            assertTrue(result.next())
            (result.getInt(1) to result.getLong(2)).also { assertFalse(result.next()) }
        }
    }

    private fun assertNormalLimits(connection: Connection) {
        connection.prepareStatement("SELECT current_setting('statement_timeout'), current_setting('lock_timeout')").use { statement ->
            statement.executeQuery().use { result ->
                assertTrue(result.next())
                assertEquals("1s", result.getString(1))
                assertEquals("100ms", result.getString(2))
                assertFalse(result.next())
            }
        }
        assertEquals(1_000, connection.networkTimeout)
    }

    private fun assertRollback(failure: PersistencePhaseException, code: PersistencePhaseFailureCode) {
        assertEquals(code, failure.code)
        assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, failure.databaseOutcome)
        assertTrue(failure.cleanupProven)
    }

    /** One lost-reply case, using the existing phase/relay/observer rather than a second fixture or production authority. */
    private inner class LostCommitResponseCase {
        fun run() {
            val probe = ControlSnapshotProbeClock()
            val warm = PgLifecycleDatabaseWarmControl()
            val case = PgLifecycleDatabaseCase(PgLifecycleDatabaseRecipe.DEFAULT, 0, PgLifecycleDatabaseLane.ORDINARY)
            PgLifecycleDatabaseRelay(database.value, warm.application, case, warm).use { relay ->
                PgLifecycleDatabaseDiagnostics.preservingFailure(
                    diagnostic = { println("DELETION_COMMIT_RELAY observation=PRE_RELAY_CLEANUP_MIXED ${relay.diagnostic(-1)}") },
                ) {
                    relay.start()
                    val endpoint = PgLifecycleDatabaseSettings.endpoint(case, relay.port, warm.application)
                    withDeletionComplaintAudit(database.value, probe, endpoint) { f ->
                        database.value.observer().use { observer ->
                            withOpenControls(f) { verify(f, probe, relay, warm, observer) }
                        }
                    }
                }
            }
        }

        private fun verify(
            f: DeletionComplaintAuditFixture,
            probe: ControlSnapshotProbeClock,
            relay: PgLifecycleDatabaseRelay,
            warm: PgLifecycleDatabaseWarmControl,
            observer: PgLifecycleDatabaseObserver,
        ) {
            val before = f.base.state()
            val controlsBefore = controlRows(f)
            val selected = AtomicReference<ControlLeaseObservation?>()
            val native = AtomicReference<ControlNativeFailureObservation?>()
            OwnedCallerTestScope().use { callers ->
                val start = callers.gate()
                val witness = callers.launch {
                    start.hold()
                    requireConnectionFree()
                    val observed = checkNotNull(selected.get())
                    val pending = checkNotNull(native.get())
                    val deadline = PgLifecycleDatabaseDeadline(2_000)
                    warm.awaitHeld(deadline, relay::progress)
                    assertEquals(observed.pid, warm.requireHeld().backendPid.get())
                    observer.awaitCommittedFixture(warm.application, observed.session, f.base.resourceId, f.base.scope.id, deadline) {
                        relay.progress()
                        assertEquals(observed.pid, warm.requireHeld().backendPid.get())
                        pending.requirePendingNativeCall()
                        assertTrue(observed.lease.state.epoch.foregroundActive())
                    }
                    assertEquals(listOf(true, true), listOf(ComplaintDataScope.LIVE, f.base.scope).map { rowLockAvailable(f, it) })
                    pending.requirePendingNativeCall()
                    warm.witnessed() // The persisted read precedes both caller completion and client-origin disposal.
                    requireConnectionFree()
                    true
                }
                try {
                    start.awaitEntered() // Preopened observer and witness actor do not consume the phase's work allowance.
                    val failure = assertThrows<PersistencePhaseException> {
                        runSnapshot(f, probe = probe, beforeFinish = { phase ->
                            assertEquals(PersistenceDatabaseOutcome.UNKNOWN, phase.databaseOutcome())
                        }) {
                            val phase = checkNotNull(PersistencePhaseOwnership.current())
                            val observed = observeControlLease(f, phase)
                            selected.set(observed)
                            f.changeProtectedFixtureRows() // Existing direct synthetic SQL, not an authorized production deletion operation.
                            native.set(ControlNativeFailureObservation(observed.lease, commit = true))
                            warm.armCommit(relay.warmedSession(observed.pid)) // Only after the mutation's own reply fully returned.
                            start.release()
                        }
                    }
                    assertTrue(witness.value())
                    probe.requireHealthy()
                    checkNotNull(native.get()).requireFailedNativeCall()
                    assertEquals(PersistencePhaseFailureCode.WORK_FAILED, failure.code)
                    assertEquals(PersistenceDatabaseOutcome.UNKNOWN, failure.databaseOutcome)
                    val observed = checkNotNull(selected.get())
                    assertEquals(false, ownedCutField(observed.raw, "autoCommit"))
                    val ended = warm.requireStopped()
                    relay.awaitClientDisposal(ended.ordinal, PgLifecycleDatabaseDeadline(1_000)) {}
                    assertDisposedControlLease(f, observed, failure, "lost-commit-response")
                    assertEquals(controlsBefore, controlRows(f))
                    assertEquals(
                        listOf("IN_PROGRESS" to 2L),
                        f.base.observer.query(
                            "SELECT status, version FROM complaints WHERE id = ? AND data_scope_id = ?",
                            { result, _ -> result.getString(1) to result.getLong(2) },
                            f.base.resourceId,
                            f.base.scope.id,
                        ),
                    )
                    val after = f.base.state()
                    assertEquals(before.copy(domain = after.domain), after) // No counter, grant, receipt or audit activation.
                    f.resetProtectedFixture() // Restore only after observing the real persisted outcome and complete original custody release.
                    assertEquals(before, f.base.state())
                } finally {
                    start.release()
                    try {
                        witness.awaitExit()
                    } finally {
                        native.get()?.close()
                    }
                }
            }
        }
    }
}

/** Synthetic database rows only; restore the exact original global row, including untouched nullable fields. */
private fun withOpenControls(f: DeletionComplaintAuditFixture, test: () -> Unit) {
    val original = controlRows(f).single()
    try {
        assertEquals(
            1,
            f.base.observer.update(
                "UPDATE complaint_journal_control SET maintenance_closed = false, creation_closed = true, scan_requested = false, " +
                    "publication_epoch = 11, desired_configuration_hash = ?, database_identity = ?, restore_identity = ?, " +
                    "event_writer_generation = ?, accepted_catalog_generation = 7, accepted_catalog_hash = ?, trust_bundle_hash = ?, " +
                    "catalog_writer_generation = ? WHERE data_scope_id = ?",
                ByteArray(32) { 1 },
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                ByteArray(32) { 2 },
                ByteArray(32) { 3 },
                UUID.randomUUID(),
                ComplaintDataScope.LIVE.id,
            ),
        )
        assertEquals(
            1,
            f.base.observer.update(
                "INSERT INTO complaint_journal_control SELECT (jsonb_populate_record(NULL::complaint_journal_control, " +
                    "to_jsonb(control) || jsonb_build_object('data_scope_id', ?::uuid, 'test_only', true, 'publication_epoch', 12, " +
                    "'event_writer_generation', ?::uuid))).* FROM complaint_journal_control control WHERE data_scope_id = ?",
                f.base.scope.id,
                UUID.randomUUID(),
                ComplaintDataScope.LIVE.id,
            ),
        )
        test()
    } finally {
        f.assertReleased()
        f.base.observer.update("DELETE FROM complaint_journal_control WHERE data_scope_id IN (?, ?)", ComplaintDataScope.LIVE.id, f.base.scope.id)
        assertEquals(
            1,
            f.base.observer.update(
                "INSERT INTO complaint_journal_control SELECT (jsonb_populate_record(NULL::complaint_journal_control, ?::jsonb)).*",
                original,
            ),
        )
        assertEquals(listOf(original), controlRows(f))
    }
}

private fun controlRows(f: DeletionComplaintAuditFixture): List<String> = f.base.observer.queryForList(
    "SELECT to_jsonb(control)::text FROM complaint_journal_control control WHERE data_scope_id IN (?, ?) ORDER BY data_scope_id",
    String::class.java,
    ComplaintDataScope.LIVE.id,
    f.base.scope.id,
)

/** Independent real NOWAIT probes, isolated from the phase thread's Spring resource map. */
private fun lockAvailability(f: DeletionComplaintAuditFixture, pid: Int? = null): List<Boolean> = OwnedCallerTestScope().use { readers ->
    readers.launch {
        requireConnectionFree()
        if (pid != null) {
            assertEquals(
                true,
                f.base.observer.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM pg_locks WHERE pid = ? AND locktype = 'advisory' AND mode = 'ShareLock' AND granted)",
                    Boolean::class.java,
                    pid,
                ),
            )
        }
        val result = listOf(ComplaintDataScope.LIVE, f.base.scope).map { scope -> rowLockAvailable(f, scope) }
        requireConnectionFree()
        result
    }.value()
}

private fun rowLockAvailable(f: DeletionComplaintAuditFixture, scope: ComplaintDataScope): Boolean = checkNotNull(
    f.base.observer.execute(
        ConnectionCallback<Boolean> { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement("SELECT data_scope_id FROM complaint_journal_control WHERE data_scope_id = ? FOR UPDATE NOWAIT")
                    .use { statement ->
                        statement.setObject(1, scope.id)
                        statement.executeQuery().use { result ->
                            assertTrue(result.next())
                            assertEquals(scope.id, result.getObject(1, UUID::class.java))
                            assertFalse(result.next())
                        }
                    }
                true
            } catch (failure: SQLException) {
                assertEquals("55P03", failure.sqlState)
                false
            } finally {
                connection.rollback()
                connection.autoCommit = true
            }
        },
    ),
)

private fun holdContendedControl(
    connection: Connection,
    scope: ComplaintDataScope,
    target: AtomicInteger,
    start: OwnedCallerTestGate,
    release: OwnedCallerTestGate,
    onBlocked: () -> Unit,
): Boolean {
    connection.autoCommit = false
    try {
        val pid = connection.prepareStatement(
            "SELECT pg_backend_pid() FROM complaint_journal_control WHERE data_scope_id = ? FOR UPDATE",
        ).use { statement ->
            statement.queryTimeout = 1
            statement.setObject(1, scope.id)
            statement.executeQuery().use { result ->
                assertTrue(result.next())
                result.getInt(1).also { assertFalse(result.next()) }
            }
        }
        start.hold()
        assertTrue(target.get() > 0)
        val blocked = connection.prepareStatement("SELECT ? = ANY(pg_blocking_pids(?))").use { statement ->
            statement.queryTimeout = 1
            statement.setInt(1, pid)
            statement.setInt(2, target.get())
            val began = System.nanoTime()
            var witnessed = false
            while (!witnessed && System.nanoTime() - began < 800_000_000L) {
                witnessed = statement.executeQuery().use { result ->
                    assertTrue(result.next())
                    result.getBoolean(1).also { assertFalse(result.next()) }
                }
                if (!witnessed) LockSupport.parkNanos(1_000_000L)
            }
            witnessed
        }
        if (blocked) onBlocked()
        release.hold()
        return blocked
    } finally {
        connection.rollback()
        connection.autoCommit = true
    }
}

private class ControlLeaseObservation(
    val phase: PersistencePhaseContext,
    val work: PersistenceTimeBudget,
    val lease: PersistenceJdbcLease,
    val physical: PersistencePhysicalEntry,
    val raw: Connection,
    val session: PgLifecycleDatabaseSession,
) {
    val pid: Int get() = session.pid
    val acceptedAtNanos = ownedCutField(work, "startedAtNanos") as Long
}

/** Same passive own-project instance-TL seam as OrdinaryCommitObservation; native state and outcomes are never written. */
private class ControlNativeFailureObservation(lease: PersistenceJdbcLease, private val rollback: Boolean = false, private val commit: Boolean = false) :
    ThreadLocal<PersistenceJdbcGuardCall?>(),
    AutoCloseable {
    private val caller = Thread.currentThread()
    private val context = lease.state.context
    private val root = ownedPoolRoot(lease)
    private val field = context.javaClass.getDeclaredField("frames").apply { check(trySetAccessible()) }

    @Suppress("UNCHECKED_CAST")
    private val delegate = field.get(context) as ThreadLocal<PersistenceJdbcGuardCall?>
    private val driver = PersistenceJdbcGuardCall::class.java.getDeclaredField("driver").apply { check(trySetAccessible()) }
    private val operation = when {
        rollback -> "rollback"
        commit -> "commit"
        else -> "executeQuery"
    }
    private val expectedOutcome = if (rollback) PersistenceJdbcCallOutcome.CLEANUP_FAILURE else PersistenceJdbcCallOutcome.ORDINARY_FAILURE
    private var selected: PersistenceJdbcGuardCall? = null
    private var invocation: PersistencePgOwnedCutAccess.Invocation? = null
    private val publishedInvocation = AtomicReference<PersistencePgOwnedCutAccess.Invocation?>()
    private var additionalCall = false
    private var preparedBeforeArm = false
    private var failedWhileArmed = false
    private var interruptedWhileArmed = false
    private var autoCommitDispatched = false
    private var laterBusinessDispatched = false
    private var observationFailure: Throwable? = null

    init {
        check(!rollback || !commit)
        assertNull(delegate.get())
        field.set(context, this)
    }

    override fun get(): PersistenceJdbcGuardCall? = delegate.get().also { call ->
        if (Thread.currentThread() === caller && call != null) {
            try {
                observe(call)
            } catch (failure: Throwable) {
                observationFailure = failure // Never turn an observation error into the tested native failure.
            }
        }
    }

    // Distinguish an actual lower query/commit/rollback failure from preparation, arm or result-wrapping refusal.
    @Suppress("ComplexCondition")
    private fun observe(call: PersistenceJdbcGuardCall) {
        val native = driver.get(call) as? PersistencePgOwnedCutAccess.Invocation ?: return
        val name = ownedCutField(native.cell, "operation")
        val armed = ownedCutField(native.cell, "armed") == true
        if (name == "setAutoCommit" && armed) autoCommitDispatched = true
        if (failedWhileArmed && armed && call !== selected && ownedCutField(call, "kind") === PersistenceJdbcGuardCallKind.BUSINESS) {
            laterBusinessDispatched = true
        }
        if (name != operation) return
        if (selected == null) {
            selected = call
            invocation = native
            publishedInvocation.set(native)
        } else if (selected !== call) {
            additionalCall = true
            return
        }
        if (!armed && context.transaction.databaseOutcome() === PersistenceDatabaseOutcome.NONE) preparedBeforeArm = true
        if (armed && ownedCutField(native.cell, "ended") == false &&
            ownedCutField(call, "driverPreparationFailure") == false && ownedCutField(call, "outcome") === expectedOutcome &&
            (!(rollback || commit) || context.transaction.databaseOutcome() === PersistenceDatabaseOutcome.UNKNOWN)
        ) {
            failedWhileArmed = true
            if (caller.isInterrupted) interruptedWhileArmed = true
        }
    }

    override fun set(value: PersistenceJdbcGuardCall?) = delegate.set(value)
    override fun remove() = delegate.remove()

    /** Published exact identity and native volatile end only; real held SQL, not a foreign plain armed read, proves dispatch. */
    fun requirePendingNativeCall() {
        val native = checkNotNull(publishedInvocation.get())
        assertSame(root, native.owner.root)
        assertSame(caller, ownedCutField(native.cell, "actual"))
        assertEquals(operation, ownedCutField(native.cell, "operation"))
        assertEquals(false, ownedCutField(native.cell, "ended"))
    }

    fun requireFailedNativeCall(requireInterrupted: Boolean = false) {
        assertNull(observationFailure)
        assertFalse(additionalCall || laterBusinessDispatched)
        assertTrue(preparedBeforeArm && failedWhileArmed)
        if (requireInterrupted) assertTrue(interruptedWhileArmed, "Interrupt must be observed before the selected native failure ends")
        if (rollback || commit) assertFalse(autoCommitDispatched)
        val call = checkNotNull(selected)
        val native = checkNotNull(invocation)
        assertSame(call, native.callKey)
        assertSame(root, native.owner.root)
        assertEquals(operation, ownedCutField(native.cell, "operation"))
        assertEquals(if (rollback) PersistenceJdbcGuardCallKind.CLEANUP else PersistenceJdbcGuardCallKind.BUSINESS, ownedCutField(call, "kind"))
        assertEquals(true, ownedCutField(call, "driverArmAttempted"))
        assertEquals(false, ownedCutField(call, "driverPreparationFailure"))
        assertEquals(expectedOutcome, ownedCutField(call, "outcome"))
        assertEquals(true, ownedCutField(call, "ended"))
        assertEquals(true, ownedCutField(native.cell, "armed"))
        assertEquals(true, ownedCutField(native.cell, "disarmed"))
        assertEquals(true, ownedCutField(native.cell, "ended"))
    }

    override fun close() {
        assertSame(this, field.get(context))
        field.set(context, delegate)
        assertNull(delegate.get())
    }
}

/** Existing injected-clock observation pattern: no clock advance, fabricated row, lock result or driver replacement. */
private class ControlSnapshotProbeClock : PersistenceNanoClock {
    private val actions = mutableMapOf<String, (PersistencePhaseContext, Any) -> Unit>()
    private var problem: Throwable? = null

    fun at(stage: String, action: (PersistencePhaseContext, Any) -> Unit) {
        check(actions.put(stage, action) == null)
    }

    override fun nanoTime(): Long {
        val phase = PersistencePhaseOwnership.current()
        val holder = phase?.let { ownedCutField(it, "selectedHolder") }
        val snapshot = holder?.let { ownedCutField(it, "deletionControlSnapshot") }
        if (phase != null && snapshot != null) {
            val action = actions.remove(ownedCutField(snapshot, "stage").toString())
            if (action != null) {
                try {
                    action(phase, snapshot)
                } catch (failure: Throwable) {
                    problem = failure
                    throw failure
                }
            }
        }
        return System.nanoTime()
    }

    fun requireHealthy() {
        problem?.let { throw it }
        assertTrue(actions.isEmpty(), "The real control prefix did not reach its required observation point")
    }
}
