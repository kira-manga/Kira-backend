package me.manga.kira.backend.common.infrastructure.persistence

import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import javax.net.ssl.SSLException

/** Test oracle only. Pinned OpenJDK21 Unix NioSocketImpl maps native reset to this exact marker on Linux and macOS. */
internal object PgLifecycleDisconnectEvidence {
    fun reset(failure: IOException): Boolean = failure.javaClass === SocketException::class.java && failure.message == "Connection reset"

    fun tlsEofOrReset(failure: IOException): Boolean {
        var cursor: Throwable? = failure
        repeat(4) {
            val current = cursor ?: return false
            when {
                current.javaClass === EOFException::class.java -> return true
                current is SocketException -> return reset(current)
                current is SSLException -> cursor = current.cause
                else -> return false
            }
        }
        return false
    }
}
