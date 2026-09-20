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
import me.manga.kira.backend.complaint.application.ComplaintAdminBatchStatusService
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchStatusAcknowledgement
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchStatusInput
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchStatusPort
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchStatusReceipt
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchStatusRequestContext
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusFailure
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusRejected
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusRejection
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.security.ComplaintIngressContext
import me.manga.kira.backend.security.adminBatchStatusTestIngress
import me.manga.kira.backend.security.adminBatchStatusTestRequest
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

/** Supplied-data transport only, NOT proof of real auth/commit. The connected IT uses the actual issuer/JWT/producer. */
class ComplaintAdminBatchStatusHttpTest {
    private val mapper = ObjectMapper()
    private val scope = ComplaintDataScope.of(UUID.fromString("11111111-1111-4111-8111-111111111111"))
    private val id = UUID.fromString("123e4567-e89b-52d3-a456-426614174000")
    private val key = UUID.fromString("33333333-3333-4333-8333-333333333333")

    @Test
    fun exactPostRouteRejectsAggregateTagsFramingMediaAndDuplicateSecurityHeadersBeforeThePort() {
        for ((request, expected) in listOf(
            input().apply { addHeader("If-Match", "\"complaint-$id-v7\"") } to 400,
            input().apply { addHeader("If-None-Match", "*") } to 400,
            input().apply { addHeader("Authorization", "Bearer other") } to 400,
            input().apply { removeHeader("Authorization") } to 401,
            input().replace("Authorization", "Bearer " + "x".repeat(4097)) to 400,
            input().apply { addHeader("X-Kira-Admin-Step-Up", "other") } to 400,
            input().replace("X-Kira-Admin-Step-Up", "x".repeat(129)) to 400,
            input().replace("X-Kira-Admin-Step-Up", "private\tproof") to 400,
            input().apply { addHeader("X-Kira-Complaint-Contract", "1") } to 400,
            input().replace("X-Kira-Complaint-Contract", "2") to 400,
            input().replace("X-Kira-Idempotency-Key", "not-canonical") to 400,
            input().apply { addHeader("X-Kira-Idempotency-Key", key.toString()) } to 400,
            input().apply { method = "PATCH" } to 404,
            input().apply { requestURI += "/" } to 404,
            input().apply { requestURI = "/api/v1/admin/complaints/%62atch" } to 404,
            input().apply { queryString = "dataScopeId=${scope.id}&action=STATUS" } to 400,
            input().apply { queryString = "dataScopeId=${ComplaintDataScope.LIVE.id}" } to 400,
            input().apply { queryString = "dataScopeId=11111111-1111-5111-8111-111111111111" } to 400,
            input().apply { contentType = "text/plain" } to 415,
            input().apply { addHeader("Content-Encoding", "gzip") } to 415,
            input().apply { addHeader("Transfer-Encoding", "chunked") } to 400,
            input().replace("Content-Length", "-1") to 400,
            input().replace("Content-Length", "1") to 400,
            input().apply { addHeader("Content-Length", "1") } to 400,
            input().apply { addHeader(" padded-name", "private") } to 400,
            input().apply { repeat(65) { addHeader("Extra-$it", "x") } } to 400,
            input().apply { addHeader("Extra", "x".repeat(16 * 1024)) } to 400,
            input().apply { dispatcherType = DispatcherType.ASYNC } to 503,
            input("""{"action":"STATUS","status":"RESOLVED","targets":[{"id":"$id"}]}""") to 428,
            input("""{"action":"STATUS","status":"RESOLVED","targets":[{"id":"$id","actionTag":null}]}""") to 400,
            input("""{"action":"STATUS","status":"RESOLVED","targets":[{"id":"$id","actionTag":"*"}]}""") to 412,
        )) {
            val f = Fixture()
            val response = f.send(request)
            assertEquals(expected, response.status)
            assertEquals(0, f.calls)
            envelope(response, false)
            assertTrue(response.contentAsByteArray.size <= 512)
        }
        val f = Fixture()
        assertEquals(200, f.send(input()).status)
        assertEquals(id, checkNotNull(f.parsed).targets.single().id)
        assertEquals(7L, checkNotNull(f.parsed).targets.single().precondition.version)
        val contextual = input().apply { contextPath = "/test"; requestURI = "/test/api/v1/admin/complaints/batch" }
        assertEquals(200, f.send(contextual).status)
    }

    @Test
    fun declaredChunkedAndUnknownLengthBodiesUseExactlyThe32KiBCapAndEraseTheOwnedInput() {
        for (framing in listOf("declared", "chunked", "unknown")) for (size in listOf(32768, 32769)) {
            val raw = body().toByteArray()
            val bytes = raw + ByteArray(size - raw.size) { 32 }
            var acquisitions = 0
            var read = 0
            var captured: ByteArray? = null
            val template = input().apply {
                removeHeader("Content-Length")
                if (framing == "declared") addHeader("Content-Length", size.toString())
                if (framing == "chunked") addHeader("Transfer-Encoding", "chunked")
            }
            val request = object : HttpServletRequestWrapper(template) {
                override fun getContentLengthLong(): Long = -1
                override fun getInputStream(): ServletInputStream {
                    acquisitions++
                    check(framing != "declared" || size <= 32768)
                    return object : ServletInputStream() {
                        override fun isFinished(): Boolean = read == size
                        override fun isReady(): Boolean = true
                        override fun setReadListener(listener: ReadListener) = Unit
                        override fun read(): Int = if (read == size) -1 else bytes[read++].toInt() and 255
                        override fun readNBytes(length: Int): ByteArray {
                            assertEquals(32769, length)
                            return super.readNBytes(length).also { captured = it }
                        }
                    }
                }
            }
            val f = Fixture()
            assertEquals(if (size == 32768) 200 else 413, f.send(request).status)
            assertEquals(if (framing == "declared" && size == 32769) 0 else 1, acquisitions)
            assertEquals(if (acquisitions == 0) 0 else size, read)
            assertEquals(if (size == 32768) 1 else 0, f.calls)
            captured?.let { assertTrue(it.all { byte -> byte == 0.toByte() }) }
        }
    }

    @Test
    fun completeSortedNumericLongAckAndHistoricalGrantUseTheOriginalSharedEightThroughDelivery() {
        val grant = UUID.fromString("44444444-4444-4444-8444-444444444444")
        for (version in listOf(9_007_199_254_740_993L, Long.MAX_VALUE)) {
            val ids = (1..50).map { UUID(0, it.toLong()) }
            val f = Fixture().apply { receipt = ComplaintAdminBatchStatusReceipt.Applied(ids.map { ComplaintAdminBatchStatusAcknowledgement(it, version) }, grant) }
            val response = f.send(input(body(ids.reversed(), version - 1)))
            val tree = mapper.readTree(response.contentAsByteArray)
            assertEquals(setOf("items"), tree.fieldNames().asSequence().toSet())
            assertEquals(ids.map(UUID::toString), tree["items"].map { it["id"].asText() })
            assertTrue(tree["items"].all { it["version"].isIntegralNumber && it["version"].longValue() == version && it.fieldNames().asSequence().toSet() == setOf("id", "version") })
            assertEquals(50, tree["items"].size())
            envelope(response, true, grant)
        }
        for (code in ComplaintAdminStatusRejection.entries) {
            val f = Fixture().apply { receipt = ComplaintAdminBatchStatusReceipt.Rejected(code, grant) }
            val response = f.send(input().apply { addHeader("X-Kira-Admin-Step-Up-Grant-Id", UUID.randomUUID().toString()) })
            assertEquals(code.status, response.status)
            assertEquals(code.name, mapper.readTree(response.contentAsByteArray)["errors"][0]["code"].asText())
            envelope(response, true, grant)
            assertFalse(response.contentAsString.contains(id.toString()))
        }
        for (failure in ComplaintAdminStatusFailure.entries) {
            val response = Fixture().apply { this.failure = ComplaintAdminStatusRejected(failure) }.send(input())
            assertEquals(failure.status, response.status)
            envelope(response, false)
            assertEquals(if (failure.status == 401) "Bearer realm=\"kira-complaints\"" else null, response.getHeader("WWW-Authenticate"))
            assertEquals(if (failure == ComplaintAdminStatusFailure.IN_PROGRESS) "1" else null, response.getHeader("Retry-After"))
        }
        for (problem in listOf(false, true)) {
            val f = Fixture().apply { if (problem) failure = ComplaintAdminStatusRejected(ComplaintAdminStatusFailure.NOT_FOUND) }
            val readResponses = ComplaintAdminReadResponses(f.owner)
            val held = List(4) { checkNotNull(f.owner.acquire()) } + List(4) { checkNotNull(readResponses.acquire()) }
            try {
                val unread = object : HttpServletRequestWrapper(input()) {
                    override fun getInputStream(): ServletInputStream = error("No ninth response slot may buffer a batch.")
                }
                assertEquals(503, f.send(unread).status)
                assertEquals(0, f.calls)
            } finally { held.forEach { it.close() } }
            var captured: ByteArray? = null
            val response = object : MockHttpServletResponse() {
                override fun getOutputStream(): ServletOutputStream {
                    val delegate = super.getOutputStream()
                    return object : ServletOutputStream() {
                        override fun isReady(): Boolean = true
                        override fun setWriteListener(listener: WriteListener) = Unit
                        override fun write(value: Int) = delegate.write(value)
                        override fun write(bytes: ByteArray, offset: Int, count: Int) {
                            requireConnectionFree()
                            captured = bytes
                            val others = List(7) { checkNotNull(readResponses.acquire()) }
                            try {
                                assertNull(f.responses.acquire())
                                delegate.write(bytes, offset, count)
                            } finally { others.forEach { it.close() } }
                        }
                    }
                }
            }
            f.handler.handleRequest(input(), response)
            assertEquals(if (problem) 404 else 200, response.status)
            if (!problem) assertTrue(checkNotNull(captured).all { it == 0.toByte() })
            List(8) { checkNotNull(f.owner.acquire()) }.forEach { it.close() }
        }
    }

    @Test
    fun defaultCompositionRemainsClosedAndAmbiguousDeliveryNeverAppendsOrInventoriesThePresentedProof() {
        val disabled = MockHttpServletResponse()
        DisabledComplaintRoutesFilter().doFilter(object : HttpServletRequestWrapper(input()) {
            override fun getInputStream(): ServletInputStream = error("Dormant batch must stay unread.")
        }, disabled, FilterChain { _, _ -> error("No batch activation is supplied by these sources.") })
        assertEquals(404, disabled.status)
        assertNull(disabled.getHeader(ComplaintAdminBatchStatusHttpHandler.CONSUMED_HEADER))
        for (fault in listOf(IOException("private"), IllegalStateException("private"), OutOfMemoryError("private"), InterruptedIOException("private"))) {
            val f = Fixture()
            val prefix = ByteArrayOutputStream()
            var writes = 0
            var retained: ByteArray? = null
            val response = object : MockHttpServletResponse() {
                override fun getOutputStream(): ServletOutputStream = object : ServletOutputStream() {
                    override fun isReady(): Boolean = true
                    override fun setWriteListener(listener: WriteListener) = Unit
                    override fun write(value: Int): Unit = error("Buffered sender expected")
                    override fun write(bytes: ByteArray, offset: Int, count: Int) {
                        writes++
                        retained = bytes
                        prefix.write(bytes, offset, minOf(12, count))
                        throw fault
                    }
                }
            }
            try {
                val error = assertThrows<IOException> { f.handler.handleRequest(input(), response) }
                assertEquals("Complaint response delivery failed.", error.message)
                assertNull(error.cause)
                assertEquals(1, writes)
                assertEquals(12, prefix.size())
                assertTrue(checkNotNull(retained).all { it == 0.toByte() })
                assertEquals(fault is InterruptedIOException, Thread.currentThread().isInterrupted)
                assertEquals("true", response.getHeader(ComplaintAdminBatchStatusHttpHandler.CONSUMED_HEADER))
            } finally { Thread.interrupted() }
        }
    }

    private fun body(ids: List<UUID> = listOf(id), version: Long = 7): String = mapper.writeValueAsString(linkedMapOf(
        "action" to "STATUS", "status" to "RESOLVED", "targets" to ids.map { linkedMapOf("id" to it.toString(), "actionTag" to "\"complaint-$it-v$version\"") },
    ))
    private fun input(raw: String = body()): MockHttpServletRequest = adminBatchStatusTestRequest(scope, key, "synthetic-token", "private-proof", raw)
    private fun MockHttpServletRequest.replace(name: String, value: String): MockHttpServletRequest = apply { removeHeader(name); addHeader(name, value) }

    private fun envelope(response: MockHttpServletResponse, consumed: Boolean, grant: UUID? = null) {
        assertEquals("1", response.getHeader("X-Kira-Complaint-Contract"))
        assertEquals("no-store, no-transform", response.getHeader("Cache-Control"))
        assertEquals(response.contentAsByteArray.size.toString(), response.getHeader("Content-Length"))
        assertTrue(response.contentAsByteArray.size <= 32768)
        assertEquals(if (consumed) listOf("true") else emptyList<String>(), response.getHeaders(ComplaintAdminBatchStatusHttpHandler.CONSUMED_HEADER).toList())
        assertEquals(grant?.let { listOf(it.toString()) } ?: emptyList<String>(), response.getHeaders(ComplaintAdminBatchStatusHttpHandler.CONSUMED_GRANT_HEADER).toList())
        assertNull(response.getHeader("ETag"))
        assertNull(response.getHeader("Location"))
        for (privateValue in listOf("private-proof", "synthetic-token", key.toString(), scope.id.toString(), grant?.toString()).filterNotNull()) {
            assertFalse(response.contentAsString.contains(privateValue))
        }
    }

    private inner class Fixture {
        val ingress = adminBatchStatusTestIngress()
        val owner = ComplaintOwnerHistoryResponses()
        val responses = ComplaintAdminBatchStatusResponses(owner)
        var calls = 0
        var parsed: ComplaintAdminBatchStatusInput? = null
        var receipt: ComplaintAdminBatchStatusReceipt = ComplaintAdminBatchStatusReceipt.Applied(listOf(ComplaintAdminBatchStatusAcknowledgement(id, 8)))
        var failure: ComplaintAdminStatusRejected? = null
        val handler = ComplaintAdminBatchStatusHttpHandler(ComplaintAdminBatchStatusService(object : ComplaintAdminBatchStatusPort {
            override fun change(context: ComplaintAdminBatchStatusRequestContext, bearer: String, proof: String?, input: ComplaintAdminBatchStatusInput): ComplaintAdminBatchStatusReceipt {
                requireConnectionFree()
                ingress.requireLiveContext(context as ComplaintIngressContext)
                assertEquals("synthetic-token", bearer)
                assertEquals("private-proof", proof)
                calls++
                parsed = input
                failure?.let { throw it }
                return receipt
            }
        }), ingress, responses)

        fun send(request: HttpServletRequest): MockHttpServletResponse = MockHttpServletResponse().also { handler.handleRequest(request, it) }
    }
}
