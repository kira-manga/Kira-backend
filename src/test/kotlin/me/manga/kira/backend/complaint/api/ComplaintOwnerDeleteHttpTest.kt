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
import me.manga.kira.backend.complaint.application.ComplaintOwnerDeleteService
import me.manga.kira.backend.complaint.domain.ComplaintOwnerCreateInput
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteInput
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeletePort
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteReceipt
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteRejection
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteStatusQuery
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationContext
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationFailure
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationPort
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationRejected
import me.manga.kira.backend.complaint.domain.ComplaintOwnerReceipt
import me.manga.kira.backend.complaint.domain.ComplaintOwnerStatusQuery
import me.manga.kira.backend.complaint.infrastructure.ComplaintInstallationBearerAuthenticator
import me.manga.kira.backend.security.ComplaintHttpIngressBridge
import me.manga.kira.backend.security.ComplaintInstallationRoutes
import me.manga.kira.backend.security.ComplaintInstallationSecurityChainFactory
import me.manga.kira.backend.security.ownerDeleteTestIngress
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

/** Actual ingress/parser/sender, deliberately synthetic domain outcomes; never SQL apply or TEST registration proof. */
class ComplaintOwnerDeleteHttpTest {
    private val id = UUID.fromString("e5555555-5555-4555-8555-555555555555")
    private val key = UUID.fromString("d4444444-4444-4444-8444-444444444444")
    private val path = "/api/v1/complaints/$id"
    private val statusBody = """{"operation":"OWNER_DELETE","key":"$key","targetIds":["$id"],"fingerprint":"PgZfSK3fannK-yvw2dmhLHQjZrV9InbiTKOwF-vE4Gs"}"""

    @Test
    fun `empty delete returns exactly 204 without representation and status exactly the two scalar acknowledgement`() {
        val f = Fixture()
        for (media in listOf(null, "application/json", "application/json; charset=UTF-8")) {
            val direct = f.send(input().apply { contentType = media })
            assertEquals(204, direct.status)
            assertEquals(0, direct.contentAsByteArray.size)
            assertNull(direct.contentType)
            assertNull(direct.getHeader("ETag"))
            assertNull(direct.getHeader("Location"))
            envelope(direct)
            assertEquals(id, checkNotNull(f.deleted).targetId)
            assertEquals(key, checkNotNull(f.deleted).key)
            assertEquals(42L, checkNotNull(f.deleted).precondition.version)
        }
        val status = f.send(input(STATUS))
        assertEquals(200, status.status)
        assertEquals("""{"outcome":"APPLIED","originalStatus":204}""", status.contentAsString)
        assertEquals(id, checkNotNull(f.queried).targetId)
        assertEquals(key, checkNotNull(f.queried).key)
        assertNull(status.getHeader("ETag"))
        assertNull(status.getHeader("Location"))
        envelope(status)
        assertEquals(listOf("delete", "delete", "delete", "status"), f.calls)
    }

    @Test
    fun `direct and status rejections expose only the closed minimal historical cells`() {
        for (code in ComplaintOwnerDeleteRejection.entries) {
            val f = Fixture().apply { receipt = ComplaintOwnerDeleteReceipt.Rejected(code) }
            val direct = f.send(input())
            val title = when (code.status) { 404 -> "Not Found"; 412 -> "Precondition Failed"; else -> "Conflict" }
            assertEquals(code.status, direct.status)
            assertEquals(
                """{"type":"about:blank","title":"$title","status":${code.status},"errors":[{"code":"${code.name}","message":"Complaint request refused."}]}""",
                direct.contentAsString,
            )
            val status = f.send(input(STATUS))
            assertEquals(200, status.status)
            assertEquals("""{"outcome":"REJECTED","originalStatus":${code.status},"problemCode":"${code.name}"}""", status.contentAsString)
            for (response in listOf(direct, status)) {
                envelope(response)
                assertNull(response.getHeader("ETag"))
                assertNull(response.getHeader("Retry-After"))
                assertFalse(response.contentAsString.contains(id.toString()))
            }
        }
    }

    @Test
    fun `bodyless request refuses nonempty JSON whitespace bad preconditions identity media and framing before port`() {
        val cases = listOf(
            input().apply { removeHeader("If-Match") } to 428,
            input().apply { addHeader("If-Match", "\"complaint-$id-v42\"") } to 412,
            input().apply { removeHeader("If-Match"); addHeader("If-Match", "*") } to 412,
            input().apply { removeHeader("If-Match"); addHeader("If-Match", "W/\"complaint-$id-v42\"") } to 412,
            input().apply { removeHeader("If-Match"); addHeader("If-Match", "x".repeat(257)) } to 412,
            input().apply { removeHeader("Authorization") } to 401,
            input().apply { addHeader("Authorization", "Bearer other") } to 400,
            input().apply { removeHeader("X-Kira-Idempotency-Key") } to 400,
            input().apply { removeHeader("X-Kira-Idempotency-Key"); addHeader("X-Kira-Idempotency-Key", key.toString().uppercase()) } to 400,
            input().apply { removeHeader("X-Kira-Idempotency-Key"); addHeader("X-Kira-Idempotency-Key", "00000000-0000-0000-0000-000000000000") } to 400,
            input().apply { addHeader("X-Kira-Complaint-Contract", "2") } to 400,
            input().apply { queryString = "target=private" } to 400,
            input().apply { method = "POST" } to 404,
            input().apply { requestURI = "$path/" } to 404,
            input().apply { requestURI = path.replace(id.toString(), id.toString().uppercase()) } to 404,
            input().apply { contentType = "text/plain" } to 415,
            input().apply { contentType = "application/json; charset=utf-16" } to 415,
            input().apply { addHeader("Content-Encoding", "gzip") } to 415,
            input().apply { addHeader("Content-Length", "0"); addHeader("Transfer-Encoding", "chunked") } to 400,
            input().apply { addHeader("Content-Length", "1") } to 400,
            input(body = "{}".toByteArray()) to 400,
            input(body = "null".toByteArray()) to 400,
            input(body = " \n".toByteArray()) to 400,
            input(body = byteArrayOf(0)) to 400,
            input(body = ByteArray(16385) { 32 }) to 413,
        )
        for ((request, expected) in cases) {
            val f = Fixture()
            assertEquals(expected, f.send(request).status, request.requestURI)
            assertTrue(f.calls.isEmpty())
        }
        val f = Fixture()
        assertEquals(204, f.send(input().apply { addHeader("Transfer-Encoding", "chunked") }).status)
        assertEquals(204, f.send(input().apply { addHeader("Content-Length", "0") }).status)
        assertEquals(204, f.send(input().apply { contextPath = "/kira"; requestURI = "/kira$path" }).status)
    }

    @Test
    fun `status requires one exact target with no invented body version credential or delete all branch`() {
        val invalid = listOf(
            statusBody.replace("[\"$id\"]", "[]"),
            statusBody.replace("[\"$id\"]", "[\"$id\",\"$key\"]"),
            statusBody.replace("OWNER_DELETE", "OWNER_DELETE_ALL"),
            statusBody.replace("OWNER_DELETE", "owner_delete"),
            statusBody.replace(id.toString(), id.toString().uppercase()),
            statusBody.replace("\"key\":", "\"version\":42,\"key\":"),
            statusBody.replace("\"key\":", "\"credentialVersion\":9,\"key\":"),
            "$statusBody {}",
            statusBody.replace("\"operation\":", "\"body\":null,\"operation\":"),
        )
        for (body in invalid) {
            val f = Fixture()
            assertEquals(400, f.send(input(STATUS, body.toByteArray())).status)
            assertTrue(f.calls.isEmpty())
        }
        for (header in listOf("If-Match", "X-Kira-Idempotency-Key")) {
            val f = Fixture()
            assertEquals(400, f.send(input(STATUS).apply { addHeader(header, key.toString()) }).status)
            assertTrue(f.calls.isEmpty())
        }
    }

    @Test
    fun `default route is dormant and optional delete and status require the same ingress response and handler owner`() {
        val f = Fixture()
        assertTrue(ComplaintInstallationRoutes.matches(input()))
        assertTrue(ComplaintInstallationRoutes.isDetail(input()))
        assertTrue(ComplaintInstallationRoutes.requiresBearer(input()))
        assertFalse(ComplaintInstallationRoutes.implemented(input()))
        val closed = MockHttpServletResponse()
        DisabledComplaintRoutesFilter().doFilter(input(), closed, FilterChain { _, _ -> error("Default DELETE must remain closed") })
        assertEquals(404, closed.status)
        val noDelete = ComplaintOwnerCreateHttpHandler(f.createService, f.ingress, f.responses)
        assertEquals(400, MockHttpServletResponse().also { noDelete.handleRequest(input(STATUS), it) }.status)
        assertThrows<IllegalArgumentException> { ComplaintOwnerCreateHttpHandler(f.createService, f.ingress, ComplaintOwnerOperationResponse(), deleteStatus = f.delete) }
        assertThrows<IllegalArgumentException> { ComplaintOwnerCreateHttpHandler(f.createService, ownerDeleteTestIngress(), f.responses, deleteStatus = f.delete) }
        fun factory(create: ComplaintOwnerCreateHttpHandler, delete: ComplaintOwnerDeleteHttpHandler?) = ComplaintInstallationSecurityChainFactory(
            f.bridge, mock(ComplaintInstallationBearerAuthenticator::class.java), mock(ComplaintInstallationHttpHandler::class.java),
            mock(ComplaintInstallationMeHttpHandler::class.java), mock(ComplaintOwnerHistoryHttpHandler::class.java), create, delete = delete,
        )
        factory(noDelete, null)
        factory(f.create, f.delete)
        assertThrows<IllegalArgumentException> { factory(noDelete, f.delete) }
        assertThrows<IllegalArgumentException> { factory(f.create, null) }
        assertThrows<IllegalArgumentException> { factory(f.create, Fixture().delete) }
    }

    @Test
    fun `unproved work is retryable 503 never 202 or assumed 204 and only claim wait has retry after one`() {
        for (failure in listOf(ComplaintOwnerOperationFailure.UNAVAILABLE, ComplaintOwnerOperationFailure.IN_PROGRESS, ComplaintOwnerOperationFailure.NOT_FOUND)) {
            val f = Fixture().apply { rejected = failure }
            for (request in listOf(input(), input(STATUS))) {
                val response = f.send(request)
                assertEquals(failure.status, response.status)
                assertEquals(if (failure == ComplaintOwnerOperationFailure.IN_PROGRESS) "1" else null, response.getHeader("Retry-After"))
                assertEquals(failure.code, ObjectMapper().readTree(response.contentAsByteArray)["errors"][0]["code"].asText())
                envelope(response)
            }
        }
    }

    @Test
    fun `no body writer is opened for 204 and full response slots refuse before input while partial status never appends a problem`() {
        val f = Fixture()
        val noBody = object : MockHttpServletResponse() {
            override fun getOutputStream(): ServletOutputStream = error("A 204 has no representation writer")
        }
        f.delete.handleRequest(input(), noBody)
        assertEquals(204, noBody.status)
        val permits = List(8) { checkNotNull(f.responses.acquire()) }
        val unread = object : MockHttpServletRequest("DELETE", path) {
            override fun getInputStream(): ServletInputStream = error("No read while response slots are full")
        }.apply {
            remoteAddr = "192.0.2.1"
            addHeader("Authorization", "Bearer synthetic")
            addHeader("X-Kira-Idempotency-Key", key.toString())
            addHeader("If-Match", "\"complaint-$id-v42\"")
        }
        try {
            assertEquals(503, MockHttpServletResponse().also { f.delete.handleRequest(unread, it) }.status)
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
                    if (prefix.size() == 12) throw IOException("Synthetic private send failure")
                }
            }
        }
        val failure = assertThrows<IOException> { f.create.handleRequest(input(STATUS), broken) }
        assertEquals("Complaint operation delivery failed.", failure.message)
        assertNull(failure.cause)
        assertEquals(12, prefix.size())
        assertEquals(200, broken.status)
        assertEquals(204, f.send(input()).status)
    }

    private fun input(selectedPath: String = path, body: ByteArray = if (selectedPath == STATUS) statusBody.toByteArray() else ByteArray(0)): MockHttpServletRequest =
        MockHttpServletRequest(if (selectedPath == STATUS) "POST" else "DELETE", selectedPath).apply {
            remoteAddr = "192.0.2.1"
            addHeader("Authorization", "Bearer synthetic")
            if (selectedPath == STATUS) contentType = "application/json" else {
                addHeader("X-Kira-Idempotency-Key", key.toString())
                addHeader("If-Match", "\"complaint-$id-v42\"")
            }
            setContent(body)
        }

    private fun envelope(response: MockHttpServletResponse) {
        assertEquals("1", response.getHeader("X-Kira-Complaint-Contract"))
        assertEquals("no-store, no-transform", response.getHeader("Cache-Control"))
        if (response.status == 204) {
            assertNull(response.getHeader("Content-Length"))
            assertNull(response.getHeader("Transfer-Encoding"))
            assertNull(response.contentType)
            assertNull(response.getHeader("ETag"))
        } else {
            assertEquals(response.contentAsByteArray.size.toString(), response.getHeader("Content-Length"))
        }
        assertTrue(response.contentAsByteArray.size <= 16384)
        assertNull(response.getHeader("Location"))
        for (privateValue in listOf(id.toString(), key.toString(), "Synthetic", "fingerprint", "credentialVersion")) assertFalse(response.contentAsString.contains(privateValue))
    }

    private inner class Fixture {
        val ingress = ownerDeleteTestIngress()
        val bridge = ComplaintHttpIngressBridge(ingress)
        val responses = ComplaintOwnerOperationResponse()
        val calls = mutableListOf<String>()
        var receipt: ComplaintOwnerDeleteReceipt = ComplaintOwnerDeleteReceipt.Applied
        var rejected: ComplaintOwnerOperationFailure? = null
        var deleted: ComplaintOwnerDeleteInput? = null
        var queried: ComplaintOwnerDeleteStatusQuery? = null
        var expectedContext: ComplaintOwnerOperationContext? = null
        val delete = ComplaintOwnerDeleteHttpHandler(ComplaintOwnerDeleteService(object : ComplaintOwnerDeletePort {
            override fun delete(context: ComplaintOwnerOperationContext, bearer: String, input: ComplaintOwnerDeleteInput): ComplaintOwnerDeleteReceipt {
                expectedContext?.let { assertSame(it, context) }
                assertEquals("synthetic", bearer)
                calls.add("delete")
                deleted = input
                return result()
            }
            override fun status(context: ComplaintOwnerOperationContext, bearer: String, query: ComplaintOwnerDeleteStatusQuery): ComplaintOwnerDeleteReceipt {
                expectedContext?.let { assertSame(it, context) }
                assertEquals("synthetic", bearer)
                calls.add("status")
                queried = query
                return result()
            }
            private fun result(): ComplaintOwnerDeleteReceipt {
                rejected?.let { throw ComplaintOwnerOperationRejected(it) }
                return receipt
            }
        }), ingress, responses)
        val createService = ComplaintOwnerCreateService(object : ComplaintOwnerOperationPort {
            override fun create(context: ComplaintOwnerOperationContext, bearer: String, input: ComplaintOwnerCreateInput): ComplaintOwnerReceipt = error("Delete cannot create")
            override fun status(context: ComplaintOwnerOperationContext, bearer: String, query: ComplaintOwnerStatusQuery): ComplaintOwnerReceipt = error("Delete cannot enter creation status")
        })
        val create = ComplaintOwnerCreateHttpHandler(createService, ingress, responses, deleteStatus = delete)

        fun send(request: MockHttpServletRequest): MockHttpServletResponse = MockHttpServletResponse().also { response ->
            bridge.doFilter(request, response) { admitted, outgoing ->
                RequestBodySizeLimitFilter(ObjectMapper()).doFilter(admitted, outgoing) { buffered, selected ->
                    val actual = buffered as HttpServletRequest
                    val context = bridge.claimHandler(actual)
                    expectedContext = context
                    if (actual.requestURI.removePrefix(actual.contextPath) == STATUS) create.handleWithinIngress(actual, selected as HttpServletResponse, context)
                    else delete.handleWithinIngress(actual, selected as HttpServletResponse, context)
                }
            }
        }
    }

    private companion object { const val STATUS = ComplaintOwnerCreateHttpHandler.STATUS }
}
