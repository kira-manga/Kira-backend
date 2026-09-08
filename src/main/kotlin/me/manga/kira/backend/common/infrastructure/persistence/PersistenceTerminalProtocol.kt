package me.manga.kira.backend.common.infrastructure.persistence

/** Work ending and resource disposal are deliberately different facts. Only the exact terminal path writes these. */
internal enum class PersistenceTerminalDisposition {
    PENDING,
    BEFORE_DRIVER,
    TRACKED_DISPOSED,
    DRIVER_CLOSE_RETURNED,
    NO_RAW_DRIVER_RETURN_ONLY,
    UNKNOWN_ENDED,
}

internal enum class PersistenceTerminalCall {
    NOT_INVOKED,
    RUNNING,
    RETURNED,
    THREW,
}

internal enum class PersistenceTerminalTransportState {
    PENDING,
    DISPOSED,
    FAILED_ENDED,
}

internal enum class PersistenceLifecycleActivation {
    STARTED,
    ALREADY_CLAIMED,
    CLOSED,
    FAILED,
}

internal enum class PersistenceLifecycleObservation {
    READY,
    NOT_REQUESTED,
    UNAVAILABLE,
    UNSUPPORTED_OBSERVER,
    PENDING,
    UNKNOWN,
    TRACKED_LOCAL_ENDED,
    DRIVER_CONTRACT_ONLY_ENDED,
}

/** Counts are diagnostics, never disposal/reuse authority. No identity, Thread, raw JDBC or writable cell escapes. */
internal data class PersistenceLifecycleSnapshot(
    val shutdownRequested: Boolean,
    val ordinaryReady: Boolean,
    val deletionRequested: Boolean,
    val deletionReady: Boolean,
    val timerReady: Boolean,
    val ordinaryRetained: Int?,
    val deletionRetained: Int?,
    val weakEvidenceUsed: Boolean,
    val cleanupFailureObserved: Boolean,
)
