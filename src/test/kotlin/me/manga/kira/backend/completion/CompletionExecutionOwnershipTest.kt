package me.manga.kira.backend.completion

import me.manga.kira.backend.completion.application.BoundedCompletionExecutor
import me.manga.kira.backend.completion.application.CompletionActivation
import me.manga.kira.backend.completion.application.CompletionExecutionOwnership
import me.manga.kira.backend.completion.application.CompletionPermit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@Timeout(10)
class CompletionExecutionOwnershipTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `only the second of caller close and actual body exit releases`(callerFirst: Boolean) {
        val permit = RecordingPermit()
        val owner = CompletionExecutionOwnership(permit)
        assertTrue(owner.tryEnter())
        assertFalse(owner.tryEnter())
        assertEquals(CompletionActivation.ACTIVATED, owner.activate())
        assertEquals(1, permit.activations.get())

        if (callerFirst) owner.close() else owner.workerExited()
        assertEquals(0, permit.closes.get())
        if (callerFirst) owner.workerExited() else owner.close()
        owner.close()
        owner.workerExited()
        assertEquals(1, permit.closes.get())
        assertFalse(owner.tryEnter())
        assertThrows<IllegalStateException> { owner.activate() }
    }

    @Test
    fun `caller close before entry prevents late work and activation`() {
        val permit = RecordingPermit()
        val owner = CompletionExecutionOwnership(permit)
        owner.close()
        owner.close()
        assertFalse(owner.tryEnter())
        assertThrows<IllegalStateException> { owner.activate() }
        assertEquals(0, permit.activations.get())
        assertEquals(1, permit.closes.get())
    }

    @Test
    fun `close racing actual entry never releases an entered body`() {
        val permit = RecordingPermit()
        val owner = CompletionExecutionOwnership(permit)
        val start = CountDownLatch(1)
        val entryDecided = CountDownLatch(1)
        val finishBody = CountDownLatch(1)
        val entered = AtomicBoolean()
        val failure = AtomicReference<Throwable?>()
        val body = Thread {
            try {
                start.await()
                entered.set(owner.tryEnter())
                entryDecided.countDown()
                if (entered.get()) {
                    finishBody.await()
                    owner.workerExited()
                }
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        val caller = Thread {
            start.await()
            owner.close()
        }
        try {
            body.start()
            caller.start()
            start.countDown()
            assertTrue(entryDecided.await(2, TimeUnit.SECONDS))
            caller.join(2_000)
            assertFalse(caller.isAlive)
            assertEquals(if (entered.get()) 0 else 1, permit.closes.get())
            finishBody.countDown()
            body.join(2_000)
            assertFalse(body.isAlive)
            assertNull(failure.get())
            assertEquals(1, permit.closes.get())
        } finally {
            start.countDown()
            finishBody.countDown()
            body.join(2_000)
            caller.join(2_000)
            assertFalse(body.isAlive)
            assertFalse(caller.isAlive)
        }
    }

    @Test
    fun `slow raw release does not hold the ownership lock`() {
        val releaseEntered = CountDownLatch(1)
        val releaseExit = CountDownLatch(1)
        val permit = RecordingPermit {
            releaseEntered.countDown()
            releaseExit.await()
        }
        val owner = CompletionExecutionOwnership(permit)
        val firstClose = Thread { owner.close() }
        val secondClose = Thread { owner.close() }
        try {
            firstClose.start()
            assertTrue(releaseEntered.await(2, TimeUnit.SECONDS))
            secondClose.start()
            secondClose.join(2_000)
            assertFalse(secondClose.isAlive, "Repeated close cannot wait for raw release I/O under the state lock")
            assertFalse(owner.tryEnter())
            assertEquals(1, permit.closes.get())
        } finally {
            releaseExit.countDown()
            firstClose.join(2_000)
            secondClose.join(2_000)
            assertFalse(firstClose.isAlive)
            assertFalse(secondClose.isAlive)
        }
    }

    @ParameterizedTest
    @CsvSource("false,false", "true,false", "false,true", "true,true")
    fun `owed cleanup clears only prior interruption and restores flags even on failure`(interruptedBefore: Boolean, throws: Boolean) {
        val sentinel = IllegalStateException("synthetic raw cleanup failure")
        val permit = RecordingPermit {
            assertFalse(Thread.currentThread().isInterrupted)
            if (!interruptedBefore) Thread.currentThread().interrupt() // A new interrupt must survive finally too.
            if (throws) throw sentinel
        }
        val owner = CompletionExecutionOwnership(permit)
        assertTrue(owner.tryEnter())
        owner.close() // Deferred worker-side cleanup must obey the same interrupt rule.
        try {
            if (interruptedBefore) Thread.currentThread().interrupt()
            if (throws) assertSame(sentinel, assertThrows<IllegalStateException> { owner.workerExited() }) else owner.workerExited()
            assertTrue(Thread.currentThread().isInterrupted)
            owner.close()
            assertEquals(1, permit.closes.get())
        } finally {
            Thread.interrupted()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `canceled or shutdown queued bodies never enter and caller close releases once`(cancelQueued: Boolean) {
        val worker = AtomicReference<Thread>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = BoundedCompletionExecutor(1, 1) { task -> Thread(task).also(worker::set) }
        val permit = RecordingPermit()
        val owner = CompletionExecutionOwnership(permit)
        val lateEntry = AtomicBoolean()
        try {
            executor.submit(
                Callable {
                    entered.countDown()
                    release.await()
                },
            )
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val queued = executor.submit(
                Callable {
                    if (owner.tryEnter()) {
                        lateEntry.set(true)
                        owner.workerExited()
                    }
                },
            )
            assertEquals(1, executor.queueSize())
            if (cancelQueued) assertTrue(queued.cancel(false)) else executor.shutdownNow()
            owner.close() // The request owner unwinds; a canceled Future is not a body-exit callback.
            assertEquals(1, permit.closes.get())
            assertFalse(owner.tryEnter())
        } finally {
            release.countDown()
            executor.shutdownNow()
            worker.get()?.join(2_000)
            assertFalse(worker.get()?.isAlive ?: false)
        }
        assertFalse(lateEntry.get())
        assertEquals(1, permit.closes.get())
    }

    @Test
    fun `rejected submission leaves a never-entered owner owing one close`() {
        val executor = BoundedCompletionExecutor(1, 1) { task -> Thread(task) }
        executor.shutdownNow()
        val permit = RecordingPermit()
        CompletionExecutionOwnership(permit).use { owner ->
            assertThrows<RejectedExecutionException> { executor.submit(Callable { owner.tryEnter() }) }
        }
        assertEquals(0, permit.activations.get())
        assertEquals(1, permit.closes.get())
    }

    private class RecordingPermit(private val onClose: () -> Unit = {}) : CompletionPermit {
        val activations = AtomicInteger()
        val closes = AtomicInteger()

        override fun activate(): CompletionActivation = when {
            closes.get() != 0 -> CompletionActivation.EXPIRED
            activations.incrementAndGet() != 1 -> CompletionActivation.UNAVAILABLE
            else -> CompletionActivation.ACTIVATED
        }

        override fun close() {
            closes.incrementAndGet()
            onClose()
        }
    }
}
