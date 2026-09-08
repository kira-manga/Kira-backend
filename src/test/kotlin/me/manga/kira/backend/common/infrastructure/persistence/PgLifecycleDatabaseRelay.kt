package me.manga.kira.backend.common.infrastructure.persistence

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicReferenceArray

/** Fixed plaintext relay to one owned PostgreSQL. At most one primary plus two AUX connections per request. */
internal class PgLifecycleDatabaseRelay(private val database: PgLifecycleDatabaseFixture, private val application: String, attempts: Int) : AutoCloseable {
    private val listener = ServerSocket()
    private val sessions = CopyOnWriteArrayList<PgLifecycleDatabaseRelaySession>()
    private val primaries = AtomicReferenceArray<PgLifecycleDatabaseRelaySession>(attempts)
    private val primaryCount = AtomicInteger()
    private val closing = AtomicBoolean()
    private val failed = AtomicReference<Throwable?>()
    private val actor = Thread.ofPlatform().name("w03-database-relay-accept").unstarted(::accept)

    val port: Int get() = listener.localPort

    init {
        check(attempts in 1..2)
    }

    fun start() {
        check(actor.state === Thread.State.NEW && !closing.get())
        listener.soTimeout = 500
        listener.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 6)
        actor.start()
    }

    private fun accept() {
        runCatching {
            while (!closing.get()) {
                val socket = try {
                    listener.accept()
                } catch (_: SocketTimeoutException) {
                    continue
                }
                var retained = false
                try {
                    // The owning list is published before configuration, parsing, connecting, or starting the accepted socket's actor.
                    val session = PgLifecycleDatabaseRelaySession(socket, database.host, database.port, ::register)
                    sessions.add(session)
                    retained = true
                    check(sessions.size <= primaries.length() * 3) { "Synthetic relay connection bound exceeded." }
                    session.start()
                } finally {
                    if (!retained) socket.close()
                }
            }
        }.onFailure { failure ->
            if (!closing.get() || failure !is SocketException) failed.compareAndSet(null, failure)
        }
    }

    private fun register(session: PgLifecycleDatabaseRelaySession, startup: ByteArray) {
        when (PgLifecycleDatabaseWire.protocol(startup)) {
            PgLifecycleDatabaseWire.STARTUP -> {
                PgLifecycleDatabaseWire.requireStartup(startup, application)
                val ordinal = primaryCount.getAndIncrement()
                check(ordinal < primaries.length())
                session.state.ordinal = ordinal
                check(primaries.compareAndSet(ordinal, null, session))
            }

            PgLifecycleDatabaseWire.CANCEL -> {
                val pid = PgLifecycleDatabaseWire.cancelPid(startup)
                val primary = (0 until primaries.length()).mapNotNull { primaries.get(it) }.single { it.state.backendPid.get() == pid }
                session.state.ordinal = primary.state.ordinal
                session.state.auxiliary = true
                check(sessions.count { it.state.auxiliary && it.state.ordinal == primary.state.ordinal } <= 2)
            }

            else -> error("Plaintext fixture received an unexpected startup protocol.")
        }
    }

    fun progress() {
        check(failed.get() == null && sessions.all { it.state.failure.get() == null }) { "Owned PostgreSQL relay failed." }
        check(!closing.get() && actor.isAlive) { "Owned PostgreSQL relay is no longer running." }
    }

    fun awaitGate(ordinal: Int, deadline: PgLifecycleDatabaseDeadline, childAlive: () -> Unit): PgLifecycleDatabaseRelayState {
        while (true) {
            progress()
            childAlive()
            deadline.checkRemaining()
            val state = primaries.get(ordinal)?.state
            if (state?.isHeld() == true) return state
            deadline.pause()
        }
    }

    fun requireLive(ordinal: Int) {
        progress()
        val state = requireNotNull(primaries.get(ordinal)).state
        check(state.gate.get() === PgLifecycleDatabaseGate.AUTHENTICATED_READY && state.clientEnd.get() == null)
        check(!state.fixtureClosing.get() && state.backendPid.get() > 0)
    }

    fun awaitClientDisposal(ordinal: Int, deadline: PgLifecycleDatabaseDeadline, childAlive: () -> Unit) {
        while (true) {
            progress()
            childAlive()
            deadline.checkRemaining()
            val selected = sessions.filter { it.state.ordinal == ordinal }
            if (selected.isNotEmpty() && selected.all { it.state.completed.get() && it.actorsEnded() }) {
                selected.forEach { it.state.requireClientDisposal(primary = !it.state.auxiliary) }
                return
            }
            deadline.pause()
        }
    }

    fun requireRecipe(case: PgLifecycleDatabaseCase, ordinal: Int) {
        val state = requireNotNull(primaries.get(ordinal)).state
        if (case.mode === PgLifecycleDatabaseMode.WRONG_PASSWORD) {
            check(state.gate.get() === PgLifecycleDatabaseGate.AUTHENTICATION_REFUSAL && state.errorState.get() == "28P01")
            check(state.authentication == listOf(10, 11) && state.backendPid.get() == 0 && state.statements.isEmpty())
        } else {
            check(state.gate.get() === PgLifecycleDatabaseGate.AUTHENTICATED_READY && state.errorState.get() == null)
            check(state.authentication == listOf(10, 11, 12, 0))
            val expected = buildList {
                if (case.recipe.readOnlySql(case.queryTimeout)) add(PgLifecycleDatabaseSql.READ_ONLY)
                if (case.recipe.catalogSql(case.queryTimeout)) add(PgLifecycleDatabaseSql.CATALOG)
            }
            check(state.statements == expected) { "Pinned constructor statement fingerprints differ; queryTimeout was not suppressed." }
        }
    }

    override fun close() {
        closing.set(true)
        val listenerClose = runCatching { listener.close() }
        // Wake every gate before joining the acceptor or any session; cleanup is intentionally not a disposal oracle.
        sessions.forEach { it.state.cleanupRelease() }
        val deadline = PgLifecycleDatabaseDeadline(5_000)
        val acceptor = runCatching { while (actor.isAlive) actor.join(deadline.millis(100)) }
        val connections = sessions.map { runCatching { it.close() } }
        (listOf(listenerClose, acceptor) + connections).forEach { it.getOrThrow() }
        check(!actor.isAlive && sessions.all { it.actorsEnded() })
    }
}
