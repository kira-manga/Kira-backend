package me.manga.kira.backend.common.infrastructure.persistence

import java.sql.Driver
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Explicit fixture-owned foreign pin on the real public utility. Never reads a refcount or cancels its Timer. */
internal class PgTimerForeignReference(private val driver: Driver, private val holdTask: Boolean) : AutoCloseable {
    private val utility = AtomicReference<Any?>()
    private val timer = AtomicReference<Timer?>()
    private val acquisitionEntered = AtomicBoolean()
    private val releaseEntered = AtomicBoolean()
    private val taskEntered = CountDownLatch(1)
    private val unblock = CountDownLatch(1)
    private val taskReturned = AtomicBoolean()
    private val thread = AtomicReference<Thread?>()
    private val witness = object : TimerTask() {
        override fun run() {
            thread.set(Thread.currentThread())
            taskEntered.countDown()
            if (holdTask) check(unblock.await(10, TimeUnit.SECONDS))
            taskReturned.set(true)
        }
    }

    fun acquire() {
        check(acquisitionEntered.compareAndSet(false, true))
        utility.set(driver.javaClass.getMethod("getSharedTimer").invoke(null))
        timer.set(requireNotNull(utility.get()).javaClass.getMethod("getTimer").invoke(utility.get()) as Timer)
        requireNotNull(timer.get()).schedule(witness, 0L)
        check(taskEntered.await(5, TimeUnit.SECONDS))
    }

    fun releaseHold() = unblock.countDown()

    fun capturedThread(): Thread = requireNotNull(thread.get())

    fun verifyHeld() {
        check(holdTask && !taskReturned.get() && capturedThread().isAlive)
    }

    fun releaseReference() {
        check(timer.get() != null && releaseEntered.compareAndSet(false, true))
        requireNotNull(utility.get()).javaClass.getMethod("releaseTimer").invoke(utility.get())
    }

    override fun close() {
        releaseHold()
        val release = runCatching { if (timer.get() != null && !releaseEntered.get()) releaseReference() }
        val ended = runCatching {
            if (timer.get() != null) awaitPgFixtureFact { taskReturned.get() && !requireNotNull(thread.get()).isAlive }
        }
        release.getOrThrow()
        ended.getOrThrow()
        println("PG_TIMER_FOREIGN_CLEANUP thread_id=${thread.get()?.threadId() ?: 0} all_terminated=true own_ref_only=true")
    }
}
