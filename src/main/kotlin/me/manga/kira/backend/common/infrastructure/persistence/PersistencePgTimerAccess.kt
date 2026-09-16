package me.manga.kira.backend.common.infrastructure.persistence

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.sql.Driver
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Successful ordinary Driver construction is retained even when optional timer metadata is rejected. */
internal class PersistenceDriverWithTimerAccess(val driver: Driver, val timerAccess: PersistencePgTimerAccess?) {
    override fun toString(): String = "PersistenceDriverWithTimerAccess(redacted)"
}

/** Public pgjdbc 42.7.12 utility seam, not standard JDBC or a controlled-runtime certificate. */
internal class PersistencePgTimerAccess private constructor(
    private val prepared: PreparedPersistenceDriver,
    private val utilityClass: Class<*>,
    private val utilityMethod: Method,
    private val acquireMethod: Method,
    private val releaseMethod: Method,
) {
    private val bound = AtomicBoolean()

    /** Construct and retain calls/cells/task before starting this controller. No native work here. */
    fun bind(controller: PersistenceRetainedPlatformThread): PersistenceTimerReferenceCalls? {
        if (controller.startPhase() !== PersistenceThreadStartPhase.NEW || !bound.compareAndSet(false, true)) return null
        return Calls(this, controller)
    }

    /** The managed controller was retained before start and binds only from its authentic body. */
    fun bindActive(controller: PersistenceRetainedPlatformThread): PersistenceTimerReferenceCalls? {
        val authentic = Thread.currentThread() === controller.thread && controller.hasEntered() && !controller.hasBodyEnded()
        if (!authentic || !bound.compareAndSet(false, true)) return null
        return Calls(this, controller)
    }

    override fun toString(): String = "PersistencePgTimerAccess(redacted)"

    private class Calls(private val access: PersistencePgTimerAccess, private val controller: PersistenceRetainedPlatformThread) :
        PersistenceTimerReferenceCalls {
        private val guard = Call()
        private val utility = Call()
        private val acquisition = Call()
        private val scheduling = Call()
        private val release = Call()
        private val rawUtility = AtomicReference<Any?>()
        private val rawTimer = AtomicReference<Any?>()
        private val referenceReturned = AtomicBoolean()
        private val stockTimerReturned = AtomicBoolean()
        private val capture = Capture()

        override fun acquireReference(): PersistenceTimerAction {
            if (!onController() || release.claimed.get() || guard.claimed.get()) return PersistenceTimerAction.REFUSED
            val checked = invoke(guard) {
                access.prepared.recheckForTimer()
                PersistenceTimerAction.SUCCEEDED
            }
            if (checked !== PersistenceTimerAction.SUCCEEDED) return checked
            val found = invoke(utility) {
                rawUtility.set(invokeMethod(access.utilityMethod, null))
                if (rawUtility.get()?.javaClass === access.utilityClass) PersistenceTimerAction.SUCCEEDED else PersistenceTimerAction.UNAVAILABLE
            }
            if (found !== PersistenceTimerAction.SUCCEEDED) return found
            return invoke(acquisition) {
                // First after the reflective return: retain even null/subclass returns before later checks.
                rawTimer.set(invokeMethod(access.acquireMethod, rawUtility.get()))
                referenceReturned.set(rawTimer.get() != null)
                stockTimerReturned.set(rawTimer.get()?.javaClass === Timer::class.java)
                if (stockTimerReturned.get()) PersistenceTimerAction.SUCCEEDED else PersistenceTimerAction.UNAVAILABLE
            }
        }

        override fun captureThread(): PersistenceTimerAction {
            if (!onController() || release.claimed.get()) return PersistenceTimerAction.REFUSED
            if (!acquisition.succeeded() || !stockTimerReturned.get()) return PersistenceTimerAction.REFUSED
            return invoke(scheduling) {
                capture.schedule(rawTimer.get() as Timer)
                PersistenceTimerAction.SUCCEEDED
            }
        }

        override fun releaseOwnedReference(): PersistenceTimerAction {
            if (!onController() || !acquisition.succeeded() || !referenceReturned.get()) return PersistenceTimerAction.REFUSED
            // An entered uncertain schedule is never recast as a never-scheduled capture.
            if (scheduling.entered.get() && (!scheduling.extentEnded.get() || !capture.hasPublication())) return PersistenceTimerAction.REFUSED
            return invoke(release) {
                invokeMethod(access.releaseMethod, rawUtility.get())
                PersistenceTimerAction.SUCCEEDED
            }
        }

        override fun observe(): PersistenceTimerObservation {
            val schedule = scheduling.observe()
            return PersistenceTimerObservation(
                guard.observe(),
                utility.observe(),
                acquisition.observe(),
                schedule,
                release.observe(),
                referenceReturned.get(),
                stockTimerReturned.get(),
                capture.observe(schedule),
            )
        }

        override fun newBoundary(runner: PersistenceRetainedPlatformThread): PersistenceTimerBoundary? {
            if (release.claimed.get() || !acquisition.succeeded()) return null
            if (!stockTimerReturned.get() || !scheduling.succeeded()) return null
            val identity = capture.threadIdentity() ?: return null
            return Boundary(rawTimer.get() as Timer, identity, runner)
        }

        private fun onController(): Boolean = Thread.currentThread() === controller.thread && controller.hasEntered() && !controller.hasBodyEnded()

        private inline fun invoke(call: Call, action: () -> PersistenceTimerAction): PersistenceTimerAction {
            if (!call.claimed.compareAndSet(false, true)) return PersistenceTimerAction.REFUSED
            var returned = false
            return runCatching {
                try {
                    call.entered.set(true)
                    val result = action()
                    call.outcome.set(PersistenceTimerOutcome.RETURNED)
                    returned = true
                    result
                } finally {
                    // Publish exit even before a failed Result can allocate/box an external Throwable.
                    if (!returned) call.outcome.set(PersistenceTimerOutcome.THREW)
                    call.extentEnded.set(true)
                }
            }.getOrElse { failure ->
                if (failure is InterruptedException) Thread.currentThread().interrupt()
                if (failure is Error) throw failure
                PersistenceTimerAction.FAILED
            }
        }

        private fun invokeMethod(method: Method, receiver: Any?): Any? = try {
            method.invoke(receiver)
        } catch (failure: InvocationTargetException) {
            // Only Method.invoke's trusted wrapper, never an arbitrary failure graph or diagnostic accessor.
            throw failure.targetException
        }

        override fun toString(): String = "PersistenceTimerReferenceCalls(redacted)"
    }

    private class Call {
        val claimed = AtomicBoolean()
        val entered = AtomicBoolean()
        val outcome = AtomicReference(PersistenceTimerOutcome.NOT_RETURNED)
        val extentEnded = AtomicBoolean()

        fun succeeded(): Boolean = extentEnded.get() && outcome.get() === PersistenceTimerOutcome.RETURNED

        fun observe(): PersistenceTimerCall = PersistenceTimerCall(entered.get(), outcome.get(), extentEnded.get())
    }

    /** The exact pin supplies both identities. A caller cannot construct this with an arbitrary Timer/Thread. */
    private class Boundary(private val timer: Timer, expectedThread: Thread, private val runner: PersistenceRetainedPlatformThread) :
        PersistenceTimerBoundary {
        private val scheduling = Call()
        private val cell = BoundaryCell(expectedThread)
        private val task = BoundaryTask(cell)

        override fun schedule(): PersistenceTimerAction {
            if (Thread.currentThread() !== runner.thread || !runner.hasEntered() || runner.hasBodyEnded()) return PersistenceTimerAction.REFUSED
            if (!scheduling.claimed.compareAndSet(false, true)) return PersistenceTimerAction.REFUSED
            var returned = false
            return runCatching {
                try {
                    scheduling.entered.set(true)
                    timer.schedule(task, 0L)
                    returned = true
                    PersistenceTimerAction.SUCCEEDED
                } finally {
                    scheduling.outcome.set(if (returned) PersistenceTimerOutcome.RETURNED else PersistenceTimerOutcome.THREW)
                    scheduling.extentEnded.set(true)
                }
            }.getOrElse { failure ->
                if (failure is InterruptedException) Thread.currentThread().interrupt()
                if (failure is Error) throw failure
                PersistenceTimerAction.FAILED
            }
        }

        override fun acknowledged(): Boolean = scheduling.succeeded() && cell.acknowledged.get()

        override fun observation(): PersistenceTimerBoundaryObservation = PersistenceTimerBoundaryObservation(
            scheduling.observe(),
            cell.acknowledged.get(),
        )

        override fun toString(): String = "PersistenceTimerBoundary(redacted)"
    }

    private class BoundaryCell(val expectedThread: Thread) {
        val acknowledged = AtomicBoolean()
    }

    /** Non-inner: no Boundary/Calls/root/record/raw reference survives on the Timer thread. */
    private class BoundaryTask(private val cell: BoundaryCell) : TimerTask() {
        override fun run() {
            if (Thread.currentThread() === cell.expectedThread) cell.acknowledged.compareAndSet(false, true)
        }
    }

    private class Capture {
        private val identity = AtomicReference<Thread?>()
        private val task = CaptureTask(identity)

        fun schedule(timer: Timer) = timer.schedule(task, 0L)

        fun hasPublication(): Boolean = identity.get() != null

        fun threadIdentity(): Thread? = identity.get()

        fun observe(schedule: PersistenceTimerCall): PersistenceTimerCapture {
            val thread = identity.get()
            val status = when {
                !schedule.entered -> PersistenceTimerCaptureStatus.NOT_SCHEDULED
                !schedule.extentEnded -> PersistenceTimerCaptureStatus.PENDING
                schedule.outcome !== PersistenceTimerOutcome.RETURNED -> PersistenceTimerCaptureStatus.FAILED
                thread == null -> PersistenceTimerCaptureStatus.PENDING
                else -> PersistenceTimerCaptureStatus.CAPTURED
            }
            val termination = when {
                status !== PersistenceTimerCaptureStatus.CAPTURED -> PersistenceThreadTermination.UNKNOWN
                thread === Thread.currentThread() -> PersistenceThreadTermination.PENDING
                requireNotNull(thread).isAlive -> PersistenceThreadTermination.PENDING
                else -> PersistenceThreadTermination.TERMINATED
            }
            return PersistenceTimerCapture(thread != null, status, termination)
        }
    }

    /** Deliberately non-inner: the Timer retains only this bounded cell, never Calls, Driver or a root. */
    private class CaptureTask(private val identity: AtomicReference<Thread?>) : TimerTask() {
        override fun run() {
            identity.compareAndSet(null, Thread.currentThread())
        }
    }

    companion object {
        /** Metadata only. The managed caller already retains the guarded Driver before reaching this seam. */
        internal fun inspectRetained(prepared: PreparedPersistenceDriver, driver: Class<*>): PersistencePgTimerAccess? = inspect(prepared, driver)

        /** The only metadata factory itself executes guarded construction; callers cannot supply a fake Driver. */
        fun construct(prepared: PreparedPersistenceDriver): PersistenceDriverWithTimerAccess {
            val driver = prepared.construct()
            val access = runCatching { inspect(prepared, driver.javaClass) }.getOrElse { failure ->
                if (failure is InterruptedException) Thread.currentThread().interrupt()
                if (failure is Error) throw failure
                null
            }
            return PersistenceDriverWithTimerAccess(driver, access)
        }

        private fun inspect(prepared: PreparedPersistenceDriver, driver: Class<*>): PersistencePgTimerAccess? {
            if (!prepared.ownsTimerDriverClass(driver) || !publicConcrete(driver)) return null
            val loader = driver.classLoader
            val shared = Class.forName("org.postgresql.util.SharedTimer", false, loader)
            if (shared.classLoader !== loader || !publicConcrete(shared) || Class.forName("org.postgresql.Driver", false, loader) !== driver) return null
            val utility = exactMethod(driver, "getSharedTimer", shared, true) ?: return null
            val acquire = exactMethod(shared, "getTimer", Timer::class.java, false) ?: return null
            val release = exactMethod(shared, "releaseTimer", Void.TYPE, false) ?: return null
            return PersistencePgTimerAccess(prepared, shared, utility, acquire, release)
        }

        private fun publicConcrete(type: Class<*>): Boolean = Modifier.isPublic(type.modifiers) && !Modifier.isAbstract(type.modifiers)

        private fun exactMethod(type: Class<*>, name: String, returns: Class<*>, isStatic: Boolean): Method? {
            val method = type.getDeclaredMethod(name)
            return method.takeIf {
                it.declaringClass === type && it.returnType === returns && Modifier.isPublic(it.modifiers) &&
                    Modifier.isStatic(it.modifiers) == isStatic && !Modifier.isAbstract(it.modifiers)
            }
        }
    }
}
