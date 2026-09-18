package me.manga.kira.backend.complaint.infrastructure.transaction

import me.manga.kira.backend.common.infrastructure.persistence.CatalogCoordinatorPersistence
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintSignedGenesisFirstDAttemptV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintSignedGenesisFirstDOperationV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintSignedGenesisFirstDResultV1
import org.springframework.jdbc.core.JdbcTemplate

/** One explicit first-D command phase; never widens the old control-only installer or constructs a history writer. */
internal class ComplaintSignedGenesisFirstDPhaseExecutor(private val coordinator: CatalogCoordinatorPersistence, private val jdbc: JdbcTemplate) {
    private val ownership = coordinator.ownership
    private val manager = coordinator.manager
    private val source = coordinator.dataSource

    @Suppress("TooGenericExceptionCaught")
    internal fun select(attempt: ComplaintSignedGenesisFirstDAttemptV1): ComplaintSignedGenesisFirstDResultV1 {
        requireConnectionFree()
        coordinator.requireResources()
        if (!coordinator.desiredInstallationOperator || coordinator.ownership !== ownership || coordinator.manager !== manager) {
            throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
        if (coordinator.dataSource !== source || jdbc.dataSource !== source) {
            throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
        attempt.requirePersistence(ownership, jdbc)
        val phase = ownership.enterComplaintDesiredSignedGenesisFirst(attempt)
        var operation: ComplaintSignedGenesisFirstDOperationV1? = null
        try {
            phase.begin() // Actual READ COMMITTED holder and genuine shared epoch fence, before LIVE/catalog/history.
            operation = ComplaintSignedGenesisFirstDOperationV1.select(jdbc, attempt)
            attempt.requirePersistence(ownership, jdbc)
            phase.commit()
        } catch (problem: Throwable) {
            attempt.abort()
            phase.recordFailure(problem)
        } finally {
            phase.finish()
        }
        return (operation ?: throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)).releasedResult()
    }

    override fun toString(): String = "ComplaintSignedGenesisFirstDPhaseExecutor(fixed-operator,D-only,no-runtime-authority)"
}
