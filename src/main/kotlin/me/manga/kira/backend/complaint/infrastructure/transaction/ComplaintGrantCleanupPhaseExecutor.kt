package me.manga.kira.backend.complaint.infrastructure.transaction

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.security.ComplaintGrantCleanup
import java.time.Clock

/** Dormant fixed entry, not a bean, scheduler, caller-selected cutoff or replacement for live step-up cleanup. */
internal class ComplaintGrantCleanupPhaseExecutor(
    private val ownership: PersistencePhaseOwnership,
    private val complaintGrantCleanup: ComplaintGrantCleanup,
    private val clock: Clock,
) {
    // Every failure returns through the existing retained finalizer, including a known commit with failed local cleanup.
    @Suppress("TooGenericExceptionCaught")
    fun cleanupComplaintGrants(): Int {
        val phase = ownership.enterComplaintGrantCleanup()
        var count = 0
        try {
            phase.begin()
            val cutoff = clock.instant()
            count = complaintGrantCleanup.deleteEligibleComplaintGrantsAndRefund(cutoff)
            phase.checkWorkReturned(count)
            phase.commit()
        } catch (failure: Throwable) {
            phase.recordFailure(failure)
        } finally {
            phase.finish()
        }
        return phase.result(count)
    }

    override fun toString(): String = "ComplaintGrantCleanupPhaseExecutor(COMPLAINT_GRANT_CLEANUP)"
}
