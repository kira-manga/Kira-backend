package me.manga.kira.backend.common.infrastructure.persistence

import java.util.Timer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Real foreign pin/hold and read-only own-project identity checks; never a replacement Timer or a manufactured capture. */
internal class PgLifecycleTrackedWeakNoRawCapture : AutoCloseable {
    private val foreign = PgLifecycleTimerFixture()
    private var hold: PgLifecycleTimerFixture.Hold? = null
    private var calls: PersistenceTimerReferenceCalls? = null

    fun acquireAndHold() {
        foreign.acquire()
        hold = foreign.hold()
    }

    fun awaitPending(scope: PgLifecycleTestScope) {
        awaitLifecycleFact {
            val observed = (lifecycleField(scope.root.timer, "calls") as AtomicReference<*>).get() as? PersistenceTimerReferenceCalls
            observed?.observe()?.scheduling?.extentEnded == true
        }
        calls = (lifecycleField(scope.root.timer, "calls") as AtomicReference<*>).get() as PersistenceTimerReferenceCalls
        requirePending(scope)
        println("PG_LIFECYCLE_TRACKED_WEAK_NO_RAW_CAPTURE pending=true same_timer=true proof=REAL_SHARED_TIMER")
    }

    fun requirePending(scope: PgLifecycleTestScope) {
        val retained = requireNotNull(calls)
        check((lifecycleField(scope.root.timer, "calls") as AtomicReference<*>).get() === retained)
        val timer = (lifecycleField(foreign, "timer") as AtomicReference<*>).get()
        check(timer?.javaClass === Timer::class.java)
        check((lifecycleField(retained, "rawTimer") as AtomicReference<*>).get() === timer)
        check(
            (lifecycleField(retained, "rawUtility") as AtomicReference<*>).get() ===
                (lifecycleField(foreign, "utility") as AtomicReference<*>).get(),
        )
        val observed = retained.observe()
        check(observed.referenceReturned && observed.stockTimerReturned)
        check(observed.acquisition.extentEnded && observed.acquisition.outcome === PersistenceTimerOutcome.RETURNED)
        check(observed.scheduling.entered && observed.scheduling.extentEnded && observed.scheduling.outcome === PersistenceTimerOutcome.RETURNED)
        check(observed.capture.status === PersistenceTimerCaptureStatus.PENDING && !observed.capture.publicationReceived)
        check(!observed.release.entered && !scope.owner.snapshot().timerReady && !scope.root.timer.canAcceptStrong())
        check(requireNotNull(hold).entered.count == 0L && !requireNotNull(hold).returned.get() && foreign.capturedThread().isAlive)
    }

    fun releaseAndRequireReady(scope: PgLifecycleTestScope) {
        requirePending(scope)
        requireNotNull(hold).unblock.countDown()
        awaitLifecycleFact { scope.owner.snapshot().timerReady && requireNotNull(hold).returned.get() }
        val retained = requireNotNull(calls)
        check((lifecycleField(scope.root.timer, "calls") as AtomicReference<*>).get() === retained)
        check(
            (lifecycleField(retained, "rawTimer") as AtomicReference<*>).get() ===
                (lifecycleField(foreign, "timer") as AtomicReference<*>).get(),
        )
        val capture = requireNotNull(lifecycleField(retained, "capture"))
        check((lifecycleField(capture, "identity") as AtomicReference<*>).get() === foreign.capturedThread())
        val observed = retained.observe()
        check(observed.capture.status === PersistenceTimerCaptureStatus.CAPTURED && observed.capture.publicationReceived)
        check(!observed.release.entered && scope.root.timer.canAcceptStrong())
        println("PG_LIFECYCLE_TRACKED_WEAK_NO_RAW_CAPTURE_RELEASED ready=true same_thread=true proof=REAL_SHARED_TIMER")
    }

    fun releaseGates() = foreign.releaseHolds()

    /** The fixture's own reference must end before the root observer waits for shared-Timer termination. */
    fun releaseReferenceIfAcquired() {
        val acquired = (lifecycleField(foreign, "timer") as AtomicReference<*>).get() != null
        val released = (lifecycleField(foreign, "released") as AtomicBoolean).get()
        if (acquired && !released) foreign.releaseReference()
    }

    override fun close() = foreign.close()
}
