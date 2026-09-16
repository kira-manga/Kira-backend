package me.manga.kira.backend.common.infrastructure.persistence

import java.net.Socket
import java.net.SocketAddress
import java.net.SocketOption
import java.net.StandardSocketOptions
import java.util.concurrent.atomic.AtomicInteger

internal fun invokeTransportSocketBusiness(socket: Socket, method: String): Any? = when (method) {
    "CONNECT" -> socket.connect(null)
    "CONNECT_TIMEOUT" -> socket.connect(null, -1)
    "BIND" -> socket.bind(null)
    "INPUT" -> socket.getInputStream()
    "OUTPUT" -> socket.getOutputStream()
    "SHUTDOWN_INPUT" -> socket.shutdownInput()
    "SHUTDOWN_OUTPUT" -> socket.shutdownOutput()
    "URGENT" -> socket.sendUrgentData(42)
    else -> invokeTransportSocketOption(socket, method)
}

private fun invokeTransportSocketOption(socket: Socket, method: String): Any? = when (method) {
    "SET_TCP" -> socket.setTcpNoDelay(true)
    "GET_TCP" -> socket.getTcpNoDelay()
    "SET_LINGER" -> socket.setSoLinger(false, 0)
    "GET_LINGER" -> socket.getSoLinger()
    "SET_OOB" -> socket.setOOBInline(true)
    "GET_OOB" -> socket.getOOBInline()
    "SET_TIMEOUT" -> socket.setSoTimeout(100)
    "GET_TIMEOUT" -> socket.getSoTimeout()
    "SET_SEND" -> socket.setSendBufferSize(4_096)
    "GET_SEND" -> socket.getSendBufferSize()
    "SET_RECEIVE" -> socket.setReceiveBufferSize(4_096)
    "GET_RECEIVE" -> socket.getReceiveBufferSize()
    "SET_KEEPALIVE" -> socket.setKeepAlive(true)
    "GET_KEEPALIVE" -> socket.getKeepAlive()
    "SET_TRAFFIC" -> socket.setTrafficClass(0)
    "GET_TRAFFIC" -> socket.getTrafficClass()
    "SET_REUSE" -> socket.setReuseAddress(true)
    "GET_REUSE" -> socket.getReuseAddress()
    "SET_OPTION" -> socket.setOption(StandardSocketOptions.TCP_NODELAY, true)
    "GET_OPTION" -> socket.getOption(StandardSocketOptions.TCP_NODELAY)
    else -> error("Unknown synthetic Socket operation.")
}

internal fun invokeTransportObservation(socket: Socket, method: String): Any? = when (method) {
    "REMOTE_ADDRESS" -> socket.getInetAddress()
    "LOCAL_ADDRESS" -> socket.getLocalAddress()
    "REMOTE_PORT" -> socket.getPort()
    "LOCAL_PORT" -> socket.getLocalPort()
    "REMOTE_ENDPOINT" -> socket.getRemoteSocketAddress()
    "LOCAL_ENDPOINT" -> socket.getLocalSocketAddress()
    "OPTIONS" -> socket.supportedOptions()
    else -> error("Unknown synthetic observation.")
}

internal fun invokeTransportArgumentCase(socket: Socket, vector: String): Any? = when (vector) {
    "CONNECT_NULL" -> socket.connect(null)
    "CONNECT_NULL_NEGATIVE" -> socket.connect(null, -1)
    "BIND_NULL" -> socket.bind(null)
    "GET_OPTION_NULL" -> socket.getOption<Boolean>(null)
    "SET_OPTION_NULL" -> socket.setOption<Boolean>(null, true)
    "SET_VALUE_NULL" -> socket.setOption(StandardSocketOptions.SO_RCVBUF, null)
    "TIMEOUT_NEGATIVE" -> socket.setSoTimeout(-1)
    "SEND_ZERO" -> socket.setSendBufferSize(0)
    "TRAFFIC_INVALID" -> socket.setTrafficClass(256)
    else -> error("Unknown synthetic argument vector.")
}

internal class TransportHostileAddress : SocketAddress() {
    val renders = AtomicInteger()

    override fun toString(): String {
        renders.incrementAndGet()
        error("Synthetic address must not be rendered by admission.")
    }
}

internal class TransportHostileOption : SocketOption<Any> {
    val callbacks = AtomicInteger()

    override fun name(): String {
        callbacks.incrementAndGet()
        error("Synthetic option must not be inspected by admission.")
    }

    override fun type(): Class<Any> {
        callbacks.incrementAndGet()
        error("Synthetic option must not be inspected by admission.")
    }

    override fun toString(): String {
        callbacks.incrementAndGet()
        error("Synthetic option must not be rendered by admission.")
    }

    override fun hashCode(): Int {
        callbacks.incrementAndGet()
        return 1
    }

    override fun equals(other: Any?): Boolean {
        callbacks.incrementAndGet()
        return this === other
    }
}
