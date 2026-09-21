package me.manga.kira.backend.complaint.infrastructure.transaction

import me.manga.kira.backend.common.infrastructure.persistence.CatalogCoordinatorPersistence
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveOrdinarySealOperationV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveOrdinarySealV1
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator

/** Fixed normal-root phases. An operation can leave this executor only after its original committed release. */
internal class ComplaintTestActiveOrdinarySealPhaseExecutorV1(private val coordinator: CatalogCoordinatorPersistence) {
    private val jdbc = JdbcTemplate(coordinator.dataSource).apply { exceptionTranslator = SQLExceptionSubclassTranslator() }

    internal fun execute(original: TestActiveOrdinarySealV1): TestActiveOrdinarySealOperationV1 {
        requireConnectionFree()
        original.requirePersistence(coordinator.ownership, jdbc)
        val phase = coordinator.ownership.enterTestActiveOrdinarySeal(original)
        var operation: TestActiveOrdinarySealOperationV1? = null
        var failure: Throwable? = null
        try {
            phase.begin()
            original.authenticate(coordinator.ownership, jdbc)
            operation = TestActiveOrdinarySealOperationV1.execute(jdbc, original)
            original.requirePersistence(coordinator.ownership, jdbc)
            phase.commit()
        } catch (problem: Throwable) {
            original.observeFailure(problem)
            phase.recordFailure(problem)
        } finally {
            try { failure = runCatching(phase::finish).exceptionOrNull(); failure?.let(original::observeFailure) }
            finally { original.observePhaseCleanup(phase) }
        }
        return try {
            original.throwIfSignalled()
            failure?.let { throw it }
            val actual = operation ?: throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
            actual.requireReleased()
            actual
        } catch (problem: Throwable) {
            // A body may have finished before COMMIT/cleanup became UNKNOWN. Its detached page or
            // canonical winner never escaped, but still needs wiping; visible rows cannot repair it.
            operation?.discardDetached()
            original.observeFailure(problem)
            original.throwIfSignalled()
            throw problem
        }
    }
}
