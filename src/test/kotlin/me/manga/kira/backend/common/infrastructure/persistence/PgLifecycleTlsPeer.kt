package me.manga.kira.backend.common.infrastructure.persistence

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext

internal enum class PgLifecycleTlsMode {
    MATCHED,
    WRONG_CA,
    WRONG_HOST,
    PARTIAL_HANDSHAKE,
}

/** Two armed attempts plus an unexpected-connection guard. Real TLS, but deliberately not a PostgreSQL server. */
internal class PgLifecycleTlsPeer(mode: PgLifecycleTlsMode, context: SSLContext) : AutoCloseable {
    private val budget = PersistenceTimeBudget.start(35_000)
    private val server = AtomicReference<ServerSocket?>()
    private val pendingSocket = AtomicReference<Socket?>()
    private val connections = AtomicInteger()
    private val started = AtomicBoolean()
    private val closing = AtomicBoolean()
    private val noMoreClients = AtomicBoolean()
    private val problem = AtomicReference<Throwable?>()
    private val sessions = Array(2) { PgLifecycleTlsSession(it, mode, context, budget, closing, problem) }
    private val acceptor = Thread.ofPlatform().name("synthetic-lifecycle-tls-listener").inheritInheritableThreadLocals(false).unstarted(::accept)
    val port: Int get() = requireNotNull(server.get()).localPort

    fun start() {
        check(started.compareAndSet(false, true))
        server.set(ServerSocket())
        requireNotNull(server.get()).bind(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0), 3)
        acceptor.start()
    }

    fun arm(index: Int) {
        check(sessions[index].armed.compareAndSet(false, true))
    }

    fun awaitSslRequest(index: Int) {
        awaitLifecycleFact(5_000) {
            problem.get()?.let { throw it }
            sessions[index].requested.count == 0L
        }
    }

    fun diagnostic(index: Int): String = "observation=MIXED accepted=${connections.get()} " +
        "armed=${sessions[index].armed.get()} started=${sessions[index].started.get()} " +
        "ssl_requested=${sessions[index].requested.count == 0L} problem_present=${problem.get() != null}"

    fun release(index: Int) = sessions[index].allowTls.countDown()

    fun releaseAll() = sessions.forEach { it.allowTls.countDown() }

    fun awaitComplete(index: Int) {
        awaitLifecycleFact(5_000) {
            problem.get()?.let { throw it }
            sessions[index].complete.get()
        }
        sessions[index].verify()
    }

    fun requireStillConnected(index: Int) {
        problem.get()?.let { throw it }
        check(sessions[index].authenticated.get() && !sessions[index].disconnected.get() && !sessions[index].complete.get())
    }

    /** Called only after all driver actors have ended: the listener must time out with no queued successor. */
    fun verifyNoDowngrade() {
        noMoreClients.set(true)
        awaitLifecycleFact {
            problem.get()?.let { throw it }
            sessions.all { it.complete.get() } && !acceptor.isAlive
        }
        pgLifecycleTlsJoin(listOf(acceptor) + sessions.map { it.worker })
        check(connections.get() == sessions.size && pendingSocket.get() == null)
        sessions.forEach { it.verify() }
    }

    private fun accept() {
        runCatching {
            val listener = requireNotNull(server.get())
            while (!closing.get()) {
                try {
                    listener.soTimeout = budget.remainingMillis(100).toInt()
                    pendingSocket.set(listener.accept()) // Retain before checking count, arm or starting a worker.
                } catch (failure: SocketTimeoutException) {
                    check(!listener.isClosed) { "Synthetic TLS listener unexpectedly closed: ${failure.javaClass.simpleName}" }
                    if (noMoreClients.get()) return
                    continue
                }
                if (closing.get()) return
                val index = connections.getAndIncrement()
                check(index in sessions.indices) { "Unexpected TLS reconnect or plaintext downgrade." }
                val session = sessions[index]
                check(session.armed.get()) { "Driver attempted an unarmed TLS successor." }
                session.socket.set(pendingSocket.get())
                pendingSocket.set(null)
                session.started.set(true)
                session.worker.start()
            }
        }.onFailure { if (!closing.get()) problem.compareAndSet(null, it) }
    }

    override fun close() {
        if (!closing.compareAndSet(false, true)) return
        releaseAll()
        val listener = runCatching { server.get()?.close() }
        val firstCloses = closeSockets()
        val joined = runCatching { pgLifecycleTlsJoin(listOf(acceptor) + sessions.map { it.worker }) }
        val lateCloses = closeSockets() // An accept/TLS constructor may have published after the first cleanup pass.
        listener.getOrThrow()
        firstCloses.forEach { it.getOrThrow() }
        lateCloses.forEach { it.getOrThrow() }
        joined.getOrThrow()
        check(server.get()?.isClosed != false && pendingSocket.get()?.isClosed != false)
        check(sessions.all { it.socket.get()?.isClosed != false && it.tls.get()?.isClosed != false && !it.worker.isAlive })
        check(!acceptor.isAlive)
        problem.get()?.let { throw it }
        println("PG_LIFECYCLE_TLS_PEER_CLEANUP sockets_closed=true threads_joined=true proof=REAL_TLS+PROTOCOL_PEER")
    }

    private fun closeSockets(): List<Result<Unit?>> = listOf(runCatching { pendingSocket.get()?.close() }) + sessions.flatMap {
        // Closing the raw first also unblocks an unfinished handshake/read; SSLSocket is independently retained and closed.
        listOf(runCatching { it.socket.get()?.close() }, runCatching { it.tls.get()?.close() })
    }
}
