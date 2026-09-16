package me.manga.kira.backend.common.infrastructure.persistence

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer

/** Bounded synthetic PostgreSQL v3.0 messages, not a database/server implementation. */
internal class PgProtocolChannel(private val socket: Socket, private val budget: PersistenceTimeBudget) {
    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()
    private var totalBytes = 0
    private var messages = 0

    fun startup(): ByteArray {
        beginMessage()
        return payload()
    }

    fun receive(type: Char): ByteArray {
        beginMessage()
        check(read(1).single().toInt() == type.code) { "Unexpected synthetic frontend message type." }
        return payload()
    }

    fun send(type: Char, payload: ByteArray) {
        beginMessage()
        check(payload.size <= MAX_PACKET - 4) { "Synthetic outbound packet exceeded its bound." }
        charge(payload.size + 5)
        val bytes = ByteArrayOutputStream(payload.size + 5)
        DataOutputStream(bytes).use { writer ->
            writer.writeByte(type.code)
            writer.writeInt(payload.size + 4)
            writer.write(payload)
        }
        budget.remainingMillis(1)
        output.write(bytes.toByteArray())
        output.flush()
    }

    fun status(name: String, value: String) = send('S', zeroTerminated(name) + zeroTerminated(value))

    fun ready() = send('Z', byteArrayOf('I'.code.toByte()))

    fun partialReady() {
        // Intentionally omit the required transaction-status byte. The peer owner then closes this socket.
        beginMessage()
        charge(5)
        output.write(byteArrayOf('Z'.code.toByte(), 0, 0, 0, 5))
        output.flush()
    }

    fun requireEof() {
        socket.soTimeout = budget.remainingMillis(3_000).toInt()
        check(input.read() == -1) { "Synthetic peer expected client EOF." }
    }

    private fun payload(): ByteArray {
        val length = ByteBuffer.wrap(read(4)).int
        check(length in 4..MAX_PACKET) { "Synthetic inbound packet exceeded its bound." }
        return read(length - 4)
    }

    private fun read(count: Int): ByteArray {
        charge(count)
        val result = ByteArray(count)
        var offset = 0
        while (offset < count) {
            socket.soTimeout = budget.remainingMillis(3_000).toInt()
            val read = input.read(result, offset, count - offset)
            check(read > 0) { "Synthetic peer received incomplete input." }
            offset += read
        }
        return result
    }

    private fun beginMessage() {
        check(++messages <= 32) { "Synthetic peer message count exceeded its bound." }
        budget.remainingMillis(1)
    }

    private fun charge(bytes: Int) {
        check(bytes >= 0 && bytes <= MAX_TOTAL - totalBytes) { "Synthetic peer byte budget exceeded its bound." }
        totalBytes += bytes
    }

    companion object {
        private const val MAX_PACKET = 4_096
        private const val MAX_TOTAL = 32_768

        fun integer(value: Int): ByteArray = ByteBuffer.allocate(4).putInt(value).array()

        fun zeroTerminated(value: String): ByteArray = value.toByteArray(Charsets.UTF_8) + byteArrayOf(0)
    }
}
