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

/** Two preowned attempts, at most two listeners/four raw sockets/two TLS layers, and one joined platform worker. Not PostgreSQL. */
internal class PgLifecycleNegotiationPeer(private val mode: PgLifecycleNegotiationMode, context: SSLContext?) : AutoCloseable {
    private val budget = PersistenceTimeBudget.start(35_000)
    private val servers = Array(2) { AtomicReference<ServerSocket?>() }
    private val pending = AtomicReference<Socket?>()
    private val connections = Array(2) { AtomicInteger() }
    private val started = AtomicBoolean()
    private val closing = AtomicBoolean()
    private val noMoreClients = AtomicBoolean()
    private val normalExit = AtomicBoolean()
    private val problem = AtomicReference<Throwable?>()
    private val attempts = Array(2) { PgLifecycleNegotiationAttempt(mode, context, budget, closing) }
    private val worker = Thread.ofPlatform().name("synthetic-lifecycle-negotiation-peer").inheritInheritableThreadLocals(false).unstarted {
        runCatching {
            attempts.forEach { attempt ->
                accept(0, attempt.first)
                check(attempt.armed.get()) { "An unarmed negotiation attempt connected." }
                attempt.serve { target -> accept(if (mode === PgLifecycleNegotiationMode.NEXT_HOST) 1 else 0, target) }
            }
            guardNoExtraClients()
            normalExit.set(true)
        }.onFailure { if (!closing.get()) problem.compareAndSet(null, it) }
    }
    val port: Int get() = requireNotNull(servers[0].get()).localPort
    val secondPort: Int get() = requireNotNull(servers[1].get()).localPort

    init {
        check((context != null) == mode.realTls)
    }

    fun start() {
        check(started.compareAndSet(false, true))
        repeat(if (mode === PgLifecycleNegotiationMode.NEXT_HOST) 2 else 1) { index ->
            servers[index].set(ServerSocket())
            requireNotNull(servers[index].get()).bind(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0), 3)
        }
        worker.start()
    }

    fun arm(index: Int) {
        check(attempts[index].armed.compareAndSet(false, true))
    }

    fun awaitInitial(index: Int) = await { attempts[index].initialSeen.count == 0L }

    /** Independently sampled fixture facts, not an atomic snapshot or admission/cleanup evidence. */
    fun diagnostic(index: Int): String {
        val attempt = attempts[index]
        return "observation=MIXED accepted_listener0=${connections[0].get()} accepted_listener1=${connections[1].get()} " +
            "armed=${attempt.armed.get()} initial_seen=${attempt.initialSeen.count == 0L} " +
            "held_gate=${attempt.heldGate.get().name} problem_present=${problem.get() != null}"
    }

    fun releaseResponse(index: Int) = attempts[index].allowResponse.countDown()

    fun awaitFinalStartup(index: Int) {
        check(mode.succeeds)
        await { attempts[index].finalStartup.count == 0L }
        val attempt = attempts[index]
        check(!attempt.complete.get() && !attempt.disconnected.get() && !attempt.authenticated.get())
        check(attempt.predecessorDisconnected.get() == mode.rotates && ((attempt.successor.get() != null) == mode.rotates))
        check(attempt.first.get()?.isClosed == false && attempt.successor.get()?.isClosed != true)
    }

    fun releaseAuthentication(index: Int) = attempts[index].allowAuthentication.countDown()

    fun release(index: Int) = attempts[index].releaseAll()

    fun awaitHeldGate(index: Int, phase: PgLifecycleNegotiationCut) {
        check(phase !== PgLifecycleNegotiationCut.NONE)
        val attempt = attempts[index]
        await { attempt.heldGate.get() === phase }
        val latch = if (phase === PgLifecycleNegotiationCut.INITIAL_RESPONSE) attempt.allowResponse else attempt.allowAuthentication
        check(latch.count == 1L && !closing.get() && worker.isAlive)
        check(!attempt.complete.get() && !attempt.authenticated.get() && !attempt.disconnected.get())
    }

    fun awaitComplete(index: Int) {
        await { attempts[index].complete.get() }
        attempts[index].verify()
    }

    fun requireStillConnected(index: Int) {
        await { attempts[index].authenticated.get() }
        check(!attempts[index].complete.get() && !attempts[index].disconnected.get())
    }

    /** Only after authentic driver/root shutdown: every owned listening port must time out with no queued connection. */
    fun verifyNoExtraConnections() {
        noMoreClients.set(true)
        await { !worker.isAlive }
        pgLifecycleTlsJoin(listOf(worker))
        check(normalExit.get() && pending.get() == null && started.get())
        val nextHost = mode === PgLifecycleNegotiationMode.NEXT_HOST
        check(connections[0].get() == if (mode.rotates && !nextHost) 4 else 2)
        check(connections[1].get() == if (nextHost) 2 else 0)
        attempts.forEach { it.verify() }
    }

    private fun accept(index: Int, target: AtomicReference<Socket?>): Socket {
        check(target.get() == null && pending.get() == null)
        val server = requireNotNull(servers[index].get())
        while (true) {
            check(!closing.get())
            try {
                server.soTimeout = budget.remainingMillis(100).toInt()
                pending.set(server.accept()) // Retain the actual return before checking any recipe/arm/count.
                break
            } catch (_: SocketTimeoutException) {
                check(!server.isClosed)
            }
        }
        val raw = requireNotNull(pending.get())
        check(target.compareAndSet(null, raw))
        pending.set(null)
        connections[index].incrementAndGet()
        raw.tcpNoDelay = true
        return raw
    }

    private fun guardNoExtraClients() {
        while (!closing.get()) {
            val finalRound = noMoreClients.get()
            servers.mapNotNull { it.get() }.forEachIndexed { index, server ->
                try {
                    server.soTimeout = budget.remainingMillis(100).toInt()
                    pending.set(server.accept())
                    throw PgLifecycleNegotiationExtraClient(index)
                } catch (_: SocketTimeoutException) {
                    check(!server.isClosed)
                }
            }
            if (finalRound) return // Each timeout in this final full round occurred after no future driver allocation was possible.
        }
        error("Fixture cleanup cannot satisfy the extra-connection guard.")
    }

    private fun await(predicate: () -> Boolean) = awaitLifecycleFact {
        problem.get()?.let { throw it }
        predicate()
    }

    override fun close() {
        if (!closing.compareAndSet(false, true)) return
        attempts.forEach { it.releaseAll() }
        val listeners = servers.map { runCatching { it.get()?.close() } }
        val firstCloses = closeSockets()
        val joined = runCatching { pgLifecycleTlsJoin(listOf(worker)) }
        val lateCloses = closeSockets() // Also retain/close an accept or TLS layer published during the first cleanup pass.
        listeners.forEach { it.getOrThrow() }
        firstCloses.forEach { it.getOrThrow() }
        lateCloses.forEach { it.getOrThrow() }
        joined.getOrThrow()
        check(servers.all { it.get()?.isClosed != false } && pending.get()?.isClosed != false && !worker.isAlive)
        check(attempts.all { it.first.get()?.isClosed != false && it.successor.get()?.isClosed != false && it.tls.get()?.isClosed != false })
        println("PG_LIFECYCLE_NEGOTIATION_PEER_CLEANUP sockets_closed=true threads_joined=true proof=PROTOCOL_PEER")
        problem.get()?.let { throw it }
    }

    private fun closeSockets(): List<Result<Unit?>> = listOf(runCatching { pending.get()?.close() }) + attempts.flatMap {
        listOf(runCatching { it.first.get()?.close() }, runCatching { it.successor.get()?.close() }, runCatching { it.tls.get()?.close() })
    }
}
