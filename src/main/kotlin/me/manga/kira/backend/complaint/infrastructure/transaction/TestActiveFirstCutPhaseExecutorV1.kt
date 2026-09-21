package me.manga.kira.backend.complaint.infrastructure.transaction

import me.manga.kira.backend.common.infrastructure.persistence.CatalogCoordinatorPersistence
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveFirstCutV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveFirstCutOperationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationHistoryV1
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator

/** Same retained runtime coordinator; no named-root bypass or row/provider result supplier. */
internal class TestActiveFirstCutPhaseExecutorV1(private val coordinator: CatalogCoordinatorPersistence) {
    private val jdbc = JdbcTemplate(coordinator.dataSource).apply {
        fetchSize = CatalogTestRunActivationHistoryV1.FETCH_ROWS
        exceptionTranslator = SQLExceptionSubclassTranslator()
    }

    internal fun execute(original: TestActiveFirstCutV1): TestActiveFirstCutOperationV1 {
        requireConnectionFree()
        original.requirePersistence(coordinator.ownership, jdbc)
        val phase = coordinator.ownership.enterTestActiveFirstCut(original)
        var operation: TestActiveFirstCutOperationV1? = null
        var failure: Throwable? = null
        try {
            phase.begin()
            original.authenticate(coordinator.ownership, jdbc)
            operation = TestActiveFirstCutOperationV1.execute(jdbc, original)
            original.requirePersistence(coordinator.ownership, jdbc)
            phase.commit()
        } catch (problem: Throwable) {
            original.observeFailure(problem)
            phase.recordFailure(problem)
        } finally {
            try { failure = runCatching(phase::finish).exceptionOrNull(); failure?.let(original::observeFailure) }
            finally { original.observePhaseCleanup(phase) }
        }
        original.throwIfSignalled()
        failure?.let { throw it }
        return (operation ?: throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)).also { it.requireReleased() }
    }
}
