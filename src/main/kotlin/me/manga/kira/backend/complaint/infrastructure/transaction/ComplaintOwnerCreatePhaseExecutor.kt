package me.manga.kira.backend.complaint.infrastructure.transaction

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationFailure
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationRejected
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationTuple
import me.manga.kira.backend.complaint.domain.ComplaintPlatform
import me.manga.kira.backend.complaint.domain.rejectOwnerOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerClaimWaitTimeout
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerCreateCandidate
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerCreateOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerOperationIdentity
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerOperationObservation
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerCreateStore
import me.manga.kira.backend.security.ComplaintAdmittedOwnerCreate

/** Fixed named phases, no application callback, alternate manager or independent physical owner. */
internal class ComplaintOwnerCreatePhaseExecutor(private val ownership: PersistencePhaseOwnership, private val store: JdbcComplaintOwnerCreateStore) {
    fun authenticate(identity: ComplaintOwnerOperationIdentity): ComplaintOwnerOperationObservation =
        read(identity, null, PersistencePhasePath.COMPLAINT_OWNER_OPERATION_AUTHENTICATION)

    fun preflight(identity: ComplaintOwnerOperationIdentity, tuple: ComplaintOwnerOperationTuple): ComplaintOwnerOperationObservation =
        read(identity, tuple, PersistencePhasePath.COMPLAINT_OWNER_CREATE_PREFLIGHT)

    fun status(identity: ComplaintOwnerOperationIdentity, tuple: ComplaintOwnerOperationTuple): ComplaintOwnerOperationObservation =
        read(identity, tuple, PersistencePhasePath.COMPLAINT_OWNER_OPERATION_STATUS)

    @Suppress("TooGenericExceptionCaught")
    private fun read(
        identity: ComplaintOwnerOperationIdentity,
        tuple: ComplaintOwnerOperationTuple?,
        path: PersistencePhasePath,
    ): ComplaintOwnerOperationObservation {
        val phase = when (path) {
            PersistencePhasePath.COMPLAINT_OWNER_OPERATION_AUTHENTICATION -> ownership.enterComplaintOwnerOperationAuthentication()
            PersistencePhasePath.COMPLAINT_OWNER_CREATE_PREFLIGHT -> ownership.enterComplaintOwnerCreatePreflight()
            PersistencePhasePath.COMPLAINT_OWNER_OPERATION_STATUS -> ownership.enterComplaintOwnerOperationStatus()
            else -> throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
        }
        var operation: ComplaintOwnerCreateOperation? = null
        try {
            phase.begin()
            operation = when (path) {
                PersistencePhasePath.COMPLAINT_OWNER_OPERATION_AUTHENTICATION -> store.authenticate(identity)
                PersistencePhasePath.COMPLAINT_OWNER_CREATE_PREFLIGHT -> store.preflight(identity, checkNotNull(tuple))
                PersistencePhasePath.COMPLAINT_OWNER_OPERATION_STATUS -> store.status(identity, checkNotNull(tuple))
                else -> throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            }
            phase.commit()
        } catch (problem: Throwable) {
            phase.recordFailure(problem)
        } finally {
            phase.finish()
        }
        return (operation ?: throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)).result
    }

    @Suppress("TooGenericExceptionCaught")
    fun create(
        identity: ComplaintOwnerOperationIdentity,
        candidate: ComplaintOwnerCreateCandidate,
        platform: ComplaintPlatform,
        admission: ComplaintAdmittedOwnerCreate,
    ): ComplaintOwnerOperationObservation {
        val phase = ownership.enterComplaintOwnerCreate()
        var operation: ComplaintOwnerCreateOperation? = null
        var refusal: ComplaintOwnerOperationFailure? = null
        try {
            phase.ownerOperation.bindCreate(admission)
            phase.begin()
            operation = store.create(identity, candidate, platform)
            phase.commit()
        } catch (problem: ComplaintOwnerClaimWaitTimeout) {
            phase.recordFailure(problem)
            refusal = ComplaintOwnerOperationFailure.IN_PROGRESS
        } catch (problem: ComplaintOwnerOperationRejected) {
            phase.recordFailure(problem)
            refusal = problem.failure
        } catch (problem: Throwable) {
            phase.recordFailure(problem)
        } finally {
            phase.finish()
        }
        if (operation != null) return operation.result
        val failure = phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
        // Even a bounded nonterminal error is not publishable through ambiguous rollback/cleanup.
        val rolledBack = failure.cleanupProven && failure.databaseOutcome === PersistenceDatabaseOutcome.ROLLED_BACK
        if (refusal != null && rolledBack && failure.code === PersistencePhaseFailureCode.WORK_FAILED) {
            rejectOwnerOperation(refusal)
        }
        throw failure
    }

    override fun toString(): String = "ComplaintOwnerCreatePhaseExecutor(dormant)"
}
