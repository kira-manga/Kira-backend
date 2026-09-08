package me.manga.kira.backend.common.infrastructure.persistence

import java.util.Date
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Owns one explicit foreign pin. Never cancels the shared Timer or reads pgjdbc/JDK private fields. */
internal class PgLifecycleTimerFixture : AutoCloseable {
    private val utility = AtomicReference<Any?>()
    private val timer = AtomicReference<Timer?>()
    private val released = AtomicBoolean()
    private val thread = AtomicReference<Thread?>()
    private val holds = mutableListOf<Hold>() // Test-only, explicitly capped; no production task history.

    fun acquire() {
        check(utility.get() == null)
        val driver = PersistenceDriverBootstrap.prepare().construct()
        utility.set(driver.javaClass.getMethod("getSharedTimer").invoke(null))
        timer.set(requireNotNull(utility.get()).javaClass.getMethod("getTimer").invoke(utility.get()) as Timer)
    }

    fun hold(ownMonitor: Boolean = false): Hold {
        check(holds.size < 3)
        val hold = Hold(ownMonitor)
        holds.add(hold)
        requireNotNull(timer.get()).schedule(hold, 0L)
        check(hold.entered.await(5, TimeUnit.SECONDS))
        return hold
    }

    fun queued(cancel: Boolean): AtomicBoolean {
        val ran = AtomicBoolean()
        val task = object : TimerTask() {
            override fun run() {
                ran.set(true)
            }
        }
        requireNotNull(timer.get()).schedule(task, 0L)
        if (cancel) check(task.cancel())
        return ran
    }

    fun killThreadForModel(): CountDownLatch {
        val entered = CountDownLatch(1)
        requireNotNull(timer.get()).schedule(
            object : TimerTask() {
                override fun run() {
                    Thread.currentThread().uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, _ -> }
                    entered.countDown()
                    throw AssertionError("Synthetic owned fixture stops Timer to test failed-ended classification.")
                }
            },
            Date(0L),
        )
        return entered
    }

    fun releaseHolds() = holds.forEach { it.unblock.countDown() }

    fun capturedThread(): Thread = requireNotNull(thread.get())

    fun releaseReference() {
        check(timer.get() != null && released.compareAndSet(false, true))
        requireNotNull(utility.get()).javaClass.getMethod("releaseTimer").invoke(utility.get())
    }

    override fun close() {
        releaseHolds()
        val release = runCatching { if (timer.get() != null && !released.get()) releaseReference() }
        val end = runCatching { awaitLifecycleFact { holds.all { it.returned.get() } && thread.get()?.isAlive != true } }
        release.getOrThrow()
        end.getOrThrow()
        println("PG_LIFECYCLE_FOREIGN_TIMER_CLEANUP own_ref_only=true all_terminated=true")
    }

    internal inner class Hold(private val ownMonitor: Boolean) : TimerTask() {
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val returned = AtomicBoolean()

        override fun run() {
            thread.set(Thread.currentThread())
            if (ownMonitor) synchronized(Thread.currentThread()) { awaitRelease() } else awaitRelease()
            returned.set(true)
        }

        private fun awaitRelease() {
            entered.countDown()
            check(unblock.await(30, TimeUnit.SECONDS)) { "Synthetic foreign Timer hold timed out." }
        }
    }
}
