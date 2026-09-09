package me.manga.kira.backend.common.infrastructure.persistence

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Bounded successful protocol peer, not PostgreSQL. Imported strict wire oracle distinguishes remote EOF/reset from fixture close. */
internal class PgLifecycleRecordBoundaryPeer(count: Int) : AutoCloseable {
    private val server = AtomicReference<ServerSocket?>()
    private val budget = PersistenceTimeBudget.start(45_000)
    private val started = AtomicBoolean()
    private val closing = AtomicBoolean()
    private val problem = AtomicReference<Throwable?>()
    private val slots = Array(count) { Session(it) }
    private val acceptor = Thread.ofPlatform().name("record-boundary-listener").inheritInheritableThreadLocals(false).unstarted(::accept)
    val port: Int get() = requireNotNull(server.get()).localPort

    init {
        require(count in 1..2)
    }

    fun start() {
        check(started.compareAndSet(false, true))
        server.set(ServerSocket())
        requireNotNull(server.get()).bind(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0), slots.size)
        acceptor.start()
    }

    fun observeRetirement(ordinal: Int) {
        val slot = slots[ordinal]
        check(slot.authenticated.await(5, TimeUnit.SECONDS))
        slot.allowDisconnect.countDown()
        awaitLifecycleFact { slot.disconnected.get() || problem.get() != null }
        problem.get()?.let { throw it }
        check(slot.disconnected.get() && !closing.get())
    }

    fun verify() {
        pgLifecycleTlsJoin(listOf(acceptor) + slots.map { it.worker })
        problem.get()?.let { throw it }
        check(!closing.get() && slots.all { it.socket.get() != null && it.disconnected.get() })
        println("PG_LIFECYCLE_RECORD_BOUNDARY_PEER_VERIFIED connections=${slots.size} proof=PROTOCOL_PEER_NOT_POSTGRESQL")
    }

    fun releaseGates() = slots.forEach { it.allowDisconnect.countDown() }

    private fun accept() {
        runCatching {
            for (slot in slots) {
                val listener = requireNotNull(server.get())
                listener.soTimeout = budget.remainingMillis(25_000).toInt()
                slot.socket.set(listener.accept()) // Retain before any later check/start, including a concurrent close.
                if (!closing.get()) slot.worker.start()
            }
        }.onFailure { if (!closing.get()) problem.compareAndSet(null, it) }
    }

    private fun serve(slot: Session) {
        val wire = PgLifecycleNegotiationWire(requireNotNull(slot.socket.get()), budget, closing)
        wire.startup()
        wire.authenticate()
        slot.authenticated.countDown()
        check(slot.allowDisconnect.await(25, TimeUnit.SECONDS))
        if (closing.get()) return
        wire.disconnect(allowTerminate = true)
        check(!closing.get())
        slot.disconnected.set(true)
    }

    override fun close() {
        closing.set(true)
        releaseGates()
        val listener = runCatching { server.get()?.close() }
        val sockets = slots.map { runCatching { it.socket.get()?.close() } }
        val joins = runCatching { pgLifecycleTlsJoin(listOf(acceptor) + slots.map { it.worker }) }
        val lateSockets = slots.map { runCatching { it.socket.get()?.close() } }
        (listOf(listener) + sockets + listOf(joins) + lateSockets).forEach { it.getOrThrow() }
        check(!acceptor.isAlive && slots.all { !it.worker.isAlive && it.socket.get()?.isClosed != false })
        problem.get()?.let { throw it }
        println("PG_LIFECYCLE_RECORD_BOUNDARY_PEER_CLEANUP connections=${slots.size} all_terminated=true api_closed=true")
    }

    private inner class Session(index: Int) {
        val socket = AtomicReference<Socket?>()
        val authenticated = CountDownLatch(1)
        val allowDisconnect = CountDownLatch(1)
        val disconnected = AtomicBoolean()
        val worker: Thread = Thread.ofPlatform().name("record-boundary-peer-$index").inheritInheritableThreadLocals(false).unstarted {
            runCatching { serve(this) }.onFailure { if (!closing.get()) problem.compareAndSet(null, it) }
        }
    }
}
