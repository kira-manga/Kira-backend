package me.manga.kira.backend.complaint.infrastructure.transaction

import me.manga.kira.backend.common.infrastructure.persistence.CatalogCoordinatorPersistence
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationObservationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationOperationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationSqlInputV1
import me.manga.kira.backend.complaint.infrastructure.catalog.JdbcCatalogSignerRotationStoreV1
import org.springframework.jdbc.core.JdbcTemplate

/** Only the fixed first-overlap attempt may select these three operations on the original runtime coordinator. */
internal class ComplaintCatalogSignerRotationPersistencePhaseExecutor(private val coordinator: CatalogCoordinatorPersistence, private val jdbc: JdbcTemplate) {
    private val ownership = coordinator.ownership

    @Suppress("TooGenericExceptionCaught")
    internal fun execute(input: CatalogSignerRotationSqlInputV1): CatalogSignerRotationObservationV1 {
        requireConnectionFree()
        input.requirePersistence(ownership, jdbc)
        val store = JdbcCatalogSignerRotationStoreV1(jdbc)
        val capacity = JdbcComplaintCapacityStore(jdbc, input.attempt.capacityDigest())
        val phase = when (input.path) {
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_READ -> ownership.enterComplaintCatalogSignerRotationRead(input.attempt)
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_PREPARE -> ownership.enterComplaintCatalogSignerRotationPrepare(input.attempt)
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_SIGNATURE -> ownership.enterComplaintCatalogSignerRotationSignature(input.attempt)
            else -> error("Unsupported signer rotation phase.")
        }
        var operation: CatalogSignerRotationOperationV1? = null
        var closingFailure: Throwable? = null
        try {
            phase.begin()
            operation = store.execute(input, capacity)
            input.requirePersistence(ownership, jdbc)
            phase.commit()
        } catch (problem: Throwable) {
            phase.recordFailure(problem)
        } finally {
            try {
                closingFailure = runCatching(phase::finish).exceptionOrNull()
                closingFailure?.let(input.attempt::observeFailure)
            } finally {
                input.attempt.observePhaseCleanup(phase) // An unknown original phase is retained; a free thread-local is insufficient.
            }
        }
        input.attempt.throwIfSignalled()
        closingFailure?.let { throw it }
        return (operation ?: throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)).observation
    }

    override fun toString(): String = "ComplaintCatalogSignerRotationPersistencePhaseExecutor(fixed-overlap2-only,no-head-write)"
}
