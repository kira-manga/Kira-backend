package me.manga.kira.backend.complaint.infrastructure.admission

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.StepUpPhaseObservation
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

internal enum class DesiredInstallationCompletionCut { DEFERRED_COMMIT, AFTER_COMMIT, UNRESOLVED_RELEASE }

/** Existing connected IT only: actual pristine bootstrap, original commit/holder and fresh independently authenticated retries. */
internal class ComplaintDesiredInstallationCompletionCases(private val f: ComplaintDesiredInstallationFixture) {
    fun commitAndReleaseFailure(cut: DesiredInstallationCompletionCut) {
        requireConnectionFree()
        val before = f.control()
        val preserved = outsideBootstrap()
        val history = f.historyAndCounters()
        assertEquals(
            true,
            f.observer.queryForObject(
                "SELECT desired_generation = 1 AND desired_configuration_hash IS NULL AND accepted_catalog_generation IS NULL " +
                    "FROM public.complaint_journal_control WHERE data_scope_id = ?",
                Boolean::class.java,
                ComplaintDataScope.LIVE.id,
            ),
        )
        val fault = CompletionFault(cut)
        val invocation = instrumentedInvocation(fault)
        withDeferredCommitTrigger(cut) {
            try {
                refused(
                    if (cut == DesiredInstallationCompletionCut.UNRESOLVED_RELEASE) {
                        ComplaintDesiredInstallationFailureV1.CLEANUP_UNPROVEN
                    } else {
                        ComplaintDesiredInstallationFailureV1.DATABASE_REFUSED
                    },
                ) { invocation.execute() }
                assertFailedOriginal(invocation, fault)
            } finally {
                fault.removeOwnSentinel()
                requireConnectionFree() // Only this original caller settles its actual quarantine; no outcome is rewritten.
                invocation.fixtureCleanup()
            }
        }

        val original = checkNotNull(fault.operation)
        val observed = checkNotNull(invocation.probe).observations.values.single()
        assertPhaseRefusal(original, outcome(cut), cleanup = true)
        assertReleased(invocation, observed)
        assertEquals(preserved, outsideBootstrap())
        assertEquals(history, f.historyAndCounters())
        if (cut == DesiredInstallationCompletionCut.DEFERRED_COMMIT) {
            assertEquals(before, f.control(), "UNKNOWN is not a bootstrap receipt; the independent durable row is unchanged.")
        } else {
            assertInstalledHash(invocation)
        }
        assertOriginalCannotRevive(invocation, cut)

        val beforeRetry = f.control()
        val retry = f.invocation()
        try {
            assertNotSame(invocation.operator, retry.operator)
            val result = retry.execute()
            val expected = if (cut == DesiredInstallationCompletionCut.DEFERRED_COMMIT) {
                ComplaintDesiredInstallationTransitionV1.BOOTSTRAPPED
            } else {
                ComplaintDesiredInstallationTransitionV1.ALREADY_SELECTED
            }
            assertEquals(expected, result.transition)
            assertEquals(1L, result.desiredGeneration)
            assertInstalledHash(retry)
            assertEquals(bootstrapSteps(write = cut == DesiredInstallationCompletionCut.DEFERRED_COMMIT), checkNotNull(retry.probe).steps)
            val retryObserved = checkNotNull(retry.probe).observations.values.single()
            assertNotSame(observed.phase, retryObserved.phase)
            assertNotSame(observed.lease, retryObserved.lease)
            assertNotEquals(observed.identity.second, retryObserved.identity.second)
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, retryObserved.lease.completion.databaseOutcome())
            assertReleased(retry, retryObserved)
            if (expected == ComplaintDesiredInstallationTransitionV1.ALREADY_SELECTED) assertEquals(beforeRetry, f.control())
            assertEquals(preserved, outsideBootstrap())
            assertEquals(history, f.historyAndCounters())
        } finally {
            retry.fixtureCleanup()
        }
        assertOriginalCannotRevive(invocation, cut)
        assertPhaseRefusal(original, outcome(cut), cleanup = true)
    }

    private fun instrumentedInvocation(fault: CompletionFault): DesiredInstallationInvocation = f.invocation { probe ->
        probe.afterSql = { path, step ->
            assertEquals(PersistencePhasePath.COMPLAINT_DESIRED_BOOTSTRAP, path)
            if (step == DesiredInstallationSqlStep.READ_CONTROL) {
                assertNull(fault.operation)
                val retained = probe.retainedOperation()
                fault.operation = retained // Capture while this actual phase is current; never fabricate an operation.
                TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun beforeCommit(readOnly: Boolean) = probe.preserveAssertions {
                        assertFalse(readOnly)
                        assertTrue(retained.completedFor(probe.phase))
                        // READ_CONTROL itself precedes COMPLETE. A getter there would poison work rather than test the commit cut.
                        assertPhaseRefusal(retained, PersistenceDatabaseOutcome.NONE, cleanup = false)
                        fault.beforeCommitObserved = true
                    }

                    override fun afterCommit() = probe.preserveAssertions {
                        assertTrue(fault.beforeCommitObserved)
                        assertPhaseRefusal(retained, PersistenceDatabaseOutcome.COMMITTED, cleanup = false)
                        fault.afterCommitObserved = true
                        if (fault.cut == DesiredInstallationCompletionCut.UNRESOLVED_RELEASE) {
                            TransactionSynchronizationManager.bindResource(fault.key, fault.sentinel)
                            fault.sentinelBound = true
                        }
                        if (fault.cut != DesiredInstallationCompletionCut.DEFERRED_COMMIT) {
                            error("Synthetic desired-install private completion-tail canary.")
                        }
                    }
                })
            }
        }
    }

    private fun assertFailedOriginal(invocation: DesiredInstallationInvocation, fault: CompletionFault) {
        val probe = checkNotNull(invocation.probe)
        val observed = probe.observations.values.single()
        assertTrue(fault.beforeCommitObserved)
        assertEquals(fault.cut != DesiredInstallationCompletionCut.DEFERRED_COMMIT, fault.afterCommitObserved)
        assertEquals(bootstrapSteps(write = true), probe.steps)
        assertEquals(outcome(fault.cut), observed.lease.completion.databaseOutcome())
        val quarantined = fault.cut == DesiredInstallationCompletionCut.UNRESOLVED_RELEASE
        val getter = assertPhaseRefusal(checkNotNull(fault.operation), outcome(fault.cut), cleanup = !quarantined)
        if (quarantined) {
            assertTrue(fault.sentinelBound)
            assertSame(fault.sentinel, TransactionSynchronizationManager.getResource(fault.key))
            assertSame(observed.phase, PersistencePhaseOwnership.current())
            assertTrue(observed.phase.quarantined())
            assertEquals(1, probe.coordinator.activeSnapshotOwners())
            assertEquals(PersistencePhaseFailureCode.CLEANUP_UNRESOLVED, getter.code)
            // No foreign observer/JdbcTemplate may run until the original sentinel and quarantine have settled.
        } else {
            assertReleased(invocation, observed)
        }
    }

    private fun assertOriginalCannotRevive(invocation: DesiredInstallationInvocation, cut: DesiredInstallationCompletionCut) {
        requireConnectionFree()
        val probe = checkNotNull(invocation.probe)
        val steps = probe.steps.toList()
        val requests = invocation.http.requests.toList()
        val clients = invocation.http.createdClients to invocation.http.closedClients
        val before = f.control()
        val stickyCleanup = cut == DesiredInstallationCompletionCut.UNRESOLVED_RELEASE
        refused(
            if (stickyCleanup) ComplaintDesiredInstallationFailureV1.CLEANUP_UNPROVEN else ComplaintDesiredInstallationFailureV1.PROCESS_REFUSED,
        ) { invocation.execute() }
        if (stickyCleanup) {
            refused(ComplaintDesiredInstallationFailureV1.CLEANUP_UNPROVEN) { invocation.operator.close() }
        } else {
            invocation.operator.close()
        }
        assertEquals(steps, probe.steps)
        assertEquals(requests, invocation.http.requests)
        assertEquals(clients, invocation.http.createdClients to invocation.http.closedClients)
        assertEquals(before, f.control())
        requireConnectionFree()
    }

    private fun assertReleased(invocation: DesiredInstallationInvocation, observed: StepUpPhaseObservation) {
        assertTrue(observed.lease.completion.quiescent())
        assertFalse(observed.phase.quarantined())
        assertEquals(0, checkNotNull(invocation.probe).coordinator.activeSnapshotOwners())
        requireConnectionFree()
    }

    private fun assertInstalledHash(invocation: DesiredInstallationInvocation) = assertArrayEquals(
        checkNotNull(invocation.target).configurationHashBytes(),
        f.observer.queryForObject(
            "SELECT desired_configuration_hash FROM public.complaint_journal_control WHERE data_scope_id = ?",
            ByteArray::class.java,
            ComplaintDataScope.LIVE.id,
        ),
    )

    private fun outsideBootstrap(): String = checkNotNull(
        f.observer.queryForObject(
            "SELECT (to_jsonb(c) - ARRAY['desired_configuration_hash','updated_at'])::text " +
                "FROM public.complaint_journal_control c WHERE data_scope_id = ?",
            String::class.java,
            ComplaintDataScope.LIVE.id,
        ),
    )

    private fun withDeferredCommitTrigger(cut: DesiredInstallationCompletionCut, work: () -> Unit) {
        if (cut != DesiredInstallationCompletionCut.DEFERRED_COMMIT) {
            work()
            return
        }
        requireConnectionFree()
        val name = "kira_desired_commit_${UUID.randomUUID().toString().replace("-", "")}"
        val operator = ComplaintDesiredInstallationFixture.OPERATOR
        // Privileged fixture setup is outside the operator command. Its actual login receives no DDL or TEMP grant.
        f.independentTransaction { connection, jdbc ->
            jdbc.execute(
                "CREATE FUNCTION public.$name() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN " +
                    "IF session_user = '$operator' AND current_user = '$operator' THEN " +
                    "RAISE EXCEPTION 'Synthetic desired-install private deferred-commit canary.' USING ERRCODE = '23000'; " +
                    "END IF; RETURN NEW; END; $$",
            )
            jdbc.execute(
                "CREATE CONSTRAINT TRIGGER $name AFTER UPDATE ON public.complaint_journal_control " +
                    "DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.$name()",
            )
            connection.commit()
        }
        try {
            work()
        } finally {
            requireConnectionFree()
            f.independentTransaction { connection, jdbc ->
                jdbc.execute("DROP TRIGGER $name ON public.complaint_journal_control")
                jdbc.execute("DROP FUNCTION public.$name()")
                connection.commit()
            }
        }
    }

    private fun bootstrapSteps(write: Boolean): List<Pair<PersistencePhasePath, DesiredInstallationSqlStep>> {
        val steps = listOf(DesiredInstallationSqlStep.AUTHENTICATE, DesiredInstallationSqlStep.LOCK_CONTROL) +
            (if (write) listOf(DesiredInstallationSqlStep.CHECK_PENDING, DesiredInstallationSqlStep.WRITE_CONTROL) else emptyList()) +
            DesiredInstallationSqlStep.READ_CONTROL
        return steps.map { PersistencePhasePath.COMPLAINT_DESIRED_BOOTSTRAP to it }
    }

    private fun outcome(cut: DesiredInstallationCompletionCut): PersistenceDatabaseOutcome =
        if (cut == DesiredInstallationCompletionCut.DEFERRED_COMMIT) PersistenceDatabaseOutcome.UNKNOWN else PersistenceDatabaseOutcome.COMMITTED

    private fun assertPhaseRefusal(
        operation: ComplaintDesiredInstallOperationV1,
        outcome: PersistenceDatabaseOutcome,
        cleanup: Boolean,
    ): PersistencePhaseException {
        val failure = assertThrows<PersistencePhaseException> { operation.releasedResult() }
        assertEquals(outcome, failure.databaseOutcome)
        assertEquals(cleanup, failure.cleanupProven)
        assertEquals("Persistence phase refused.", failure.message)
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
        assertTrue(failure.stackTrace.isEmpty())
        return failure
    }

    private fun refused(code: ComplaintDesiredInstallationFailureV1, work: () -> Unit) {
        val failure = assertThrows<ComplaintDesiredInstallationExceptionV1> { work() }
        assertEquals(code, failure.code)
        assertEquals("Desired configuration installation refused.", failure.message)
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
        assertTrue(failure.stackTrace.isEmpty())
    }

    private class CompletionFault(val cut: DesiredInstallationCompletionCut) {
        var operation: ComplaintDesiredInstallOperationV1? = null
        var beforeCommitObserved = false
        var afterCommitObserved = false
        val key = Any()
        val sentinel = Any()
        var sentinelBound = false

        fun removeOwnSentinel() {
            if (sentinelBound) {
                assertSame(sentinel, TransactionSynchronizationManager.unbindResource(key))
                sentinelBound = false
            }
        }
    }
}
