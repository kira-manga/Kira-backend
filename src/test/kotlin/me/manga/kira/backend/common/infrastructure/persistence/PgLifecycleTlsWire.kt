package me.manga.kira.backend.common.infrastructure.persistence

import java.io.EOFException
import java.io.IOException
import java.net.Socket
import java.net.SocketException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

/** Bounded wire witnesses. A peer cleanup close is never counted as a client EOF/reset. */
internal class PgLifecycleTlsWire(private val raw: Socket, private val budget: PersistenceTimeBudget) {
    private val input = raw.getInputStream()
    private var bytes = 0
    private var records = 0

    fun readClientHello(): ByteArray {
        val type = requireNotNull(first()) { "Client closed before sending a real TLS ClientHello." }
        check(type == 22)
        val header = read(4)
        val hello = record(header)
        check(hello.size >= 42 && hello[0].toInt() == 1)
        val helloLength = ((hello[1].toInt() and 255) shl 16) or ((hello[2].toInt() and 255) shl 8) or (hello[3].toInt() and 255)
        check(helloLength == hello.size - 4) { "Synthetic fixture expected one complete ClientHello record." }
        return byteArrayOf(type.toByte()) + header + hello
    }

    fun sendPartialHandshakeAfterClientHello() {
        readClientHello()
        // Real client handshake input, then an incomplete TLS record/ServerHello. This is not an E/N negotiation test.
        raw.getOutputStream().apply {
            write(byteArrayOf(22, 3, 3, 0, 42, 2, 0, 0))
            flush()
        }
        raw.shutdownOutput() // Inject server EOF, but retain the readable raw socket for the client's independent disconnect.
        check(raw.isOutputShutdown && !raw.isClosed && !raw.isInputShutdown)
    }

    fun awaitRawDisconnect() {
        while (true) {
            val type = first() ?: return
            check(type == 21 || type == 20) { "Unexpected application data or plaintext downgrade after TLS failure." }
            val payload = record()
            if (type == 20) check(payload.contentEquals(byteArrayOf(1)))
        }
    }

    fun awaitFailedHandshakeDisconnect(encrypted: SSLSocket) {
        // Pinned OpenJDK21 fatal initial-handshake shutdown already closed this autoClose=false TLS layer.
        // Its raw socket must still be untouched; an SSL/socket exception alone is not a disconnect witness.
        check(encrypted.isClosed && !raw.isClosed && !raw.isInputShutdown && !raw.isOutputShutdown)
        encrypted.close() // Documented layer-close barrier; already-closed SSLSocketImpl.close returns without I/O.
        check(encrypted.isClosed && !raw.isClosed && !raw.isInputShutdown && !raw.isOutputShutdown)
        awaitRawDisconnect()
        check(!raw.isClosed && !raw.isInputShutdown && !raw.isOutputShutdown)
    }

    fun awaitEncryptedDisconnect(encrypted: SSLSocket, allowTerminate: Boolean = true) {
        check(!encrypted.isClosed && !encrypted.isInputShutdown && !raw.isClosed && !raw.isInputShutdown)
        try {
            encrypted.soTimeout = budget.remainingMillis(3_000).toInt()
            val clear = encrypted.getInputStream()
            val type = clear.read()
            if (type == 'X'.code && allowTerminate) {
                check(clear.readNBytes(4).contentEquals(PgProtocolChannel.integer(4)))
                check(clear.read() == -1)
            } else {
                check(type == -1) { "Unexpected business traffic on an opaque TLS candidate." }
            }
        } catch (failure: IOException) {
            // This single owner has not closed/half-closed the layer or raw, and no cleanup is running.
            // Stock JSSE preserves SocketException and wraps EOF separately. Other TLS failures are not EOF evidence.
            check(isEofOrReset(failure) && !raw.isClosed && !raw.isInputShutdown) {
                "TLS disconnect was not observed: ${failure.javaClass.simpleName}"
            }
        }
        // The layered-socket contract forbids raw reads before SSLSocket.close returns. Client TLS closure
        // was observed above; autoClose=false leaves this separate raw EOF/reset witness fixture-owned and open.
        encrypted.close()
        awaitRawDisconnect()
    }

    private fun isEofOrReset(failure: IOException): Boolean {
        var cursor: Throwable? = failure
        repeat(4) {
            when (val current = cursor) {
                is EOFException, is SocketException -> return true
                is SSLException -> cursor = current.cause
                else -> return false
            }
        }
        return false
    }

    private fun first(): Int? {
        check(!raw.isClosed && !raw.isInputShutdown) { "Locally closed fixture socket cannot witness client disposal." }
        raw.soTimeout = budget.remainingMillis(3_000).toInt()
        val type = try {
            input.read()
        } catch (failure: SocketException) {
            check(!raw.isClosed && !raw.isInputShutdown) { "Local socket cleanup masked a client disconnect: ${failure.javaClass.simpleName}" }
            -1
        }
        if (type == -1) return null
        charge(1)
        return type
    }

    private fun record(header: ByteArray = read(4)): ByteArray {
        check(++records <= 8) { "Synthetic TLS record count exceeded its bound." }
        check(header[0].toInt() == 3 && header[1].toInt() in 0..3) { "Expected a TLS record, not PostgreSQL plaintext." }
        val length = ((header[2].toInt() and 255) shl 8) or (header[3].toInt() and 255)
        check(length in 1..18_432) { "Synthetic TLS record exceeded its bound." }
        return read(length)
    }

    private fun read(count: Int): ByteArray {
        charge(count)
        val result = ByteArray(count)
        var offset = 0
        while (offset < count) {
            raw.soTimeout = budget.remainingMillis(3_000).toInt()
            val received = input.read(result, offset, count - offset)
            check(received > 0) { "Incomplete synthetic TLS record." }
            offset += received
        }
        return result
    }

    private fun charge(count: Int) {
        check(count in 0..(32_768 - bytes)) { "Synthetic TLS byte budget exceeded its bound." }
        bytes += count
    }
}
