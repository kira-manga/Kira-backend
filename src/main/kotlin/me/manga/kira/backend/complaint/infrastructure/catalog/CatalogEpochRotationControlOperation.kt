package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import org.springframework.jdbc.core.JdbcTemplate

/** Fixed control-only request/discovery. No epoch fence, counters, scan rows, seal or network work. */
internal class CatalogEpochRotationControlOperation private constructor(
    private val phase: PersistencePhaseContext,
    private val jdbc: JdbcTemplate,
    internal val attempt: CatalogEpochRotationAttemptV1,
    internal val path: PersistencePhasePath,
) {
    private var stage = Stage.RETAINED
    private var observed: CatalogEpochRotationRowV1? = null

    internal fun belongsTo(candidate: CatalogEpochRotationAttemptV1): Boolean = attempt === candidate

    internal fun belongsTo(selected: PersistencePhaseContext, expected: PersistencePhasePath): Boolean = phase === selected && path === expected

    internal fun completedFor(selected: PersistencePhaseContext): Boolean = phase === selected && stage === Stage.COMPLETE

    internal fun requireReleasedRow(): CatalogEpochRotationRowV1 {
        phase.epochRotation.requireCommitted(this)
        requireConnectionFree()
        phase.epochRotation.requireProcessBinding(attempt, jdbc)
        attempt.requireControl(this)
        return checkNotNull(observed)
    }

    /** Pure old-phase commit/release seal: safe while the later exclusive session is active. */
    internal fun requireSealedHandoff(): CatalogEpochRotationRowV1 {
        phase.epochRotation.requireCommitted(this)
        attempt.requireHandoff(this)
        return checkNotNull(observed).also { check(it.slot?.state === CatalogEpochRotationStateV1.REQUESTED) }
    }

    internal fun returnFailure(problem: Throwable): PersistencePhaseException {
        attempt.abort()
        stage = Stage.FAILED
        phase.recordFailure(boundedEpochRotationFailure(problem))
        return phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun execute() {
        try {
            requireAt(Stage.RETAINED)
            val locked = jdbc.query(LOCK_EPOCH_ROTATION_CONTROL, { row, _ -> CatalogEpochRotationRowV1.copy(row) }).single()
            requireAt(Stage.RETAINED)
            stage = Stage.LOCKED
            // The locking projection contains no clock. Only this later statement establishes DB-time authority.
            val sampled = jdbc.query(READ_EPOCH_ROTATION_CONTROL, { row, _ -> CatalogEpochRotationRowV1.copy(row) }).single()
            requireAt(Stage.LOCKED)
            check(locked.sameState(sampled))
            sampled.requireCurrent(attempt)
            val expected = when (path) {
                PersistencePhasePath.COMPLAINT_EPOCH_ROTATION_REQUEST -> {
                    check(sampled.slot == null && sampled.sequence == 0L && sampled.historyEmpty)
                    attempt.retainPredecessor(this, sampled)
                    val arguments = attempt.requestArguments(this) // Exact private preimage/nonce retained BEFORE first mutation.
                    stage = Stage.REQUESTING
                    val changed = jdbc.query(REQUEST_EPOCH_ROTATION_CONTROL, { row, _ -> CatalogEpochRotationRowV1.copy(row) }, *arguments).single()
                    requireAt(Stage.REQUESTING)
                    changed.requireCurrent(attempt)
                    attempt.requireRequestedMutation(this, changed)
                    changed
                }

                PersistencePhasePath.COMPLAINT_EPOCH_ROTATION_RESUME -> {
                    if (sampled.slot == null) attempt.retainPredecessor(this, sampled)
                    sampled // REQUESTED and CAPTURED are discoveries, never rewritten/rebound here.
                }

                else -> error("Unsupported rotation control phase.")
            }
            stage = Stage.REREADING
            val after = jdbc.query(READ_EPOCH_ROTATION_CONTROL, { row, _ -> CatalogEpochRotationRowV1.copy(row) }).single()
            requireAt(Stage.REREADING)
            after.requireCurrent(attempt)
            check(expected.sameState(after))
            observed = after
            stage = Stage.COMPLETE
        } catch (problem: Throwable) {
            val failure = returnFailure(problem)
            PersistencePhaseOwnership.current()?.takeIf { it !== phase }?.recordFailure(failure)
            throw failure
        }
    }

    private fun requireAt(expected: Stage) {
        phase.epochRotation.requireRetained(this, jdbc)
        phase.epochRotation.requireProcessBinding(attempt, jdbc)
        attempt.requireControl(this)
        check(stage === expected && attempt.path === path)
    }

    override fun toString(): String = "CatalogEpochRotationControlOperation(fixed-control-only,no-cutoff-or-work-authority)"

    private enum class Stage { RETAINED, LOCKED, REQUESTING, REREADING, COMPLETE, FAILED }

    companion object {
        internal fun request(jdbc: JdbcTemplate, attempt: CatalogEpochRotationAttemptV1): CatalogEpochRotationControlOperation =
            execute(jdbc, attempt, PersistencePhasePath.COMPLAINT_EPOCH_ROTATION_REQUEST)

        internal fun resume(jdbc: JdbcTemplate, attempt: CatalogEpochRotationAttemptV1): CatalogEpochRotationControlOperation =
            execute(jdbc, attempt, PersistencePhasePath.COMPLAINT_EPOCH_ROTATION_RESUME)

        @Suppress("TooGenericExceptionCaught")
        private fun execute(jdbc: JdbcTemplate, attempt: CatalogEpochRotationAttemptV1, path: PersistencePhasePath): CatalogEpochRotationControlOperation {
            val phase = PersistencePhaseOwnership.current() ?: run {
                attempt.abort()
                throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            }
            try {
                phase.epochRotation.requireOperation(jdbc, path)
                check(attempt.path === path)
                val operation = CatalogEpochRotationControlOperation(phase, jdbc, attempt, path)
                phase.epochRotation.retain(operation, jdbc)
                attempt.retain(operation)
                operation.execute()
                return operation
            } catch (problem: Throwable) {
                attempt.abort()
                phase.recordFailure(boundedEpochRotationFailure(problem))
                throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
            }
        }
    }
}
