package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.infrastructure.CommittedOwnerDeleteAllApplyV1
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeleteAllApplyOperation
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteAllVerificationStore
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerDeleteAllApplyPhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerDeleteAllVerificationPhaseExecutor
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** Producer custody only: real PG/paid content and existing faults, never an HTTP or live-runtime qualification. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class OwnerDeleteAllApplyOutcomeIT {
    private val database = lazy { PgLifecycleDatabaseFixture(OwnerDeleteAllApplyOutcomeIT::class.java).also { it.start() } }

    @AfterAll
    fun closeDatabase() {
        if (database.isInitialized()) database.value.close()
    }

    @Test
    fun `known commit returns the original producer result and later diagnostics cannot create pending`() = withFixture { f ->
        val work = f.verification.prepared
        val captured = f.store.capture(work, f.proof)
        val phase = f.auth.ownership.enterComplaintOwnerDeleteAllApply()
        var operation: ComplaintOwnerDeleteAllApplyOperation? = null
        try {
            phase.begin()
            operation = f.store.apply(captured)
            val beforeCommit = assertThrows<PersistencePhaseException> { checkNotNull(operation).continuationResult }
            assertEquals(PersistenceDatabaseOutcome.NONE, beforeCommit.databaseOutcome)
            assertFalse(beforeCommit.cleanupProven)
            phase.commit()
            val beforeCleanup = assertThrows<PersistencePhaseException> { checkNotNull(operation).continuationResult }
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, beforeCleanup.databaseOutcome)
            assertFalse(beforeCleanup.cleanupProven)
        } catch (problem: Throwable) {
            phase.recordFailure(problem)
            throw problem
        } finally {
            phase.finish()
        }
        val retained = checkNotNull(operation)
        val committed = retained.result
        assertSame(committed, retained.continuationResult)
        assertSame(committed, retained.continuationResult)
        f.assertCompleted(committed, f.targets, 1, 0)
        val stable = f.auth.state()
        f.statements.clear()
        val named = assertInstanceOf(CommittedOwnerDeleteAllApplyV1::class.java, f.phases.applyForContinuation(work, f.proof))
        assertEquals(committed.completedAt, named.completedAt)
        assertEquals(committed.expiresAt, named.expiresAt)
        assertEquals(stable, f.auth.state())
        f.assertNoWrites()
        // A callable diagnostic after the original finalizer is not an unconfirmed completion.
        phase.recordFailure(PersistencePhaseException(PersistencePhaseFailureCode.COMPLETION_FAILED))
        assertRedacted(assertThrows { retained.continuationResult })
        f.assertReleased()
    }

    @Test
    fun `genuine withheld APPLY commit acknowledgement yields only pending before an exact known retry`() =
        assertOwnerDeleteAllApplyPendingLostCommitResponse(database.value)

    @Test
    fun `the continuation entry rejects both foreign private issuers and routing before any APPLY phase`() = withFixture { f ->
        val before = f.auth.state()
        val work = f.verification.prepared
        val foreignVerification = JdbcComplaintOwnerDeleteAllVerificationStore(f.verification.jdbc, f.auth.routing, f.auth.store)
        assertThrows<IllegalStateException> {
            ComplaintOwnerDeleteAllApplyPhaseExecutor(f.auth.ownership, f.newStore(selectedVerification = foreignVerification))
                .applyForContinuation(work, f.proof)
        }
        val foreignAuthorization = f.auth.newStore()
        val foreignAuthVerification = JdbcComplaintOwnerDeleteAllVerificationStore(f.verification.jdbc, f.auth.routing, foreignAuthorization)
        val genuineForeignProof = ComplaintOwnerDeleteAllVerificationPhaseExecutor(f.auth.ownership, foreignAuthVerification)
            .verify(f.verification.readback())
        assertThrows<IllegalStateException> {
            ComplaintOwnerDeleteAllApplyPhaseExecutor(f.auth.ownership, f.newStore(selectedVerification = foreignAuthVerification))
                .applyForContinuation(work, genuineForeignProof)
        }
        val otherRouting = ownerDeleteAllTestRouting()
        assertEquals(f.auth.routing.journalConfiguration.sha256, otherRouting.journalConfiguration.sha256)
        assertThrows<IllegalStateException> {
            ComplaintOwnerDeleteAllApplyPhaseExecutor(f.auth.ownership, f.newStore(selectedRouting = otherRouting))
                .applyForContinuation(work, f.proof)
        }
        assertTrue(f.statements.isEmpty())
        assertEquals(before, f.auth.state())
        f.assertReleased()
    }

    @Test
    fun `early SQL failure incomplete final work and an undispatched completion cannot produce pending from diagnostics`() = withFixture { f ->
        val before = f.auth.state()
        for (step in listOf(ApplyStep.CONTROL, ApplyStep.FINAL)) {
            val fired = AtomicBoolean()
            f.afterStep = { reached ->
                if (reached === step && fired.compareAndSet(false, true)) {
                    // Public defaults say cleanupProven=true; they confer no private producer authority.
                    throw PersistencePhaseException(PersistencePhaseFailureCode.COMPLETION_FAILED)
                }
            }
            try {
                assertApplyRolledBack(assertThrows { f.phases.applyForContinuation(f.verification.prepared, f.proof) })
                assertTrue(fired.get())
                assertEquals(before, f.auth.state())
                f.assertReleased()
            } finally {
                f.afterStep = {}
            }
        }
        val captured = f.store.capture(f.verification.prepared, f.proof)
        val phase = f.auth.ownership.enterComplaintOwnerDeleteAllApply()
        var operation: ComplaintOwnerDeleteAllApplyOperation? = null
        try {
            phase.begin()
            operation = f.store.apply(captured)
            assertTrue(checkNotNull(operation).completedFor(phase))
            phase.recordFailure(PersistencePhaseException(PersistencePhaseFailureCode.COMPLETION_FAILED))
            assertThrows<PersistencePhaseException> { phase.commit() } // Refuses before the original commit dispatch.
        } catch (problem: Throwable) {
            phase.recordFailure(problem)
            throw problem
        } finally {
            phase.finish()
        }
        assertApplyRolledBack(assertThrows { checkNotNull(operation).continuationResult })
        assertEquals(before, f.auth.state())
        f.assertReleased()
    }

    @Test
    fun `actual postcommit cancellation interruption and fatal failures remain redacted failures rather than pending`() = withFixture { f ->
        for (signal in listOf("ERROR", "CANCELLATION", "INTERRUPTION", "FLAG")) {
            val fired = AtomicBoolean()
            f.afterStep = { step ->
                if (step === ApplyStep.FINAL) {
                    TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                        override fun afterCommit() {
                            fired.set(true)
                            if (signal == "FLAG") {
                                Thread.currentThread().interrupt()
                            } else {
                                val problem = when (signal) {
                                    "ERROR" -> Error("Synthetic private fatal detail.")
                                    "CANCELLATION" -> CancellationException("Synthetic private cancellation detail.")
                                    else -> InterruptedException("Synthetic private interrupt detail.")
                                }
                                problem.initCause(IllegalStateException("Synthetic private cause."))
                                problem.addSuppressed(IllegalStateException("Synthetic private suppressed detail."))
                                throw problem
                            }
                        }
                    })
                }
            }
            val failure = try {
                assertThrows<PersistencePhaseException> { f.phases.applyForContinuation(f.verification.prepared, f.proof) }
            } finally {
                f.afterStep = {}
                val interrupted = Thread.interrupted()
                if (signal in setOf("INTERRUPTION", "FLAG")) assertTrue(interrupted)
            }
            assertTrue(fired.get(), signal)
            val original = f.observations.last().second.phase
            assertTrue(original.ownerDeleteAllApply.completed())
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, original.databaseOutcome())
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, failure.databaseOutcome)
            assertTrue(failure.cleanupProven)
            assertRedacted(failure)
            f.assertReleased()
        }
    }

    @Test
    fun `unresolved original cleanup cannot release pending even after foreign checks or later exact reconciliation`() = withFixture { f ->
        val captured = f.store.capture(f.verification.prepared, f.proof)
        val phase = f.auth.ownership.enterComplaintOwnerDeleteAllApply()
        var operation: ComplaintOwnerDeleteAllApplyOperation? = null
        val sentinelKey = Any()
        val sentinel = Any()
        var sentinelBound = false
        try {
            try {
                phase.begin()
                operation = f.store.apply(captured)
                TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun afterCommit() {
                        // Existing containment fault: a genuinely nonempty original Spring map,
                        // never a made-up cleanup flag, substitute phase or fake database outcome.
                        TransactionSynchronizationManager.bindResource(sentinelKey, sentinel)
                        sentinelBound = true
                        throw IllegalStateException("Synthetic completion failure with unresolved Spring cleanup.")
                    }
                })
                phase.commit()
            } catch (problem: Throwable) {
                phase.recordFailure(problem)
            } finally {
                phase.finish()
            }
            val retained = checkNotNull(operation)
            assertTrue(retained.completedFor(phase))
            assertTrue(phase.quarantined())
            assertSame(phase, PersistencePhaseOwnership.current())
            val refused = assertThrows<PersistencePhaseException> { retained.continuationResult }
            assertEquals(PersistencePhaseFailureCode.CLEANUP_UNRESOLVED, refused.code)
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, refused.databaseOutcome)
            assertFalse(refused.cleanupProven)
            assertRedacted(refused)
            OwnedCallerTestScope().use { callers ->
                assertTrue(
                    callers.launch {
                        requireConnectionFree()
                        val foreign = assertThrows<PersistencePhaseException> { retained.continuationResult }
                        assertFalse(foreign.cleanupProven)
                        assertRedacted(foreign)
                        requireConnectionFree()
                        true
                    }.value(),
                )
            }
            assertSame(phase, PersistencePhaseOwnership.current())
        } finally {
            if (sentinelBound) assertSame(sentinel, TransactionSynchronizationManager.unbindResource(sentinelKey))
            requireConnectionFree() // Only the original caller can now settle its genuine retained quarantine.
        }
        val later = assertThrows<PersistencePhaseException> { checkNotNull(operation).continuationResult }
        assertEquals(PersistencePhaseFailureCode.CLEANUP_UNRESOLVED, later.code)
        assertTrue(later.cleanupProven) // A later receipt cannot rewrite the failed attempt's outcome.
        assertRedacted(later)
        f.assertReleased()
    }

    private fun withFixture(test: (OwnerDeleteAllApplyFixture) -> Unit) = withOwnerDeleteAllAuthorization(database.value) { auth ->
        val candidate = auth.enrolled()
        test(OwnerDeleteAllApplyFixture(auth, candidate, paidApplyContent(auth, candidate, 1)))
    }

    private fun assertRedacted(failure: PersistencePhaseException) {
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
        assertEquals("Persistence phase refused.", failure.message)
    }
}
