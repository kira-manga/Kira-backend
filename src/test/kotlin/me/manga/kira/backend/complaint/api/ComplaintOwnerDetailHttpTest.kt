package me.manga.kira.backend.complaint.api

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.ServletOutputStream
import jakarta.servlet.WriteListener
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.common.web.DisabledComplaintRoutesFilter
import me.manga.kira.backend.complaint.application.ComplaintOwnerDetailService
import me.manga.kira.backend.complaint.domain.ComplaintKind
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDetail
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDetailAuthentication
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDetailFailure
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDetailReadPort
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDetailRejected
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDetailRequestContext
import me.manga.kira.backend.complaint.domain.ComplaintOwnerHistoryContent
import me.manga.kira.backend.complaint.domain.ComplaintOwnerHistoryNotice
import me.manga.kira.backend.complaint.domain.ComplaintPlatform
import me.manga.kira.backend.complaint.domain.ComplaintStatus
import me.manga.kira.backend.complaint.domain.ComplaintType
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
import java.time.Instant
import java.util.UUID

/** The real bounded writer/handler, with only its domain port faked; the PG IT uses an actual HTTP-issued c1 token. */
class ComplaintOwnerDetailHttpTest {
    private val mapper = ObjectMapper()
    private val now = Instant.parse("2026-09-18T01:00:00.123456Z")

    @Test
    fun `detail is one complete closed item with exact strong action tag and never conditional304`() {
        val notice = ComplaintOwnerHistoryNotice(UUID.nameUUIDFromBytes(byteArrayOf(1, 2, 3)), "complaints.notice.fixture", now, now, 1)
        val report = content()
        val reply = content(parent = report.id)
        val noticeReply = content(parent = notice.id, key = notice.noticeKey)
        val common = setOf(
            "id", "kind", "type", "subject", "body", "status", "createdAt", "updatedAt", "version", "actionTag", "appVersion", "platform",
            "osVersion", "manufacturer", "deviceModel", "closureReason", "replyToId",
        )
        for (item in listOf(report, reply, noticeReply)) {
            val fixture = Fixture(ComplaintOwnerDetail.Content(item))
            val tag = "\"complaint-${item.id}-v${item.version}\""
            val response = fixture.request(ownerDetailTestRequest(item.id).apply { addHeader("If-None-Match", tag) })
            assertEquals(200, response.status)
            assertHeaders(response, "application/json")
            val tree = mapper.readTree(response.contentAsByteArray)
            assertEquals(if (item.noticeKey == null) common else common + "noticeKey", tree.fieldNames().asSequence().toSet())
            assertEquals(tag, response.getHeader("ETag"))
            assertEquals(tag, tree["actionTag"].asText())
            assertEquals(item.body, tree["body"].asText())
            assertEquals(item.id.toString(), tree["id"].asText())
            assertEquals(item.replyToId?.toString(), tree["replyToId"].let { if (it.isNull) null else it.asText() })
            assertEquals(item.subject, tree["subject"].let { if (it.isNull) null else it.asText() })
            assertEquals(listOf("authenticate", "read"), fixture.calls)
        }
        val fixture = Fixture(ComplaintOwnerDetail.Notice(notice))
        val response = fixture.request(ownerDetailTestRequest(notice.id).apply { addHeader("If-None-Match", "*") })
        assertEquals(200, response.status) // Canonical server NOTICE IDs are not restricted to UUID version 4.
        assertHeaders(response, "application/json")
        assertNull(response.getHeader("ETag"))
        val tree = mapper.readTree(response.contentAsByteArray)
        assertEquals(setOf("id", "kind", "noticeKey", "status", "createdAt", "updatedAt", "version"), tree.fieldNames().asSequence().toSet())
        assertEquals("NOTICE", tree["kind"].asText())
        assertEquals("PINNED", tree["status"].asText())
    }

    @Test
    fun `admitted detail reuses only its original outer ingress`() {
        val item = content()
        val fixture = Fixture(ComplaintOwnerDetail.Content(item))
        val bridge = ComplaintHttpIngressBridge(fixture.ingress)
        val response = MockHttpServletResponse()
        bridge.doFilter(ownerDetailTestRequest(item.id), response) { admitted, target ->
            val http = admitted as HttpServletRequest
            fixture.handler.handleWithinIngress(http, target as HttpServletResponse, bridge.claimHandler(http))
        }
        assertEquals(200, response.status)
        val refused = MockHttpServletResponse()
        fixture.handler.handleWithinIngress(ownerDetailTestRequest(item.id), refused, ComplaintIngressContext())
        assertEquals(503, refused.status)
        assertEquals(listOf("authenticate", "read"), fixture.calls)
    }

    @Test
    fun `canonical detail path query and representative body framing failures precede the producer`() {
        val item = content()
        // The existing installation/history tests retain the broad auth/framing matrix; these are detail-specific regressions.
        val requests = listOf(
            ownerDetailTestRequest(item.id).apply { requestURI = requireNotNull(requestURI).uppercase() } to 404,
            ownerDetailTestRequest(item.id).apply { requestURI = "/api/v1/complaints/A${item.id.toString().drop(1)}" } to 400,
            ownerDetailTestRequest(item.id).apply { requestURI += "/" } to 404,
            ownerDetailTestRequest(item.id).apply { queryString = "limit=1" } to 400,
            ownerDetailTestRequest(item.id).apply { queryString = "" } to 400,
            ownerDetailTestRequest(item.id).apply { addHeader("Authorization", "Bearer other") } to 400,
            ownerDetailTestRequest(item.id).apply { addHeader("Content-Encoding", "gzip") } to 415,
            ownerDetailTestRequest(item.id).apply { addHeader("Content-Length", "00") } to 400,
        )
        for ((request, status) in requests) {
            val fixture = Fixture(ComplaintOwnerDetail.Content(item))
            val response = fixture.request(request)
            assertEquals(status, response.status)
            assertTrue(fixture.calls.isEmpty())
            assertHeaders(response, "application/problem+json")
            assertNull(response.getHeader("ETag"))
        }
        val fixture = Fixture(ComplaintOwnerDetail.Content(item))
        val lyingBody = object : MockHttpServletRequest("GET", "/api/v1/complaints/${item.id}") {
            override fun getContentLengthLong(): Long = 0
            override fun getInputStream(): ServletInputStream = object : ServletInputStream() {
                override fun isFinished(): Boolean = false
                override fun isReady(): Boolean = true
                override fun setReadListener(listener: ReadListener) = Unit
                override fun read(): Int = 65
            }
        }.apply {
            remoteAddr = "192.0.2.1"
            addHeader("Authorization", "Bearer synthetic-token")
            addHeader("Content-Length", "0")
        }
        assertEquals(400, fixture.request(lyingBody).status)
        assertTrue(fixture.calls.isEmpty())
    }

    @Test
    fun `maximal legal escaped detail retains one 32KiB buffer through delivery and zeroes it on release`() {
        val item = ComplaintOwnerHistoryContent(
            UUID.randomUUID(), ComplaintKind.REPORT, ComplaintType.TECHNICAL, "\"".repeat(200), "𐐀".repeat(1000),
            ComplaintStatus.CLOSED, now, now, Long.MAX_VALUE, "\"".repeat(64), ComplaintPlatform.IOS,
            "\\".repeat(128), "𐐀".repeat(128), "𐐀".repeat(128), "𐐀".repeat(500), null, null,
        )
        val fixture = Fixture(ComplaintOwnerDetail.Content(item))
        var retainedBytes: ByteArray? = null
        val response = object : MockHttpServletResponse() {
            override fun getOutputStream(): ServletOutputStream {
                val output = super.getOutputStream()
                return object : ServletOutputStream() {
                    override fun isReady(): Boolean = true
                    override fun setWriteListener(listener: WriteListener) = Unit
                    override fun write(value: Int) = output.write(value)
                    override fun write(bytes: ByteArray, offset: Int, count: Int) {
                        requireConnectionFree()
                        assertEquals(ComplaintOwnerHistoryResponses.DETAIL_MAX_BYTES, bytes.size)
                        retainedBytes = bytes
                        val other = List(7) { checkNotNull(fixture.responses.acquire()) }
                        try {
                            assertNull(fixture.responses.acquire())
                            output.write(bytes, offset, count)
                        } finally {
                            other.forEach { it.close() }
                        }
                    }
                }
            }
        }
        fixture.handler.handleRequest(ownerDetailTestRequest(item.id), response)
        assertEquals(200, response.status)
        assertHeaders(response, "application/json")
        assertEquals(item.body, mapper.readTree(response.contentAsByteArray)["body"].asText())
        assertTrue(checkNotNull(retainedBytes).all { it == 0.toByte() })
        List(8) { checkNotNull(fixture.responses.acquire()) }.forEach { it.close() }
    }

    @Test
    fun `post escaping overflow refuses before any success prefix instead of returning truncated detail`() {
        val item = content()
        // Deliberate DTO fault injection isolates the final writer guard; normal SQL/DTO validation rejects this content earlier.
        val raw = "\"".repeat(20 * 1024)
        assertTrue(raw.toByteArray(Charsets.UTF_8).size < ComplaintOwnerHistoryResponses.DETAIL_MAX_BYTES)
        item.javaClass.getDeclaredField("body").apply { isAccessible = true }.set(item, raw)
        val fixture = Fixture(ComplaintOwnerDetail.Content(item))
        val response = fixture.request(ownerDetailTestRequest(item.id))
        assertEquals(500, response.status)
        assertHeaders(response, "application/problem+json")
        assertNull(response.getHeader("ETag"))
        assertEquals("INTERNAL_ERROR", mapper.readTree(response.contentAsByteArray)["errors"][0]["code"].asText())
        assertFalse(response.contentAsString.contains(item.id.toString()))
        assertFalse(fixture.responses.isOpen())
        assertEquals(503, fixture.request(ownerDetailTestRequest(item.id)).status)
        assertEquals(listOf("authenticate", "read"), fixture.calls)
    }

    @Test
    fun `partial success or problem delivery never appends retries or retains content even when uncommitted`() {
        for (problem in listOf(false, true)) {
            for (fault in DeliveryFault.entries) {
                val item = content()
                val fixture = Fixture(ComplaintOwnerDetail.Content(item)).apply {
                    if (problem) failure = ComplaintOwnerDetailRejected(ComplaintOwnerDetailFailure.NOT_FOUND)
                }
                val response = PartialResponse(fault)
                val failure = assertThrows<IOException> { fixture.handler.handleRequest(ownerDetailTestRequest(item.id), response) }
                assertEquals("Complaint detail delivery failed.", failure.message)
                assertNull(failure.cause)
                assertTrue(failure.suppressed.isEmpty())
                assertFalse(response.isCommitted)
                assertEquals(if (problem) 404 else 200, response.status)
                assertEquals(1, response.sends)
                assertEquals(24, response.prefix.size())
                if (!problem) assertTrue(checkNotNull(response.buffer).all { it == 0.toByte() })
                assertEquals(fault == DeliveryFault.IO, fixture.responses.isOpen())
            }
        }
    }

    @Test
    fun `production disabled filter still blocks detail before body access or producer dispatch`() {
        var reached = false
        val request = object : MockHttpServletRequest("GET", "/api/v1/complaints/${UUID.randomUUID()}") {
            override fun getInputStream(): ServletInputStream = error("Disabled detail must not inspect a body")
        }
        val response = MockHttpServletResponse()
        DisabledComplaintRoutesFilter().doFilter(request, response, FilterChain { _, _ -> reached = true })
        assertEquals(404, response.status)
        assertFalse(reached)
        assertNull(response.getHeader("ETag"))
    }

    private fun content(parent: UUID? = null, key: String? = null): ComplaintOwnerHistoryContent = ComplaintOwnerHistoryContent(
        UUID.randomUUID(), if (parent == null) ComplaintKind.REPORT else ComplaintKind.REPLY,
        if (key == null) ComplaintType.TECHNICAL else ComplaintType.CUSTOM,
        if (key == null) "Synthetic subject" else null, "Synthetic body", ComplaintStatus.OPEN, now, now, 1,
        null, ComplaintPlatform.ANDROID, "", "", "", null, parent, key,
    )

    private fun assertHeaders(response: MockHttpServletResponse, media: String) {
        assertEquals("1", response.getHeader("X-Kira-Complaint-Contract"))
        assertEquals("no-store, no-transform", response.getHeader("Cache-Control"))
        assertEquals("$media;charset=UTF-8", response.contentType)
        val maximum = if (media == "application/problem+json") 16 * 1024 else ComplaintOwnerHistoryResponses.DETAIL_MAX_BYTES
        assertTrue(response.contentAsByteArray.size in 1..maximum)
        assertEquals(response.contentAsByteArray.size.toString(), response.getHeader("Content-Length"))
        assertNull(response.getHeader("Location"))
        assertNull(response.getHeader("Content-Encoding"))
    }

    private class Fixture(val detail: ComplaintOwnerDetail) {
        val ingress = historyTestIngress()
        val responses = ComplaintOwnerHistoryResponses()
        val calls = mutableListOf<String>()
        var failure: RuntimeException? = null
        private val authentication = object : ComplaintOwnerDetailAuthentication {}
        private var authenticatedContext: ComplaintOwnerDetailRequestContext? = null
        private val port = object : ComplaintOwnerDetailReadPort {
            override fun authenticate(context: ComplaintOwnerDetailRequestContext, bearer: String, id: UUID): ComplaintOwnerDetailAuthentication {
                calls.add("authenticate")
                assertEquals("synthetic-token", bearer)
                val expected = when (detail) {
                    is ComplaintOwnerDetail.Content -> detail.item.id
                    is ComplaintOwnerDetail.Notice -> detail.item.id
                }
                assertEquals(expected, id)
                authenticatedContext = context
                return authentication
            }

            override fun read(context: ComplaintOwnerDetailRequestContext, authentication: ComplaintOwnerDetailAuthentication): ComplaintOwnerDetail {
                calls.add("read")
                assertSame(this@Fixture.authentication, authentication)
                assertSame(authenticatedContext, context)
                failure?.let { throw it }
                return detail
            }
        }
        val handler = ComplaintOwnerDetailHttpHandler(ComplaintOwnerDetailService(port), ingress, responses)

        fun request(request: MockHttpServletRequest): MockHttpServletResponse = MockHttpServletResponse().also { handler.handleRequest(request, it) }
    }

    private enum class DeliveryFault { IO, RUNTIME, MEMORY }

    private class PartialResponse(private val fault: DeliveryFault) : MockHttpServletResponse() {
        var sends = 0
        var buffer: ByteArray? = null
        val prefix = ByteArrayOutputStream()

        override fun getOutputStream(): ServletOutputStream = object : ServletOutputStream() {
            override fun isReady(): Boolean = true
            override fun setWriteListener(listener: WriteListener) = Unit
            override fun write(value: Int) = error("Expected one bounded byte-array send")
            override fun write(bytes: ByteArray, offset: Int, count: Int) {
                sends += 1
                buffer = bytes
                prefix.write(bytes, offset, minOf(count, 24))
                when (fault) {
                    DeliveryFault.IO -> throw IOException("Synthetic private output failure")
                    DeliveryFault.RUNTIME -> error("Synthetic private output failure")
                    DeliveryFault.MEMORY -> throw OutOfMemoryError("Synthetic private output failure")
                }
            }
        }
    }
}

/** Syntax only. The default bearer is deliberately not a signed token; actual issuance is exercised by the PG fixture. */
internal fun ownerDetailTestRequest(id: UUID, token: String? = "synthetic-token"): MockHttpServletRequest =
    MockHttpServletRequest("GET", "/api/v1/complaints/$id").apply {
        remoteAddr = "192.0.2.1"
        token?.let { addHeader("Authorization", "Bearer $it") }
    }
