package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger

class TrackedPersistenceTransferTest {
    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["READ_ALL", "READ_N", "READ_N_SLICE", "SKIP_N", "TRANSFER"])
    fun `MODEL whole bulk calls remain active in a held tail after their last raw leaf read`(method: String) = TransportTestScope("MODEL").use { scope ->
        val fixture = scope.socket()
        val gate = scope.gate()
        val raw = TransportTailInput(gate)
        val input = TrackedPersistenceInputStream(raw, fixture.modelBinding(), fixture.socket)
        val worker = scope.launch {
            when (method) {
                "READ_ALL" -> assertArrayEquals(byteArrayOf(1, 2), input.readAllBytes())

                "READ_N" -> assertArrayEquals(byteArrayOf(1, 2), input.readNBytes(2))

                "READ_N_SLICE" -> {
                    val target = ByteArray(4)
                    assertEquals(2, input.readNBytes(target, 1, 2))
                    assertArrayEquals(byteArrayOf(0, 1, 2, 0), target)
                }

                "SKIP_N" -> input.skipNBytes(2)

                "TRANSFER" -> {
                    val target = ByteArrayOutputStream()
                    assertEquals(2L, input.transferTo(target))
                    assertArrayEquals(byteArrayOf(1, 2), target.toByteArray())
                }
            }
            true
        }
        gate.awaitEntered()
        assertEquals(1, raw.leafReads.get())
        assertEquals(1, transportRecordSnapshot(fixture.owner, fixture.record).activeBusiness)
        fixture.socket.close()
        assertEquals(PersistenceTransportClosePhase.API_CLOSE_ACKNOWLEDGED, transportRecordSnapshot(fixture.owner, fixture.record).firstClose)
        assertEquals(1, transportRecordSnapshot(fixture.owner, fixture.record).activeBusiness)
        assertTransportBusinessRefused { input.available() }
        gate.release()
        assertTrue(worker.join())
        assertNoTransportCalls(fixture)
        // This deliberate tail injection does not instrument real JDK array copies or native I/O.
    }

    @Test
    fun `REAL API transfer destination survives first close and same thread reentry is freshly refused`() = TransportTestScope("REAL_SOCKET_API").use { scope ->
        val fixture = scope.socket()
        val peer = scope.connect(fixture)
        val payload = byteArrayOf(5, 6, 7)
        peer.outputStream.write(payload)
        peer.shutdownOutput()
        val input = fixture.socket.inputStream
        val gate = scope.gate()
        val destination = ByteArrayOutputStream()
        val callbacks = AtomicInteger()
        val reentries = AtomicInteger()
        val target = object : OutputStream() {
            override fun write(b: Int) = error("Expected selected bulk transfer destination.")

            override fun write(b: ByteArray, off: Int, len: Int) {
                destination.write(b, off, len)
                callbacks.incrementAndGet()
                gate.hold()
                assertTransportBusinessRefused { fixture.socket.getSoTimeout() }
                reentries.incrementAndGet()
            }
        }
        val worker = scope.launch { runCatching { input.transferTo(target) } }
        gate.awaitEntered()
        assertEquals(1, callbacks.get())
        assertEquals(1, transportRecordSnapshot(fixture.owner, fixture.record).activeBusiness)
        fixture.socket.close()
        assertEquals(PersistenceTransportClosePhase.API_CLOSE_ACKNOWLEDGED, transportRecordSnapshot(fixture.owner, fixture.record).firstClose)
        assertEquals(1, transportRecordSnapshot(fixture.owner, fixture.record).activeBusiness)
        assertTrue(worker.thread.isAlive)
        gate.release()
        val result = worker.join()
        if (result.isFailure) assertTrue(result.exceptionOrNull() is IOException) else assertEquals(destination.size().toLong(), result.getOrThrow())
        assertEquals(1, reentries.get())
        assertTrue(destination.size() in 1..3)
        assertArrayEquals(payload.copyOf(destination.size()), destination.toByteArray())
        assertNoTransportCalls(fixture)
    }
}

private class TransportTailInput(private val gate: TransportTestGate) : InputStream() {
    val leafReads = AtomicInteger()

    override fun read(): Int {
        leafReads.incrementAndGet()
        return -1
    }

    private fun tail(): ByteArray {
        assertEquals(-1, read())
        val bytes = byteArrayOf(1, 2)
        gate.hold()
        return bytes
    }

    override fun readAllBytes(): ByteArray = tail()

    override fun readNBytes(len: Int): ByteArray = tail()

    override fun readNBytes(b: ByteArray, off: Int, len: Int): Int {
        tail().copyInto(b, off)
        return 2
    }

    override fun skipNBytes(n: Long) {
        tail()
    }

    override fun transferTo(out: OutputStream): Long {
        out.write(tail())
        return 2
    }
}
