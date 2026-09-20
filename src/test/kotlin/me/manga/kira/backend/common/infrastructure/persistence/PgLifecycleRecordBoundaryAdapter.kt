package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Supplemental ADAPTER/MODEL authorization and one-shot control. No record/work uses this Boundary as disposal evidence. */
internal class PgLifecycleRecordBoundaryAdapter(root: PersistenceJdbcDriverRoot, timer: PgLifecycleRecordBoundaryTimer) : AutoCloseable {
    private val reached = CountDownLatch(1)
    private val permit = CountDownLatch(1)
    private val callsEnded = CountDownLatch(1)
    private val finish = CountDownLatch(1)
    private val closing = AtomicBoolean()
    private val problem = AtomicReference<Throwable?>()
    private val first = AtomicReference<PersistenceTimerAction?>()
    private val second = AtomicReference<PersistenceTimerAction?>()
    private val retryInsideBody = AtomicBoolean()
    private val sameReceipt = AtomicBoolean()
    val runner = PersistenceRetainedPlatformThread("record-boundary-adapter-runner", ::run)
    val access = PgLifecycleRecordBoundaryAccess(requireNotNull(root.timer.newBoundary(runner)), root, timer, runner)

    fun startHeld() {
        check(runner.start() === PersistenceFactoryStart.STARTED)
        check(reached.await(5, TimeUnit.SECONDS))
        check(runner.hasEntered() && !runner.hasBodyEnded() && runner.thread.isAlive)
    }

    fun exerciseWrongCallerThenOneShot() {
        access.requireVirgin()
        access.wrongCallerSchedule() // Runner is already entered/alive; no claimed/not-entered explanation.
        access.requireVirgin()
        permit.countDown()
        check(callsEnded.await(5, TimeUnit.SECONDS))
        problem.get()?.let { throw it }
        check(first.get() === PersistenceTimerAction.SUCCEEDED && second.get() === PersistenceTimerAction.REFUSED)
        check(retryInsideBody.get() && sameReceipt.get() && !runner.hasBodyEnded())
        access.requireGenuineScheduledPending()
    }

    private fun run() {
        try {
            runCatching {
                reached.countDown()
                check(permit.await(25, TimeUnit.SECONDS))
                if (closing.get()) return
                first.set(access.boundary.schedule())
                val original = access.state()
                retryInsideBody.set(Thread.currentThread() === runner.thread && runner.hasEntered() && !runner.hasBodyEnded())
                second.set(access.boundary.schedule())
                sameReceipt.set(access.state() == original)
                callsEnded.countDown()
                check(finish.await(25, TimeUnit.SECONDS))
            }.onFailure { problem.compareAndSet(null, it) }
        } finally {
            callsEnded.countDown()
        }
    }

    fun releaseGates() {
        closing.set(true)
        permit.countDown()
        finish.countDown()
        runner.forbidStart()
    }

    override fun close() {
        releaseGates()
        val actorEnd = runCatching { pgLifecycleTlsJoin(listOf(runner.thread)) }
        val scheduledWork = runCatching {
            if (!access.claimed.get()) {
                access.requireVirgin()
            } else {
                check(access.entered.get() && access.extentEnded.get() && access.outcome.get() === PersistenceTimerOutcome.RETURNED)
                awaitLifecycleFact { access.cellAcknowledged() && access.boundary.acknowledged() }
            }
        }
        actorEnd.getOrThrow()
        scheduledWork.getOrThrow()
        problem.get()?.let { throw it }
        check(runner.termination().ended() && !runner.thread.isAlive)
        println(
            "PG_LIFECYCLE_RECORD_BOUNDARY_ADAPTER_CLEANUP start=${runner.startPhase()} runner_ended=true " +
                "scheduled_ack_or_uninvoked=true proof=ADAPTER_NOT_RECORD_DISPOSAL",
        )
    }
}
