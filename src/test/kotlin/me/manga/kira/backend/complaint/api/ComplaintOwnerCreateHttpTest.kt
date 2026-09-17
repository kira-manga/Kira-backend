package me.manga.kira.backend.complaint.api

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletInputStream
import jakarta.servlet.ServletOutputStream
import jakarta.servlet.WriteListener
import me.manga.kira.backend.common.web.DisabledComplaintRoutesFilter
import me.manga.kira.backend.complaint.application.ComplaintOwnerCreateService
import me.manga.kira.backend.complaint.domain.ComplaintOwnerCreateInput
import me.manga.kira.backend.complaint.domain.ComplaintOwnerCreateRejection
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationContext
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationFailure
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationPort
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationRejected
import me.manga.kira.backend.complaint.domain.ComplaintOwnerReceipt
import me.manga.kira.backend.complaint.domain.ComplaintOwnerStatusQuery
import me.manga.kira.backend.security.ComplaintAdmissionRejected
import me.manga.kira.backend.security.ownerCreateTestIngress
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
import java.util.UUID

/** Actual strict parser and bounded sender, fake domain port. Real issued HTTP token/SQL integration belongs to the IT. */
class ComplaintOwnerCreateHttpTest {
    private val mapper = ObjectMapper()
    private val id = UUID.randomUUID()
    private val key = UUID.randomUUID()
    private val createBody = """{"id":"$id","type":"TECHNICAL","subject":"  Synthetic subject  ","body":"Synthetic body","metadata":{"appVersion":null,""" +
        """"osVersion":"","manufacturer":"","deviceModel":""}}"""
    private val statusBody = """{"operation":"OWNER_CREATE","key":"$key","targetIds":["$id"],"fingerprint":"${"A".repeat(43)}"}"""

    @Test
    fun `direct acknowledgement and status are exact closed scalar unions with original headers only on direct creation`() {
        val f = Fixture()
        val direct = f.request(input())
        assertEquals(201, direct.status)
        assertEquals("""{"id":"$id","version":1}""", direct.contentAsString)
        assertEquals("/api/v1/complaints/$id", direct.getHeader("Location"))
        assertEquals("\"complaint-$id-v1\"", direct.getHeader("ETag"))
        assertHeaders(direct)
        val status = f.request(input(STATUS))
        assertEquals(200, status.status)
        assertEquals(
            """{"outcome":"APPLIED","originalStatus":201,"location":"/api/v1/complaints/$id","etag":"\"complaint-$id-v1\"","body":{"id":"$id","version":1}}""",
            status.contentAsString,
        )
        assertNull(status.getHeader("Location"))
        assertNull(status.getHeader("ETag"))
        assertHeaders(status)
        assertEquals(listOf("create", "status"), f.calls)
        assertEquals("  Synthetic subject  ", checkNotNull(f.created).subject, "Structural parsing must not supply its own normalization.")
        assertNull(checkNotNull(f.created).metadata.appVersion)
    }

    @Test
    fun `both terminal create rejections have exact direct problems and content-free status outcomes`() {
        for (code in ComplaintOwnerCreateRejection.entries) {
            val f = Fixture().apply { receipt = ComplaintOwnerReceipt.Rejected(code) }
            val direct = f.request(input())
            assertEquals(409, direct.status)
            assertEquals(code.name, mapper.readTree(direct.contentAsByteArray)["errors"][0]["code"].asText())
            assertTrue(checkNotNull(direct.contentType).startsWith("application/problem+json"))
            val status = f.request(input(STATUS))
            assertEquals("""{"outcome":"REJECTED","originalStatus":409,"problemCode":"${code.name}"}""", status.contentAsString)
            assertEquals(200, status.status)
            for (response in listOf(direct, status)) {
                assertHeaders(response)
                assertNull(response.getHeader("Location"))
                assertNull(response.getHeader("ETag"))
                assertFalse(response.contentAsString.contains(id.toString()))
            }
        }
    }

    @Test
    fun `closed duplicate and wrongly typed JSON cannot reach the domain port`() {
        val invalidCreates = listOf(
            createBody.replace("\"appVersion\":null,", ""),
            createBody.replace("\"osVersion\":\"\"", "\"osVersion\":null"),
            createBody.replace("\"appVersion\":null", "\"appVersion\":3"),
            createBody.replace("\"appVersion\":null", "\"appVersion\":null,\"appVersion\":null"),
            createBody.replace("\"deviceModel\":\"\"", "\"deviceModel\":\"\",\"platform\":\"ANDROID\""),
            createBody.replace("\"type\":\"TECHNICAL\"", "\"type\":\"UNKNOWN\""),
            createBody.replace("\"subject\":", "\"platform\":\"ANDROID\",\"subject\":"),
            createBody.replace("\"body\":\"Synthetic body\"", "\"body\":{\"nested\":{}}"),
            "$createBody {}",
        )
        val invalidStatuses = listOf(
            statusBody.replace("OWNER_CREATE", "CREATE_REPORT"),
            statusBody.replace("OWNER_CREATE", "OWNER_REPLY"),
            statusBody.replace("[\"$id\"]", "[]"),
            statusBody.replace("[\"$id\"]", "[\"$id\",\"$id\"]"),
            statusBody.replace("\"fingerprint\":", "\"subject\":\"private\",\"fingerprint\":"),
            statusBody.replace("A".repeat(43), "A".repeat(42) + "B"),
        )
        val cases = invalidCreates.map { input(body = it.toByteArray()) } + invalidStatuses.map { input(STATUS, it.toByteArray()) } +
            input(body = byteArrayOf(0xc3.toByte(), 0x28))
        for (request in cases) {
            val f = Fixture()
            val response = f.request(request)
            assertEquals(400, response.status)
            assertTrue(f.calls.isEmpty())
            assertFalse(response.contentAsString.contains("Synthetic"))
        }
    }

    @Test
    fun `ingress surrounds bounded streamed parsing and framing headers routes and media are enforced before calls`() {
        val f = Fixture()
        var streams = 0
        val exact = object : MockHttpServletRequest("POST", CREATE) {
            override fun getInputStream(): ServletInputStream {
                streams += 1
                assertThrows<ComplaintAdmissionRejected> { f.ingress.withIngress(this) { error("Nested ingress") } }
                return super.getInputStream()
            }
        }.apply {
            remoteAddr = "192.0.2.1"
            contentType = "application/json"
            addHeader("Authorization", "Bearer synthetic")
            addHeader("X-Kira-Idempotency-Key", key.toString())
            setContent(createBody.padEnd(16 * 1024).toByteArray())
        }
        assertEquals(201, f.request(exact).status)
        assertEquals(1, streams)
        val cases = listOf(
            input(body = ByteArray(16 * 1024 + 1) { 32 }) to 413,
            input().apply {
                addHeader("Content-Length", "1")
                addHeader("Transfer-Encoding", "chunked")
            } to 400,
            input().apply { addHeader("Content-Length", "2") } to 400,
            input().apply { addHeader("Content-Encoding", "gzip") } to 415,
            input().apply { contentType = "application/json; unsupported=yes" } to 415,
            input().apply { addHeader("Authorization", "Bearer second") } to 400,
            input().apply { removeHeader("Authorization") } to 401,
            input().apply { removeHeader("X-Kira-Idempotency-Key") } to 400,
            input(STATUS).apply { addHeader("X-Kira-Idempotency-Key", key.toString()) } to 400,
            input().apply { addHeader("If-Match", "*") } to 400,
            input().apply { queryString = "key=private" } to 400,
            input().apply { method = "GET" } to 404,
            input().apply { requestURI = "$CREATE/" } to 404,
        )
        for ((request, expected) in cases) {
            val selected = Fixture()
            assertEquals(expected, selected.request(request).status)
            assertTrue(selected.calls.isEmpty())
        }
    }

    @Test
    fun `finite response slots and registered closed routes refuse before body or domain work`() {
        val f = Fixture()
        val permits = List(8) { checkNotNull(f.responses.acquire()) }
        try {
            assertEquals(503, f.request(input()).status)
            assertTrue(f.calls.isEmpty())
        } finally {
            permits.forEach(AutoCloseable::close)
        }
        assertEquals(201, f.request(input()).status)
        var reached = false
        val response = MockHttpServletResponse()
        DisabledComplaintRoutesFilter().doFilter(input(), response, FilterChain { _, _ -> reached = true })
        assertFalse(reached)
        assertEquals(404, response.status)
    }

    @Test
    fun `partial acknowledgement send never appends a problem and releases response and ingress custody`() {
        val f = Fixture()
        val prefix = ByteArrayOutputStream()
        val response = object : MockHttpServletResponse() {
            override fun getOutputStream(): ServletOutputStream = object : ServletOutputStream() {
                override fun isReady(): Boolean = true
                override fun setWriteListener(listener: WriteListener) = Unit
                override fun write(value: Int) {
                    prefix.write(value)
                    if (prefix.size() == 12) throw IOException("Synthetic private sender failure")
                }
            }
        }
        val failure = assertThrows<IOException> { f.handler.handleRequest(input(), response) }
        assertEquals("Complaint operation delivery failed.", failure.message)
        assertNull(failure.cause)
        assertEquals(12, prefix.size())
        assertEquals(201, response.status)
        assertEquals(201, f.request(input()).status)
    }

    @Test
    fun `nonterminal problems are closed and only actual claim wait has the one second retry`() {
        for (failure in ComplaintOwnerOperationFailure.entries) {
            val f = Fixture().apply { rejected = failure }
            val response = f.request(input())
            assertEquals(failure.status, response.status)
            val json = mapper.readTree(response.contentAsByteArray)
            assertEquals(setOf("type", "title", "status", "errors"), json.fieldNames().asSequence().toSet())
            assertEquals(failure.code, json["errors"][0]["code"].asText())
            assertEquals(if (failure == ComplaintOwnerOperationFailure.IN_PROGRESS) "1" else null, response.getHeader("Retry-After"))
            assertEquals(
                if (failure == ComplaintOwnerOperationFailure.UNAUTHORIZED) "Bearer realm=\"kira-complaints\"" else null,
                response.getHeader("WWW-Authenticate"),
            )
            assertFalse(response.contentAsString.contains(key.toString()))
            assertHeaders(response)
        }
    }

    private fun input(path: String = CREATE, body: ByteArray = (if (path == STATUS) statusBody else createBody).toByteArray()): MockHttpServletRequest =
        MockHttpServletRequest("POST", path).apply {
            remoteAddr = "192.0.2.1"
            contentType = "application/json"
            addHeader("Authorization", "Bearer synthetic")
            if (path == CREATE) addHeader("X-Kira-Idempotency-Key", key.toString())
            setContent(body)
        }

    private fun assertHeaders(response: MockHttpServletResponse) {
        assertEquals("1", response.getHeader("X-Kira-Complaint-Contract"))
        assertEquals("no-store, no-transform", response.getHeader("Cache-Control"))
        assertEquals(response.contentAsByteArray.size.toString(), response.getHeader("Content-Length"))
        assertTrue(response.contentAsByteArray.size <= 16 * 1024)
    }

    private inner class Fixture {
        val ingress = ownerCreateTestIngress()
        val responses = ComplaintOwnerOperationResponse()
        val calls = mutableListOf<String>()
        var created: ComplaintOwnerCreateInput? = null
        var receipt: ComplaintOwnerReceipt = ComplaintOwnerReceipt.Applied(id, 1)
        var rejected: ComplaintOwnerOperationFailure? = null
        private val port = object : ComplaintOwnerOperationPort {
            override fun create(context: ComplaintOwnerOperationContext, bearer: String, input: ComplaintOwnerCreateInput): ComplaintOwnerReceipt {
                calls.add("create")
                created = input
                return result()
            }

            override fun status(context: ComplaintOwnerOperationContext, bearer: String, query: ComplaintOwnerStatusQuery): ComplaintOwnerReceipt {
                calls.add("status")
                return result()
            }

            private fun result(): ComplaintOwnerReceipt {
                rejected?.let { throw ComplaintOwnerOperationRejected(it) }
                return receipt
            }
        }
        val handler = ComplaintOwnerCreateHttpHandler(ComplaintOwnerCreateService(port), ingress, responses)
        fun request(request: MockHttpServletRequest): MockHttpServletResponse = MockHttpServletResponse().also { handler.handleRequest(request, it) }
    }

    private companion object {
        const val CREATE = ComplaintOwnerCreateHttpHandler.CREATE
        const val STATUS = ComplaintOwnerCreateHttpHandler.STATUS
    }
}
