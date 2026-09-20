package me.manga.kira.backend.complaint.infrastructure.transaction

import me.manga.kira.backend.common.infrastructure.persistence.CatalogCoordinatorPersistence
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintCatalogGenesisPublishRecheckOperationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisPublishAttemptV1
import me.manga.kira.backend.complaint.infrastructure.catalog.catalogPublicationSignal
import me.manga.kira.backend.complaint.infrastructure.catalog.preferCatalogFreezeCleanup
import org.springframework.jdbc.core.JdbcTemplate

/** The fixed config-operator, never TARGET/AUTHOR checkout or a historical first-D result as publication admission. */
internal class ComplaintCatalogGenesisPublishRecheckPhaseExecutor(private val coordinator: CatalogCoordinatorPersistence, private val jdbc: JdbcTemplate) {
    private val ownership = coordinator.ownership
    private val manager = coordinator.manager
    private val source = coordinator.dataSource

    @Suppress("TooGenericExceptionCaught")
    internal fun recheck(attempt: CatalogGenesisPublishAttemptV1) {
        requireConnectionFree()
        coordinator.requireResources()
        if (!coordinator.desiredInstallationOperator || coordinator.ownership !== ownership || coordinator.manager !== manager) {
            throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
        if (coordinator.dataSource !== source || jdbc.dataSource !== source) {
            throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
        attempt.requirePersistence(ownership, jdbc)
        val phase = ownership.enterComplaintCatalogGenesisPublishRecheck(attempt)
        var operation: ComplaintCatalogGenesisPublishRecheckOperationV1? = null
        var failure: Throwable? = null
        try {
            phase.begin() // FOR UPDATE needs a writable transaction, but this named operation contains SELECT statements only.
            operation = ComplaintCatalogGenesisPublishRecheckOperationV1.recheck(jdbc, attempt)
            attempt.requirePersistence(ownership, jdbc)
            phase.commit()
        } catch (problem: Throwable) {
            attempt.abort()
            failure = catalogPublicationSignal(problem)
            phase.recordFailure(checkNotNull(failure))
        } finally {
            try {
                phase.finish()
            } catch (cleanup: Throwable) {
                failure = preferCatalogFreezeCleanup(failure, cleanup)
            }
        }
        failure?.let {
            val finalPhase = phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
            throw if (finalPhase.cleanupProven) it else preferCatalogFreezeCleanup(it, finalPhase)
        }
        (operation ?: throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)).requireReleased()
    }

    override fun toString(): String = "ComplaintCatalogGenesisPublishRecheckPhaseExecutor(fixed-operator,no-writes-or-runtime-authority)"
}
