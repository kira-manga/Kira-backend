package me.manga.kira.backend.complaint.infrastructure.transaction

import me.manga.kira.backend.common.infrastructure.persistence.CatalogCoordinatorPersistence
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalPreflightOperationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalV1
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator

/** Separate fixed P/L-before-counters preflight. No catalog lock or provider may enter its holder. */
internal class ComplaintTestRunTerminalCatalogPreflightPhaseExecutorV1(private val coordinator: CatalogCoordinatorPersistence) {
    private val jdbc = JdbcTemplate(coordinator.dataSource).apply { exceptionTranslator = SQLExceptionSubclassTranslator() }
    internal fun execute(original: CatalogTestRunTerminalV1): CatalogTestRunTerminalPreflightOperationV1 {
        requireConnectionFree(); original.requirePreflightPersistence(coordinator.ownership, jdbc)
        val phase = coordinator.ownership.enterTestRunTerminalCatalogPreflight(original)
        var operation: CatalogTestRunTerminalPreflightOperationV1? = null
        var cleanup: Throwable? = null
        try {
            phase.begin(); original.authenticate(coordinator.ownership, jdbc)
            operation = CatalogTestRunTerminalPreflightOperationV1.execute(jdbc, original)
            original.requirePreflightPersistence(coordinator.ownership, jdbc); phase.commit()
        } catch (problem: Throwable) {
            original.observeFailure(problem); phase.recordFailure(problem)
        } finally {
            try { cleanup = runCatching(phase::finish).exceptionOrNull(); cleanup?.let(original::observeFailure) }
            finally { original.observePhaseCleanup(phase) }
        }
        try {
            original.throwIfSignalled(); cleanup?.let { throw it }
            return (operation ?: throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)).also { it.requireReleased() }
        } catch (problem: Throwable) { operation?.discardRow(); throw problem }
    }
}
