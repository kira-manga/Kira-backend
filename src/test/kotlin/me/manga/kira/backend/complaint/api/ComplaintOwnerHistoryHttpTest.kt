package me.manga.kira.backend.complaint.api

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletOutputStream
import jakarta.servlet.WriteListener
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.common.web.DisabledComplaintRoutesFilter
import me.manga.kira.backend.complaint.application.ComplaintOwnerHistoryService
import me.manga.kira.backend.complaint.domain.ComplaintKind
import me.manga.kira.backend.complaint.domain.ComplaintOwnerHistoryAuthentication
import me.manga.kira.backend.complaint.domain.ComplaintOwnerHistoryContent
import me.manga.kira.backend.complaint.domain.ComplaintOwnerHistoryFailure
import me.manga.kira.backend.complaint.domain.ComplaintOwnerHistoryNotice
import me.manga.kira.backend.complaint.domain.ComplaintOwnerHistoryPage
import me.manga.kira.backend.complaint.domain.ComplaintOwnerHistoryQuery
import me.manga.kira.backend.complaint.domain.ComplaintOwnerHistoryReadPort
import me.manga.kira.backend.complaint.domain.ComplaintOwnerHistoryRejected
import me.manga.kira.backend.complaint.domain.ComplaintOwnerHistoryRequestContext
import me.manga.kira.backend.complaint.domain.ComplaintPlatform
import me.manga.kira.backend.complaint.domain.ComplaintStatus
import me.manga.kira.backend.complaint.domain.ComplaintType
import me.manga.kira.backend.complaint.domain.ComplaintValidationException
import me.manga.kira.backend.security.historyTestIngress
import me.manga.kira.backend.security.historyTestRequest
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
import java.time.Instant
import java.util.UUID

/** Actual handler/writer with a finite fake domain port; real JWT/PG composition is tested separately. */
class ComplaintOwnerHistoryHttpTest {
    private val mapper = ObjectMapper()
    private val now = Instant.parse("2026-09-17T01:00:00.123456Z")

    @Test
    fun `empty history is exact UTF8 JSON with explicit terminal null and complaint headers`() {
        val fixture = Fixture()
        val response = fixture.request()
        assertEquals(200, response.status)
        assertEquals("{\"notices\":[],\"items\":[],\"nextCursor\":null}", response.contentAsString)
        assertHeaders(response, "application/json")
        assertEquals(listOf("authenticate", "read"), fixture.port.events)
        assertNull(response.getHeader("ETag"))
        assertNull(response.getHeader("Location"))
        assertNull(response.getHeader("Content-Encoding"))
    }

    @Test
    fun `REPORT ordinary reply and notice reply have exact closed fields without sensitive identifiers`() {
        val fixture = Fixture()
        val notice = ComplaintOwnerHistoryNotice(UUID.randomUUID(), "complaints.notice.fixture", now, now, 1)
        val report = content(at = now.plusSeconds(3))
        val reply = content(kind = ComplaintKind.REPLY, parent = report.id, at = now.plusSeconds(2))
        val noticeReply = content(kind = ComplaintKind.REPLY, parent = notice.id, key = notice.noticeKey, at = now.plusSeconds(1))
        fixture.port.page = { ComplaintOwnerHistoryPage(listOf(notice), listOf(report, reply, noticeReply), null) }
        val response = fixture.request()
        val tree = mapper.readTree(response.contentAsByteArray)
        assertEquals(setOf("notices", "items", "nextCursor"), tree.fieldNames().asSequence().toSet())
        assertEquals(setOf("id", "kind", "noticeKey", "status", "createdAt", "updatedAt", "version"), tree["notices"][0].fieldNames().asSequence().toSet())
        val common = setOf(
            "id", "kind", "type", "subject", "body", "status", "createdAt", "updatedAt", "version", "actionTag", "appVersion", "platform",
            "osVersion", "manufacturer", "deviceModel", "closureReason", "replyToId",
        )
        for (index in 0..2) {
            val row = tree["items"][index]
            assertEquals(if (index == 2) common + "noticeKey" else common, row.fieldNames().asSequence().toSet())
            assertTrue(row["appVersion"].isNull && row["closureReason"].isNull)
            assertEquals("\"complaint-${row["id"].asText()}-v1\"", row["actionTag"].asText())
        }
        assertTrue(tree["items"][0]["replyToId"].isNull)
        assertTrue(tree["items"][2]["subject"].isNull)
        assertEquals("CUSTOM", tree["items"][2]["type"].asText())
        assertEquals(notice.id.toString(), tree["items"][2]["replyToId"].asText())
        assertFalse(response.contentAsString.contains("owner_id"))
        assertFalse(response.contentAsString.contains("verifier"))
        assertHeaders(response, "application/json")
    }

    @Test
    fun `closed bounded query header method and body refusals happen before the domain producer`() {
        val fixture = Fixture()
        val cases = listOf(
            historyTestRequest(token = null),
            historyTestRequest(query = "limit=0"), historyTestRequest(query = "limit=51"), historyTestRequest(query = "limit=50&limit=1"),
            historyTestRequest(query = "scope=private"), historyTestRequest(query = "cursor=not-a-cursor"),
            historyTestRequest(query = "limit=%35%30"), historyTestRequest(query = "cursor=" + "A".repeat(2201)),
            historyTestRequest().apply { addHeader("Authorization", "Bearer other") },
            historyTestRequest().apply { addHeader("Content-Encoding", "gzip") },
            historyTestRequest().apply { addHeader("Content-Length", "1") },
            historyTestRequest().apply { addHeader("Transfer-Encoding", "chunked") },
            historyTestRequest().apply { method = "POST" },
        )
        for (request in cases) {
            val response = fixture.request(request)
            assertTrue(response.status in setOf(400, 401, 404, 415))
            assertTrue(response.contentAsByteArray.size <= 16 * 1024)
            assertHeaders(response, "application/problem+json")
            assertTrue(fixture.port.events.isEmpty())
        }
    }

    @Test
    fun `problem envelope uses allowlisted errors and exact 401 challenge without invented top level code`() {
        for (failure in ComplaintOwnerHistoryFailure.entries) {
            val fixture = Fixture()
            fixture.port.page = { throw ComplaintOwnerHistoryRejected(failure) }
            val response = fixture.request()
            assertEquals(failure.status, response.status)
            val json = mapper.readTree(response.contentAsByteArray)
            assertEquals(setOf("type", "title", "status", "errors"), json.fieldNames().asSequence().toSet())
            assertEquals("about:blank", json["type"].asText())
            assertEquals(failure.code, json["errors"][0]["code"].asText())
            assertEquals(1, json["errors"].size())
            if (failure.status == 401) assertEquals("Bearer realm=\"kira-complaints\"", response.getHeader("WWW-Authenticate"))
            assertEquals(failure != ComplaintOwnerHistoryFailure.INTERNAL, fixture.responses.isOpen())
            assertHeaders(response, "application/problem+json")
        }
    }

    @Test
    fun `maximal legal Unicode and escaped fields fit item and total UTF8 bounds`() {
        val fixture = Fixture()
        val items = List(50) { index ->
            val at = now.plusSeconds(50L - index)
            ComplaintOwnerHistoryContent(
                UUID.randomUUID(), ComplaintKind.REPORT, ComplaintType.TECHNICAL, "\"".repeat(200), "𐐀".repeat(1000),
                ComplaintStatus.CLOSED, at, at, Long.MAX_VALUE, "\"".repeat(64), ComplaintPlatform.IOS,
                "\\".repeat(128), "𐐀".repeat(128), "𐐀".repeat(128), "𐐀".repeat(500), null, null,
            )
        }
        val notices = List(16) { index ->
            ComplaintOwnerHistoryNotice(UUID.randomUUID(), "history.fixture.$index.".padEnd(96, 'a'), now, now, 1)
        }
        fixture.port.page = { ComplaintOwnerHistoryPage(notices, items, null) }
        val response = fixture.request()
        assertEquals(200, response.status)
        assertTrue(response.contentAsByteArray.size <= 2 * 1024 * 1024)
        val tree = mapper.readTree(response.contentAsByteArray)
        assertEquals(50, tree["items"].size())
        assertEquals(16, tree["notices"].size())
        tree["items"].forEach { assertTrue(mapper.writeValueAsBytes(it).size <= 32 * 1024) }
    }

    @Test
    fun `response reservation remains retained through a connection free sender callback`() {
        val fixture = Fixture()
        var observed = false
        val response = object : MockHttpServletResponse() {
            override fun getOutputStream(): ServletOutputStream {
                val delegate = super.getOutputStream()
                return object : ServletOutputStream() {
                    override fun isReady(): Boolean = true
                    override fun setWriteListener(listener: WriteListener) = Unit
                    override fun write(value: Int) = delegate.write(value)
                    override fun write(bytes: ByteArray, offset: Int, count: Int) {
                        requireConnectionFree()
                        val other = List(7) { checkNotNull(fixture.responses.acquire()) }
                        try {
                            assertNull(fixture.responses.acquire())
                            observed = true
                            delegate.write(bytes, offset, count)
                        } finally {
                            other.forEach { it.close() }
                        }
                    }
                }
            }
        }
        fixture.handler.handleRequest(historyTestRequest(), response)
        assertTrue(observed)
        assertEquals(200, response.status)
        val released = List(8) { checkNotNull(fixture.responses.acquire()) }
        released.forEach { it.close() }
    }

    @Test
    fun `runtime sender failure after a buffered prefix cannot append a problem or retry`() {
        val fixture = Fixture()
        val sent = ByteArrayOutputStream()
        var calls = 0
        val response = object : MockHttpServletResponse() {
            override fun getOutputStream(): ServletOutputStream = object : ServletOutputStream() {
                override fun isReady(): Boolean = true
                override fun setWriteListener(listener: WriteListener) = Unit
                override fun write(value: Int) = Unit
                override fun write(bytes: ByteArray, offset: Int, count: Int) {
                    calls++
                    sent.write(bytes, offset, minOf(count, 8))
                    throw IllegalStateException("Synthetic container buffer failure")
                }
            }
        }
        val failure = assertThrows<IOException> { fixture.handler.handleRequest(historyTestRequest(), response) }
        assertEquals("Complaint response delivery failed.", failure.message)
        assertNull(failure.cause)
        assertEquals(1, calls)
        assertEquals(8, sent.size())
        assertFalse(fixture.responses.isOpen())
    }

    @Test
    fun `eight response reservations bound producer entry and release is exact and idempotent`() {
        val fixture = Fixture()
        val held = List(8) { checkNotNull(fixture.responses.acquire()) }
        try {
            assertNull(fixture.responses.acquire())
            assertEquals(503, fixture.request().status)
            assertTrue(fixture.port.events.isEmpty())
        } finally {
            held.forEach { it.close(); it.close() }
        }
        assertEquals(200, fixture.request().status)
        val again = List(8) { checkNotNull(fixture.responses.acquire()) }
        try {
            assertNull(fixture.responses.acquire())
        } finally {
            again.forEach { it.close() }
        }
    }

    @Test
    fun `failed send attempts no replay or problem suffix and releases the response reservation`() {
        val fixture = Fixture()
        val response = object : MockHttpServletResponse() {
            override fun getOutputStream(): ServletOutputStream = object : ServletOutputStream() {
                override fun isReady(): Boolean = true
                override fun setWriteListener(listener: WriteListener) = Unit
                override fun write(value: Int): Unit = throw IOException("synthetic send failure")
            }
        }
        val failure = assertThrows<IOException> { fixture.handler.handleRequest(historyTestRequest(), response) }
        assertEquals("Complaint response delivery failed.", failure.message)
        assertNull(failure.cause)
        assertEquals(listOf("authenticate", "read"), fixture.port.events)
        assertTrue(fixture.responses.isOpen())
        assertEquals(200, fixture.request().status)
    }

    @Test
    fun `counting buffers allow exact limits refuse one over and cannot send after destruction`() {
        for (maximum in listOf(16 * 1024, 32 * 1024, 2 * 1024 * 1024)) {
            val body = ComplaintHistoryEncodedBody(maximum)
            body.write(ByteArray(maximum))
            assertEquals(maximum, body.length)
            assertThrows<ComplaintHistorySerializationFailure> { body.write(0) }
            body.destroy()
            assertEquals(0, body.length)
            assertThrows<ComplaintHistorySerializationFailure> { body.write(0) }
            assertThrows<IllegalStateException> { body.sendTo(ByteArrayOutputStream()) }
        }
        assertThrows<IllegalArgumentException> { ComplaintHistoryEncodedBody(Int.MAX_VALUE) }
    }

    @Test
    fun `writer failure closes only dormant capability and emits bounded 500 before any success prefix`() {
        val fixture = Fixture()
        fixture.port.page = {
            fixture.responses.failClosed()
            ComplaintOwnerHistoryPage(emptyList(), emptyList(), null)
        }
        val response = fixture.request()
        assertEquals(500, response.status)
        assertEquals("INTERNAL_ERROR", mapper.readTree(response.contentAsByteArray)["errors"][0]["code"].asText())
        assertFalse(response.contentAsString.contains("items"))
        assertFalse(fixture.responses.isOpen())
        val events = fixture.port.events.toList()
        assertEquals(503, fixture.request().status)
        assertEquals(events, fixture.port.events)
    }

    @Test
    fun `full C1 range is rejected while embedded tab newline and supplementary Unicode remain legal`() {
        for (control in 0x7f..0x9f) {
            assertThrows<ComplaintValidationException> { content(body = "a${control.toChar()}b") }
        }
        val legal = "line\tquote \" slash \\ and 終\n𐐀"
        assertEquals(legal, content(body = legal).body)
    }

    @Test
    fun `registered closed filter still rejects before constructing the dormant producer`() {
        var allocated = false
        val response = MockHttpServletResponse()
        DisabledComplaintRoutesFilter().doFilter(historyTestRequest(), response, FilterChain { request, result ->
            allocated = true
            Fixture().handler.handleRequest(request as MockHttpServletRequest, result as MockHttpServletResponse)
        })
        assertEquals(404, response.status)
        assertFalse(allocated)
    }

    private fun content(
        kind: ComplaintKind = ComplaintKind.REPORT,
        parent: UUID? = null,
        key: String? = null,
        at: Instant = now,
        body: String = "Fixture body",
    ): ComplaintOwnerHistoryContent = ComplaintOwnerHistoryContent(
        UUID.randomUUID(), kind, if (key == null) ComplaintType.TECHNICAL else ComplaintType.CUSTOM,
        if (key == null) "Fixture subject" else null, body, ComplaintStatus.OPEN, at, at, 1,
        null, ComplaintPlatform.ANDROID, "", "", "", null, parent, key,
    )

    private fun assertHeaders(response: MockHttpServletResponse, media: String) {
        assertEquals("1", response.getHeader("X-Kira-Complaint-Contract"))
        assertEquals("no-store, no-transform", response.getHeader("Cache-Control"))
        assertTrue(checkNotNull(response.contentType).startsWith(media))
        assertEquals(response.contentAsByteArray.size.toString(), response.getHeader("Content-Length"))
    }

    private class Fixture {
        val port = Port()
        val responses = ComplaintOwnerHistoryResponses()
        val handler = ComplaintOwnerHistoryHttpHandler(ComplaintOwnerHistoryService(port), historyTestIngress(), responses)
        fun request(request: MockHttpServletRequest = historyTestRequest()): MockHttpServletResponse =
            MockHttpServletResponse().also { handler.handleRequest(request, it) }
    }

    private class Port : ComplaintOwnerHistoryReadPort {
        val events = mutableListOf<String>()
        var page: () -> ComplaintOwnerHistoryPage = { ComplaintOwnerHistoryPage(emptyList(), emptyList(), null) }
        override fun authenticate(
            context: ComplaintOwnerHistoryRequestContext,
            bearer: String,
            query: ComplaintOwnerHistoryQuery,
        ): ComplaintOwnerHistoryAuthentication {
            events.add("authenticate")
            return object : ComplaintOwnerHistoryAuthentication {}
        }
        override fun read(context: ComplaintOwnerHistoryRequestContext, authentication: ComplaintOwnerHistoryAuthentication): ComplaintOwnerHistoryPage {
            events.add("read")
            return page()
        }
    }
}
