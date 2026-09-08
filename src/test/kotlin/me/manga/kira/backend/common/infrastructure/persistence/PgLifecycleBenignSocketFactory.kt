package me.manga.kira.backend.common.infrastructure.persistence

import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReferenceArray
import javax.net.SocketFactory

/** Genuine harmless configured provider. It returns stock unconnected sockets; no auth/TLS/routing override or trust bypass. */
class PgLifecycleBenignSocketFactory : SocketFactory() {
    init {
        check(instances.incrementAndGet() <= 2)
    }

    override fun createSocket(): Socket {
        val slot = creations.getAndIncrement()
        check(slot in 0..1)
        val socket = Socket()
        check(sockets.compareAndSet(slot, null, socket))
        return socket
    }

    override fun createSocket(host: String?, port: Int): Socket = error("Unexpected connected provider overload.")

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = error("Unexpected connected provider overload.")

    override fun createSocket(host: InetAddress?, port: Int): Socket = error("Unexpected connected provider overload.")

    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        error("Unexpected connected provider overload.")

    companion object {
        val instances = AtomicInteger()
        val creations = AtomicInteger()
        private val sockets = AtomicReferenceArray<Socket?>(2)

        fun verifyDisposed(count: Int) {
            check(instances.get() == count && creations.get() == count)
            repeat(count) { check(requireNotNull(sockets.get(it)).isClosed) }
        }

        fun cleanup() {
            val closed = (0..1).map { runCatching { sockets.get(it)?.close() } }
            closed.forEach { it.getOrThrow() }
        }
    }
}
