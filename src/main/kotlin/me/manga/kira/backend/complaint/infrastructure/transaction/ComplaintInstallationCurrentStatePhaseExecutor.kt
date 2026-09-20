package me.manga.kira.backend.complaint.infrastructure.transaction

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintInstallationBootstrap
import me.manga.kira.backend.complaint.domain.ComplaintInstallationCurrentStateAssessment
import me.manga.kira.backend.complaint.domain.ComplaintInstallationDesiredSettings
import me.manga.kira.backend.complaint.infrastructure.admission.InstallationCurrentStateReadOperation
import me.manga.kira.backend.complaint.infrastructure.admission.JdbcInstallationCurrentStateReader
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationV1

/** Diagnostic equality stays untrusted. The separate bootstrap path requires the actual retained TEST registration. */
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

    @Suppress("TooGenericExceptionCaught")
    fun bootstrap(registration: ComplaintTestNamespaceRegistrationV1): ComplaintInstallationBootstrap {
        requireConnectionFree()
        reader.requireBootstrapResources(ownership, registration)
        val phase = ownership.enterComplaintInstallationCurrentState()
        var captured: InstallationCurrentStateReadOperation? = null
        try {
            phase.begin()
            captured = reader.readBootstrap(ownership, registration)
            phase.commit()
        } catch (problem: Throwable) {
            phase.recordFailure(problem)
        } finally {
            phase.finish()
        }
        val operation = captured ?: throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
        return operation.bootstrap(registration)
    }

    override fun toString(): String = "ComplaintInstallationCurrentStatePhaseExecutor(read-only,registered-TEST-bootstrap)"
}
