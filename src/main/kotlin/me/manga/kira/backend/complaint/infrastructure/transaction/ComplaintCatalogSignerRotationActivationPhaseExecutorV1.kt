package me.manga.kira.backend.complaint.infrastructure.transaction

import me.manga.kira.backend.common.infrastructure.persistence.CatalogCoordinatorPersistence
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationActivationInputV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationActivationOperationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.JdbcCatalogSignerRotationActivationStoreV1
import org.springframework.jdbc.core.JdbcTemplate

/** Original activation owner only: bounded exact2/3 READ, PREPARE, SIGNATURE, COMPLETE, and a distinct same-owner PROJECT transaction. */
internal class ComplaintCatalogSignerRotationActivationPhaseExecutorV1(
    private val coordinator: CatalogCoordinatorPersistence,
    private val jdbc: JdbcTemplate,
) {
    private val ownership = coordinator.ownership

    @Suppress("TooGenericExceptionCaught")
    internal fun execute(input: CatalogSignerRotationActivationInputV1): CatalogSignerRotationActivationOperationV1 {
        requireConnectionFree()
        input.requirePersistence(ownership, jdbc)
        val store = JdbcCatalogSignerRotationActivationStoreV1(jdbc)
        val capacity = JdbcComplaintCapacityStore(jdbc, input.original.capacityDigest())
        val phase = when (input.path) {
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_READ -> ownership.enterComplaintSignerRotationActivationRead(input.original)

            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_COMPLETE -> ownership.enterComplaintSignerRotationActivationComplete(
                input.original,
            )

            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_PROJECT -> ownership.enterComplaintSignerRotationActivationProject(input.original)

            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_PREPARE -> ownership.enterComplaintSignerRotationActivationPrepare(input.original)

            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_SIGNATURE -> ownership.enterComplaintSignerRotationActivationSignature(
                input.original,
            )

            else -> error("Unsupported fixed signer rotation activation phase.")
        }
        var operation: CatalogSignerRotationActivationOperationV1? = null
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

    override fun toString(): String = "ComplaintCatalogSignerRotationActivationPhaseExecutorV1(fixed-activation3-only,no-general-writer)"
}
