package me.manga.kira.backend.common.infrastructure.persistence

import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Read-only own-project identity access; only the separately named receipt MODEL may write two Call receipts. */
internal class PgLifecycleRecordBoundaryAccess(
    val boundary: PersistenceTimerBoundary,
    root: PersistenceJdbcDriverRoot,
    fixture: PgLifecycleRecordBoundaryTimer,
    val runner: PersistenceRetainedPlatformThread,
) {
    val call = requireNotNull(lifecycleField(boundary, "scheduling"))
    val cell = requireNotNull(lifecycleField(boundary, "cell"))
    val task = lifecycleField(boundary, "task") as TimerTask
    val timer = lifecycleField(boundary, "timer") as Timer
    val expectedThread = lifecycleField(cell, "expectedThread") as Thread
    val claimed = lifecycleField(call, "claimed") as AtomicBoolean
    val entered = lifecycleField(call, "entered") as AtomicBoolean
    val extentEnded = lifecycleField(call, "extentEnded") as AtomicBoolean

    @Suppress("UNCHECKED_CAST")
    val outcome = lifecycleField(call, "outcome") as AtomicReference<PersistenceTimerOutcome>
    private val acknowledgement = lifecycleField(cell, "acknowledged") as AtomicBoolean

    init {
        check(boundary.javaClass.enclosingClass === PersistencePgTimerAccess::class.java && boundary.javaClass.simpleName == "Boundary")
        check(task.javaClass.enclosingClass === PersistencePgTimerAccess::class.java && task.javaClass.simpleName == "BoundaryTask")
        check(cell.javaClass.enclosingClass === PersistencePgTimerAccess::class.java && cell.javaClass.simpleName == "BoundaryCell")
        check(call.javaClass.enclosingClass === PersistencePgTimerAccess::class.java && call.javaClass.simpleName == "Call")
        check(lifecycleField(task, "cell") === cell && lifecycleField(boundary, "runner") === runner)
        val calls = requireNotNull((lifecycleField(root.timer, "calls") as AtomicReference<*>).get())
        val capture = requireNotNull(lifecycleField(calls, "capture"))
        check((lifecycleField(calls, "rawTimer") as AtomicReference<*>).get() === timer)
        check((lifecycleField(capture, "identity") as AtomicReference<*>).get() === expectedThread)
        check(timer === fixture.timer() && expectedThread === fixture.actualThread())
    }

    fun state(): PgLifecycleRecordBoundaryCallState = PgLifecycleRecordBoundaryCallState(claimed.get(), boundary.observation())

    fun cellAcknowledged(): Boolean = acknowledgement.get()

    fun requireVirgin() {
        check(!claimed.get() && !entered.get() && outcome.get() === PersistenceTimerOutcome.NOT_RETURNED && !extentEnded.get())
        check(!cellAcknowledged() && !boundary.acknowledged())
    }

    fun requireGenuineScheduledPending() {
        check(claimed.get() && entered.get() && extentEnded.get() && outcome.get() === PersistenceTimerOutcome.RETURNED)
        check(!cellAcknowledged() && !boundary.acknowledged() && expectedThread.isAlive)
    }

    fun wrongCallerSchedule() {
        check(Thread.currentThread() !== runner.thread && runner.hasEntered() && !runner.hasBodyEnded() && runner.thread.isAlive)
        val before = state()
        check(boundary.schedule() === PersistenceTimerAction.REFUSED)
        check(state() == before && !boundary.acknowledged())
    }

    /** MODEL access to our detached task only; this does not invoke a pgjdbc private task or write ACK. */
    fun wrongThreadTask() {
        check(Thread.currentThread() !== expectedThread && !cellAcknowledged())
        val before = state()
        task.run()
        check(state() == before && !cellAcknowledged() && !boundary.acknowledged())
    }
}

internal data class PgLifecycleRecordBoundaryCallState(val claimed: Boolean, val observation: PersistenceTimerBoundaryObservation)
