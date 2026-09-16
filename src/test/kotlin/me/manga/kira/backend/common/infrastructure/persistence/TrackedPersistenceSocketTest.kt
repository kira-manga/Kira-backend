package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Modifier
import java.net.Socket
import java.net.StandardSocketOptions

class TrackedPersistenceSocketTest {
    @Test
    fun `REAL API all selected declarations have exact override inheritance and checked exception treatment`() = TransportTestScope("REAL_SOCKET_API").use {
        val inherited = setOf("isConnected", "isBound", "isClosed", "isInputShutdown", "isOutputShutdown", "getChannel", "setPerformancePreferences")
        assertTransportApiShape(Socket::class.java, TrackedPersistenceSocket::class.java, inherited, 44)
        assertTransportApiShape(InputStream::class.java, TrackedPersistenceInputStream::class.java, setOf("mark", "reset", "markSupported"), 14)
        assertTransportApiShape(OutputStream::class.java, TrackedPersistenceOutputStream::class.java, setOf("flush"), 5)
        for (type in listOf(TrackedPersistenceSocket::class.java, TrackedPersistenceInputStream::class.java, TrackedPersistenceOutputStream::class.java)) {
            assertTrue(Modifier.isFinal(type.modifiers))
            assertTrue(type.methods.none { method -> method.name in setOf("getRaw", "unwrap", "getDelegate", "getBinding") })
            assertSame(type, type.getMethod("toString").declaringClass)
        }
        val constructors = TrackedPersistenceSocket::class.java.declaredConstructors.filterNot { constructor -> constructor.isSynthetic }
        assertEquals(2, constructors.size)
        assertTrue(constructors.all { constructor -> Modifier.isPrivate(constructor.modifiers) })
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(
        strings = [
            "CONNECT", "CONNECT_TIMEOUT", "BIND", "INPUT", "OUTPUT", "SHUTDOWN_INPUT", "SHUTDOWN_OUTPUT", "URGENT",
            "SET_TCP", "GET_TCP", "SET_LINGER", "GET_LINGER", "SET_OOB", "GET_OOB", "SET_TIMEOUT", "GET_TIMEOUT",
            "SET_SEND", "GET_SEND", "SET_RECEIVE", "GET_RECEIVE", "SET_KEEPALIVE", "GET_KEEPALIVE", "SET_TRAFFIC",
            "GET_TRAFFIC", "SET_REUSE", "GET_REUSE", "SET_OPTION", "GET_OPTION",
        ],
    )
    fun `REAL API each business method is refused before super on seal and full capacity`(method: String) = TransportTestScope("REAL_SOCKET_API").use { scope ->
        for (mode in listOf("SEALED", "FULL")) {
            val fixture = scope.socket()
            val calls = if (mode == "FULL") holdTransportCalls(fixture, PersistenceTransportCallKind.BUSINESS, 32) else emptyList()
            if (mode == "SEALED") fixture.owner.seal(fixture.record)
            assertTransportBusinessRefused { invokeTransportSocketBusiness(fixture.socket, method) }
            assertFalse(fixture.socket.isBound)
            assertFalse(fixture.socket.isConnected)
            assertFalse(fixture.socket.isClosed)
            assertEquals(PersistenceTransportClosePhase.NOT_STARTED, transportRecordSnapshot(fixture.owner, fixture.record).firstClose)
            releaseTransportCalls(fixture, calls)
        }
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["REMOTE_ADDRESS", "LOCAL_ADDRESS", "REMOTE_PORT", "LOCAL_PORT", "REMOTE_ENDPOINT", "LOCAL_ENDPOINT", "OPTIONS"])
    fun `REAL API every observation uses one independent cell and overload never fabricates metadata`(method: String) =
        TransportTestScope("REAL_SOCKET_API").use { scope ->
            val fixture = scope.socket()
            scope.connect(fixture)
            val expected = invokeTransportObservation(fixture.socket, method)
            val business = holdTransportCalls(fixture, PersistenceTransportCallKind.BUSINESS, 32)
            val observations = holdTransportCalls(fixture, PersistenceTransportCallKind.OBSERVATION, 7)
            val revision = transportSnapshot(fixture.owner).revision
            assertEquals(expected, invokeTransportObservation(fixture.socket, method))
            assertEquals(revision + 2, transportSnapshot(fixture.owner).revision)
            fixture.owner.seal(fixture.record)
            assertEquals(expected, invokeTransportObservation(fixture.socket, method))
            val last = holdTransportCalls(fixture, PersistenceTransportCallKind.OBSERVATION, 1)
            assertTransportObservationRefused { invokeTransportObservation(fixture.socket, method) }
            releaseTransportCalls(fixture, business + observations + last)
            assertNoTransportCalls(fixture)
        }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["ONE_ARGUMENT", "TWO_ARGUMENTS"])
    fun `REAL API connect overloads consume only one available business cell`(overload: String) = TransportTestScope("REAL_SOCKET_API").use { scope ->
        val fixture = scope.socket()
        val held = holdTransportCalls(fixture, PersistenceTransportCallKind.BUSINESS, 31)
        scope.connect(fixture, oneArgument = overload == "ONE_ARGUMENT")
        assertTrue(fixture.socket.isConnected)
        assertEquals(31, transportRecordSnapshot(fixture.owner, fixture.record).activeBusiness)
        releaseTransportCalls(fixture, held)
        assertNoTransportCalls(fixture)
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(
        strings = [
            "CONNECT_NULL", "CONNECT_NULL_NEGATIVE", "BIND_NULL", "GET_OPTION_NULL", "SET_OPTION_NULL",
            "SET_VALUE_NULL", "TIMEOUT_NEGATIVE", "SEND_ZERO", "TRAFFIC_INVALID",
        ],
    )
    fun `REAL API admitted Java arguments match stock validation while refused entry takes precedence`(vector: String) =
        TransportTestScope("REAL_SOCKET_API").use { scope ->
            val fixture = scope.socket()
            val stock = scope.own(Socket())
            val expected = runCatching { invokeTransportArgumentCase(stock, vector) }
            val actual = runCatching { invokeTransportArgumentCase(fixture.socket, vector) }
            assertEquals(expected.exceptionOrNull()?.javaClass, actual.exceptionOrNull()?.javaClass)
            assertEquals(expected.exceptionOrNull()?.message, actual.exceptionOrNull()?.message)
            assertEquals(expected.isSuccess, actual.isSuccess)
            if (vector == "BIND_NULL") assertTrue(fixture.socket.isBound)
            fixture.owner.seal(fixture.record)
            assertTransportBusinessRefused { invokeTransportArgumentCase(fixture.socket, vector) }
            assertNoTransportCalls(fixture)
        }

    @Test
    fun `REAL API refused hostile address and option inputs are never inspected or rendered`() = TransportTestScope("REAL_SOCKET_API").use { scope ->
        val fixture = scope.socket()
        val address = TransportHostileAddress()
        val option = TransportHostileOption()
        fixture.owner.seal(fixture.record)
        assertTransportBusinessRefused { fixture.socket.connect(address) }
        assertTransportBusinessRefused { fixture.socket.bind(address) }
        assertTransportBusinessRefused { fixture.socket.getOption(option) }
        assertTransportBusinessRefused { fixture.socket.setOption(option, address) }
        assertEquals(0, address.renders.get())
        assertEquals(0, option.callbacks.get())
        assertEquals("TrackedPersistenceSocket(redacted)", fixture.socket.toString())
    }

    @Test
    fun `REAL API named options and generic return identity preserve meaningful positive controls`() = TransportTestScope("REAL_SOCKET_API").use { scope ->
        val fixture = scope.socket()
        val socket = fixture.socket
        val peer = scope.connect(fixture)
        socket.setTcpNoDelay(true)
        assertTrue(socket.getTcpNoDelay())
        socket.setSoLinger(false, 0)
        assertEquals(-1, socket.getSoLinger())
        socket.setOOBInline(true)
        assertTrue(socket.getOOBInline())
        socket.setSoTimeout(2_000)
        assertEquals(2_000, socket.getSoTimeout())
        socket.setSendBufferSize(4_096)
        assertTrue(socket.getSendBufferSize() >= 4_096)
        socket.setReceiveBufferSize(4_096)
        assertTrue(socket.getReceiveBufferSize() >= 4_096)
        socket.setKeepAlive(true)
        assertTrue(socket.getKeepAlive())
        socket.setTrafficClass(0)
        assertEquals(0, socket.getTrafficClass())
        socket.setReuseAddress(true)
        assertTrue(socket.getReuseAddress())
        assertSame(socket, socket.setOption(StandardSocketOptions.TCP_NODELAY, false))
        assertFalse(socket.getOption(StandardSocketOptions.TCP_NODELAY))
        peer.setOOBInline(true)
        socket.sendUrgentData(42)
        assertEquals(42, peer.getInputStream().read())
        assertNull(socket.channel)
        assertNoTransportCalls(fixture)
    }
}

private fun assertTransportApiShape(base: Class<*>, tracked: Class<*>, inherited: Set<String>, count: Int) {
    val methods = base.declaredMethods.filter { Modifier.isPublic(it.modifiers) && !Modifier.isStatic(it.modifiers) }
    assertEquals(count, methods.size)
    for (method in methods) {
        val actual = tracked.getMethod(method.name, *method.parameterTypes)
        assertSame(if (method.name in inherited) base else tracked, actual.declaringClass, method.name)
        assertEquals(method.exceptionTypes.toList(), actual.exceptionTypes.toList(), method.name)
        assertEquals(method.returnType, actual.returnType, method.name)
    }
}
