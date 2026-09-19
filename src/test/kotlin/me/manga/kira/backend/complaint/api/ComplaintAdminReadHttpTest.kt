package me.manga.kira.backend.complaint.api

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.DispatcherType
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.ServletOutputStream
import jakarta.servlet.WriteListener
import me.manga.kira.backend.common.infrastructure.persistence.OwnedCallerTestScope
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.common.web.DisabledComplaintRoutesFilter
import me.manga.kira.backend.complaint.application.ComplaintAdminReadService
import me.manga.kira.backend.complaint.domain.ComplaintAdminItem
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadAuthentication
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadFailure
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadPort
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadQuery
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadRejected
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadRequestContext
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadResult
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintKind
import me.manga.kira.backend.complaint.domain.ComplaintOwnerHistoryContent
import me.manga.kira.backend.complaint.domain.ComplaintOwnerHistoryNotice
import me.manga.kira.backend.complaint.domain.ComplaintPlatform
import me.manga.kira.backend.complaint.domain.ComplaintStatus
import me.manga.kira.backend.complaint.domain.ComplaintType
import me.manga.kira.backend.security.ComplaintIngressAdmission
import me.manga.kira.backend.security.ComplaintIngressContext
import me.manga.kira.backend.security.adminReadDetailRequest
import me.manga.kira.backend.security.adminReadSearchRequest
import me.manga.kira.backend.security.adminReadTestIngress
import me.manga.kira.backend.security.admissionTestPolicy
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
import java.io.InterruptedIOException
import java.time.Instant
import java.util.UUID

/** Boundary-unit doubles only. ComplaintAdminReadIT composes the actual signer, decoder, adapter and PostgreSQL owner. */
class ComplaintAdminReadHttpTest {
    private val mapper = ObjectMapper()
    private val scope = ComplaintDataScope.of(UUID.randomUUID())
    private val now = Instant.parse("2026-09-19T01:00:00.123456Z")

    @Test
    fun `search and detail emit only closed Admin fields and exact integer versions beyond double precision`() {
        val notice = ComplaintAdminItem.Notice(ComplaintOwnerHistoryNotice(UUID.nameUUIDFromBytes(byteArrayOf(1, 2, 3)), "admin.read.notice", now, now, 1))
        val report = content(version = 9_007_199_254_740_993L)
        val reply = content(version = Long.MAX_VALUE, parent = report.id, closed = true)
        val noticeReply = content(parent = notice.id, key = notice.notice.noticeKey)
        val common = setOf("id", "kind", "status", "createdAt", "updatedAt", "version", "ownership", "ownerReference")
        val contentFields = common + setOf(
            "type", "subject", "body", "actionTag", "appVersion", "platform", "osVersion", "manufacturer", "deviceModel",
            "closureReason", "replyToId", "closedAt", "closureProvenance", "closureActorId",
        )
        val items = listOf(report, reply, noticeReply, notice).sortedByDescending { it.id.toString() }
        val fixture = Fixture(ComplaintAdminReadResult.Page(items, null))
        val search = fixture.request(adminReadSearchRequest(scope))
        assertEquals(200, search.status)
        assertHeaders(search, "application/json", 2 * 1024 * 1024)
        assertNull(search.getHeader("ETag"))
        val page = mapper.readTree(search.contentAsByteArray)
        assertEquals(setOf("items", "nextCursor"), page.fieldNames().asSequence().toSet())
        assertTrue(page["nextCursor"].isNull)
        assertEquals(items.map { it.id.toString() }, page["items"].map { it["id"].asText() })
        for (item in items) {
            val selected = Fixture(ComplaintAdminReadResult.Detail(item))
            val response = selected.request(adminReadDetailRequest(scope, item.id))
            assertEquals(200, response.status)
            assertHeaders(response, "application/json", 32 * 1024)
            val tree = mapper.readTree(response.contentAsByteArray)
            assertEquals(page["items"].single { it["id"].asText() == item.id.toString() }, tree)
            assertTrue(tree["version"].isIntegralNumber)
            assertEquals(item.version, tree["version"].longValue())
            when (item) {
                is ComplaintAdminItem.Content -> {
                    assertEquals(contentFields + if (item.content.noticeKey == null) emptySet() else setOf("noticeKey"), tree.fieldNames().asSequence().toSet())
                    val tag = "\"complaint-${item.id}-v${item.version}\""
                    assertEquals(tag, tree["actionTag"].asText())
                    assertEquals(tag, response.getHeader("ETag"))
                    assertTrue(response.contentAsString.contains("\"version\":${item.version},\"ownership\":\"INSTALLATION\""))
                    assertTrue(response.contentAsString.contains("\"actionTag\":\"\\\"complaint-${item.id}-v${item.version}\\\"\""))
                    assertEquals(item.ownerReference.toString(), tree["ownerReference"].asText())
                    assertEquals("INSTALLATION", tree["ownership"].asText())
                    assertEquals(item.closedAt?.toString(), tree["closedAt"].let { if (it.isNull) null else it.asText() })
                    assertEquals(item.closureActorId?.toString(), tree["closureActorId"].let { if (it.isNull) null else it.asText() })
                }
                is ComplaintAdminItem.Notice -> {
                    assertEquals(common + "noticeKey", tree.fieldNames().asSequence().toSet())
                    assertEquals("SYSTEM", tree["ownership"].asText())
                    assertTrue(tree["ownerReference"].isNull)
                    assertNull(response.getHeader("ETag"))
                }
            }
            assertEquals(listOf("authenticate", "read"), selected.calls)
        }
        assertFalse(search.contentAsString.contains("owner_id"))
        assertFalse(search.contentAsString.contains("verifier"))
    }

    @Test
    fun `method path duplicate framing encoded scope and closed input failures precede the domain producer`() {
        val id = UUID.randomUUID()
        val requests = listOf(
            adminReadSearchRequest(scope, token = null) to 401,
            adminReadSearchRequest(scope).apply { method = "GET" } to 404,
            adminReadSearchRequest(scope).apply { queryString = "" } to 400,
            adminReadSearchRequest(scope).apply { requestURI += "/" } to 404,
            adminReadSearchRequest(scope).apply { addHeader("Authorization", "Bearer second") } to 400,
            adminReadSearchRequest(scope).apply { addHeader("X-Kira-Complaint-Contract", "1") } to 400,
            adminReadSearchRequest(scope).apply { addHeader("Content-Length", "0") } to 400,
            adminReadSearchRequest(scope).apply { addHeader("Transfer-Encoding", "chunked") } to 400,
            adminReadSearchRequest(scope).apply { addHeader("Content-Encoding", "gzip") } to 415,
            adminReadSearchRequest(scope).apply { contentType = "text/plain" } to 415,
            adminReadSearchRequest(scope, body = """{"dataScopeId":"${scope.id}","dataScopeId":"${scope.id}"}""") to 400,
            adminReadSearchRequest(scope, body = """{"dataScopeId":"${scope.id}","unknown":true}""") to 400,
            adminReadSearchRequest(scope, body = "{} {}") to 400,
            adminReadDetailRequest(scope, id).apply { requestURI += "/" } to 404,
            adminReadDetailRequest(scope, id).apply { requestURI = "/api/v1/admin/complaints/%61${id.toString().drop(1)}" } to 404,
            adminReadDetailRequest(scope, id).apply { queryString = "dataScopeId=%61${scope.id.toString().drop(1)}" } to 400,
            adminReadDetailRequest(scope, id).apply { queryString += "&dataScopeId=${scope.id}" } to 400,
            adminReadDetailRequest(scope, id).apply { queryString = null } to 400,
            adminReadDetailRequest(scope, id).apply { addHeader("Content-Length", "1") } to 400,
            adminReadDetailRequest(scope, id).apply { addHeader("Transfer-Encoding", "chunked") } to 400,
        ) + listOf("If-Match", "If-None-Match", "X-Kira-Idempotency-Key", "X-Kira-Admin-Step-Up").map { name ->
            adminReadSearchRequest(scope).apply { addHeader(name, "*") } to 400
        }
        for ((request, status) in requests) {
            val fixture = Fixture()
            val response = fixture.request(request)
            assertEquals(status, response.status)
            assertHeaders(response, "application/problem+json", 512)
            assertTrue(fixture.calls.isEmpty())
            assertNull(response.getHeader("ETag"))
        }
    }

    @Test
    fun `direct search stream stops at32769 bytes while bodyless detail reads at most one byte`() {
        val cases = listOf(
            CountingRequest(true, ByteArray(40_000) { 65 }, "40000") to (413 to 0),
            CountingRequest(true, ByteArray(40_000) { 65 }, null) to (413 to 32_769),
            CountingRequest(true, "{}".toByteArray(), "3") to (400 to 2),
            CountingRequest(false, ByteArray(40_000) { 65 }, "0") to (400 to 1),
            CountingRequest(false, ByteArray(40_000) { 65 }, "1") to (400 to 0),
        )
        for ((request, expected) in cases) {
            val fixture = Fixture()
            val response = fixture.request(request)
            assertEquals(expected.first, response.status)
            assertEquals(expected.second, request.readBytes)
            assertTrue(fixture.calls.isEmpty())
        }
        val valid = CountingRequest(true, """{"dataScopeId":"${scope.id}"}""".toByteArray(), null)
        valid.addHeader("Transfer-Encoding", "chunked")
        assertEquals(200, Fixture().request(valid).status)
    }

    @Test
    fun `finite header count character and total budgets reject before reading input`() {
        val cases = listOf<(MockHttpServletRequest) -> Unit>(
            { request -> repeat(65) { request.addHeader("X-Extra-$it", "x") } },
            { request -> request.addHeader("X-Extra", "x".repeat(16 * 1024)) },
            { request -> request.addHeader(" Bad-Name", "x") },
            { request -> request.addHeader("Authorization", "Bearer second") },
            { request -> request.addHeader("Content-Length", "-1") },
        )
        for (mutate in cases) {
            val request = CountingRequest(true, ByteArray(40_000), null)
            mutate(request)
            val fixture = Fixture()
            assertEquals(400, fixture.request(request).status)
            assertEquals(0, request.readBytes)
            assertTrue(fixture.calls.isEmpty())
        }
    }

    @Test
    fun `real ingress admission is charged before request buffering including parse failures`() {
        val fixture = Fixture(ingress = adminReadTestIngress(policy = admissionTestPolicy(ingressRate = 1)))
        assertEquals(400, fixture.request(adminReadSearchRequest(scope, body = "{}")).status)
        val request = CountingRequest(true, ByteArray(40_000), null)
        val limited = fixture.request(request)
        assertEquals(429, limited.status)
        assertEquals("60", limited.getHeader("Retry-After"))
        assertEquals(0, request.readBytes)
        assertTrue(fixture.calls.isEmpty())
    }

    @Test
    fun `ninth shared owner or Admin response permit fails before buffering and producer invocation`() {
        val fixture = Fixture()
        val secondAdminWriter = ComplaintAdminReadResponses(fixture.owner)
        val held = List(4) { checkNotNull(fixture.owner.acquire()) } + List(4) { checkNotNull(secondAdminWriter.acquire()) }
        try {
            assertNull(fixture.responses.acquire())
            val request = CountingRequest(true, ByteArray(40_000), null)
            assertEquals(503, fixture.request(request).status)
            assertEquals(0, request.readBytes)
            assertTrue(fixture.calls.isEmpty())
        } finally {
            held.forEach { it.close() }
        }
        assertEquals(200, fixture.request(adminReadSearchRequest(scope)).status)
        val foreign = checkNotNull(ComplaintOwnerHistoryResponses().acquire())
        foreign.use { assertThrows<RuntimeException> { fixture.responses.encode(it, ComplaintAdminReadResult.Page(emptyList(), null)) } }
        val closed = checkNotNull(fixture.owner.acquire())
        closed.close()
        assertThrows<RuntimeException> { fixture.responses.encode(closed, ComplaintAdminReadResult.Page(emptyList(), null)) }
    }

    @Test
    fun `eight blocked401403404429 problems retain original ingress and acquired shared slots through delivery`() {
        val owner = ComplaintOwnerHistoryResponses()
        val ingress = adminReadTestIngress()
        val failures = listOf(ComplaintAdminReadFailure.UNAUTHORIZED, ComplaintAdminReadFailure.FORBIDDEN, ComplaintAdminReadFailure.NOT_FOUND, ComplaintAdminReadFailure.RATE_LIMITED)
        OwnedCallerTestScope().use { callers ->
            val gates = List(8) { callers.gate() }
            val calls = gates.mapIndexed { index, gate ->
                callers.launch {
                    val fixture = Fixture(ingress = ingress, owner = owner).apply { failure = ComplaintAdminReadRejected(failures[index % 4]) }
                    val response = object : MockHttpServletResponse() {
                        override fun getOutputStream(): ServletOutputStream {
                            requireConnectionFree()
                            fixture.requireLiveContext()
                            gate.hold()
                            fixture.requireLiveContext()
                            return super.getOutputStream()
                        }
                    }
                    fixture.handler.handleRequest(adminReadSearchRequest(scope), response)
                    assertEquals(failures[index % 4].status, response.status)
                    assertHeaders(response, "application/problem+json", 512)
                    true
                }
            }
            gates.forEach { it.awaitEntered() }
            try {
                assertNull(owner.acquire())
                val ninth = Fixture(ingress = ingress, owner = owner)
                val request = CountingRequest(true, ByteArray(40_000), null)
                assertEquals(503, ninth.request(request).status)
                assertEquals(0, request.readBytes)
                assertTrue(ninth.calls.isEmpty())
            } finally {
                gates.forEach { it.release() }
                calls.forEach { it.value() }
            }
        }
        List(8) { checkNotNull(owner.acquire()) }.forEach { it.close() }
    }

    @Test
    fun `complete maximal escaped response retains the shared permit through connection free delivery then erases bytes`() {
        val fixture = Fixture(ComplaintAdminReadResult.Detail(content(maximal = true, closed = true, version = Long.MAX_VALUE)))
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
                        assertEquals(32 * 1024, bytes.size)
                        retainedBytes = bytes
                        val held = List(7) { checkNotNull(fixture.owner.acquire()) }
                        try {
                            assertNull(fixture.responses.acquire())
                            output.write(bytes, offset, count)
                        } finally {
                            held.forEach { it.close() }
                        }
                    }
                }
            }
        }
        val item = (fixture.result as ComplaintAdminReadResult.Detail).item
        fixture.handler.handleRequest(adminReadDetailRequest(scope, item.id), response)
        assertEquals(200, response.status)
        assertHeaders(response, "application/json", 32 * 1024)
        assertEquals(Long.MAX_VALUE, mapper.readTree(response.contentAsByteArray)["version"].longValue())
        assertTrue(checkNotNull(retainedBytes).all { it == 0.toByte() })
        List(8) { checkNotNull(fixture.owner.acquire()) }.forEach { it.close() }
    }

    @Test
    fun `fifty maximum legal rows respect item and aggregate limits and no extra top level notices`() {
        val items = List(50) { content(maximal = true, closed = true) }.sortedByDescending { it.id.toString() }
        val response = Fixture(ComplaintAdminReadResult.Page(items, null)).request(adminReadSearchRequest(scope))
        assertEquals(200, response.status)
        assertHeaders(response, "application/json", 2 * 1024 * 1024)
        val tree = mapper.readTree(response.contentAsByteArray)
        assertEquals(50, tree["items"].size())
        assertFalse(tree.has("notices"))
        tree["items"].forEach { assertTrue(mapper.writeValueAsBytes(it).size <= 32 * 1024) }
    }

    @Test
    fun `escaped overflow closes the one shared response owner before any success prefix or ETag`() {
        val item = content()
        // Deliberate post-validation DTO fault injection checks the last writer, not a normal construction route.
        item.content.javaClass.getDeclaredField("body").apply { isAccessible = true }.set(item.content, "\"".repeat(20 * 1024))
        val fixture = Fixture(ComplaintAdminReadResult.Page(listOf(item), null))
        val response = fixture.request(adminReadSearchRequest(scope))
        assertEquals(500, response.status)
        assertHeaders(response, "application/problem+json", 512)
        assertFalse(response.contentAsString.contains(item.id.toString()))
        assertFalse(response.contentAsString.contains("items"))
        assertNull(response.getHeader("ETag"))
        assertFalse(fixture.owner.isOpen())
        assertFalse(ComplaintAdminReadResponses(fixture.owner).isOpen())
        assertNull(fixture.owner.acquire())
        assertEquals(503, fixture.request(adminReadSearchRequest(scope)).status)
        assertEquals(listOf("authenticate", "read"), fixture.calls)
    }

    @Test
    fun `all problems are fixed allowlisted envelopes with one401 challenge and no sensitive inputs`() {
        for (failure in ComplaintAdminReadFailure.entries) {
            val fixture = Fixture().apply { this.failure = ComplaintAdminReadRejected(failure) }
            val response = fixture.request(adminReadSearchRequest(scope))
            assertEquals(failure.status, response.status)
            assertHeaders(response, "application/problem+json", 512)
            val tree = mapper.readTree(response.contentAsByteArray)
            assertEquals(setOf("type", "title", "status", "errors"), tree.fieldNames().asSequence().toSet())
            assertEquals("about:blank", tree["type"].asText())
            assertEquals(failure.title, tree["title"].asText())
            assertEquals(failure.status, tree["status"].intValue())
            assertEquals(1, tree["errors"].size())
            assertEquals(failure.code, tree["errors"][0]["code"].asText())
            assertEquals(setOf("code", "message"), tree["errors"][0].fieldNames().asSequence().toSet())
            assertEquals(if (failure.status == 401) listOf("Bearer realm=\"kira-complaints\"") else emptyList<String>(), response.getHeaders("WWW-Authenticate").toList())
            assertFalse(response.contentAsString.contains(scope.id.toString()))
            assertFalse(response.contentAsString.contains("synthetic-token"))
            assertNull(response.getHeader("ETag"))
            assertEquals(failure != ComplaintAdminReadFailure.INTERNAL, fixture.owner.isOpen())
        }
        val head = Fixture().request(adminReadSearchRequest(scope).apply { method = "HEAD" })
        assertEquals(404, head.status)
        assertEquals(0, head.contentAsByteArray.size)
        assertTrue(checkNotNull(head.getHeader("Content-Length")).toInt() in 1..512)
    }

    @Test
    fun `partial buffered success or problem delivery is never appended retried or retained`() {
        for (problem in listOf(false, true)) {
            for (fault in DeliveryFault.entries) {
                val fixture = Fixture(ComplaintAdminReadResult.Detail(content())).apply {
                    if (problem) failure = ComplaintAdminReadRejected(ComplaintAdminReadFailure.NOT_FOUND)
                }
                val response = PartialResponse(fault)
                val failure = assertThrows<IOException> { fixture.handler.handleRequest(adminReadSearchRequest(scope), response) }
                assertEquals("Complaint response delivery failed.", failure.message)
                assertNull(failure.cause)
                assertTrue(failure.suppressed.isEmpty())
                assertEquals(1, response.sends)
                assertFalse(response.isCommitted)
                assertEquals(24, response.prefix.size())
                assertEquals(if (problem) 404 else 200, response.status)
                if (!problem) assertTrue(checkNotNull(response.buffer).all { it == 0.toByte() })
                assertEquals(fault == DeliveryFault.IO, fixture.owner.isOpen())
            }
        }
    }

    @Test
    fun `problem committed guard and sender faults are sanitized once and preserve interruption`() {
        for (guardFault in listOf(false, true)) {
            for (fault in listOf(IllegalStateException("Synthetic private guard"), OutOfMemoryError("Synthetic private guard"), InterruptedIOException("Synthetic private guard"))) {
                val fixture = Fixture().apply { failure = ComplaintAdminReadRejected(ComplaintAdminReadFailure.UNAUTHORIZED) }
                var guards = 0
                var sends = 0
                val response = object : MockHttpServletResponse() {
                    override fun isCommitted(): Boolean {
                        guards++
                        if (guardFault) throw fault
                        return false
                    }

                    override fun getOutputStream(): ServletOutputStream {
                        sends++
                        throw fault
                    }
                }
                try {
                    val rejected = assertThrows<IOException> { fixture.handler.handleRequest(adminReadSearchRequest(scope), response) }
                    assertEquals("Complaint response delivery failed.", rejected.message)
                    assertNull(rejected.cause)
                    assertTrue(rejected.suppressed.isEmpty())
                    assertEquals(fault is InterruptedIOException, Thread.currentThread().isInterrupted)
                    assertEquals(fault is InterruptedIOException, fixture.owner.isOpen())
                    assertEquals(1, guards)
                    assertEquals(if (guardFault) 0 else 1, sends)
                } finally {
                    Thread.interrupted()
                }
            }
        }
    }

    @Test
    fun `redispatch asynchronous and interrupted callers never reach the producer`() {
        for (type in listOf(DispatcherType.FORWARD, DispatcherType.INCLUDE, DispatcherType.ERROR, DispatcherType.ASYNC)) {
            val fixture = Fixture()
            assertEquals(503, fixture.request(adminReadSearchRequest(scope).apply { dispatcherType = type }).status)
            assertTrue(fixture.calls.isEmpty())
        }
        val fixture = Fixture()
        val response = MockHttpServletResponse()
        try {
            Thread.currentThread().interrupt()
            assertThrows<InterruptedIOException> { fixture.handler.handleRequest(adminReadSearchRequest(scope), response) }
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(0, response.contentAsByteArray.size)
        } finally {
            Thread.interrupted()
        }
        assertTrue(fixture.calls.isEmpty())
        assertEquals(200, fixture.request(adminReadSearchRequest(scope)).status)
    }

    @Test
    fun `unchanged production disabled filter denies both Admin routes before any body or bearer work`() {
        for ((method, path) in listOf("POST" to "/api/v1/admin/complaints/search", "GET" to "/api/v1/admin/complaints/${UUID.randomUUID()}")) {
            val request = object : MockHttpServletRequest(method, path) {
                override fun getInputStream(): ServletInputStream = error("Disabled Admin route read a body")
                override fun getHeader(name: String): String? = error("Disabled Admin route read a bearer or request header")
            }
            val response = MockHttpServletResponse()
            var reached = false
            DisabledComplaintRoutesFilter().doFilter(request, response, FilterChain { _, _ -> reached = true })
            assertEquals(404, response.status)
            assertFalse(reached)
            assertNull(response.getHeader("ETag"))
        }
    }

    private fun content(version: Long = 1, parent: UUID? = null, key: String? = null, closed: Boolean = false, maximal: Boolean = false): ComplaintAdminItem.Content =
        ComplaintAdminItem.Content(
            ComplaintOwnerHistoryContent(
                UUID.randomUUID(), if (parent == null) ComplaintKind.REPORT else ComplaintKind.REPLY,
                if (key == null) ComplaintType.TECHNICAL else ComplaintType.CUSTOM,
                if (key != null) null else if (maximal) "\"".repeat(200) else "Synthetic subject",
                if (maximal) "𐐀".repeat(1000) else "Synthetic body", if (closed) ComplaintStatus.CLOSED else ComplaintStatus.OPEN,
                now, now, version, if (maximal) "\"".repeat(64) else null, ComplaintPlatform.ANDROID,
                if (maximal) "\\".repeat(128) else "", if (maximal) "𐐀".repeat(128) else "", if (maximal) "𐐀".repeat(128) else "",
                if (closed) if (maximal) "𐐀".repeat(500) else "Synthetic closure" else null, parent, key,
            ),
            UUID.randomUUID(), if (closed) now else null, if (closed) "ADMIN" else null, if (closed) UUID.randomUUID() else null,
        )

    private fun assertHeaders(response: MockHttpServletResponse, media: String, maximum: Int) {
        assertEquals("1", response.getHeader("X-Kira-Complaint-Contract"))
        assertEquals("no-store, no-transform", response.getHeader("Cache-Control"))
        assertEquals("$media;charset=UTF-8", response.contentType)
        assertTrue(response.contentAsByteArray.size in 1..maximum)
        assertEquals(response.contentAsByteArray.size.toString(), response.getHeader("Content-Length"))
        assertNull(response.getHeader("Location"))
        assertNull(response.getHeader("Content-Encoding"))
    }

    private class Fixture(
        val result: ComplaintAdminReadResult = ComplaintAdminReadResult.Page(emptyList(), null),
        val ingress: ComplaintIngressAdmission = adminReadTestIngress(),
        val owner: ComplaintOwnerHistoryResponses = ComplaintOwnerHistoryResponses(),
    ) {
        val responses = ComplaintAdminReadResponses(owner)
        val calls = mutableListOf<String>()
        var failure: RuntimeException? = null
        private val original = object : ComplaintAdminReadAuthentication {}
        private var originalContext: ComplaintAdminReadRequestContext? = null
        private val port = object : ComplaintAdminReadPort {
            override fun authenticate(context: ComplaintAdminReadRequestContext, bearer: String, query: ComplaintAdminReadQuery): ComplaintAdminReadAuthentication {
                requireConnectionFree()
                calls.add("authenticate")
                assertEquals("synthetic-token", bearer)
                originalContext = context
                return original
            }

            override fun read(context: ComplaintAdminReadRequestContext, authentication: ComplaintAdminReadAuthentication): ComplaintAdminReadResult {
                requireConnectionFree()
                calls.add("read")
                assertSame(originalContext, context)
                assertSame(original, authentication)
                failure?.let { throw it }
                return result
            }
        }
        val handler = ComplaintAdminReadHttpHandler(ComplaintAdminReadService(port), ingress, responses)

        fun request(request: MockHttpServletRequest): MockHttpServletResponse = MockHttpServletResponse().also { handler.handleRequest(request, it) }

        fun requireLiveContext() = ingress.requireLiveContext(originalContext as ComplaintIngressContext)
    }

    private inner class CountingRequest(search: Boolean, private val bytes: ByteArray, declaredLength: String?) :
        MockHttpServletRequest(if (search) "POST" else "GET", if (search) "/api/v1/admin/complaints/search" else "/api/v1/admin/complaints/${UUID.randomUUID()}") {
        var readBytes = 0
            private set
        private val stream = object : ServletInputStream() {
            override fun isFinished(): Boolean = readBytes == bytes.size
            override fun isReady(): Boolean = true
            override fun setReadListener(listener: ReadListener) = Unit
            override fun read(): Int = if (readBytes == bytes.size) -1 else bytes[readBytes++].toInt() and 255
        }

        init {
            remoteAddr = "192.0.2.1"
            addHeader("Authorization", "Bearer synthetic-token")
            if (search) contentType = "application/json" else queryString = "dataScopeId=${scope.id}"
            declaredLength?.let { addHeader("Content-Length", it) }
        }

        override fun getInputStream(): ServletInputStream = stream
    }

    private enum class DeliveryFault { IO, RUNTIME, MEMORY }

    private class PartialResponse(private val fault: DeliveryFault) : MockHttpServletResponse() {
        var sends = 0
        var buffer: ByteArray? = null
        val prefix = ByteArrayOutputStream()

        override fun getOutputStream(): ServletOutputStream = object : ServletOutputStream() {
            override fun isReady(): Boolean = true
            override fun setWriteListener(listener: WriteListener) = Unit
            override fun write(value: Int) = error("Expected one bounded send")
            override fun write(bytes: ByteArray, offset: Int, count: Int) {
                sends++
                buffer = bytes
                prefix.write(bytes, offset, minOf(24, count))
                when (fault) {
                    DeliveryFault.IO -> throw IOException("Synthetic private response failure")
                    DeliveryFault.RUNTIME -> error("Synthetic private response failure")
                    DeliveryFault.MEMORY -> throw OutOfMemoryError("Synthetic private response failure")
                }
            }
        }
    }
}
