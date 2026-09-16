package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class PgLifecycleDisconnectLoopbackTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `REAL_SOCKET client FIN is accepted without closing the fixture input`(tlsRaw: Boolean) = PairOwner().use { pair ->
        pair.open()
        val socket = pair.accepted()
        val witness = wire(socket, tlsRaw, AtomicBoolean())
        pair.client.shutdownOutput()
        witness()
        assertFalse(socket.isClosed || socket.isInputShutdown)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `REAL_SOCKET client RST has the pinned reset marker and is accepted`(tlsRaw: Boolean) = PairOwner().use { pair ->
        pair.open()
        val socket = pair.accepted()
        val witness = wire(socket, tlsRaw, AtomicBoolean())
        pair.client.setSoLinger(true, 0)
        pair.client.close()
        socket.soTimeout = 1_000
        val reset = assertThrows(SocketException::class.java) { socket.getInputStream().read() }
        assertTrue(PgLifecycleDisconnectEvidence.reset(reset)) // Actual kernel/JDK reset, not MODEL exception construction.
        witness() // NioSocketImpl keeps the observed reset sticky on the same reader.
        assertFalse(socket.isClosed || socket.isInputShutdown)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `REAL_SOCKET timeout is not a disconnect`(tlsRaw: Boolean) = PairOwner().use { pair ->
        pair.open()
        val socket = pair.accepted()
        val witness = wire(socket, tlsRaw, AtomicBoolean(), allowanceMillis = 500)
        assertThrows(SocketTimeoutException::class.java) { witness() }
        assertFalse(socket.isClosed || socket.isInputShutdown)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `REAL_SOCKET unexpected peer traffic is not a disconnect`(tlsRaw: Boolean) = PairOwner().use { pair ->
        pair.open()
        val witness = wire(pair.accepted(), tlsRaw, AtomicBoolean())
        pair.client.getOutputStream().apply {
            write(42)
            flush()
        }
        assertThrows(IllegalStateException::class.java) { witness() }
    }

    @ParameterizedTest
    @ValueSource(strings = ["PLAIN_INPUT", "TLS_INPUT", "PLAIN_OUTPUT", "TLS_OUTPUT", "PLAIN_CLOSED", "TLS_CLOSED", "PLAIN_CLEANING", "TLS_CLEANING"])
    fun `REAL_SOCKET local shutdown or cleanup cannot provide a peer-disconnect witness`(kind: String) = PairOwner().use { pair ->
        pair.open()
        val socket = pair.accepted()
        val closing = AtomicBoolean()
        val witness = wire(socket, kind.startsWith("TLS"), closing)
        pair.client.shutdownOutput()
        when {
            kind.endsWith("INPUT") -> socket.shutdownInput()
            kind.endsWith("OUTPUT") -> socket.shutdownOutput()
            kind.endsWith("CLOSED") -> socket.close()
            else -> closing.set(true)
        }
        assertThrows(IllegalStateException::class.java) { witness() }
    }

    private fun wire(socket: Socket, tlsRaw: Boolean, closing: AtomicBoolean, allowanceMillis: Long = 2_000): () -> Unit {
        val budget = PersistenceTimeBudget.start(allowanceMillis)
        if (tlsRaw) {
            val wire = PgLifecycleTlsWire(socket, budget, closing)
            return wire::awaitRawDisconnect
        }
        val wire = PgLifecycleNegotiationWire(socket, budget, closing)
        return { wire.disconnect(allowTerminate = false) }
    }

    /** Fixed loopback resources, all retained before bind/connect/accept; no worker or global service. */
    private class PairOwner : AutoCloseable {
        private val listener = ServerSocket()
        val client = Socket()
        private val server = AtomicReference<Socket?>()

        fun open() {
            val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
            listener.bind(InetSocketAddress(loopback, 0))
            listener.soTimeout = 1_000
            client.connect(InetSocketAddress(loopback, listener.localPort), 1_000)
            server.set(listener.accept())
        }

        fun accepted(): Socket = requireNotNull(server.get())

        override fun close() {
            val results = listOf(runCatching { client.close() }, runCatching { server.get()?.close() }, runCatching { listener.close() })
            results.forEach { it.getOrThrow() }
            check(client.isClosed && server.get()?.isClosed != false && listener.isClosed)
        }
    }
}
