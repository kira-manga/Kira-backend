package me.manga.kira.backend.common.infrastructure.persistence

/** Only the private timer-access implementation can create a boundary; neither Timer nor task escapes. */
internal sealed interface PersistenceTimerBoundary {
    fun schedule(): PersistenceTimerAction

    fun acknowledged(): Boolean

    fun observation(): PersistenceTimerBoundaryObservation
}

internal data class PersistenceTimerBoundaryObservation(val scheduling: PersistenceTimerCall, val acknowledgement: Boolean)
