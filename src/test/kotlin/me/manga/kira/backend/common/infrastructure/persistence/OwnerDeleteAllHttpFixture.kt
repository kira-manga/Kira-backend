package me.manga.kira.backend.common.infrastructure.persistence

import jakarta.servlet.ServletOutputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import me.manga.kira.backend.complaint.api.ComplaintOwnerDeleteAllHttpHandler
import me.manga.kira.backend.complaint.application.ComplaintOwnerDeleteAllService
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeleteAllExchangeAdapter
import me.manga.kira.backend.security.ComplaintHttpIngressBridge
import me.manga.kira.backend.security.ComplaintIngressContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.io.PrintWriter
import java.util.Base64
import java.util.UUID

/** Real parser, handler, ingress, private producers and paid-content PG. Synthetic catalog/provider fixture, NOT live routing. */
internal class OwnerDeleteAllHttpFixture(val connected: OwnerDeleteAllContinuationFixture) {
    val auth get() = connected.auth
    private val bridge = ComplaintHttpIngressBridge(auth.ingress)
    private val handler = ComplaintOwnerDeleteAllHttpHandler(
        ComplaintOwnerDeleteAllService(ComplaintOwnerDeleteAllExchangeAdapter(auth.ingress, connected.continuation())),
        auth.ingress,
    )
    private var context: ComplaintIngressContext? = null
    private var caller: Thread? = null
    var dispatches = 0
        private set

    init {
        val beforeS3 = connected.publisher.beforePrepare
        connected.publisher.beforePrepare = {
            beforeS3()
            requireOriginalIngress()
        }
        val beforeKms = connected.publisher.kms.beforePrepare
        connected.publisher.kms.beforePrepare = {
            beforeKms()
            requireOriginalIngress()
        }
    }

    fun request(
        version: Long = connected.candidate.credentialVersion,
        key: UUID = connected.candidate.operationKey,
        secret: ByteArray = ByteArray(32) { it.toByte() },
    ): MockHttpServletRequest = MockHttpServletRequest("POST", PATH).apply {
        remoteAddr = "192.0.2.1"
        contentType = "application/json"
        addHeader("X-Kira-Idempotency-Key", key.toString())
        addHeader("X-Kira-Complaint-Contract", "1")
        addHeader("Authorization", "Bearer deliberately-not-an-installation-token")
        val candidate = connected.candidate
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(secret)
        setContent(
            (
                "{\"installationId\":\"${candidate.installation.id}\",\"secret\":\"$encoded\"," +
                    "\"credentialVersion\":$version,\"dataScopeId\":\"${candidate.installation.scope.id}\"}"
                ).toByteArray(),
        )
    }

    fun exchange(request: MockHttpServletRequest = request(), response: DeleteAllHttpResponse = DeleteAllHttpResponse()): DeleteAllHttpResponse {
        bridge.doFilter(request, response) { admitted, output ->
            val http = admitted as HttpServletRequest
            val original = bridge.claimHandler(http)
            context = original
            caller = Thread.currentThread()
            try {
                dispatches++
                handler.handleWithinIngress(http, output as HttpServletResponse, original)
            } finally {
                context = null
                caller = null
            }
        }
        return response
    }

    fun assertEmpty(response: DeleteAllHttpResponse, status: Int) {
        assertEquals(status, response.status)
        assertTrue(response.contentAsByteArray.isEmpty())
        assertEquals(0, response.streams)
        assertEquals(0, response.writers)
        assertEquals("1", response.getHeader("X-Kira-Complaint-Contract"))
        assertEquals("no-store, no-transform", response.getHeader("Cache-Control"))
        assertNull(response.getHeader("Location"))
        assertNull(response.getHeader("ETag"))
        assertNull(response.getHeader("WWW-Authenticate"))
        if (status == 204) {
            assertNull(response.getHeader("Content-Length"))
            assertNull(response.getHeader("Retry-After"))
        } else {
            assertTrue(checkNotNull(response.getHeader("Retry-After")).toLong() in 1..60)
        }
        connected.assertReleased()
    }

    private fun requireOriginalIngress() {
        assertSame(checkNotNull(caller), Thread.currentThread())
        auth.ingress.requireLiveContext(checkNotNull(context))
        requireConnectionFree()
    }

    companion object {
        const val PATH = "/api/v1/installations/delete-all"
    }
}

internal open class DeleteAllHttpResponse : MockHttpServletResponse() {
    var streams = 0
        private set
    var writers = 0
        private set

    override fun getOutputStream(): ServletOutputStream {
        streams++
        return super.getOutputStream()
    }

    override fun getWriter(): PrintWriter {
        writers++
        return super.getWriter()
    }
}
