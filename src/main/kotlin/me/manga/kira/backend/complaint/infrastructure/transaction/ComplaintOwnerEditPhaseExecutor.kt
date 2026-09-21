package me.manga.kira.backend.complaint.infrastructure.transaction

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.complaint.domain.ComplaintOwnerEditTuple
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationFailure
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationRejected
import me.manga.kira.backend.complaint.domain.ComplaintPlatform
import me.manga.kira.backend.complaint.domain.rejectOwnerOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerClaimWaitTimeout
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerEditCandidate
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerEditObservation
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerEditOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerOperationIdentity
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerEditStore
import me.manga.kira.backend.security.ComplaintAdmittedOwnerEdit
import me.manga.kira.backend.security.ComplaintIngressContext

/** Fixed named phases on the existing ordinary owner; no caller callback, alternate manager or retained JDBC result. */
internal class ComplaintOwnerEditPhaseExecutor(private val ownership: PersistencePhaseOwnership, private val store: JdbcComplaintOwnerEditStore) {
    fun authenticate(identity: ComplaintOwnerOperationIdentity, context: ComplaintIngressContext? = null): ComplaintOwnerEditObservation =
        read(identity, null, PersistencePhasePath.COMPLAINT_OWNER_EDIT_AUTHENTICATION, context)

    fun preflight(identity: ComplaintOwnerOperationIdentity, tuple: ComplaintOwnerEditTuple, context: ComplaintIngressContext? = null): ComplaintOwnerEditObservation =
        read(identity, tuple, PersistencePhasePath.COMPLAINT_OWNER_EDIT_PREFLIGHT, context)

    fun status(identity: ComplaintOwnerOperationIdentity, tuple: ComplaintOwnerEditTuple, context: ComplaintIngressContext? = null): ComplaintOwnerEditObservation =
        read(identity, tuple, PersistencePhasePath.COMPLAINT_OWNER_EDIT_STATUS, context)

    @Suppress("TooGenericExceptionCaught")
    private fun read(identity: ComplaintOwnerOperationIdentity, tuple: ComplaintOwnerEditTuple?, path: PersistencePhasePath, context: ComplaintIngressContext?): ComplaintOwnerEditObservation {
        store.requireResources(ownership)
        val phase = when (path) {
            PersistencePhasePath.COMPLAINT_OWNER_EDIT_AUTHENTICATION -> ownership.enterComplaintOwnerEditAuthentication()
            PersistencePhasePath.COMPLAINT_OWNER_EDIT_PREFLIGHT -> ownership.enterComplaintOwnerEditPreflight()
            PersistencePhasePath.COMPLAINT_OWNER_EDIT_STATUS -> ownership.enterComplaintOwnerEditStatus()
            else -> throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
        }
        var operation: ComplaintOwnerEditOperation? = null
        try {
            store.bind(phase, context) // Original typed read ingress is bound before begin/checkout.
            phase.begin()
            operation = when (path) {
                PersistencePhasePath.COMPLAINT_OWNER_EDIT_AUTHENTICATION -> store.authenticate(identity)
                PersistencePhasePath.COMPLAINT_OWNER_EDIT_PREFLIGHT -> store.preflight(identity, checkNotNull(tuple))
                PersistencePhasePath.COMPLAINT_OWNER_EDIT_STATUS -> store.status(identity, checkNotNull(tuple))
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
    fun edit(
        identity: ComplaintOwnerOperationIdentity,
        candidate: ComplaintOwnerEditCandidate,
        platform: ComplaintPlatform,
        admission: ComplaintAdmittedOwnerEdit,
    ): ComplaintOwnerEditObservation {
        store.requireResources(ownership)
        val phase = ownership.enterComplaintOwnerEdit()
        var operation: ComplaintOwnerEditOperation? = null
        var refusal: ComplaintOwnerOperationFailure? = null
        try {
            phase.ownerEdit.bindEdit(admission)
            store.bind(phase) // Never a CREATE handoff; the retained registered EDIT binding compares this exact admission.
            phase.begin()
            operation = store.edit(identity, candidate, platform)
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
        val rolledBack = failure.cleanupProven && failure.databaseOutcome === PersistenceDatabaseOutcome.ROLLED_BACK
        if (refusal != null && rolledBack && failure.code === PersistencePhaseFailureCode.WORK_FAILED) rejectOwnerOperation(refusal)
        throw failure
    }

    override fun toString(): String = "ComplaintOwnerEditPhaseExecutor(dormant)"
}
