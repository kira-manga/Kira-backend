package me.manga.kira.backend.complaint.infrastructure.transaction

import me.manga.kira.backend.common.infrastructure.persistence.CatalogCoordinatorPersistence
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationFinalizationInputV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationFinalizationOperationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.JdbcCatalogSignerRotationFinalizationStoreV1
import org.springframework.jdbc.core.JdbcTemplate

/** Original delivery owner only: bounded exact2 READ, COMPLETE, and a distinct same-owner PROJECT transaction. */
internal class ComplaintCatalogSignerRotationFinalizationPhaseExecutorV1(
    private val coordinator: CatalogCoordinatorPersistence,
    private val jdbc: JdbcTemplate,
) {
    private val ownership = coordinator.ownership

    @Suppress("TooGenericExceptionCaught")
    internal fun execute(input: CatalogSignerRotationFinalizationInputV1): CatalogSignerRotationFinalizationOperationV1 {
        requireConnectionFree()
        input.requirePersistence(ownership, jdbc)
        val store = JdbcCatalogSignerRotationFinalizationStoreV1(jdbc)
        val capacity = JdbcComplaintCapacityStore(jdbc, input.original.capacityDigest())
        val phase = when (input.path) {
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_FINAL_READ -> ownership.enterComplaintSignerRotationFinalRead(input.original)
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_COMPLETE -> ownership.enterComplaintSignerRotationComplete(input.original)
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_PROJECT -> ownership.enterComplaintSignerRotationProject(input.original)
            else -> error("Unsupported fixed signer rotation finalization phase.")
        }
        var operation: CatalogSignerRotationFinalizationOperationV1? = null
        var closingFailure: Throwable? = null
        try {
            phase.begin() // Existing shared epoch fence precedes LIVE control/catalog/history/capacity locks.
            input.original.authenticate(ownership, jdbc)
            operation = store.execute(input, capacity)
            input.requirePersistence(ownership, jdbc)
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
        actual.observation // Known commit AND original phase release/refund, not just an empty ThreadLocal.
        return actual
    }

    override fun toString(): String = "ComplaintCatalogSignerRotationFinalizationPhaseExecutorV1(fixed-overlap2-only,no-general-writer)"
}
