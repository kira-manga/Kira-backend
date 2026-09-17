package me.manga.kira.backend.common.infrastructure.persistence

internal fun interface PersistenceNanoClock {
    fun nanoTime(): Long
}

internal object SystemPersistenceNanoClock : PersistenceNanoClock {
    override fun nanoTime(): Long = System.nanoTime()
}

/**
 * One immutable same-JVM elapsed budget, not a wall-clock timestamp or a promise of resource disposal.
 * Callers still check cancellation and validate the result after each blocking operation.
 */
internal class PersistenceTimeBudget private constructor(
    private val clock: PersistenceNanoClock,
    private val startedAtNanos: Long,
    private val allowanceNanos: Long,
    private val parent: PersistenceTimeBudget? = null,
) {
    /** Floor, never round up into extra work or return the zero that many timeout APIs treat as unlimited. */
    fun remainingMillis(ceilingMillis: Long): Long {
        if (ceilingMillis <= 0) rejectPersistenceBoundary(PersistenceBoundaryFailureCode.INVALID_TIME_BUDGET)
        // Intentional signed-wrap subtraction follows nanoTime's contract for elapsed intervals below 2^63ns.
        val elapsed = clock.nanoTime() - startedAtNanos
        if (elapsed < 0 || elapsed >= allowanceNanos) rejectPersistenceBoundary(PersistenceBoundaryFailureCode.TIME_BUDGET_EXHAUSTED)
        val remainingMillis = (allowanceNanos - elapsed) / NANOS_PER_MILLISECOND
        if (remainingMillis <= 0) rejectPersistenceBoundary(PersistenceBoundaryFailureCode.TIME_BUDGET_EXHAUSTED)
        val local = minOf(remainingMillis, ceilingMillis)
        return parent?.let { minOf(local, it.remainingMillis(ceilingMillis)) } ?: local
    }

    /** A stricter stage cap retains the original total deadline; no retry receives a restarted allowance. */
    internal fun capped(ceilingMillis: Long): PersistenceTimeBudget {
        remainingMillis(ceilingMillis)
        if (ceilingMillis > Long.MAX_VALUE / NANOS_PER_MILLISECOND) rejectPersistenceBoundary(PersistenceBoundaryFailureCode.INVALID_TIME_BUDGET)
        return PersistenceTimeBudget(clock, clock.nanoTime(), ceilingMillis * NANOS_PER_MILLISECOND, this)
    }

    /**
     * Called outside F/G/T. The factory's protected predicates may read only the fixed system clock,
     * never a supplied parent clock. Sampling system time first conservatively charges this copy's
     * own construction time; callers still recheck the original budget outside the protected cut.
     */
    internal fun systemCappedSnapshot(ceilingMillis: Long): PersistenceTimeBudget {
        val started = System.nanoTime()
        val remaining = remainingMillis(ceilingMillis)
        return PersistenceTimeBudget(SystemPersistenceNanoClock, started, remaining * NANOS_PER_MILLISECOND)
    }

    override fun toString(): String = "PersistenceTimeBudget(redacted)"

    companion object {
        private const val NANOS_PER_MILLISECOND = 1_000_000L

        fun start(allowanceMillis: Long, clock: PersistenceNanoClock = SystemPersistenceNanoClock): PersistenceTimeBudget {
            if (allowanceMillis <= 0 || allowanceMillis > Long.MAX_VALUE / NANOS_PER_MILLISECOND) {
                rejectPersistenceBoundary(PersistenceBoundaryFailureCode.INVALID_TIME_BUDGET)
            }
            return PersistenceTimeBudget(clock, clock.nanoTime(), allowanceMillis * NANOS_PER_MILLISECOND)
        }
    }
}
