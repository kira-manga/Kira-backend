package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException

internal class PgLifecycleDisconnectEvidenceTest {
    @ParameterizedTest
    @ValueSource(strings = ["SOCKET_IO", "IO", "TIMEOUT", "LOCAL_CLOSE", "TLS_ERROR", "TLS_TIMEOUT", "TLS_SOCKET_IO", "TLS_LOCAL_CLOSE"])
    fun `MODEL unrelated error cannot certify either TLS stage or plaintext disconnect`(kind: String) {
        val failure = failure(kind)
        assertFalse(PgLifecycleDisconnectEvidence.reset(failure))
        assertFalse(PgLifecycleDisconnectEvidence.tlsEofOrReset(failure))
        val raw = PgLifecycleDisconnectModelSocket { throw failure }
        val closing = AtomicBoolean()
        val plain = PgLifecycleNegotiationWire(raw, PersistenceTimeBudget.start(1_000), closing)
        assertThrows(Exception::class.java) { plain.disconnect(allowTerminate = false) }
        assertEquals(1, raw.reads)
        val tls = PgLifecycleTlsWire(raw, PersistenceTimeBudget.start(1_000), closing)
        assertThrows(Exception::class.java) { tls.awaitRawDisconnect() }
        assertEquals(2, raw.reads)
        val encrypted = PgLifecycleDisconnectModelTls({ throw failure })
        val before = raw.reads
        assertThrows(IllegalStateException::class.java) { tls.awaitEncryptedDisconnect(encrypted) }
        assertEquals(0, encrypted.closes)
        assertEquals(before, raw.reads) // Encrypted failure cannot be hidden by a later raw EOF.
        val cleanLayer = PgLifecycleDisconnectModelTls({ -1 })
        assertThrows(Exception::class.java) { tls.awaitEncryptedDisconnect(cleanLayer) }
        assertEquals(1, cleanLayer.closes) // Raw-stage failure occurs only after the layer-close barrier.
        assertTrue(raw.reads > before)
        assertFalse(raw.isClosed)
    }

    @ParameterizedTest
    @ValueSource(strings = ["EOF", "RESET", "TLS_EOF", "TLS_RESET"])
    fun `MODEL narrow TLS EOF and reset still require layer-close then an independent raw read`(kind: String) {
        val failure = when (kind) {
            "EOF" -> EOFException()
            "RESET" -> SocketException("Connection reset")
            "TLS_EOF" -> SSLException("MODEL wrapper", EOFException())
            else -> SSLException("MODEL wrapper", SocketException("Connection reset"))
        }
        assertTrue(PgLifecycleDisconnectEvidence.tlsEofOrReset(failure))
        var layerClosed = false
        val raw = PgLifecycleDisconnectModelSocket {
            check(layerClosed)
            -1
        }
        val encrypted = PgLifecycleDisconnectModelTls({ throw failure }, { layerClosed = true })
        PgLifecycleTlsWire(raw, PersistenceTimeBudget.start(1_000), AtomicBoolean()).awaitEncryptedDisconnect(encrypted)
        assertEquals(1, encrypted.closes)
        assertEquals(1, raw.reads)
        assertFalse(raw.isClosed)
    }

    @ParameterizedTest
    @ValueSource(strings = ["CLOSING", "RAW_CLOSED", "INPUT_SHUTDOWN", "OUTPUT_SHUTDOWN"])
    fun `MODEL cleanup during TLS closure cannot manufacture either disconnect stage`(kind: String) {
        val closing = AtomicBoolean()
        val raw = PgLifecycleDisconnectModelSocket { -1 }
        val wire = PgLifecycleTlsWire(raw, PersistenceTimeBudget.start(1_000), closing)
        val contaminate = {
            when (kind) {
                "CLOSING" -> closing.set(true)
                "RAW_CLOSED" -> raw.locallyClosed = true
                "INPUT_SHUTDOWN" -> raw.inputClosed = true
                else -> raw.outputClosed = true
            }
        }
        val encrypted = PgLifecycleDisconnectModelTls(
            read = {
                contaminate()
                -1
            },
        )
        assertThrows(IllegalStateException::class.java) { wire.awaitEncryptedDisconnect(encrypted) }
        assertEquals(0, encrypted.closes)
        assertEquals(0, raw.reads)
        closing.set(false)
        raw.locallyClosed = false
        raw.inputClosed = false
        raw.outputClosed = false
        val closingLayer = PgLifecycleDisconnectModelTls({ -1 }, contaminate)
        assertThrows(IllegalStateException::class.java) { wire.awaitEncryptedDisconnect(closingLayer) }
        assertEquals(1, closingLayer.closes)
        assertEquals(0, raw.reads)
    }

    @Test
    fun `MODEL a cyclic or overdeep TLS cause chain and reset-like messages are not accepted`() {
        val cycle = SSLException("MODEL cycle")
        cycle.initCause(SSLException("MODEL cycle", cycle))
        assertFalse(PgLifecycleDisconnectEvidence.tlsEofOrReset(cycle))
        var deep: IOException = SocketException("Connection reset")
        repeat(4) { deep = SSLException("MODEL depth", deep) }
        assertFalse(PgLifecycleDisconnectEvidence.tlsEofOrReset(deep))
        val subclass = object : SocketException("Connection reset") {}
        assertFalse(PgLifecycleDisconnectEvidence.reset(subclass))
        assertFalse(PgLifecycleDisconnectEvidence.tlsEofOrReset(SSLException("MODEL wrapper", subclass)))
        assertFalse(PgLifecycleDisconnectEvidence.reset(IOException("Connection reset")))
        listOf("Connection reset by peer", "Socket closed", "Broken pipe", "Input/output error", "Connection reset ").forEach { text ->
            assertFalse(PgLifecycleDisconnectEvidence.reset(SocketException(text)))
        }
    }

    private fun failure(kind: String): IOException = when (kind) {
        "SOCKET_IO" -> SocketException("Input/output error")
        "IO" -> IOException("MODEL non-socket error")
        "TIMEOUT" -> SocketTimeoutException("MODEL timeout")
        "LOCAL_CLOSE" -> SocketException("Socket closed")
        "TLS_ERROR" -> SSLException("MODEL unrelated TLS error")
        "TLS_TIMEOUT" -> SSLException("MODEL wrapper", SocketTimeoutException())
        "TLS_SOCKET_IO" -> SSLException("MODEL wrapper", SocketException("Input/output error"))
        else -> SSLException("MODEL wrapper", SocketException("Socket closed"))
    }
}
