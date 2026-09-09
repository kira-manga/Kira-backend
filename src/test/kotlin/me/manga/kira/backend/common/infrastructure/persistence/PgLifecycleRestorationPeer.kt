package me.manga.kira.backend.common.infrastructure.persistence

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One real opening, but no authentication/Ready response or database. No peer-write/client-close race after startup. */
internal class PgLifecycleRestorationPeer : AutoCloseable {
    private val server = AtomicReference<ServerSocket?>()
    private val accepted = AtomicReference<Socket?>()
    private val started = AtomicBoolean()
    private val closing = AtomicBoolean()
    private val startup = CountDownLatch(1)
    private val end = AtomicReference<End?>()
    private val problem = AtomicReference<Throwable?>()
    private val budget = PersistenceTimeBudget.start(45_000)
    private val actor = Thread.ofPlatform()
        .name("synthetic-lifecycle-restoration-peer")
        .inheritInheritableThreadLocals(false)
        .unstarted(::serve)
    val port: Int get() = requireNotNull(server.get()).localPort

    fun start() {
        check(!closing.get() && started.compareAndSet(false, true))
        server.set(ServerSocket())
        requireNotNull(server.get()).bind(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0), 1)
        actor.start()
    }

    fun awaitStartup(progress: () -> Unit) {
        awaitLifecycleFact {
            problem.get()?.let { throw it }
            progress()
            startup.count == 0L
        }
        progress()
        check(!closing.get() && end.get() == null && !requireNotNull(accepted.get()).isClosed)
    }

    fun diagnostic(): String = "peer_accepted=${accepted.get() != null} peer_startup=${startup.count == 0L} peer_end=${end.get()?.name ?: "UNOBSERVED"} " +
        "peer_problem_present=${problem.get() != null} peer_closing=${closing.get()} peer_actor_alive=${actor.isAlive}"

    private fun serve() {
        runCatching {
            val listener = requireNotNull(server.get())
            listener.soTimeout = budget.remainingMillis(25_000).toInt()
            accepted.set(listener.accept()) // Retain first; cleanup also covers a late accept publication.
            if (!closing.get()) readOpening(requireNotNull(accepted.get()))
        }.onFailure { if (!closing.get()) problem.compareAndSet(null, it) }
    }

    private fun readOpening(socket: Socket) {
        val packet = PgProtocolChannel(socket, budget).startup()
        check(packet.size > 4 && packet.take(4).toByteArray().contentEquals(PgProtocolChannel.integer(196_608)))
        check(packet.last() == 0.toByte())
        startup.countDown()
        // Deliberately send zero backend bytes. The real Driver cannot return a Connection from this peer.
        requireLocallyOpen(socket)
        socket.soTimeout = budget.remainingMillis(25_000).toInt()
        val observed = try {
            check(socket.getInputStream().read() == -1) { "Unexpected frontend bytes after the bounded StartupMessage." }
            End.EOF
        } catch (failure: IOException) {
            if (!PgLifecycleDisconnectEvidence.reset(failure)) throw failure
            End.EXACT_RESET
        }
        requireLocallyOpen(socket)
        check(end.compareAndSet(null, observed))
    }

    private fun requireLocallyOpen(socket: Socket) {
        check(!closing.get() && !socket.isClosed && !socket.isInputShutdown && !socket.isOutputShutdown)
    }

    fun verifyBeforeCleanup() {
        awaitLifecycleFact {
            problem.get()?.let { throw it }
            end.get() != null && !actor.isAlive
        }
        requireLocallyOpen(requireNotNull(accepted.get()))
        check(startup.count == 0L && !requireNotNull(server.get()).isClosed && actor.state === Thread.State.TERMINATED)
        println(
            "PG_LIFECYCLE_RESTORATION_PEER_VERIFIED connections=1 startup=PROTOCOL3 backend_bytes=0 " +
                "end=${requireNotNull(end.get()).name} fixture_open=true proof=PROTOCOL_PEER_NOT_POSTGRESQL",
        )
    }

    override fun close() {
        closing.set(true)
        val listener = runCatching { server.get()?.close() }
        val socket = runCatching { accepted.get()?.close() }
        val stopped = runCatching { awaitLifecycleFact { !actor.isAlive } }
        val lateSocket = runCatching { accepted.get()?.close() }
        val receipts = listOf(listener, socket, stopped, lateSocket)
        receipts.forEach { it.getOrThrow() } // Every independent cleanup above has already been attempted.
        check(server.get()?.isClosed != false && accepted.get()?.isClosed != false)
        val listenerState = if (server.get() == null) "NOT_CREATED" else "API_CLOSED"
        val socketState = if (accepted.get() == null) "NOT_ACCEPTED" else "API_CLOSED"
        val actorState = if (actor.state === Thread.State.NEW) "INERT" else "TERMINATED"
        println("PG_LIFECYCLE_RESTORATION_PEER_CLEANUP listener=$listenerState accepted=$socketState actor=$actorState all_terminated=true")
        problem.get()?.let { throw it }
    }

    private enum class End {
        EOF,
        EXACT_RESET,
    }
}
