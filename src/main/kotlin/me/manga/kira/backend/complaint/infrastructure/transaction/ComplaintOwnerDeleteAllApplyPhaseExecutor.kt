package me.manga.kira.backend.complaint.infrastructure.transaction

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.complaint.infrastructure.CommittedOwnerDeleteAllApplyV1
import me.manga.kira.backend.complaint.infrastructure.CommittedOwnerDeleteAllVerificationV1
import me.manga.kira.backend.complaint.infrastructure.CommittedOwnerDeleteAllWork
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeleteAllApplyOperation
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteAllApplyStore
import me.manga.kira.backend.complaint.infrastructure.OwnerDeleteAllApplyOutcomeV1

/** Fenced privacy continuation only; no provider/admission retry or route activation in this phase. */
internal class ComplaintOwnerDeleteAllApplyPhaseExecutor(
    private val ownership: PersistencePhaseOwnership,
    private val store: JdbcComplaintOwnerDeleteAllApplyStore,
) {
    fun apply(work: CommittedOwnerDeleteAllWork, proof: CommittedOwnerDeleteAllVerificationV1): CommittedOwnerDeleteAllApplyV1 =
        execute(work, proof).result

    /** Fixed proof-backed continuation, not an exception-to-success adapter or an in-process retry. */
    fun applyForContinuation(work: CommittedOwnerDeleteAllWork, proof: CommittedOwnerDeleteAllVerificationV1): OwnerDeleteAllApplyOutcomeV1 =
        execute(work, proof).continuationResult

    @Suppress("TooGenericExceptionCaught")
    private fun execute(work: CommittedOwnerDeleteAllWork, proof: CommittedOwnerDeleteAllVerificationV1): ComplaintOwnerDeleteAllApplyOperation {
        val captured = store.capture(work, proof)
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
        // A failed capture/entry/SQL operation never reaches either result-release surface.
        return operation ?: throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
    }

    override fun toString(): String = "ComplaintOwnerDeleteAllApplyPhaseExecutor(dormant,redacted)"
}
