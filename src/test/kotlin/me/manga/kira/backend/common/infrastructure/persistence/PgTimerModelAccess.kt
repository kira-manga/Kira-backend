package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Fixed own-project field allowlist. Never reflects into pgjdbc or JDK classes or authenticates a scheduler. */
internal object PgTimerModelAccess {
    fun capturedThread(calls: PersistenceTimerReferenceCalls): Thread? = identity(calls).get() as Thread?

    fun verifyHeldExtent(scope: PgTimerProbeScope) {
        val call = field(scope.calls, "scheduling")
        val ended = field(call, "extentEnded") as AtomicBoolean
        check(ended.getAndSet(false))
        try {
            val observation = scope.calls.observe()
            check(observation.capture.publicationReceived)
            check(observation.capture.status === PersistenceTimerCaptureStatus.PENDING)
            check(observation.capture.termination !== PersistenceThreadTermination.TERMINATED)
            check(scope.call { it.releaseOwnedReference() } === PersistenceTimerAction.REFUSED)
            check(!scope.calls.observe().release.entered)
        } finally {
            ended.set(true)
        }
        println("PG_TIMER_MODEL cut=ACK_WITH_HELD_ENCLOSING_EXIT proof=MODEL_NOT_NATIVE_HOLD")
    }

    fun verifyMissingPublication(scope: PgTimerProbeScope) {
        val cell = identity(scope.calls)
        val thread = requireNotNull(cell.getAndSet(null))
        try {
            check(scope.calls.observe().scheduling.extentEnded)
            check(scope.calls.observe().capture.status === PersistenceTimerCaptureStatus.PENDING)
            check(scope.call { it.releaseOwnedReference() } === PersistenceTimerAction.REFUSED)
            check(!scope.calls.observe().release.entered)
        } finally {
            restoreIdentity(cell, thread)
        }
        println("PG_TIMER_MODEL cut=ENDED_SCHEDULE_WITH_MISSING_ACK proof=MODEL_NOT_LOST_NATIVE_TASK")
    }

    fun verifyFailedSchedule(scope: PgTimerProbeScope) {
        val outcome = field(field(scope.calls, "scheduling"), "outcome")
        val setter = AtomicReference::class.java.getMethod("set", Any::class.java)
        setter.invoke(outcome, PersistenceTimerOutcome.THREW)
        check(scope.calls.observe().capture.publicationReceived)
        check(scope.calls.observe().capture.status === PersistenceTimerCaptureStatus.FAILED)
        check(scope.calls.observe().capture.termination === PersistenceThreadTermination.UNKNOWN)
        check(scope.call { it.captureThread() } === PersistenceTimerAction.REFUSED)
        // Leave the modeled ended failure in place; its ACK permits only the low-level owned release.
        println("PG_TIMER_MODEL cut=FAILED_ENDED_SCHEDULE_WITH_ACK proof=MODEL_NOT_NATIVE_SCHEDULE_THROW")
    }

    private fun identity(calls: PersistenceTimerReferenceCalls): AtomicReference<*> = field(field(calls, "capture"), "identity") as AtomicReference<*>

    private fun restoreIdentity(cell: AtomicReference<*>, thread: Any) = AtomicReference::class.java.getMethod("set", Any::class.java).invoke(cell, thread)

    private fun field(owner: Any, name: String): Any = requireNotNull(owner.javaClass.getDeclaredField(name).also { it.isAccessible = true }.get(owner))
}
