package me.manga.kira.backend.common.infrastructure.persistence

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/** Fixed owned loopback resources; protocol/fallback evidence only, never PostgreSQL/TLS/native disposal. */
internal class PgRotationPeer(private val mode: PgOpeningProbeCase) : AutoCloseable {
    private val listeners = Array(2) { AtomicReference<ServerSocket?>() }
    private val sockets = Array(5) { AtomicReference<Socket?>() }
    private val problem = AtomicReference<Throwable?>()
    private val started = AtomicBoolean()
    private val complete = AtomicBoolean()
    private val held = AtomicBoolean()
    private val released = AtomicBoolean()
    private val setup = AtomicBoolean()
    private val fallbackClosed = AtomicBoolean()
    private val accepted = AtomicInteger()
    private val cancels = AtomicInteger()
    private val budget = PersistenceTimeBudget.start(15_000)
    private val worker = Thread.ofPlatform().name("kira-pg-rotation-peer").inheritInheritableThreadLocals(false).unstarted {
        runCatching(::serve).onFailure(problem::set)
    }
    val port: Int get() = requireNotNull(listeners[0].get()).localPort
    val secondPort: Int get() = requireNotNull(listeners[1].get()).localPort
    val cancellations: Int get() = cancels.get()

    fun bind() {
        check(listeners.all { it.get() == null })
        val count = if (mode === PgOpeningProbeCase.ROTATE_NEXT_HOST) 2 else 1
        repeat(count) { index ->
            listeners[index].set(ServerSocket())
            requireNotNull(listeners[index].get()).bind(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0), 3)
        }
    }

    fun start() {
        check(started.compareAndSet(false, true) && listeners[0].get() != null)
        worker.start()
    }

    fun awaitHeld() = awaitPgFixtureFact { held.get() }

    fun releaseHeld() = released.set(true)

    fun refuseCancellationConnection() {
        check(mode === PgOpeningProbeCase.ROTATE_MISSING_CONTACT)
        awaitPgFixtureFact { setup.get() }
        requireNotNull(listeners[0].get()).close()
    }

    fun verify() {
        awaitPgFixtureFact { !worker.isAlive }
        problem.get()?.let { throw it }
        check(complete.get()) { "Synthetic rotation protocol did not complete." }
        val fallback = mode === PgOpeningProbeCase.ROTATE_NEXT_HOST || mode === PgOpeningProbeCase.ROTATE_PREFER_E
        check(!fallback || fallbackClosed.get()) { "Predecessor client close was not observed before fallback." }
        check(cancels.get() == expectedCancels())
        check(accepted.get() == (if (fallback) 2 else 1) + expectedCancels())
        println(
            "PG_ROTATION_PROTOCOL mode=${mode.name} accepted=${accepted.get()} cancels=${cancels.get()} " +
                "predecessor_eof=${fallbackClosed.get()} proof=REAL_DRIVER+PROTOCOL_PEER",
        )
    }

    /** Registered actor cleanup can unblock a failed protocol without waiting for the outer use block. */
    fun unblock() {
        releaseHeld()
        val closed = listeners.map { runCatching { it.get()?.close() } } + sockets.map { runCatching { it.get()?.close() } }
        val exit = runCatching { awaitPgFixtureFact { !worker.isAlive } }
        val late = sockets.map { runCatching { it.get()?.close() } }
        closed.forEach { it.getOrThrow() }
        late.forEach { it.getOrThrow() }
        exit.getOrThrow()
    }

    override fun close() {
        unblock()
        check(listeners.all { it.get()?.isClosed != false } && sockets.all { it.get()?.isClosed != false })
        println(
            "PG_PEER_CLEANUP thread_id=${worker.threadId()} started=${started.get()} sockets=${sockets.count { it.get() != null }} " +
                "listeners=${listeners.count { it.get() != null }} all_terminated=true api_closed=true",
        )
    }

    private fun serve() {
        var socket = accept(0, 0)
        if (mode === PgOpeningProbeCase.ROTATE_NEXT_HOST) {
            val first = PgProtocolChannel(socket, budget)
            requireStartup(first.startup())
            first.send(
                'E',
                byteArrayOf('S'.code.toByte()) + PgProtocolChannel.zeroTerminated("FATAL") +
                    byteArrayOf('C'.code.toByte()) + PgProtocolChannel.zeroTerminated("08006") +
                    byteArrayOf('M'.code.toByte()) + PgProtocolChannel.zeroTerminated("Synthetic startup refusal.") + byteArrayOf(0),
            )
            first.requireEof()
            fallbackClosed.set(true)
            socket = accept(1, 1)
        } else if (mode === PgOpeningProbeCase.ROTATE_PREFER_E || mode === PgOpeningProbeCase.ROTATE_REQUIRE_E) {
            val first = PgProtocolChannel(socket, budget)
            check(first.startup().contentEquals(PgProtocolChannel.integer(80_877_103)))
            socket.getOutputStream().write('E'.code) // One fixed bounded SSL negotiation byte, not a TLS handshake.
            first.requireEof()
            fallbackClosed.set(true)
            if (mode === PgOpeningProbeCase.ROTATE_REQUIRE_E) {
                complete.set(true)
                return
            }
            socket = accept(0, 1)
        }
        val channel = PgProtocolChannel(socket, budget)
        startup(channel)
        setup.set(true)
        repeat(expectedCancels()) { index -> cancel(index) }
        check(channel.receive('X').isEmpty())
        channel.requireEof()
        complete.set(true)
    }

    private fun startup(channel: PgProtocolChannel) {
        requireStartup(channel.startup())
        channel.send('R', PgProtocolChannel.integer(3))
        check(channel.receive('p').contentEquals(PgProtocolChannel.zeroTerminated(PgProtocolPeer.PASSWORD)))
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

    private fun cancel(index: Int) {
        val socket = accept(if (mode === PgOpeningProbeCase.ROTATE_NEXT_HOST) 1 else 0, index + 2)
        val payload = PgProtocolChannel(socket, budget).startup()
        check(payload.contentEquals(PgProtocolChannel.integer(80_877_102) + PgProtocolChannel.integer(4_242) + byteArrayOf(1, 2, 3, 4)))
        cancels.incrementAndGet()
        if (mode === PgOpeningProbeCase.ROTATE_HELD_AUX && index == 0) {
            held.set(true)
            while (!released.get()) LockSupport.parkNanos(budget.remainingMillis(1) * 1_000_000)
        }
        socket.close()
    }

    private fun accept(listener: Int, slot: Int): Socket {
        val server = requireNotNull(listeners[listener].get())
        server.soTimeout = budget.remainingMillis(5_000).toInt()
        check(sockets[slot].get() == null)
        sockets[slot].set(server.accept())
        accepted.incrementAndGet()
        return requireNotNull(sockets[slot].get())
    }

    private fun expectedCancels(): Int = when (mode) {
        PgOpeningProbeCase.ROTATE_REQUIRE_E, PgOpeningProbeCase.ROTATE_MISSING_CONTACT -> 0

        PgOpeningProbeCase.ROTATE_ORDINARY, PgOpeningProbeCase.ROTATE_CONJUNCTION,
        PgOpeningProbeCase.ROTATE_DELETION, PgOpeningProbeCase.ROTATE_HELD_AUX,
        -> 3

        else -> 1
    }

    private fun requireStartup(bytes: ByteArray) {
        val expected = PgProtocolChannel.integer(196_608) + listOf(
            "user", "fixture", "database", "fixture", "client_encoding", "UTF8", "DateStyle", "ISO", "TimeZone", "UTC",
        ).flatMap { PgProtocolChannel.zeroTerminated(it).toList() }.toByteArray() + byteArrayOf(0)
        check(bytes.contentEquals(expected))
    }
}
