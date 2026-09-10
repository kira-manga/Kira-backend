package me.manga.kira.backend.common.infrastructure.persistence

import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.sql.SQLException

/** A validated association, not the tentative ordinal/auxiliary fields used by registration. */
internal enum class PgLifecycleDatabaseRelayAssociation(val token: String) {
    UNREGISTERED("UNREGISTERED"),
    PRIMARY_0("PRIMARY(0)"),
    PRIMARY_1("PRIMARY(1)"),
    AUX_0("AUX(0)"),
    AUX_1("AUX(1)"),
}

internal enum class PgLifecycleDatabaseRelayActor {
    ACCEPTOR,
    COORDINATOR,
    CLIENT,
    SERVER,
}

/** Closed capture spans only: not native/server causes or a chronological first-cause assertion. */
internal enum class PgLifecycleDatabaseRelayStage(val actor: PgLifecycleDatabaseRelayActor) {
    ACCEPT(PgLifecycleDatabaseRelayActor.ACCEPTOR),
    SESSION_CONSTRUCT(PgLifecycleDatabaseRelayActor.ACCEPTOR),
    SESSION_RETAIN(PgLifecycleDatabaseRelayActor.ACCEPTOR),
    SESSION_START(PgLifecycleDatabaseRelayActor.ACCEPTOR),
    UNRETAINED_CLOSE(PgLifecycleDatabaseRelayActor.ACCEPTOR),
    CLIENT_CONFIGURE(PgLifecycleDatabaseRelayActor.COORDINATOR),
    STARTUP_READ(PgLifecycleDatabaseRelayActor.COORDINATOR),
    REGISTER(PgLifecycleDatabaseRelayActor.COORDINATOR),
    UPSTREAM_CONFIGURE(PgLifecycleDatabaseRelayActor.COORDINATOR),
    UPSTREAM_CONNECT(PgLifecycleDatabaseRelayActor.COORDINATOR),
    STARTUP_FORWARD(PgLifecycleDatabaseRelayActor.COORDINATOR),
    PUMP_START(PgLifecycleDatabaseRelayActor.COORDINATOR),
    ACTOR_JOIN(PgLifecycleDatabaseRelayActor.COORDINATOR),
    CLIENT_PUMP(PgLifecycleDatabaseRelayActor.CLIENT),
    SERVER_PUMP(PgLifecycleDatabaseRelayActor.SERVER),
    CLEANUP_CLIENT_CLOSE(PgLifecycleDatabaseRelayActor.COORDINATOR),
    CLEANUP_UPSTREAM_CLOSE(PgLifecycleDatabaseRelayActor.COORDINATOR),
}

internal enum class PgLifecycleDatabaseRelayFailureType {
    SOCKET_TIMEOUT,
    CONNECT,
    SOCKET,
    EOF,
    IO,
    SQL,
    INTERRUPTED,
    BOUNDARY,
    STATE_CHECK,
    ERROR,
    OTHER,
    ;

    companion object {
        /** Type tests only. Never read a Throwable accessor or use the JDBC envelope adapter. */
        fun of(failure: Throwable): PgLifecycleDatabaseRelayFailureType = when (failure) {
            is SocketTimeoutException -> SOCKET_TIMEOUT
            is ConnectException -> CONNECT
            is SocketException -> SOCKET
            is EOFException -> EOF
            is IOException -> IO
            is SQLException -> SQL
            is InterruptedException -> INTERRUPTED
            is PersistenceBoundaryException -> BOUNDARY
            is IllegalStateException -> STATE_CHECK
            is Error -> ERROR
            else -> OTHER
        }
    }
}

/** The failure-presence cell itself stores this detached winning event, never a Throwable/resource graph. */
internal data class PgLifecycleDatabaseRelayFailure(
    val acceptedIndex: Int?,
    val association: PgLifecycleDatabaseRelayAssociation,
    val stage: PgLifecycleDatabaseRelayStage,
    val type: PgLifecycleDatabaseRelayFailureType,
    // The acceptor uses its relay closing flag; session actors use that session's fixtureClosing flag.
    val fixtureClosing: Boolean,
) {
    /** Tuple: accepted index / validated association / actor / capture span / type / closing-at-capture. */
    fun diagnostic(): String = "${acceptedIndex ?: "UNAVAILABLE"}/${association.token}/${stage.actor.name}/${stage.name}/${type.name}/$fixtureClosing"

    companion object {
        /** Force the closed catalogs (including stage-owned actors) cold, before the relay owns any socket. Count is not an oracle. */
        fun prepareRuntime(): Int =
            PgLifecycleDatabaseRelayAssociation.entries.size + PgLifecycleDatabaseRelayStage.entries.size + PgLifecycleDatabaseRelayFailureType.entries.size
    }
}
