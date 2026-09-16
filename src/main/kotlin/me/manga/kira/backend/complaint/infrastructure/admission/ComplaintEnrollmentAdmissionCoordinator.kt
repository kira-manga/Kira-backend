package me.manga.kira.backend.complaint.infrastructure.admission

import jakarta.servlet.http.HttpServletRequest
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.InstallationEnrollmentCandidate
import me.manga.kira.backend.complaint.domain.InstallationEnrollmentResult
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintInstallationEnrollmentPhaseExecutor
import me.manga.kira.backend.security.ComplaintAdmissionFailure
import me.manga.kira.backend.security.ComplaintIngressAdmission
import me.manga.kira.backend.security.ComplaintIngressContext
import me.manga.kira.backend.security.refuseComplaintAdmission

/** Opaque one-use quota handoff, not authentication, current-mode authority or permission to route a request. */
internal sealed interface ComplaintEnrollmentAdmission

/**
 * Dormant connection-free bootstrap/enrollment quotas and closed enrollment phase composition.
 * No caller-supplied new/replay flag, UUID actor bucket or replay exemption. The genuine locked
 * store alone selects NEW/replay/rejection. Future HTTP ownership must encompass parsing/body
 * limits and independently prove current mode/restore state; this coordinator cannot open them.
 */
internal class ComplaintEnrollmentAdmissionCoordinator(
    private val ingress: ComplaintIngressAdmission,
    private val phases: ComplaintInstallationEnrollmentPhaseExecutor,
) {
    private val owner = Any()

    fun <T> withIngress(request: HttpServletRequest, operation: (ComplaintIngressContext) -> T): T = ingress.withIngress(request, operation)

    /** Successful quota consumption returns no scope, response or current-state evidence. */
    fun admitBootstrap(context: ComplaintIngressContext) = ingress.chargeBootstrap(context)

    fun admitEnrollment(context: ComplaintIngressContext, candidate: InstallationEnrollmentCandidate): ComplaintEnrollmentAdmission {
        val admitted = Admitted(owner, context, candidate)
        ingress.chargeEnrollment(context, admitted.identity)
        return admitted
    }

    fun enroll(context: ComplaintIngressContext, admission: ComplaintEnrollmentAdmission): InstallationEnrollmentResult {
        val admitted = admission as? Admitted ?: refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
        if (admitted.owner !== owner || admitted.context !== context) refuseComplaintAdmission(ComplaintAdmissionFailure.INVALID_CONTEXT)
        val handoff = ingress.prepareEnrollment(context, admitted.identity, admitted.candidate)
        val result = phases.enrollAdmitted(admitted.candidate, handoff)
        requireConnectionFree()
        return result
    }

    override fun toString(): String = "ComplaintEnrollmentAdmissionCoordinator(dormant,redacted)"

    private class Admitted(val owner: Any, val context: ComplaintIngressContext, val candidate: InstallationEnrollmentCandidate) :
        ComplaintEnrollmentAdmission {
        val identity = Any()

        override fun toString(): String = "ComplaintEnrollmentAdmission(redacted)"
    }
}
