package me.manga.kira.backend.complaint.infrastructure.transaction

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.complaint.domain.ComplaintRecoverySettlement
import me.manga.kira.backend.complaint.domain.ComplaintRecoverySettlementExpectation
import me.manga.kira.backend.complaint.domain.ComplaintRecoverySettlementResult

/** Fixed dormant entry only. The expectation is comparison data, not authenticated recovery/unused-work authority. */
internal class ComplaintRecoverySettlementPhaseExecutor(
    private val ownership: PersistencePhaseOwnership,
    private val settlement: ComplaintRecoverySettlement,
) {
    @Suppress("TooGenericExceptionCaught")
    fun settle(expectation: ComplaintRecoverySettlementExpectation): ComplaintRecoverySettlementResult {
        val phase = ownership.enterComplaintRecoverySettlement()
        var result: ComplaintRecoverySettlementResult? = null
        try {
            phase.begin()
            result = settlement.settle(expectation)
            phase.checkComplaintRecoverySettlementWork(result)
            phase.commit()
        } catch (problem: Throwable) {
            phase.recordFailure(problem)
        } finally {
            phase.finish()
        }
        return phase.complaintRecoverySettlementResult(result)
    }

    override fun toString(): String = "ComplaintRecoverySettlementPhaseExecutor(COMPLAINT_RECOVERY_SETTLEMENT)"
}
