package me.manga.kira.backend.common.infrastructure.persistence

import java.util.Timer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One public foreign pin on the genuine pgjdbc SharedTimer; no Timer replacement, cancel or private JDK/driver access. */
internal class PgLifecycleRecordBoundaryTimer {
    private val utility = AtomicReference<Any?>()
    private val retained = AtomicReference<Timer?>()
    private val acquireClaimed = AtomicBoolean()
    private val releaseClaimed = AtomicBoolean()
    private val releaseReturned = AtomicBoolean()
    private val capture = PgLifecycleRecordBoundaryTask("capture", held = false)
    val hold = PgLifecycleRecordBoundaryTask("earlier-running", held = true)

    fun acquire() {
        check(acquireClaimed.compareAndSet(false, true))
        val driver = PersistenceDriverBootstrap.prepare().construct()
        utility.set(driver.javaClass.getMethod("getSharedTimer").invoke(null))
        val shared = requireNotNull(utility.get())
        retained.set(shared.javaClass.getMethod("getTimer").invoke(shared) as Timer)
        check(timer().javaClass === Timer::class.java)
        capture.schedule(timer())
        capture.awaitEntry()
        capture.awaitBodyEnded()
    }

    fun timer(): Timer = requireNotNull(retained.get())

    fun actualThread(): Thread = capture.actualThread()

    fun holdRunning() {
        hold.schedule(timer())
        hold.awaitEntry()
        check(hold.isRunning() && hold.actualThread() === actualThread())
    }

    fun releaseGates() {
        capture.release()
        hold.release()
    }

    fun releaseReference() {
        if (retained.get() == null || !releaseClaimed.compareAndSet(false, true)) return
        val shared = requireNotNull(utility.get())
        shared.javaClass.getMethod("releaseTimer").invoke(shared)
        releaseReturned.set(true) // A failed first release is never repaired by a retry/no-op.
    }

    /** Called after root observation; actual Timer-thread join proves more than a task's final in-body publication. */
    fun finish() {
        val captureEnd = runCatching { capture.awaitBodyEnded() }
        val holdEnd = runCatching { hold.awaitBodyEnded() }
        val timerEnd = runCatching {
            if (retained.get() != null) {
                val actual = actualThread()
                pgLifecycleTlsJoin(listOf(actual))
                check(!actual.isAlive && releaseReturned.get())
            }
        }
        listOf(captureEnd, holdEnd, timerEnd).forEach { it.getOrThrow() }
        println(
            "PG_LIFECYCLE_RECORD_BOUNDARY_TIMER_CLEANUP own_ref_only=true acquired=${retained.get() != null} " +
                "release_returned=${releaseReturned.get()} captured_thread_joined=${retained.get() != null}",
        )
    }
}
