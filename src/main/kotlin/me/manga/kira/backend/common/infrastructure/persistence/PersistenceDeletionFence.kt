package me.manga.kira.backend.common.infrastructure.persistence

import java.sql.Connection

/**
 * One dormant journal-fence prefix on the phase's retained deletion or catalog-write holder. This
 * is neither a control-row check nor authority. There is no caller-selected key, retry or work callback.
 * A late true result still owns its transaction-scoped lock until the same phase finishes rollback.
 */
internal class PersistenceDeletionFence(private val phase: PersistencePhaseContext, private val clock: PersistenceNanoClock) {
    private var stage = FenceStage.NEW
    private var active: PersistenceDeletionFenceBudget? = null
    private var observedLock: Boolean? = null

    @Suppress("TooGenericExceptionCaught")
    fun acquire(connection: Connection, work: PersistenceTimeBudget) {
        try {
            phase.requireDeletionFence(this, connection)
            if (stage !== FenceStage.NEW) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            active = PersistenceDeletionFenceBudget(work, clock) // Before preparing or applying any fence-specific settings.
            stage = FenceStage.INSTALLING
            installLimits(connection)
            stage = FenceStage.SETTINGS_RETURNED
            requireRemaining()
            stage = FenceStage.TRYING
            observedLock = connection.prepareStatement(if (phase.path === PersistencePhasePath.COMPLAINT_TEST_ORDINARY_SEAL) TRY_TEST_SEAL_EXCLUSIVE_FENCE else TRY_SHARED_FENCE).use { statement ->
                statement.executeQuery().use { result ->
                    if (!result.next()) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
                    val locked = result.getBoolean(1)
                    if (result.wasNull() || result.next()) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
                    locked
                }
            }
            stage = FenceStage.OBSERVED
            requireRemaining() // Includes the actual boolean, result/statement return and all preceding settings.
            phase.requireAcceptedLease()
            if (observedLock != true) refuse(PersistencePhaseFailureCode.ENTRY_REFUSED)
            stage = FenceStage.ACCEPTED
        } catch (failure: Throwable) {
            stage = FenceStage.FAILED
            phase.recordFailure(failure)
            throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
        } finally {
            active = null // Cleanup always uses the phase's existing work/emergency budget, not an expired fence budget.
        }
    }

    fun accepted(): Boolean = stage === FenceStage.ACCEPTED

    fun active(): Boolean = active != null

    fun callBudget(normal: PersistenceTimeBudget): PersistenceTimeBudget = try {
        active?.dispatchBudget() ?: normal
    } catch (_: PersistenceBoundaryException) {
        refuse(PersistencePhaseFailureCode.TIME_BUDGET_EXHAUSTED)
    }

    fun readCeiling(normalMillis: Long): Long = if (active == null) normalMillis else minOf(normalMillis, FENCE_CALL_MILLIS)

    fun requireRemaining() {
        if (active != null) remainingMillis()
    }

    private fun remainingMillis(): Long = try {
        checkNotNull(active).remainingMillis()
    } catch (_: PersistenceBoundaryException) {
        refuse(PersistencePhaseFailureCode.TIME_BUDGET_EXHAUSTED)
    }

    private fun installLimits(connection: Connection) {
        connection.prepareStatement(FENCE_LIMITS).use { statement ->
            statement.setString(1, remainingMillis().toString() + "ms")
            statement.setString(2, remainingMillis().toString() + "ms")
            statement.executeQuery().use { result ->
                if (!result.next() || result.next()) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
        }
    }

    private fun refuse(code: PersistencePhaseFailureCode): Nothing {
        phase.recordFailure(PersistencePhaseException(code))
        throw phase.failureException(code)
    }

    override fun toString(): String = "PersistenceDeletionFence(redacted)"

    private enum class FenceStage { NEW, INSTALLING, SETTINGS_RETURNED, TRYING, OBSERVED, ACCEPTED, FAILED }

    companion object {
        // One fixed database-wide namespace, separate from future leader/catalog locks. No session-lock fallback.
        // Fixed TEST cutoff/manifest sequence excludes concurrent epoch participants; never upgrade after row locks.
        private const val TRY_TEST_SEAL_EXCLUSIVE_FENCE = "SELECT pg_try_advisory_xact_lock(hashtextextended('complaint-journal-epoch', 0))"
        private const val TRY_SHARED_FENCE = "SELECT pg_try_advisory_xact_lock_shared(hashtextextended('complaint-journal-epoch', 0))"
        private const val FENCE_LIMITS = "SELECT set_config('statement_timeout', ?, true), set_config('lock_timeout', ?, true)"
    }
}

/** The sub-budget never resets or extends the already-started two-second phase. */
internal class PersistenceDeletionFenceBudget(private val work: PersistenceTimeBudget, clock: PersistenceNanoClock) {
    private val deadline = PersistenceTimeBudget.start(100, clock)

    fun remainingMillis(): Long = minOf(work.remainingMillis(FENCE_CALL_MILLIS), deadline.remainingMillis(FENCE_CALL_MILLIS))

    fun dispatchBudget(): PersistenceTimeBudget = if (work.remainingMillis(Long.MAX_VALUE) <= deadline.remainingMillis(Long.MAX_VALUE)) work else deadline
}

private const val FENCE_CALL_MILLIS = 75L
