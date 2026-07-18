package me.manga.kira.backend.completion

import me.manga.kira.backend.completion.application.BoundedCompletionExecutor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

class BoundedCompletionExecutorTest {
    @Test
    fun `work beyond active threads and queue capacity is rejected`() {
        val executor =
            BoundedCompletionExecutor(
                threads = 1,
                queueCapacity = 1,
                threadFactory = { runnable -> Thread(runnable, "bounded-completion-test").apply { isDaemon = true } },
            )
        val workerStarted = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)

        try {
            val active =
                executor.submit(
                    Callable {
                        workerStarted.countDown()
                        releaseWorker.await()
                        "active"
                    },
                )
            assertTrue(workerStarted.await(2, TimeUnit.SECONDS))
            val queued = executor.submit(Callable { "queued" })

            assertThrows(RejectedExecutionException::class.java) {
                executor.submit(Callable { "must-reject" })
            }

            releaseWorker.countDown()
            assertEquals("active", active.get(2, TimeUnit.SECONDS))
            assertEquals("queued", queued.get(2, TimeUnit.SECONDS))
        } finally {
            releaseWorker.countDown()
            executor.shutdownNow()
        }
    }
}
