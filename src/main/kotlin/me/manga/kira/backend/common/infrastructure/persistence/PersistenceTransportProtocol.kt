package me.manga.kira.backend.common.infrastructure.persistence

internal enum class PersistenceTransportRole {
    PRIMARY,
    AUX_CANCEL,
}

internal enum class PersistenceTransportCallKind {
    BUSINESS,
    OBSERVATION,
}

internal enum class PersistenceTransportRefusal {
    CONTENDED,
    FULL,
    SEALED,
    EXHAUSTED,
    INVALID_CONSTRUCTION,
}

internal enum class PersistenceTransportConstruction {
    ACTIVE,
    RETURNED,
    FAILED,
}

internal enum class PersistenceTransportClosePhase {
    NOT_STARTED,
    RUNNING,
    API_CLOSE_ACKNOWLEDGED,
    FAILED,
}

/** A requested close is not an acknowledgement, nor a promise to close a future raw result. */
internal enum class PersistenceTransportCloseRequest {
    REFUSED,
    WAITING_FOR_RAW,
    NO_RAW_RETURNED,
    REQUESTED,
}

internal sealed interface PersistenceTransportCreation<out T : AutoCloseable> {
    class Created<T : AutoCloseable>(val record: PersistenceTransportRecord, val resource: T) : PersistenceTransportCreation<T> {
        override fun toString(): String = "PersistenceTransportCreation.Created(redacted)"
    }

    class Retained(val record: PersistenceTransportRecord) : PersistenceTransportCreation<Nothing> {
        override fun toString(): String = "PersistenceTransportCreation.Retained(redacted)"
    }

    class Refused(val reason: PersistenceTransportRefusal) : PersistenceTransportCreation<Nothing> {
        override fun toString(): String = "PersistenceTransportCreation.Refused(${reason.name})"
    }
}

internal class PersistenceTransportRecordSnapshot(
    val record: PersistenceTransportRecord,
    val construction: PersistenceTransportConstruction,
    val rawReturned: Boolean,
    val businessSealed: Boolean,
    val activeBusiness: Int,
    val activeObservations: Int,
    val firstClose: PersistenceTransportClosePhase,
    val unknown: Boolean,
    val allCallsSealed: Boolean,
) {
    override fun toString(): String = "PersistenceTransportRecordSnapshot(redacted)"
}

/** Point-in-time counts only. Later observations remain possible; no snapshot authorizes reuse. */
internal sealed interface PersistenceTransportSnapshot {
    data object Unavailable : PersistenceTransportSnapshot

    class Available(
        val sealed: Boolean,
        val revision: Long,
        val revisionExhausted: Boolean,
        val primary: PersistenceTransportRecordSnapshot?,
        val auxiliary: PersistenceTransportRecordSnapshot?,
    ) : PersistenceTransportSnapshot {
        override fun toString(): String = "PersistenceTransportSnapshot.Available(redacted)"
    }
}
