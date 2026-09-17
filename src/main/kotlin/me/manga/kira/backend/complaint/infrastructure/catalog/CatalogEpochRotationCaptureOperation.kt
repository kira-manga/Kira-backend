package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceEpochRotationSession
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree

/** Only the original typed first-delivery session invokes this fixed exclusive-fence/control operation. */
internal class CatalogEpochRotationCaptureOperation private constructor(
    private val attempt: CatalogEpochRotationAttemptV1,
    private val session: PersistenceEpochRotationSession,
) {
    private var stage = Stage.RETAINED
    private var captureBindings: Array<Any?>? = null
    private var observed: CatalogEpochRotationRowV1? = null

    internal fun belongsTo(selected: PersistenceEpochRotationSession): Boolean = session === selected

    internal fun belongsTo(candidate: CatalogEpochRotationAttemptV1): Boolean = attempt === candidate

    internal fun completedFor(selected: PersistenceEpochRotationSession): Boolean = session === selected && stage === Stage.COMPLETE

    internal fun lockArguments(): Array<Any?> {
        requireAt(Stage.RETAINED)
        return emptyArray()
    }

    internal fun readArguments(): Array<Any?> {
        attempt.requireCapture(this)
        check(stage === Stage.LOCKED || stage === Stage.REREADING)
        return emptyArray()
    }

    /** No caller-provided tuple/SQL or result flag enters the core dispatcher. */
    internal fun captureArguments(): Array<Any?> {
        requireAt(Stage.WRITING)
        return checkNotNull(captureBindings)
    }

    internal fun requireReleasedRow(): CatalogEpochRotationRowV1 {
        session.requireReleased(this)
        requireConnectionFree()
        attempt.requireCapture(this)
        return checkNotNull(observed).also { check(it.slot?.state === CatalogEpochRotationStateV1.CAPTURED) }
    }

    internal fun acceptResult() = attempt.acceptCaptured(this)

    internal fun returnFailure(problem: Throwable): PersistencePhaseException {
        attempt.abort()
        stage = Stage.FAILED
        session.failed()
        val actual = session.failure()
        return PersistencePhaseException(boundedEpochRotationFailure(problem).code, actual.databaseOutcome, actual.cleanupProven)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun execute() {
        try {
            // Core already owns a fresh same-root nonpooled transaction and its EXCLUSIVE epoch fence.
            requireAt(Stage.RETAINED)
            val locked = session.lockControl(this)
            requireAt(Stage.RETAINED)
            stage = Stage.LOCKED
            val sampled = session.readControl(this)
            requireAt(Stage.LOCKED)
            check(locked.sameState(sampled))
            sampled.requireCurrent(attempt)
            val slot = checkNotNull(sampled.slot)
            attempt.requireExactHandoff(this, slot)
            val expected = when (slot.state) {
                CatalogEpochRotationStateV1.REQUESTED -> {
                    captureBindings = attempt.captureArguments(this, sampled)
                    stage = Stage.WRITING
                    val changed = session.captureControl(this)
                    requireAt(Stage.WRITING)
                    changed.requireCurrent(attempt)
                    requireCapturedMutation(sampled, changed)
                    changed
                }
                CatalogEpochRotationStateV1.CAPTURED -> sampled // Exact stored cutoff, never a second increment or epoch-minus-one guess.
            }
            stage = Stage.REREADING
            val after = session.readControl(this)
            requireAt(Stage.REREADING)
            after.requireCurrent(attempt)
            check(expected.sameState(after))
            observed = after
            stage = Stage.COMPLETE
        } catch (problem: Throwable) {
            throw returnFailure(problem)
        }
    }

    private fun requireCapturedMutation(before: CatalogEpochRotationRowV1, after: CatalogEpochRotationRowV1) {
        val request = checkNotNull(before.slot)
        val captured = checkNotNull(after.slot)
        val provenance = checkNotNull(captured.capture)
        val next = Math.addExact(request.epochBefore, 1L)
        check(before.sameAuthorityAndGates(after) && request.sameRequest(captured) && captured.state === CatalogEpochRotationStateV1.CAPTURED)
        check(after.sequence == before.sequence && after.publicationEpoch == next && captured.epochAfter == next && !after.scanRequested)
        check(provenance.owner == attempt.owner && provenance.token == attempt.token && provenance.at == after.sampledAt)
        check(after.updatedAt == after.sampledAt)
    }

    private fun requireAt(expected: Stage) {
        attempt.requireCapture(this)
        check(stage === expected)
    }

    override fun toString(): String = "CatalogEpochRotationCaptureOperation(fixed-exclusive-control,no-seal-or-checkpoint-authority)"

    private enum class Stage { RETAINED, LOCKED, WRITING, REREADING, COMPLETE, FAILED }

    companion object {
        @Suppress("TooGenericExceptionCaught")
        internal fun execute(attempt: CatalogEpochRotationAttemptV1, session: PersistenceEpochRotationSession): CatalogEpochRotationCaptureOperation {
            try {
                val operation = CatalogEpochRotationCaptureOperation(attempt, session)
                session.retain(operation)
                attempt.retain(operation)
                operation.execute()
                return operation
            } catch (problem: Throwable) {
                attempt.abort()
                session.failed()
                val actual = session.failure()
                throw PersistencePhaseException(boundedEpochRotationFailure(problem).code, actual.databaseOutcome, actual.cleanupProven)
            }
        }
    }
}
