package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.infrastructure.persistence.OwnedCallerTestScope
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCharges
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationFailure
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationRejected
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerCreateOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerReplyParentRows
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerCreateStore
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredInitialCheckpointCreateCasesV1.refused
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/** Only negative/observational seams. Product clocks, limits, deadlines, SQL results and cleanup are never replaced. */
internal object TestRegisteredInitialCheckpointCreateRaceCasesV1 {
    fun claimLoser(f: TestRegisteredInitialCheckpointCreateFixtureV1) = claimLoser(f, desiredDrift = false)
    fun desiredIdentityClaimLoser(f: TestRegisteredInitialCheckpointCreateFixtureV1) = claimLoser(f, desiredDrift = true)

    fun replyClaimLoser(f: TestRegisteredInitialCheckpointCreateFixtureV1) {
        val attempt = registeredReplyAttempt(f.actor, f.notice())
        val before = f.counters(); val providers = f.providerCounts()
        assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> {
            JdbcComplaintOwnerCreateStore.registeredInitialCheckpointWithReplies(JdbcTemplate(f.process.pools.ordinary), f.exchange.service,
                f.exchange.ordinary.ownership, f.registration, f.checkpoint.assembly)
        }
        // Even this reply-capable full D cannot widen the older registered CREATE-only store.
        refused { f.ingress.withIngress(f.request()) { f.adapter.reply(it, f.token, attempt.input) } }
        assertEquals(before, f.counters()); assertTrue(f.replySql().isEmpty())
        val winnerThread = AtomicReference<Thread?>(); val loserPid = AtomicInteger()
        val loserEntering = CountDownLatch(1); val closedAfterClaim = AtomicBoolean()
        f.raw { observer ->
            OwnedCallerTestScope().use { callers ->
                val completedWinner = callers.gate()
                f.jdbc.before = { path, sql ->
                    if (path === REGISTERED_REPLY && sql.startsWith("INSERT INTO complaint_idempotency_receipts") &&
                        winnerThread.get() != null && Thread.currentThread() !== winnerThread.get()) {
                        loserPid.set(currentPid(f)); loserEntering.countDown()
                    }
                }
                f.jdbc.after = { path, sql -> if (path === REGISTERED_REPLY) {
                    if ("UPDATE complaint_idempotency_receipts" in sql && winnerThread.compareAndSet(null, Thread.currentThread())) completedWinner.hold()
                    if (sql.startsWith("INSERT INTO complaint_idempotency_receipts") && winnerThread.get() != null &&
                        Thread.currentThread() !== winnerThread.get() && closedAfterClaim.compareAndSet(false, true)) {
                        assertEquals(1, f.exchange.f.foreignUpdate("UPDATE complaint_journal_control SET creation_closed = true, maintenance_closed = true WHERE data_scope_id = ?", f.scope))
                    }
                } }
                val winner = callers.launch { f.reply(attempt) }
                completedWinner.awaitEntered()
                try {
                    val loser = callers.launch { f.reply(attempt) }
                    assertTrue(loserEntering.await(1, TimeUnit.SECONDS))
                    awaitActualLockWait(observer, loserPid.get())
                    completedWinner.release()
                    f.assertApplied(winner.value(), attempt); f.assertApplied(loser.value(), attempt)
                } finally { completedWinner.release() }
            }
        }
        f.jdbc.before = { _, _ -> }; f.jdbc.after = { _, _ -> }
        assertTrue(closedAfterClaim.get()); f.assertReleased()
        assertEquals(2, f.replyPhases().size)
        assertTrue(f.replyPhases().all { it.databaseOutcome() === PersistenceDatabaseOutcome.COMMITTED })
        assertEquals(7, f.replySql().count { it == TestActiveInitialCheckpointSqlV1.currentForOwnerCreate }, "Only the winner runs new-work checks, including both resources and parent content.")
        assertEquals(1, f.replySql().count { "FROM complaint_capacity_counters" in it && "FOR UPDATE" in it })
        f.assertCharge(before, ComplaintCapacityCharges.OWNER_CREATE)
        assertEquals(1L, f.observer.queryForObject("SELECT count(*) FROM complaint_idempotency_receipts WHERE actor_id = ? AND operation = 'OWNER_REPLY'", Long::class.java, f.actor.id))
        val state = f.state(); f.jdbc.calls.clear()
        f.assertApplied(f.reply(attempt), attempt); f.assertApplied(f.replyStatus(attempt), attempt)
        assertTrue(f.replySql().isEmpty()); assertEquals(state, f.state())
        assertEquals(providers, f.providerCounts()); f.assertReleased()
    }

    fun replyWaitedCheckpointExpiry(f: TestRegisteredInitialCheckpointCreateFixtureV1, resource: Boolean) {
        val deadlines = f.process.consumers.journalConfiguration.declaration().limits.deadlines
        assertEquals(30_000, deadlines.scanMillis); assertEquals(30_000, deadlines.scanCadenceMillis); assertEquals(30_000, deadlines.checkpointMaxAgeMillis)
        val first = registeredReplyAttempt(f.actor, f.notice())
        f.assertApplied(f.reply(first), first); f.assertReleased()
        val before = f.state(); val counters = f.counters(); val providers = f.providerCounts()
        val expires = (f.checkpoint.control().getValue("checkpoint_completed_at") as Timestamp).toInstant().plusMillis(deadlines.checkpointMaxAgeMillis.toLong())
        val second = registeredReplyAttempt(f.actor, first.input.id, UUID.fromString("00000000-0000-4000-8000-000000000001"))
        assertTrue(second.input.id.toString() < first.input.id.toString(), "The provisional child must precede the locked parent.")
        val entering = CountDownLatch(1); val reached = AtomicBoolean(); val pid = AtomicInteger()
        val blockedSql = if (resource) ComplaintOwnerReplyParentRows.RESOURCE else ComplaintOwnerReplyParentRows.content
        f.raw { locker ->
            locker.autoCommit = false
            try {
                val table = if (resource) "complaint_resource_ids" else "complaints"
                locker.prepareStatement("SELECT id FROM $table WHERE id = ? FOR UPDATE").use { statement ->
                    statement.queryTimeout = 1; statement.setObject(1, first.input.id)
                    statement.executeQuery().use { row -> assertTrue(row.next()); assertFalse(row.next()) }
                }
                f.raw { observer ->
                    waitUntil(observer, expires.minusMillis(800), 31_000) // No original request/admission exists during this wait.
                    f.jdbc.calls.clear()
                    OwnedCallerTestScope().use { callers ->
                        f.jdbc.before = { path, sql -> if (path === REGISTERED_REPLY && sql == blockedSql && reached.compareAndSet(false, true)) {
                            // Negative caller stall consumes the same 2s original. Then the real 100ms
                            // lock_timeout remains untouched while the actual row wait crosses expiry.
                            waitUntil(observer, expires.minusMillis(45), 900)
                            assertTrue(now(observer).isBefore(expires))
                            pid.set(currentPid(f)); entering.countDown()
                        } }
                        val original = callers.launch { f.reply(second) }
                        assertTrue(entering.await(1, TimeUnit.SECONDS))
                        awaitActualLockWait(observer, pid.get())
                        waitUntil(observer, expires.plusNanos(1000), 80)
                        locker.commit()
                        val failure = original.problem()
                        assertTrue(failure is ComplaintOwnerOperationRejected)
                        assertEquals(ComplaintOwnerOperationFailure.UNAVAILABLE, (failure as ComplaintOwnerOperationRejected).failure)
                    }
                }
            } finally { f.jdbc.before = { _, _ -> }; locker.rollback() }
        }
        assertTrue(reached.get()); f.assertReleased()
        assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, f.replyPhases().last().databaseOutcome())
        assertTrue(f.replySql().any { it.startsWith("INSERT INTO complaint_resource_ids") })
        assertEquals(if (resource) 5 else 6, f.replySql().count { it == TestActiveInitialCheckpointSqlV1.currentForOwnerCreate })
        if (resource) assertFalse(f.replySql().contains(ComplaintOwnerReplyParentRows.content))
        assertFalse(f.replySql().any { it.startsWith("INSERT INTO complaints") || "UPDATE complaint_idempotency_receipts" in it })
        assertEquals(before, f.state()); assertEquals(counters, f.counters()) // Includes provisional child, claim, capacity and audit rollback.
        f.jdbc.calls.clear()
        f.assertApplied(f.reply(first), first); f.assertApplied(f.replyStatus(first), first)
        refused(ComplaintOwnerOperationFailure.OPERATION_NOT_FOUND) { f.replyStatus(second) }
        assertTrue(f.replySql().isEmpty(), "Expired current checkpoint cannot block exact completed reply receipt reads.")
        refused { f.reply(second) }
        assertFalse(f.replySql().any { "complaint_capacity_counters" in it })
        assertEquals(before, f.state()); assertEquals(counters, f.counters())
        assertEquals(providers, f.providerCounts()); f.assertReleased()
    }

    private fun claimLoser(f: TestRegisteredInitialCheckpointCreateFixtureV1, desiredDrift: Boolean) {
        val attempt = f.attempt(); val before = f.counters(); val providers = f.providerCounts()
        val winnerThread = AtomicReference<Thread?>()
        val loserPid = AtomicInteger()
        val loserEntering = CountDownLatch(1)
        val changedAfterClaim = AtomicBoolean()
        f.raw { observer ->
            OwnedCallerTestScope().use { callers ->
                val completedWinner = callers.gate()
                f.jdbc.before = { path, sql ->
                    if (path === REGISTERED_CREATE && sql.startsWith("INSERT INTO complaint_idempotency_receipts") &&
                        winnerThread.get() != null && Thread.currentThread() !== winnerThread.get()) {
                        loserPid.set(currentPid(f)); loserEntering.countDown()
                    }
                }
                f.jdbc.after = { path, sql -> if (path === REGISTERED_CREATE) {
                    if ("UPDATE complaint_idempotency_receipts" in sql && winnerThread.compareAndSet(null, Thread.currentThread())) completedWinner.hold()
                    if (sql.startsWith("INSERT INTO complaint_idempotency_receipts") && winnerThread.get() != null &&
                        Thread.currentThread() !== winnerThread.get() && changedAfterClaim.compareAndSet(false, true)) {
                        // Winner committed and released its control locks before this actual DO NOTHING
                        // returned. New work is unavailable now; the loser still owes an exact receipt read.
                        if (desiredDrift) assertEquals(1, f.exchange.f.foreignUpdate(
                            "UPDATE complaint_journal_control SET desired_configuration_hash = ? WHERE data_scope_id = ?", ByteArray(32) { 0x68 }, f.scope))
                        else assertEquals(1, f.exchange.f.foreignUpdate("UPDATE complaint_journal_control SET creation_closed = true, maintenance_closed = true WHERE data_scope_id = ?", f.scope))
                    }
                } }
                val winner = callers.launch { f.create(attempt) }
                completedWinner.awaitEntered()
                try {
                    val loser = callers.launch { f.create(attempt) }
                    assertTrue(loserEntering.await(1, TimeUnit.SECONDS))
                    awaitActualLockWait(observer, loserPid.get())
                    completedWinner.release()
                    f.assertApplied(winner.value(), attempt)
                    if (desiredDrift) {
                        val failure = loser.problem()
                        assertTrue(failure is ComplaintOwnerOperationRejected)
                        assertEquals(ComplaintOwnerOperationFailure.UNAVAILABLE, (failure as ComplaintOwnerOperationRejected).failure)
                    } else f.assertApplied(loser.value(), attempt)
                } finally { completedWinner.release() }
            }
        }
        f.jdbc.before = { _, _ -> }; f.jdbc.after = { _, _ -> }
        assertTrue(changedAfterClaim.get()); f.assertReleased()
        assertEquals(2, f.createPhases().size)
        assertEquals(if (desiredDrift) 1 else 2, f.createPhases().count { it.databaseOutcome() === PersistenceDatabaseOutcome.COMMITTED })
        assertEquals(if (desiredDrift) 1 else 0, f.createPhases().count { it.databaseOutcome() === PersistenceDatabaseOutcome.ROLLED_BACK })
        assertEquals(5, f.createSql().count { it == TestActiveInitialCheckpointSqlV1.currentForOwnerCreate }, "Only winner checked new-work eligibility.")
        assertEquals(1, f.createSql().count { "FROM complaint_capacity_counters" in it && "FOR UPDATE" in it })
        f.assertCharge(before, ComplaintCapacityCharges.OWNER_CREATE)
        assertEquals(1L, f.observer.queryForObject("SELECT count(*) FROM complaint_idempotency_receipts WHERE actor_id = ?", Long::class.java, f.actor.id))
        if (desiredDrift) refused { f.status(attempt) } else f.assertApplied(f.status(attempt), attempt)
        assertEquals(providers, f.providerCounts())
    }

    fun naturalFreshness(f: TestRegisteredInitialCheckpointCreateFixtureV1) {
        val deadlines = f.process.consumers.journalConfiguration.declaration().limits.deadlines
        assertEquals(30_000, deadlines.scanMillis); assertEquals(30_000, deadlines.scanCadenceMillis); assertEquals(30_000, deadlines.checkpointMaxAgeMillis)
        val first = f.attempt(); f.assertApplied(f.create(first), first); f.assertReleased()
        val before = f.state(); val counters = f.counters(); val providers = f.providerCounts()
        val expires = (f.checkpoint.control().getValue("checkpoint_completed_at") as Timestamp).toInstant().plusMillis(deadlines.checkpointMaxAgeMillis.toLong())
        val second = f.attempt()
        val heldAfterActor = AtomicBoolean()
        f.raw { observer ->
            requireConnectionFree()
            waitUntil(observer, expires.minusMillis(800), 31_000) // Outside every CREATE original/admission.
            f.jdbc.calls.clear()
            f.jdbc.after = { path, sql -> if (path === REGISTERED_CREATE && credentialLock(sql) && heldAfterActor.compareAndSet(false, true)) {
                // The actual locked credential result and both earlier current checks already exist.
                // This intentional caller stall consumes the SAME 2s original, no clock/reset shortcut.
                assertEquals(2, f.createSql().count { it == TestActiveInitialCheckpointSqlV1.currentForOwnerCreate })
                assertTrue(now(observer).isBefore(expires))
                waitUntil(observer, expires.plusNanos(1000), 900)
            } }
            try { refused { f.create(second) } } finally { f.jdbc.after = { _, _ -> } }
        }
        assertTrue(heldAfterActor.get()); f.assertReleased()
        assertEquals(3, f.createSql().count { it == TestActiveInitialCheckpointSqlV1.currentForOwnerCreate })
        assertFalse(f.createSql().any { it.startsWith("INSERT INTO complaint_resource_ids") })
        assertEquals(before, f.state()); assertEquals(counters, f.counters())
        f.jdbc.calls.clear()
        f.assertApplied(f.create(first), first); f.assertApplied(f.status(first), first)
        assertTrue(f.createSql().isEmpty(), "Exact receipts precede expired freshness and already-spent admission members.")
        refused { f.create(second) }; f.assertNoCounterSql(); f.assertReleased()
        assertEquals(before, f.state()); assertEquals(providers, f.providerCounts())
    }

    fun credentialWait(f: TestRegisteredInitialCheckpointCreateFixtureV1) {
        val attempt = f.attempt(); val before = f.state(); val counters = f.counters()
        val entering = CountDownLatch(1); val pid = AtomicInteger()
        f.raw { locker ->
            locker.autoCommit = false
            try {
                locker.prepareStatement("SELECT id FROM app_installations WHERE id = ? FOR UPDATE").use { statement ->
                    statement.queryTimeout = 1; statement.setObject(1, f.actor.id)
                    statement.executeQuery().use { row -> assertTrue(row.next()); assertFalse(row.next()) }
                }
                f.raw { observer ->
                    OwnedCallerTestScope().use { callers ->
                        f.jdbc.before = { path, sql -> if (path === REGISTERED_CREATE && credentialLock(sql)) {
                            pid.set(currentPid(f)); entering.countDown()
                        } }
                        val original = callers.launch { f.create(attempt) }
                        assertTrue(entering.await(1, TimeUnit.SECONDS))
                        awaitActualLockWait(observer, pid.get())
                        locker.prepareStatement("UPDATE app_installations SET credential_version = credential_version + 1 WHERE id = ?").use { statement ->
                            statement.queryTimeout = 1; statement.setObject(1, f.actor.id); assertEquals(1, statement.executeUpdate())
                        }
                        locker.commit()
                        val failure = original.problem()
                        assertTrue(failure is ComplaintOwnerOperationRejected)
                        assertEquals(ComplaintOwnerOperationFailure.UNAUTHORIZED, (failure as ComplaintOwnerOperationRejected).failure)
                    }
                }
            } finally { locker.rollback(); f.jdbc.before = { _, _ -> } }
        }
        f.assertReleased(); assertEquals(counters, f.counters())
        assertEquals(before.filterKeys { it != "app_installations" }, f.state().filterKeys { it != "app_installations" })
        assertEquals(2, f.createSql().count { it == TestActiveInitialCheckpointSqlV1.currentForOwnerCreate })
        assertFalse(f.createSql().any { it.startsWith("INSERT INTO complaint_resource_ids") })
    }

    fun completionFailures(f: TestRegisteredInitialCheckpointCreateFixtureV1) {
        val first = f.attempt(); val initial = f.state(); val counters = f.counters()
        val rollback = failedCompletion(f, first, CompletionCut.BEFORE)
        assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, rollback.databaseOutcome)
        assertEquals(initial, f.state())
        val tail = failedCompletion(f, first, CompletionCut.TAIL)
        assertEquals(PersistenceDatabaseOutcome.COMMITTED, tail.databaseOutcome)
        f.assertApplied(f.status(first), first); f.assertApplied(f.create(first), first)
        f.assertCharge(counters, ComplaintCapacityCharges.OWNER_CREATE)
        val second = f.attempt(); val beforeUnknown = f.state()
        val unknown = failedCompletion(f, second, CompletionCut.DEFERRED_UNIQUE)
        assertEquals(PersistenceDatabaseOutcome.UNKNOWN, unknown.databaseOutcome)
        assertEquals(beforeUnknown, f.state())
        refused(ComplaintOwnerOperationFailure.OPERATION_NOT_FOUND) { f.status(second) }
        f.assertApplied(f.create(second), second); f.assertReleased()
        f.assertCharge(counters, ComplaintCapacityCharges.OWNER_CREATE.scaled(2))
    }

    private fun failedCompletion(f: TestRegisteredInitialCheckpointCreateFixtureV1, attempt: RegisteredInitialCreateAttemptV1,
        cut: CompletionCut): PersistencePhaseException {
        val reached = AtomicBoolean()
        f.jdbc.after = { path, sql -> if (path === REGISTERED_CREATE && "UPDATE complaint_idempotency_receipts" in sql && reached.compareAndSet(false, true)) {
            if (cut === CompletionCut.DEFERRED_UNIQUE) {
                // Real driver COMMIT failure, not a caller-provided UNKNOWN. A temp table lives only
                // in this original transaction; the registered checkpoint/current checks stay intact.
                val connection = (TransactionSynchronizationManager.getResource(f.jdbc.dataSource!!) as ConnectionHolder).connection
                connection.createStatement().use { statement ->
                    statement.execute("CREATE TEMP TABLE kira_registered_create_commit (id int UNIQUE DEFERRABLE INITIALLY DEFERRED) ON COMMIT DROP")
                    assertEquals(2, statement.executeUpdate("INSERT INTO kira_registered_create_commit VALUES (1), (1)"))
                }
            } else TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun beforeCommit(readOnly: Boolean) { if (cut === CompletionCut.BEFORE) throw SyntheticRegisteredCreateCutV1() }
                override fun afterCommit() { if (cut === CompletionCut.TAIL) throw SyntheticRegisteredCreateCutV1() }
            })
        } }
        try { refused { f.create(attempt) } } finally { f.jdbc.after = { _, _ -> } }
        assertTrue(reached.get()); f.assertReleased()
        val phase = f.createPhases().last()
        val original = poolTestField<ComplaintOwnerCreateOperation>(phase.ownerOperation, "retained")
        val failure = assertThrows<PersistencePhaseException> { original.result }
        assertTrue(failure.cleanupProven); assertNull(failure.cause); assertTrue(failure.suppressed.isEmpty())
        assertEquals(phase.databaseOutcome(), failure.databaseOutcome)
        assertThrows<PersistencePhaseException> { original.result } // Neither visible rows nor another read rehabilitate it.
        return failure
    }

    private fun currentPid(f: TestRegisteredInitialCheckpointCreateFixtureV1): Int =
        f.jdbc.observations.getValue(checkNotNull(PersistencePhaseOwnership.current())).identity.first
    private fun credentialLock(sql: String): Boolean = "FROM app_installations WHERE id = ? FOR UPDATE" in sql

    /** The real lock_timeout remains 100ms. No retry or long wait is reclassified as a successful race. */
    private fun awaitActualLockWait(observer: Connection, pid: Int) {
        val stop = System.nanoTime() + 80_000_000L
        observer.prepareStatement("SELECT coalesce(wait_event_type = 'Lock', false) FROM pg_stat_activity WHERE pid = ?").use { statement ->
            statement.queryTimeout = 1; statement.setInt(1, pid)
            while (true) {
                val waiting = statement.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
                if (waiting) return
                check(System.nanoTime() - stop < 0) { "Actual original did not reach the bounded PostgreSQL row wait." }
                LockSupport.parkNanos(500_000)
            }
        }
    }
    private fun now(observer: Connection): Instant = observer.createStatement().use { statement ->
        statement.queryTimeout = 1
        statement.executeQuery("SELECT clock_timestamp()").use { row -> assertTrue(row.next()); row.getTimestamp(1).toInstant() }
    }
    private fun waitUntil(observer: Connection, time: Instant, maximumMillis: Long) {
        val stop = System.nanoTime() + maximumMillis * 1_000_000L
        while (now(observer).isBefore(time)) {
            check(System.nanoTime() - stop < 0) { "Actual database time did not reach the explicit test boundary." }
            LockSupport.parkNanos(2_000_000)
        }
    }
    private enum class CompletionCut { BEFORE, TAIL, DEFERRED_UNIQUE }
    private class SyntheticRegisteredCreateCutV1 : RuntimeException("Synthetic registered CREATE completion cut.")
}
