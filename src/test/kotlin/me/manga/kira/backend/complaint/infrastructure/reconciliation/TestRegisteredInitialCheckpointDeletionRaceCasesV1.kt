package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.infrastructure.persistence.OwnedCallerTestScope
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteFailure
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteRejected
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteReceipt
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteRejection
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationFailure
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationRejected
import me.manga.kira.backend.complaint.domain.OwnerDeleteCapacityCharges
import me.manga.kira.backend.complaint.infrastructure.AdminDeletePersistenceSql
import me.manga.kira.backend.complaint.infrastructure.CommittedTestOwnerDeleteWork
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeleteAuthorizationOperation
import me.manga.kira.backend.complaint.infrastructure.OwnerDeletePersistenceSql
import me.manga.kira.backend.complaint.infrastructure.TestOwnerDeleteAuthorizationV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredInitialCheckpointDeletionFailureCasesV1.adminAttempt
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredInitialCheckpointDeletionFailureCasesV1.ownerAttempt
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredInitialCheckpointDeletionFailureCasesV1.phaseRefused
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
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
import java.util.concurrent.locks.LockSupport

/** Real row waits, completion dispatch and physical native-close return; no supplied result, clock or budget. */
internal object TestRegisteredInitialCheckpointDeletionRaceCasesV1 {
    fun receiptClaimLoser(f: TestRegisteredInitialCheckpointDeletionFixtureV1, revokeActor: Boolean = false) {
        val before = f.counters()
        val providers = nativeBaseline(f); val currentSql = f.currentCheckpointSql()
        val entering = CountDownLatch(1); val pid = AtomicInteger()
        val oldBefore = f.deletion.before
        f.raw { locker -> f.raw { observer ->
            OwnedCallerTestScope().use { callers ->
                val readBeforeWinner = callers.gate()
                val loser = callers.launch {
                    f.ingress.withIngress(f.request()) { context ->
                        f.ingress.startOwnerDelete(context)
                        val identity = f.creators.first().identity()
                        val preflight = f.ownerReads.preflight(identity, f.ownerCandidate.tuple)
                        assertNull(preflight.failure); assertFalse(preflight.authorized)
                        // No admitted handoff, SQL original or native reservation is held here.
                        readBeforeWinner.hold()
                        val admitted = f.ingress.admitOwnerDelete(context, f.ownerCandidate.tuple)
                        f.ownerPublisher.reserve().use { lane -> f.ownerPhases.authorize(identity, f.ownerCandidate, preflight, admitted, lane) }
                    }
                }
                readBeforeWinner.awaitEntered()
                f.authorize(); f.assertCharge(before)
                if (!revokeActor) assertEquals(2, f.foreignUpdate("UPDATE complaint_journal_control SET creation_closed = true, maintenance_closed = true " +
                    "WHERE data_scope_id IN (?, ?)", UUID(0, 0), f.scope))
                val authorized = f.image(); val paid = f.counters(); val audits = f.audits()
                val freshChecks = f.deletion.calls.count { it.sql == currentSql }
                locker.autoCommit = false
                try {
                    lockOne(locker, "SELECT actor_id FROM complaint_idempotency_receipts WHERE actor_id = ? AND idempotency_key = ? FOR UPDATE", f.actor.id, f.key)
                    f.deletion.before = { call -> oldBefore(call); if (call.sql == OwnerDeletePersistenceSql.LOCK_RECEIPT) { pid.set(currentPid(f)); entering.countDown() } }
                    readBeforeWinner.release()
                    assertTrue(entering.await(1, TimeUnit.SECONDS))
                    awaitActualLockWait(observer, pid.get())
                    if (revokeActor) updateOne(locker, "UPDATE app_installations SET credential_version = credential_version + 1 WHERE id = ?", f.actor.id)
                    locker.commit()
                    if (revokeActor) {
                        assertEquals(ComplaintOwnerOperationFailure.UNAUTHORIZED,
                            assertInstanceOf(ComplaintOwnerOperationRejected::class.java, loser.problem()).failure)
                    } else {
                        val work = assertInstanceOf(TestOwnerDeleteAuthorizationV1.Continue::class.java, loser.value()).work
                        assertInstanceOf(CommittedTestOwnerDeleteWork.Prepared::class.java, work)
                        val expected = checkNotNull(f.event).canonicalBytes(); val actual = work.canonicalBytes()
                        try { assertArrayEquals(expected, actual) } finally { expected.fill(0); actual.fill(0) }
                    }
                } finally { locker.rollback(); readBeforeWinner.release(); f.deletion.before = oldBefore }
                assertEquals(freshChecks, f.deletion.calls.count { it.sql == currentSql },
                    "A real receipt loser does not become a new checkpoint-gated claim.")
                assertEquals(authorized.filterKeys { !revokeActor || it != "app_installations" }, f.image().filterKeys { !revokeActor || it != "app_installations" })
                assertEquals(paid, f.counters()); assertEquals(audits, f.audits()); f.assertSqlReleased()
                if (!revokeActor) f.assertReload(verified = false)
                assertEquals(providers, f.native.counts())
            }
        } }
    }

    fun credentialWait(f: TestRegisteredInitialCheckpointDeletionFixtureV1) {
        val rows = f.image(); val counters = f.counters(); val audits = f.audits()
        val providers = nativeBaseline(f)
        waitAt(f, "SELECT id FROM app_installations WHERE id = ? FOR UPDATE", arrayOf(f.actor.id),
            { it.sql == OwnerDeletePersistenceSql.LOCK_CREDENTIAL },
            { locker -> updateOne(locker, "UPDATE app_installations SET credential_version = credential_version + 1 WHERE id = ?", f.actor.id) },
            { ownerAttempt(f) }) { _, failure ->
            assertEquals(ComplaintOwnerOperationFailure.UNAUTHORIZED, assertInstanceOf(ComplaintOwnerOperationRejected::class.java, failure).failure)
        }
        assertEquals(rows.filterKeys { it != "app_installations" }, f.image().filterKeys { it != "app_installations" })
        assertEquals(counters, f.counters()); assertEquals(audits, f.audits()); f.assertReleased()
        assertEquals(providers, f.native.counts())
    }

    fun counterAndRunWaits(f: TestRegisteredInitialCheckpointDeletionFixtureV1) {
        val runHash = f.observer.queryForObject("SELECT configuration_hash FROM complaint_test_runs WHERE data_scope_id = ?", ByteArray::class.java, f.scope)!!
        val rows = f.image(); val counters = f.counters(); val audits = f.audits()
        for (counter in listOf(true, false)) {
            try {
                val sql = if (counter) "SELECT name FROM complaint_capacity_counters ORDER BY ordinal LIMIT 1 FOR UPDATE"
                    else "SELECT data_scope_id FROM complaint_test_runs WHERE data_scope_id = ? FOR UPDATE"
                val args = if (counter) emptyArray<Any?>() else arrayOf<Any?>(f.scope)
                waitAt(f, sql, args,
                    { call -> if (counter) "FROM complaint_capacity_counters" in call.sql && "FOR UPDATE" in call.sql else call.sql == TestActiveInitialCheckpointSqlV1.lockRun },
                    { locker ->
                        if (counter) {
                            // This run row is not locked yet. Hostile drift is not a new registration.
                            assertEquals(1, f.foreignUpdate("UPDATE complaint_test_runs SET configuration_hash = ? WHERE data_scope_id = ?", ByteArray(32) { 0x68 }, f.scope))
                        } else updateOne(locker, "UPDATE complaint_test_runs SET configuration_hash = ? WHERE data_scope_id = ?", ByteArray(32) { 0x68 }, f.scope)
                    }, { ownerAttempt(f) }) { _, failure ->
                    val refused = assertInstanceOf(PersistencePhaseException::class.java, failure)
                    assertTrue(refused.cleanupProven); assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, refused.databaseOutcome)
                }
                assertEquals(rows, f.image()); assertEquals(counters, f.counters()); assertEquals(audits, f.audits()); f.assertReleased()
            } finally { f.foreignUpdate("UPDATE complaint_test_runs SET configuration_hash = ? WHERE data_scope_id = ?", runHash, f.scope) }
        }
        assertEquals(listOf(0, 0, 0, 0), f.native.counts())
    }

    fun targetWait(f: TestRegisteredInitialCheckpointDeletionFixtureV1) {
        val before = f.counters(); val audits = f.audits()
        waitAt(f, "SELECT id FROM complaints WHERE id = ? FOR UPDATE", arrayOf(f.reports.first().input.id),
            { it.sql == OwnerDeletePersistenceSql.LOCK_CONTENT },
            { locker -> updateOne(locker, "UPDATE complaints SET version = version + 1 WHERE id = ?", f.reports.first().input.id) },
            { ownerAttempt(f) }) { result, failure ->
            assertNull(failure)
            val completed = assertInstanceOf(TestOwnerDeleteAuthorizationV1.Completed::class.java, result)
            assertEquals(ComplaintOwnerDeleteRejection.PRECONDITION_FAILED,
                assertInstanceOf(ComplaintOwnerDeleteReceipt.Rejected::class.java, completed.receipt).code)
        }
        val after = f.counters()
        for (counter in ComplaintCapacityCounter.entries) {
            val old = before.getValue(counter); val current = after.getValue(counter)
            assertEquals(old.free - OwnerDeleteCapacityCharges.RECEIPT[counter], current.free)
            assertEquals(old.actual + OwnerDeleteCapacityCharges.RECEIPT[counter], current.actual)
            assertEquals(old.recovery, current.recovery); assertEquals(old.test, current.test); assertEquals(old.preserved, current.preserved)
        }
        assertEquals(true, f.observer.queryForObject("SELECT state = 'COMPLETED' AND outcome = 'REJECTED' AND response_status = 412 AND publication_ref IS NULL " +
            "FROM complaint_idempotency_receipts WHERE actor_id = ? AND idempotency_key = ?", Boolean::class.java, f.actor.id, f.key))
        assertTrue(f.image().getValue("complaint_journal_publications").isEmpty()); assertTrue(f.image().getValue("complaint_recovery_capacity_reservations").isEmpty())
        assertEquals(audits, f.audits()); assertEquals(listOf(0, 0, 0, 0), f.native.counts()); f.assertReleased()
    }

    fun grantWait(f: TestRegisteredInitialCheckpointDeletionFixtureV1) {
        val proof = checkNotNull(f.proof); val rows = f.image(); val counters = f.counters(); val audits = f.audits()
        waitAt(f, "SELECT id FROM admin_step_up_grants WHERE id = ? FOR UPDATE", arrayOf(proof.grantId),
            { it.sql == AdminDeletePersistenceSql.LOCK_GRANT },
            { locker -> updateOne(locker, "UPDATE admin_step_up_grants SET expires_at = created_at + interval '1 microsecond' WHERE id = ?", proof.grantId) },
            { adminAttempt(f, proof.token) }) { _, failure ->
            assertEquals(ComplaintAdminDeleteFailure.STEP_UP_REQUIRED, assertInstanceOf(ComplaintAdminDeleteRejected::class.java, failure).failure)
        }
        assertEquals(rows, f.image()); assertEquals(counters, f.counters()); assertEquals(audits, f.audits())
        assertEquals(false, f.observer.queryForObject("SELECT used_at IS NOT NULL FROM admin_step_up_grants WHERE id = ?", Boolean::class.java, proof.grantId))
        assertEquals(listOf(0, 0, 0, 0), f.native.counts()); f.assertReleased()
    }

    fun naturalFreshnessAfterCredential(f: TestRegisteredInitialCheckpointDeletionFixtureV1) {
        val limits = f.process.consumers.journalConfiguration.declaration().limits.deadlines
        assertEquals(30_000, limits.checkpointMaxAgeMillis)
        val expires = (f.checkpoint.control().getValue("checkpoint_completed_at") as Timestamp).toInstant().plusMillis(limits.checkpointMaxAgeMillis.toLong())
        val rows = f.image(); val counters = f.counters(); val audits = f.audits()
        val providers = nativeBaseline(f); val calls = f.deletion.calls.size
        val reached = AtomicBoolean(); val oldAfter = f.deletion.after
        f.raw { observer ->
            requireConnectionFree()
            waitUntil(observer, expires.minusMillis(800), 31_000) // Outside every original/context/handoff.
            f.deletion.after = { call -> oldAfter(call); if (call.sql == OwnerDeletePersistenceSql.LOCK_CREDENTIAL && reached.compareAndSet(false, true)) {
                assertTrue(now(observer).isBefore(expires))
                // A bounded caller stall consumes the same original's 2s; no deadline or clock reset.
                waitUntil(observer, expires.plusNanos(1000), 900)
            } }
            try { phaseRefused { ownerAttempt(f) } } finally { f.deletion.after = oldAfter }
        }
        assertTrue(reached.get())
        assertFalse((if (f.expectedEpoch == 2L) f.deletion.calls else f.deletion.calls.drop(calls))
            .any { it.sql == OwnerDeletePersistenceSql.INSERT_PUBLICATION })
        assertEquals(rows, f.image()); assertEquals(counters, f.counters()); assertEquals(audits, f.audits()); f.assertReleased()
        phaseRefused { ownerAttempt(f) }; assertEquals(counters, f.counters()); assertEquals(providers, f.native.counts())
    }

    /** Retain the literal zero-provider oracle for old first-primary fixtures. */
    private fun nativeBaseline(f: TestRegisteredInitialCheckpointDeletionFixtureV1): List<Int> = f.native.counts().also {
        if (f.expectedEpoch == 2L) assertEquals(listOf(0, 0, 0, 0), it)
    }

    fun nativeCloseCustody(f: TestRegisteredInitialCheckpointDeletionFixtureV1) {
        f.authorize()
        val primary = f.image(); val paid = f.counters(); val audits = f.audits(); val sql = f.deletion.calls.size
        val previous = f.native.publisher.onClientClose
        OwnedCallerTestScope().use { callers ->
            val closing = callers.gate()
            f.native.publisher.onClientClose = { previous(); closing.hold() }
            val publishing = callers.launch { f.publish() }
            closing.awaitEntered()
            try {
                assertTrue(publishing.thread.isAlive); assertNull(f.readback); assertNull(f.record)
                assertEquals(1L, f.process.publicationLanes.activeOwners().totalOwners)
                assertEquals(primary, f.image()); assertEquals("PREPARED", f.publication()["state"])
                assertThrows<IllegalStateException> { f.verify() }
                assertEquals(sql, f.deletion.calls.size); f.assertSqlReleased()
            } finally { closing.release() }
            publishing.value()
        }
        f.native.publisher.onClientClose = previous
        f.verify(); TestRegisteredInitialCheckpointDeletionCasesV1.assertPrimary(f, "VERIFIED")
        assertEquals(paid, f.counters()); assertEquals(audits, f.audits()); f.assertReleased()
    }

    fun completionFailures(f: TestRegisteredInitialCheckpointDeletionFixtureV1) {
        val rows = f.image(); val counters = f.counters()
        assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, failedCompletion(f, CompletionCut.BEFORE).databaseOutcome)
        assertEquals(rows, f.image()); assertEquals(counters, f.counters())
        assertEquals(PersistenceDatabaseOutcome.UNKNOWN, failedCompletion(f, CompletionCut.DEFERRED_UNIQUE).databaseOutcome)
        assertEquals(rows, f.image()); assertEquals(counters, f.counters())
        assertEquals(PersistenceDatabaseOutcome.COMMITTED, failedCompletion(f, CompletionCut.TAIL).databaseOutcome)
        f.assertCharge(counters)
        assertNull(f.event); assertNull(f.ownerWork); assertNull(f.readback)
        assertEquals("PREPARED", f.publication()["state"])
        // Only a NEW actual read/reload original can observe the committed tail's exact receipt.
        val authorized = f.image(); val paid = f.counters()
        f.ingress.withIngress(f.request()) { context ->
            f.ingress.startOwnerDelete(context)
            val identity = f.creators.first().identity()
            val preflight = f.ownerReads.preflight(identity, f.ownerCandidate.tuple)
            assertTrue(preflight.authorized); assertNull(preflight.failure)
            val work = assertInstanceOf(TestOwnerDeleteAuthorizationV1.Continue::class.java, f.ownerPhases.reload(identity, f.ownerCandidate, preflight)).work
            val bytes = work.canonicalBytes()
            try { assertArrayEquals(f.publication()["event_bytes"] as ByteArray, bytes) } finally { bytes.fill(0) }
        }
        assertEquals(authorized, f.image()); assertEquals(paid, f.counters()); assertEquals(listOf(0, 0, 0, 0), f.native.counts()); f.assertReleased()
    }

    private fun failedCompletion(f: TestRegisteredInitialCheckpointDeletionFixtureV1, cut: CompletionCut): PersistencePhaseException {
        val reached = AtomicBoolean(); val previous = f.deletion.after
        f.deletion.after = { call -> previous(call); if (call.sql == OwnerDeletePersistenceSql.AUTHORIZE_RECEIPT && reached.compareAndSet(false, true)) {
            if (cut == CompletionCut.DEFERRED_UNIQUE) {
                val connection = (TransactionSynchronizationManager.getResource(f.deletion.dataSource!!) as ConnectionHolder).connection
                connection.createStatement().use { statement ->
                    statement.queryTimeout = 1
                    statement.execute("CREATE TEMP TABLE kira_registered_delete_commit (id int UNIQUE DEFERRABLE INITIALLY DEFERRED) ON COMMIT DROP")
                    assertEquals(2, statement.executeUpdate("INSERT INTO kira_registered_delete_commit VALUES (1), (1)"))
                }
            } else TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun beforeCommit(readOnly: Boolean) { if (cut == CompletionCut.BEFORE) throw SyntheticRegisteredDeleteCutV1() }
                override fun afterCommit() { if (cut == CompletionCut.TAIL) throw SyntheticRegisteredDeleteCutV1() }
            })
        } }
        try { assertThrows<PersistencePhaseException> { ownerAttempt(f) } } finally { f.deletion.after = previous }
        assertTrue(reached.get()); f.assertReleased()
        val phase = f.deletion.calls.last { it.path == PersistencePhasePath.COMPLAINT_OWNER_DELETE_AUTHORIZE }.phase
        val original = poolTestField<ComplaintOwnerDeleteAuthorizationOperation>(phase.ownerDelete, "retained")
        val failure = assertThrows<PersistencePhaseException> { original.result }
        assertTrue(failure.cleanupProven); assertEquals(phase.databaseOutcome(), failure.databaseOutcome)
        assertNull(failure.cause); assertTrue(failure.suppressed.isEmpty())
        assertThrows<PersistencePhaseException> { original.result }
        return failure
    }

    /** Fixed source-selected SQL only. Hooks observe the original pair; raw lockers never become product owners. */
    private fun <T : Any> waitAt(f: TestRegisteredInitialCheckpointDeletionFixtureV1, lockSql: String, args: Array<out Any?>,
        matches: (TestRegisteredInitialDeletionSqlCallV1) -> Boolean, change: (Connection) -> Unit,
        action: () -> T, result: (T?, Throwable?) -> Unit) {
        val entering = CountDownLatch(1); val pid = AtomicInteger(); val previous = f.deletion.before
        f.raw { locker -> f.raw { observer ->
            locker.autoCommit = false
            try {
                lockOne(locker, lockSql, *args)
                OwnedCallerTestScope().use { callers ->
                    f.deletion.before = { call -> previous(call); if (matches(call)) { pid.set(currentPid(f)); entering.countDown() } }
                    val original = callers.launch(action = action)
                    assertTrue(entering.await(1, TimeUnit.SECONDS))
                    awaitActualLockWait(observer, pid.get())
                    change(locker); locker.commit()
                    val failure = original.problem()
                    result(if (failure == null) original.value() else null, failure)
                }
            } finally { locker.rollback(); f.deletion.before = previous }
        } }
    }

    private fun currentPid(f: TestRegisteredInitialCheckpointDeletionFixtureV1): Int =
        f.deletion.observations.getValue(checkNotNull(PersistencePhaseOwnership.current())).identity.first
    private fun lockOne(connection: Connection, sql: String, vararg args: Any?) = connection.prepareStatement(sql).use { statement ->
        statement.queryTimeout = 1; args.forEachIndexed { i, arg -> statement.setObject(i + 1, arg) }
        statement.executeQuery().use { rows -> assertTrue(rows.next()); assertFalse(rows.next()) }
    }
    private fun updateOne(connection: Connection, sql: String, vararg args: Any?) = connection.prepareStatement(sql).use { statement ->
        statement.queryTimeout = 1; args.forEachIndexed { i, arg -> statement.setObject(i + 1, arg) }; assertEquals(1, statement.executeUpdate())
    }
    private fun awaitActualLockWait(observer: Connection, pid: Int) {
        val stop = System.nanoTime() + 80_000_000L // The actual original lock_timeout stays 100ms.
        observer.prepareStatement("SELECT coalesce(wait_event_type = 'Lock', false) FROM pg_stat_activity WHERE pid = ?").use { statement ->
            statement.queryTimeout = 1; statement.setInt(1, pid)
            while (true) {
                if (statement.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }) return
                check(System.nanoTime() - stop < 0) { "Original did not reach its bounded real PostgreSQL wait." }
                LockSupport.parkNanos(500_000)
            }
        }
    }
    private fun now(observer: Connection): Instant = observer.createStatement().use { statement ->
        statement.queryTimeout = 1
        statement.executeQuery("SELECT clock_timestamp()").use { row -> assertTrue(row.next()); row.getTimestamp(1).toInstant() }
    }
    private fun waitUntil(observer: Connection, at: Instant, maximumMillis: Long) {
        val stop = System.nanoTime() + maximumMillis * 1_000_000L
        while (now(observer).isBefore(at)) {
            check(System.nanoTime() - stop < 0) { "Actual database time did not reach the explicit boundary." }
            LockSupport.parkNanos(2_000_000)
        }
    }
    private enum class CompletionCut { BEFORE, TAIL, DEFERRED_UNIQUE }
    private class SyntheticRegisteredDeleteCutV1 : RuntimeException("Synthetic registered deletion completion cut.")
}
