package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Inert socket inputs drive the actual relay actors/capture cuts. Never a driver provider or native/DB disposal proof. */
internal class PgLifecycleDatabaseRelayModelSocket(
    private val input: InputStream = ByteArrayInputStream(byteArrayOf()),
    private val output: OutputStream = ByteArrayOutputStream(),
    private val configure: () -> Unit = {},
    private val connect: () -> Unit = {},
    private val closing: () -> Unit = {},
) : Socket() {
    val operations = AtomicInteger()
    val connects = AtomicInteger()
    val closes = AtomicInteger()
    private val closed = AtomicBoolean()

    override fun setSoTimeout(timeout: Int) {
        operations.incrementAndGet()
        check(timeout == 15_000)
        configure()
    }

    override fun setTcpNoDelay(on: Boolean) {
        operations.incrementAndGet()
        check(on)
    }

    override fun connect(endpoint: SocketAddress, timeout: Int) {
        operations.incrementAndGet()
        connects.incrementAndGet()
        check(timeout == 1_000)
        connect()
    }

    override fun getInputStream(): InputStream {
        operations.incrementAndGet()
        return input
    }

    override fun getOutputStream(): OutputStream {
        operations.incrementAndGet()
        return output
    }

    override fun isClosed(): Boolean = closed.get()

    override fun isInputShutdown(): Boolean = false

    override fun close() {
        operations.incrementAndGet()
        closes.incrementAndGet()
        closed.set(true)
        closing()
    }
}

/** The relay's ServerSocket remains unbound and the PostgreSQL fixture is never started. */
internal class PgLifecycleDatabaseRelayEvidenceFixture(
    val case: PgLifecycleDatabaseCase = PgLifecycleDatabaseCase(
        PgLifecycleDatabaseRecipe.DEFAULT,
        0,
        PgLifecycleDatabaseLane.ORDINARY,
        PgLifecycleDatabaseMode.ORIGINAL_MATRIX,
    ),
    private val expectedCloseFailure: Class<out Throwable>? = IllegalStateException::class.java,
) : AutoCloseable {
    val relay = PgLifecycleDatabaseRelay(PgLifecycleDatabaseFixture(), APPLICATION, case)
    val sessions = mutableListOf<PgLifecycleDatabaseRelaySession>()
    val sockets = mutableListOf<PgLifecycleDatabaseRelayModelSocket>()
    val releases = mutableListOf<CountDownLatch>()

    fun accept(
        client: PgLifecycleDatabaseRelayModelSocket = PgLifecycleDatabaseRelayModelSocket(relayEvidenceInput(startup())),
        upstream: PgLifecycleDatabaseRelayModelSocket = PgLifecycleDatabaseRelayModelSocket(connect = { throw ConnectException() }),
        beforeStart: (PgLifecycleDatabaseRelaySession, (PgLifecycleDatabaseRelaySession, ByteArray) -> Unit) -> Unit = { _, _ -> },
    ): PgLifecycleDatabaseRelaySession = relay.acceptSession(client) { index, registration ->
        sockets.add(client)
        sockets.add(upstream)
        PgLifecycleDatabaseRelaySession(client, "127.0.0.1", 1, case, index, upstream, registration).also {
            sessions.add(it)
            beforeStart(it, registration)
        }
    }

    fun ended(session: PgLifecycleDatabaseRelaySession): PgLifecycleDatabaseRelayFailure? {
        awaitLifecycleFact(5_000) { session.actorsEnded() }
        return session.state.failure.get()
    }

    override fun close() {
        releases.forEach(CountDownLatch::countDown)
        try {
            if (expectedCloseFailure == null) relay.close() else assertThrows(expectedCloseFailure) { relay.close() }
        } finally {
            check(sessions.all { it.actorsEnded() }) { "MODEL relay actors did not actually end." }
        }
    }

    companion object {
        const val APPLICATION = "w03c_00000000-0000-0000-0000-000000000029"

        fun startup(): ByteArray {
            val values = "application_name\u0000$APPLICATION\u0000user\u0000${PgLifecycleDatabaseSettings.CANDIDATE}\u0000" +
                "database\u0000${PgLifecycleDatabaseSettings.DATABASE}\u0000\u0000"
            val bytes = values.toByteArray(Charsets.US_ASCII)
            return ByteBuffer.allocate(bytes.size + 8).putInt(bytes.size + 8).putInt(PgLifecycleDatabaseWire.STARTUP).put(bytes).array()
        }

        fun cancel(pid: Int): ByteArray = ByteBuffer.allocate(16).putInt(16).putInt(PgLifecycleDatabaseWire.CANCEL).putInt(pid).putInt(0).array()
    }
}

/** Exact finite startup prefix, followed only by a bounded test-controlled pump input. */
internal fun relayEvidenceInput(prefix: ByteArray = byteArrayOf(), after: () -> Int = { -1 }): InputStream = object : InputStream() {
    private val bytes = ByteArrayInputStream(prefix)

    override fun read(): Int = bytes.read().let { if (it < 0) after() else it }
}

/** Renderer maxima only, not an attainable wire transcript: all winning events precede later MODEL association/cleanup. */
internal fun pgLifecycleDatabaseWorstRelayEvidence(case: PgLifecycleDatabaseCase): PgLifecycleDatabaseRelayEvidenceFixture {
    val fixture = PgLifecycleDatabaseRelayEvidenceFixture(case)
    try {
        val last = case.attempts * 3
        repeat(last + 1) { position ->
            val construct = {
                fixture.accept(upstream = PgLifecycleDatabaseRelayModelSocket()) { session, registration ->
                    val state = session.state
                    state.captureFailure(PgLifecycleDatabaseRelayStage.CLEANUP_UPSTREAM_CLOSE, SocketTimeoutException())
                    if (position < case.attempts) registration(session, PgLifecycleDatabaseRelayEvidenceFixture.startup())
                    state.authentication.addAll(List(4) { 11 })
                    state.errorState.set("28P01")
                    state.gate.set(PgLifecycleDatabaseGate.AUTHENTICATION_REFUSAL)
                    state.originOrder.set(Long.MIN_VALUE)
                    state.upstreamCloseOrder.set(Long.MIN_VALUE)
                    session.close() // Keeps every actor NEW; a later start is permanently prevented by the existing state machine.
                }
            }
            if (position == last) assertThrows(IllegalStateException::class.java) { construct() } else construct()
        }
        return fixture
    } catch (failure: Throwable) {
        runCatching { fixture.close() }.exceptionOrNull()?.let(failure::addSuppressed)
        throw failure
    }
}
