package me.manga.kira.backend.security

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget

/** One shrinking same-J seal deadline, not a lease, a prepared intent, or permission to publish. */
internal class EpochSealAttemptV1 internal constructor(
    private val owner: VersionBoundComplaintJournalRouting,
    private val nanoTime: () -> Long,
    private val enclosingBudget: PersistenceTimeBudget? = null,
) {
    private val started = nanoTime()
    private val allowanceNanos = owner.journalConfiguration.declaration().limits.deadlines.epochSealMillis * 1_000_000L
    private var lastElapsed = 0L
    private var expired = false

    init {
        remainingMillis(1)
    }

    internal fun requireOwner(expected: VersionBoundComplaintJournalRouting) = requireEpochSeal(owner === expected)

    /** Safe in local/durable phases too; only the actual key/wire operations require connection-free custody. */
    @Synchronized
    fun remainingMillis(ceilingMillis: Int): Int = epochSealBoundary {
        requireEpochSeal(ceilingMillis > 0)
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        val elapsed = nanoTime() - started
        val remaining = (allowanceNanos - elapsed) / 1_000_000L
        if (expired || elapsed < 0 || elapsed < lastElapsed || elapsed >= allowanceNanos || remaining <= 0) {
            expired = true
            throw EpochSealExceptionV1(EpochSealFailureV1.DEADLINE_EXHAUSTED)
        }
        lastElapsed = elapsed
        val local = minOf(remaining, ceilingMillis.toLong())
        val outer = runCatching { enclosingBudget?.remainingMillis(local) ?: local }
        if (outer.isFailure) expired = true
        outer.getOrThrow().toInt()
    }

    override fun toString(): String = "EpochSealAttemptV1(original-J-deadline,no-authority)"
}
