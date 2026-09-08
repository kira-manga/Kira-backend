package me.manga.kira.backend.common.infrastructure.persistence

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean

/** All three actors and both socket identities are retained before start/connect. No server EOF fabricates client EOF. */
internal class PgLifecycleDatabaseRelaySession(
    private val client: Socket,
    private val host: String,
    private val port: Int,
    private val register: (PgLifecycleDatabaseRelaySession, ByteArray) -> Unit,
) : AutoCloseable {
    val state = PgLifecycleDatabaseRelayState()
    private val upstream = Socket()
    private val started = AtomicBoolean()
    private val coordinator = Thread.ofPlatform().name("w03-database-relay-session").unstarted { guarded(::run) }
    private val clientPump = Thread.ofPlatform().name("w03-database-relay-client").unstarted { guarded(::fromClient) }
    private val serverPump = Thread.ofPlatform().name("w03-database-relay-server").unstarted { guarded(::fromServer) }

    fun start() {
        check(started.compareAndSet(false, true))
        coordinator.start()
    }

    private fun run() {
        client.soTimeout = 15_000
        client.tcpNoDelay = true
        val startup = PgLifecycleDatabaseWire.startup(DataInputStream(client.getInputStream()))
        register(this, startup)
        upstream.soTimeout = 15_000
        upstream.tcpNoDelay = true
        upstream.connect(InetSocketAddress(host, port), 1_000)
        upstream.getOutputStream().apply {
            write(startup)
            flush()
        }
        clientPump.start()
        serverPump.start()
        val deadline = PgLifecycleDatabaseDeadline(20_000)
        waitActor(clientPump, deadline)
        waitActor(serverPump, deadline)
        state.completed.set(true)
    }

    private fun fromClient() {
        val input = DataInputStream(client.getInputStream())
        val output = DataOutputStream(upstream.getOutputStream())
        copyClient(input, output)
        if (state.clientEnd.get() != null && !state.fixtureClosing.get()) {
            state.propagatingUpstreamClose()
            upstream.close() // Never executed before the client-originated receipt above.
        }
    }

    private fun copyClient(input: DataInputStream, output: DataOutputStream) {
        var bytes = 0
        var messages = 0
        while (!state.fixtureClosing.get()) {
            val message = readClient(input) ?: return
            bytes += message.bytes.size + 5
            check(++messages <= 256 && bytes <= 1_048_576)
            PgLifecycleDatabaseWire.sqlFingerprint(message)?.let { fingerprint ->
                check(state.statements.size < 4)
                state.statements.add(fingerprint)
            }
            if (message.type == 'X'.code) {
                check(message.bytes.isEmpty() && state.frontendTerminate.compareAndSet(false, true))
            }
            PgLifecycleDatabaseWire.write(output, message)
        }
    }

    private fun readClient(input: DataInputStream): PgLifecycleDatabaseWire.Message? {
        var ending = PgLifecycleDatabaseClientEnd.EOF
        // Deliberately only the client INPUT operation is caught here. An upstream write/reset cannot manufacture this receipt.
        val message = try {
            if (state.auxiliary) {
                check(input.read() == -1) { "Unexpected data after the bounded CancelRequest." }
                null
            } else {
                PgLifecycleDatabaseWire.read(input)
            }
        } catch (_: EOFException) {
            null
        } catch (failure: SocketException) {
            if (state.fixtureClosing.get() || client.isClosed) throw failure
            ending = PgLifecycleDatabaseClientEnd.RESET
            null
        }
        if (message == null && !state.fixtureClosing.get()) state.clientOriginatedEnd(ending)
        return message
    }

    private fun fromServer() {
        val input = DataInputStream(upstream.getInputStream())
        val output = DataOutputStream(client.getOutputStream())
        try {
            if (state.auxiliary) {
                check(input.read() == -1) { "Unexpected CancelRequest response." }
            } else {
                copyServer(input, output)
            }
        } catch (failure: SocketException) {
            if (state.clientEnd.get() == null && !state.fixtureClosing.get()) throw failure
        } catch (failure: EOFException) {
            if (state.clientEnd.get() == null && !state.fixtureClosing.get()) throw failure
        }
        // In particular do not close client here. Its own EOF/reset is required even when PostgreSQL closes first.
    }

    private fun copyServer(input: DataInputStream, output: DataOutputStream) {
        var bytes = 0
        var messages = 0
        while (!state.fixtureClosing.get()) {
            val message = PgLifecycleDatabaseWire.read(input)
            if (message == null) {
                check(state.clientEnd.get() != null || state.frontendTerminate.get() || state.errorState.get() == "28P01") {
                    "Unexpected server-first EOF must not masquerade as the expected constructor failure."
                }
                return
            }
            bytes += message.bytes.size + 5
            check(++messages <= 256 && bytes <= 1_048_576)
            if (!beforeServerMessage(message)) return
            PgLifecycleDatabaseWire.write(output, message)
        }
    }

    private fun beforeServerMessage(message: PgLifecycleDatabaseWire.Message): Boolean {
        when (message.type.toChar()) {
            'R' -> {
                check(state.authentication.size < 4)
                state.authentication.add(message.integer())
            }

            'K' -> {
                check(message.bytes.size == 8 && state.backendPid.compareAndSet(0, message.integer()) && state.backendPid.get() > 0)
            }

            'Z' -> if (state.gate.get() == null) {
                check(message.bytes.contentEquals(byteArrayOf('I'.code.toByte())))
                check(state.authentication == listOf(10, 11, 12, 0) && state.backendPid.get() > 0)
                return state.hold(PgLifecycleDatabaseGate.AUTHENTICATED_READY)
            }

            'E' -> {
                check(state.errorState.compareAndSet(null, PgLifecycleDatabaseWire.sqlState(message)))
                if (state.gate.get() == null) {
                    check(state.errorState.get() == "28P01" && state.authentication == listOf(10, 11) && state.backendPid.get() == 0)
                    return state.hold(PgLifecycleDatabaseGate.AUTHENTICATION_REFUSAL)
                }
            }
        }
        return !state.fixtureClosing.get()
    }

    fun actorsEnded(): Boolean = listOf(coordinator, clientPump, serverPump).none { it.isAlive }

    private fun guarded(operation: () -> Unit) {
        runCatching(operation).onFailure { failure ->
            if (!state.fixtureClosing.get()) state.failure.compareAndSet(null, failure)
        }
    }

    override fun close() {
        state.cleanupRelease()
        val sockets = listOf(client, upstream).map { runCatching { it.close() } }
        val deadline = PgLifecycleDatabaseDeadline(5_000)
        val actors = listOf(coordinator, clientPump, serverPump).map { runCatching { waitActor(it, deadline) } }
        (sockets + actors).forEach { it.getOrThrow() }
        check(actorsEnded())
    }

    private fun waitActor(thread: Thread, deadline: PgLifecycleDatabaseDeadline) {
        while (thread.isAlive) thread.join(deadline.millis(100))
    }
}
