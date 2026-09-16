package me.manga.kira.backend.complaint.infrastructure.transaction

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintInstallationEnrollment
import me.manga.kira.backend.complaint.domain.InstallationEnrollmentCandidate
import me.manga.kira.backend.complaint.domain.InstallationEnrollmentResult
import me.manga.kira.backend.security.ComplaintAdmittedEnrollmentWrite
import me.manga.kira.backend.security.ComplaintIngressAdmission

/**
 * Dormant paired enrollment; TEST needs the concrete store's independent constructor binding.
 * Both normal outcomes require exact owned completion and release; neither is routing or JWT authority.
 */
internal class ComplaintInstallationEnrollmentPhaseExecutor(
    private val ownership: PersistencePhaseOwnership,
    private val enrollment: ComplaintInstallationEnrollment,
) {
    /** Explicit lower-core seam only; an active ingress context must use the closed admitted path. */
    fun enroll(candidate: InstallationEnrollmentCandidate): InstallationEnrollmentResult {
        ComplaintIngressAdmission.requireRawEnrollmentContext()
        return enrollPhase(candidate, null)
    }

    fun enrollAdmitted(candidate: InstallationEnrollmentCandidate, handoff: ComplaintAdmittedEnrollmentWrite): InstallationEnrollmentResult =
        enrollPhase(candidate, handoff)

    @Suppress("TooGenericExceptionCaught")
    private fun enrollPhase(candidate: InstallationEnrollmentCandidate, handoff: ComplaintAdmittedEnrollmentWrite?): InstallationEnrollmentResult {
        val phase = ownership.enterComplaintInstallationEnrollment()
        var result: InstallationEnrollmentResult? = null
        try {
            if (handoff != null) phase.installationEnrollment.bindAdmitted(handoff)
            phase.begin()
            result = enrollment.enroll(candidate)
            phase.installationEnrollment.checkWork(result)
            phase.commit()
        } catch (problem: Throwable) {
            phase.recordFailure(problem)
        } finally {
            phase.finish()
        }
        val completed = phase.installationEnrollment.result(result)
        requireConnectionFree()
        return completed
    }

    override fun toString(): String = "ComplaintInstallationEnrollmentPhaseExecutor(COMPLAINT_INSTALLATION_ENROLLMENT)"
}
