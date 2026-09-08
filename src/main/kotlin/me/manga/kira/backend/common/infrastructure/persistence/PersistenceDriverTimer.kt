package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One pin/controller shared by both participants. It never owns a participant's Connection or releases a foreign pin. */
internal class PersistenceDriverTimer(private val root: PersistenceJdbcDriverRoot) {
    private val metadata = AtomicReference<PersistencePgTimerAccess?>()
    private val metadataReady = AtomicBoolean()
    private val calls = AtomicReference<PersistenceTimerReferenceCalls?>()
    private val ready = AtomicBoolean()
    private val failed = AtomicBoolean()
    private val actor = PersistenceRetainedPlatformThread("kira-persistence-timer-controller", ::run)

    fun start(): PersistenceFactoryStart = actor.start()

    fun forbidStart() {
        ready.set(false)
        actor.forbidStart()
    }

    fun publishMetadata(access: PersistencePgTimerAccess?) {
        check(!metadataReady.get())
        metadata.set(access)
        metadataReady.set(true)
    }

    fun finishMetadataIfAbsent() {
        metadataReady.compareAndSet(false, true)
    }

    fun canAcceptStrong(): Boolean = ready.get() && !root.shutdown.get() && !failed.get() && !actor.hasBodyEnded()

    fun preparationFailed(): Boolean = failed.get() || (metadataReady.get() && metadata.get() == null) || actor.hasBodyEnded()

    fun newBoundary(runner: PersistenceRetainedPlatformThread): PersistenceTimerBoundary? = calls.get()?.newBoundary(runner)

    fun threadEnded(): Boolean = actor.termination().ended()

    /** Independent of controller release: a dead captured Timer can settle failed work, never mint an ACK. */
    fun capturedThreadEnded(): Boolean = calls.get()?.observe()?.capture?.termination === PersistenceThreadTermination.TERMINATED

    /** Called only outside F/G/T. The captured Thread's actual isAlive observation is not an aggregate snapshot certificate. */
    fun shutdownObservation(): PersistenceLifecycleObservation {
        if (!actor.termination().ended()) return PersistenceLifecycleObservation.PENDING
        val operations =
            calls.get() ?: return if (failed.get()) PersistenceLifecycleObservation.UNKNOWN else PersistenceLifecycleObservation.TRACKED_LOCAL_ENDED
        val state = operations.observe()
        if (!state.acquisition.entered) {
            return if (failed.get()) PersistenceLifecycleObservation.UNKNOWN else PersistenceLifecycleObservation.TRACKED_LOCAL_ENDED
        }
        if (!state.referenceReturned || !state.acquisition.extentEnded) return PersistenceLifecycleObservation.UNKNOWN
        if (!state.release.extentEnded || state.release.outcome !== PersistenceTimerOutcome.RETURNED) return PersistenceLifecycleObservation.UNKNOWN
        return when (state.capture.termination) {
            PersistenceThreadTermination.TERMINATED ->
                if (failed.get()) PersistenceLifecycleObservation.UNKNOWN else PersistenceLifecycleObservation.TRACKED_LOCAL_ENDED

            PersistenceThreadTermination.PENDING -> PersistenceLifecycleObservation.PENDING

            else -> PersistenceLifecycleObservation.UNKNOWN
        }
    }

    private fun run() {
        try {
            runCatching { controlTimer() }.onFailure { failure ->
                failed.set(true)
                if (failure is InterruptedException) Thread.currentThread().interrupt()
                if (failure is Error) throw failure
            }
        } finally {
            ready.set(false)
        }
    }

    private fun controlTimer() {
        while (!metadataReady.get() && !root.shutdown.get()) persistenceLifecyclePark()
        if (root.shutdown.get()) return
        val access = metadata.get() ?: return
        val bound = access.bindActive(actor)
        calls.set(bound) // Entire object/cells/task are retained before any utility invocation.
        if (bound == null) {
            failed.set(true)
            return
        }
        val acquired = bound.acquireReference()
        if (acquired === PersistenceTimerAction.SUCCEEDED) {
            if (bound.captureThread() !== PersistenceTimerAction.SUCCEEDED) failed.set(true)
        } else {
            failed.set(true)
        }
        while (!root.canReleaseTimer()) {
            val state = bound.observe()
            ready.set(
                !failed.get() && state.capture.status === PersistenceTimerCaptureStatus.CAPTURED &&
                    state.capture.termination === PersistenceThreadTermination.PENDING && !state.release.entered,
            )
            persistenceLifecyclePark()
        }
        ready.set(false)
        release(bound)
    }

    private fun release(bound: PersistenceTimerReferenceCalls) {
        var state = bound.observe()
        if (!state.referenceReturned || state.acquisition.outcome !== PersistenceTimerOutcome.RETURNED || !state.acquisition.extentEnded) return
        // A partly entered schedule is not an unused capture. Wait without a retry, cancel or guessed Thread.
        while (state.scheduling.entered && (!state.scheduling.extentEnded || !state.capture.publicationReceived)) {
            persistenceLifecyclePark()
            state = bound.observe()
        }
        if (bound.releaseOwnedReference() !== PersistenceTimerAction.SUCCEEDED) failed.set(true)
    }

    override fun toString(): String = "PersistenceDriverTimer(redacted)"
}
