package me.manga.kira.backend.complaint.infrastructure.transaction

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.complaint.infrastructure.CommittedOwnerDeleteAllApplyV1
import me.manga.kira.backend.complaint.infrastructure.CommittedOwnerDeleteAllVerificationV1
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeleteAllApplyOperation
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteAllApplyStore

/** Fenced privacy continuation only; no provider/admission retry or route activation in this phase. */
internal class ComplaintOwnerDeleteAllApplyPhaseExecutor(
    private val ownership: PersistencePhaseOwnership,
    private val store: JdbcComplaintOwnerDeleteAllApplyStore,
) {
    @Suppress("TooGenericExceptionCaught")
    fun apply(proof: CommittedOwnerDeleteAllVerificationV1): CommittedOwnerDeleteAllApplyV1 {
        val captured = store.capture(proof)
        val phase = ownership.enterComplaintOwnerDeleteAllApply()
        var operation: ComplaintOwnerDeleteAllApplyOperation? = null
        try {
            phase.begin()
            operation = store.apply(captured)
            phase.commit()
        } catch (problem: Throwable) {
            phase.recordFailure(problem)
        } finally {
            phase.finish()
        }
        // UNKNOWN commit or unresolved cleanup must never be treated as an erasure success.
        return (operation ?: throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)).result
    }

    override fun toString(): String = "ComplaintOwnerDeleteAllApplyPhaseExecutor(dormant,redacted)"
}
