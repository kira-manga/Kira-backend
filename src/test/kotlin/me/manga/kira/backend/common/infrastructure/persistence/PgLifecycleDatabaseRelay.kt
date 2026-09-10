package me.manga.kira.backend.common.infrastructure.persistence

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicReferenceArray

/** Fixed plaintext relay to one owned PostgreSQL. At most one primary plus two AUX connections per request. */
internal class PgLifecycleDatabaseRelay(
    private val database: PgLifecycleDatabaseFixture,
    private val application: String,
    private val case: PgLifecycleDatabaseCase,
) : AutoCloseable {
    init {
        PgLifecycleDatabaseRelayFailure.prepareRuntime()
    }

    private val listener = ServerSocket()
    private val sessions = CopyOnWriteArrayList<PgLifecycleDatabaseRelaySession>()
    private val primaries = AtomicReferenceArray<PgLifecycleDatabaseRelaySession>(case.attempts)
    private val acceptedCount = AtomicInteger()
    private val primaryCount = AtomicInteger()
    private val weakCleanupThrough = AtomicInteger(-1)
    private val closing = AtomicBoolean()
    private val failed = AtomicReference<PgLifecycleDatabaseRelayFailure?>()
    private val actor = Thread.ofPlatform().name("w03-database-relay-accept").unstarted(::accept)

    val port: Int get() = listener.localPort

    init {
        check(case.attempts in 1..2)
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
                acceptSession(socket)
            }
        }.onFailure { failure ->
            captureAcceptorFailure(null, null, PgLifecycleDatabaseRelayStage.ACCEPT, failure)
        }
    }

    /** Same accepted-socket path in the real accept loop and inert capture controls; no listener/driver replacement. */
    internal fun acceptSession(
        socket: Socket,
        construct: (Int, (PgLifecycleDatabaseRelaySession, ByteArray) -> Unit) -> PgLifecycleDatabaseRelaySession = { index, registration ->
            PgLifecycleDatabaseRelaySession(socket, database.host, database.port, case, acceptedIndex = index, register = registration)
        },
    ): PgLifecycleDatabaseRelaySession {
        val acceptedIndex = acceptedCount.incrementAndGet() // Immediately after accept, before endpoint lookup/construction/start. Not an attempt ordinal.
        var stage = PgLifecycleDatabaseRelayStage.SESSION_CONSTRUCT
        var state: PgLifecycleDatabaseRelayState? = null
        var retained = false
        try {
            val session = construct(acceptedIndex, ::register)
            state = session.state
            stage = PgLifecycleDatabaseRelayStage.SESSION_RETAIN
            // The owning list is published before configuration, parsing, connecting, or starting the accepted socket's actor.
            sessions.add(session)
            retained = true
            check(sessions.size <= primaries.length() * 3) { "Synthetic relay connection bound exceeded." }
            stage = PgLifecycleDatabaseRelayStage.SESSION_START
            session.start()
            return session
        } catch (failure: Throwable) {
            // Capture before the original finally: an unretained close may still replace the thrown object, not its evidence.
            captureAcceptorFailure(acceptedIndex, state, stage, failure)
            throw failure
        } finally {
            if (!retained) closeUnretained(socket, acceptedIndex, state)
        }
    }

    private fun closeUnretained(socket: Socket, acceptedIndex: Int, state: PgLifecycleDatabaseRelayState?) {
        try {
            socket.close()
        } catch (failure: Throwable) {
            captureAcceptorFailure(acceptedIndex, state, PgLifecycleDatabaseRelayStage.UNRETAINED_CLOSE, failure)
            throw failure
        }
    }

    private fun captureAcceptorFailure(index: Int?, state: PgLifecycleDatabaseRelayState?, stage: PgLifecycleDatabaseRelayStage, failure: Throwable) {
        val fixtureClosing = closing.get()
        if (fixtureClosing && failure is SocketException) return
        failed.compareAndSet(
            null,
            PgLifecycleDatabaseRelayFailure(
                index,
                state?.association ?: PgLifecycleDatabaseRelayAssociation.UNREGISTERED,
                stage,
                PgLifecycleDatabaseRelayFailureType.of(failure),
                fixtureClosing,
            ),
        )
    }

    private fun register(session: PgLifecycleDatabaseRelaySession, startup: ByteArray) {
        when (PgLifecycleDatabaseWire.protocol(startup)) {
            PgLifecycleDatabaseWire.STARTUP -> {
                PgLifecycleDatabaseWire.requireStartup(startup, application)
                val ordinal = primaryCount.getAndIncrement()
                check(ordinal < primaries.length())
                session.state.ordinal = ordinal
                session.state.readyFault.bind(ordinal)
                check(primaries.compareAndSet(ordinal, null, session))
                session.state.publishAssociation(
                    if (ordinal == 0) PgLifecycleDatabaseRelayAssociation.PRIMARY_0 else PgLifecycleDatabaseRelayAssociation.PRIMARY_1,
                )
            }

            PgLifecycleDatabaseWire.CANCEL -> {
                val pid = PgLifecycleDatabaseWire.cancelPid(startup)
                val primary = (0 until primaries.length()).mapNotNull { primaries.get(it) }.single { it.state.backendPid.get() == pid }
                session.state.ordinal = primary.state.ordinal
                session.state.auxiliary = true
                check(sessions.count { it.state.auxiliary && it.state.ordinal == primary.state.ordinal } <= 2)
                session.state.publishAssociation(
                    if (session.state.ordinal == 0) PgLifecycleDatabaseRelayAssociation.AUX_0 else PgLifecycleDatabaseRelayAssociation.AUX_1,
                )
            }

            else -> error("Plaintext fixture received an unexpected startup protocol.")
        }
        // A delayed accepted AUX from an already sampled weak attempt is fixture cleanup, never a new upstream connection or disposal receipt.
        if (session.state.ordinal <= weakCleanupThrough.get()) session.cleanupBeforeConnect()
    }

    fun progress() {
        check(failed.get() == null && sessions.all { it.state.failure.get() == null }) { "Owned PostgreSQL relay failed." }
        check(!closing.get() && actor.isAlive) { "Owned PostgreSQL relay is no longer running." }
    }

    /** No progress assertion, I/O, waits, gate release or cleanup: callable before failure unwinds this relay's use scope. */
    fun diagnostic(ordinal: Int): String {
        val selected = if (ordinal in 0 until primaries.length()) primaries.get(ordinal)?.state else null
        val count = sessions.size
        val bound = primaries.length() * 3
        // Insertion precedes the bound check: include the one overflow offender, not only the six legal sessions.
        val limit = bound + 1
        val events = sessions.take(limit).mapNotNull { it.state.failure.get() }.joinToString(";") { it.diagnostic() }.ifEmpty { "NOT_RECORDED" }
        return "accepted=${acceptedCount.get()} retained=$count registered=${primaryCount.get()} " +
            "acceptor_state=${actor.state.name} acceptor_alive=${actor.isAlive} " +
            "relay_closing=${closing.get()} acceptor_failure=${failed.get() != null} session_failure=${sessions.any { it.state.failure.get() != null }} " +
            "primary_registered=${selected != null} ${selected?.diagnostic() ?: "gate=UNAVAILABLE"} " +
            "accept_event=${failed.get()?.diagnostic() ?: "NOT_RECORDED"} session_events=$events " +
            "event_overflow=${count > bound} events_omitted=${(count - limit).coerceAtLeast(0)}"
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
        check(state.readyFault.bytesWritten.get() == 6)
    }

    fun armFault(ordinal: Int) {
        progress()
        val state = requireNotNull(primaries.get(ordinal)).state
        check(state.isHeld() && state.gate.get() === PgLifecycleDatabaseGate.AUTHENTICATED_READY)
        check(!state.fixtureClosing.get() && state.clientEnd.get() == null)
        state.readyFault.arm(case, ordinal)
    }

    fun confirmDeadline(ordinal: Int) {
        progress()
        val state = requireNotNull(primaries.get(ordinal)).state
        check(state.gate.get() === PgLifecycleDatabaseGate.AUTHENTICATED_READY && !state.fixtureClosing.get() && state.clientEnd.get() == null)
        state.readyFault.confirmDeadline(case, ordinal)
    }

    fun releaseLateReady(ordinal: Int, deadline: PgLifecycleDatabaseDeadline, childAlive: () -> Unit) {
        val state = requireNotNull(primaries.get(ordinal)).state
        while (state.readyFault.bytesWritten.get() != 5) {
            progress()
            childAlive()
            check(state.clientEnd.get() == null && !state.fixtureClosing.get())
            deadline.pause()
        }
        deadline.checkRemaining()
        childAlive()
        check(state.clientEnd.get() == null && !state.fixtureClosing.get())
        state.readyFault.releaseFinal(case, ordinal)
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
                if (case.roleProbe) add(PgLifecycleDatabaseSql.ROLE)
                if (case.recipe.readOnlySql(case.queryTimeout)) add(PgLifecycleDatabaseSql.READ_ONLY)
                if (case.recipe.catalogSql(case.queryTimeout)) add(PgLifecycleDatabaseSql.CATALOG)
            }
            check(state.statements == expected) { "Pinned constructor statement fingerprints differ; queryTimeout was not suppressed." }
            state.role.requireEvidence()
            state.readyFault.requireEvidence(state.clientEndNanos.get())
            if (state.readyFault.kind !== PgLifecycleDatabaseReadyKind.COMPLETE) {
                println("PG_DATABASE_READY ${case.label} ordinal=$ordinal ${state.readyFault.summary(state.clientEndNanos.get())} proof=REAL_RELAY_WRITES")
            }
        }
    }

    fun weakBeforeCleanup(ordinal: Int): PgLifecycleDatabaseClientEnd? {
        check(case.originalProvider && !case.returnsRaw)
        progress()
        val state = requireNotNull(primaries.get(ordinal)).state
        check(!state.fixtureClosing.get() && state.gate.get() === PgLifecycleDatabaseGate.AUTHENTICATED_READY)
        return state.clientEnd.get()
    }

    fun cleanupWeakNoRaw(ordinal: Int, deadline: PgLifecycleDatabaseDeadline, childAlive: () -> Unit) {
        check(case.originalProvider && !case.returnsRaw)
        check(ordinal in 0 until case.attempts && weakCleanupThrough.compareAndSet(ordinal - 1, ordinal))
        var pending: Boolean
        do {
            progress()
            childAlive()
            deadline.checkRemaining()
            val selected = sessions.filter { (it.state.ordinal == ordinal || it.state.ordinal < 0) && !it.state.fixtureClosing.get() }
            val cleanup = selected.map { runCatching { it.close() } }
            cleanup.forEach { it.getOrThrow() } // Attempt every known close/join, even after one failed.
            pending = sessions.any {
                it.state.ordinal <= ordinal && (!it.state.fixtureClosing.get() || !it.actorsEnded())
            }
            if (pending) deadline.pause()
        } while (pending)
        val selected = sessions.filter { it.state.ordinal <= ordinal }
        check(selected.isNotEmpty() && selected.all { it.state.fixtureClosing.get() && it.actorsEnded() })
        childAlive()
        progress()
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
        check(failed.get() == null && sessions.all { it.state.failure.get() == null }) { "Owned relay failure remains failure after cleanup." }
    }
}
