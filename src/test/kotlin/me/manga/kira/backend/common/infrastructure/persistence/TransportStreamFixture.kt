package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/** These overrides witness whole-method dispatch, not the behavior of a native Socket stream. */
internal class TransportInputProbe(private val before: (String) -> Unit = {}) : InputStream() {
    val calls = mutableListOf<String>()

    private fun <T> invoked(method: String, value: () -> T): T {
        calls.add(method)
        before(method)
        return value()
    }

    override fun read(): Int = invoked("READ") { 65 }

    override fun read(b: ByteArray?): Int = invoked("READ_ARRAY") {
        requireNotNull(b)[0] = 66
        1
    }

    override fun read(b: ByteArray?, off: Int, len: Int): Int = invoked("READ_SLICE") {
        requireNotNull(b)[off] = 67
        1
    }

    override fun readAllBytes(): ByteArray = invoked("READ_ALL") { byteArrayOf(68, 69) }

    override fun readNBytes(len: Int): ByteArray = invoked("READ_N") { byteArrayOf(70) }

    override fun readNBytes(b: ByteArray?, off: Int, len: Int): Int = invoked("READ_N_SLICE") {
        requireNotNull(b)[off] = 71
        1
    }

    override fun skip(n: Long): Long = invoked("SKIP") { 1L }

    override fun skipNBytes(n: Long) = invoked("SKIP_N") { Unit }

    override fun available(): Int = invoked("AVAILABLE") { 9 }

    override fun transferTo(out: OutputStream?): Long = invoked("TRANSFER") {
        requireNotNull(out).write(72)
        1L
    }

    override fun mark(readlimit: Int) = invoked("MARK") { Unit }

    override fun reset() = invoked("RESET") { Unit }

    override fun markSupported(): Boolean = invoked("MARK_SUPPORTED") { true }

    override fun close() = invoked("CLOSE") { Unit }

    override fun toString(): String = error("Synthetic raw stream must not be rendered.")
}

internal class TransportOutputProbe(private val before: (String) -> Unit = {}) : OutputStream() {
    val calls = mutableListOf<String>()
    val bytes = ByteArrayOutputStream()

    private fun invoked(method: String, write: () -> Unit) {
        calls.add(method)
        before(method)
        write()
    }

    override fun write(b: Int) = invoked("WRITE") { bytes.write(b) }

    override fun write(b: ByteArray?) = invoked("WRITE_ARRAY") { bytes.write(b) }

    override fun write(b: ByteArray?, off: Int, len: Int) = invoked("WRITE_SLICE") { bytes.write(b, off, len) }

    override fun flush() = invoked("FLUSH") { error("Raw flush must not be used by the selected Socket adapter.") }

    override fun close() = invoked("CLOSE") { error("Raw close must not bypass the tracked parent.") }

    override fun toString(): String = error("Synthetic raw stream must not be rendered.")
}

internal fun invokeTransportInput(input: InputStream, method: String) {
    val bytes = ByteArray(4)
    when (method) {
        "READ" -> assertEquals(65, input.read())

        "READ_ARRAY" -> {
            assertEquals(1, input.read(bytes))
            assertEquals(66, bytes[0].toInt())
        }

        "READ_SLICE" -> {
            assertEquals(1, input.read(bytes, 1, 2))
            assertEquals(67, bytes[1].toInt())
        }

        "READ_ALL" -> assertArrayEquals(byteArrayOf(68, 69), input.readAllBytes())

        "READ_N" -> assertArrayEquals(byteArrayOf(70), input.readNBytes(3))

        "READ_N_SLICE" -> {
            assertEquals(1, input.readNBytes(bytes, 1, 2))
            assertEquals(71, bytes[1].toInt())
        }

        "SKIP" -> assertEquals(1L, input.skip(2))

        "SKIP_N" -> input.skipNBytes(2)

        "AVAILABLE" -> assertEquals(9, input.available())

        "TRANSFER" -> {
            val target = ByteArrayOutputStream()
            assertEquals(1L, input.transferTo(target))
            assertArrayEquals(byteArrayOf(72), target.toByteArray())
        }

        else -> error("Unknown synthetic input operation.")
    }
}

internal fun invokeTransportOutput(output: OutputStream, method: String) {
    when (method) {
        "WRITE" -> output.write(65)
        "WRITE_ARRAY" -> output.write(byteArrayOf(66, 67))
        "WRITE_SLICE" -> output.write(byteArrayOf(0, 68, 69, 0), 1, 2)
        else -> error("Unknown synthetic output operation.")
    }
}

internal fun invokeTransportInputArgument(input: InputStream, vector: String): Any? = when (vector) {
    "ZERO_ARRAY" -> input.read(ByteArray(0))
    "READ_NULL" -> input.read(null)
    "SLICE_NULL" -> input.read(null, 0, 0)
    "SLICE_NEGATIVE" -> input.read(ByteArray(2), -1, 1)
    "SLICE_OVERFLOW" -> input.read(ByteArray(2), 1, Int.MAX_VALUE)
    "N_ZERO" -> input.readNBytes(0)
    "N_NEGATIVE" -> input.readNBytes(-1)
    "N_NULL" -> input.readNBytes(null, 0, 0)
    "N_BOUND" -> input.readNBytes(ByteArray(2), 1, 2)
    "N_PARTIAL" -> input.readNBytes(5)
    "SKIP_NEGATIVE" -> input.skip(-1)
    "SKIP_N_NEGATIVE" -> input.skipNBytes(-1)
    "SKIP_N_EOF" -> input.skipNBytes(5)
    "TRANSFER_NULL" -> input.transferTo(null)
    else -> error("Unknown synthetic input argument.")
}

internal fun invokeTransportOutputArgument(output: OutputStream, vector: String) {
    when (vector) {
        "ZERO_ARRAY" -> output.write(ByteArray(0))
        "WRITE_NULL" -> output.write(null)
        "SLICE_NULL" -> output.write(null, 0, 0)
        "SLICE_NEGATIVE" -> output.write(ByteArray(2), -1, 1)
        "SLICE_OVERFLOW" -> output.write(ByteArray(2), 1, Int.MAX_VALUE)
        else -> error("Unknown synthetic output argument.")
    }
}
