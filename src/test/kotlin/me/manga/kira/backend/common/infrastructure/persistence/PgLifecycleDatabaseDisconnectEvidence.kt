package me.manga.kira.backend.common.infrastructure.persistence

import java.net.SocketException

/** Pinned OpenJDK21 stock NioSocketImpl read mapping only. Any other input exception remains fixture failure, not reset evidence. */
internal object PgLifecycleDatabaseDisconnectEvidence {
    fun reset(failure: SocketException): Boolean = failure.javaClass === SocketException::class.java && failure.message == "Connection reset"
}
