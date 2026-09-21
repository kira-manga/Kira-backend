package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceEpochRotationSession
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.catalog.boundedEpochRotationFailure

/** Fixed same-original NONPOOLED first capture. No resumable/row-to-success constructor and no seal/checkpoint writer. */
internal class TestActiveFirstCutCaptureOperationV1 private constructor(
    internal val original: TestActiveFirstCutV1,
    private val session: PersistenceEpochRotationSession,
) {
    private var stage = Stage.RETAINED
    private var captureBindings: Array<Any?>? = null
    private var slotBindings: Array<Any?>? = null
    private var observed: TestActiveFirstCutStateV1? = null

    internal fun belongsTo(selected: PersistenceEpochRotationSession): Boolean = session === selected
    internal fun belongsTo(selected: TestActiveFirstCutV1): Boolean = original === selected
    internal fun completedFor(selected: PersistenceEpochRotationSession): Boolean = session === selected && stage === Stage.COMPLETE

    internal fun authenticationArguments(): Array<Any?> { requireAt(Stage.RETAINED); return original.authenticationArguments() }
    internal fun scopeArguments(): Array<Any?> { requireAt(Stage.RETAINED); return arrayOf(original.identity.scope) }
    internal fun expectedScope() = original.identity.scope
    internal fun expectedSlot() = original.nonce
    internal fun readArguments(): Array<Any?> {
        original.requireCapture(this)
        requireFirstCut(stage === Stage.LOCKED || stage === Stage.REREADING)
        return original.identity.arguments()
    }
    internal fun captureArguments(): Array<Any?> { requireAt(Stage.WRITING); return copyFirstCutArguments(checkNotNull(captureBindings)) }
    internal fun captureSlotArguments(): Array<Any?> { requireAt(Stage.WRITING); return copyFirstCutArguments(checkNotNull(slotBindings)) }

    internal fun requireReleasedRow(): TestActiveFirstCutStateV1 {
        session.requireReleased(this)
        requireConnectionFree()
        original.requireCapture(this)
        return checkNotNull(observed).also { requireFirstCut(it.state == "CAPTURED") }
    }

    internal fun returnFailure(problem: Throwable): PersistencePhaseException {
        original.observeFailure(problem)
        original.abort()
        stage = Stage.FAILED
        session.failed()
        val actual = session.failure()
        return PersistencePhaseException(boundedEpochRotationFailure(problem).code, actual.databaseOutcome, actual.cleanupProven)
    }

    private fun execute() {
        try {
            requireAt(Stage.RETAINED)
            session.lockControl(this) // Already M/shared -> E/exclusive, then global -> scope -> run -> existing paid slot.
            requireAt(Stage.RETAINED)
            stage = Stage.LOCKED
            val before = session.readControl(this) // A fresh DB-time sample AFTER every possibly blocking lock.
            requireAt(Stage.LOCKED)
            original.requirePrior(before)
            val lease = original.selectedLease()
            before.requireCurrent(lease)
            requireFirstCut(before.state == "REQUESTED" && before.sequence == 1L && before.id == original.nonce &&
                before.epochBefore == 1L && before.epoch == 1L && before.scanRequested && before.requestOwner == lease.owner && before.requestToken == lease.token)
            captureBindings = arrayOf(original.identity.scope, original.nonce, *lease.arguments(), before.controlFingerprint())
            slotBindings = arrayOf(original.identity.scope, original.nonce, before.slotFingerprint())
            stage = Stage.WRITING
            session.captureControl(this) // Exact current scoped owner is relinquished atomically, never global/foreign.
            requireAt(Stage.WRITING)
            stage = Stage.REREADING
            val after = session.readControl(this)
            requireAt(Stage.REREADING)
            before.requireSameGlobal(after)
            before.requireSameRun(after)
            before.requireSameRequest(after)
            requireFirstCut(after.state == "CAPTURED" && after.sequence == 1L && after.epoch == 2L && after.epochAfter == 2L && !after.scanRequested &&
                after.captureOwner == lease.owner && after.captureToken == lease.token && after.leaseToken == lease.token && after.lease == null &&
                checkNotNull(after.capturedAt) >= before.sampledAt)
            after.slotFingerprint() // The fixed read checked the complete paid identity and exact V17 capture linkage.
            observed = after
            stage = Stage.COMPLETE
        } catch (problem: Throwable) {
            throw returnFailure(problem)
        }
    }

    private fun requireAt(expected: Stage) {
        original.requireCapture(this)
        requireFirstCut(stage === expected)
    }
    override fun toString(): String = "TestActiveFirstCutCaptureOperationV1(original-exclusive-E,first-range-only,no-seal-or-health-authority)"
    private enum class Stage { RETAINED, LOCKED, WRITING, REREADING, COMPLETE, FAILED }

    companion object {
        internal fun execute(original: TestActiveFirstCutV1, session: PersistenceEpochRotationSession): TestActiveFirstCutCaptureOperationV1 {
            try {
                val operation = TestActiveFirstCutCaptureOperationV1(original, session)
                session.retain(operation)
                original.retain(operation)
                operation.execute()
                return operation
            } catch (problem: Throwable) {
                original.observeFailure(problem)
                original.abort()
                session.failed()
                val actual = session.failure()
                throw PersistencePhaseException(boundedEpochRotationFailure(problem).code, actual.databaseOutcome, actual.cleanupProven)
            }
        }
    }
}
