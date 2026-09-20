package me.manga.kira.backend.common.infrastructure.persistence

import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Fixture work only. RUNNING is actual entry, not selected-before-run or pgjdbc private-tail evidence. */
internal class PgLifecycleRecordBoundaryTask(private val label: String, held: Boolean) : TimerTask() {
    private val entry = CountDownLatch(1)
    private val release = CountDownLatch(if (held) 1 else 0)
    private val schedulingEntered = AtomicBoolean()
    private val schedulingReturned = AtomicBoolean()
    private val bodyEnded = AtomicBoolean()
    private val problem = AtomicReference<Throwable?>()
    private val thread = AtomicReference<Thread?>()

    fun schedule(timer: Timer) {
        check(schedulingEntered.compareAndSet(false, true)) { "Fixture task cannot be rescheduled: $label" }
        timer.schedule(this, 0L)
        schedulingReturned.set(true)
    }

    override fun run() {
        try {
            runCatching {
                check(thread.compareAndSet(null, Thread.currentThread()))
                entry.countDown()
                check(release.await(25, TimeUnit.SECONDS)) { "Fixture Timer gate timed out: $label" }
            }.onFailure { failure ->
                // Fail outside the Timer thread; never kill that thread as a fault-injection shortcut.
                problem.compareAndSet(null, failure)
            }
        } finally {
            bodyEnded.set(true)
        }
    }

    fun awaitEntry() {
        check(entry.await(5, TimeUnit.SECONDS)) { "Fixture Timer task did not enter: $label" }
        check(schedulingReturned.get())
        checkHealthy()
    }

    fun isRunning(): Boolean = entry.count == 0L && !bodyEnded.get()

    fun isHeldRunning(): Boolean = isRunning() && release.count == 1L

    fun actualThread(): Thread = requireNotNull(thread.get())

    fun release() = release.countDown()

    fun wasScheduled(): Boolean = schedulingEntered.get()

    fun checkHealthy() {
        problem.get()?.let { throw it }
    }

    fun awaitBodyEnded() {
        if (!schedulingEntered.get()) return
        awaitLifecycleFact { bodyEnded.get() }
        check(schedulingReturned.get())
        checkHealthy()
    }

    override fun toString(): String = "PgLifecycleRecordBoundaryTask($label)"
}
