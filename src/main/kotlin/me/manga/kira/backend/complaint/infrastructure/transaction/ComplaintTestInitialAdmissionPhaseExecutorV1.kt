package me.manga.kira.backend.complaint.infrastructure.transaction

import me.manga.kira.backend.common.infrastructure.persistence.CatalogCoordinatorPersistence
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestInitialAdmissionV1
import me.manga.kira.backend.complaint.infrastructure.admission.TestInitialAdmissionOperationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationHistoryV1
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator

/** Exact target coordinator, fixed initial CAPTURE/RELEASE SQL; result requires known commit and original cleanup. */
internal class ComplaintTestInitialAdmissionPhaseExecutorV1(private val coordinator: CatalogCoordinatorPersistence) {
    // Dedicated fetch policy; never mutate the generic coordinator/old-reader template.
    private val jdbc = JdbcTemplate(coordinator.dataSource).apply { fetchSize = CatalogTestRunActivationHistoryV1.FETCH_ROWS; exceptionTranslator = SQLExceptionSubclassTranslator() }

    internal fun execute(original: ComplaintTestInitialAdmissionV1): TestInitialAdmissionOperationV1 {
        requireConnectionFree()
        original.requirePersistence(coordinator.ownership, jdbc)
        val phase = coordinator.ownership.enterTestInitialAdmission(original)
        var operation: TestInitialAdmissionOperationV1? = null
        var failure: Throwable? = null
        try {
            phase.begin()
            original.authenticate(coordinator.ownership, jdbc)
            operation = TestInitialAdmissionOperationV1.execute(jdbc, original)
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
