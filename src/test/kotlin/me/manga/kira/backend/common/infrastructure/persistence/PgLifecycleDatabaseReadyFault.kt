package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

internal enum class PgLifecycleDatabaseReadyKind {
    COMPLETE,
    TRUNCATED,
    IDLE,
    PROGRESS,
    LATE,
}

/** One first-Ready fault, six bounded byte timestamps, and one exact parent-only final-byte permit. No protocol payload history. */
internal class PgLifecycleDatabaseReadyFault(private val case: PgLifecycleDatabaseCase) {
    val kind = when {
        case.lateReturn -> PgLifecycleDatabaseReadyKind.LATE
        case.mode === PgLifecycleDatabaseMode.PROGRESS_DEADLINE -> PgLifecycleDatabaseReadyKind.PROGRESS
        case.mode === PgLifecycleDatabaseMode.TRUNCATED_STARTUP -> PgLifecycleDatabaseReadyKind.TRUNCATED
        case.mode === PgLifecycleDatabaseMode.SOCKET_TIMEOUT -> PgLifecycleDatabaseReadyKind.IDLE
        else -> PgLifecycleDatabaseReadyKind.COMPLETE
    }
    val armed = AtomicBoolean()
    val deliveryStarted = AtomicBoolean()
    val bytesWritten = AtomicInteger()
    val deadlineConfirmed = AtomicBoolean()
    val finalReleased = AtomicBoolean()
    val outputHalfCloseEntered = AtomicBoolean()
    val outputHalfCloseEnded = AtomicBoolean()
    private val boundOrdinal = AtomicInteger(-1)
    private val priorServerWrite = AtomicLong()
    private val writes = AtomicLongArray(6)
    private val finalPermit = CountDownLatch(1)
    private val ended = CountDownLatch(1)

    val ordinal: Int get() = boundOrdinal.get()
    val intervalMillis: Long get() = if (case.lane.deleting) 350 else 1_250

    fun bind(ordinal: Int) {
        check(ordinal in 0 until case.attempts && boundOrdinal.compareAndSet(-1, ordinal))
    }

    fun observeReady(previousWriteNanos: Long) {
        check(ordinal >= 0 && previousWriteNanos != 0L && priorServerWrite.compareAndSet(0, previousWriteNanos))
    }

    fun arm(identity: PgLifecycleDatabaseCase, ordinal: Int) {
        check(identity == case && ordinal == this.ordinal && case.supplemental)
        check(priorServerWrite.get() != 0L && !deliveryStarted.get() && ended.count != 0L)
        check(armed.compareAndSet(false, true))
    }

    fun beginDelivery() {
        check(priorServerWrite.get() != 0L && (!case.supplemental || armed.get()))
        check(ended.count != 0L && deliveryStarted.compareAndSet(false, true))
    }

    /** Called only after the concrete output write/flush returned. The single server pump is the only writer. */
    fun wrote(offset: Int, count: Int) {
        check(deliveryStarted.get() && offset == bytesWritten.get() && count > 0 && offset + count <= 6)
        if (offset + count == 6 && kind === PgLifecycleDatabaseReadyKind.LATE) check(finalReleased.get() && deadlineConfirmed.get())
        if (kind === PgLifecycleDatabaseReadyKind.PROGRESS) check(offset + count <= 5)
        if (kind === PgLifecycleDatabaseReadyKind.TRUNCATED) check(offset + count <= 3)
        check(kind !== PgLifecycleDatabaseReadyKind.IDLE)
        val now = System.nanoTime()
        repeat(count) { writes.set(offset + it, now) }
        bytesWritten.set(offset + count)
    }

    fun confirmDeadline(identity: PgLifecycleDatabaseCase, ordinal: Int) {
        check(identity == case && ordinal == this.ordinal && case.deadlineFailure)
        check(armed.get() && deliveryStarted.get() && bytesWritten.get() in 1..5 && ended.count != 0L)
        check(deadlineConfirmed.compareAndSet(false, true))
    }

    fun releaseFinal(identity: PgLifecycleDatabaseCase, ordinal: Int) {
        check(identity == case && ordinal == this.ordinal && kind === PgLifecycleDatabaseReadyKind.LATE)
        check(deadlineConfirmed.get() && bytesWritten.get() == 5 && ended.count != 0L)
        check(finalReleased.compareAndSet(false, true))
        finalPermit.countDown()
    }

    fun awaitFinal(): Boolean {
        check(kind === PgLifecycleDatabaseReadyKind.LATE && bytesWritten.get() == 5)
        check(finalPermit.await(4, TimeUnit.SECONDS)) { "Late Ready byte was not released within its fixture bound." }
        return ended.count != 0L && finalReleased.get()
    }

    fun pauseBeforeNextByte(): Boolean = !ended.await(intervalMillis, TimeUnit.MILLISECONDS)

    fun awaitClientEnd(millis: Long): Boolean = ended.await(millis, TimeUnit.MILLISECONDS)

    fun stop() {
        ended.countDown()
        finalPermit.countDown() // Failure cleanup wakes the actor, but never grants the parent-only permit.
    }

    fun requireEvidence(clientEndNanos: Long) {
        check(deliveryStarted.get() && (!case.supplemental || armed.get()))
        if (kind !== PgLifecycleDatabaseReadyKind.COMPLETE) check(clientEndNanos != 0L)
        if (kind !== PgLifecycleDatabaseReadyKind.TRUNCATED) check(!outputHalfCloseEntered.get() && !outputHalfCloseEnded.get())
        val bytes = bytesWritten.get()
        when (kind) {
            PgLifecycleDatabaseReadyKind.COMPLETE -> check(bytes == 6 && !deadlineConfirmed.get() && !finalReleased.get())

            PgLifecycleDatabaseReadyKind.TRUNCATED -> {
                check(bytes == 3 && outputHalfCloseEntered.get() && outputHalfCloseEnded.get())
                check(!deadlineConfirmed.get() && !finalReleased.get())
            }

            PgLifecycleDatabaseReadyKind.IDLE -> {
                check(bytes == 0 && !deadlineConfirmed.get() && !finalReleased.get())
                val idleMillis = TimeUnit.NANOSECONDS.toMillis(clientEndNanos - priorServerWrite.get())
                check(idleMillis >= 900 && idleMillis < case.lane.allowanceMillis)
            }

            PgLifecycleDatabaseReadyKind.PROGRESS -> {
                check(bytes in 1..5 && deadlineConfirmed.get() && !finalReleased.get())
                requireProgressGap(clientEndNanos)
            }

            PgLifecycleDatabaseReadyKind.LATE -> {
                check(bytes == 6 && deadlineConfirmed.get() && finalReleased.get())
                requireProgressGap(writes.get(5))
            }
        }
    }

    fun summary(clientEndNanos: Long): String {
        val bytes = bytesWritten.get()
        val last = if (bytes == 0) priorServerWrite.get() else writes.get(bytes - 1)
        val end = if (kind === PgLifecycleDatabaseReadyKind.LATE) last else clientEndNanos
        return "kind=$kind ready_bytes=$bytes max_gap_ms=${TimeUnit.NANOSECONDS.toMillis(maxGap(end))} " +
            "last_write_to_client_end_ms=${TimeUnit.NANOSECONDS.toMillis(clientEndNanos - last)} " +
            "output_half_close=${outputHalfCloseEnded.get()} deadline_confirmed=${deadlineConfirmed.get()} final_released=${finalReleased.get()}"
    }

    private fun requireProgressGap(endNanos: Long) {
        val gap = maxGap(endNanos)
        check(gap > 0 && gap < TimeUnit.SECONDS.toNanos(case.socketTimeout.toLong())) {
            "Real Ready progress did not stay inside the configured socket-read timeout."
        }
    }

    private fun maxGap(endNanos: Long): Long {
        var last = priorServerWrite.get()
        var maximum = 0L
        repeat(bytesWritten.get()) { index ->
            val now = writes.get(index)
            check(now - last >= 0)
            maximum = maxOf(maximum, now - last)
            last = now
        }
        check(endNanos - last >= 0)
        return maxOf(maximum, endNanos - last)
    }
}
