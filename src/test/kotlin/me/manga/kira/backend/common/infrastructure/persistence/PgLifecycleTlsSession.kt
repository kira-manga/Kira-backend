package me.manga.kira.backend.common.infrastructure.persistence

import java.io.IOException
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

/** The server-side TLS wrapper is owned here. It never replaces or wraps the driver's stock LibPQFactory. */
internal class PgLifecycleTlsSession(
    index: Int,
    private val mode: PgLifecycleTlsMode,
    private val context: SSLContext,
    private val budget: PersistenceTimeBudget,
    private val closing: AtomicBoolean,
    problem: AtomicReference<Throwable?>,
) {
    val socket = AtomicReference<Socket?>()
    val tls = AtomicReference<SSLSocket?>()
    val armed = AtomicBoolean()
    val started = AtomicBoolean()
    val complete = AtomicBoolean()
    val authenticated = AtomicBoolean()
    val disconnected = AtomicBoolean()
    val requested = CountDownLatch(1)
    val allowTls = CountDownLatch(1)
    private val acceptedTls = AtomicBoolean()
    private val handshakeCompleted = AtomicBoolean()
    private val handshakeRejected = AtomicBoolean()
    private val clientHelloSeen = AtomicBoolean()
    private val partialSent = AtomicBoolean()
    private val startupSeen = AtomicBoolean()
    val worker: Thread = Thread.ofPlatform().name("synthetic-lifecycle-tls-peer-$index").inheritInheritableThreadLocals(false).unstarted {
        runCatching {
            serve()
            complete.set(true)
        }.onFailure { if (!closing.get()) problem.compareAndSet(null, it) }
    }

    private fun serve() {
        val raw = requireNotNull(socket.get())
        val request = PgProtocolChannel(raw, budget).startup()
        check(request.contentEquals(PgProtocolChannel.integer(80_877_103))) { "Expected SSLRequest, never plaintext StartupMessage." }
        requested.countDown()
        check(allowTls.await(budget.remainingMillis(3_000), TimeUnit.MILLISECONDS)) { "Synthetic TLS gate was not released." }
        raw.getOutputStream().apply {
            write('S'.code)
            flush()
        }
        acceptedTls.set(true)
        val wire = PgLifecycleTlsWire(raw, budget)
        if (mode === PgLifecycleTlsMode.PARTIAL_HANDSHAKE) {
            wire.sendPartialHandshakeAfterClientHello()
            partialSent.set(true)
            wire.awaitRawDisconnect()
        } else {
            handshake(raw, wire)
        }
        check(!closing.get()) { "Fixture cleanup cannot provide the client-disconnect witness." }
        disconnected.set(true)
    }

    private fun handshake(raw: Socket, wire: PgLifecycleTlsWire) {
        if (mode === PgLifecycleTlsMode.WRONG_CA) {
            val hello = wire.readClientHello()
            clientHelloSeen.set(true)
            // Standard server overload replays exactly the bounded bytes consumed above, then reads the raw socket.
            tls.set(context.socketFactory.createSocket(raw, hello.inputStream(), false) as SSLSocket)
        } else {
            tls.set(context.socketFactory.createSocket(raw, "127.0.0.1", raw.port, false) as SSLSocket)
        }
        val encrypted = requireNotNull(tls.get())
        encrypted.useClientMode = false
        encrypted.needClientAuth = false
        encrypted.wantClientAuth = false
        encrypted.enabledProtocols = arrayOf("TLSv1.2")
        encrypted.soTimeout = budget.remainingMillis(3_000).toInt()
        try {
            encrypted.startHandshake()
        } catch (failure: IOException) {
            if (mode !== PgLifecycleTlsMode.WRONG_CA || (failure !is SSLException && failure !is SocketException)) throw failure
            runCatching {
                check(!closing.get()) { "Fixture cleanup cannot provide a failed-handshake disconnect witness." }
                wire.awaitFailedHandshakeDisconnect(encrypted)
            }.onFailure { proof -> if (proof !== failure) proof.addSuppressed(failure) }.getOrThrow()
            handshakeRejected.set(true)
            return
        }
        handshakeCompleted.set(true)
        check(encrypted.session.protocol == "TLSv1.2" && encrypted.session.cipherSuite != "SSL_NULL_WITH_NULL_NULL")
        check(mode !== PgLifecycleTlsMode.WRONG_CA) { "The wrong existing CA unexpectedly trusted the server." }
        if (mode === PgLifecycleTlsMode.WRONG_HOST) {
            // Hostname verification is pgjdbc's post-handshake step. No encrypted StartupMessage may follow it.
            wire.awaitEncryptedDisconnect(encrypted, allowTerminate = false)
        } else {
            authenticate(encrypted)
            wire.awaitEncryptedDisconnect(encrypted)
        }
    }

    private fun authenticate(encrypted: SSLSocket) {
        val channel = PgProtocolChannel(encrypted, budget)
        check(channel.startup().take(4).toByteArray().contentEquals(PgProtocolChannel.integer(196_608)))
        startupSeen.set(true)
        channel.send('R', PgProtocolChannel.integer(3))
        check(channel.receive('p').contentEquals(PgProtocolChannel.zeroTerminated(PgProtocolPeer.PASSWORD)))
        authenticated.set(true)
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

    fun verify() {
        check(armed.get() && started.get() && complete.get() && disconnected.get())
        check(requested.count == 0L && acceptedTls.get())
        check(handshakeCompleted.get() == (mode === PgLifecycleTlsMode.MATCHED || mode === PgLifecycleTlsMode.WRONG_HOST))
        check(handshakeRejected.get() == (mode === PgLifecycleTlsMode.WRONG_CA))
        check(clientHelloSeen.get() == (mode === PgLifecycleTlsMode.WRONG_CA))
        check(partialSent.get() == (mode === PgLifecycleTlsMode.PARTIAL_HANDSHAKE))
        check(startupSeen.get() == (mode === PgLifecycleTlsMode.MATCHED) && authenticated.get() == startupSeen.get())
    }
}
