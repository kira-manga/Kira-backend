package me.manga.kira.backend.common.infrastructure.persistence

import java.sql.Connection

internal sealed interface PersistencePhysicalReservation {
    class Accepted(val record: PersistencePhysicalRecord) : PersistencePhysicalReservation {
        override fun toString(): String = "PersistencePhysicalReservation.Accepted(redacted)"
    }

    class Refused(val reason: PersistencePhysicalRefusal) : PersistencePhysicalReservation {
        override fun toString(): String = "PersistencePhysicalReservation.Refused(${reason.name})"
    }
}

internal enum class PersistencePhysicalRefusal {
    CONTENDED,
    FULL,
    SEALED,
}

/** No outcome delivers raw to the opening caller or certifies physical disposal. */
internal enum class PersistencePhysicalOpening {
    REFUSED,
    INTERRUPTED,
    RETAINED,
    RETAINED_FOR_RETIREMENT,
    NO_RAW_RETURN,
    FAILED,
}

internal sealed interface PersistencePhysicalTerminal {
    class Claimed(val claim: PersistencePhysicalTerminalClaim) : PersistencePhysicalTerminal {
        override fun toString(): String = "PersistencePhysicalTerminal.Claimed(redacted)"
    }

    data object Refused : PersistencePhysicalTerminal
}

internal sealed interface PersistencePhysicalRawDecision {
    /** The registry still retains this exact raw reference after the one grant. */
    class Granted(val raw: Connection) : PersistencePhysicalRawDecision {
        override fun toString(): String = "PersistencePhysicalRawDecision.Granted(redacted)"
    }

    data object WaitingForOpening : PersistencePhysicalRawDecision

    data object NoRawReturned : PersistencePhysicalRawDecision

    data object AlreadyTaken : PersistencePhysicalRawDecision

    data object Refused : PersistencePhysicalRawDecision
}

internal sealed interface PersistencePhysicalSnapshot {
    data object Unavailable : PersistencePhysicalSnapshot

    class Available(
        val capacity: Int,
        val sealed: Boolean,
        val occupied: Int,
        val opening: Int,
        val live: Int,
        val retiring: Int,
        val unknown: Int,
        val activeOpening: Int,
        val terminalClaimed: Int,
    ) : PersistencePhysicalSnapshot {
        override fun toString(): String = "PersistencePhysicalSnapshot.Available"
    }
}
