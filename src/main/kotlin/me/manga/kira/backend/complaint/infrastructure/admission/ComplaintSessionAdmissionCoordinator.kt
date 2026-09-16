package me.manga.kira.backend.complaint.infrastructure.admission

import jakarta.servlet.http.HttpServletRequest
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.InstallationSessionCandidate
import me.manga.kira.backend.complaint.domain.InstallationSessionPreflight
import me.manga.kira.backend.complaint.domain.InstallationSessionRejection
import me.manga.kira.backend.complaint.domain.SessionPreflightResult
import me.manga.kira.backend.complaint.domain.SessionRefreshResult
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintInstallationSessionPhaseExecutor
import me.manga.kira.backend.security.ComplaintAdmissionFailure
import me.manga.kira.backend.security.ComplaintIngressAdmission
import me.manga.kira.backend.security.ComplaintIngressContext
import me.manga.kira.backend.security.refuseComplaintAdmission

internal sealed interface ComplaintSessionAdmissionResult {
    class Rejected(val reason: InstallationSessionRejection) : ComplaintSessionAdmissionResult {
        override fun toString(): String = "ComplaintSessionAdmissionResult.Rejected($reason)"
    }

    sealed interface Admitted : ComplaintSessionAdmissionResult
}

/**
 * Dormant genuine-preflight -> release -> local semantic admission. No caller-prepared Ready,
 * authentication flag, raw bucket key, bean, route or current-mode authority. Admitted refresh
 * stays on the exact bound store. TEST comparison is not activation provenance; future HTTP still needs mode/restore guards.
 */
internal class ComplaintSessionAdmissionCoordinator(
    private val ingress: ComplaintIngressAdmission,
    private val phases: ComplaintInstallationSessionPhaseExecutor,
) {
    private val owner = Any()

    fun <T> withIngress(request: HttpServletRequest, operation: (ComplaintIngressContext) -> T): T = ingress.withIngress(request, operation)

    fun admitSession(context: ComplaintIngressContext, candidate: InstallationSessionCandidate): ComplaintSessionAdmissionResult {
        ingress.startSession(context)
        val preflight = phases.preflight(candidate)
        requireConnectionFree()
        return when (preflight) {
            is SessionPreflightResult.Rejected -> ComplaintSessionAdmissionResult.Rejected(preflight.reason)

            is SessionPreflightResult.Ready -> {
                val admitted = Admitted(owner, context, preflight.continuation)
                ingress.chargeSession(context, preflight.continuation.installation, admitted.identity)
                admitted
            }
        }
    }

    /**
     * Owns the one-use handoff through actual refresh and release. There is no raw-continuation
     * extraction/fallback: the phase enforces the original five seconds at entry and before writing.
     */
    fun refresh(context: ComplaintIngressContext, result: ComplaintSessionAdmissionResult.Admitted): SessionRefreshResult {
        val admitted = result as? Admitted ?: refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
        if (admitted.owner !== owner || admitted.context !== context) refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
        val handoff = ingress.prepareSessionRefresh(context, admitted.identity, admitted.preflight)
        val refreshed = phases.refreshAdmitted(admitted.preflight, handoff)
        requireConnectionFree()
        return refreshed
    }

    override fun toString(): String = "ComplaintSessionAdmissionCoordinator(dormant,redacted)"

    private class Admitted(val owner: Any, val context: ComplaintIngressContext, val preflight: InstallationSessionPreflight) :
        ComplaintSessionAdmissionResult.Admitted {
        val identity = Any()

        override fun toString(): String = "ComplaintSessionAdmissionResult.Admitted(redacted)"
    }
}
