package me.manga.kira.backend.complaint.infrastructure.transaction

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteReceipt
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationFailure
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationRejected
import me.manga.kira.backend.complaint.domain.rejectOwnerOperation
import me.manga.kira.backend.complaint.infrastructure.CommittedTestOwnerDeleteVerificationV1
import me.manga.kira.backend.complaint.infrastructure.CommittedTestOwnerDeleteWork
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerClaimWaitTimeout
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeleteApplyOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeleteAuthorizationOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeleteCandidate
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeleteObservation
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeleteVerificationOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerOperationIdentity
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteApplyStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteVerificationStore
import me.manga.kira.backend.complaint.infrastructure.TestOwnerDeleteApplyInputV1
import me.manga.kira.backend.complaint.infrastructure.TestOwnerDeleteAuthorizationV1
import me.manga.kira.backend.complaint.infrastructure.TestOwnerDeleteLocalGraphV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestOwnerDeleteJournalPublisherFactoryV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestOwnerDeleteJournalReadbackV1
import me.manga.kira.backend.security.ComplaintAdmittedOwnerDelete

/** Fixed existing-deletion-holder phases only; all results follow real commit AND original physical release. */
internal class ComplaintOwnerDeletePhaseExecutor(
    private val ownership: PersistencePhaseOwnership,
    private val store: JdbcComplaintOwnerDeleteStore,
    private val reads: ComplaintOwnerDeleteReadPhaseExecutor,
    private val verification: JdbcComplaintOwnerDeleteVerificationStore,
    private val apply: JdbcComplaintOwnerDeleteApplyStore,
) {
    fun requirePublisher(publisher: TestOwnerDeleteJournalPublisherFactoryV1) = publisher.requireBinding(store)
    fun requireGraph(graph: TestOwnerDeleteLocalGraphV1, selectedReads: ComplaintOwnerDeleteReadPhaseExecutor) { check(store.graph === graph && reads === selectedReads); graph.requireUnchanged() }
    @Suppress("TooGenericExceptionCaught")
    fun authorize(identity: ComplaintOwnerOperationIdentity, candidate: ComplaintOwnerDeleteCandidate, preflight: ComplaintOwnerDeleteObservation, admitted: ComplaintAdmittedOwnerDelete): TestOwnerDeleteAuthorizationV1 {
        reads.requirePreflight(preflight, identity, candidate.tuple)
        check(!preflight.authorized)
        val phase = ownership.enterComplaintOwnerDeleteAuthorize(admitted, candidate.tuple)
        var operation: ComplaintOwnerDeleteAuthorizationOperation? = null
        var refusal: ComplaintOwnerOperationFailure? = null
        try {
            phase.ownerDelete.bindAuthorize(admitted)
            phase.begin()
            operation = store.authorize(identity, candidate, checkNotNull(preflight.platform))
            phase.commit()
        } catch (problem: ComplaintOwnerClaimWaitTimeout) { phase.recordFailure(problem); refusal = ComplaintOwnerOperationFailure.IN_PROGRESS }
        catch (problem: ComplaintOwnerOperationRejected) { phase.recordFailure(problem); refusal = problem.failure }
        catch (problem: Throwable) { phase.recordFailure(problem) }
        finally { phase.finish() }
        if (operation != null) return operation.result
        val failure = phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
        if (refusal != null && failure.cleanupProven && failure.databaseOutcome === PersistenceDatabaseOutcome.ROLLED_BACK && failure.code === PersistencePhaseFailureCode.WORK_FAILED)
            rejectOwnerOperation(refusal)
        throw failure
    }
    @Suppress("TooGenericExceptionCaught")
    fun reload(identity: ComplaintOwnerOperationIdentity, candidate: ComplaintOwnerDeleteCandidate, preflight: ComplaintOwnerDeleteObservation): TestOwnerDeleteAuthorizationV1 {
        reads.requirePreflight(preflight, identity, candidate.tuple)
        check(preflight.authorized)
        val phase = ownership.enterComplaintOwnerDeleteReload()
        var operation: ComplaintOwnerDeleteAuthorizationOperation? = null
        try { phase.begin(); operation = store.reload(identity, candidate, checkNotNull(preflight.platform)); phase.commit() }
        catch (problem: Throwable) { phase.recordFailure(problem) }
        finally { phase.finish() }
        return (operation ?: throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)).result
    }
    @Suppress("TooGenericExceptionCaught")
    fun verify(readback: TestOwnerDeleteJournalReadbackV1): CommittedTestOwnerDeleteVerificationV1 {
        val input = verification.capture(readback)
        val phase = ownership.enterComplaintOwnerDeleteVerify()
        var operation: ComplaintOwnerDeleteVerificationOperation? = null
        try { phase.begin(); operation = verification.verify(input); phase.commit() }
        catch (problem: Throwable) { phase.recordFailure(problem) }
        finally { phase.finish() }
        return (operation ?: throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)).result
    }
    fun resume(work: CommittedTestOwnerDeleteWork.RecordedVerified): CommittedTestOwnerDeleteVerificationV1 = verification.resume(work)
    fun apply(work: CommittedTestOwnerDeleteWork, proof: CommittedTestOwnerDeleteVerificationV1): ComplaintOwnerDeleteReceipt = executeApply(apply.capture(work, proof)).result
    fun recover(readback: TestOwnerDeleteJournalReadbackV1) = executeApply(apply.captureRecovery(readback)).requireRecovered()
    @Suppress("TooGenericExceptionCaught")
    private fun executeApply(input: TestOwnerDeleteApplyInputV1): ComplaintOwnerDeleteApplyOperation {
        val phase = ownership.enterComplaintOwnerDeleteApply()
        var operation: ComplaintOwnerDeleteApplyOperation? = null
        try { phase.begin(); operation = apply.apply(input); phase.commit() }
        catch (problem: Throwable) { phase.recordFailure(problem) }
        finally { phase.finish() }
        return operation ?: throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
    }
}
