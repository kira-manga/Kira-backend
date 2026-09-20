package me.manga.kira.backend.complaint.api

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletInputStream
import jakarta.servlet.ServletOutputStream
import jakarta.servlet.WriteListener
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import me.manga.kira.backend.common.web.DisabledComplaintRoutesFilter
import me.manga.kira.backend.complaint.application.ComplaintInstallationService
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintInstallationEnrollmentResponse
import me.manga.kira.backend.complaint.domain.ComplaintInstallationExchange
import me.manga.kira.backend.complaint.domain.ComplaintInstallationHttpFailure
import me.manga.kira.backend.complaint.domain.ComplaintInstallationHttpRejected
import me.manga.kira.backend.complaint.domain.ComplaintInstallationRequestContext
import me.manga.kira.backend.complaint.domain.ComplaintInstallationSessionResponse
import me.manga.kira.backend.complaint.domain.InstallationEnrollmentCandidate
import me.manga.kira.backend.complaint.domain.InstallationEnrollmentDisposition
import me.manga.kira.backend.complaint.domain.InstallationSessionCandidate
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import me.manga.kira.backend.security.ComplaintAdmissionRejected
import me.manga.kira.backend.security.ComplaintHttpIngressBridge
import me.manga.kira.backend.security.historyTestIngress
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
import java.util.Base64
import java.util.UUID

/** Real HTTP/parser/writer with a fake domain port only; actual issuance and PostgreSQL are the IT's responsibility. */
class ComplaintInstallationHttpTest {
    private val mapper = ObjectMapper()
    private val id = UUID.randomUUID()
    private val scope = ComplaintDataScope.of(UUID.randomUUID())
    private val secret = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })

    @Test
    fun `admitted installation entry shares the outer ingress for both body secret routes`() {
        for ((path, status) in listOf(ENROLLMENT to 201, SESSION to 200)) {
            val fixture = Fixture()
            val bridge = ComplaintHttpIngressBridge(fixture.ingress)
            val response = MockHttpServletResponse()
            bridge.doFilter(request(path), response) { admitted, target ->
                val http = admitted as HttpServletRequest
                fixture.handler.handleWithinIngress(http, target as HttpServletResponse, bridge.claimHandler(http))
            }
            assertEquals(status, response.status)
            assertEquals(1, fixture.calls.size)
            assertHeaders(response, "application/json")
        }
    }

    @Test
    fun `two POST responses have exactly seven fields with enrollment-only creation location`() {
        val fixture = Fixture()
        for ((path, disposition, expected) in listOf(
            Triple(ENROLLMENT, InstallationEnrollmentDisposition.CREATED, 201),
            Triple(ENROLLMENT, InstallationEnrollmentDisposition.EXACT_REPLAY, 200),
            Triple(SESSION, InstallationEnrollmentDisposition.CREATED, 200),
        )) {
            fixture.disposition = disposition
            val response = fixture.request(request(path))
            assertEquals(expected, response.status)
            assertHeaders(response, "application/json")
            assertEquals(if (expected == 201) "/api/v1/installations/me" else null, response.getHeader("Location"))
            val json = mapper.readTree(response.contentAsByteArray)
            assertEquals(
                setOf("installationId", "accessToken", "tokenType", "credentialVersion", "dataScopeId", "issuedAt", "expiresInSeconds"),
                json.fieldNames().asSequence().toSet(),
            )
            assertEquals(id.toString(), json["installationId"].asText())
            assertEquals(scope.id.toString(), json["dataScopeId"].asText())
            assertEquals("Bearer", json["tokenType"].asText())
            assertEquals("ZmFrZQ.c2Vzc2lvbg.dG9rZW4", json["accessToken"].asText())
            assertEquals("2026-09-17T01:00:00Z", json["issuedAt"].asText())
            assertEquals(900L, json["expiresInSeconds"].asLong())
            assertEquals(1L, json["credentialVersion"].asLong())
            assertFalse(response.contentAsString.contains(secret))
        }
        assertEquals(listOf("enroll", "enroll", "session"), fixture.calls)
    }

    @Test
    fun `existing streamed body filter runs inside ingress and accepts the exact request cap`() {
        val fixture = Fixture()
        var reads = 0
        val request = object : MockHttpServletRequest("POST", ENROLLMENT) {
            override fun getInputStream(): ServletInputStream {
                reads += 1
                assertThrows<ComplaintAdmissionRejected> { fixture.ingress.withIngress(this) { error("Nested ingress must not start") } }
                return super.getInputStream()
            }
        }.apply {
            remoteAddr = "192.0.2.1"
            contentType = "application/json"
            setContent(body(ENROLLMENT).toString(Charsets.UTF_8).padEnd(4096).toByteArray())
        }
        assertEquals(201, fixture.request(request).status)
        assertEquals(1, reads) // The parser reads the filter's replay buffer, not the network stream twice.
        assertEquals(listOf("enroll"), fixture.calls)
    }

    @Test
    fun `selected framing media and strict body errors reach neither exchange nor token output`() {
        val cases = listOf(
            request(ENROLLMENT, ByteArray(4097) { 32 }) to 413,
            request(SESSION).apply { addHeader("Content-Encoding", "gzip") } to 415,
            request(SESSION).apply {
                addHeader("Content-Length", "1")
                addHeader("Transfer-Encoding", "chunked")
            } to 400,
            request(
                ENROLLMENT,
                body(ENROLLMENT).toString(Charsets.UTF_8).replace("\"platform\":\"ANDROID\"", "\"platform\":\"ANDROID\",\"platform\":\"IOS\"").toByteArray(),
            ) to
                400,
            request(SESSION, byteArrayOf(0xc3.toByte(), 0x28)) to 400,
            request(SESSION).apply {
                addHeader("Authorization", "one")
                addHeader("Authorization", "two")
            } to 400,
            request(SESSION).apply { addHeader("Authorization", "A".repeat(4097)) } to 400,
            request(SESSION).apply {
                addHeader("X-Kira-Complaint-Contract", "1")
                addHeader("X-Kira-Complaint-Contract", "1")
            } to 400,
            request(SESSION).apply { queryString = "installationId=private" } to 400,
        )
        for ((request, status) in cases) {
            val fixture = Fixture()
            val response = fixture.request(request)
            assertEquals(status, response.status)
            assertHeaders(response, "application/problem+json")
            assertTrue(fixture.calls.isEmpty())
            assertFalse(response.contentAsString.contains("accessToken"))
        }
    }

    @Test
    fun `closed problems preserve the real ApiError shape and body-secret statuses without bearer challenge`() {
        for (kind in ComplaintInstallationHttpFailure.entries) {
            val fixture = Fixture().apply { failure = kind }
            val response = fixture.request(request(SESSION))
            assertEquals(kind.status, response.status)
            assertHeaders(response, "application/problem+json")
            val json = mapper.readTree(response.contentAsByteArray)
            assertEquals(setOf("type", "title", "status", "errors"), json.fieldNames().asSequence().toSet())
            assertEquals("about:blank", json["type"].asText())
            assertEquals(1, json["errors"].size())
            assertEquals(kind.code, json["errors"][0]["code"].asText())
            assertEquals(if (kind == ComplaintInstallationHttpFailure.RATE_LIMITED) "17" else null, response.getHeader("Retry-After"))
            assertNull(response.getHeader("WWW-Authenticate"))
            assertNull(response.getHeader("Location"))
            assertFalse(response.contentAsString.contains(secret))
        }
    }

    @Test
    fun `partial send is not replaced by a problem or retried and releases ingress for the next request`() {
        for (unexpected in listOf(false, true)) {
            val fixture = Fixture()
            val prefix = ByteArrayOutputStream()
            val response = object : MockHttpServletResponse() {
                override fun getOutputStream(): ServletOutputStream = object : ServletOutputStream() {
                    override fun isReady(): Boolean = true
                    override fun setWriteListener(listener: WriteListener) = Unit
                    override fun write(value: Int) {
                        prefix.write(value)
                        if (prefix.size() == 24) {
                            if (unexpected) error("Synthetic private failure")
                            throw IOException("Synthetic private failure")
                        }
                    }
                }
            }
            val failure = assertThrows<IOException> { fixture.handler.handleRequest(request(SESSION), response) }
            assertEquals("Installation HTTP exchange failed.", failure.message)
            assertNull(failure.cause)
            assertEquals(24, prefix.size())
            assertEquals(200, response.status)
            assertEquals(listOf("session"), fixture.calls)
            assertEquals(if (unexpected) 503 else 200, fixture.request(request(SESSION)).status)
        }
    }

    @Test
    fun `registered closed boundary still prevents producer allocation and no bootstrap or me producer exists`() {
        var allocated = false
        val response = MockHttpServletResponse()
        DisabledComplaintRoutesFilter().doFilter(request(ENROLLMENT), response, FilterChain { _, _ -> allocated = true })
        assertEquals(404, response.status)
        assertFalse(allocated)
        val fixture = Fixture()
        for (path in listOf("/api/v1/installations/bootstrap", "/api/v1/installations/me", "/api/v1/installations/delete-all")) {
            assertEquals(404, fixture.request(request(path)).status)
        }
        assertTrue(fixture.calls.isEmpty())
    }

    private fun request(path: String, bytes: ByteArray = body(path)): MockHttpServletRequest = MockHttpServletRequest("POST", path).apply {
        remoteAddr = "192.0.2.1"
        contentType = "application/json"
        setContent(bytes)
    }

    private fun body(path: String): ByteArray = mapper.writeValueAsBytes(
        linkedMapOf<String, String>().apply {
            put("installationId", id.toString())
            put("secret", secret)
            put("expectedDataScopeId", scope.id.toString())
            if (path == ENROLLMENT) put("platform", "ANDROID")
        },
    )

    private fun assertHeaders(response: MockHttpServletResponse, media: String) {
        assertEquals(listOf("1"), response.getHeaders("X-Kira-Complaint-Contract").toList())
        assertEquals(listOf("no-store, no-transform"), response.getHeaders("Cache-Control").toList())
        assertEquals("$media;charset=UTF-8", response.contentType)
        assertTrue(response.contentLength in 1..ComplaintInstallationHttpResponses.MAX_BYTES)
        assertEquals(response.contentAsByteArray.size, response.contentLength)
        assertNull(response.getHeader("ETag"))
        assertNull(response.getHeader("Content-Encoding"))
        assertNull(response.getHeader("Transfer-Encoding"))
    }

    private class Fixture {
        val ingress = historyTestIngress()
        val calls = mutableListOf<String>()
        var disposition = InstallationEnrollmentDisposition.CREATED
        var failure: ComplaintInstallationHttpFailure? = null
        private val exchange = object : ComplaintInstallationExchange {
            override fun enroll(
                context: ComplaintInstallationRequestContext,
                candidate: InstallationEnrollmentCandidate,
            ): ComplaintInstallationEnrollmentResponse {
                calls.add("enroll")
                return ComplaintInstallationEnrollmentResponse(disposition, result(candidate.installation))
            }

            override fun session(context: ComplaintInstallationRequestContext, candidate: InstallationSessionCandidate): ComplaintInstallationSessionResponse {
                calls.add("session")
                return result(candidate.installation)
            }
        }
        val handler = ComplaintInstallationHttpHandler(ComplaintInstallationService(exchange), ingress)

        fun request(request: MockHttpServletRequest): MockHttpServletResponse = MockHttpServletResponse().also { handler.handleRequest(request, it) }

        private fun result(installation: ScopedInstallationId): ComplaintInstallationSessionResponse {
            failure?.let { throw ComplaintInstallationHttpRejected(it, if (it == ComplaintInstallationHttpFailure.RATE_LIMITED) 17 else null) }
            return ComplaintInstallationSessionResponse(installation, 1, "ZmFrZQ.c2Vzc2lvbg.dG9rZW4", Instant.parse("2026-09-17T01:00:00Z"))
        }
    }

    private companion object {
        const val ENROLLMENT = "/api/v1/installations"
        const val SESSION = "/api/v1/installations/session"
    }
}
