package me.manga.kira.backend.complaint.infrastructure.transaction

import me.manga.kira.backend.common.infrastructure.persistence.CatalogCoordinatorPersistence
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationHistoryV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalOperationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalPhaseV1
import me.manga.kira.backend.complaint.infrastructure.catalog.JdbcCatalogTestRunTerminalStoreV1
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator

/** Normal coordinator root only. Every result awaits the exact known-committed original release. */
internal class ComplaintCatalogTestRunTerminalPhaseExecutorV1(private val coordinator: CatalogCoordinatorPersistence) {
    private val jdbc = JdbcTemplate(coordinator.dataSource).apply {
        exceptionTranslator = SQLExceptionSubclassTranslator()
        fetchSize = CatalogTestRunActivationHistoryV1.FETCH_ROWS
    }
    internal fun execute(input: CatalogTestRunTerminalPhaseV1): CatalogTestRunTerminalOperationV1 {
        requireConnectionFree(); input.requirePersistence(coordinator.ownership, jdbc)
        val phase = coordinator.ownership.enterCatalogTestRunTerminal(input.original, input.kind)
        var operation: CatalogTestRunTerminalOperationV1? = null
        var cleanup: Throwable? = null
        try {
            phase.begin(); input.original.authenticate(coordinator.ownership, jdbc)
            operation = JdbcCatalogTestRunTerminalStoreV1(jdbc).execute(input,
                JdbcComplaintCapacityStore(jdbc, input.process.consumers.capacityPolicy.digestBytes()))
            input.requirePersistence(coordinator.ownership, jdbc); phase.commit()
        } catch (problem: Throwable) {
            input.original.observeFailure(problem); phase.recordFailure(problem)
        } finally {
            try { cleanup = runCatching(phase::finish).exceptionOrNull(); cleanup?.let(input.original::observeFailure) }
            finally { input.original.observePhaseCleanup(phase) }
        }
        input.original.throwIfSignalled(); cleanup?.let { throw it }
        return (operation ?: throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)).also { it.requireReleased() }
    }
}
