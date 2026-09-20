package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

class TrackedPersistenceSocketHistoryTest {
    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(
        strings = [
            "NEVER_CREATED", "CLOSED_NEVER_CREATED", "CREATED_OPEN", "CREATED_CLOSED", "BOUND_OPEN",
            "BOUND_CLOSED", "CONNECTED_OPEN", "SEALED_OPEN", "CONNECTED_CLOSED",
        ],
    )
    fun `REAL API metadata histories match stock without fabricated cached local endpoints`(history: String) =
        TransportTestScope("REAL_SOCKET_API").use { scope ->
            val fixture = scope.socket()
            val stock = scope.own(Socket())
            val connected = history.startsWith("CONNECTED") || history == "SEALED_OPEN"
            val bound = history.startsWith("BOUND")
            if (history.startsWith("CREATED")) {
                stock.setReuseAddress(true)
                fixture.socket.setReuseAddress(true)
            }
            if (bound) {
                stock.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
                fixture.socket.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            }
            if (connected) {
                val listener = scope.listener()
                stock.connect(listener.localSocketAddress, 3_000)
                scope.own(listener.accept())
                fixture.socket.connect(listener.localSocketAddress, 3_000)
                scope.own(listener.accept())
            }
            val initialLocalPort = fixture.socket.localPort
            val stockLocalPort = stock.localPort
            if (history.contains("CLOSED")) {
                stock.close()
                fixture.socket.close()
            }
            if (history == "SEALED_OPEN") fixture.owner.seal(fixture.record)
            assertEquals(stock.isConnected, fixture.socket.isConnected)
            assertEquals(stock.isBound, fixture.socket.isBound)
            assertEquals(stock.isClosed, fixture.socket.isClosed)
            assertEquals(stock.inetAddress, fixture.socket.inetAddress)
            assertEquals(stock.port, fixture.socket.port)
            assertEquals(stock.remoteSocketAddress, fixture.socket.remoteSocketAddress)
            assertEquals(stock.localAddress, fixture.socket.localAddress)
            assertEquals(initialLocalPort, fixture.socket.localPort)
            assertEquals(stockLocalPort, stock.localPort)
            val endpoint = fixture.socket.localSocketAddress as InetSocketAddress?
            if (bound || connected) {
                assertTrue(initialLocalPort > 0)
                assertEquals(initialLocalPort, requireNotNull(endpoint).port)
                assertEquals(fixture.socket.localAddress, endpoint.address)
                if (history.contains("CLOSED")) assertTrue(endpoint.address.isAnyLocalAddress)
            } else {
                assertNull(endpoint)
                assertEquals(-1, fixture.socket.localPort)
                assertNull(fixture.socket.remoteSocketAddress)
            }
            assertNull(fixture.socket.channel)
            assertNoTransportCalls(fixture)
        }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["OPEN_UNCREATED", "OPEN_CREATED", "CLOSED_UNCREATED", "CLOSED_CREATED", "CACHED_CLOSED"])
    fun `REAL API supported options preserves stock creation and cache histories even after business seal`(history: String) =
        TransportTestScope("REAL_SOCKET_API").use { scope ->
            val fixture = scope.socket()
            val stock = scope.own(Socket())
            if (history == "OPEN_CREATED" || history == "CLOSED_CREATED") {
                stock.setReuseAddress(true)
                fixture.socket.setReuseAddress(true)
            }
            val prior = if (history == "CACHED_CLOSED") fixture.socket.supportedOptions() else null
            val stockPrior = if (history == "CACHED_CLOSED") stock.supportedOptions() else null
            if (history.startsWith("CLOSED") || history == "CACHED_CLOSED") {
                fixture.socket.close()
                stock.close()
            }
            fixture.owner.seal(fixture.record)
            val expected = stock.supportedOptions()
            val actual = fixture.socket.supportedOptions()
            assertEquals(expected, actual)
            assertSame(actual, fixture.socket.supportedOptions())
            if (prior != null) {
                assertSame(prior, actual)
                assertSame(stockPrior, expected)
            }
            if (history == "CLOSED_UNCREATED") assertTrue(actual.isEmpty())
            val held = holdTransportCalls(fixture, PersistenceTransportCallKind.OBSERVATION, 8)
            assertTransportObservationRefused { fixture.socket.supportedOptions() }
            releaseTransportCalls(fixture, held)
            assertSame(actual, fixture.socket.supportedOptions())
            assertNoTransportCalls(fixture)
            // Public flags/results are not used here to infer native descriptor creation or destruction.
        }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["ORDINARY", "APPROVED_DIRECT"])
    fun `REAL API each explicit constructor route remains a real socket with no raw channel escape`(route: String) =
        TransportTestScope("REAL_SOCKET_API").use { scope ->
            val fixture = scope.socket(direct = route == "APPROVED_DIRECT", role = PersistenceTransportRole.AUX_CANCEL)
            assertFalse(fixture.socket.isConnected)
            assertFalse(fixture.socket.isBound)
            assertFalse(fixture.socket.isClosed)
            assertNull(fixture.socket.channel)
            scope.connect(fixture)
            val input = fixture.socket.inputStream
            val output = fixture.socket.outputStream
            assertSame(TrackedPersistenceInputStream::class.java, input.javaClass)
            assertSame(TrackedPersistenceOutputStream::class.java, output.javaClass)
            assertSame(input, fixture.socket.inputStream)
            assertSame(output, fixture.socket.outputStream)
            assertEquals("TrackedPersistenceInputStream(redacted)", input.toString())
            assertEquals("TrackedPersistenceOutputStream(redacted)", output.toString())
            assertNull(fixture.socket.channel)
            assertNoTransportCalls(fixture)
        }
}
