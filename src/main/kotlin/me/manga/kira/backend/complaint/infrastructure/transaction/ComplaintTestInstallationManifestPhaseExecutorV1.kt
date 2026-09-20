package me.manga.kira.backend.complaint.infrastructure.transaction

import me.manga.kira.backend.common.infrastructure.persistence.CatalogCoordinatorPersistence
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.terminal.TestInstallationManifestOperationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunInstallationManifestV1
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator

/** Fixed normal-root phases. An operation can leave this executor only after its original committed release. */
internal class ComplaintTestInstallationManifestPhaseExecutorV1(private val coordinator: CatalogCoordinatorPersistence) {
    private val jdbc = JdbcTemplate(coordinator.dataSource).apply { exceptionTranslator = SQLExceptionSubclassTranslator() }

    internal fun execute(original: TestRunInstallationManifestV1): TestInstallationManifestOperationV1 {
        requireConnectionFree()
        original.requirePersistence(coordinator.ownership, jdbc)
        val phase = coordinator.ownership.enterTestInstallationManifest(original)
        var operation: TestInstallationManifestOperationV1? = null
        var failure: Throwable? = null
        try {
            phase.begin()
            original.authenticate(coordinator.ownership, jdbc)
            operation = TestInstallationManifestOperationV1.execute(jdbc, original)
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
        actual.requireReleased()
        return actual
    }
}
