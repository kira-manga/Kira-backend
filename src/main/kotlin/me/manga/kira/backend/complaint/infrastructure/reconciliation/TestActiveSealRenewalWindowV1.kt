package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceNanoClock

/** Same retained clock for every SQL/window sample; backward movement poisons the original. */
internal class TestActiveSealNanoClockV1(private val actual: PersistenceNanoClock) : PersistenceNanoClock {
    private val started = actual.nanoTime()
    private var lastElapsed = 0L
    private var failed = false
    @Synchronized
    override fun nanoTime(): Long {
        requireActiveSeal(!failed)
        val now = try { actual.nanoTime() } catch (problem: Throwable) { failed = true; throw problem }
        val elapsed = now - started // nanoTime signed-wrap elapsed contract, not wall-clock arithmetic.
        if (elapsed < 0 || elapsed < lastElapsed) { failed = true; throw TestActiveOrdinarySealExceptionV1() }
        lastElapsed = elapsed
        return now
    }
}

/** A budget begun BEFORE renewal dispatch includes checkout, SQL waits, COMMIT and physical cleanup. */
internal class TestActiveSealRenewalWindowV1(private val original: PersistenceTimeBudget) {
    private var current: PersistenceTimeBudget? = null
    private var pending: PersistenceTimeBudget? = null
    private var failed = false
    fun beginDispatch(first: Boolean) {
        requireActiveSeal(!failed && pending == null && first == (current == null))
        if (!first) remainingMillis(1)
        // This exact budget, not a post-COMMIT restarted ten seconds, will be retained on success.
        pending = original.capped(10_000)
    }
    fun committedAndReleased() {
        requireActiveSeal(!failed)
        val dispatched = checkNotNull(pending)
        try {
            current?.remainingMillis(1) // Renewal COMMIT/cleanup must finish within the previous actual window too.
            dispatched.remainingMillis(1); current = dispatched; pending = null
        } catch (problem: Throwable) { failed = true; throw problem }
    }
    fun remainingMillis(ceiling: Int): Int {
        requireActiveSeal(!failed && ceiling > 0)
        return try { checkNotNull(current).remainingMillis(ceiling.toLong()).toInt() }
        catch (problem: Throwable) { failed = true; throw problem }
    }
    fun providerBudget(): PersistenceTimeBudget { remainingMillis(1); return checkNotNull(current) }
    fun phaseBudget(): PersistenceTimeBudget {
        requireActiveSeal(!failed)
        return try { (current ?: pending ?: original).capped(2_000) }
        catch (problem: Throwable) { failed = true; throw problem }
    }
    fun poison() { failed = true }
}
