package me.manga.kira.backend.common.infrastructure.persistence

import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.net.SocketException
import javax.net.SocketFactory

/** Public only for pgjdbc's exact reflective no-argument construction. A configured name grants no authority. */
class TrackedPgSocketFactory : SocketFactory() {
    private val scope = PersistencePgFactoryScope.capture(this)

    @Throws(IOException::class)
    override fun createSocket(): Socket = scope.createSocket(this)

    @Throws(IOException::class)
    override fun createSocket(host: String?, port: Int): Socket = connectedUnavailable()

    @Throws(IOException::class)
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = connectedUnavailable()

    @Throws(IOException::class)
    override fun createSocket(host: InetAddress?, port: Int): Socket = connectedUnavailable()

    @Throws(IOException::class)
    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket = connectedUnavailable()

    private fun connectedUnavailable(): Nothing = throw SocketException("Connected persistence factory overloads are unavailable.")

    override fun toString(): String = "TrackedPgSocketFactory(redacted)"
}
