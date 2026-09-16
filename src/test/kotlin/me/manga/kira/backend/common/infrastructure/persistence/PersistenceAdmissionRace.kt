package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Bounded real-thread start barrier; every task is joined before an admitted fixture permit is released. */
internal fun <T> racePersistenceAdmissions(action: () -> T): List<T> {
    val sequence = AtomicInteger()
    val executor = Executors.newFixedThreadPool(8) { task -> Thread(task, "kira-w03-admission-test-${sequence.incrementAndGet()}") }
    return racePersistenceAdmissions(executor, action)
}

/** Ownership of this dedicated test executor transfers to this invocation, including on failure. */
internal fun <T> racePersistenceAdmissions(executor: ExecutorService, action: () -> T): List<T> {
    val ready = CountDownLatch(8)
    val start = CountDownLatch(1)
    return AutoCloseable {
        start.countDown()
        stopPersistenceAdmissionWorkers(executor)
    }.use {
        try {
            val results = (1..8).map {
                executor.submit<T> {
                    ready.countDown()
                    assertTrue(start.await(5, TimeUnit.SECONDS), "Admission test start barrier must be released.")
                    action()
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS), "All admission workers must reach the start barrier.")
            start.countDown()
            results.map { it.get(5, TimeUnit.SECONDS) }
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw failure
        }
    }
}

/** Interruption stops work, not the obligation to observe termination within the original allowance. */
internal fun stopPersistenceAdmissionWorkers(executor: ExecutorService, nanoTime: () -> Long = System::nanoTime) {
    val interruptedOnEntry = Thread.interrupted()
    var cleanupInterruption: InterruptedException? = null
    try {
        val started = nanoTime()
        val allowance = TimeUnit.SECONDS.toNanos(5)
        executor.shutdownNow()
        var terminated = false
        while (!terminated) {
            val elapsed = nanoTime() - started
            if (elapsed < 0 || elapsed >= allowance) break
            try {
                terminated = executor.awaitTermination(allowance - elapsed, TimeUnit.NANOSECONDS)
                // False is an unsuccessful observation, not permission to restart a five-second wait.
                if (!terminated) break
            } catch (failure: InterruptedException) {
                cleanupInterruption = failure
            }
        }
        assertTrue(terminated, "Owned admission workers did not terminate within the five-second cleanup allowance.")
    } finally {
        if (interruptedOnEntry || cleanupInterruption != null) Thread.currentThread().interrupt()
    }
}
