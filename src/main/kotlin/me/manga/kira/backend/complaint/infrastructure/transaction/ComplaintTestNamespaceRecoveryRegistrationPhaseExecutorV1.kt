package me.manga.kira.backend.complaint.infrastructure.transaction

import me.manga.kira.backend.common.infrastructure.persistence.CatalogCoordinatorPersistence
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRecoveryRegistrationAttemptV1
import me.manga.kira.backend.complaint.infrastructure.admission.TestNamespaceRecoveryRegistrationOperationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationHistoryV1
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator

/** Exact target coordinator, fixed read/lock-only SQL; result requires known commit and original cleanup. */
internal class ComplaintTestNamespaceRecoveryRegistrationPhaseExecutorV1(private val coordinator: CatalogCoordinatorPersistence) {
    // Dedicated fetch policy; never mutate the generic coordinator/old-reader template.
    private val jdbc = JdbcTemplate(coordinator.dataSource).apply { fetchSize = CatalogTestRunActivationHistoryV1.FETCH_ROWS; exceptionTranslator = SQLExceptionSubclassTranslator() }

    internal fun execute(original: ComplaintTestNamespaceRecoveryRegistrationAttemptV1): TestNamespaceRecoveryRegistrationOperationV1 {
        requireConnectionFree()
        original.requirePersistence(coordinator.ownership, jdbc)
        val phase = coordinator.ownership.enterTestNamespaceRecoveryRegistration(original)
        var operation: TestNamespaceRecoveryRegistrationOperationV1? = null
        var failure: Throwable? = null
        try {
            phase.begin()
            original.authenticate(coordinator.ownership, jdbc)
            operation = TestNamespaceRecoveryRegistrationOperationV1.execute(jdbc, original)
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
