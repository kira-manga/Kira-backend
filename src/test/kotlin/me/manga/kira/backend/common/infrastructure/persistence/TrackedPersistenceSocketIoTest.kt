package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.net.SocketException

class TrackedPersistenceSocketIoTest {
    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["READ", "READ_ARRAY", "READ_SLICE", "READ_ALL", "READ_N", "READ_N_SLICE", "SKIP", "SKIP_N", "AVAILABLE", "TRANSFER"])
    fun `REAL API every whole input method preserves loopback bytes partial data and EOF`(method: String) = TransportTestScope("REAL_SOCKET_API").use { scope ->
        val fixture = scope.socket()
        val peer = scope.connect(fixture)
        val bytes = byteArrayOf(1, 2, 3)
        peer.outputStream.write(bytes)
        peer.shutdownOutput()
        val input = fixture.socket.inputStream
        when (method) {
            "READ" -> assertEquals(1, input.read())

            "READ_ARRAY" -> {
                val received = ByteArray(3)
                val count = input.read(received)
                assertTrue(count in 1..3)
                assertArrayEquals(bytes.copyOf(count), received.copyOf(count))
                assertArrayEquals(bytes.copyOfRange(count, 3), input.readAllBytes())
            }

            "READ_SLICE" -> {
                val received = ByteArray(5)
                val count = input.read(received, 1, 3)
                assertTrue(count in 1..3)
                assertArrayEquals(bytes.copyOf(count), received.copyOfRange(1, count + 1))
                assertArrayEquals(bytes.copyOfRange(count, 3), input.readAllBytes())
            }

            "READ_ALL" -> assertArrayEquals(bytes, input.readAllBytes())

            "READ_N" -> assertArrayEquals(bytes, input.readNBytes(5))

            "READ_N_SLICE" -> {
                val received = ByteArray(6)
                assertEquals(3, input.readNBytes(received, 1, 4))
                assertArrayEquals(bytes, received.copyOfRange(1, 4))
            }

            "SKIP" -> {
                assertEquals(2L, input.skip(2))
                assertEquals(3, input.read())
            }

            "SKIP_N" -> {
                input.skipNBytes(2)
                assertEquals(3, input.read())
            }

            "AVAILABLE" -> awaitTransportTestFact { input.available() == 3 }

            "TRANSFER" -> {
                val target = ByteArrayOutputStream()
                assertEquals(3L, input.transferTo(target))
                assertArrayEquals(bytes, target.toByteArray())
            }
        }
        input.readAllBytes()
        assertEquals(-1, input.read())
        assertEquals(0, input.read(ByteArray(0)))
        assertArrayEquals(ByteArray(0), input.readNBytes(0))
        assertNoTransportCalls(fixture)
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["WRITE", "WRITE_ARRAY", "WRITE_SLICE"])
    fun `REAL API each whole output overload delivers its exact selected bytes`(method: String) = TransportTestScope("REAL_SOCKET_API").use { scope ->
        val fixture = scope.socket()
        val peer = scope.connect(fixture)
        val output = fixture.socket.outputStream
        invokeTransportOutput(output, method)
        output.flush()
        fixture.socket.shutdownOutput()
        val expected = when (method) {
            "WRITE" -> byteArrayOf(65)
            "WRITE_ARRAY" -> byteArrayOf(66, 67)
            else -> byteArrayOf(68, 69)
        }
        assertArrayEquals(expected, peer.inputStream.readAllBytes())
        assertTrue(fixture.socket.isOutputShutdown)
        assertFalse(fixture.socket.isClosed)
        assertNoTransportCalls(fixture)
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["INPUT_SHUTDOWN", "OUTPUT_SHUTDOWN", "CLOSED"])
    fun `REAL API cached stream acquisition revalidates every half shutdown and close`(state: String) = TransportTestScope("REAL_SOCKET_API").use { scope ->
        val fixture = scope.socket()
        scope.connect(fixture)
        val input = fixture.socket.inputStream
        val output = fixture.socket.outputStream
        assertSame(input, fixture.socket.inputStream)
        assertSame(output, fixture.socket.outputStream)
        when (state) {
            "INPUT_SHUTDOWN" -> {
                fixture.socket.shutdownInput()
                val failure = assertThrows(SocketException::class.java) { fixture.socket.inputStream }
                assertEquals("Socket input is shutdown", failure.message)
                assertEquals(-1, input.read())
                assertSame(output, fixture.socket.outputStream)
                assertTrue(fixture.socket.isInputShutdown)
            }

            "OUTPUT_SHUTDOWN" -> {
                fixture.socket.shutdownOutput()
                val failure = assertThrows(SocketException::class.java) { fixture.socket.outputStream }
                assertEquals("Socket output is shutdown", failure.message)
                assertSame(input, fixture.socket.inputStream)
                assertTrue(fixture.socket.isOutputShutdown)
            }

            else -> {
                fixture.socket.close()
                assertTransportBusinessRefused { fixture.socket.inputStream }
                assertTransportBusinessRefused { fixture.socket.outputStream }
                assertTransportBusinessRefused { input.read(ByteArray(0)) }
                assertTransportBusinessRefused { output.write(ByteArray(0)) }
            }
        }
        assertNoTransportCalls(fixture)
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["DIRECT", "INPUT", "OUTPUT"])
    fun `REAL API direct and stream close aliases share exactly the first completed producer`(alias: String) =
        TransportTestScope("REAL_SOCKET_API").use { scope ->
            val fixture = scope.socket()
            scope.connect(fixture)
            val input = fixture.socket.inputStream
            val output = fixture.socket.outputStream
            val revision = transportSnapshot(fixture.owner).revision
            when (alias) {
                "DIRECT" -> fixture.socket.close()
                "INPUT" -> input.close()
                else -> output.close()
            }
            assertTrue(fixture.socket.isClosed)
            assertEquals(PersistenceTransportClosePhase.API_CLOSE_ACKNOWLEDGED, transportRecordSnapshot(fixture.owner, fixture.record).firstClose)
            assertEquals(revision + 2, transportSnapshot(fixture.owner).revision)
            fixture.socket.close()
            input.close()
            output.close()
            assertEquals(revision + 2, transportSnapshot(fixture.owner).revision)
            assertNoTransportCalls(fixture)
        }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["DIRECT", "INPUT", "OUTPUT"])
    fun `REAL API close aliases unblock an actually entered platform read and preserve outer accounting`(alias: String) =
        TransportTestScope("REAL_SOCKET_API").use { scope ->
            val fixture = scope.socket()
            scope.connect(fixture)
            fixture.socket.soTimeout = 0
            val input = fixture.socket.inputStream
            val output = fixture.socket.outputStream
            val reader = scope.launch { runCatching { input.read() } }
            awaitTransportSocketRead(reader.thread)
            assertEquals(1, transportRecordSnapshot(fixture.owner, fixture.record).activeBusiness)
            when (alias) {
                "DIRECT" -> fixture.socket.close()
                "INPUT" -> input.close()
                else -> output.close()
            }
            assertTrue(reader.join().exceptionOrNull() is SocketException)
            assertEquals(PersistenceTransportClosePhase.API_CLOSE_ACKNOWLEDGED, transportRecordSnapshot(fixture.owner, fixture.record).firstClose)
            assertNoTransportCalls(fixture)
        }
}
