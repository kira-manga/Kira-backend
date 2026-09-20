package me.manga.kira.backend.complaint.infrastructure.transaction

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.complaint.domain.ComplaintTestReserveSpend
import me.manga.kira.backend.complaint.domain.ComplaintTestReserveSpendExpectation
import me.manga.kira.backend.complaint.domain.ComplaintTestReserveSpendResult

/** Fixed dormant ACTIVE-run entry. Comparison data is not authenticated allocation or terminal authority. */
internal class ComplaintTestReserveSpendPhaseExecutor(private val ownership: PersistencePhaseOwnership, private val spending: ComplaintTestReserveSpend) {
    @Suppress("TooGenericExceptionCaught")
    fun spend(expectation: ComplaintTestReserveSpendExpectation): ComplaintTestReserveSpendResult {
        val phase = ownership.enterComplaintTestReserveSpend()
        var result: ComplaintTestReserveSpendResult? = null
        try {
            phase.begin()
            result = spending.spend(expectation)
            phase.checkComplaintTestReserveSpendWork(result)
            phase.commit()
        } catch (problem: Throwable) {
            phase.recordFailure(problem)
        } finally {
            phase.finish()
        }
        return phase.complaintTestReserveSpendResult(result)
    }

    override fun toString(): String = "ComplaintTestReserveSpendPhaseExecutor(COMPLAINT_TEST_RESERVE_SPEND)"
}
