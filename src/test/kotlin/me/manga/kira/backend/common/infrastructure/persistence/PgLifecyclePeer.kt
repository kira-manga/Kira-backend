package me.manga.kira.backend.common.infrastructure.persistence

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

internal enum class PgLifecyclePeerMode {
    SUCCESS,
    REFUSE,
    HOLD_REFUSE,
    PARTIAL,
    HOLD_ORIGINAL,
    BLOCK_TRACKED,
    PROGRESS_READY,
    PROGRESS_TIMEOUT,
}

/** Fixed bounded protocol peer, not PostgreSQL. Transport-first retirement may yield EOF/reset rather than CancelRequest/Terminate. */
internal class PgLifecyclePeer(count: Int = 1, private val mode: PgLifecyclePeerMode = PgLifecyclePeerMode.SUCCESS) : AutoCloseable {
    private val server = AtomicReference<ServerSocket?>()
    private val budget = PersistenceTimeBudget.start(45_000)
    private val allowReady = CountDownLatch(1)
    private val reachedStartup = CountDownLatch(1)
    private val problem = AtomicReference<Throwable?>()
    private val slots = Array(count) { index -> Session(index) }
    private val acceptor = Thread.ofPlatform().name("synthetic-lifecycle-listener").inheritInheritableThreadLocals(false).unstarted(::accept)
    private val started = AtomicBoolean()
    private val closing = AtomicBoolean()
    val progressWrites = AtomicInteger()
    val lastProgressNanos = AtomicLong()
    val maximumProgressGapNanos = AtomicLong()
    val port: Int get() = requireNotNull(server.get()).localPort

    init {
        require(count in 1..5)
    }

    fun start() {
        check(started.compareAndSet(false, true))
        server.set(ServerSocket())
        requireNotNull(server.get()).bind(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0), slots.size)
        acceptor.start()
    }

    fun awaitStartup() {
        check(reachedStartup.await(5, TimeUnit.SECONDS)) { "Synthetic peer did not reach startup." }
    }

    fun releaseReady() = allowReady.countDown()

    fun awaitRefusal(ordinal: Int) {
        check(mode === PgLifecyclePeerMode.HOLD_REFUSE && ordinal in slots.indices)
        check(slots[ordinal].refusalReached.await(5, TimeUnit.SECONDS)) { "Provider refusal did not reach real startup." }
    }

    fun diagnostic(ordinal: Int): String = "observation=MIXED accepted=${slots.count { it.socket.get() != null }} " +
        "started=${slots[ordinal].started.get()} refusal_reached=${slots[ordinal].refusalReached.count == 0L} " +
        "problem_present=${problem.get() != null}"

    fun releaseRefusal(ordinal: Int) = slots[ordinal].allowRefusal.countDown()

    fun verify() {
        awaitLifecycleFact { !acceptor.isAlive && slots.all { it.complete.get() && !it.worker.isAlive } }
        problem.get()?.let { throw it }
        check(slots.all { it.started.get() && it.socket.get() != null })
        println("PG_LIFECYCLE_PEER_VERIFIED connections=${slots.size} mode=$mode proof=PROTOCOL_PEER_NOT_POSTGRESQL")
    }

    private fun accept() {
        runCatching {
            for (slot in slots) {
                val listener = requireNotNull(server.get())
                listener.soTimeout = budget.remainingMillis(25_000).toInt()
                slot.socket.set(listener.accept())
                slot.started.set(true)
                slot.worker.start()
            }
        }.onFailure { if (!closing.get()) problem.compareAndSet(null, it) }
    }

    private fun serve(slot: Session) {
        val socket = requireNotNull(slot.socket.get())
        val channel = PgProtocolChannel(socket, budget)
        check(channel.startup().take(4).toByteArray().contentEquals(PgProtocolChannel.integer(196_608)))
        reachedStartup.countDown()
        if (mode === PgLifecyclePeerMode.REFUSE || mode === PgLifecyclePeerMode.HOLD_REFUSE) {
            if (mode === PgLifecyclePeerMode.HOLD_REFUSE) {
                slot.refusalReached.countDown()
                check(slot.allowRefusal.await(5, TimeUnit.SECONDS))
            }
            channel.send(
                'E',
                PgProtocolChannel.zeroTerminated("SFATAL") + PgProtocolChannel.zeroTerminated("C28P01") +
                    PgProtocolChannel.zeroTerminated("Msynthetic authorization refusal") + byteArrayOf(0),
            )
            socket.close()
            return
        }
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
        when (mode) {
            PgLifecyclePeerMode.PARTIAL -> {
                channel.partialReady()
                socket.close()
            }

            PgLifecyclePeerMode.BLOCK_TRACKED -> disconnected(socket)

            PgLifecyclePeerMode.PROGRESS_TIMEOUT -> {
                progressReady(socket)
                disconnected(socket)
            }

            else -> {
                if (mode === PgLifecyclePeerMode.HOLD_ORIGINAL) check(allowReady.await(15, TimeUnit.SECONDS))
                if (mode === PgLifecyclePeerMode.PROGRESS_READY) check(progressReady(socket)) else channel.ready()
                check(channel.receive('Q').contentEquals(PgProtocolChannel.zeroTerminated("SET application_name = '${PgProtocolPeer.APPLICATION}'")))
                channel.status("application_name", PgProtocolPeer.APPLICATION)
                channel.send('C', PgProtocolChannel.zeroTerminated("SET"))
                channel.ready()
                disconnected(socket)
            }
        }
    }

    /** Six real bytes, 500ms apart: a 2s socket-idle timeout must not be confused with the original 2s caller deadline. */
    private fun progressReady(socket: Socket): Boolean {
        val bytes = byteArrayOf('Z'.code.toByte(), 0, 0, 0, 5, 'I'.code.toByte())
        val output = socket.getOutputStream()
        for ((index, byte) in bytes.withIndex()) {
            if (index != 0) {
                val until = System.nanoTime() + 500_000_000L
                while (System.nanoTime() - until < 0) {
                    budget.remainingMillis(1)
                    check(!Thread.currentThread().isInterrupted)
                    LockSupport.parkNanos(minOf(10_000_000L, until - System.nanoTime()).coerceAtLeast(1))
                }
            }
            try {
                output.write(byte.toInt())
                output.flush()
            } catch (failure: SocketException) {
                if (socket.isClosed || closing.get() || mode !== PgLifecyclePeerMode.PROGRESS_TIMEOUT) throw failure
                return false // Actual remote close while the fixture still owns an open socket, not fixture teardown.
            }
            val now = System.nanoTime()
            val previous = lastProgressNanos.getAndSet(now)
            if (previous != 0L) maximumProgressGapNanos.accumulateAndGet(now - previous, ::maxOf)
            progressWrites.incrementAndGet()
        }
        return true
    }

    private fun disconnected(socket: Socket) {
        socket.soTimeout = budget.remainingMillis(25_000).toInt()
        try {
            val input = socket.getInputStream()
            val first = input.read()
            if (first == 'X'.code) {
                check(input.readNBytes(4).contentEquals(PgProtocolChannel.integer(4)) && input.read() == -1)
            } else {
                check(first == -1) { "Unexpected business traffic on opaque lifecycle candidate." }
            }
        } catch (_: SocketException) {
            // A reset on this still-owned-open peer is a real client disconnect, not a fixture cleanup.
            check(!socket.isClosed)
        }
    }

    override fun close() {
        closing.set(true)
        releaseReady()
        slots.forEach { it.allowRefusal.countDown() }
        val listenerClose = runCatching { server.get()?.close() }
        val closes = slots.map { runCatching { it.socket.get()?.close() } }
        val ends = runCatching { awaitLifecycleFact { !acceptor.isAlive && slots.all { !it.worker.isAlive } } }
        val lateCloses = slots.map { runCatching { it.socket.get()?.close() } }
        listenerClose.getOrThrow()
        closes.forEach { it.getOrThrow() }
        lateCloses.forEach { it.getOrThrow() }
        ends.getOrThrow()
        check(slots.all { it.socket.get()?.isClosed != false })
        println("PG_LIFECYCLE_PEER_CLEANUP connections=${slots.size} all_terminated=true api_closed=true")
        problem.get()?.let { throw it }
    }

    private inner class Session(index: Int) {
        val refusalReached = CountDownLatch(1)
        val allowRefusal = CountDownLatch(1)
        val socket = AtomicReference<Socket?>()
        val started = AtomicBoolean()
        val complete = AtomicBoolean()
        val worker: Thread = Thread.ofPlatform().name("synthetic-lifecycle-peer-$index").inheritInheritableThreadLocals(false).unstarted {
            runCatching {
                serve(this)
                complete.set(true)
            }.onFailure { if (!closing.get()) problem.compareAndSet(null, it) }
        }
    }
}
