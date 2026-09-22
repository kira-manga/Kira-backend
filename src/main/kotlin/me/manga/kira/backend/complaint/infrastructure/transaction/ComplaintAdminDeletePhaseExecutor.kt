package me.manga.kira.backend.complaint.infrastructure.transaction

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteReceipt
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteFailure
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteRejected
import me.manga.kira.backend.complaint.domain.rejectAdminDelete
import me.manga.kira.backend.complaint.infrastructure.CommittedTestAdminDeleteVerificationV1
import me.manga.kira.backend.complaint.infrastructure.CommittedTestAdminDeleteWork
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminDeleteClaimWaitTimeout
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminDeleteApplyOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminDeleteAuthorizationOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminDeleteCandidate
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminDeleteObservation
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminDeleteVerificationOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminReadIdentity
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintAdminDeleteApplyStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintAdminDeleteStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintAdminDeleteVerificationStore
import me.manga.kira.backend.complaint.infrastructure.TestAdminDeleteApplyInputV1
import me.manga.kira.backend.complaint.infrastructure.TestAdminDeleteAuthorizationV1
import me.manga.kira.backend.complaint.infrastructure.TestOwnerDeleteLocalGraphV1
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationLanesV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestAdminDeleteJournalPublisherFactoryV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestOwnerDeleteJournalReadbackV1
import me.manga.kira.backend.security.ComplaintAdmittedAdminErasure

/** Fixed existing-deletion-holder phases only; all results follow real commit AND original physical release. */
internal class ComplaintAdminDeletePhaseExecutor(
    private val ownership: PersistencePhaseOwnership,
    private val store: JdbcComplaintAdminDeleteStore,
    private val reads: ComplaintAdminDeleteReadPhaseExecutor,
    private val verification: JdbcComplaintAdminDeleteVerificationStore,
    private val apply: JdbcComplaintAdminDeleteApplyStore?,
) {
    // The fixed registered HTTP graph constructs no APPLY store. Lower callers retain their original one.
    init { check(apply != null || store.graph.initialDeletion != null) }
    fun requirePublisher(publisher: TestAdminDeleteJournalPublisherFactoryV1) = publisher.requireBinding(store)
    fun requireGraph(graph: TestOwnerDeleteLocalGraphV1, selectedReads: ComplaintAdminDeleteReadPhaseExecutor) { check(store.graph === graph && reads === selectedReads); graph.requireUnchanged() }
    @Suppress("TooGenericExceptionCaught")
    fun authorize(identity: ComplaintAdminReadIdentity, candidate: ComplaintAdminDeleteCandidate, preflight: ComplaintAdminDeleteObservation, proof: String?,
        admitted: ComplaintAdmittedAdminErasure, lane: JournalPublicationLanesV1.TestAdminDeleteReservation? = null): TestAdminDeleteAuthorizationV1 {
        reads.requirePreflight(preflight, identity, candidate.tuple)
        check(!preflight.authorized)
        val original = store.graph.initialDeletion
        original?.let {
            it.requireEntry(ownership, PersistencePhasePath.COMPLAINT_ADMIN_DELETE_AUTHORIZE)
            reads.requireGraph(store.graph)
            checkNotNull(lane).requireAuthorizing(it, store)
        }
        val phase = ownership.enterComplaintAdminDeleteAuthorize(admitted, candidate.tuple)
        var operation: ComplaintAdminDeleteAuthorizationOperation? = null
        var refusal: ComplaintAdminDeleteFailure? = null
        try {
            original?.let { phase.bindInitialAdminDeleteAuthorize(it, store, admitted, checkNotNull(lane)) }
            phase.adminDelete.bindAuthorize(admitted)
            phase.begin()
            operation = store.authorize(identity, candidate, proof)
            phase.commit()
        } catch (problem: ComplaintAdminDeleteClaimWaitTimeout) { phase.recordFailure(problem); refusal = ComplaintAdminDeleteFailure.IN_PROGRESS }
        catch (problem: ComplaintAdminDeleteRejected) { phase.recordFailure(problem); refusal = problem.failure }
        catch (problem: Throwable) { phase.recordFailure(problem) }
        finally { phase.finish() }
        if (operation != null) return operation.result
        val failure = phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
        if (refusal != null && failure.cleanupProven && failure.databaseOutcome === PersistenceDatabaseOutcome.ROLLED_BACK && failure.code === PersistencePhaseFailureCode.WORK_FAILED)
            rejectAdminDelete(refusal)
        throw failure
    }
    @Suppress("TooGenericExceptionCaught")
    fun reload(identity: ComplaintAdminReadIdentity, candidate: ComplaintAdminDeleteCandidate, preflight: ComplaintAdminDeleteObservation): TestAdminDeleteAuthorizationV1 {
        reads.requirePreflight(preflight, identity, candidate.tuple)
        check(preflight.authorized)
        val original = store.graph.initialDeletion
        original?.let {
            it.requireEntry(ownership, PersistencePhasePath.COMPLAINT_ADMIN_DELETE_RELOAD)
            reads.requireGraph(store.graph)
        }
        val phase = ownership.enterComplaintAdminDeleteReload()
        var operation: ComplaintAdminDeleteAuthorizationOperation? = null
        try { original?.let(phase::bindInitialDeletionRead); phase.begin(); operation = store.reload(identity, candidate); phase.commit() }
        catch (problem: Throwable) { phase.recordFailure(problem) }
        finally { phase.finish() }
        return (operation ?: throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)).result
    }
    /** No supplied canonical record can replace the actual private AUTH work and native lane result. */
    @Suppress("TooGenericExceptionCaught")
    fun verify(work: CommittedTestAdminDeleteWork.Prepared, readback: TestOwnerDeleteJournalReadbackV1,
        lane: JournalPublicationLanesV1.TestAdminDeleteReservation): CommittedTestAdminDeleteVerificationV1 {
        val original = checkNotNull(store.graph.initialDeletion)
        original.requireEntry(ownership, PersistencePhasePath.COMPLAINT_ADMIN_DELETE_VERIFY)
        verification.requireBinding(store)
        val input = verification.captureInitial(work, readback, lane)
        val phase = ownership.enterComplaintAdminDeleteVerify()
        var operation: ComplaintAdminDeleteVerificationOperation? = null
        try {
            phase.bindInitialAdminDeleteVerify(original, verification, input)
            phase.begin(); operation = verification.verify(input); phase.commit()
        } catch (problem: Throwable) { phase.recordFailure(problem) }
        finally { phase.finish() }
        return (operation ?: throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)).result
    }
    @Suppress("TooGenericExceptionCaught")
    fun verify(readback: TestOwnerDeleteJournalReadbackV1): CommittedTestAdminDeleteVerificationV1 {
        val input = verification.capture(readback)
        val phase = ownership.enterComplaintAdminDeleteVerify()
        var operation: ComplaintAdminDeleteVerificationOperation? = null
        try { phase.begin(); operation = verification.verify(input); phase.commit() }
        catch (problem: Throwable) { phase.recordFailure(problem) }
        finally { phase.finish() }
        return (operation ?: throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)).result
    }
    fun resume(work: CommittedTestAdminDeleteWork.RecordedVerified): CommittedTestAdminDeleteVerificationV1 = verification.resume(work)
    fun apply(work: CommittedTestAdminDeleteWork, proof: CommittedTestAdminDeleteVerificationV1): ComplaintAdminDeleteReceipt = executeApply(checkNotNull(apply).capture(work, proof)).result
    fun recover(readback: TestOwnerDeleteJournalReadbackV1) = executeApply(checkNotNull(apply).captureRecovery(readback)).requireRecovered()
    @Suppress("TooGenericExceptionCaught")
    private fun executeApply(input: TestAdminDeleteApplyInputV1): ComplaintAdminDeleteApplyOperation {
        val original = checkNotNull(apply)
        val phase = ownership.enterComplaintAdminDeleteApply()
        var operation: ComplaintAdminDeleteApplyOperation? = null
        try { phase.begin(); operation = original.apply(input); phase.commit() }
        catch (problem: Throwable) { phase.recordFailure(problem) }
        finally { phase.finish() }
        return operation ?: throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
    }
}
