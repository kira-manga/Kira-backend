package me.manga.kira.backend.complaint.infrastructure.transaction

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusFailure
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusRejected
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusTuple
import me.manga.kira.backend.complaint.domain.rejectAdminStatus
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminStatusCandidate
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminStatusClaimWaitTimeout
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminStatusObservation
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminStatusMutation
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminReadIdentity
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminReadOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminReadRows
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintAdminStatusStore
import me.manga.kira.backend.security.ComplaintAdmittedAdminStatus

/** Existing authentication plus one ordinary observation/writer; no deletion permit/fence or alternate pool. */
internal class ComplaintAdminStatusPhaseExecutor(private val ownership: PersistencePhaseOwnership, private val store: JdbcComplaintAdminStatusStore) {
    @Suppress("TooGenericExceptionCaught")
    fun authenticate(identity: ComplaintAdminReadIdentity): ComplaintAdminReadRows {
        store.requireResources(ownership)
        val phase = ownership.enterComplaintAdminReadAuthentication()
        var operation: ComplaintAdminReadOperation? = null
        var registeredRows: ComplaintAdminReadRows? = null
        try {
            store.bind(phase)
            phase.begin()
            operation = store.authenticate(identity)
            registeredRows = store.registeredAuthentication(phase, identity)
            phase.commit()
        } catch (failure: Throwable) {
            phase.recordFailure(failure)
        } finally {
            phase.finish()
        }
        val rows = (operation ?: throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)).result
        if (!rows.contractValid) return rows
        return registeredRows ?: rows
    }

    @Suppress("TooGenericExceptionCaught")
    fun preflight(identity: ComplaintAdminReadIdentity, tuple: ComplaintAdminStatusTuple): ComplaintAdminStatusObservation {
        store.requireResources(ownership)
        val phase = ownership.enterComplaintAdminStatusPreflight()
        var operation: ComplaintAdminStatusMutation? = null
        try {
            store.bind(phase)
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
        candidate: ComplaintAdminStatusCandidate,
        proof: String?,
        admission: ComplaintAdmittedAdminStatus,
    ): ComplaintAdminStatusObservation {
        store.requireResources(ownership)
        val phase = ownership.enterComplaintAdminStatus()
        var operation: ComplaintAdminStatusMutation? = null
        var refusal: ComplaintAdminStatusFailure? = null
        try {
            phase.adminStatus.bindStatus(admission)
            store.bind(phase)
            phase.begin()
            operation = store.change(identity, candidate, proof)
            phase.commit()
        } catch (problem: ComplaintAdminStatusClaimWaitTimeout) {
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

    override fun toString(): String = "ComplaintAdminStatusPhaseExecutor(TEST-only,ordinary)"
}
