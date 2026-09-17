package me.manga.kira.backend.complaint.api

import jakarta.servlet.ServletInputStream
import jakarta.servlet.ServletOutputStream
import jakarta.servlet.WriteListener
import me.manga.kira.backend.complaint.application.ComplaintOwnerDeleteAllService
import me.manga.kira.backend.complaint.domain.ComplaintInstallationHttpFailure
import me.manga.kira.backend.complaint.domain.ComplaintInstallationRequestContext
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteAllExchange
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteAllResponse
import me.manga.kira.backend.complaint.domain.InstallationDeletionCandidate
import me.manga.kira.backend.complaint.domain.rejectInstallationHttp
import me.manga.kira.backend.security.ComplaintIngressContext
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
import java.io.InterruptedIOException
import java.util.Base64

/** Request/delivery boundaries only. This throwing port never fabricates a Completed/Pending result or deletion evidence. */
class ComplaintOwnerDeleteAllHttpHandlerTest {
    @Test
    fun `delete all header and query rejection precedes input acquisition and the exchange`() {
        val changes: List<(MockHttpServletRequest) -> Unit> = listOf(
            { it.removeHeader(KEY_HEADER) },
            { it.addHeader(KEY_HEADER, KEY) },
            {
                it.removeHeader(KEY_HEADER)
                it.addHeader(KEY_HEADER, KEY.uppercase())
            },
            {
                it.removeHeader(KEY_HEADER)
                it.addHeader(KEY_HEADER, " $KEY")
            },
            { it.queryString = "" },
            { it.queryString = "installationId=private" },
            {
                it.addHeader("Authorization", "one")
                it.addHeader("Authorization", "two")
            },
            { it.addHeader("Authorization", "A".repeat(4097)) },
            { it.addHeader("Authorization", "Bearer\nprivate") },
            {
                it.addHeader("X-Kira-Complaint-Contract", "1")
                it.addHeader("X-Kira-Complaint-Contract", "1")
            },
            { it.addHeader("X-Kira-Complaint-Contract", "2") },
        )
        for (change in changes) {
            val fixture = Fixture()
            var reads = 0
            val request = request(object : MockHttpServletRequest("POST", PATH) {
                override fun getInputStream(): ServletInputStream {
                    reads += 1
                    error("Rejected headers must precede input acquisition.")
                }
            })
            change(request)
            val response = MockHttpServletResponse()
            fixture.handler.handleRequest(request, response)
            assertProblem(response, 400)
            assertEquals(0, reads)
            assertEquals(0, fixture.calls)
        }
    }

    @Test
    fun `only ordinary input IO becomes malformed while interruption and exchange IO escape without a response`() {
        val input = Fixture()
        val inputRequest = request(object : MockHttpServletRequest("POST", PATH) {
            override fun getInputStream(): ServletInputStream = throw IOException("Synthetic private input failure.")
        })
        val inputResponse = MockHttpServletResponse()
        input.handler.handleRequest(inputRequest, inputResponse)
        assertProblem(inputResponse, 400)
        assertEquals(0, input.calls)

        val interrupted = Fixture()
        val interruptedRequest = request(object : MockHttpServletRequest("POST", PATH) {
            override fun getInputStream(): ServletInputStream = throw InterruptedIOException("Synthetic private input interruption.")
        })
        val interruptedResponse = MockHttpServletResponse()
        val signal = assertThrows<InterruptedIOException> { interrupted.handler.handleRequest(interruptedRequest, interruptedResponse) }
        assertEquals(DELIVERY_FAILURE, signal.message)
        assertNull(signal.cause)
        assertEquals(0, interrupted.calls)
        assertTrue(interruptedResponse.headerNames.isEmpty())
        assertTrue(interruptedResponse.contentAsByteArray.isEmpty())

        val exchange = Fixture { throw IOException("Synthetic private downstream failure.") }
        val exchangeResponse = MockHttpServletResponse()
        val failed = assertThrows<IOException> { exchange.handler.handleRequest(request(), exchangeResponse) }
        assertEquals(DELIVERY_FAILURE, failed.message)
        assertNull(failed.cause)
        assertEquals(1, exchange.calls)
        assertTrue(exchangeResponse.headerNames.isEmpty())
        assertTrue(exchangeResponse.contentAsByteArray.isEmpty())
    }

    @Test
    fun `partial problem delivery never writes a second response or repeats the exchange`() {
        for (unexpected in listOf(false, true)) {
            val fixture = Fixture()
            val prefix = ByteArrayOutputStream()
            var statuses = 0
            val response = object : MockHttpServletResponse() {
                override fun setStatus(status: Int) {
                    statuses += 1
                    super.setStatus(status)
                }

                override fun getOutputStream(): ServletOutputStream = object : ServletOutputStream() {
                    override fun isReady(): Boolean = true
                    override fun setWriteListener(listener: WriteListener) = Unit
                    override fun write(value: Int) {
                        prefix.write(value)
                        if (prefix.size() == 24) {
                            if (unexpected) error("Synthetic private output failure.")
                            throw IOException("Synthetic private output failure.")
                        }
                    }
                }
            }
            val failed = assertThrows<IOException> { fixture.handler.handleRequest(request(), response) }
            assertEquals(DELIVERY_FAILURE, failed.message)
            assertNull(failed.cause)
            assertEquals(503, response.status)
            assertEquals(1, statuses)
            assertEquals(24, prefix.size())
            assertEquals(1, fixture.calls)
            fixture.ingress.withIngress(request()) { fixture.ingress.requireLiveContext(it) }
        }
    }

    private fun request(request: MockHttpServletRequest = MockHttpServletRequest("POST", PATH)): MockHttpServletRequest = request.apply {
        remoteAddr = "192.0.2.1"
        contentType = "application/json"
        addHeader(KEY_HEADER, KEY)
        setContent(
            (
                "{\"installationId\":\"123e4567-e89b-42d3-a456-426614174000\",\"secret\":\"$SECRET\"," +
                    "\"credentialVersion\":1,\"dataScopeId\":\"00000000-0000-0000-0000-000000000000\"}"
                ).toByteArray(),
        )
    }

    private fun assertProblem(response: MockHttpServletResponse, status: Int) {
        assertEquals(status, response.status)
        assertEquals("1", response.getHeader("X-Kira-Complaint-Contract"))
        assertEquals("no-store, no-transform", response.getHeader("Cache-Control"))
        assertEquals("application/problem+json;charset=UTF-8", response.contentType)
        assertTrue(response.contentAsByteArray.size in 1..ComplaintInstallationHttpResponses.MAX_BYTES)
        assertFalse(response.contentAsString.contains(SECRET))
        assertNull(response.getHeader("WWW-Authenticate"))
        assertNull(response.getHeader("Location"))
    }

    private class Fixture(private val fail: () -> Nothing = { rejectInstallationHttp(ComplaintInstallationHttpFailure.UNAVAILABLE) }) {
        val ingress = historyTestIngress()
        var calls = 0
        private val exchange = object : ComplaintOwnerDeleteAllExchange {
            override fun deleteAll(context: ComplaintInstallationRequestContext, candidate: InstallationDeletionCandidate): ComplaintOwnerDeleteAllResponse {
                ingress.requireLiveContext(context as ComplaintIngressContext)
                calls += 1
                fail()
            }
        }
        val handler = ComplaintOwnerDeleteAllHttpHandler(ComplaintOwnerDeleteAllService(exchange), ingress)
    }

    private companion object {
        const val PATH = "/api/v1/installations/delete-all"
        const val KEY_HEADER = "X-Kira-Idempotency-Key"
        const val KEY = "123e4567-e89b-42d3-a456-426614174002"
        const val DELIVERY_FAILURE = "Installation deletion HTTP exchange failed."
        val SECRET = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
    }
}
