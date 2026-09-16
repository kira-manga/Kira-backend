package me.manga.kira.backend.complaint.infrastructure.transaction

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintInstallationCurrentStateAssessment
import me.manga.kira.backend.complaint.domain.ComplaintInstallationDesiredSettings
import me.manga.kira.backend.complaint.infrastructure.admission.InstallationCurrentStateReadOperation
import me.manga.kira.backend.complaint.infrastructure.admission.JdbcInstallationCurrentStateReader

/** Dormant diagnostic read only. Matching is PROVENANCE_REQUIRED, never a scope response, session or admission grant. */
internal class ComplaintInstallationCurrentStatePhaseExecutor(
    private val ownership: PersistencePhaseOwnership,
    private val reader: JdbcInstallationCurrentStateReader,
) {
    @Suppress("TooGenericExceptionCaught")
    fun assess(desired: ComplaintInstallationDesiredSettings, requestedScope: ComplaintDataScope): ComplaintInstallationCurrentStateAssessment {
        requireConnectionFree()
        if (desired !is ComplaintInstallationDesiredSettings.Configured) return ComplaintInstallationCurrentStateAssessment.DISABLED
        val phase = ownership.enterComplaintInstallationCurrentState()
        var captured: InstallationCurrentStateReadOperation? = null
        try {
            phase.begin()
            captured = reader.read(desired, requestedScope)
            phase.commit()
        } catch (problem: Throwable) {
            phase.recordFailure(problem)
        } finally {
            phase.finish()
        }
        val operation = captured ?: throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
        return operation.assessment // Exact retained read + known commit + actual release, never equality of a supplied enum.
    }

    override fun toString(): String = "ComplaintInstallationCurrentStatePhaseExecutor(read-only,no-authority)"
}
