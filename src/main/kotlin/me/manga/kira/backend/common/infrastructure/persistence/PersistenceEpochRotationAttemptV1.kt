package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogEpochRotationAttemptV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveFirstCutV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveRecurrentV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveRecurrentCaptureOperationV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveFirstCutCaptureOperationV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveFirstCutSuccessorV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveFirstCutSuccessorCaptureOperationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogEpochRotationCaptureOperation

/** Closed LIVE/original-TEST/successor-TEST lineage sum; no executor, SQL callback or supplied receipt. */
internal class PersistenceEpochRotationAttemptV1 private constructor(
    private val live: CatalogEpochRotationAttemptV1?,
    private val test: TestActiveFirstCutV1?,
    private val successor: TestActiveFirstCutSuccessorV1?,
    private val recurrent: TestActiveRecurrentV1?,
) {
    val budget: PersistenceTimeBudget = live?.budget ?: test?.budget ?: successor?.budget ?: checkNotNull(recurrent).captureBudget()

    internal fun requireCore(resource: EpochRotationPersistence) {
        if (live != null) live.requireCore(resource) else if (test != null) test.requireCore(resource) else if (successor != null) successor.requireCore(resource) else checkNotNull(recurrent).requireCore(resource)
    }

    internal fun observeFailure(problem: Throwable) { test?.observeFailure(problem); successor?.observeFailure(problem); recurrent?.observeFailure(problem) }

    internal fun abort() {
        if (live != null) live.abort() else if (test != null) test.abort() else if (successor != null) successor.abort() else checkNotNull(recurrent).abort()
    }

    internal fun requireMaintenanceGate(resource: EpochRotationPersistence, gate: PersistenceComplaintMaintenanceGateV1) {
        requireCore(resource)
        if (live != null) gate.requireUnownedOpen() else if (test != null) test.requireCaptureMaintenanceGate(resource, gate)
        else if (successor != null) successor.requireCaptureMaintenanceGate(resource, gate) else checkNotNull(recurrent).requireCaptureMaintenanceGate(resource, gate)
    }

    internal fun owns(candidate: CatalogEpochRotationAttemptV1): Boolean = live === candidate && test == null && successor == null && recurrent == null
    internal fun owns(candidate: TestActiveFirstCutV1): Boolean = test === candidate && live == null && successor == null && recurrent == null
    internal fun owns(candidate: TestActiveFirstCutSuccessorV1): Boolean = successor === candidate && live == null && test == null && recurrent == null

    internal fun owns(operation: CatalogEpochRotationCaptureOperation): Boolean = live != null && test == null && successor == null && operation.belongsTo(live) && recurrent == null
    internal fun owns(operation: TestActiveFirstCutCaptureOperationV1): Boolean = test != null && live == null && successor == null && operation.belongsTo(test) && recurrent == null
    internal fun owns(operation: TestActiveFirstCutSuccessorCaptureOperationV1): Boolean = successor != null && live == null && test == null && operation.belongsTo(successor) && recurrent == null

    internal fun owns(candidate: TestActiveRecurrentV1): Boolean = recurrent === candidate && live == null && test == null && successor == null
    internal fun owns(operation: TestActiveRecurrentCaptureOperationV1): Boolean = recurrent != null && live == null && test == null && successor == null && operation.belongsTo(recurrent)

    override fun toString(): String = "PersistenceEpochRotationAttemptV1(closed-original-lineage,redacted)"

    companion object {
        internal fun retain(original: TestActiveRecurrentV1) = PersistenceEpochRotationAttemptV1(null, null, null, original)
        internal fun retain(original: CatalogEpochRotationAttemptV1) = PersistenceEpochRotationAttemptV1(original, null, null, null)
        internal fun retain(original: TestActiveFirstCutV1) = PersistenceEpochRotationAttemptV1(null, original, null, null)
        internal fun retain(original: TestActiveFirstCutSuccessorV1) = PersistenceEpochRotationAttemptV1(null, null, original, null)
    }
}
