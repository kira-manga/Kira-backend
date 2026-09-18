package me.manga.kira.backend.complaint.api

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletInputStream
import jakarta.servlet.ServletOutputStream
import jakarta.servlet.WriteListener
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import me.manga.kira.backend.common.web.DisabledComplaintRoutesFilter
import me.manga.kira.backend.common.web.RequestBodySizeLimitFilter
import me.manga.kira.backend.complaint.application.ComplaintOwnerCreateService
import me.manga.kira.backend.complaint.application.ComplaintOwnerEditService
import me.manga.kira.backend.complaint.domain.ComplaintOwnerCreateInput
import me.manga.kira.backend.complaint.domain.ComplaintOwnerEditInput
import me.manga.kira.backend.complaint.domain.ComplaintOwnerEditPort
import me.manga.kira.backend.complaint.domain.ComplaintOwnerEditReceipt
import me.manga.kira.backend.complaint.domain.ComplaintOwnerEditRejection
import me.manga.kira.backend.complaint.domain.ComplaintOwnerEditStatusQuery
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationContext
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationPort
import me.manga.kira.backend.complaint.domain.ComplaintOwnerReceipt
import me.manga.kira.backend.complaint.domain.ComplaintOwnerStatusQuery
import me.manga.kira.backend.complaint.infrastructure.ComplaintInstallationBearerAuthenticator
import me.manga.kira.backend.security.ComplaintHttpIngressBridge
import me.manga.kira.backend.security.ComplaintInstallationRoutes
import me.manga.kira.backend.security.ComplaintInstallationSecurityChainFactory
import me.manga.kira.backend.security.ownerEditTestIngress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID

/** Real structural parsing, bridge, replayable guard and finite sender; deliberately synthetic port outcomes. */
class ComplaintOwnerEditHttpTest {
    private val mapper = ObjectMapper()
    private val id = UUID.fromString("123e4567-e89b-52d3-a456-426614174000")
    private val key = UUID.fromString("33333333-3333-4333-8333-333333333333")
    private val path = "/api/v1/complaints/$id/content"
    private val editBody = """{"subject":"  Synthetic edit  ","body":"  Synthetic body\r\nline  "}"""
    private val statusBody = """{"operation":"OWNER_EDIT","key":"$key","targetIds":["$id"],"fingerprint":"${"A".repeat(43)}"}"""

    @Test
    fun `edit and status retain the original outer ingress and return exact minimal two scalar acknowledgement`() {
        val f = Fixture()
        val direct = f.request(input())
        assertEquals(200, direct.status)
        assertEquals("""{"id":"$id","version":8}""", direct.contentAsString)
        assertEquals("\"complaint-$id-v8\"", direct.getHeader("ETag"))
        val parsed = checkNotNull(f.edited)
        assertEquals("  Synthetic edit  ", parsed.subject)
        assertEquals("  Synthetic body\r\nline  ", parsed.body, "Prose normalization belongs after actual authentication, never the structural parser.")
        assertEquals(id, parsed.targetId)
        assertEquals(key, parsed.key)
        assertEquals(7L, parsed.precondition.version)
        val status = f.request(input(STATUS))
        assertEquals(200, status.status)
        assertEquals(
            """{"outcome":"APPLIED","originalStatus":200,"etag":"\"complaint-$id-v8\"","body":{"id":"$id","version":8}}""",
            status.contentAsString,
        )
        assertEquals(id, checkNotNull(f.queried).targetId)
        assertEquals(key, checkNotNull(f.queried).key)
        assertNull(status.getHeader("ETag"))
        for (response in listOf(direct, status)) assertEnvelope(response)
        assertEquals(listOf("edit", "status"), f.calls)
        val bodyOnly = f.request(input(body = """{"body":"Notice body"}""".toByteArray()))
        assertEquals(200, bodyOnly.status)
        assertNull(checkNotNull(f.edited).subject)
    }

    @Test
    fun `edit rejected direct and status outcomes are exact closed privacy limited cells`() {
        for (code in ComplaintOwnerEditRejection.entries) {
            val f = Fixture().apply { receipt = ComplaintOwnerEditReceipt.Rejected(code) }
            val direct = f.request(input())
            val title = when (code.status) {
                404 -> "Not Found"
                412 -> "Precondition Failed"
                else -> "Conflict"
            }
            assertEquals(code.status, direct.status)
            assertEquals(
                """{"type":"about:blank","title":"$title","status":${code.status},"errors":[{"code":"${code.name}","message":"Complaint request refused."}]}""",
                direct.contentAsString,
            )
            val status = f.request(input(STATUS))
            assertEquals(200, status.status)
            assertEquals("""{"outcome":"REJECTED","originalStatus":${code.status},"problemCode":"${code.name}"}""", status.contentAsString)
            for (response in listOf(direct, status)) {
                assertEnvelope(response)
                assertFalse(response.contentAsString.contains(id.toString()))
                assertNull(response.getHeader("ETag"))
                assertNull(response.getHeader("Retry-After"))
            }
        }
    }

    @Test
    fun `both edit shapes are closed and status never coerces edits into a creation query`() {
        val invalidBodies = listOf(
            "{}", "[]", "null", """{"subject":"x"}""", """{"subject":null,"body":"x"}""",
            """{"body":null}""", """{"body":1}""", """{"body":"x","body":"y"}""",
            """{"subject":"x","subject":"y","body":"z"}""", "$editBody {}",
        ) + listOf("type", "parentId", "noticeKey", "metadata", "status", "owner", "closureReason", "id").map { field ->
            """{"body":"x","$field":"private"}"""
        }
        for (body in invalidBodies) {
            val f = Fixture()
            val response = f.request(input(body = body.toByteArray()))
            assertEquals(400, response.status)
            assertEquals("VALIDATION_FAILED", mapper.readTree(response.contentAsByteArray)["errors"][0]["code"].asText())
            assertTrue(f.calls.isEmpty())
        }
        val invalidStatus = listOf(
            statusBody.replace("[\"$id\"]", "[]"),
            statusBody.replace("[\"$id\"]", "[\"$id\",\"$key\"]"),
            statusBody.replace("OWNER_EDIT", "owner_edit"),
            statusBody.replace("OWNER_EDIT", "ADMIN_EDIT"),
            statusBody.replace("\"fingerprint\":", "\"body\":\"private\",\"fingerprint\":"),
            statusBody.replace(id.toString(), id.toString().uppercase()),
            "$statusBody true",
        )
        for (body in invalidStatus) {
            val f = Fixture()
            assertEquals(400, f.request(input(STATUS, body.toByteArray())).status)
            assertTrue(f.calls.isEmpty())
        }
        val f = Fixture()
        assertEquals(400, f.request(input(body = byteArrayOf(0xc3.toByte(), 0x28))).status)
        assertTrue(f.calls.isEmpty())
    }

    @Test
    fun `edit preconditions routes media framing and header bounds fail without invoking the port`() {
        val cases = listOf(
            input().apply { removeHeader("If-Match") } to 428,
            input().apply { addHeader("If-Match", "\"complaint-$id-v7\"") } to 412,
            input().apply {
                removeHeader("If-Match")
                addHeader("If-Match", "*")
            } to 412,
            input().apply {
                removeHeader("If-Match")
                addHeader("If-Match", "x".repeat(257))
            } to 412,
            input().apply { removeHeader("Authorization") } to 401,
            input().apply { addHeader("Authorization", "Bearer other") } to 400,
            input().apply { removeHeader("X-Kira-Idempotency-Key") } to 400,
            input().apply { addHeader("X-Kira-Complaint-Contract", "2") } to 400,
            input().apply { queryString = "body=private" } to 400,
            input().apply { method = "POST" } to 404,
            input().apply { requestURI = "$path/" } to 404,
            input().apply { requestURI = path.replace(id.toString(), id.toString().uppercase()) } to 404,
            input().apply { contentType = "text/plain" } to 415,
            input().apply { addHeader("Content-Encoding", "gzip") } to 415,
            input().apply {
                addHeader("Content-Length", "1")
                addHeader("Transfer-Encoding", "chunked")
            } to 400,
            input().apply { addHeader("Content-Length", "1") } to 400,
            input(body = ByteArray(16 * 1024 + 1) { 32 }) to 413,
            input(STATUS).apply { addHeader("If-Match", "\"complaint-$id-v7\"") } to 400,
            input(STATUS).apply { addHeader("X-Kira-Idempotency-Key", key.toString()) } to 400,
        )
        for ((request, expected) in cases) {
            val f = Fixture()
            assertEquals(expected, f.request(request).status)
            assertTrue(f.calls.isEmpty())
        }
        val f = Fixture()
        assertEquals(
            200,
            f.request(
                input().apply {
                    contextPath = "/kira"
                    requestURI = "/kira$path"
                },
            ).status,
        )
    }

    @Test
    fun `default graph remains closed and TEST composition rejects either half or a different response owner`() {
        val f = Fixture()
        assertTrue(ComplaintInstallationRoutes.matches(input()))
        assertTrue(ComplaintInstallationRoutes.isContent(input()))
        assertTrue(ComplaintInstallationRoutes.requiresBearer(input()))
        assertFalse(ComplaintInstallationRoutes.implemented(input()))
        val closed = MockHttpServletResponse()
        DisabledComplaintRoutesFilter().doFilter(input(), closed, FilterChain { _, _ -> error("default route must remain closed") })
        assertEquals(404, closed.status)
        val noEdit = ComplaintOwnerCreateHttpHandler(f.createService, f.ingress, f.responses)
        val status = MockHttpServletResponse().also { noEdit.handleRequest(input(STATUS), it) }
        assertEquals(400, status.status)
        assertTrue(f.calls.isEmpty())
        assertThrows<IllegalArgumentException> {
            ComplaintOwnerCreateHttpHandler(f.createService, f.ingress, ComplaintOwnerOperationResponse(), f.edit)
        }
        fun factory(create: ComplaintOwnerCreateHttpHandler, edit: ComplaintOwnerEditHttpHandler?) = ComplaintInstallationSecurityChainFactory(
            f.bridge,
            mock(ComplaintInstallationBearerAuthenticator::class.java),
            mock(ComplaintInstallationHttpHandler::class.java),
            mock(ComplaintInstallationMeHttpHandler::class.java),
            mock(ComplaintOwnerHistoryHttpHandler::class.java),
            create,
            edit = edit,
        )
        factory(noEdit, null)
        factory(f.create, f.edit)
        assertThrows<IllegalArgumentException> { factory(noEdit, f.edit) }
        assertThrows<IllegalArgumentException> { factory(f.create, null) }
    }

    @Test
    fun `shared response pool refusal precedes handler body read and partial output never appends a problem`() {
        val f = Fixture()
        val permits = List(8) { checkNotNull(f.responses.acquire()) }
        val unread = object : MockHttpServletRequest("PATCH", path) {
            override fun getInputStream(): ServletInputStream = error("No handler read when its response slots are full")
        }.apply {
            remoteAddr = "192.0.2.1"
            contentType = "application/json"
            addHeader("Authorization", "Bearer synthetic")
            addHeader("X-Kira-Idempotency-Key", key.toString())
            addHeader("If-Match", "\"complaint-$id-v7\"")
        }
        try {
            val response = MockHttpServletResponse().also { f.edit.handleRequest(unread, it) }
            assertEquals(503, response.status)
            assertTrue(f.calls.isEmpty())
        } finally {
            permits.forEach(AutoCloseable::close)
        }
        val prefix = ByteArrayOutputStream()
        val broken = object : MockHttpServletResponse() {
            override fun getOutputStream(): ServletOutputStream = object : ServletOutputStream() {
                override fun isReady(): Boolean = true
                override fun setWriteListener(listener: WriteListener) = Unit
                override fun write(value: Int) {
                    prefix.write(value)
                    if (prefix.size() == 12) throw IOException("Synthetic private sender failure")
                }
            }
        }
        val failure = assertThrows<IOException> { f.edit.handleRequest(input(), broken) }
        assertEquals("Complaint operation delivery failed.", failure.message)
        assertNull(failure.cause)
        assertEquals(12, prefix.size())
        assertEquals(200, broken.status)
        assertEquals(200, f.request(input()).status)
    }

    private fun input(
        selectedPath: String = path,
        body: ByteArray = (
            if (selectedPath ==
                STATUS
            ) {
                statusBody
            } else {
                editBody
            }
            ).toByteArray(),
    ): MockHttpServletRequest = MockHttpServletRequest(if (selectedPath == STATUS) "POST" else "PATCH", selectedPath).apply {
        remoteAddr = "192.0.2.1"
        contentType = "application/json"
        addHeader("Authorization", "Bearer synthetic")
        if (selectedPath != STATUS) {
            addHeader("X-Kira-Idempotency-Key", key.toString())
            addHeader("If-Match", "\"complaint-$id-v7\"")
        }
        setContent(body)
    }

    private fun assertEnvelope(response: MockHttpServletResponse) {
        assertEquals("1", response.getHeader("X-Kira-Complaint-Contract"))
        assertEquals("no-store, no-transform", response.getHeader("Cache-Control"))
        assertEquals(response.contentAsByteArray.size.toString(), response.getHeader("Content-Length"))
        assertTrue(response.contentAsByteArray.size <= 16 * 1024)
        assertNull(response.getHeader("Location"))
        for (privateValue in listOf("Synthetic", key.toString(), "actionTag", "location")) assertFalse(response.contentAsString.contains(privateValue))
    }

    private inner class Fixture {
        val ingress = ownerEditTestIngress()
        val bridge = ComplaintHttpIngressBridge(ingress)
        val responses = ComplaintOwnerOperationResponse()
        val calls = mutableListOf<String>()
        var receipt: ComplaintOwnerEditReceipt = ComplaintOwnerEditReceipt.Applied(id, 8)
        var edited: ComplaintOwnerEditInput? = null
        var queried: ComplaintOwnerEditStatusQuery? = null
        var expectedContext: ComplaintOwnerOperationContext? = null
        val edit = ComplaintOwnerEditHttpHandler(
            ComplaintOwnerEditService(object : ComplaintOwnerEditPort {
                override fun edit(context: ComplaintOwnerOperationContext, bearer: String, input: ComplaintOwnerEditInput): ComplaintOwnerEditReceipt {
                    expectedContext?.let { assertSame(it, context) }
                    assertEquals("synthetic", bearer)
                    calls.add("edit")
                    edited = input
                    return receipt
                }

                override fun status(context: ComplaintOwnerOperationContext, bearer: String, query: ComplaintOwnerEditStatusQuery): ComplaintOwnerEditReceipt {
                    expectedContext?.let { assertSame(it, context) }
                    assertEquals("synthetic", bearer)
                    calls.add("status")
                    queried = query
                    return receipt
                }
            }),
            ingress,
            responses,
        )
        val createService = ComplaintOwnerCreateService(object : ComplaintOwnerOperationPort {
            override fun create(context: ComplaintOwnerOperationContext, bearer: String, input: ComplaintOwnerCreateInput): ComplaintOwnerReceipt =
                error("Edit cannot create")
            override fun status(context: ComplaintOwnerOperationContext, bearer: String, query: ComplaintOwnerStatusQuery): ComplaintOwnerReceipt =
                error("Edit cannot enter creation status")
        })
        val create = ComplaintOwnerCreateHttpHandler(createService, ingress, responses, edit)

        fun request(request: MockHttpServletRequest): MockHttpServletResponse = MockHttpServletResponse().also { response ->
            bridge.doFilter(
                request,
                response,
                FilterChain { admitted, outgoing ->
                    RequestBodySizeLimitFilter(mapper).doFilter(
                        admitted,
                        outgoing,
                        FilterChain { buffered, selected ->
                            val actual = buffered as HttpServletRequest
                            val context = bridge.claimHandler(actual)
                            expectedContext = context
                            if (actual.requestURI.removePrefix(actual.contextPath) == STATUS) {
                                create.handleWithinIngress(actual, selected as HttpServletResponse, context)
                            } else {
                                edit.handleWithinIngress(actual, selected as HttpServletResponse, context)
                            }
                        },
                    )
                },
            )
        }
    }

    private companion object {
        const val STATUS = ComplaintOwnerCreateHttpHandler.STATUS
    }
}
