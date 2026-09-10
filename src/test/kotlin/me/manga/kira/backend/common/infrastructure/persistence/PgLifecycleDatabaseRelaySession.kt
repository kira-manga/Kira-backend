package me.manga.kira.backend.common.infrastructure.persistence

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicReference

private enum class PgLifecycleDatabaseSessionStart {
    NEW,
    ENTERED,
    ENDED,
    CLOSED_BEFORE_START,
}

/** All three actors and both socket identities are retained before start/connect. No server EOF fabricates client EOF. */
internal class PgLifecycleDatabaseRelaySession(
    private val client: Socket,
    private val host: String,
    private val port: Int,
    case: PgLifecycleDatabaseCase,
    acceptedIndex: Int? = null,
    upstreamSocket: Socket? = null,
    private val register: (PgLifecycleDatabaseRelaySession, ByteArray) -> Unit,
) : AutoCloseable {
    val state = PgLifecycleDatabaseRelayState(case, acceptedIndex)

    // Only local capture controls supply an inert socket. The real relay constructs Socket at the original point.
    private val upstream = upstreamSocket ?: Socket()
    private val startPhase = AtomicReference(PgLifecycleDatabaseSessionStart.NEW)
    private val coordinator = Thread.ofPlatform().name("w03-database-relay-session").unstarted(::run)
    private val clientPump = Thread.ofPlatform().name("w03-database-relay-client").unstarted {
        guarded(PgLifecycleDatabaseRelayStage.CLIENT_PUMP, ::fromClient)
    }
    private val serverPump = Thread.ofPlatform().name("w03-database-relay-server").unstarted {
        guarded(PgLifecycleDatabaseRelayStage.SERVER_PUMP, ::fromServer)
    }

    fun start() {
        if (!startPhase.compareAndSet(PgLifecycleDatabaseSessionStart.NEW, PgLifecycleDatabaseSessionStart.ENTERED)) {
            check(startPhase.get() === PgLifecycleDatabaseSessionStart.CLOSED_BEFORE_START && state.fixtureClosing.get())
            return
        }
        try {
            coordinator.start()
        } finally {
            startPhase.set(PgLifecycleDatabaseSessionStart.ENDED)
        }
    }

    private fun run() {
        // This cursor belongs only to the coordinator. Neither pump can race to relabel its capture span.
        var stage = PgLifecycleDatabaseRelayStage.CLIENT_CONFIGURE
        runCatching {
            if (state.fixtureClosing.get()) return
            client.soTimeout = 15_000
            client.tcpNoDelay = true
            stage = PgLifecycleDatabaseRelayStage.STARTUP_READ
            val startup = PgLifecycleDatabaseWire.startup(DataInputStream(client.getInputStream()))
            if (state.fixtureClosing.get()) return
            stage = PgLifecycleDatabaseRelayStage.REGISTER
            register(this, startup)
            if (state.fixtureClosing.get()) return
            stage = PgLifecycleDatabaseRelayStage.UPSTREAM_CONFIGURE
            upstream.soTimeout = 15_000
            upstream.tcpNoDelay = true
            stage = PgLifecycleDatabaseRelayStage.UPSTREAM_CONNECT
            upstream.connect(InetSocketAddress(host, port), 1_000)
            stage = PgLifecycleDatabaseRelayStage.STARTUP_FORWARD
            upstream.getOutputStream().apply {
                write(startup)
                flush()
            }
            stage = PgLifecycleDatabaseRelayStage.PUMP_START
            clientPump.start()
            serverPump.start()
            stage = PgLifecycleDatabaseRelayStage.ACTOR_JOIN
            val deadline = PgLifecycleDatabaseDeadline(20_000)
            waitActor(clientPump, deadline)
            waitActor(serverPump, deadline)
            state.completed.set(true)
        }.onFailure {
            state.captureFailure(stage, it)
        }
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
                if (fingerprint === PgLifecycleDatabaseSql.ROLE) state.role.request(message)
            }
            if (message.type == 'X'.code) {
                check(message.bytes.isEmpty() && state.frontendTerminate.compareAndSet(false, true))
            }
            PgLifecycleDatabaseWire.write(output, message)
        }
    }

    private fun readClient(input: DataInputStream): PgLifecycleDatabaseWire.Message? {
        if (state.fixtureClosing.get()) return null
        check(!client.isClosed && !client.isInputShutdown)
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
            if (state.fixtureClosing.get() || client.isClosed || client.isInputShutdown) throw failure
            if (!PgLifecycleDatabaseDisconnectEvidence.reset(failure)) throw failure
            ending = PgLifecycleDatabaseClientEnd.RESET
            null
        }
        if (message == null && !state.fixtureClosing.get()) {
            check(!client.isClosed && !client.isInputShutdown)
            state.clientOriginatedEnd(ending)
        }
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
            requireIndependentClientEnd(failure)
        } catch (failure: EOFException) {
            requireIndependentClientEnd(failure)
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
            val firstReady = message.type == 'Z'.code && state.gate.get() == null
            if (!beforeServerMessage(message)) return
            if (firstReady) {
                if (!forwardFirstReady(output, message)) return
            } else {
                PgLifecycleDatabaseWire.write(output, message)
                state.lastServerWriteNanos.set(System.nanoTime())
                state.role.response(message) // Only a successfully forwarded complete role response earns its wire receipt.
            }
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
                state.readyFault.observeReady(state.lastServerWriteNanos.get())
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

    private fun forwardFirstReady(output: DataOutputStream, message: PgLifecycleDatabaseWire.Message): Boolean {
        val frame = PgLifecycleDatabaseWire.readyFrame(message)
        val fault = state.readyFault
        fault.beginDelivery()
        return when (fault.kind) {
            PgLifecycleDatabaseReadyKind.COMPLETE -> {
                output.write(frame)
                output.flush()
                fault.wrote(0, frame.size)
                true
            }

            PgLifecycleDatabaseReadyKind.TRUNCATED -> {
                output.write(frame, 0, 3)
                output.flush()
                fault.wrote(0, 3)
                state.outputHalfCloseInvoked()
                client.shutdownOutput() // Never close client input or upstream to manufacture a candidate EOF receipt.
                fault.outputHalfCloseEnded.set(true)
                false
            }

            PgLifecycleDatabaseReadyKind.IDLE -> {
                // No Ready byte, progress, socket close, or fabricated timeout observation.
                false
            }

            PgLifecycleDatabaseReadyKind.PROGRESS, PgLifecycleDatabaseReadyKind.LATE -> progressReady(output, frame)
        }
    }

    private fun progressReady(output: DataOutputStream, frame: ByteArray): Boolean {
        val fault = state.readyFault
        for (offset in 0..4) {
            if (offset > 0 && !fault.pauseBeforeNextByte()) return false
            if (state.fixtureClosing.get() || state.clientEnd.get() != null) return false
            output.writeByte(frame[offset].toInt())
            output.flush()
            fault.wrote(offset, 1)
        }
        if (fault.kind === PgLifecycleDatabaseReadyKind.PROGRESS || !fault.awaitFinal()) return false
        check(!state.fixtureClosing.get() && state.clientEnd.get() == null)
        output.writeByte(frame[5].toInt()) // The authentic payload byte, released only after the parent's exact after-expiry witness.
        output.flush()
        fault.wrote(5, 1)
        return true
    }

    private fun requireIndependentClientEnd(failure: Exception) {
        if (state.fixtureClosing.get() || state.clientEnd.get() != null) return
        // A write can race the independent input pump's EOF. Wait boundedly for that input receipt; never create it from this failure.
        state.readyFault.awaitClientEnd(1_000)
        if (!state.fixtureClosing.get() && state.clientEnd.get() == null) throw failure
    }

    fun actorsEnded(): Boolean = startPhase.get() in setOf(
        PgLifecycleDatabaseSessionStart.ENDED,
        PgLifecycleDatabaseSessionStart.CLOSED_BEFORE_START,
    ) && listOf(coordinator, clientPump, serverPump).none { it.isAlive }

    /** Called only by this retained coordinator after late predecessor registration; never joins itself or opens the upstream socket. */
    fun cleanupBeforeConnect() {
        check(Thread.currentThread() === coordinator && clientPump.state === Thread.State.NEW && serverPump.state === Thread.State.NEW)
        state.cleanupRelease()
        val sockets = listOf(client, upstream).mapIndexed { index, socket ->
            runCatching { socket.close() }.onFailure {
                val stage = if (index == 0) PgLifecycleDatabaseRelayStage.CLEANUP_CLIENT_CLOSE else PgLifecycleDatabaseRelayStage.CLEANUP_UPSTREAM_CLOSE
                state.captureFailure(stage, it, retainDuringCleanup = true)
            }
        }
        sockets.forEach { it.getOrThrow() }
    }

    private fun guarded(stage: PgLifecycleDatabaseRelayStage, operation: () -> Unit) {
        runCatching(operation).onFailure { failure ->
            state.captureFailure(stage, failure)
        }
    }

    override fun close() {
        state.cleanupRelease()
        startPhase.compareAndSet(PgLifecycleDatabaseSessionStart.NEW, PgLifecycleDatabaseSessionStart.CLOSED_BEFORE_START)
        val sockets = listOf(client, upstream).map { runCatching { it.close() } }
        val deadline = PgLifecycleDatabaseDeadline(5_000)
        val start = runCatching { while (startPhase.get() === PgLifecycleDatabaseSessionStart.ENTERED) deadline.pause() }
        val actors = listOf(coordinator, clientPump, serverPump).map { runCatching { waitActor(it, deadline) } }
        (sockets + start + actors).forEach { it.getOrThrow() }
        check(actorsEnded())
    }

    private fun waitActor(thread: Thread, deadline: PgLifecycleDatabaseDeadline) {
        while (thread.isAlive) thread.join(deadline.millis(100))
    }
}
