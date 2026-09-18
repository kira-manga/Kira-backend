package me.manga.kira.backend.common.infrastructure.persistence

import java.sql.Connection

/**
 * Fixed SQL-writer participation on the phase's original holder, before its existing locks.
 * This is not a gate check, an activation right or proof that provider/physical cleanup has ended.
 * One transaction try only: even a late true stays in the original phase's rollback custody.
 */
internal class PersistenceComplaintMaintenanceFenceV1(private val phase: PersistencePhaseContext) {
    private var stage = FenceStage.NEW
    private var active: PersistenceComplaintMaintenanceFenceBudgetV1? = null
    private var observedLock: Boolean? = null

    @Suppress("TooGenericExceptionCaught")
    fun acquire(connection: Connection, work: PersistenceTimeBudget) {
        try {
            phase.requireComplaintMaintenanceFence(this, connection)
            if (stage !== FenceStage.NEW) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            active = PersistenceComplaintMaintenanceFenceBudgetV1(work) // Before any prefix-specific settings or dispatch.
            stage = FenceStage.INSTALLING
            installLimits(connection)
            stage = FenceStage.SETTINGS_RETURNED
            requireRemaining()
            stage = FenceStage.TRYING
            observedLock = connection.prepareStatement(TRY_SHARED_LOCK).use { statement ->
                statement.executeQuery().use { result ->
                    if (!result.next()) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
                    val locked = result.getBoolean(1)
                    if (result.wasNull() || result.next()) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
                    locked
                }
            }
            stage = FenceStage.OBSERVED
            requireRemaining() // Includes actual boolean and result/statement return, not just time before SELECT.
            phase.requireComplaintMaintenanceFence(this, connection)
            if (observedLock != true) refuse(PersistencePhaseFailureCode.ENTRY_REFUSED)
            stage = FenceStage.ACCEPTED
        } catch (failure: Throwable) {
            stage = FenceStage.FAILED
            if (failure is PersistenceBoundaryException) refuse(PersistencePhaseFailureCode.TIME_BUDGET_EXHAUSTED)
            phase.recordFailure(failure)
            throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
        } finally {
            active = null // Original work/emergency cleanup remains callable after prefix expiry.
        }
    }

    fun accepted(): Boolean = stage === FenceStage.ACCEPTED

    fun active(): Boolean = active != null

    fun callBudget(normal: PersistenceTimeBudget): PersistenceTimeBudget = try {
        active?.dispatchBudget() ?: normal
    } catch (_: PersistenceBoundaryException) {
        refuse(PersistencePhaseFailureCode.TIME_BUDGET_EXHAUSTED)
    }

    fun readCeiling(normalMillis: Long): Long = if (active == null) normalMillis else minOf(normalMillis, DISPATCH_MILLIS)

    fun requireRemaining() {
        if (active != null) remainingMillis()
    }

    private fun remainingMillis(): Long = try {
        checkNotNull(active).remainingMillis()
    } catch (_: PersistenceBoundaryException) {
        refuse(PersistencePhaseFailureCode.TIME_BUDGET_EXHAUSTED)
    }

    private fun installLimits(connection: Connection) {
        connection.prepareStatement(LOCAL_LIMITS).use { statement ->
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

    override fun toString(): String = "PersistenceComplaintMaintenanceFenceV1(redacted)"

    private enum class FenceStage { NEW, INSTALLING, SETTINGS_RETURNED, TRYING, OBSERVED, ACCEPTED, FAILED }

    companion object {
        // Facts shared with the concrete nonpooled epoch owner, not a caller-selected lock/SQL capability.
        internal const val TRY_SHARED_LOCK = "SELECT pg_try_advisory_xact_lock_shared(hashtextextended('complaint-maintenance-v1', 0))"
        internal const val LOCAL_LIMITS = "SELECT set_config('statement_timeout', ?, true), set_config('lock_timeout', ?, true)"
        internal const val PREFIX_MILLIS = 100L
        internal const val DISPATCH_MILLIS = 75L
    }
}

/** Every dispatch retains the one prefix deadline AND its original already-started work budget. */
internal class PersistenceComplaintMaintenanceFenceBudgetV1(work: PersistenceTimeBudget) {
    private val prefix = work.capped(PersistenceComplaintMaintenanceFenceV1.PREFIX_MILLIS)

    fun remainingMillis(): Long = prefix.remainingMillis(PersistenceComplaintMaintenanceFenceV1.DISPATCH_MILLIS)

    fun dispatchBudget(): PersistenceTimeBudget = prefix.capped(PersistenceComplaintMaintenanceFenceV1.DISPATCH_MILLIS)
}
