package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceEpochRotationSession
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.catalog.boundedEpochRotationFailure

/** The recurrent original's one actual NONPOOLED M/shared -> E/exclusive capture. No row-to-success adapter. */
internal class TestActiveRecurrentCaptureOperationV1 private constructor(
    internal val original: TestActiveRecurrentV1,
    private val session: PersistenceEpochRotationSession,
) {
    private val token = original.operationToken
    private var stage = Stage.RETAINED
    private var captureBindings: Array<Any?>? = null
    private var slotBindings: Array<Any?>? = null
    private var observed: TestActiveRecurrentCurrentV1? = null
    private var released = false

    internal fun belongsTo(selected: PersistenceEpochRotationSession): Boolean = session === selected
    internal fun belongsTo(selected: TestActiveRecurrentV1): Boolean = original === selected
    internal fun completedFor(selected: PersistenceEpochRotationSession): Boolean = session === selected && stage === Stage.COMPLETE
    internal fun authenticationArguments(): Array<Any?> { requireAt(Stage.RETAINED); return original.authenticationArguments() }
    internal fun scopeArguments(): Array<Any?> { requireAt(Stage.RETAINED); return arrayOf(original.scope) }
    internal fun slotArguments(): Array<Any?> { requireAt(Stage.RETAINED); return arrayOf(original.scope, token) }
    internal fun expectedScope() = original.scope
    internal fun expectedSlot() = token
    internal fun readArguments(): Array<Any?> {
        original.requireCapture(this)
        requireRecurrent(stage === Stage.LOCKED || stage === Stage.REREADING)
        return original.identity.arguments()
    }
    internal fun captureArguments(): Array<Any?> { requireAt(Stage.WRITING); return copyFirstCutArguments(checkNotNull(captureBindings)) }
    internal fun captureSlotArguments(): Array<Any?> { requireAt(Stage.WRITING); return copyFirstCutArguments(checkNotNull(slotBindings)) }

    internal fun requireReleasedRow(): TestActiveRecurrentCurrentV1 {
        session.requireReleased(this)
        requireConnectionFree(); original.requireCapture(this)
        released = true
        return checkNotNull(observed).also { requireRecurrent(it.id == token && it.state == "CAPTURED" && it.lease == null) }
    }

    /** Historical same-original comparison only, after the actual session release above, for the next fresh ACQUIRE. */
    internal fun requireReleasedComparison(fresh: TestActiveRecurrentCurrentV1) {
        requireRecurrent(released && stage === Stage.COMPLETE && fresh.id == token)
        checkNotNull(observed).requireSameContent(fresh)
    }
    internal fun discardDetached() { observed?.close() }

    private fun execute() {
        try {
            requireAt(Stage.RETAINED)
            session.lockControl(this) // No pooled holder crosses E. Global -> scope -> run -> exact already-paid recurrent slot.
            requireAt(Stage.RETAINED); stage = Stage.LOCKED
            session.readControl(this).use { before -> // Fresh server time only AFTER every possibly blocking lock.
                requireAt(Stage.LOCKED)
                original.requireCapturePrior(this, before)
                before.requireLease(original.attemptId, original.leaseToken)
                val lease = checkNotNull(before.lease)
                requireRecurrent(before.state == "REQUESTED" && before.sequence in 2..14 && before.id == token &&
                    before.epoch == before.epochBefore && before.scanRequested && before.captureOwner == null && before.capturedAt == null &&
                    before.sealState == null && before.checkpointSha256 == null)
                captureBindings = arrayOf(original.scope, token, *lease.arguments(), before.controlFingerprint())
                slotBindings = arrayOf(original.scope, token, before.slotFingerprint())
                stage = Stage.WRITING
                session.captureControl(this) // Preserve request owner/token, record actual capture, relinquish only this exact scoped lease.
                requireAt(Stage.WRITING); stage = Stage.REREADING
                val after = session.readControl(this)
                try {
                    requireAt(Stage.REREADING)
                    before.requireSameGlobal(after); before.requireSameRun(after); before.requireSameRequest(after)
                    requireRecurrent(after.state == "CAPTURED" && after.epoch == before.epoch + 1 && after.epochAfter == after.epoch &&
                        !after.scanRequested && after.captureOwner == lease.owner && after.captureToken == lease.token &&
                        after.leaseToken == lease.token && after.lease == null && checkNotNull(after.capturedAt) in before.sampledAt..after.sampledAt &&
                        after.sealState == null && after.checkpointSha256 == null)
                    after.slotFingerprint().fill(0)
                    observed = after; stage = Stage.COMPLETE
                } catch (problem: Throwable) { after.close(); throw problem }
            }
        } catch (problem: Throwable) {
            original.observeFailure(problem); original.abort(); stage = Stage.FAILED
            session.failed()
            val actual = session.failure()
            throw PersistencePhaseException(boundedEpochRotationFailure(problem).code, actual.databaseOutcome, actual.cleanupProven)
        } finally {
            listOfNotNull(captureBindings, slotBindings).forEach { values -> values.forEach { if (it is ByteArray) it.fill(0) } }
            captureBindings = null; slotBindings = null
        }
    }
    private fun requireAt(expected: Stage) { original.requireCapture(this); requireRecurrent(stage === expected) }
    override fun toString(): String = "RecurrentCapture(actual-original-exclusive-E,no-portable-authority,redacted)"
    private enum class Stage { RETAINED, LOCKED, WRITING, REREADING, COMPLETE, FAILED }

    companion object {
        internal fun execute(original: TestActiveRecurrentV1, session: PersistenceEpochRotationSession): TestActiveRecurrentCaptureOperationV1 {
            try {
                val operation = TestActiveRecurrentCaptureOperationV1(original, session)
                session.retain(operation); original.retain(operation)
                operation.execute()
                return operation
            } catch (problem: Throwable) {
                original.observeFailure(problem); original.abort(); session.failed()
                val actual = session.failure()
                throw PersistencePhaseException(boundedEpochRotationFailure(problem).code, actual.databaseOutcome, actual.cleanupProven)
            }
        }
    }
}
