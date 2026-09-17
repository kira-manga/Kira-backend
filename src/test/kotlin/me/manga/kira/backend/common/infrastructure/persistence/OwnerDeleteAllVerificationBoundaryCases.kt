package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.infrastructure.CommittedOwnerDeleteAllVerificationV1
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeleteAllVerificationOperation
import me.manga.kira.backend.complaint.infrastructure.journal.OwnerDeleteAllJournalReadbackV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal fun assertOwnerDeleteAllVerificationLocks(f: OwnerDeleteAllVerificationFixture) {
    val readback = f.readback()
    val before = f.auth.state()
    val routine = List(3) { checkNotNull(f.auth.admission.tryRoutineDeletion()) }
    try {
        checkNotNull(f.auth.observer.dataSource).connection.use { observer ->
            observer.autoCommit = false
            f.afterStep = { step ->
                assertEquals(3, f.auth.admission.activeOwners().routineOwners)
                assertEquals(1, f.auth.admission.activeOwners().privacyOwners)
                assertNull(f.auth.admission.tryRoutineDeletion())
                f.assertNoForbiddenLocks(f.observations.last().second)
                assertRowLock(
                    observer,
                    "SELECT installation_id FROM installation_deletion_receipts WHERE installation_id = ? FOR UPDATE NOWAIT",
                    f.candidate.installation.id,
                    blocked = true,
                )
                assertRowLock(
                    observer,
                    "SELECT event_id FROM complaint_journal_publications WHERE event_id = ? FOR UPDATE NOWAIT",
                    readback.event.route.eventId,
                    blocked = step != VerificationStep.RECEIPT,
                )
            }
            f.auth.transaction { blockers ->
                // Genuine concurrent locks on every omitted class. Any forbidden reach-back blocks
                // or fails the short phase; merely claiming an unfenced enum would not pass this.
                blockers.execute("SELECT pg_advisory_xact_lock(hashtextextended('complaint-journal-epoch', 0))")
                holdRows(blockers, "SELECT data_scope_id FROM complaint_journal_control WHERE data_scope_id = ? FOR UPDATE", ComplaintDataScope.LIVE.id)
                holdRows(blockers, "SELECT event_id FROM complaint_recovery_capacity_reservations WHERE event_id = ? FOR UPDATE", readback.event.route.eventId)
                holdRows(blockers, "SELECT name FROM complaint_capacity_counters ORDER BY name COLLATE \"C\" FOR UPDATE")
                holdRows(blockers, "SELECT id FROM complaint_installation_ids WHERE id = ? FOR UPDATE", f.candidate.installation.id)
                holdRows(blockers, "SELECT id FROM app_installations WHERE id = ? FOR UPDATE", f.candidate.installation.id)
                val ids = readback.event.complaintIds().joinToString(",", "{", "}")
                holdRows(blockers, "SELECT id FROM complaint_resource_ids WHERE id = ANY (?::uuid[]) ORDER BY id FOR UPDATE", ids)
                holdRows(blockers, "SELECT id FROM complaints WHERE id = ANY (?::uuid[]) ORDER BY id FOR UPDATE", ids)
                f.phases.verify(readback)
            }
        }
    } finally {
        f.afterStep = {}
        routine.forEach { assertTrue(it.releaseAfterQuiescence()) }
    }
    assertEquals(before.filterKeys { it != "publications" }, f.auth.state().filterKeys { it != "publications" })
    f.assertOnlyVerificationStatements(write = true)
    f.assertReleased()
}

internal fun assertOwnerDeleteAllVerificationFailures(f: OwnerDeleteAllVerificationFixture) {
    val readback = f.readback()
    val prepared = f.auth.state()
    for (step in VerificationStep.entries) {
        val fired = AtomicBoolean()
        f.afterStep = { reached -> if (reached == step && fired.compareAndSet(false, true)) throw SyntheticOwnerDeleteAllFailure() }
        try {
            assertVerificationRolledBack(assertThrows { f.phases.verify(readback) })
            assertTrue(fired.get())
            assertEquals(prepared, f.auth.state(), step.name)
            f.assertReleased()
        } finally {
            f.afterStep = {}
        }
    }

    for (mode in listOf("COMMIT", "SERVER_LOSS", "INTERRUPT", "TAIL")) {
        val captured = f.store.capture(readback)
        val phase = f.auth.ownership.enterComplaintOwnerDeleteAllVerify()
        var operation: ComplaintOwnerDeleteAllVerificationOperation? = null
        try {
            phase.begin()
            operation = f.store.verify(captured)
            when (mode) {
                "COMMIT" -> {
                    f.jdbc.execute("CREATE TEMP TABLE kira_verify_commit (id int UNIQUE DEFERRABLE INITIALLY DEFERRED)")
                    assertEquals(2, f.jdbc.update("INSERT INTO kira_verify_commit VALUES (1), (1)"))
                }

                "SERVER_LOSS" -> f.auth.base.ordinary.terminateSession(f.observations.last().second.identity.first)

                "INTERRUPT" -> Thread.currentThread().interrupt()

                "TAIL" -> TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun afterCommit(): Unit = throw SyntheticOwnerDeleteAllFailure()
                })
            }
            phase.commit()
        } catch (problem: Throwable) {
            phase.recordFailure(problem)
        } finally {
            phase.finish()
            if (mode == "INTERRUPT") assertTrue(Thread.interrupted())
        }
        val failure = assertThrows<PersistencePhaseException> { checkNotNull(operation).result }
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty() && failure.cleanupProven)
        f.assertReleased()
        if (mode == "TAIL") {
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, failure.databaseOutcome)
            val committed = f.auth.state()
            f.statements.clear()
            f.phases.verify(readback) // New independently committed/cleaned attempt, never turn the failed getter into success.
            assertEquals(committed, f.auth.state())
            f.assertOnlyVerificationStatements(write = false)
        } else {
            assertNotEquals(PersistenceDatabaseOutcome.COMMITTED, failure.databaseOutcome)
            if (mode == "COMMIT") assertEquals(PersistenceDatabaseOutcome.UNKNOWN, failure.databaseOutcome)
            if (mode == "INTERRUPT") assertEquals(PersistencePhaseFailureCode.INTERRUPTED, failure.code)
            assertEquals(prepared, f.auth.state(), mode)
        }
    }
    assertHeldCommittedVerificationDoesNotRelease(f, readback)
}

/** A real afterCommit callback holds the original Spring/JDBC completion, not a fabricated cleanup flag. */
private fun assertHeldCommittedVerificationDoesNotRelease(f: OwnerDeleteAllVerificationFixture, readback: OwnerDeleteAllJournalReadbackV1) {
    val returned = AtomicReference<CommittedOwnerDeleteAllVerificationV1?>()
    val observed = AtomicReference<StepUpPhaseObservation?>()
    val before = f.auth.state()
    OwnedCallerTestScope().use { callers ->
        val held = callers.gate()
        f.afterStep = { step ->
            if (step == VerificationStep.PUBLICATION) {
                observed.set(f.observations.last().second)
                TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun afterCommit() {
                        assertThrows<PersistencePhaseException> { requireConnectionFree() }
                        held.hold()
                    }
                })
            }
        }
        val worker = callers.launch { f.phases.verify(readback).also(returned::set) }
        try {
            held.awaitEntered()
            val lease = checkNotNull(observed.get()).lease
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, lease.completion.databaseOutcome())
            assertFalse(lease.completion.quiescent())
            assertEquals(1, f.auth.admission.activeOwners().privacyOwners)
            assertNull(returned.get())
            assertEquals(before, f.auth.state())
        } finally {
            held.release()
            f.afterStep = {}
        }
        assertEquals(readback.event.route.eventId, worker.value().eventId)
    }
    f.assertReleased()
}

private fun holdRows(jdbc: JdbcTemplate, sql: String, vararg arguments: Any?) {
    val selected = jdbc.query(sql, { _, _ -> true }, *arguments)
    assertTrue(selected.isNotEmpty())
}

private fun assertRowLock(connection: Connection, sql: String, key: Any, blocked: Boolean) {
    try {
        val failure = runCatching {
            connection.prepareStatement(sql).use { statement ->
                statement.queryTimeout = 1
                statement.setObject(1, key)
                statement.executeQuery().use { row -> assertTrue(row.next()) }
            }
        }.exceptionOrNull()
        if (blocked) {
            assertTrue(failure is SQLException)
            assertEquals("55P03", (failure as SQLException).sqlState)
        } else if (failure != null) {
            throw failure
        }
    } finally {
        connection.rollback()
    }
}
