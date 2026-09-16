package me.manga.kira.backend.common.infrastructure.persistence

/** No task, writable cell or Thread escapes through this observation. ACK is not task-return proof. */
internal data class PersistenceTimerCapture(
    val publicationReceived: Boolean,
    val status: PersistenceTimerCaptureStatus,
    val termination: PersistenceThreadTermination,
)

internal enum class PersistenceTimerCaptureStatus {
    NOT_SCHEDULED,
    PENDING,
    FAILED,
    CAPTURED,
}
