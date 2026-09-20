package me.manga.kira.backend.complaint.api

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.ServletOutputStream
import jakarta.servlet.WriteListener
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.complaint.application.ComplaintInstallationBootstrapService
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintInstallationBootstrap
import me.manga.kira.backend.complaint.domain.ComplaintInstallationBootstrapFailure
import me.manga.kira.backend.complaint.domain.ComplaintInstallationBootstrapReadPort
import me.manga.kira.backend.complaint.domain.ComplaintInstallationBootstrapRejected
import me.manga.kira.backend.security.ComplaintAdmissionFailure
import me.manga.kira.backend.security.ComplaintAdmissionRejected
import me.manga.kira.backend.security.ComplaintHttpIngressBridge
import me.manga.kira.backend.security.ComplaintIngressContext
import me.manga.kira.backend.security.MutableAdmissionTestClock
import me.manga.kira.backend.security.admissionTestKey
import me.manga.kira.backend.security.historyTestIngress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.spy
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.util.Enumeration
import java.util.UUID
import java.util.concurrent.CancellationException

/** Real handler/encoder/admission, fake domain data only. Registered SQL and the actual mount have separate ITs. */
class ComplaintInstallationBootstrapHttpTest {
    private val mapper = ObjectMapper()

    @Test
    fun `exact two field body is canonical bounded and ignores every bearer family and validator`() {
        val f = Fixture()
        for (token in listOf(null, "not-a-bearer", "Bearer user-token", "Bearer installation-token", "Bearer")) {
            val response = f.request(bootstrapTestRequest().apply {
                token?.let { addHeader("Authorization", it); addHeader("Authorization", "different malformed credential") }
                addHeader("If-None-Match", "*")
            })
            assertEquals(200, response.status)
            assertEquals("""{"dataScopeId":"${f.scope.id}","contractVersion":1}""", response.contentAsString)
            assertHeaders(response, "application/json")
            val body = mapper.readTree(response.contentAsByteArray)
            assertEquals(setOf("dataScopeId", "contractVersion"), body.fieldNames().asSequence().toSet())
            assertTrue(body["contractVersion"].isIntegralNumber)
            assertEquals(1, body["contractVersion"].asInt())
        }
        val unreadableAuth = object : MockHttpServletRequest("GET", ComplaintInstallationBootstrapHttpHandler.PATH) {
            override fun getHeaders(name: String): Enumeration<String> {
                check(!name.equals("Authorization", ignoreCase = true)) { "Bootstrap inspected Authorization" }
                return super.getHeaders(name)
            }
        }.apply { remoteAddr = "192.0.2.1" }
        assertEquals(200, f.request(unreadableAuth).status)
        assertEquals(6, f.reads)
    }

    @Test
    fun `exact route framing headers and EOF are checked before the domain or semantic admission`() {
        for ((request, status) in invalidRequests()) {
            val f = Fixture()
            val response = f.request(request)
            assertEquals(status, response.status, request.requestURI)
            assertEquals(0, f.reads)
            assertHeaders(response, "application/problem+json", head = request.method == "HEAD")
        }
        for (byte in listOf(-1, 65, null)) {
            val f = Fixture()
            val request = ProbeRequest(f, byte)
            val response = f.request(request)
            assertEquals(if (byte == -1) 200 else 400, response.status)
            assertEquals(1, request.reads)
            assertEquals(if (byte == -1) 1 else 0, f.reads)
        }
    }

    @Test
    fun `admitted dispatch reuses one original ingress and refuses fabricated or finished contexts`() {
        val f = Fixture()
        val bridge = ComplaintHttpIngressBridge(f.ingress)
        var retained: ComplaintIngressContext? = null
        val request = bootstrapTestRequest()
        val response = MockHttpServletResponse()
        bridge.doFilter(request, response) { admitted, target ->
            val http = admitted as HttpServletRequest
            retained = bridge.authenticationContext(http)
            assertTrue(f.handler.validateWithinIngress(http, target as HttpServletResponse, checkNotNull(retained)))
            f.handler.handleWithinIngress(http, target, bridge.claimHandler(http))
        }
        assertEquals(200, response.status)
        assertSame(retained, f.context)
        for (context in listOf(ComplaintIngressContext(), checkNotNull(retained))) {
            val refused = MockHttpServletResponse()
            f.handler.handleWithinIngress(bootstrapTestRequest(), refused, context)
            assertEquals(503, refused.status)
        }
        assertEquals(1, f.reads)
    }

    @Test
    fun `existing bootstrap quota and key overlap return real 429 retry without a fallback scope`() {
        val f = Fixture()
        repeat(120) { assertEquals(200, f.request(bootstrapTestRequest()).status) }
        f.clock.value = 61_000_000_000L // The separate ingress minute expires; the semantic hour does not.
        f.ingress.rotate(admissionTestKey(2))
        val refused = f.request(bootstrapTestRequest().apply { addHeader("X-Forwarded-For", "198.51.100.9") })
        assertEquals(429, refused.status)
        assertEquals("3539", refused.getHeader("Retry-After"))
        assertFalse(refused.contentAsString.contains("dataScopeId"))
        assertEquals(120, f.reads, "A denied semantic attempt never reaches the fake read.")
        assertEquals(200, f.request(bootstrapTestRequest().apply { remoteAddr = "192.0.2.2" }).status)
        f.clock.value = 3_600_000_000_000L
        assertEquals(200, f.request(bootstrapTestRequest()).status)
    }

    @Test
    fun `fixed problems distinguish coordination unavailable and unexpected resource failures from rate limits`() {
        for (kind in ComplaintInstallationBootstrapFailure.entries) {
            val f = Fixture().apply { failure = ComplaintInstallationBootstrapRejected(kind) }
            val response = f.request(bootstrapTestRequest())
            assertEquals(kind.status, response.status)
            assertHeaders(response, "application/problem+json")
            assertEquals(kind.code, mapper.readTree(response.contentAsByteArray)["errors"][0]["code"].asText())
            assertFalse(response.contentAsString.contains(f.scope.id.toString()))
        }
        for (failure in listOf(ComplaintAdmissionRejected(ComplaintAdmissionFailure.UNAVAILABLE), IllegalStateException("private provider details"), OutOfMemoryError("private allocation"))) {
            val f = Fixture().apply { this.failure = failure }
            val response = f.request(bootstrapTestRequest())
            assertEquals(503, response.status)
            assertNull(response.getHeader("Retry-After"))
            assertFalse(response.contentAsString.contains("private"))
            if (failure !is ComplaintAdmissionRejected) {
                f.failure = null
                assertEquals(503, f.request(bootstrapTestRequest()).status, "Unexpected resource failure closes this writer.")
                assertEquals(1, f.reads)
            }
        }
        // Catch-after-read failure, not a refusal before the handler is selected. Both the direct
        // handler and its original bridge must preserve unresolved custody without touching output.
        for (throughBridge in listOf(false, true)) {
            val f = Fixture()
            val response = spy(MockHttpServletResponse())
            val key = Any()
            val sentinel = Any()
            f.onRead = {
                TransactionSynchronizationManager.bindResource(key, sentinel)
                clearInvocations(response) // The bridge's safe pre-read headers are not post-failure delivery.
                throw ComplaintInstallationBootstrapRejected(ComplaintInstallationBootstrapFailure.UNAVAILABLE)
            }
            try {
                assertThrows<PersistencePhaseException> {
                    if (throughBridge) {
                        val bridge = ComplaintHttpIngressBridge(f.ingress)
                        bridge.doFilter(bootstrapTestRequest(), response) { admitted, target ->
                            val request = admitted as HttpServletRequest
                            f.handler.handleWithinIngress(request, target as HttpServletResponse, bridge.claimHandler(request))
                        }
                    } else f.handler.handleRequest(bootstrapTestRequest(), response)
                }
                verifyNoInteractions(response)
                assertSame(sentinel, TransactionSynchronizationManager.getResource(key))
                assertEquals(1, f.reads)
                assertTrue(response.contentAsByteArray.isEmpty())
            } finally {
                assertSame(sentinel, TransactionSynchronizationManager.unbindResource(key))
            }
            f.ingress.withIngress(bootstrapTestRequest()) {} // Only the test's original custody has now ended.
        }
    }

    @Test
    fun `partial delivery never appends a problem and cancellation or interrupt keeps its signal`() {
        for (unexpected in listOf(false, true)) {
            val f = Fixture()
            val response = PartialResponse(unexpected)
            val failure = assertThrows<IOException> { f.handler.handleRequest(bootstrapTestRequest(), response) }
            assertEquals("Installation bootstrap delivery failed.", failure.message)
            assertNull(failure.cause)
            assertTrue(failure.suppressed.isEmpty())
            assertEquals(24, response.prefix.size())
            assertEquals(200, response.status)
            assertEquals(if (unexpected) 503 else 200, f.request(bootstrapTestRequest()).status)
        }
        val cancellation = CancellationException("synthetic")
        assertSame(cancellation, assertThrows<CancellationException> { Fixture().apply { failure = cancellation }.request(bootstrapTestRequest()) })
        try {
            assertThrows<InterruptedIOException> { Fixture().apply { failure = InterruptedIOException("synthetic") }.request(bootstrapTestRequest()) }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    private fun invalidRequests(): List<Pair<MockHttpServletRequest, Int>> = listOf(
        bootstrapTestRequest().apply { method = "POST" } to 404,
        bootstrapTestRequest().apply { method = "HEAD" } to 404,
        bootstrapTestRequest().apply { requestURI += "/" } to 404,
        bootstrapTestRequest().apply { requestURI = "/api/v1/installations/%62ootstrap" } to 404,
        bootstrapTestRequest().apply { queryString = "" } to 400,
        bootstrapTestRequest().apply { setContent(byteArrayOf(1)) } to 400,
        bootstrapTestRequest().apply { addHeader("Content-Length", "00") } to 400,
        bootstrapTestRequest().apply { addHeader("Content-Length", " 0") } to 400,
        bootstrapTestRequest().apply { addHeader("Content-Length", "9".repeat(65)) } to 400,
        bootstrapTestRequest().apply { addHeader("Transfer-Encoding", "chunked") } to 400,
        bootstrapTestRequest().apply { addHeader("Content-Encoding", "gzip") } to 415,
        bootstrapTestRequest().apply { addHeader("X-Kira-Complaint-Contract", "2") } to 400,
        bootstrapTestRequest().apply { addHeader("X-Kira-Complaint-Contract", "1\n") } to 400,
    ) + listOf("Content-Length", "Content-Encoding", "Content-Type", "X-Kira-Complaint-Contract").map { header ->
        // MockHttpServletRequest.addHeader replaces Content-Type instead of preserving duplicates.
        // Expose the actual two raw values to the handler; do not turn this into a singleton case.
        object : MockHttpServletRequest("GET", ComplaintInstallationBootstrapHttpHandler.PATH) {
            override fun getHeaders(name: String): Enumeration<String> =
                if (name.equals(header, ignoreCase = true)) java.util.Collections.enumeration(listOf("0", "0"))
                else super.getHeaders(name)
        }.apply { remoteAddr = "192.0.2.1" } to 400
    }

    private fun assertHeaders(response: MockHttpServletResponse, media: String, head: Boolean = false) {
        assertEquals(listOf("1"), response.getHeaders("X-Kira-Complaint-Contract").toList())
        assertEquals(listOf("no-store, no-transform"), response.getHeaders("Cache-Control").toList())
        assertEquals("$media;charset=UTF-8", response.contentType)
        assertTrue(response.contentLength in 1..ComplaintInstallationBootstrapHttpResponses.MAX_BYTES)
        assertEquals(if (head) 0 else response.contentLength, response.contentAsByteArray.size)
        for (name in listOf("ETag", "Location", "Content-Encoding", "Transfer-Encoding", "WWW-Authenticate", "Set-Cookie")) assertNull(response.getHeader(name))
        assertNull(response.redirectedUrl)
    }

    private class Fixture {
        val clock = MutableAdmissionTestClock()
        val ingress = historyTestIngress(clock)
        val scope = ComplaintDataScope.of(UUID.randomUUID())
        var reads = 0
        var failure: Throwable? = null
        var onRead: () -> Unit = {}
        var context: ComplaintIngressContext? = null
        val handler = ComplaintInstallationBootstrapHttpHandler(ComplaintInstallationBootstrapService(ComplaintInstallationBootstrapReadPort { original ->
            val selected = original as ComplaintIngressContext
            ingress.requireLiveContext(selected)
            ingress.chargeBootstrap(selected)
            context = selected
            reads++
            onRead()
            failure?.let { throw it }
            ComplaintInstallationBootstrap(scope)
        }), ingress)

        fun request(request: MockHttpServletRequest): MockHttpServletResponse = MockHttpServletResponse().also { handler.handleRequest(request, it) }
    }

    private class ProbeRequest(private val fixture: Fixture, private val value: Int?) : MockHttpServletRequest("GET", ComplaintInstallationBootstrapHttpHandler.PATH) {
        var reads = 0

        init { remoteAddr = "192.0.2.1"; addHeader("Content-Length", "0"); addHeader("Content-Encoding", "identity") }

        override fun getContentLengthLong(): Long = -1

        override fun getInputStream(): ServletInputStream {
            assertThrows<ComplaintAdmissionRejected> { fixture.ingress.withIngress(this) { error("Nested ingress") } }
            return object : ServletInputStream() {
                override fun isFinished(): Boolean = value == -1
                override fun isReady(): Boolean = true
                override fun setReadListener(listener: ReadListener) = Unit
                override fun read(): Int { reads++; return value ?: throw IOException("Synthetic input failure") }
            }
        }
    }

    private class PartialResponse(private val unexpected: Boolean) : MockHttpServletResponse() {
        val prefix = ByteArrayOutputStream()
        override fun getOutputStream(): ServletOutputStream = object : ServletOutputStream() {
            override fun isReady(): Boolean = true
            override fun setWriteListener(listener: WriteListener) = Unit
            override fun write(value: Int) {
                prefix.write(value)
                if (prefix.size() == 24) {
                    if (unexpected) error("Synthetic private failure")
                    throw IOException("Synthetic private failure")
                }
            }
        }
    }
}

internal fun bootstrapTestRequest(): MockHttpServletRequest =
    MockHttpServletRequest("GET", ComplaintInstallationBootstrapHttpHandler.PATH).apply { remoteAddr = "192.0.2.1" }
