package me.manga.kira.backend.common.infrastructure.persistence

/** Safe facts only. A returned native call and its enclosing extent ending are separate observations. */
internal data class PersistenceTimerCall(val entered: Boolean, val outcome: PersistenceTimerOutcome, val extentEnded: Boolean)

internal enum class PersistenceTimerOutcome {
    NOT_RETURNED,
    RETURNED,
    THREW,
}

internal enum class PersistenceTimerAction {
    REFUSED,
    SUCCEEDED,
    UNAVAILABLE,
    FAILED,
}
