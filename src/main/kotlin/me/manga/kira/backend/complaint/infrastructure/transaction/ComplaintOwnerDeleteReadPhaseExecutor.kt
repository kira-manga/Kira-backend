package me.manga.kira.backend.complaint.infrastructure.transaction

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteTuple
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeleteObservation
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeleteReadOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerOperationIdentity
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteReceiptStore

/** Three named ordinary read phases. No generic callback, secondary manager or retained JDBC result. */
internal class ComplaintOwnerDeleteReadPhaseExecutor(private val ownership: PersistencePhaseOwnership, private val store: JdbcComplaintOwnerDeleteReceiptStore) {
    fun authenticate(identity: ComplaintOwnerOperationIdentity): ComplaintOwnerDeleteObservation = read(identity, null, PersistencePhasePath.COMPLAINT_OWNER_DELETE_AUTHENTICATION)
    fun preflight(identity: ComplaintOwnerOperationIdentity, tuple: ComplaintOwnerDeleteTuple): ComplaintOwnerDeleteObservation = read(identity, tuple, PersistencePhasePath.COMPLAINT_OWNER_DELETE_PREFLIGHT)
    fun status(identity: ComplaintOwnerOperationIdentity, tuple: ComplaintOwnerDeleteTuple): ComplaintOwnerDeleteObservation = read(identity, tuple, PersistencePhasePath.COMPLAINT_OWNER_DELETE_STATUS)
    fun requirePreflight(observation: ComplaintOwnerDeleteObservation, identity: ComplaintOwnerOperationIdentity, tuple: ComplaintOwnerDeleteTuple) = store.requirePreflight(observation, identity, tuple)
    @Suppress("TooGenericExceptionCaught")
    private fun read(identity: ComplaintOwnerOperationIdentity, tuple: ComplaintOwnerDeleteTuple?, path: PersistencePhasePath): ComplaintOwnerDeleteObservation {
        val phase = when (path) {
            PersistencePhasePath.COMPLAINT_OWNER_DELETE_AUTHENTICATION -> ownership.enterComplaintOwnerDeleteAuthentication()
            PersistencePhasePath.COMPLAINT_OWNER_DELETE_PREFLIGHT -> ownership.enterComplaintOwnerDeletePreflight()
            PersistencePhasePath.COMPLAINT_OWNER_DELETE_STATUS -> ownership.enterComplaintOwnerDeleteStatus()
            else -> throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
        }
        var operation: ComplaintOwnerDeleteReadOperation? = null
        try {
            phase.begin()
            operation = when (path) {
                PersistencePhasePath.COMPLAINT_OWNER_DELETE_AUTHENTICATION -> store.authenticate(identity)
                PersistencePhasePath.COMPLAINT_OWNER_DELETE_PREFLIGHT -> store.preflight(identity, checkNotNull(tuple))
                PersistencePhasePath.COMPLAINT_OWNER_DELETE_STATUS -> store.status(identity, checkNotNull(tuple))
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
}
