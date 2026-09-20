package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.domain.ComplaintCapacityCharges
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintDeleteAllFingerprint
import me.manga.kira.backend.complaint.domain.OwnerDeleteAllCapacityCharges
import me.manga.kira.backend.complaint.infrastructure.CommittedOwnerDeleteAllApplyV1
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeleteAllApplyOperation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal fun assertOwnerDeleteAllApplyCorruption(f: OwnerDeleteAllApplyFixture) {
    val wrong = ByteArray(32) { 37 }
    corruptApplyRow(
        f,
        { f.auth.observer.update("UPDATE complaint_journal_publications SET object_version = 'different-object-version' WHERE event_id = ?", f.eventId()) },
        { f.auth.observer.update("UPDATE complaint_journal_publications SET object_version = ? WHERE event_id = ?", f.proof.objectVersion, f.eventId()) },
    )
    val badGrammar = f.proof.verificationBytes() + "{}".toByteArray()
    corruptApplyRow(
        f,
        {
            f.auth.observer.update(
                "UPDATE complaint_journal_publications SET verification_bytes = ?, verification_hash = ? WHERE event_id = ?",
                badGrammar,
                ownerDeleteAllTestDigest(badGrammar),
                f.eventId(),
            )
        },
        {
            f.auth.observer.update(
                "UPDATE complaint_journal_publications SET verification_bytes = ?, verification_hash = ? WHERE event_id = ?",
                f.proof.verificationBytes(),
                f.proof.verificationHash(),
                f.eventId(),
            )
        },
    )
    corruptApplyRow(
        f,
        { f.auth.observer.update("UPDATE installation_deletion_receipts SET fingerprint = ? WHERE installation_id = ?", wrong, f.candidate.installation.id) },
        {
            f.auth.observer.update(
                "UPDATE installation_deletion_receipts SET fingerprint = ? WHERE installation_id = ?",
                ComplaintDeleteAllFingerprint.of(f.candidate).bytes(),
                f.candidate.installation.id,
            )
        },
    )
    corruptApplyRow(
        f,
        {
            f.auth.observer.update(
                "UPDATE complaint_recovery_capacity_reservations SET reserved_amounts = ?::bigint[] WHERE event_id = ?",
                applyFixtureVector(OwnerDeleteAllCapacityCharges.RECOVERY - ComplaintCapacityCharges.RESOURCE_ID),
                f.eventId(),
            )
        },
        {
            f.auth.observer.update(
                "UPDATE complaint_recovery_capacity_reservations SET reserved_amounts = ?::bigint[] WHERE event_id = ?",
                applyFixtureVector(OwnerDeleteAllCapacityCharges.RECOVERY),
                f.eventId(),
            )
        },
    )
    requireConnectionFree()
    val (credentialVersion, credentialRowVersion) = checkNotNull(
        f.auth.observer.queryForObject(
            "SELECT credential_version, version FROM app_installations WHERE id = ?",
            { row, _ -> row.getLong(1) to row.getLong(2) },
            f.candidate.installation.id,
        ),
    )
    corruptApplyRow(
        f,
        {
            f.auth.observer.update(
                "UPDATE app_installations SET credential_version = ? WHERE id = ?",
                Math.addExact(credentialVersion, 1L),
                f.candidate.installation.id,
            )
        },
        { f.auth.observer.update("UPDATE app_installations SET credential_version = ? WHERE id = ?", credentialVersion, f.candidate.installation.id) },
    )
    corruptApplyRow(
        f,
        { f.auth.observer.update("UPDATE app_installations SET version = ? WHERE id = ?", Long.MAX_VALUE, f.candidate.installation.id) },
        { f.auth.observer.update("UPDATE app_installations SET version = ? WHERE id = ?", credentialRowVersion, f.candidate.installation.id) },
    )
    f.apply()
    corruptApplyRow(
        f,
        { f.auth.observer.update("UPDATE complaint_deletion_journal_applied SET target_count = 0 WHERE event_id = ?", f.eventId()) },
        { f.auth.observer.update("UPDATE complaint_deletion_journal_applied SET target_count = ? WHERE event_id = ?", f.targets.size, f.eventId()) },
    )
    val before = f.auth.state()
    f.statements.clear()
    f.apply()
    assertEquals(before, f.auth.state())
    f.assertNoWrites()
    f.assertReleased()
}

private fun corruptApplyRow(f: OwnerDeleteAllApplyFixture, change: () -> Int, restore: () -> Int) {
    assertEquals(1, change())
    val corrupted = f.auth.state()
    try {
        assertApplyRolledBack(assertThrows { f.apply() })
        assertEquals(corrupted, f.auth.state())
        f.assertReleased()
    } finally {
        assertEquals(1, restore())
    }
}

internal fun assertOwnerDeleteAllApplyLocks(f: OwnerDeleteAllApplyFixture) {
    val locks = listOf(
        ApplyLock(ApplyStep.CONTROL, "complaint_journal_control", "data_scope_id", ComplaintDataScope.LIVE.id),
        ApplyLock(ApplyStep.RECEIPT, "installation_deletion_receipts", "installation_id", f.candidate.installation.id),
        ApplyLock(ApplyStep.PUBLICATION, "complaint_journal_publications", "event_id", f.eventId()),
        ApplyLock(ApplyStep.RECOVERY, "complaint_recovery_capacity_reservations", "event_id", f.eventId()),
        ApplyLock(ApplyStep.COUNTERS, "complaint_capacity_counters", "name", ComplaintCapacityCounter.APP_INSTALLATIONS.storedName),
        ApplyLock(ApplyStep.INSTALLATION, "complaint_installation_ids", "id", f.candidate.installation.id),
        ApplyLock(ApplyStep.CREDENTIAL, "app_installations", "id", f.candidate.installation.id),
        ApplyLock(ApplyStep.RESOURCE, "complaint_resource_ids", "id", f.targets.single()),
        ApplyLock(ApplyStep.CONTENT, "complaints", "id", f.targets.single()),
    )
    OwnedCallerTestScope().use { callers ->
        val held = callers.gate()
        val occupancy = callers.launch {
            requireConnectionFree()
            val routine = List(3) { checkNotNull(f.auth.admission.tryRoutineDeletion()) }
            try {
                held.hold()
            } finally {
                routine.forEach { assertTrue(it.releaseAfterQuiescence()) }
            }
            requireConnectionFree()
            true
        }
        held.awaitEntered()
        try {
            checkNotNull(f.auth.observer.dataSource).connection.use { observer ->
                observer.autoCommit = false
                f.afterStep = { step ->
                    val index = locks.indexOfFirst { it.step === step }
                    if (index >= 0) {
                        assertEquals(3, f.auth.admission.activeOwners().routineOwners)
                        assertEquals(1, f.auth.admission.activeOwners().privacyOwners)
                        assertTrue(
                            callers.launch {
                                requireConnectionFree()
                                assertApplyLock(observer, locks[index], blocked = true)
                                locks.getOrNull(index + 1)?.let { assertApplyLock(observer, it, blocked = false) }
                                if (step === ApplyStep.CONTROL) assertSharedApplyFence(observer)
                                requireConnectionFree()
                                true
                            }.value(),
                        )
                    }
                }
                f.apply()
            }
        } finally {
            f.afterStep = {}
            held.release()
        }
        assertTrue(occupancy.value())
    }
    assertEquals(locks.map { it.step }, f.observations.map { it.first }.filter { step -> locks.any { it.step === step } })
    // Exact lock/recheck SQL, not a claim that a callable LIVE create/edit route exists. The
    // installation lock above excluded a contender, and committed terminal state cannot pass it.
    val mutationAllowed = f.auth.transaction { selected ->
        val state = selected.queryForObject(
            "SELECT state FROM complaint_installation_ids WHERE id = ? FOR UPDATE",
            String::class.java,
            f.candidate.installation.id,
        )
        val credential = selected.queryForObject(
            "SELECT state = 'ACTIVE' AND credential_version = ? FROM app_installations WHERE id = ? FOR UPDATE",
            Boolean::class.java,
            f.candidate.credentialVersion,
            f.candidate.installation.id,
        )
        state == "ACTIVE" && credential == true
    }
    assertFalse(mutationAllowed)
    f.assertReleased()
}

private class ApplyLock(val step: ApplyStep, val table: String, val key: String, val value: Any)

private fun assertApplyLock(connection: Connection, lock: ApplyLock, blocked: Boolean) {
    try {
        val failure = runCatching {
            connection.prepareStatement("SELECT ${lock.key} FROM ${lock.table} WHERE ${lock.key} = ? FOR UPDATE NOWAIT").use { statement ->
                statement.queryTimeout = 1
                statement.setObject(1, lock.value)
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

private fun assertSharedApplyFence(connection: Connection) {
    try {
        connection.createStatement().use { statement ->
            statement.queryTimeout = 1
            statement.executeQuery("SELECT pg_try_advisory_xact_lock(hashtextextended('complaint-journal-epoch', 0))").use { row ->
                assertTrue(row.next())
                assertFalse(row.getBoolean(1))
            }
            statement.executeQuery("SELECT pg_try_advisory_xact_lock_shared(hashtextextended('complaint-journal-epoch', 0))").use { row ->
                assertTrue(row.next())
                assertTrue(row.getBoolean(1))
            }
        }
    } finally {
        connection.rollback()
    }
}

internal fun assertOwnerDeleteAllApplyFailures(f: OwnerDeleteAllApplyFixture) {
    val before = f.auth.state()
    for (step in ApplyStep.entries) {
        val fired = AtomicBoolean()
        f.afterStep = { reached -> if (reached === step && fired.compareAndSet(false, true)) throw SyntheticOwnerDeleteAllFailure() }
        try {
            assertApplyRolledBack(assertThrows { f.apply() })
            assertTrue(fired.get(), step.name)
            assertEquals(before, f.auth.state(), step.name)
            f.assertReleased()
        } finally {
            f.afterStep = {}
        }
    }
    for (mode in listOf("COMMIT", "SERVER_LOSS", "INTERRUPT", "TAIL")) {
        val captured = f.store.capture(f.verification.prepared, f.proof)
        val phase = f.auth.ownership.enterComplaintOwnerDeleteAllApply()
        var operation: ComplaintOwnerDeleteAllApplyOperation? = null
        try {
            phase.begin()
            operation = f.store.apply(captured)
            when (mode) {
                "COMMIT" -> {
                    f.jdbc.execute("CREATE TEMP TABLE kira_apply_commit (id int UNIQUE DEFERRABLE INITIALLY DEFERRED)")
                    assertEquals(2, f.jdbc.update("INSERT INTO kira_apply_commit VALUES (1), (1)"))
                }

                "SERVER_LOSS" -> OwnedCallerTestScope().use { faults ->
                    val pid = f.observations.last().second.identity.first
                    assertTrue(
                        faults.launch {
                            requireConnectionFree()
                            f.auth.base.ordinary.terminateSession(pid)
                            requireConnectionFree()
                            true
                        }.value(),
                    )
                }

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
            val stable = f.auth.state()
            f.statements.clear()
            f.apply()
            assertEquals(stable, f.auth.state())
            f.assertNoWrites()
        } else {
            assertNotEquals(PersistenceDatabaseOutcome.COMMITTED, failure.databaseOutcome)
            if (mode == "COMMIT") assertEquals(PersistenceDatabaseOutcome.UNKNOWN, failure.databaseOutcome)
            if (mode == "INTERRUPT") assertEquals(PersistencePhaseFailureCode.INTERRUPTED, failure.code)
            assertEquals(before, f.auth.state(), mode)
        }
    }
    assertHeldApplyCompletion(f)
}

private fun assertHeldApplyCompletion(f: OwnerDeleteAllApplyFixture) {
    val returned = AtomicReference<CommittedOwnerDeleteAllApplyV1?>()
    val selected = AtomicReference<StepUpPhaseObservation?>()
    val stable = f.auth.state()
    OwnedCallerTestScope().use { callers ->
        val held = callers.gate()
        f.afterStep = { step ->
            if (step === ApplyStep.FINAL) {
                selected.set(f.observations.last().second)
                TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun afterCommit() {
                        assertThrows<PersistencePhaseException> { requireConnectionFree() }
                        held.hold()
                    }
                })
            }
        }
        val worker = callers.launch { f.apply().also(returned::set) }
        try {
            held.awaitEntered()
            val lease = checkNotNull(selected.get()).lease
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, lease.completion.databaseOutcome())
            assertFalse(lease.completion.quiescent())
            assertEquals(1, f.auth.admission.activeOwners().privacyOwners)
            assertNull(returned.get())
            assertEquals(stable, f.auth.state())
        } finally {
            held.release()
            f.afterStep = {}
        }
        assertEquals(204, worker.value().responseStatus)
    }
    f.assertReleased()
}

internal fun assertApplyRolledBack(failure: PersistencePhaseException) {
    assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, failure.databaseOutcome)
    assertTrue(failure.cleanupProven)
    assertNull(failure.cause)
    assertTrue(failure.suppressed.isEmpty())
}
