package me.manga.kira.backend.complaint.infrastructure.transaction

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.security.SourceGrantCleanup
import java.time.Clock

/** Dormant named entry: no bean, caller-selected cutoff/SQL/flags, generic callback, controller or scheduler. */
internal class OrdinaryPersistencePhaseExecutor(
    private val ownership: PersistencePhaseOwnership,
    private val sourceGrantCleanup: SourceGrantCleanup,
    private val clock: Clock,
) {
    // Every work/completion failure must reach the same phase finalizer; only its bounded outcome/refund result escapes.
    @Suppress("TooGenericExceptionCaught")
    fun cleanupSourceGrants(): Int {
        val phase = ownership.enterSourceGrantCleanup()
        var count = 0
        try {
            phase.begin()
            val cutoff = clock.instant() // Trusted injected clock, sampled exactly once for this one batch.
            count = sourceGrantCleanup.deleteEligibleSourceGrants(cutoff)
            phase.checkWorkReturned(count)
            phase.commit()
        } catch (failure: Throwable) {
            phase.recordFailure(failure)
        } finally {
            phase.finish()
        }
        return phase.result(count) // Known commit and cleanup failure remain distinct, never silently called rollback.
    }

    override fun toString(): String = "OrdinaryPersistencePhaseExecutor(SOURCE_GRANT_CLEANUP)"
}
