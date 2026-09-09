package me.manga.kira.backend.common.infrastructure.persistence

import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import javax.net.ssl.HandshakeCompletedListener
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket

/** Inert MODEL inputs for oracle negatives; never a driver/client provider or evidence of a native read/handshake. */
internal class PgLifecycleDisconnectModelSocket(private val read: () -> Int) : Socket() {
    var reads = 0
        private set
    var locallyClosed = false
    var inputClosed = false
    var outputClosed = false

    override fun getInputStream(): InputStream = object : InputStream() {
        override fun read(): Int {
            reads++
            return this@PgLifecycleDisconnectModelSocket.read()
        }
    }

    override fun getOutputStream(): OutputStream = object : OutputStream() {
        override fun write(value: Int) {
            error("MODEL does not write wire traffic.")
        }
    }

    override fun setSoTimeout(timeout: Int) = check(timeout > 0)

    override fun isClosed(): Boolean = locallyClosed

    override fun isInputShutdown(): Boolean = inputClosed

    override fun isOutputShutdown(): Boolean = outputClosed

    override fun close() {
        locallyClosed = true
    }
}

/** Detached MODEL TLS read/close only. All handshake/configuration methods refuse use rather than simulating real TLS. */
internal class PgLifecycleDisconnectModelTls(private val read: () -> Int, private val afterClose: () -> Unit = {}) : SSLSocket() {
    var closes = 0
        private set
    var inputClosed = false

    override fun getInputStream(): InputStream = object : InputStream() {
        override fun read(): Int = this@PgLifecycleDisconnectModelTls.read()
    }

    override fun setSoTimeout(timeout: Int) = check(timeout > 0)

    override fun isClosed(): Boolean = closes > 0

    override fun isInputShutdown(): Boolean = inputClosed

    override fun close() {
        closes++
        afterClose()
    }

    override fun getSupportedCipherSuites(): Array<String> = error("MODEL does not perform TLS.")

    override fun getEnabledCipherSuites(): Array<String> = error("MODEL does not perform TLS.")

    override fun setEnabledCipherSuites(suites: Array<out String>?) = error("MODEL does not perform TLS.")

    override fun getSupportedProtocols(): Array<String> = error("MODEL does not perform TLS.")

    override fun getEnabledProtocols(): Array<String> = error("MODEL does not perform TLS.")

    override fun setEnabledProtocols(protocols: Array<out String>?) = error("MODEL does not perform TLS.")

    override fun getSession(): SSLSession = error("MODEL does not perform TLS.")

    override fun addHandshakeCompletedListener(listener: HandshakeCompletedListener?) = error("MODEL does not perform TLS.")

    override fun removeHandshakeCompletedListener(listener: HandshakeCompletedListener?) = error("MODEL does not perform TLS.")

    override fun startHandshake() = error("MODEL does not perform TLS.")

    override fun setUseClientMode(mode: Boolean) = error("MODEL does not perform TLS.")

    override fun getUseClientMode(): Boolean = error("MODEL does not perform TLS.")

    override fun setNeedClientAuth(need: Boolean) = error("MODEL does not perform TLS.")

    override fun getNeedClientAuth(): Boolean = error("MODEL does not perform TLS.")

    override fun setWantClientAuth(want: Boolean) = error("MODEL does not perform TLS.")

    override fun getWantClientAuth(): Boolean = error("MODEL does not perform TLS.")

    override fun setEnableSessionCreation(flag: Boolean) = error("MODEL does not perform TLS.")

    override fun getEnableSessionCreation(): Boolean = error("MODEL does not perform TLS.")
}
