package me.manga.kira.backend.common.infrastructure.persistence

/**
 * Low-level operations, not lifecycle admission. The managed root drains both participants before
 * release. Only the exact retained controller may acquire/capture/release its reference.
 */
internal sealed interface PersistenceTimerReferenceCalls {
    fun acquireReference(): PersistenceTimerAction

    fun captureThread(): PersistenceTimerAction

    fun releaseOwnedReference(): PersistenceTimerAction

    /** Inert, exact one-shot record boundary. Only its bound terminal runner can schedule it. */
    fun newBoundary(runner: PersistenceRetainedPlatformThread): PersistenceTimerBoundary?

    /** Nonwaiting observations only; never acquires, schedules, releases or upgrades attempt policy. */
    fun observe(): PersistenceTimerObservation
}

internal data class PersistenceTimerObservation(
    val guard: PersistenceTimerCall,
    val utility: PersistenceTimerCall,
    val acquisition: PersistenceTimerCall,
    val scheduling: PersistenceTimerCall,
    val release: PersistenceTimerCall,
    val referenceReturned: Boolean,
    val stockTimerReturned: Boolean,
    val capture: PersistenceTimerCapture,
)
