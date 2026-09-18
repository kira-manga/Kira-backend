package me.manga.kira.backend.complaint.api

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import me.manga.kira.backend.common.web.DisabledComplaintRoutesFilter
import me.manga.kira.backend.complaint.application.ComplaintOwnerCreateService
import me.manga.kira.backend.complaint.domain.ComplaintOwnerCreateInput
import me.manga.kira.backend.complaint.domain.ComplaintOwnerCreationOperation
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationContext
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationPort
import me.manga.kira.backend.complaint.domain.ComplaintOwnerReceipt
import me.manga.kira.backend.complaint.domain.ComplaintOwnerReplyInput
import me.manga.kira.backend.complaint.domain.ComplaintOwnerReplyRejection
import me.manga.kira.backend.complaint.domain.ComplaintOwnerStatusQuery
import me.manga.kira.backend.security.ComplaintHttpIngressBridge
import me.manga.kira.backend.security.ComplaintInstallationRoutes
import me.manga.kira.backend.security.ownerCreateTestIngress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.util.UUID

/** Real strict parser/ingress/bounded sender, synthetic domain receipts. Receipt durability belongs to the existing PG carrier. */
class ComplaintOwnerReplyHttpTest {
    private val mapper = ObjectMapper()
    private val parent = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
    private val id = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
    private val key = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc")
    private val replyPath = "/api/v1/complaints/$parent/replies"
    private val replyBody = """{"id":"$id","body":"  Synthetic reply payload\r\n  ","metadata":{"appVersion":null,""" +
        """"osVersion":"","manufacturer":"Synthetic manufacturer","deviceModel":""}}"""
    private val statusBody = """{"operation":"OWNER_REPLY","key":"$key","targetIds":["$parent","$id"],"fingerprint":"${"A".repeat(43)}"}"""

    @Test
    fun `reply dispatch preserves outer ingress raw body and ordered status with minimal creation acknowledgement`() {
        val f = Fixture()
        val bridge = ComplaintHttpIngressBridge(f.ingress)
        val direct = MockHttpServletResponse()
        bridge.doFilter(input(), direct) { admitted, response ->
            val request = admitted as HttpServletRequest
            val context = bridge.claimHandler(request)
            f.handler.handleWithinIngress(request, response as HttpServletResponse, context)
            assertSame(context, f.context)
        }
        assertEquals(201, direct.status)
        assertEquals("""{"id":"$id","version":1}""", direct.contentAsString)
        assertEquals("/api/v1/complaints/$id", direct.getHeader("Location"))
        assertEquals("\"complaint-$id-v1\"", direct.getHeader("ETag"))
        assertHeaders(direct)
        assertNoProse(direct)
        val received = checkNotNull(f.replied)
        assertEquals(parent, received.parentId)
        assertEquals(id, received.id)
        assertEquals(key, received.key)
        assertEquals("  Synthetic reply payload\r\n  ", received.body, "Structural parsing must not normalize before authentication.")
        assertNull(received.metadata.appVersion)
        assertEquals("Synthetic manufacturer", received.metadata.manufacturer)
        val status = f.request(input(STATUS))
        assertEquals(200, status.status)
        assertEquals(
            """{"outcome":"APPLIED","originalStatus":201,"location":"/api/v1/complaints/$id",""" +
                """"etag":"\"complaint-$id-v1\"","body":{"id":"$id","version":1}}""",
            status.contentAsString,
        )
        assertEquals(ComplaintOwnerCreationOperation.OWNER_REPLY, checkNotNull(f.queried).operation)
        assertEquals(listOf(parent, id), checkNotNull(f.queried).targetIds())
        assertEquals(key, checkNotNull(f.queried).key)
        assertNull(status.getHeader("Location"))
        assertNull(status.getHeader("ETag"))
        assertHeaders(status)
        assertNoProse(status)
        assertEquals(listOf("reply", "status"), f.calls)
    }

    @Test
    fun `reply parent and deletion dispositions keep original 404 or 409 without prose in direct and status unions`() {
        for (code in ComplaintOwnerReplyRejection.entries) {
            val f = Fixture().apply { receipt = ComplaintOwnerReceipt.Rejected(code) }
            val direct = f.request(input())
            val title = if (code.status == 404) "Not Found" else "Conflict"
            assertEquals(code.status, direct.status)
            assertEquals(
                """{"type":"about:blank","title":"$title","status":${code.status},""" +
                    """"errors":[{"code":"${code.name}","message":"Complaint request refused."}]}""",
                direct.contentAsString,
            )
            assertTrue(checkNotNull(direct.contentType).startsWith("application/problem+json"))
            val status = f.request(input(STATUS))
            assertEquals(200, status.status)
            assertEquals(
                """{"outcome":"REJECTED","originalStatus":${code.status},"problemCode":"${code.name}"}""",
                status.contentAsString,
            )
            for (response in listOf(direct, status)) {
                assertHeaders(response)
                assertNoProse(response)
                assertFalse(response.contentAsString.contains(id.toString()))
                assertNull(response.getHeader("Location"))
                assertNull(response.getHeader("ETag"))
                assertNull(response.getHeader("Retry-After"))
            }
            assertEquals(listOf("reply", "status"), f.calls)
        }
    }

    @Test
    fun `reply parser is closed and status requires exactly two distinct ordered targets`() {
        val invalidReplies = listOf(
            replyBody.replace("\"body\":", "\"type\":\"TECHNICAL\",\"body\":"),
            replyBody.replace("\"body\":", "\"subject\":\"Synthetic subject\",\"body\":"),
            replyBody.replace("\"body\":", "\"parentId\":\"$parent\",\"body\":"),
            replyBody.replace("\"body\":", "\"owner\":\"$id\",\"body\":"),
            replyBody.replace("\"body\":", "\"body\":\"duplicate\",\"body\":"),
            replyBody.replace("\"  Synthetic reply payload\\r\\n  \"", "null"),
            replyBody.replace("\"appVersion\":null,", ""),
            replyBody.replace("\"osVersion\":\"\"", "\"osVersion\":null"),
            replyBody.replace("\"appVersion\":null", "\"appVersion\":null,\"appVersion\":null"),
            replyBody.replace("\"id\":\"$id\"", "\"id\":\"$parent\""),
            "$replyBody {}",
        )
        val invalidStatuses = listOf(
            statusBody.replace("[\"$parent\",\"$id\"]", "[\"$id\"]"),
            statusBody.replace("[\"$parent\",\"$id\"]", "[\"$id\",\"$id\"]"),
            statusBody.replace("[\"$parent\",\"$id\"]", "[\"$parent\",\"$id\",\"$key\"]"),
            statusBody.replace("OWNER_REPLY", "OWNER_CREATE"),
            statusBody.replace("\"fingerprint\":", "\"body\":\"Synthetic reply payload\",\"fingerprint\":"),
        )
        val cases = invalidReplies.map { input(body = it.toByteArray()) } + invalidStatuses.map { input(STATUS, it.toByteArray()) }
        for (request in cases) {
            val f = Fixture()
            val response = f.request(request)
            assertEquals(400, response.status)
            assertEquals("VALIDATION_FAILED", mapper.readTree(response.contentAsByteArray)["errors"][0]["code"].asText())
            assertTrue(f.calls.isEmpty())
            assertNoProse(response)
        }
        val reversed = Fixture()
        val reversedBody = statusBody.replace("[\"$parent\",\"$id\"]", "[\"$id\",\"$parent\"]").toByteArray()
        assertEquals(200, reversed.request(input(STATUS, reversedBody)).status)
        assertEquals(
            listOf(id, parent), checkNotNull(reversed.queried).targetIds(),
            "Never sort the status tuple; the store must compare its original order.",
        )
    }

    @Test
    fun `canonical reply route rejects aliases and mutation preconditions while permitting canonical server notice parents`() {
        val invalid = listOf(
            input().apply { requestURI = "$replyPath/" } to 404,
            input().apply { requestURI = replyPath.replace(parent.toString(), parent.toString().uppercase()) } to 404,
            input().apply { requestURI = replyPath.replace(parent.toString(), "a-a-a-a-a") } to 404,
            input().apply { method = "GET" } to 404,
            input().apply { queryString = "owner=$id" } to 400,
            input().apply { removeHeader("X-Kira-Idempotency-Key") } to 400,
            input(STATUS).apply { addHeader("X-Kira-Idempotency-Key", key.toString()) } to 400,
            input().apply { addHeader("If-Match", "\"complaint-$parent-v1\"") } to 400,
            input().apply { addHeader("Content-Encoding", "gzip") } to 415,
            input(body = ByteArray(16 * 1024 + 1) { 32 }) to 413,
        )
        for ((request, expected) in invalid) {
            val f = Fixture()
            val response = f.request(request)
            assertEquals(expected, response.status)
            assertTrue(f.calls.isEmpty())
            assertNoProse(response)
        }
        val notice = UUID.fromString("77777777-7777-5777-8777-777777777777")
        val f = Fixture()
        assertEquals(201, f.request(input().apply { requestURI = replyPath.replace(parent.toString(), notice.toString()) }).status)
        assertEquals(notice, checkNotNull(f.replied).parentId)
    }

    @Test
    fun `reply stays dormant by default and reuses the existing finite response owner without create fallback`() {
        val f = Fixture()
        assertTrue(ComplaintInstallationRoutes.matches(input()))
        assertTrue(ComplaintInstallationRoutes.isReply(input()))
        assertTrue(ComplaintInstallationRoutes.requiresBearer(input()))
        assertFalse(ComplaintInstallationRoutes.implemented(input()))
        var reached = false
        val closed = MockHttpServletResponse()
        DisabledComplaintRoutesFilter().doFilter(input(), closed, FilterChain { _, _ -> reached = true })
        assertFalse(reached)
        assertEquals(404, closed.status)
        val legacyPort = object : ComplaintOwnerOperationPort {
            override fun create(context: ComplaintOwnerOperationContext, bearer: String, input: ComplaintOwnerCreateInput): ComplaintOwnerReceipt =
                error("Reply must not fall through to report creation.")

            override fun status(context: ComplaintOwnerOperationContext, bearer: String, query: ComplaintOwnerStatusQuery): ComplaintOwnerReceipt =
                error("Reply must not fall through to status.")
        }
        val defaultReply = ComplaintOwnerCreateHttpHandler(ComplaintOwnerCreateService(legacyPort), ownerCreateTestIngress())
        val refused = MockHttpServletResponse().also { defaultReply.handleRequest(input(), it) }
        assertEquals(503, refused.status)
        assertEquals("SERVICE_UNAVAILABLE", mapper.readTree(refused.contentAsByteArray)["errors"][0]["code"].asText())
        val permits = List(8) { checkNotNull(f.responses.acquire()) }
        try {
            assertEquals(503, f.request(input()).status)
            assertTrue(f.calls.isEmpty())
        } finally {
            permits.forEach(AutoCloseable::close)
        }
        assertEquals(201, f.request(input()).status)
        assertEquals(listOf("reply"), f.calls)
    }

    private fun input(path: String = replyPath, body: ByteArray = (if (path == STATUS) statusBody else replyBody).toByteArray()): MockHttpServletRequest =
        MockHttpServletRequest("POST", path).apply {
            remoteAddr = "192.0.2.1"
            contentType = "application/json"
            addHeader("Authorization", "Bearer synthetic")
            if (path != STATUS) addHeader("X-Kira-Idempotency-Key", key.toString())
            setContent(body)
        }

    private fun assertHeaders(response: MockHttpServletResponse) {
        assertEquals("1", response.getHeader("X-Kira-Complaint-Contract"))
        assertEquals("no-store, no-transform", response.getHeader("Cache-Control"))
        assertEquals(response.contentAsByteArray.size.toString(), response.getHeader("Content-Length"))
        assertTrue(response.contentAsByteArray.size <= 16 * 1024)
    }

    private fun assertNoProse(response: MockHttpServletResponse) {
        for (privateValue in listOf("Synthetic", parent.toString(), key.toString())) assertFalse(response.contentAsString.contains(privateValue))
    }

    private inner class Fixture {
        val ingress = ownerCreateTestIngress()
        val responses = ComplaintOwnerOperationResponse()
        val calls = mutableListOf<String>()
        var receipt: ComplaintOwnerReceipt = ComplaintOwnerReceipt.Applied(id, 1)
        var replied: ComplaintOwnerReplyInput? = null
        var queried: ComplaintOwnerStatusQuery? = null
        var context: ComplaintOwnerOperationContext? = null
        private val port = object : ComplaintOwnerOperationPort {
            override fun create(context: ComplaintOwnerOperationContext, bearer: String, input: ComplaintOwnerCreateInput): ComplaintOwnerReceipt {
                calls.add("create")
                return receipt
            }

            override fun reply(context: ComplaintOwnerOperationContext, bearer: String, input: ComplaintOwnerReplyInput): ComplaintOwnerReceipt {
                assertEquals("synthetic", bearer)
                calls.add("reply")
                this@Fixture.context = context
                replied = input
                return receipt
            }

            override fun status(context: ComplaintOwnerOperationContext, bearer: String, query: ComplaintOwnerStatusQuery): ComplaintOwnerReceipt {
                assertEquals("synthetic", bearer)
                calls.add("status")
                queried = query
                return receipt
            }
        }
        val handler = ComplaintOwnerCreateHttpHandler(ComplaintOwnerCreateService(port), ingress, responses)
        fun request(request: MockHttpServletRequest): MockHttpServletResponse = MockHttpServletResponse().also { handler.handleRequest(request, it) }
    }

    private companion object {
        const val STATUS = ComplaintOwnerCreateHttpHandler.STATUS
    }
}
