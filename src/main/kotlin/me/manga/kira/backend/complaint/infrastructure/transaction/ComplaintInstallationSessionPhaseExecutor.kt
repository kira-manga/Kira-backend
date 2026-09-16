package me.manga.kira.backend.complaint.infrastructure.transaction

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.complaint.domain.ComplaintInstallationSession
import me.manga.kira.backend.complaint.domain.InstallationSessionCandidate
import me.manga.kira.backend.complaint.domain.InstallationSessionPreflight
import me.manga.kira.backend.complaint.domain.SessionPreflightResult
import me.manga.kira.backend.complaint.domain.SessionRefreshResult
import me.manga.kira.backend.complaint.infrastructure.ComplaintInstallationSessionOperation
import me.manga.kira.backend.security.ComplaintAdmittedSessionRefresh

/**
 * Separate fixed phases on the existing owner. The admission coordinator uses refreshAdmitted;
 * raw refresh remains a dormant lower-core seam, never its request-orchestration fallback.
 */
internal class ComplaintInstallationSessionPhaseExecutor(private val ownership: PersistencePhaseOwnership, private val store: ComplaintInstallationSession) {
    @Suppress("TooGenericExceptionCaught")
    fun preflight(candidate: InstallationSessionCandidate): SessionPreflightResult {
        val phase = ownership.enterComplaintInstallationSessionPreflight()
        var result: SessionPreflightResult? = null
        try {
            phase.begin()
            result = store.preflight(candidate)
            phase.installationSession.checkWork(result)
            phase.commit()
        } catch (problem: Throwable) {
            phase.recordFailure(problem)
        } finally {
            phase.finish()
        }
        return ComplaintInstallationSessionOperation.releasePreflight(phase, result)
    }

    fun refresh(preflight: InstallationSessionPreflight): SessionRefreshResult = refreshPhase(preflight, null)

    fun refreshAdmitted(preflight: InstallationSessionPreflight, handoff: ComplaintAdmittedSessionRefresh): SessionRefreshResult =
        refreshPhase(preflight, handoff)

    @Suppress("TooGenericExceptionCaught")
    private fun refreshPhase(preflight: InstallationSessionPreflight, handoff: ComplaintAdmittedSessionRefresh?): SessionRefreshResult {
        val phase = ownership.enterComplaintInstallationSessionRefresh()
        var result: SessionRefreshResult? = null
        try {
            // Bind the private phase, not an optional adapter parameter a wrapper could silently drop.
            if (handoff != null) phase.installationSession.bindAdmittedRefresh(handoff)
            phase.begin()
            result = store.refresh(preflight)
            phase.installationSession.checkWork(result)
            phase.commit()
        } catch (problem: Throwable) {
            phase.recordFailure(problem)
        } finally {
            phase.finish()
        }
        return phase.installationSession.refreshResult(result)
    }

    override fun toString(): String = "ComplaintInstallationSessionPhaseExecutor(redacted)"
}
