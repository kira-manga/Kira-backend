package me.manga.kira.backend.complaint.infrastructure.transaction

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.complaint.domain.ComplaintAdminContentFailure
import me.manga.kira.backend.complaint.domain.ComplaintAdminContentRejected
import me.manga.kira.backend.complaint.domain.ComplaintAdminContentTuple
import me.manga.kira.backend.complaint.domain.rejectAdminContent
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminContentCandidate
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminContentClaimWaitTimeout
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminContentObservation
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminContentOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminReadIdentity
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminReadOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminReadRows
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintAdminContentStore
import me.manga.kira.backend.security.ComplaintAdmittedAdminContent

/** Existing authentication plus one ordinary observation/writer; no deletion permit/fence or alternate pool. */
internal class ComplaintAdminContentPhaseExecutor(private val ownership: PersistencePhaseOwnership, private val store: JdbcComplaintAdminContentStore) {
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
    fun preflight(identity: ComplaintAdminReadIdentity, tuple: ComplaintAdminContentTuple): ComplaintAdminContentObservation {
        store.requireResources(ownership)
        val phase = ownership.enterComplaintAdminEditPreflight()
        var operation: ComplaintAdminContentOperation? = null
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
    fun edit(
        identity: ComplaintAdminReadIdentity,
        candidate: ComplaintAdminContentCandidate,
        proof: String?,
        admission: ComplaintAdmittedAdminContent,
    ): ComplaintAdminContentObservation {
        store.requireResources(ownership)
        val phase = ownership.enterComplaintAdminEdit()
        var operation: ComplaintAdminContentOperation? = null
        var refusal: ComplaintAdminContentFailure? = null
        try {
            phase.adminContent.bindEdit(admission)
            store.bind(phase)
            phase.begin()
            operation = store.edit(identity, candidate, proof)
            phase.commit()
        } catch (problem: ComplaintAdminContentClaimWaitTimeout) {
            phase.recordFailure(problem)
            refusal = ComplaintAdminContentFailure.IN_PROGRESS
        } catch (problem: ComplaintAdminContentRejected) {
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
        if (refusal != null && rolledBack && failure.code === PersistencePhaseFailureCode.WORK_FAILED) rejectAdminContent(refusal)
        throw failure
    }

    override fun toString(): String = "ComplaintAdminContentPhaseExecutor(TEST-only,ordinary)"
}
