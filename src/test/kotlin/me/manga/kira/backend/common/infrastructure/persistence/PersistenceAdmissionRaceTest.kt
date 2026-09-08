package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.opentest4j.AssertionFailedError
import java.util.Collections
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RunnableFuture
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class PersistenceAdmissionRaceTest {
    @Test
    fun `successful fixture joins the actual dedicated workers before returning values`() = withIsolatedInterruptState {
        val executor = ObservedAdmissionExecutor()
        try {
            assertEquals(List(8) { 42 }, racePersistenceAdmissions(executor) { 42 })
            executor.assertWorkersEnded()
            assertFalse(Thread.currentThread().isInterrupted)
        } finally {
            stopPersistenceAdmissionWorkers(executor)
        }
    }

    @Test
    fun `exceptional action preserves its failure and joins the actual dedicated workers`() = withIsolatedInterruptState {
        val executor = ObservedAdmissionExecutor()
        val primary = IllegalStateException("Synthetic admission action failure")
        try {
            val failure = assertThrows(ExecutionException::class.java) { racePersistenceAdmissions(executor) { throw primary } }
            assertSame(primary, failure.cause)
            assertTrue(failure.suppressed.isEmpty())
            executor.assertWorkersEnded()
            assertFalse(Thread.currentThread().isInterrupted)
        } finally {
            stopPersistenceAdmissionWorkers(executor)
        }
    }

    @Test
    fun `already interrupted caller regains its flag after owned workers are joined`() = withIsolatedInterruptState {
        val executor = ObservedAdmissionExecutor()
        try {
            Thread.currentThread().interrupt()
            assertThrows(InterruptedException::class.java) { racePersistenceAdmissions(executor) { 42 } }
            assertTrue(Thread.currentThread().isInterrupted)
            executor.assertWorkersEnded()
        } finally {
            stopPersistenceAdmissionWorkers(executor)
        }
    }

    @Test
    fun `interrupting the caller during result collection aborts delivery but still joins workers`() = withIsolatedInterruptState {
        val executor = ObservedAdmissionExecutor()
        val caller = Thread.currentThread()
        val actionGate = CountDownLatch(1)
        val interrupter = Executors.newSingleThreadExecutor()
        try {
            val control = interrupter.submit {
                assertTrue(executor.resultCollection.await(5, TimeUnit.SECONDS))
                caller.interrupt()
            }
            assertThrows(InterruptedException::class.java) {
                racePersistenceAdmissions(executor) {
                    assertTrue(actionGate.await(5, TimeUnit.SECONDS))
                    42
                }
            }
            assertTrue(caller.isInterrupted)
            executor.assertWorkersEnded()
            withIsolatedInterruptState { control.get(5, TimeUnit.SECONDS) }
            assertTrue(caller.isInterrupted)
        } finally {
            actionGate.countDown()
            try {
                stopPersistenceAdmissionWorkers(executor)
            } finally {
                stopPersistenceAdmissionWorkers(interrupter)
            }
        }
    }

    @Test
    fun `two cleanup interruptions retain the original failure and wait for actual pending worker exits`() = withIsolatedInterruptState {
        val exitGate = CountDownLatch(1)
        val executor = ObservedAdmissionExecutor(exitGate = exitGate)
        val caller = Thread.currentThread()
        val primary = IllegalStateException("Synthetic action failure before cleanup")
        val interrupter = Executors.newSingleThreadExecutor()
        try {
            val control = interrupter.submit {
                // Workers have completed their FutureTasks, but the real executor cannot terminate yet.
                assertTrue(executor.pendingWorkerExits.await(5, TimeUnit.SECONDS))
                for (index in 0..1) {
                    assertTrue(executor.cleanupWaits[index].await(5, TimeUnit.SECONDS))
                    caller.interrupt()
                    assertTrue(executor.cleanupInterrupts[index].await(5, TimeUnit.SECONDS))
                }
                exitGate.countDown()
            }
            val failure = assertThrows(ExecutionException::class.java) { racePersistenceAdmissions(executor) { throw primary } }
            assertSame(primary, failure.cause)
            assertTrue(failure.suppressed.isEmpty())
            assertTrue(caller.isInterrupted)
            assertEquals(3, executor.cleanupCalls.get())
            executor.assertWorkersEnded()
            withIsolatedInterruptState { control.get(5, TimeUnit.SECONDS) }
            assertTrue(caller.isInterrupted)
        } finally {
            exitGate.countDown()
            try {
                stopPersistenceAdmissionWorkers(executor)
            } finally {
                stopPersistenceAdmissionWorkers(interrupter)
            }
        }
    }

    @Test
    fun `a reported cleanup failure is visible without replacing the original action failure`() = withIsolatedInterruptState {
        val executor = ObservedAdmissionExecutor(reportFirstCleanupFailure = true)
        val primary = IllegalStateException("Synthetic primary failure")
        try {
            val failure = assertThrows(ExecutionException::class.java) { racePersistenceAdmissions(executor) { throw primary } }
            assertSame(primary, failure.cause)
            assertEquals(1, failure.suppressed.size)
            assertTrue(failure.suppressed.single() is AssertionFailedError)
            // This fixture injects a failed observation after a genuine join; it owns no leaked thread.
            executor.assertWorkersEnded()
        } finally {
            stopPersistenceAdmissionWorkers(executor)
        }
    }

    @Test
    fun `repeated interrupted joins cannot restart the cleanup budget or report false termination`() = withIsolatedInterruptState {
        val executor = NonterminatingClockExecutor()
        val failure = assertThrows(AssertionFailedError::class.java) {
            stopPersistenceAdmissionWorkers(executor) { executor.elapsedNanos }
        }
        assertTrue(failure.message.orEmpty().contains("five-second cleanup allowance"))
        assertEquals(listOf(5_000_000_000L, 2_000_000_000L), executor.awaitedNanos)
        assertEquals(1, executor.shutdownCalls)
        assertTrue(Thread.currentThread().isInterrupted)
        assertFalse(executor.isTerminated)
        // No actual workers exist in this labelled clock/contract fault-injection fixture.
    }
}

/** Changes only this test thread's interrupt state, including when an assertion fails. */
private fun withIsolatedInterruptState(action: () -> Unit) {
    val interruptedOnEntry = Thread.interrupted()
    try {
        action()
    } finally {
        Thread.interrupted()
        if (interruptedOnEntry) Thread.currentThread().interrupt()
    }
}

/** Real eight-thread executor; optional gates expose lifecycle boundaries without sleeps. */
private class ObservedAdmissionExecutor(
    val threads: MutableList<Thread> = Collections.synchronizedList(mutableListOf()),
    private val exitGate: CountDownLatch? = null,
    private val reportFirstCleanupFailure: Boolean = false,
) : ThreadPoolExecutor(
    8,
    8,
    0,
    TimeUnit.MILLISECONDS,
    LinkedBlockingQueue(),
    { task -> Thread(task, "kira-w03-admission-harness-test").also { threads.add(it) } },
) {
    val resultCollection = CountDownLatch(1)
    val pendingWorkerExits = CountDownLatch(8)
    val cleanupWaits = List(2) { CountDownLatch(1) }
    val cleanupInterrupts = List(2) { CountDownLatch(1) }
    val cleanupCalls = AtomicInteger()
    private val workerExitFailure = AtomicReference<Throwable?>()

    override fun <T> newTaskFor(callable: Callable<T>): RunnableFuture<T> = object : FutureTask<T>(callable) {
        override fun get(timeout: Long, unit: TimeUnit): T {
            resultCollection.countDown()
            return super.get(timeout, unit)
        }
    }

    override fun afterExecute(task: Runnable, failure: Throwable?) {
        super.afterExecute(task, failure)
        if (exitGate == null) return
        pendingWorkerExits.countDown()
        val started = System.nanoTime()
        var interruption: InterruptedException? = null
        try {
            while (exitGate.count != 0L) {
                val remaining = TimeUnit.SECONDS.toNanos(5) - (System.nanoTime() - started)
                assertTrue(remaining > 0, "Synthetic worker-exit gate must be released.")
                try {
                    assertTrue(exitGate.await(remaining, TimeUnit.NANOSECONDS), "Synthetic worker-exit gate timed out.")
                } catch (ex: InterruptedException) {
                    interruption = ex
                }
            }
        } catch (ex: Throwable) {
            workerExitFailure.compareAndSet(null, ex)
        } finally {
            if (interruption != null) Thread.currentThread().interrupt()
        }
    }

    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean {
        val index = cleanupCalls.getAndIncrement()
        cleanupWaits.getOrNull(index)?.countDown()
        try {
            val terminated = super.awaitTermination(timeout, unit)
            return terminated && !(reportFirstCleanupFailure && index == 0)
        } catch (ex: InterruptedException) {
            cleanupInterrupts.getOrNull(index)?.countDown()
            throw ex
        }
    }

    fun assertWorkersEnded() {
        withIsolatedInterruptState {
            assertTrue(isTerminated)
            assertEquals(8, threads.size)
            // Executor termination acknowledges task completion; also join the actual owned threads.
            val started = System.nanoTime()
            for (thread in threads) {
                val remaining = TimeUnit.SECONDS.toNanos(5) - (System.nanoTime() - started)
                assertTrue(remaining > 0, "Actual fixture worker joins must stay bounded.")
                TimeUnit.NANOSECONDS.timedJoin(thread, remaining)
                assertFalse(thread.isAlive, "Actual fixture workers must have exited.")
            }
            workerExitFailure.get()?.let { throw AssertionError("Synthetic worker-exit control failed", it) }
        }
    }
}

/** Pure fault injection: no submitted task, executor thread or real delayed termination. */
private class NonterminatingClockExecutor : AbstractExecutorService() {
    var elapsedNanos = 0L
    var shutdownCalls = 0
    val awaitedNanos = mutableListOf<Long>()

    override fun shutdownNow(): MutableList<Runnable> {
        shutdownCalls++
        return mutableListOf()
    }

    override fun shutdown() = Unit

    override fun isShutdown(): Boolean = shutdownCalls > 0

    override fun isTerminated(): Boolean = false

    override fun execute(command: Runnable): Unit = error("Fault-injection executor must never receive work")

    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean {
        awaitedNanos += unit.toNanos(timeout)
        elapsedNanos += 3_000_000_000L
        Thread.currentThread().interrupt()
        Thread.interrupted() // The interruptible JDK await contract clears status when it throws.
        throw InterruptedException("Synthetic cleanup-wait interruption")
    }
}
