package me.manga.kira.backend.common.infrastructure.persistence

import java.util.Timer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Intentionally unfenced GENERIC producer, not the managed record's producer or pgjdbc private task implementation. */
internal class PgLifecycleRecordBoundaryProducer(private val timer: Timer) : AutoCloseable {
    val laterTask = PgLifecycleRecordBoundaryTask("unfenced-later-running", held = true)
    private val permit = CountDownLatch(1)
    private val scheduleEnded = CountDownLatch(1)
    private val started = AtomicBoolean()
    private val closing = AtomicBoolean()
    private val earlier = AtomicReference<PersistenceTimerBoundary?>()
    private val ackBeforeEnqueue = AtomicBoolean()
    private val problem = AtomicReference<Throwable?>()
    private val producer = Thread.ofPlatform().name("record-boundary-unfenced-producer").inheritInheritableThreadLocals(false).unstarted(::run)

    fun start() {
        check(started.compareAndSet(false, true))
        producer.start()
    }

    fun permitAfter(acknowledged: PersistenceTimerBoundary) {
        check(acknowledged.acknowledged() && earlier.compareAndSet(null, acknowledged))
        permit.countDown()
    }

    fun awaitLaterRunning(expectedThread: Thread) {
        check(scheduleEnded.await(5, TimeUnit.SECONDS))
        problem.get()?.let { throw it }
        laterTask.awaitEntry()
        check(ackBeforeEnqueue.get() && earlier.get()?.acknowledged() == true)
        check(laterTask.isRunning() && laterTask.actualThread() === expectedThread)
        pgLifecycleTlsJoin(listOf(producer))
        println(
            "PG_LIFECYCLE_RECORD_BOUNDARY_UNFENCED_PRODUCER earlier_real_ack=true later_enqueue=true later_running=true " +
                "proof=GENERIC_REAL_JDK_NOT_PGJDBC_TAIL",
        )
    }

    private fun run() {
        try {
            runCatching {
                check(permit.await(25, TimeUnit.SECONDS))
                if (closing.get()) return
                check(requireNotNull(earlier.get()).acknowledged())
                ackBeforeEnqueue.set(true)
                laterTask.schedule(timer)
            }.onFailure { problem.compareAndSet(null, it) }
        } finally {
            scheduleEnded.countDown()
        }
    }

    fun releaseGates() {
        closing.set(true)
        permit.countDown()
        laterTask.release()
    }

    override fun close() {
        releaseGates()
        val producerEnd = runCatching { pgLifecycleTlsJoin(listOf(producer)) }
        val taskEnd = runCatching { laterTask.awaitBodyEnded() }
        producerEnd.getOrThrow()
        taskEnd.getOrThrow()
        problem.get()?.let { throw it }
        check(!producer.isAlive)
        println("PG_LIFECYCLE_RECORD_BOUNDARY_PRODUCER_CLEANUP producer_joined=true scheduled=${laterTask.wasScheduled()} task_ended_or_unstarted=true")
    }
}
