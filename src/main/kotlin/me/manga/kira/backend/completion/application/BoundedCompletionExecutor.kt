package me.manga.kira.backend.completion.application

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Fixed provider workers plus a hard queue bound; saturation rejects instead of growing memory. */
class BoundedCompletionExecutor(threads: Int, queueCapacity: Int, threadFactory: ThreadFactory) {
    private val delegate =
        ThreadPoolExecutor(
            threads,
            threads,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(queueCapacity),
            threadFactory,
            ThreadPoolExecutor.AbortPolicy(),
        )

    fun <T> submit(task: Callable<T>): Future<T> = delegate.submit(task)

    fun shutdownNow() {
        delegate.shutdownNow()
    }

    fun activeCount(): Int = delegate.activeCount

    fun queueSize(): Int = delegate.queue.size

    fun remainingQueueCapacity(): Int = delegate.queue.remainingCapacity()
}
