package me.manga.kira.backend.complaint.api

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.ServletOutputStream
import jakarta.servlet.WriteListener
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import me.manga.kira.backend.common.web.DisabledComplaintRoutesFilter
import me.manga.kira.backend.complaint.application.ComplaintInstallationMeService
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintInstallationMe
import me.manga.kira.backend.complaint.domain.ComplaintInstallationMeAuthentication
import me.manga.kira.backend.complaint.domain.ComplaintInstallationMeFailure
import me.manga.kira.backend.complaint.domain.ComplaintInstallationMeReadPort
import me.manga.kira.backend.complaint.domain.ComplaintInstallationMeRejected
import me.manga.kira.backend.complaint.domain.ComplaintInstallationRequestContext
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import me.manga.kira.backend.security.ComplaintAdmissionFailure
import me.manga.kira.backend.security.ComplaintAdmissionRejected
import me.manga.kira.backend.security.ComplaintHttpIngressBridge
import me.manga.kira.backend.security.ComplaintIngressContext
import me.manga.kira.backend.security.historyTestIngress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID

/** Real handler/writer with a fake domain port only. The separate owned-PG IT proves actual c1 authentication. */
class ComplaintInstallationMeHttpTest {
    private val mapper = ObjectMapper()

    @Test
    fun `admitted me entry keeps the same outer ingress without allowing an invented context`() {
        val fixture = Fixture()
        val bridge = ComplaintHttpIngressBridge(fixture.ingress)
        val response = MockHttpServletResponse()
        bridge.doFilter(installationMeTestRequest(), response) { admitted, target ->
            val http = admitted as HttpServletRequest
            fixture.handler.handleWithinIngress(http, target as HttpServletResponse, bridge.claimHandler(http))
        }
        assertEquals(200, response.status)
        assertEquals(listOf("authenticate", "read"), fixture.calls)
        val refused = MockHttpServletResponse()
        fixture.handler.handleWithinIngress(installationMeTestRequest(), refused, ComplaintIngressContext())
        assertEquals(503, refused.status)
        assertEquals(2, fixture.calls.size)
    }

    @Test
    fun `GET emits exactly three canonical scalars without cache validators or credential material`() {
        val fixture = Fixture()
        val response = fixture.request(installationMeTestRequest().apply { addHeader("If-None-Match", "*") })
        assertEquals(200, response.status)
        assertHeaders(response, "application/json")
        val body = mapper.readTree(response.contentAsByteArray)
        assertEquals(setOf("installationId", "credentialVersion", "dataScopeId"), body.fieldNames().asSequence().toSet())
        assertEquals(fixture.installation.id.toString(), body["installationId"].asText())
        assertEquals(fixture.installation.scope.id.toString(), body["dataScopeId"].asText())
        assertTrue(body["credentialVersion"].isIntegralNumber)
        assertEquals(Long.MAX_VALUE, body["credentialVersion"].asLong())
        assertEquals(listOf("authenticate", "read"), fixture.calls)
        assertNull(response.getHeader("WWW-Authenticate"))
        assertFalse(response.contentAsString.contains("synthetic-token"))
    }

    @Test
    fun `route singleton bearer query framing and actual EOF are checked inside ingress before service`() {
        for ((request, status) in invalidRequests()) {
            val fixture = Fixture()
            val response = fixture.request(request)
            assertEquals(status, response.status)
            assertTrue(fixture.calls.isEmpty())
            assertHeaders(response, "application/problem+json", head = request.method == "HEAD")
            assertEquals(if (status == 401) "Bearer realm=\"kira-complaints\"" else null, response.getHeader("WWW-Authenticate"))
        }
        assertBodyProbes()
    }

    @Test
    fun `bounded problems redact failures and partial delivery never retries or appends a problem`() {
        for (kind in ComplaintInstallationMeFailure.entries) {
            val fixture = Fixture().apply { failure = ComplaintInstallationMeRejected(kind) }
            val response = fixture.request(installationMeTestRequest())
            assertEquals(kind.status, response.status)
            assertHeaders(response, "application/problem+json")
            val body = mapper.readTree(response.contentAsByteArray)
            assertEquals(setOf("type", "title", "status", "errors"), body.fieldNames().asSequence().toSet())
            assertEquals(1, body["errors"].size())
            assertEquals(kind.code, body["errors"][0]["code"].asText())
            assertEquals(if (kind.status == 401) "Bearer realm=\"kira-complaints\"" else null, response.getHeader("WWW-Authenticate"))
            assertFalse(response.contentAsString.contains(fixture.installation.id.toString()))
        }
        val limited = Fixture().apply { failure = ComplaintAdmissionRejected(ComplaintAdmissionFailure.RATE_LIMITED, 17) }
        val response = limited.request(installationMeTestRequest())
        assertEquals(429, response.status)
        assertEquals("17", response.getHeader("Retry-After"))
        for (unexpected in listOf(false, true)) assertFailedDelivery(unexpected)
    }

    @Test
    fun `registered disabled filter still blocks me before producer allocation or body access`() {
        var allocated = false
        val request = object : MockHttpServletRequest("GET", "/api/v1/installations/me") {
            override fun getInputStream(): ServletInputStream = error("Disabled route must not read input")
        }
        val response = MockHttpServletResponse()
        DisabledComplaintRoutesFilter().doFilter(request, response, FilterChain { _, _ -> allocated = true })
        assertEquals(404, response.status)
        assertFalse(allocated)
        assertEquals("1", response.getHeader("X-Kira-Complaint-Contract"))
        assertEquals("no-store, no-transform", response.getHeader("Cache-Control"))
        assertNull(response.getHeader("ETag"))
    }

    private fun invalidRequests(): List<Pair<MockHttpServletRequest, Int>> = listOf(
        installationMeTestRequest().apply { method = "POST" } to 404,
        installationMeTestRequest().apply { method = "HEAD" } to 404,
        installationMeTestRequest().apply { requestURI += "/" } to 404,
        installationMeTestRequest(null) to 401,
        installationMeTestRequest("") to 401,
        installationMeTestRequest().apply { addHeader("Authorization", "Bearer second") } to 400,
        installationMeTestRequest("A".repeat(4090)) to 400,
        installationMeTestRequest("private\nvalue") to 400,
        installationMeTestRequest().apply { queryString = "" } to 400,
        installationMeTestRequest().apply { setContent(byteArrayOf(1)) } to 400,
        installationMeTestRequest().apply { addHeader("Content-Length", "00") } to 400,
        installationMeTestRequest().apply { addHeader("Transfer-Encoding", "chunked") } to 400,
        installationMeTestRequest().apply { addHeader("Content-Encoding", "gzip") } to 415,
        installationMeTestRequest().apply { addHeader("X-Kira-Complaint-Contract", "2") } to 400,
        installationMeTestRequest().apply {
            addHeader("Content-Length", "0")
            addHeader("Content-Length", "0")
        } to 400,
    )

    private fun assertBodyProbes() {
        for (value in listOf(-1, 65, null)) {
            val fixture = Fixture()
            val request = BodyProbe(fixture, value)
            val response = fixture.request(request)
            assertEquals(if (value == -1) 200 else 400, response.status)
            assertEquals(1, request.reads)
            assertEquals(if (value == -1) listOf("authenticate", "read") else emptyList<String>(), fixture.calls)
        }
    }

    private fun assertFailedDelivery(unexpected: Boolean) {
        val fixture = Fixture()
        val response = PartialResponse(unexpected)
        val failure = assertThrows<IOException> { fixture.handler.handleRequest(installationMeTestRequest(), response) }
        assertEquals("Installation read delivery failed.", failure.message)
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
        assertEquals(24, response.prefix.size())
        assertEquals(200, response.status)
        assertEquals(listOf("authenticate", "read"), fixture.calls)
        assertEquals(if (unexpected) 503 else 200, fixture.request(installationMeTestRequest()).status)
    }

    private fun assertHeaders(response: MockHttpServletResponse, media: String, head: Boolean = false) {
        assertEquals(listOf("1"), response.getHeaders("X-Kira-Complaint-Contract").toList())
        assertEquals(listOf("no-store, no-transform"), response.getHeaders("Cache-Control").toList())
        assertEquals("$media;charset=UTF-8", response.contentType)
        assertTrue(response.contentLength in 1..ComplaintInstallationMeHttpResponses.MAX_BYTES)
        assertEquals(if (head) 0 else response.contentLength, response.contentAsByteArray.size)
        assertNull(response.getHeader("ETag"))
        assertNull(response.getHeader("Location"))
        assertNull(response.getHeader("Content-Encoding"))
        assertNull(response.getHeader("Transfer-Encoding"))
    }

    private class Fixture {
        val ingress = historyTestIngress()
        val installation = ScopedInstallationId(UUID.randomUUID(), ComplaintDataScope.of(UUID.randomUUID()))
        val calls = mutableListOf<String>()
        var failure: RuntimeException? = null
        private val authentication = object : ComplaintInstallationMeAuthentication {}
        private var authenticatedContext: ComplaintInstallationRequestContext? = null
        private val port = object : ComplaintInstallationMeReadPort {
            override fun authenticate(context: ComplaintInstallationRequestContext, bearer: String): ComplaintInstallationMeAuthentication {
                calls.add("authenticate")
                assertEquals("synthetic-token", bearer)
                authenticatedContext = context
                return authentication
            }

            override fun read(context: ComplaintInstallationRequestContext, authentication: ComplaintInstallationMeAuthentication): ComplaintInstallationMe {
                calls.add("read")
                assertSame(this@Fixture.authentication, authentication)
                assertSame(authenticatedContext, context)
                failure?.let { throw it }
                return ComplaintInstallationMe(installation, Long.MAX_VALUE)
            }
        }
        val handler = ComplaintInstallationMeHttpHandler(ComplaintInstallationMeService(port), ingress)

        fun request(request: MockHttpServletRequest): MockHttpServletResponse = MockHttpServletResponse().also { handler.handleRequest(request, it) }
    }

    private class BodyProbe(private val fixture: Fixture, private val value: Int?) : MockHttpServletRequest("GET", "/api/v1/installations/me") {
        var reads = 0

        init {
            remoteAddr = "192.0.2.1"
            addHeader("Authorization", "Bearer synthetic-token")
            addHeader("Content-Length", "0")
            addHeader("Content-Encoding", "identity")
            addHeader("X-Kira-Complaint-Contract", "1")
        }

        override fun getContentLengthLong(): Long = -1

        override fun getInputStream(): ServletInputStream {
            assertThrows<ComplaintAdmissionRejected> { fixture.ingress.withIngress(this) { error("Nested ingress") } }
            return object : ServletInputStream() {
                override fun isFinished(): Boolean = value == -1
                override fun isReady(): Boolean = true
                override fun setReadListener(listener: ReadListener) = Unit
                override fun read(): Int {
                    reads += 1
                    return value ?: throw IOException("Synthetic input failure")
                }
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

/** Request syntax only; default text is deliberately not a signed installation token. */
internal fun installationMeTestRequest(token: String? = "synthetic-token"): MockHttpServletRequest =
    MockHttpServletRequest("GET", "/api/v1/installations/me").apply {
        remoteAddr = "192.0.2.1"
        token?.let { addHeader("Authorization", "Bearer $it") }
    }
