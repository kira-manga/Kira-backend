package me.manga.kira.backend.complaint.infrastructure.transaction

import me.manga.kira.backend.common.infrastructure.persistence.CatalogCoordinatorPersistence
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogProjectedHeadInputV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogProjectedHeadReadOperationV1
import org.springframework.jdbc.core.JdbcTemplate

/** Original coordinator/permit/manager/source only. Every provider is closed before this history-only phase begins. */
internal class ComplaintCatalogProjectedHeadPhaseExecutor(private val coordinator: CatalogCoordinatorPersistence, private val jdbc: JdbcTemplate) {
    private val ownership = coordinator.ownership
    private val manager = coordinator.manager
    private val source = coordinator.dataSource

    @Suppress("TooGenericExceptionCaught")
    internal fun revalidate(input: CatalogProjectedHeadInputV1): CatalogProjectedHeadReadOperationV1 {
        requireConnectionFree()
        coordinator.requireResources()
        if (!hasOriginalCoordinatorResources() || jdbc.dataSource !== source) {
            throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
        input.requirePersistence(ownership, jdbc)
        val phase = ownership.enterComplaintCatalogProjectedHead(input.attempt)
        var completed: CatalogProjectedHeadReadOperationV1? = null
        try {
            phase.begin()
            completed = CatalogProjectedHeadReadOperationV1.read(input, jdbc)
            input.requirePersistence(ownership, jdbc)
            phase.commit()
        } catch (problem: Throwable) {
            phase.recordFailure(problem)
        } finally {
            phase.finish()
        }
        return completed ?: throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
    }

    private fun hasOriginalCoordinatorResources(): Boolean =
        coordinator.ownership === ownership && coordinator.manager === manager && coordinator.dataSource === source

    override fun toString(): String = "ComplaintCatalogProjectedHeadPhaseExecutor(exact-history-read,no-projection-effect)"
}
