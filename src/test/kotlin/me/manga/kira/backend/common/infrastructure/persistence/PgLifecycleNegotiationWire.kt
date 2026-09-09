package me.manga.kira.backend.common.infrastructure.persistence

import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean

/** Fixed bounded PostgreSQL framing. A socket error, fixture close, or timeout is not interchangeable with peer EOF/reset. */
internal class PgLifecycleNegotiationWire(private val socket: Socket, private val budget: PersistenceTimeBudget, private val closing: AtomicBoolean) {
    private val channel = PgProtocolChannel(socket, budget)

    fun sslRequest() {
        check(channel.startup().contentEquals(PgProtocolChannel.integer(80_877_103))) { "Expected SSLRequest, not a plaintext StartupMessage." }
    }

    fun sslResponse(value: Char) {
        check(value == 'E' || value == 'N' || value == 'S')
        budget.remainingMillis(1)
        socket.getOutputStream().apply {
            write(value.code)
            flush()
        }
    }

    fun startup() {
        val expected = PgProtocolChannel.integer(196_608) + listOf(
            "user", "fixture", "database", "fixture", "client_encoding", "UTF8", "DateStyle", "ISO", "TimeZone", "UTC",
        ).flatMap { PgProtocolChannel.zeroTerminated(it).toList() }.toByteArray() + byteArrayOf(0)
        check(channel.startup().contentEquals(expected)) { "Expected this synthetic client's exact v3 StartupMessage." }
    }

    fun rejectStartup() = errorResponse("08006")

    fun rejectAuthentication() {
        password()
        errorResponse("28000") // Invalid authorization specification: the supported fallback trigger, NOT 28P01.
    }

    fun authenticate() {
        password()
        channel.send('R', PgProtocolChannel.integer(0))
        mapOf(
            "server_version" to "17.0",
            "server_encoding" to "UTF8",
            "client_encoding" to "UTF8",
            "DateStyle" to "ISO, MDY",
            "integer_datetimes" to "on",
            "standard_conforming_strings" to "on",
            "TimeZone" to "UTC",
        ).forEach { (name, value) -> channel.status(name, value) }
        channel.send('K', PgProtocolChannel.integer(4_242) + byteArrayOf(1, 2, 3, 4))
        channel.ready()
        check(channel.receive('Q').contentEquals(PgProtocolChannel.zeroTerminated("SET application_name = '${PgProtocolPeer.APPLICATION}'")))
        channel.status("application_name", PgProtocolPeer.APPLICATION)
        channel.send('C', PgProtocolChannel.zeroTerminated("SET"))
        channel.ready()
    }

    /** Only the allow/IOException recipe injects server EOF; its client-facing input remains open for a separate witness. */
    fun injectServerEof() {
        checkOpen(outputShutdown = false)
        socket.shutdownOutput()
        checkOpen(outputShutdown = true)
    }

    fun disconnect(allowTerminate: Boolean, outputShutdown: Boolean = false) {
        checkOpen(outputShutdown)
        val first = readByte(outputShutdown)
        if (first == 'X'.code && allowTerminate) {
            val header = ByteArray(4) { readByte(outputShutdown).also { check(it >= 0) }.toByte() }
            check(header.contentEquals(PgProtocolChannel.integer(4)))
            check(readByte(outputShutdown) == -1)
        } else {
            check(first == -1) { "Unexpected plaintext traffic instead of client-originated EOF/reset." }
        }
        checkOpen(outputShutdown)
    }

    private fun password() {
        channel.send('R', PgProtocolChannel.integer(3))
        check(channel.receive('p').contentEquals(PgProtocolChannel.zeroTerminated(PgProtocolPeer.PASSWORD)))
    }

    private fun errorResponse(state: String) {
        check(state == "08006" || state == "28000")
        channel.send(
            'E',
            byteArrayOf('S'.code.toByte()) + PgProtocolChannel.zeroTerminated("FATAL") +
                byteArrayOf('C'.code.toByte()) + PgProtocolChannel.zeroTerminated(state) +
                byteArrayOf('M'.code.toByte()) + PgProtocolChannel.zeroTerminated("Synthetic negotiation refusal.") + byteArrayOf(0),
        )
    }

    private fun readByte(outputShutdown: Boolean): Int {
        checkOpen(outputShutdown)
        socket.soTimeout = budget.remainingMillis(3_000).toInt()
        return try {
            socket.getInputStream().read()
        } catch (failure: SocketException) {
            check(PgLifecycleDisconnectEvidence.reset(failure)) { "Unclassified socket failure is not client EOF/reset." }
            checkOpen(outputShutdown)
            -1
        }
    }

    private fun checkOpen(outputShutdown: Boolean) {
        check(!closing.get() && !socket.isClosed && !socket.isInputShutdown && socket.isOutputShutdown == outputShutdown) {
            "Fixture cleanup or a local input shutdown cannot witness client disposal."
        }
    }
}
