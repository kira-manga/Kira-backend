package me.manga.kira.backend.security

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1

internal enum class TestTerminalCodecKindV1 { INSTALLATION_MANIFEST, TEST_RUN_PURGE, EPOCH_SEAL }

/** One codec/kind-bound original deadline. No prepared intent, custody, registered run or provider authority. */
internal class TestTerminalAttemptV1 internal constructor(
    private val owner: Any,
    private val journal: TestOwnerDeleteJournalConfigurationV1,
    val kind: TestTerminalCodecKindV1,
    private val nanoTime: () -> Long,
    private val enclosingBudget: PersistenceTimeBudget? = null,
) {
    private val started = nanoTime()
    private val allowanceNanos = journal.declaration().limits.deadlines.let {
        if (kind == TestTerminalCodecKindV1.EPOCH_SEAL) it.epochSealMillis else it.publicationAttemptMillis
    } * 1_000_000L
    private var lastElapsed = 0L
    private var expired = false

    init { remainingMillis(1) }

    internal fun requireOwner(expected: Any, expectedKind: TestTerminalCodecKindV1) {
        requireTestTerminalCodec(owner === expected && kind == expectedKind)
    }

    internal fun requireJournal(expected: TestOwnerDeleteJournalConfigurationV1) {
        requireTestTerminalCodec(journal === expected)
    }

    /** The fixed SDK adapter uses this same checked clock; even a rejected call cannot hide a backward/expired sample. */
    @Synchronized
    internal fun providerNanoTime(): Long = testTerminalCodecBoundary {
        checkRequest(1)
        val current = nanoTime()
        remainingAt(current, 1)
        current
    }

    /** JournalKmsCall intersects this ORIGINAL enclosing budget at every provider/HTTP sample. */
    internal fun remainingProviderMillis(ceilingMillis: Int): Int = remainingMillis(ceilingMillis)

    @Synchronized
    fun remainingMillis(ceilingMillis: Int): Int = testTerminalCodecBoundary {
        checkRequest(ceilingMillis)
        remainingAt(nanoTime(), ceilingMillis)
    }

    private fun checkRequest(ceilingMillis: Int) {
        requireTestTerminalCodec(ceilingMillis > 0)
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
    }

    private fun remainingAt(current: Long, ceilingMillis: Int): Int {
        // Signed-wrap subtraction follows nanoTime's elapsed-interval contract, not wall-clock arithmetic.
        val elapsed = current - started
        val remaining = (allowanceNanos - elapsed) / 1_000_000L
        if (expired || elapsed < 0 || elapsed < lastElapsed || elapsed >= allowanceNanos || remaining <= 0) {
            expired = true
            throw TestTerminalCodecExceptionV1(TestTerminalCodecFailureV1.DEADLINE_EXHAUSTED)
        }
        lastElapsed = elapsed
        val local = minOf(remaining, ceilingMillis.toLong())
        val outer = runCatching { enclosingBudget?.remainingMillis(local) ?: local }
        if (outer.isFailure) expired = true
        return outer.getOrThrow().toInt()
    }

    override fun toString(): String = "TestTerminalAttemptV1(original-J-deadline,no-authority)"
}

/** Borrowed trusted key port. Exact identity prevents using a different real adapter's original attempt. */
internal interface TestTerminalDataKeyPortV1 : JournalDataKeyPortV1 {
    val attempt: TestTerminalAttemptV1
}
