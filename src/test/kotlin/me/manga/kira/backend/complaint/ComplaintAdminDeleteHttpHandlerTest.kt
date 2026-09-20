package me.manga.kira.backend.complaint

import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.ServletOutputStream
import jakarta.servlet.http.HttpServletRequestWrapper
import me.manga.kira.backend.complaint.api.ComplaintAdminDeleteHttpHandler
import me.manga.kira.backend.complaint.api.ComplaintAdminDeleteResponses
import me.manga.kira.backend.complaint.api.ComplaintOwnerHistoryResponses
import me.manga.kira.backend.complaint.application.ComplaintAdminDeleteService
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteFailure
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteInput
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeletePort
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteReceipt
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteRejected
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteRequestContext
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.security.adminDeleteTestIngress
import me.manga.kira.backend.security.adminDeleteTestRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletResponse
import java.io.PrintWriter
import java.util.UUID
import java.util.concurrent.CancellationException

/** Boundary tests only. The stub port is NOT used as evidence of authentication, authorization or erasure. */
class ComplaintAdminDeleteHttpHandlerTest {
    private val scope = ComplaintDataScope.of(UUID.fromString("b2222222-2222-4222-8222-222222222222"))
    private val target = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val grant = UUID.fromString("44444444-4444-4444-8444-444444444444")

    @Test
    fun `empty 204 removes all representation framing and never acquires any writer`() {
        val stub = Stub()
        val handler = handler(stub)
        for (media in listOf(null, "application/json", "application/json; charset=\"UTF-8\"")) {
            val input = request().apply { media?.let { addHeader("Content-Type", it) } }
            val output = object : MockHttpServletResponse() {
                override fun getOutputStream(): ServletOutputStream = error("No 204 stream")
                override fun getWriter(): PrintWriter = error("No 204 writer")
            }.apply {
                for (name in listOf("ETag", "Location", "Content-Type", "Content-Length", "Transfer-Encoding")) {
                    addHeader(name, if (name == "Content-Length") "1" else "stale")
                }
            }
            handler.handleRequest(input, output)
            assertEquals(204, output.status)
            assertEquals(0, output.contentAsByteArray.size)
            for (name in listOf("ETag", "Location", "Content-Type", "Content-Length", "Transfer-Encoding")) assertNull(output.getHeader(name))
            assertEquals("1", output.getHeader("X-Kira-Complaint-Contract"))
            assertEquals("no-store, no-transform", output.getHeader("Cache-Control"))
            assertEquals(grant.toString(), output.getHeader(ComplaintAdminDeleteHttpHandler.CONSUMED_GRANT_HEADER))
        }
        assertEquals(3, stub.calls)
    }

    @Test
    fun `a nonempty declared body is rejected before acquiring input and unframed body uses only one byte probe`() {
        val stub = Stub()
        val handler = handler(stub)
        val declared = object : HttpServletRequestWrapper(request().apply { addHeader("Content-Length", "2") }) {
            override fun getInputStream(): ServletInputStream = error("Positive length must be refused before stream acquisition")
        }
        val first = MockHttpServletResponse()
        handler.handleRequest(declared, first)
        assertEquals(400, first.status)
        var read = 0
        val unframed = object : HttpServletRequestWrapper(request()) {
            override fun getContentLengthLong(): Long = -1
            override fun getInputStream(): ServletInputStream = object : ServletInputStream() {
                override fun read(): Int { read++; return 32 }
                override fun isFinished(): Boolean = false
                override fun isReady(): Boolean = true
                override fun setReadListener(listener: ReadListener) = Unit
            }
        }
        val second = MockHttpServletResponse()
        handler.handleRequest(unframed, second)
        assertEquals(400, second.status); assertEquals(1, read); assertEquals(0, stub.calls)
    }

    @Test
    fun `precondition grammar preserves exact positive Long and oversized tags remain 412 above header ceiling`() {
        val stub = Stub()
        val handler = handler(stub)
        for (tag in listOf(null, "*", "W/\"complaint-$target-v1\"", "\"complaint-$target-v1\",\"complaint-$target-v2\"",
            "\"complaint-$target-v0\"", "\"complaint-$target-v01\"", "\"complaint-$target-v9223372036854775808\"",
            "\"complaint-${UUID.randomUUID()}-v1\"", "x".repeat(257), "x".repeat(16385))) {
            val input = request().apply { removeHeader("If-Match"); tag?.let { addHeader("If-Match", it) } }
            val response = MockHttpServletResponse()
            handler.handleRequest(input, response)
            assertEquals(if (tag == null) 428 else 412, response.status)
        }
        assertEquals(0, stub.calls)
        val exact = request().apply { removeHeader("If-Match"); addHeader("If-Match", "\"complaint-$target-v${Long.MAX_VALUE}\"") }
        handler.handleRequest(exact, MockHttpServletResponse())
        assertEquals(Long.MAX_VALUE, stub.input?.precondition?.version)
    }

    @Test
    fun `conflicting framing duplicate sensitive headers and nonidentity encodings never reach the port`() {
        val stub = Stub()
        val handler = handler(stub)
        val invalid = listOf(
            request().apply { addHeader("Content-Length", "0"); addHeader("Transfer-Encoding", "chunked") } to 400,
            request().apply { addHeader("Authorization", "Bearer other") } to 400,
            request().apply { addHeader("Content-Encoding", "gzip") } to 415,
            request().apply { addHeader("Content-Type", "text/plain") } to 415,
            request().apply { addHeader("If-None-Match", "*") } to 400,
            request().apply { queryString = "dataScopeId=${scope.id}&extra=1" } to 400,
            request().apply { requestURI += "/" } to 404,
            request().apply { addHeader("If-Match", "\"complaint-$target-v1\"") } to 412,
        )
        for ((input, status) in invalid) {
            val response = MockHttpServletResponse()
            handler.handleRequest(input, response)
            assertEquals(status, response.status)
        }
        assertEquals(0, stub.calls)
        handler.handleRequest(request().apply { addHeader("Transfer-Encoding", "chunked") }, MockHttpServletResponse())
        assertEquals(1, stub.calls)
    }

    @Test
    fun `known authorization 503 exposes original association but neither success nor unconfirmed consumption`() {
        val stub = Stub()
        val handler = handler(stub)
        for (known in listOf(true, false)) {
            stub.failure = ComplaintAdminDeleteRejected(ComplaintAdminDeleteFailure.UNAVAILABLE, if (known) grant else null)
            val response = MockHttpServletResponse()
            handler.handleRequest(request(), response)
            assertEquals(503, response.status)
            assertEquals(if (known) "true" else null, response.getHeader(ComplaintAdminDeleteHttpHandler.CONSUMED_HEADER))
            assertEquals(if (known) grant.toString() else null, response.getHeader(ComplaintAdminDeleteHttpHandler.CONSUMED_GRANT_HEADER))
            assertFalse(response.contentAsString.contains(grant.toString()))
            assertTrue(response.contentAsByteArray.size <= 32 * 1024)
        }
    }

    @Test
    fun `delete uses the existing aggregate eight response owner rather than a parallel allowance`() {
        val stub = Stub()
        val owner = ComplaintOwnerHistoryResponses()
        val handler = handler(stub, owner)
        val permits = (1..8).map { checkNotNull(owner.acquire()) }
        try {
            val response = MockHttpServletResponse()
            handler.handleRequest(request(), response)
            assertEquals(503, response.status); assertEquals(0, stub.calls); assertNull(owner.acquire())
        } finally { permits.forEach { it.close() } }
        val response = MockHttpServletResponse()
        handler.handleRequest(request(), response)
        assertEquals(204, response.status)
        val all = (1..8).map { checkNotNull(owner.acquire()) }
        all.forEach { it.close() }
    }

    @Test
    fun `cancellation propagates without becoming a problem and releases the shared response slot`() {
        val stub = Stub().apply { failure = CancellationException("synthetic cancellation") }
        val owner = ComplaintOwnerHistoryResponses()
        val handler = handler(stub, owner)
        val response = MockHttpServletResponse()
        assertThrows(CancellationException::class.java) { handler.handleRequest(request(), response) }
        assertEquals(0, response.contentAsByteArray.size)
        assertNull(response.getHeader(ComplaintAdminDeleteHttpHandler.CONSUMED_HEADER))
        val permits = (1..8).map { checkNotNull(owner.acquire()) }
        permits.forEach { it.close() }
    }

    private fun request() = adminDeleteTestRequest(scope, target)
    private fun handler(stub: Stub, owner: ComplaintOwnerHistoryResponses = ComplaintOwnerHistoryResponses()) =
        ComplaintAdminDeleteHttpHandler(ComplaintAdminDeleteService(stub), adminDeleteTestIngress(), ComplaintAdminDeleteResponses(owner))
    private inner class Stub : ComplaintAdminDeletePort {
        var calls = 0
        var input: ComplaintAdminDeleteInput? = null
        var failure: RuntimeException? = null
        override fun delete(context: ComplaintAdminDeleteRequestContext, bearer: String, proof: String?, input: ComplaintAdminDeleteInput): ComplaintAdminDeleteReceipt {
            calls++; this.input = input
            failure?.let { throw it }
            return ComplaintAdminDeleteReceipt.Applied(grant)
        }
    }
}
