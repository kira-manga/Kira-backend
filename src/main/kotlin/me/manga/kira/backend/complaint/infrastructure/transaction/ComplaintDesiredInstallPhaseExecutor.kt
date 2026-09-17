package me.manga.kira.backend.complaint.infrastructure.transaction

import me.manga.kira.backend.common.infrastructure.persistence.CatalogCoordinatorPersistence
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintDesiredInstallAttemptV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintDesiredInstallOperationV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintDesiredInstallationResultV1
import org.springframework.jdbc.core.JdbcTemplate

/** Only the explicit authenticated installer owner can retain an attempt. No callback, supplied D, transaction flag or SQL entry. */
internal class ComplaintDesiredInstallPhaseExecutor(private val coordinator: CatalogCoordinatorPersistence, private val jdbc: JdbcTemplate) {
    private val ownership = coordinator.ownership
    private val manager = coordinator.manager
    private val source = coordinator.dataSource

    internal fun install(attempt: ComplaintDesiredInstallAttemptV1): ComplaintDesiredInstallationResultV1 {
        requireConnectionFree()
        coordinator.requireResources()
        if (!coordinator.desiredInstallationOperator || coordinator.ownership !== ownership || coordinator.manager !== manager) {
            throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
        if (coordinator.dataSource !== source || jdbc.dataSource !== source) {
            throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
        attempt.requirePersistence(ownership, jdbc)
        val first = persist(attempt)
        first.releasedResult()?.let { return it }
        attempt.continueAfterClose(first)
        return checkNotNull(persist(attempt).releasedResult())
    }

    @Suppress("TooGenericExceptionCaught") // The original phase retains all status/lease/commit/unknown/release facts before sanitization.
    private fun persist(attempt: ComplaintDesiredInstallAttemptV1): ComplaintDesiredInstallOperationV1 {
        requireConnectionFree()
        attempt.requirePersistence(ownership, jdbc)
        val phase = when (attempt.path) {
            PersistencePhasePath.COMPLAINT_DESIRED_BOOTSTRAP -> ownership.enterComplaintDesiredBootstrap(attempt)
            PersistencePhasePath.COMPLAINT_DESIRED_CLOSE -> ownership.enterComplaintDesiredClose(attempt)
            PersistencePhasePath.COMPLAINT_DESIRED_SUPERSEDE -> ownership.enterComplaintDesiredSupersede(attempt)
            else -> throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
        var operation: ComplaintDesiredInstallOperationV1? = null
        try {
            phase.begin() // Deliberately control-only: no advisory fence acquisition or later control->fence transition.
            operation = when (attempt.path) {
                PersistencePhasePath.COMPLAINT_DESIRED_BOOTSTRAP -> ComplaintDesiredInstallOperationV1.bootstrap(jdbc, attempt)
                PersistencePhasePath.COMPLAINT_DESIRED_CLOSE -> ComplaintDesiredInstallOperationV1.closeGates(jdbc, attempt)
                PersistencePhasePath.COMPLAINT_DESIRED_SUPERSEDE -> ComplaintDesiredInstallOperationV1.supersede(jdbc, attempt)
                else -> throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            attempt.requirePersistence(ownership, jdbc)
            phase.commit()
        } catch (problem: Throwable) {
            attempt.abort()
            phase.recordFailure(problem)
        } finally {
            phase.finish()
        }
        return operation ?: throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
    }

    override fun toString(): String = "ComplaintDesiredInstallPhaseExecutor(fixed-operator-only,no-runtime-authority)"
}
