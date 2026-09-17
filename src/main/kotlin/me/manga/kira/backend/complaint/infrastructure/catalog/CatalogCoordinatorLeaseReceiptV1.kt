package me.manga.kira.backend.complaint.infrastructure.catalog

import java.time.Instant
import java.util.UUID

internal enum class CatalogCoordinatorLeaseTransitionV1 { ACQUIRED, RENEWED, RELINQUISHED }

/** Actual known-commit AND released transition, not a current/live lease or permission to perform work. */
internal class CatalogCoordinatorLeaseReceiptV1 private constructor(operation: CatalogCoordinatorLeaseOperation) {
    private val transitionFact = operation.requireReleasedTransition()
    val transition: CatalogCoordinatorLeaseTransitionV1 = transitionFact.transition
    val owner: UUID = transitionFact.owner
    val token: Long = transitionFact.token
    val sampledAt: Instant = transitionFact.sampledAt
    val expiresAt: Instant? = transitionFact.expiresAt

    override fun toString(): String = "CatalogCoordinatorLeaseReceiptV1(historical-transition,no-live-or-work-authority)"

    companion object {
        internal fun issuedBy(operation: CatalogCoordinatorLeaseOperation): CatalogCoordinatorLeaseReceiptV1 = CatalogCoordinatorLeaseReceiptV1(operation)
    }
}

/** No supplied fields mint a continuation: only the actual released acquire operation can create this pair. */
internal class CatalogCoordinatorLeaseAcquisitionV1 private constructor(operation: CatalogCoordinatorLeaseOperation) {
    val receipt = operation.receipt
    val campaign = operation.acceptAcquisition()

    override fun toString(): String = "CatalogCoordinatorLeaseAcquisitionV1(local-continuation,historical-receipt,no-work-authority)"

    companion object {
        internal fun issuedBy(operation: CatalogCoordinatorLeaseOperation): CatalogCoordinatorLeaseAcquisitionV1 = CatalogCoordinatorLeaseAcquisitionV1(operation)
    }
}
