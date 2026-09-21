package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogEpochRotationAttemptV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveFirstCutV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveFirstCutCaptureOperationV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveFirstCutSuccessorV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveFirstCutSuccessorCaptureOperationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogEpochRotationCaptureOperation

/** Closed LIVE/original-TEST/successor-TEST lineage sum; no executor, SQL callback or supplied receipt. */
internal class PersistenceEpochRotationAttemptV1 private constructor(
    private val live: CatalogEpochRotationAttemptV1?,
    private val test: TestActiveFirstCutV1?,
    private val successor: TestActiveFirstCutSuccessorV1?,
) {
    val budget: PersistenceTimeBudget = live?.budget ?: test?.budget ?: checkNotNull(successor).budget

    internal fun requireCore(resource: EpochRotationPersistence) {
        if (live != null) live.requireCore(resource) else if (test != null) test.requireCore(resource) else checkNotNull(successor).requireCore(resource)
    }

    internal fun observeFailure(problem: Throwable) { test?.observeFailure(problem); successor?.observeFailure(problem) }

    internal fun abort() {
        if (live != null) live.abort() else if (test != null) test.abort() else checkNotNull(successor).abort()
    }

    internal fun requireMaintenanceGate(resource: EpochRotationPersistence, gate: PersistenceComplaintMaintenanceGateV1) {
        requireCore(resource)
        if (live != null) gate.requireUnownedOpen() else if (test != null) test.requireCaptureMaintenanceGate(resource, gate)
        else checkNotNull(successor).requireCaptureMaintenanceGate(resource, gate)
    }

    internal fun owns(candidate: CatalogEpochRotationAttemptV1): Boolean = live === candidate && test == null && successor == null
    internal fun owns(candidate: TestActiveFirstCutV1): Boolean = test === candidate && live == null && successor == null
    internal fun owns(candidate: TestActiveFirstCutSuccessorV1): Boolean = successor === candidate && live == null && test == null

    internal fun owns(operation: CatalogEpochRotationCaptureOperation): Boolean = live != null && test == null && successor == null && operation.belongsTo(live)
    internal fun owns(operation: TestActiveFirstCutCaptureOperationV1): Boolean = test != null && live == null && successor == null && operation.belongsTo(test)
    internal fun owns(operation: TestActiveFirstCutSuccessorCaptureOperationV1): Boolean = successor != null && live == null && test == null && operation.belongsTo(successor)

    override fun toString(): String = "PersistenceEpochRotationAttemptV1(closed-original-lineage,redacted)"

    companion object {
        internal fun retain(original: CatalogEpochRotationAttemptV1) = PersistenceEpochRotationAttemptV1(original, null, null)
        internal fun retain(original: TestActiveFirstCutV1) = PersistenceEpochRotationAttemptV1(null, original, null)
        internal fun retain(original: TestActiveFirstCutSuccessorV1) = PersistenceEpochRotationAttemptV1(null, null, original)
    }
}
