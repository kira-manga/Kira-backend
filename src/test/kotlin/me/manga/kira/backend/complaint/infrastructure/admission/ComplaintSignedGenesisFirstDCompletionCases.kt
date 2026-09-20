package me.manga.kira.backend.complaint.infrastructure.admission

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

/** Real first-D write and actual commit/afterCommit/original-release cuts, on the existing connected fixture only. */
internal class ComplaintSignedGenesisFirstDCompletionCases(private val f: ComplaintDesiredInstallationFixture) {
    fun failureCannotEmitSuccess(cut: DesiredInstallationCompletionCut) {
        val genesis = f.firstDGenesis()
        genesis.stageSigned()
        f.stopRuntimeWithoutWaiting()
        val before = f.control()
        val preserved = f.firstDPreservedControl()
        val history = f.historyAndCounters()
        val fault = Fault()
        val invocation = f.firstDInvocation { probe ->
            probe.afterSql = { step ->
                if (step === SignedGenesisFirstDSqlStep.REREAD) {
                    val retained = probe.retainedOperation()
                    assertNull(fault.operation)
                    fault.operation = retained
                    TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                        override fun beforeCommit(readOnly: Boolean) = probe.preserveAssertions {
                            assertFalse(readOnly)
                            assertTrue(retained.completedFor(probe.phase))
                            refusedResult(retained, PersistenceDatabaseOutcome.NONE, cleanup = false)
                            fault.beforeCommit = true
                        }

                        override fun afterCommit() = probe.preserveAssertions {
                            assertTrue(fault.beforeCommit)
                            refusedResult(retained, PersistenceDatabaseOutcome.COMMITTED, cleanup = false)
                            fault.afterCommit = true
                            if (cut === DesiredInstallationCompletionCut.UNRESOLVED_RELEASE) {
                                TransactionSynchronizationManager.bindResource(fault.key, fault.sentinel)
                                fault.bound = true
                            }
                            error("Synthetic first-D completion-tail canary.")
                        }
                    })
                }
            }
        }
        withDeferredCommit(cut) {
            try {
                val failure = assertThrows<ComplaintDesiredInstallationExceptionV1> { invocation.execute() }
                val quarantined = cut === DesiredInstallationCompletionCut.UNRESOLVED_RELEASE
                assertEquals(
                    if (quarantined) ComplaintDesiredInstallationFailureV1.CLEANUP_UNPROVEN else ComplaintDesiredInstallationFailureV1.DATABASE_REFUSED,
                    failure.code,
                )
                assertTrue(fault.beforeCommit)
                assertEquals(cut !== DesiredInstallationCompletionCut.DEFERRED_COMMIT, fault.afterCommit)
                val probe = checkNotNull(invocation.probe)
                assertEquals(firstDSteps(write = true), probe.steps)
                val observed = probe.observations.values.single()
                val resultFailure = refusedResult(checkNotNull(fault.operation), outcome(cut), cleanup = !quarantined)
                if (quarantined) {
                    assertEquals(PersistencePhaseFailureCode.CLEANUP_UNRESOLVED, resultFailure.code)
                    assertSame(fault.sentinel, TransactionSynchronizationManager.getResource(fault.key))
                    assertSame(observed.phase, PersistencePhaseOwnership.current())
                    assertTrue(observed.phase.quarantined())
                    assertEquals(1, probe.coordinator.activeSnapshotOwners())
                } else {
                    firstDReleased(observed, outcome(cut))
                }
            } finally {
                fault.removeSentinel()
                requireConnectionFree() // The original caller alone reconciles its actual quarantine; no DB outcome is rewritten.
                invocation.fixtureCleanup()
            }
        }
        firstDReleased(checkNotNull(invocation.probe).observations.values.single(), outcome(cut))
        refusedResult(checkNotNull(fault.operation), outcome(cut), cleanup = true)
        assertEquals(preserved, f.firstDPreservedControl())
        assertEquals(history, f.historyAndCounters())
        if (cut === DesiredInstallationCompletionCut.DEFERRED_COMMIT) assertEquals(before, f.control()) else f.assertFirstDSelected(invocation)
        assertDeadOwner(invocation, cut)

        val retryBefore = f.control()
        val retry = f.firstDInvocation()
        assertNotSame(invocation.operator, retry.operator)
        val result = retry.execute()
        val shouldWrite = cut === DesiredInstallationCompletionCut.DEFERRED_COMMIT
        assertEquals(
            if (shouldWrite) ComplaintSignedGenesisFirstDTransitionV1.SELECTED else ComplaintSignedGenesisFirstDTransitionV1.ALREADY_SELECTED,
            result.transition,
        )
        assertEquals(firstDSteps(shouldWrite), checkNotNull(retry.probe).steps)
        f.assertFirstDSelected(retry)
        firstDReleased(checkNotNull(retry.probe).observations.values.single())
        if (!shouldWrite) assertEquals(retryBefore, f.control())
        assertEquals(preserved, f.firstDPreservedControl())
        assertEquals(history, f.historyAndCounters())
        assertDeadOwner(invocation, cut)
        refusedResult(checkNotNull(fault.operation), outcome(cut), cleanup = true)
    }

    private fun assertDeadOwner(invocation: SignedGenesisFirstDInvocation, cut: DesiredInstallationCompletionCut) {
        val steps = checkNotNull(invocation.probe).steps.toList()
        val requests = invocation.http.requests.toList()
        val before = f.control()
        val failure = assertThrows<ComplaintDesiredInstallationExceptionV1> { invocation.execute() }
        assertEquals(
            if (cut ===
                DesiredInstallationCompletionCut.UNRESOLVED_RELEASE
            ) {
                ComplaintDesiredInstallationFailureV1.CLEANUP_UNPROVEN
            } else {
                ComplaintDesiredInstallationFailureV1.PROCESS_REFUSED
            },
            failure.code,
        )
        assertEquals(steps, checkNotNull(invocation.probe).steps)
        assertEquals(requests, invocation.http.requests)
        assertEquals(before, f.control())
        requireConnectionFree()
    }

    private fun refusedResult(
        operation: ComplaintSignedGenesisFirstDOperationV1,
        expected: PersistenceDatabaseOutcome,
        cleanup: Boolean,
    ): PersistencePhaseException {
        val failure = assertThrows<PersistencePhaseException> { operation.releasedResult() }
        assertEquals(expected, failure.databaseOutcome)
        assertEquals(cleanup, failure.cleanupProven)
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty() && failure.stackTrace.isEmpty())
        return failure
    }

    private fun withDeferredCommit(cut: DesiredInstallationCompletionCut, work: () -> Unit) {
        if (cut !== DesiredInstallationCompletionCut.DEFERRED_COMMIT) {
            work()
            return
        }
        val name = "kira_first_d_commit_${UUID.randomUUID().toString().replace("-", "")}"
        val operator = ComplaintDesiredInstallationFixture.OPERATOR
        f.independentTransaction { connection, jdbc ->
            // Privileged, bounded fixture obstruction only. The actual operator is not given TEMP/DDL/history privileges.
            jdbc.execute(
                "CREATE FUNCTION public.$name() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN " +
                    "IF session_user = '$operator' AND current_user = '$operator' THEN " +
                    "RAISE EXCEPTION 'Synthetic first-D deferred-commit canary.' USING ERRCODE = '23000'; " +
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

    private fun outcome(cut: DesiredInstallationCompletionCut): PersistenceDatabaseOutcome =
        if (cut === DesiredInstallationCompletionCut.DEFERRED_COMMIT) PersistenceDatabaseOutcome.UNKNOWN else PersistenceDatabaseOutcome.COMMITTED

    private class Fault {
        var operation: ComplaintSignedGenesisFirstDOperationV1? = null
        var beforeCommit = false
        var afterCommit = false
        val key = Any()
        val sentinel = Any()
        var bound = false
        fun removeSentinel() {
            if (bound) {
                assertSame(sentinel, TransactionSynchronizationManager.unbindResource(key))
                bound = false
            }
        }
    }
}
