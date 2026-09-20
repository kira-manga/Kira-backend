package me.manga.kira.backend.complaint.infrastructure.transaction

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusFailure
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusRejected
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchStatusTuple
import me.manga.kira.backend.complaint.domain.rejectAdminStatus
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminBatchStatusCandidate
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminBatchStatusClaimWaitTimeout
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminBatchStatusObservation
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminBatchStatusMutation
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminReadIdentity
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminReadOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminReadRows
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintAdminBatchStatusStore
import me.manga.kira.backend.security.ComplaintAdmittedAdminBatchStatus

/** Existing authentication plus one ordinary observation/writer; no deletion permit/fence or alternate pool. */
internal class ComplaintAdminBatchStatusPhaseExecutor(private val ownership: PersistencePhaseOwnership, private val store: JdbcComplaintAdminBatchStatusStore) {
    @Suppress("TooGenericExceptionCaught")
    fun authenticate(identity: ComplaintAdminReadIdentity): ComplaintAdminReadRows {
        val phase = ownership.enterComplaintAdminReadAuthentication()
        var operation: ComplaintAdminReadOperation? = null
        try {
            phase.begin()
            operation = store.authenticate(identity)
            phase.commit()
        } catch (failure: Throwable) {
            phase.recordFailure(failure)
        } finally {
            phase.finish()
        }
        return (operation ?: throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)).result
    }

    @Suppress("TooGenericExceptionCaught")
    fun preflight(identity: ComplaintAdminReadIdentity, tuple: ComplaintAdminBatchStatusTuple): ComplaintAdminBatchStatusObservation {
        val phase = ownership.enterComplaintAdminBatchStatusPreflight()
        var operation: ComplaintAdminBatchStatusMutation? = null
        try {
            phase.begin()
            operation = store.preflight(identity, tuple)
            phase.commit()
        } catch (failure: Throwable) {
            phase.recordFailure(failure)
        } finally {
            phase.finish()
        }
        return (operation ?: throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)).result
    }

    @Suppress("TooGenericExceptionCaught")
    fun change(
        identity: ComplaintAdminReadIdentity,
        candidate: ComplaintAdminBatchStatusCandidate,
        proof: String?,
        admission: ComplaintAdmittedAdminBatchStatus,
    ): ComplaintAdminBatchStatusObservation {
        val phase = ownership.enterComplaintAdminBatchStatus()
        var operation: ComplaintAdminBatchStatusMutation? = null
        var refusal: ComplaintAdminStatusFailure? = null
        try {
            phase.adminBatchStatus.bindStatus(admission)
            phase.begin()
            operation = store.change(identity, candidate, proof)
            phase.commit()
        } catch (problem: ComplaintAdminBatchStatusClaimWaitTimeout) {
            phase.recordFailure(problem)
            refusal = ComplaintAdminStatusFailure.IN_PROGRESS
        } catch (problem: ComplaintAdminStatusRejected) {
            phase.recordFailure(problem)
            refusal = problem.failure
        } catch (problem: Throwable) {
            phase.recordFailure(problem)
        } finally {
            phase.finish()
        }
        if (operation != null) return operation.result
        val failure = phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
        val rolledBack = failure.cleanupProven && failure.databaseOutcome === PersistenceDatabaseOutcome.ROLLED_BACK
        if (refusal != null && rolledBack && failure.code === PersistencePhaseFailureCode.WORK_FAILED) rejectAdminStatus(refusal)
        throw failure
    }

    override fun toString(): String = "ComplaintAdminBatchStatusPhaseExecutor(TEST-only,ordinary)"
}
