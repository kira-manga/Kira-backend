package me.manga.kira.backend.complaint.api

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.DispatcherType
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.ServletOutputStream
import jakarta.servlet.WriteListener
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.common.web.DisabledComplaintRoutesFilter
import me.manga.kira.backend.complaint.application.ComplaintAdminContentService
import me.manga.kira.backend.complaint.domain.ComplaintAdminContentFailure
import me.manga.kira.backend.complaint.domain.ComplaintAdminContentInput
import me.manga.kira.backend.complaint.domain.ComplaintAdminContentPort
import me.manga.kira.backend.complaint.domain.ComplaintAdminContentReceipt
import me.manga.kira.backend.complaint.domain.ComplaintAdminContentRejected
import me.manga.kira.backend.complaint.domain.ComplaintAdminContentRejection
import me.manga.kira.backend.complaint.domain.ComplaintAdminContentRequest
import me.manga.kira.backend.complaint.domain.ComplaintAdminContentRequestContext
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintReportTextRejected
import me.manga.kira.backend.security.ComplaintIngressContext
import me.manga.kira.backend.security.adminContentTestIngress
import me.manga.kira.backend.security.adminContentTestRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.util.UUID

/** Supplied-data boundary tests only, not authentication/commit evidence. The connected IT uses real normal JWT and complaint issuance. */
class ComplaintAdminContentHttpTest {
    private val mapper = ObjectMapper()
    private val scope = ComplaintDataScope.of(UUID.fromString("11111111-1111-4111-8111-111111111111"))
    private val id = UUID.fromString("123e4567-e89b-52d3-a456-426614174000")
    private val key = UUID.fromString("33333333-3333-4333-8333-333333333333")
    private val body = """{"type":"TECHNICAL","subject":"  Synthetic subject  ","body":"  Synthetic body\r\nline  "}"""

    @Test
    fun strictShapesHeadersNormalizationAndExactRawCapNeverBorrowUnboundedRequestWork() {
        val invalidBodies = listOf(
            "{}", "[]", "null", """{"subject":"x","body":"y"}""", """{"type":"TECHNICAL","body":"x"}""",
            """{"body":null}""", """{"body":1}""", """{"body":{}}""", """{"body":"x","body":"y"}""",
            body.replace("TECHNICAL", "technical"), "$body {}",
        ) + listOf("status", "closureReason", "parentId", "noticeKey", "metadata", "owner", "id").map { field ->
            """{"body":"x","$field":"private"}"""
        }
        for (invalid in invalidBodies) {
            val f = Fixture()
            assertEquals(400, f.send(input(invalid)).status)
            assertEquals(0, f.calls)
        }
        val invalidRequests = listOf(
            input().apply { removeHeader("If-Match") } to 428,
            input().apply { addHeader("If-Match", "\"complaint-$id-v7\"") } to 412,
            input().replace("If-Match", "W/\"complaint-$id-v7\"") to 412,
            input().replace("If-Match", "*") to 412,
            input().replace("If-Match", "\"complaint-$id-v00\"") to 412,
            input().replace("If-Match", "\"complaint-$key-v7\"") to 412,
            input().apply { removeHeader("Authorization") } to 401,
            input().apply { addHeader("Authorization", "Bearer other") } to 400,
            input().apply { addHeader("X-Kira-Admin-Step-Up", "duplicate") } to 400,
            input().replace("X-Kira-Admin-Step-Up", "x".repeat(129)) to 400,
            input().replace("X-Kira-Admin-Step-Up", "private\tproof") to 400,
            input().replace("X-Kira-Idempotency-Key", "not-canonical") to 400,
            input().replace("X-Kira-Complaint-Contract", "2") to 400,
            input().apply { addHeader("If-None-Match", "*") } to 400,
            input().apply { queryString = "dataScopeId=${scope.id}&extra=1" } to 400,
            input().apply { queryString = "dataScopeId=${ComplaintDataScope.LIVE.id}" } to 400,
            input().apply { method = "POST" } to 404,
            input().apply { requestURI += "/" } to 404,
            input().apply { requestURI = requestURI.replace(id.toString(), id.toString().uppercase()) } to 404,
            input().apply { contentType = "text/plain" } to 415,
            input().apply { addHeader("Content-Encoding", "gzip") } to 415,
            input().apply { addHeader("Transfer-Encoding", "chunked") } to 400,
            input().replace("Content-Length", "-1") to 400,
            input().replace("Content-Length", "1") to 400,
            input().apply { addHeader("Content-Length", "1") } to 400,
            input().apply { addHeader(" padded-name", "private") } to 400,
            input().apply { dispatcherType = DispatcherType.ASYNC } to 503,
        )
        for ((request, status) in invalidRequests) {
            val f = Fixture()
            val response = f.send(request)
            assertEquals(status, response.status)
            assertNull(response.getHeader(CONSUMED))
            assertEquals(0, f.calls)
        }
        val f = Fixture()
        assertEquals(200, f.send(input()).status)
        val raw = checkNotNull(f.parsed)
        assertEquals("  Synthetic subject  ", raw.subject)
        assertEquals("  Synthetic body\r\nline  ", raw.body, "Parser must not silently move normalization before real authentication.")
        val normalized = ComplaintAdminContentRequest.normalize(raw)
        assertEquals("Synthetic subject", normalized.subject)
        assertEquals("Synthetic body\nline", normalized.body)
        assertEquals(200, f.send(input("""{"body":"x"}""")).status)
        assertNull(checkNotNull(f.parsed).type)
        assertNull(checkNotNull(f.parsed).subject)
        assertEquals("x", ComplaintAdminContentRequest.normalize(checkNotNull(f.parsed)).body)

        fun escaped(value: String): String = value.toCharArray().joinToString("") { "\\u" + it.code.toString(16).padStart(4, '0') }
        val maximum = """{"type":"${escaped("TECHNICAL")}","subject":"${escaped("🙂".repeat(200))}","body":"${escaped("🙂".repeat(1000))}"}"""
        assertTrue(maximum.toByteArray().size < 15 * 1024)
        assertEquals(200, f.send(input(maximum)).status)
        assertEquals("🙂".repeat(1000), ComplaintAdminContentRequest.normalize(checkNotNull(f.parsed)).body)
        for (invalid in listOf(
            """{"body":"${"x".repeat(1001)}"}""", """{"body":"\u0000x"}""", """{"body":"\ud800"}""",
            """{"type":"TECHNICAL","subject":"${"🙂".repeat(201)}","body":"x"}""",
        )) {
            assertEquals(200, f.send(input(invalid)).status, "Supplied-data port deliberately does not impersonate authenticated normalization.")
            assertThrows<ComplaintReportTextRejected> { ComplaintAdminContentRequest.normalize(checkNotNull(f.parsed)) }
        }
        assertEquals(400, Fixture().send(input().apply { setContent(byteArrayOf(0xc3.toByte(), 0x28)); replace("Content-Length", "2") }).status)

        for ((size, declared) in listOf(16_384 to true, 16_385 to false, 16_385 to true)) {
            val bytes = body.toByteArray() + ByteArray(size - body.toByteArray().size) { 32 }
            var acquisitions = 0
            var count = 0
            var owned: ByteArray? = null
            val template = input().apply {
                removeHeader("Content-Length")
                if (declared) addHeader("Content-Length", size.toString()) else addHeader("Transfer-Encoding", "chunked")
            }
            val request = object : HttpServletRequestWrapper(template) {
                override fun getInputStream(): ServletInputStream {
                    acquisitions++
                    check(!declared || size <= 16_384) { "Declared oversize must not acquire an input stream." }
                    return object : ServletInputStream() {
                        override fun isFinished(): Boolean = count == bytes.size
                        override fun isReady(): Boolean = true
                        override fun setReadListener(listener: ReadListener) = Unit
                        override fun read(): Int = if (count == bytes.size) -1 else bytes[count++].toInt() and 255
                        override fun readNBytes(length: Int): ByteArray {
                            assertEquals(16_385, length)
                            return super.readNBytes(length).also { owned = it }
                        }
                    }
                }
            }
            val selected = Fixture()
            assertEquals(if (size == 16_384) 200 else 413, selected.send(request).status)
            assertEquals(if (declared && size > 16_384) 0 else 1, acquisitions)
            assertEquals(if (acquisitions == 0) 0 else size, count)
            owned?.let { assertTrue(it.all { byte -> byte == 0.toByte() }) }
            assertEquals(if (size == 16_384) 1 else 0, selected.calls)
        }
    }

    @Test
    fun closedLosslessRepliesAndRejectionsShareExactlyEightPermitsAndDestroyTheOwnedResponseBuffer() {
        for (version in listOf(9_007_199_254_740_993L, Long.MAX_VALUE)) {
            val f = Fixture().apply { receipt = ComplaintAdminContentReceipt.Applied(id, version) }
            val response = f.send(input())
            assertEquals("{\"id\":\"$id\",\"version\":$version}", response.contentAsString)
            assertEquals("\"complaint-$id-v$version\"", response.getHeader("ETag"))
            val tree = mapper.readTree(response.contentAsByteArray)
            assertEquals(setOf("id", "version"), tree.fieldNames().asSequence().toSet())
            assertTrue(tree["version"].isIntegralNumber)
            assertEquals(version, tree["version"].longValue())
            envelope(response, confirmed = true)
        }
        for (code in ComplaintAdminContentRejection.entries) {
            val response = Fixture().apply { receipt = ComplaintAdminContentReceipt.Rejected(code) }.send(input())
            assertEquals(code.status, response.status)
            assertEquals(code.name, mapper.readTree(response.contentAsByteArray)["errors"][0]["code"].asText())
            assertNull(response.getHeader("ETag"))
            assertFalse(response.contentAsString.contains(id.toString()))
            envelope(response, confirmed = true)
        }
        for (code in ComplaintAdminContentFailure.entries) {
            val response = Fixture().apply { failure = ComplaintAdminContentRejected(code) }.send(input())
            assertEquals(code.status, response.status)
            assertEquals(code.code, mapper.readTree(response.contentAsByteArray)["errors"][0]["code"].asText())
            assertEquals(if (code.status == 401) listOf("Bearer realm=\"kira-complaints\"") else emptyList<String>(), response.getHeaders("WWW-Authenticate").toList())
            assertEquals(if (code == ComplaintAdminContentFailure.IN_PROGRESS) "1" else null, response.getHeader("Retry-After"))
            envelope(response, confirmed = false)
        }
        val f = Fixture()
        val reads = ComplaintAdminReadResponses(f.owner)
        val held = List(4) { checkNotNull(f.owner.acquire()) } + List(3) { checkNotNull(reads.acquire()) } + checkNotNull(f.responses.acquire())
        try {
            val unread = object : HttpServletRequestWrapper(input()) {
                override fun getInputStream(): ServletInputStream = error("Full shared eight must refuse before reading.")
            }
            assertEquals(503, f.send(unread).status)
            assertEquals(0, f.calls)
        } finally { held.forEach { it.close() } }
        var retained: ByteArray? = null
        val response = object : MockHttpServletResponse() {
            override fun getOutputStream(): ServletOutputStream {
                val delegate = super.getOutputStream()
                return object : ServletOutputStream() {
                    override fun isReady(): Boolean = true
                    override fun setWriteListener(listener: WriteListener) = Unit
                    override fun write(value: Int) = delegate.write(value)
                    override fun write(bytes: ByteArray, offset: Int, count: Int) {
                        requireConnectionFree()
                        assertEquals(32 * 1024, bytes.size)
                        retained = bytes
                        val other = List(7) { checkNotNull(reads.acquire()) }
                        try {
                            assertNull(f.owner.acquire())
                            assertNull(f.responses.acquire())
                            delegate.write(bytes, offset, count)
                        } finally { other.forEach { it.close() } }
                    }
                }
            }
        }
        f.handler.handleRequest(input(), response)
        assertEquals(200, response.status)
        envelope(response, confirmed = true)
        assertTrue(checkNotNull(retained).all { it == 0.toByte() })
        List(8) { checkNotNull(f.owner.acquire()) }.forEach { it.close() }
    }

    @Test
    fun defaultRouteStaysClosedAndEveryPartialSenderFailureIsSanitizedOnceWithoutAppendingOrInventingConsumption() {
        val f = Fixture()
        val closed = MockHttpServletResponse()
        val unread = object : HttpServletRequestWrapper(input()) {
            override fun getInputStream(): ServletInputStream = error("Default disabled route cannot read body or invoke TEST producer.")
        }
        DisabledComplaintRoutesFilter().doFilter(unread, closed, FilterChain { _, _ -> error("Dormant handler must not be registered.") })
        assertEquals(404, closed.status)
        assertEquals(0, f.calls)
        assertNull(closed.getHeader(CONSUMED))
        for (problem in listOf(false, true)) for (fault in listOf(
            IOException("Synthetic private I/O"), IllegalStateException("Synthetic private runtime"),
            OutOfMemoryError("Synthetic private allocation"), InterruptedIOException("Synthetic private interrupt"),
        )) {
            val selected = Fixture().apply { if (problem) failure = ComplaintAdminContentRejected(ComplaintAdminContentFailure.NOT_FOUND) }
            val prefix = ByteArrayOutputStream()
            var sends = 0
            var retained: ByteArray? = null
            val response = object : MockHttpServletResponse() {
                override fun getOutputStream(): ServletOutputStream = object : ServletOutputStream() {
                    override fun isReady(): Boolean = true
                    override fun setWriteListener(listener: WriteListener) = Unit
                    override fun write(value: Int): Unit = error("Fixed buffered sender expected")
                    override fun write(bytes: ByteArray, offset: Int, count: Int) {
                        sends++
                        retained = bytes
                        prefix.write(bytes, offset, minOf(12, count))
                        throw fault
                    }
                }
            }
            try {
                val failed = assertThrows<IOException> { selected.handler.handleRequest(input(), response) }
                assertEquals("Complaint response delivery failed.", failed.message)
                assertNull(failed.cause)
                assertTrue(failed.suppressed.isEmpty())
                assertEquals(1, sends)
                assertEquals(12, prefix.size())
                assertFalse(response.isCommitted, "A partial but uncommitted servlet still must not receive an appended problem.")
                assertEquals(if (problem) 404 else 200, response.status)
                assertEquals(if (problem) emptyList<String>() else listOf("true"), response.getHeaders(CONSUMED).toList())
                if (!problem) assertTrue(checkNotNull(retained).all { it == 0.toByte() })
                assertEquals(fault is IOException, selected.owner.isOpen())
                assertEquals(fault is InterruptedIOException, Thread.currentThread().isInterrupted)
            } finally { Thread.interrupted() }
        }
    }

    private fun input(value: String = body): MockHttpServletRequest = adminContentTestRequest(scope, id, key, 7, "synthetic-token", "private-proof", value)

    private fun MockHttpServletRequest.replace(name: String, value: String): MockHttpServletRequest = apply {
        removeHeader(name)
        addHeader(name, value)
    }

    private fun envelope(response: MockHttpServletResponse, confirmed: Boolean) {
        assertEquals("1", response.getHeader("X-Kira-Complaint-Contract"))
        assertEquals("no-store, no-transform", response.getHeader("Cache-Control"))
        assertEquals(response.contentAsByteArray.size.toString(), response.getHeader("Content-Length"))
        assertTrue(response.contentAsByteArray.size <= 512)
        assertEquals(if (confirmed) listOf("true") else emptyList<String>(), response.getHeaders(CONSUMED).toList())
        assertNull(response.getHeader("Location"))
        for (privateValue in listOf("Synthetic", "private-proof", "synthetic-token", key.toString(), scope.id.toString(), "consumedGrantId")) {
            assertFalse(response.contentAsString.contains(privateValue))
        }
    }

    private inner class Fixture {
        val ingress = adminContentTestIngress()
        val owner = ComplaintOwnerHistoryResponses()
        val responses = ComplaintAdminContentResponses(owner)
        var calls = 0
        var parsed: ComplaintAdminContentInput? = null
        var receipt: ComplaintAdminContentReceipt = ComplaintAdminContentReceipt.Applied(id, 8)
        var failure: ComplaintAdminContentRejected? = null
        val handler = ComplaintAdminContentHttpHandler(
            ComplaintAdminContentService(object : ComplaintAdminContentPort {
                override fun edit(context: ComplaintAdminContentRequestContext, bearer: String, proof: String?, input: ComplaintAdminContentInput): ComplaintAdminContentReceipt {
                    requireConnectionFree()
                    ingress.requireLiveContext(context as ComplaintIngressContext)
                    assertEquals("synthetic-token", bearer)
                    assertEquals("private-proof", proof)
                    calls++
                    parsed = input
                    failure?.let { throw it }
                    return receipt
                }
            }),
            ingress, responses,
        )

        fun send(request: HttpServletRequest): MockHttpServletResponse = MockHttpServletResponse().also { handler.handleRequest(request, it) }
    }

    private companion object { const val CONSUMED = "X-Kira-Admin-Step-Up-Consumed" }
}
