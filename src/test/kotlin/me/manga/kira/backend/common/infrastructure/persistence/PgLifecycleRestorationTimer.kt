package me.manga.kira.backend.common.infrastructure.persistence

import java.util.Timer
import java.util.concurrent.atomic.AtomicReference

/** Read-only own-project identity oracles around one genuine same-Timer task. No MODEL ACK/extent writes. */
internal class PgLifecycleRestorationTimer(private val fixture: PgLifecycleTimerFixture, private val scope: PgLifecycleTestScope) {
    private val hold = fixture.hold()
    private val calls = (lifecycleField(scope.root.timer, "calls") as AtomicReference<*>).get() as PersistenceTimerReferenceCalls
    private val timer = (lifecycleField(calls, "rawTimer") as AtomicReference<*>).get() as Timer
    private val utility = (lifecycleField(calls, "rawUtility") as AtomicReference<*>).get()
    private val thread = fixture.capturedThread()
    private var boundary: PersistenceTimerBoundary? = null

    init {
        check(timer === (lifecycleField(fixture, "timer") as AtomicReference<*>).get())
        check(utility != null && utility === (lifecycleField(fixture, "utility") as AtomicReference<*>).get())
        val capture = requireNotNull(lifecycleField(calls, "capture"))
        check(thread === (lifecycleField(capture, "identity") as AtomicReference<*>).get())
        check(calls.observe().capture.status === PersistenceTimerCaptureStatus.CAPTURED && scope.root.timer.canAcceptStrong())
        requireRunning()
    }

    fun requireRunning() {
        check(hold.entered.count == 0L && hold.unblock.count != 0L && !hold.returned.get() && thread.isAlive)
        check((lifecycleField(scope.root.timer, "calls") as AtomicReference<*>).get() === calls)
        check(!calls.observe().release.entered && !scope.root.shutdown.get())
    }

    fun retainScheduledBoundary(exact: PgLifecycleRestorationAttempt): Boolean {
        requireRunning()
        val found = (lifecycleField(exact.work, "boundary") as AtomicReference<*>).get() as? PersistenceTimerBoundary ?: return false
        val state = found.observation()
        if (!state.scheduling.extentEnded) return false
        check(state.scheduling.entered && state.scheduling.outcome === PersistenceTimerOutcome.RETURNED)
        check(!state.acknowledgement && !found.acknowledged() && exact.work.acknowledgedBoundary() == null)
        check(lifecycleField(found, "timer") === timer && lifecycleField(found, "runner") === exact.runner)
        val cell = requireNotNull(lifecycleField(found, "cell"))
        check(lifecycleField(cell, "expectedThread") === thread)
        check(boundary == null || boundary === found)
        boundary = found
        return true
    }

    fun releaseTask() {
        requireRunning()
        check(boundary != null)
        hold.unblock.countDown()
    }

    fun returned(): Boolean = hold.returned.get()

    fun requireAcknowledged(exact: PgLifecycleRestorationAttempt) {
        val retained = requireNotNull(boundary)
        check((lifecycleField(exact.work, "boundary") as AtomicReference<*>).get() === retained)
        check(exact.work.acknowledgedBoundary() === retained && retained.acknowledged())
        check(retained.observation().scheduling.outcome === PersistenceTimerOutcome.RETURNED)
        check(retained.observation().scheduling.extentEnded && hold.returned.get() && thread.isAlive)
        check(!exact.work.failedTimerWorkEnded() && !calls.observe().release.entered)
    }
}
