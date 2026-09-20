package me.manga.kira.backend.complaint.infrastructure.transaction

import me.manga.kira.backend.common.infrastructure.persistence.CatalogCoordinatorPersistence
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunSealingOperationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunSealingV1
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator

/** Fixed normal-root phases. An operation can leave this executor only after its original committed release. */
internal class ComplaintTestRunSealingPhaseExecutorV1(private val coordinator: CatalogCoordinatorPersistence) {
    private val jdbc = JdbcTemplate(coordinator.dataSource).apply { exceptionTranslator = SQLExceptionSubclassTranslator() }

    internal fun execute(original: TestRunSealingV1): TestRunSealingOperationV1 {
        requireConnectionFree()
        original.requirePersistence(coordinator.ownership, jdbc)
        val phase = when (original.path) {
            PersistencePhasePath.COMPLAINT_TEST_RUN_SEAL -> coordinator.ownership.enterTestRunSeal(original)
            PersistencePhasePath.COMPLAINT_TEST_RUN_SEALED_AUDIT -> coordinator.ownership.enterTestRunSealedAudit(original)
            else -> error("Unexpected TEST run sealing phase.")
        }
        var operation: TestRunSealingOperationV1? = null
        var failure: Throwable? = null
        try {
            phase.begin()
            original.authenticate(coordinator.ownership, jdbc)
            operation = TestRunSealingOperationV1.execute(jdbc, original)
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
        val actual = operation ?: throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
        actual.releasedSealedAt()
        return actual
    }
}
