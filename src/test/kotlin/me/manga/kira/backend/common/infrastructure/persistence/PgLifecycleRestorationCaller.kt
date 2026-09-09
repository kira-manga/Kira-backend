package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/** Child-safe hostile caller. Only the explicit fixture injection bypasses the restoration override. */
internal class PgLifecycleRestorationCaller(private val owner: PersistenceJdbcLifecycleOwner, private val deletion: Boolean) :
    Thread(null, null, "synthetic-lifecycle-restoration-caller", 0, false),
    AutoCloseable {
    private val sample = PgLifecycleRestorationGate()
    private val restoration = PgLifecycleRestorationGate()
    private val witness = AtomicReference<PgLifecycleRestorationAttempt?>()
    private val sampleClaimed = AtomicBoolean()
    private val sampleOutsideLocks = AtomicBoolean()
    private val restorationOutsideLocks = AtomicBoolean()
    private val injections = AtomicInteger()
    private val restores = AtomicInteger()
    private val restoreReturned = AtomicBoolean()
    private val startClaimed = AtomicBoolean()
    private val closing = AtomicBoolean()
    private val bodyEntered = AtomicBoolean()
    private val bodyEnded = AtomicBoolean()
    private val requestReturned = AtomicBoolean()
    private val returnedFlag = AtomicBoolean()
    private val result = AtomicReference<PersistenceFactoryResult<PersistenceJdbcCandidate>?>()
    private val problem = AtomicReference<Throwable?>()

    fun launch() {
        check(!closing.get() && startClaimed.compareAndSet(false, true))
        super.start()
    }

    override fun run() {
        check(Thread.currentThread() === this && bodyEntered.compareAndSet(false, true))
        try {
            runCatching {
                result.set(if (deletion) owner.requestDeletion() else owner.requestOrdinary())
                returnedFlag.set(super.isInterrupted())
                requestReturned.set(true)
            }.onFailure { problem.set(it) } // Retain, never render, the original fixture/request failure.
        } finally {
            Thread.interrupted() // Only this caller's final cleanup, after the actual returned flag was recorded.
            bodyEnded.set(true)
        }
    }

    override fun isInterrupted(): Boolean {
        val exact = witness.get()
        if (Thread.currentThread() === this && exact != null && sampleClaimed.compareAndSet(false, true)) {
            exact.requireCallerOutsideLocks(this)
            sampleOutsideLocks.set(true)
            sample.hold()
        }
        return false // The injected actual flag, not this override's answer, must cause abandonment.
    }

    override fun interrupt() {
        check(Thread.currentThread() === this && injections.get() == 1 && restores.incrementAndGet() == 1)
        val exact = requireNotNull(witness.get())
        exact.requireCallerOutsideLocks(this)
        check(exact.control.state() === PersistenceOwnedCallerDisposition.ABANDONED_INTERRUPTED)
        check(!super.isInterrupted()) { "Managed sampling did not consume the genuine actual flag before restoration." }
        restorationOutsideLocks.set(true)
        restoration.hold()
        super.interrupt()
        restoreReturned.set(true)
    }

    fun holdNextSample(exact: PgLifecycleRestorationAttempt) {
        check(lifecycleField(exact.control.caller, "caller") === this && witness.compareAndSet(null, exact))
        LockSupport.unpark(this) // Scheduling only; no interruption, state publication or admission retry.
        awaitLifecycleFact {
            throwProblem()
            sample.entered()
        }
        requireSampleHeld()
    }

    fun requireSampleHeld() {
        throwProblem()
        check(isAlive && !requestReturned.get() && !bodyEnded.get())
        check(sample.held() && sampleOutsideLocks.get() && !restoration.entered())
        check(injections.get() == 0 && restores.get() == 0 && !super.isInterrupted())
    }

    fun injectActualFlagAndReleaseSample() {
        requireSampleHeld()
        check(injections.compareAndSet(0, 1))
        try {
            super.interrupt() // Non-overriding injection into this exact live caller, exactly once.
            check(super.isInterrupted())
        } finally {
            sample.release() // Never deliberately leave an interrupted sample gate parked/spinning.
        }
    }

    fun awaitRestorationHeld() {
        awaitLifecycleFact {
            throwProblem()
            restoration.entered()
        }
        requireRestorationHeld()
    }

    fun requireRestorationHeld() {
        throwProblem()
        check(isAlive && !requestReturned.get() && !bodyEnded.get() && !restoreReturned.get())
        check(sample.exited() && restoration.held() && restorationOutsideLocks.get())
        check(injections.get() == 1 && restores.get() == 1 && !super.isInterrupted())
    }

    fun releaseRestorationAndRequireResult(expected: PersistenceFactoryResult.Failed) {
        requireRestorationHeld()
        restoration.release()
        awaitLifecycleFact { !isAlive }
        throwProblem()
        val returned = result.get()
        check(returned === expected)
        check(returned.reason === PersistenceFactoryFailure.INTERRUPTED && returned.receipt === requireNotNull(witness.get()).receipt)
        check(requestReturned.get() && bodyEnded.get() && restoration.exited() && restoreReturned.get() && returnedFlag.get())
        check(injections.get() == 1 && restores.get() == 1 && state === Thread.State.TERMINATED)
    }

    fun releaseGates() {
        sample.release()
        restoration.release()
    }

    private fun throwProblem() {
        problem.get()?.let { throw it }
    }

    override fun close() {
        closing.set(true)
        releaseGates()
        awaitLifecycleFact { !isAlive }
        check(if (bodyEntered.get()) bodyEnded.get() && state === Thread.State.TERMINATED else state === Thread.State.NEW)
        println("PG_LIFECYCLE_RESTORATION_CALLER_CLEANUP started=${bodyEntered.get()} all_terminated=true forced=false")
        throwProblem()
    }
}

/** Preowned fixture-only gate. It never consumes or fabricates a Thread interrupt flag. */
private class PgLifecycleRestorationGate {
    private val reached = CountDownLatch(1)
    private val released = CountDownLatch(1)
    private val ended = AtomicBoolean()

    fun hold() {
        reached.countDown()
        val started = System.nanoTime()
        try {
            while (released.count != 0L) {
                check(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(30)) { "Restoration fixture gate was not released." }
                LockSupport.parkNanos(1_000_000)
            }
        } finally {
            ended.set(true)
        }
    }

    fun entered(): Boolean = reached.count == 0L

    fun held(): Boolean = entered() && released.count != 0L && !ended.get()

    fun exited(): Boolean = ended.get()

    fun release() = released.countDown()
}
