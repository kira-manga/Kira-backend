package me.manga.kira.backend.complaint.infrastructure.transaction

import me.manga.kira.backend.common.infrastructure.persistence.CatalogCoordinatorPersistence
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationInputV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationHistoryV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationKindV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationOperationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.JdbcCatalogTestRunActivationStoreV1
import org.springframework.jdbc.core.JdbcTemplate

/** No external effect or projection exists on this named coordinator. Every result awaits its original holder's release. */
internal class ComplaintCatalogTestRunActivationPhaseExecutorV1(
    private val coordinator: CatalogCoordinatorPersistence,
    private val jdbc: JdbcTemplate,
) {
    init {
        requireConnectionFree()
        check(coordinator.catalogTestRunActivation)
        jdbc.fetchSize = CatalogTestRunActivationHistoryV1.FETCH_ROWS // This named-only template has no ordinary/LIVE executor.
    }

    @Suppress("TooGenericExceptionCaught")
    internal fun execute(input: CatalogTestRunActivationInputV1): CatalogTestRunActivationOperationV1 {
        requireConnectionFree()
        input.requirePersistence(coordinator.ownership, jdbc)
        val store = JdbcCatalogTestRunActivationStoreV1(jdbc)
        val capacity = JdbcComplaintCapacityStore(jdbc, input.frozen.capacityDigest())
        val phase = when (input.kind) {
            CatalogTestRunActivationKindV1.SNAPSHOT -> coordinator.ownership.enterComplaintTestRunActivationSnapshot(input.original)
            CatalogTestRunActivationKindV1.LEASE_ACQUIRE -> coordinator.ownership.enterComplaintTestRunActivationAcquire(input.original)
            CatalogTestRunActivationKindV1.PREPARE -> coordinator.ownership.enterComplaintTestRunActivationPrepare(input.original)
            CatalogTestRunActivationKindV1.PREPARED_RELOAD -> coordinator.ownership.enterComplaintTestRunActivationReload(input.original)
            CatalogTestRunActivationKindV1.SIGNATURE -> coordinator.ownership.enterComplaintTestRunActivationSignature(input.original)
            CatalogTestRunActivationKindV1.SIGNED_RELOAD -> coordinator.ownership.enterComplaintTestRunActivationSignedReload(input.original)
        }
        var operation: CatalogTestRunActivationOperationV1? = null
        var closingFailure: Throwable? = null
        try {
            phase.begin()
            input.original.authenticate(coordinator.ownership, jdbc)
            operation = store.execute(input, capacity)
            input.requirePersistence(coordinator.ownership, jdbc)
            phase.commit()
        } catch (problem: Throwable) {
            phase.recordFailure(problem)
        } finally {
            try {
                closingFailure = runCatching(phase::finish).exceptionOrNull()
                closingFailure?.let(input.original::observeFailure)
            } finally {
                input.original.observePhaseCleanup(phase)
            }
        }
        input.original.throwIfSignalled()
        closingFailure?.let { throw it }
        val actual = operation ?: throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
        actual.requireReleased() // Known commit, exact operation, original manager/JDBC cleanup and refund.
        return actual
    }

    override fun toString(): String = "ComplaintCatalogTestRunActivationPhaseExecutorV1(signed-PREPARED-only,no-provider-or-run-authority)"
}
