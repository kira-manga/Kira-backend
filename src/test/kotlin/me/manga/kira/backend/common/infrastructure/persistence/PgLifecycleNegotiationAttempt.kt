package me.manga.kira.backend.common.infrastructure.persistence

import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/** One fixed first/final transport pair. No fixture raw close occurs on the successful evidence path. */
internal class PgLifecycleNegotiationAttempt(
    private val mode: PgLifecycleNegotiationMode,
    private val context: SSLContext?,
    private val budget: PersistenceTimeBudget,
    private val closing: AtomicBoolean,
) {
    val first = AtomicReference<Socket?>()
    val successor = AtomicReference<Socket?>()
    val tls = AtomicReference<SSLSocket?>()
    val armed = AtomicBoolean()
    val initialSeen = CountDownLatch(1)
    val allowResponse = CountDownLatch(1)
    val finalStartup = CountDownLatch(1)
    val allowAuthentication = CountDownLatch(1)
    val heldGate = AtomicReference(PgLifecycleNegotiationCut.NONE)
    val predecessorDisconnected = AtomicBoolean()
    val authenticated = AtomicBoolean()
    val disconnected = AtomicBoolean()
    val complete = AtomicBoolean()
    private val sslRequests = AtomicInteger()
    private val plaintextStartups = AtomicInteger()
    private val encryptedStartups = AtomicInteger()
    private val sentResponse = AtomicReference<Char?>()
    private val handshakeCompleted = AtomicBoolean()
    private val authRefused = AtomicBoolean()
    private val startupRefused = AtomicBoolean()
    private val serverEof = AtomicBoolean()

    fun serve(acceptSuccessor: (AtomicReference<Socket?>) -> Socket) {
        val raw = requireNotNull(first.get())
        val firstWire = wire(raw)
        if (mode.firstRequestsSsl) sslRequest(firstWire) else startup(firstWire, encrypted = false)
        initialSeen.countDown()
        gate(allowResponse, PgLifecycleNegotiationCut.INITIAL_RESPONSE)
        if (mode.response != null) {
            respond(firstWire, mode.response)
            if (mode === PgLifecycleNegotiationMode.PREFER_N) {
                succeed(raw, firstWire, encrypted = false)
                complete.set(true)
                return
            }
            firstWire.disconnect(allowTerminate = false)
        } else {
            rejectFirst(raw, firstWire)
        }
        check(!closing.get() && !raw.isClosed && !raw.isInputShutdown)
        if (mode.rotates) {
            predecessorDisconnected.set(true) // Separate wire receipt BEFORE accepting the successor, never fixture-close evidence.
            val next = acceptSuccessor(successor)
            check(next !== raw && predecessorDisconnected.get())
            if (mode.finalUsesTls) {
                val nextWire = wire(next)
                sslRequest(nextWire)
                succeed(next, wire(handshake(next, nextWire)), encrypted = true)
            } else {
                succeed(next, wire(next), encrypted = false)
            }
        } else {
            check(!mode.succeeds)
            disconnected.set(true)
        }
        complete.set(true)
    }

    private fun rejectFirst(raw: Socket, firstWire: PgLifecycleNegotiationWire) {
        when (mode) {
            PgLifecycleNegotiationMode.PREFER_RESPONSE_TIMEOUT -> {
                // No response, no half-close and no fixture timer injection. The stock client's explicit 750ms response timeout must cause its close/retry.
                firstWire.disconnect(allowTerminate = false)
            }

            PgLifecycleNegotiationMode.NEXT_HOST -> {
                firstWire.rejectStartup()
                startupRefused.set(true)
                firstWire.disconnect(allowTerminate = false)
            }

            PgLifecycleNegotiationMode.PREFER_TLS_28000 -> {
                val encrypted = handshake(raw, firstWire)
                val encryptedWire = wire(encrypted)
                startup(encryptedWire, encrypted = true)
                encryptedWire.rejectAuthentication()
                authRefused.set(true)
                tlsDisconnect(raw, encrypted, allowTerminate = false)
            }

            PgLifecycleNegotiationMode.ALLOW_28000_TLS -> {
                firstWire.rejectAuthentication()
                authRefused.set(true)
                firstWire.disconnect(allowTerminate = false)
            }

            PgLifecycleNegotiationMode.ALLOW_IO_TLS -> {
                firstWire.injectServerEof() // Complete plaintext StartupMessage, then server output EOF; input stays locally open.
                serverEof.set(true)
                firstWire.disconnect(allowTerminate = false, outputShutdown = true)
            }

            else -> error("Unexpected fixed negotiation recipe.")
        }
    }

    private fun succeed(raw: Socket, finalWire: PgLifecycleNegotiationWire, encrypted: Boolean) {
        startup(finalWire, encrypted)
        finalStartup.countDown()
        gate(allowAuthentication, PgLifecycleNegotiationCut.FINAL_STARTUP)
        finalWire.authenticate()
        authenticated.set(true)
        if (encrypted) tlsDisconnect(raw, requireNotNull(tls.get()), allowTerminate = true) else finalWire.disconnect(allowTerminate = true)
        check(!closing.get())
        disconnected.set(true)
    }

    private fun handshake(raw: Socket, rawWire: PgLifecycleNegotiationWire): SSLSocket {
        respond(rawWire, 'S')
        check(tls.get() == null)
        tls.set(requireNotNull(context).socketFactory.createSocket(raw, "127.0.0.1", raw.port, false) as SSLSocket)
        val encrypted = requireNotNull(tls.get())
        encrypted.useClientMode = false
        encrypted.needClientAuth = false
        encrypted.wantClientAuth = false
        encrypted.enabledProtocols = arrayOf("TLSv1.2")
        encrypted.soTimeout = budget.remainingMillis(3_000).toInt()
        encrypted.startHandshake()
        check(encrypted.session.protocol == "TLSv1.2" && encrypted.session.cipherSuite != "SSL_NULL_WITH_NULL_NULL")
        handshakeCompleted.set(true)
        return encrypted
    }

    private fun tlsDisconnect(raw: Socket, encrypted: SSLSocket, allowTerminate: Boolean) {
        check(!closing.get() && !raw.isClosed && !raw.isInputShutdown && !raw.isOutputShutdown)
        PgLifecycleTlsWire(raw, budget, closing).awaitEncryptedDisconnect(encrypted, allowTerminate)
        check(!closing.get() && !raw.isClosed && !raw.isInputShutdown && !raw.isOutputShutdown)
    }

    private fun sslRequest(wire: PgLifecycleNegotiationWire) {
        wire.sslRequest()
        check(sslRequests.incrementAndGet() == 1)
    }

    private fun respond(wire: PgLifecycleNegotiationWire, value: Char) {
        check(sentResponse.compareAndSet(null, value))
        wire.sslResponse(value)
    }

    private fun startup(wire: PgLifecycleNegotiationWire, encrypted: Boolean) {
        wire.startup()
        check((if (encrypted) encryptedStartups else plaintextStartups).incrementAndGet() <= 2)
    }

    private fun wire(socket: Socket) = PgLifecycleNegotiationWire(socket, budget, closing)

    private fun gate(latch: CountDownLatch, phase: PgLifecycleNegotiationCut) {
        check(heldGate.compareAndSet(PgLifecycleNegotiationCut.NONE, phase))
        try {
            check(latch.await(budget.remainingMillis(3_000), TimeUnit.MILLISECONDS) && !closing.get()) { "Synthetic negotiation gate was not released." }
        } finally {
            heldGate.set(PgLifecycleNegotiationCut.NONE)
        }
    }

    fun releaseAll() {
        allowResponse.countDown()
        allowAuthentication.countDown()
    }

    fun verify() {
        check(armed.get() && initialSeen.count == 0L && complete.get() && disconnected.get())
        check((first.get() != null) && ((successor.get() != null) == mode.rotates) && predecessorDisconnected.get() == mode.rotates)
        check(authenticated.get() == mode.succeeds && (finalStartup.count == 0L) == mode.succeeds)
        check(sslRequests.get() == if (mode.firstRequestsSsl || mode.finalUsesTls) 1 else 0)
        check(sentResponse.get() == (mode.response ?: if (mode.realTls) 'S' else null))
        check(handshakeCompleted.get() == mode.realTls && ((tls.get() != null) == mode.realTls))
        check(encryptedStartups.get() == if (mode.realTls) 1 else 0)
        val expectedPlaintext = (if (mode.firstRequestsSsl) 0 else 1) + (if (mode.succeeds && !mode.finalUsesTls) 1 else 0)
        check(plaintextStartups.get() == expectedPlaintext)
        check(authRefused.get() == (mode === PgLifecycleNegotiationMode.PREFER_TLS_28000 || mode === PgLifecycleNegotiationMode.ALLOW_28000_TLS))
        check(startupRefused.get() == (mode === PgLifecycleNegotiationMode.NEXT_HOST))
        check(serverEof.get() == (mode === PgLifecycleNegotiationMode.ALLOW_IO_TLS))
    }
}
