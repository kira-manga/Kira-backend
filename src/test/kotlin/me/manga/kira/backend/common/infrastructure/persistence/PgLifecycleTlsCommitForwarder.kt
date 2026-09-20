package me.manga.kira.backend.common.infrastructure.persistence

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * One original TEST catalog transaction cut (PROJECT or initial admission), at the same endpoint
 * for freeze/COMPLETE/PROJECT/registration and the selected transaction. Opaque TCP bytes
 * go to the real PostgreSQL server: no TLS termination, credential capture, protocol replies or
 * plaintext parsing. One nonblocking actor, <=8 lifetime-total connections (not concurrent), two
 * 16-KiB buffers per connection, <=16 MiB/direction, <=45s/connection and <=180s total. This case
 * has at most four catalog roots (one physical slot each); initial admission additionally starts
 * its two-slot ordinary target, with a possible min-idle replacement after the one cut. Unexpected
 * extra churn fails the unchanged bound; it never grows the relay.
 * The cut never releases retained bytes.
 */
internal class PgLifecycleTlsCommitForwarder(private val database: PgLifecycleDatabaseFixture) : AutoCloseable {
    private val lock = Any()
    private val closing = AtomicBoolean()
    private val failure = AtomicReference<Throwable?>()
    private val sessions = ArrayList<Session>(8)
    private var selector: Selector? = null
    private var listener: ServerSocketChannel? = null
    private var target: InetSocketAddress? = null
    private var selected: Session? = null
    private var startedAt = 0L
    private val actor = Thread.ofPlatform().inheritInheritableThreadLocals(false).name("kira-test-project-tls-forwarder").unstarted(::forward)

    val port: Int get() = (checkNotNull(listener).localAddress as InetSocketAddress).port

    fun start() {
        check(selector == null && listener == null && !closing.get())
        check(database.host in setOf("127.0.0.1", "localhost"))
        target = InetSocketAddress(InetAddress.getByName(database.host), database.port)
        // Retain each resource before any later operation can throw; the caller owns this use scope before start.
        selector = Selector.open()
        listener = ServerSocketChannel.open()
        checkNotNull(listener).apply {
            configureBlocking(false)
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 8)
            register(checkNotNull(selector), SelectionKey.OP_ACCEPT)
        }
        startedAt = System.nanoTime()
        actor.start()
    }

    /** Exact original holder -> own-project physical entry -> tracked TCP socket; no accept ordinal or NAT assumption. */
    fun arm(lease: PersistenceJdbcLease, scope: PgLifecycleTestScope) {
        val lower: Any = poolTestField(lease, "lower")
        val calls: Any = poolTestField(lower, "calls")
        val entry: PersistencePhysicalEntry = poolTestField(calls, "entry")
        check(scope.catalogEntries().any { it === entry })
        val socket = pgRetainedSockets(entry).single()
        check(socket.isConnected && !socket.isClosed && socket.port == port)
        val address = socket.localSocketAddress
        synchronized(lock) {
            healthy()
            check(selected == null)
            val session = sessions.single { !it.closed && it.client.remoteAddress == address }
            check(session.connected && !session.up.bytes.hasRemaining())
            session.armedAt = System.nanoTime()
            session.forwardedAtArm = session.down.written
            session.sentAtArm = session.up.written
            session.originalSocket = socket
            selected = session
            // The same lock covers EVERY nonblocking server->client write, including any already-read chunk.
            session.holdIfArmed()
            session.interests()
        }
        checkNotNull(selector).wakeup()
    }

    fun awaitHeld() {
        val deadline = PgLifecycleDatabaseDeadline(2_000)
        while (true) {
            val held = synchronized(lock) {
                healthy()
                val session = checkNotNull(selected)
                check(!session.closed)
                session.heldBytes > 0 && session.up.written > session.sentAtArm
            }
            if (held) break
            deadline.pause()
        }
        deadline.checkRemaining()
        requireHeld()
    }

    fun requireHeld() = synchronized(lock) {
        healthy()
        val session = checkNotNull(selected)
        check(!session.closed && session.client.isOpen && session.server.isOpen)
        check(!checkNotNull(session.originalSocket).isClosed) // The real driver has not already disposed its side on a timeout.
        check(session.heldBytes in 1..16_384 && session.heldAt >= session.armedAt)
        check(session.up.written > session.sentAtArm) // Actual native request bytes crossed AFTER beforeCommit armed the gate.
        check(session.down.written == session.forwardedAtArm && session.lastClientWrite <= session.armedAt)
    }

    /** Called only after the independent durable SQL witness; this is a real connection loss, not a callback exception. */
    fun loseHeldResponse(): Unit = synchronized(lock) {
        requireHeld()
        val session = checkNotNull(selected)
        session.stop()
        session.lostAt = System.nanoTime() // Actual channel closes returned; fixture teardown cannot create this observation.
        checkNotNull(selector).wakeup()
        Unit
    }

    fun requireLost() = synchronized(lock) {
        healthy()
        val session = checkNotNull(selected)
        check(session.closed && !session.client.isOpen && !session.server.isOpen)
        check(session.lostAt >= session.heldAt && session.heldBytes > 0)
        check(session.down.written == session.forwardedAtArm && session.lastClientWrite <= session.armedAt)
    }

    private fun forward() {
        try {
            val readiness = checkNotNull(selector)
            while (!closing.get()) {
                check(System.nanoTime() - startedAt < 180_000_000_000L) { "TEST TLS forwarder lifetime exceeded." }
                readiness.select(100)
                synchronized(lock) {
                    val keys = readiness.selectedKeys().iterator()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        keys.remove()
                        if (key.isValid) {
                            if (key.isAcceptable) accept() else (key.attachment() as Session).ready(key)
                        }
                    }
                    sessions.filterNot { it.closed }.forEach {
                        check(System.nanoTime() - it.openedAt < 45_000_000_000L) { "TEST TLS session lifetime exceeded." }
                    }
                }
            }
        } catch (problem: Throwable) {
            retainFailure(problem)
        } finally {
            synchronized(lock) {
                runCatching { listener?.close() }.exceptionOrNull()?.let(::retainFailure)
                sessions.forEach { runCatching(it::stop).exceptionOrNull()?.let(::retainFailure) }
            }
        }
    }

    private fun accept() {
        val client = checkNotNull(listener).accept() ?: return
        var server: SocketChannel? = null
        var retained = false
        try {
            check(sessions.size < 8) { "TEST TLS connection bound exceeded." }
            val opened = SocketChannel.open()
            server = opened
            val session = Session(client, opened)
            sessions.add(session)
            retained = true
            session.start()
        } finally {
            if (!retained) {
                runCatching(client::close).exceptionOrNull()?.let(::retainFailure)
                runCatching { server?.close() }.exceptionOrNull()?.let(::retainFailure)
            }
        }
    }

    private inner class Session(val client: SocketChannel, val server: SocketChannel) {
        val openedAt = System.nanoTime()
        val up = Direction(client, server)
        val down = Direction(server, client)
        private var clientKey: SelectionKey? = null
        private var serverKey: SelectionKey? = null
        var connected = false
        var closed = false
        var originalSocket: TrackedPersistenceSocket? = null
        var armedAt = 0L
        var forwardedAtArm = -1L
        var sentAtArm = -1L
        var heldBytes = 0
        var heldAt = 0L
        var lostAt = 0L
        var lastClientWrite = 0L

        fun start() {
            for (channel in listOf(client, server)) {
                channel.configureBlocking(false)
                channel.setOption(StandardSocketOptions.TCP_NODELAY, true)
            }
            clientKey = client.register(checkNotNull(selector), 0, this)
            connected = server.connect(checkNotNull(target))
            serverKey = server.register(checkNotNull(selector), 0, this)
            interests()
        }

        fun ready(key: SelectionKey) {
            if (closed) return
            if (key.isConnectable) connected = server.finishConnect()
            if (key.isReadable) {
                val direction = if (key.channel() === client) up else down
                if (!direction.read()) { stop(); return }
                holdIfArmed()
            }
            if (key.isWritable) {
                if (key.channel() === server) up.write() else if (selected !== this) {
                    if (down.write() > 0) lastClientWrite = System.nanoTime()
                }
            }
            interests()
        }

        fun holdIfArmed() {
            if (selected === this && heldBytes == 0 && down.bytes.hasRemaining()) {
                heldBytes = down.bytes.remaining()
                heldAt = System.nanoTime()
            }
        }

        fun interests() {
            if (closed) return
            checkNotNull(clientKey).interestOps(
                (if (!up.bytes.hasRemaining()) SelectionKey.OP_READ else 0) or
                    (if (down.bytes.hasRemaining() && selected !== this) SelectionKey.OP_WRITE else 0),
            )
            val serverOps = if (!connected) SelectionKey.OP_CONNECT else {
                (if (!down.bytes.hasRemaining()) SelectionKey.OP_READ else 0) or
                    (if (up.bytes.hasRemaining()) SelectionKey.OP_WRITE else 0)
            }
            checkNotNull(serverKey).interestOps(serverOps)
        }

        fun stop() {
            if (closed) return
            closed = true
            clientKey?.cancel()
            serverKey?.cancel()
            val closes = listOf(runCatching(client::close), runCatching(server::close))
            up.bytes.array().fill(0)
            down.bytes.array().fill(0)
            closes.forEach { it.exceptionOrNull()?.let(::retainFailure) }
            closes.forEach { it.getOrThrow() }
        }
    }

    private class Direction(private val source: SocketChannel, private val destination: SocketChannel) {
        val bytes: ByteBuffer = ByteBuffer.allocate(16_384).apply { limit(0) }
        private var received = 0L
        var written = 0L
            private set

        fun read(): Boolean {
            check(!bytes.hasRemaining())
            bytes.clear()
            val count = source.read(bytes)
            bytes.flip()
            if (count < 0) return false
            received += count
            check(received <= 16_777_216L) { "TEST TLS byte bound exceeded." }
            return true
        }

        fun write(): Int = destination.write(bytes).also { written += it }
    }

    private fun healthy() {
        failure.get()?.let { throw it }
        check(!closing.get() && actor.isAlive) { "TEST TLS forwarder is not running." }
    }

    private fun retainFailure(problem: Throwable): Unit = synchronized(lock) {
        if (!failure.compareAndSet(null, problem)) failure.get()?.let { if (it !== problem) it.addSuppressed(problem) }
    }

    override fun close() {
        closing.set(true)
        synchronized(lock) {
            runCatching { listener?.close() }.exceptionOrNull()?.let(::retainFailure)
            sessions.forEach { runCatching(it::stop).exceptionOrNull()?.let(::retainFailure) }
            selector?.wakeup()
        }
        val ended = runCatching {
            val deadline = PgLifecycleDatabaseDeadline(5_000)
            while (actor.isAlive) actor.join(deadline.millis(100))
            check(!actor.isAlive && sessions.all { !it.client.isOpen && !it.server.isOpen })
        }
        ended.exceptionOrNull()?.let(::retainFailure)
        runCatching { selector?.close() }.exceptionOrNull()?.let(::retainFailure)
        failure.get()?.let { throw it }
    }
}
