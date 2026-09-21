package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogEpochRotationAttemptV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveFirstCutV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveFirstCutCaptureOperationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogEpochRotationCaptureOperation

/** Closed LIVE/ACTIVE-TEST lineage sum for the same physical role; no executor, SQL callback or supplied receipt. */
internal class PersistenceEpochRotationAttemptV1 private constructor(
    private val live: CatalogEpochRotationAttemptV1?,
    private val test: TestActiveFirstCutV1?,
) {
    val budget: PersistenceTimeBudget = live?.budget ?: checkNotNull(test).budget

    internal fun requireCore(resource: EpochRotationPersistence) {
        if (live != null) live.requireCore(resource) else checkNotNull(test).requireCore(resource)
    }

    internal fun observeFailure(problem: Throwable) { test?.observeFailure(problem) }

    internal fun abort() {
        if (live != null) live.abort() else checkNotNull(test).abort()
    }

    internal fun requireMaintenanceGate(resource: EpochRotationPersistence, gate: PersistenceComplaintMaintenanceGateV1) {
        requireCore(resource)
        if (live != null) gate.requireUnownedOpen() else checkNotNull(test).requireCaptureMaintenanceGate(resource, gate)
    }

    internal fun owns(candidate: CatalogEpochRotationAttemptV1): Boolean = live === candidate && test == null
    internal fun owns(candidate: TestActiveFirstCutV1): Boolean = test === candidate && live == null

    internal fun owns(operation: CatalogEpochRotationCaptureOperation): Boolean = live != null && test == null && operation.belongsTo(live)
    internal fun owns(operation: TestActiveFirstCutCaptureOperationV1): Boolean = test != null && live == null && operation.belongsTo(test)

    override fun toString(): String = "PersistenceEpochRotationAttemptV1(closed-original-lineage,redacted)"

    companion object {
        internal fun retain(original: CatalogEpochRotationAttemptV1) = PersistenceEpochRotationAttemptV1(original, null)
        internal fun retain(original: TestActiveFirstCutV1) = PersistenceEpochRotationAttemptV1(null, original)
    }
}
