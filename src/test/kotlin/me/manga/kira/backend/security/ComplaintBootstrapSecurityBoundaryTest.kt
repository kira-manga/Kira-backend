package me.manga.kira.backend.security

import jakarta.servlet.ServletInputStream
import me.manga.kira.backend.complaint.api.ComplaintInstallationBootstrapHttpHandler
import me.manga.kira.backend.config.ComplaintTestBootstrapHttpCompositionV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.web.SecurityFilterChain

/** Default genuine Spring mount stays absent; malformed complaint traffic cannot trigger auth, DB or buffering. */
class ComplaintBootstrapSecurityBoundaryTest {
    @Test
    fun `absent registered composition leaves real configuration closed before buffering or account security`() = ComplaintBootstrapSpringTestFixture().use { f ->
        assertTrue(f.context.getBeansOfType(ComplaintTestBootstrapHttpCompositionV1::class.java).isEmpty())
        assertFalse(f.context.containsBean("complaintTestBootstrapHandlerMapping"))
        assertEquals(1, f.context.getBeansOfType(SecurityFilterChain::class.java).size)
        for (path in listOf(ComplaintInstallationBootstrapHttpHandler.PATH, "/api/v1/installations", "/api/v1/installations/session",
            "/api/v1/installations/me", "/api/v1/installations/delete-all", "/api/v1/complaints", "/api/v1/complaint-operations/status", "/api/v1/admin/complaints/search")) {
            val response = f.mvc.perform { servlet ->
                object : MockHttpServletRequest(servlet, "GET", path) {
                    override fun getInputStream(): ServletInputStream = error("Disabled route buffered input")
                }.apply {
                    servletPath = path
                    addHeader("Authorization", "malformed")
                    addHeader("Authorization", "Bearer second")
                    addHeader("Content-Length", Long.MAX_VALUE.toString())
                    queryString = "private=not-read"
                }
            }.andReturn().response
            assertEquals(404, response.status, path)
            assertEquals("1", response.getHeader("X-Kira-Complaint-Contract"))
            assertEquals("no-store, no-transform", response.getHeader("Cache-Control"))
            assertNull(response.getHeader("WWW-Authenticate"))
        }
        verifyNoInteractions(f.users)
    }

    @Test
    fun `only literal bootstrap bridge converts unexpected resource failures to bounded unavailable`() {
        for (path in listOf(ComplaintInstallationBootstrapHttpHandler.PATH, "/api/v1/installations/me", "/api/v1/installations")) {
            for (oom in listOf(false, true)) {
                val bridge = ComplaintHttpIngressBridge(historyTestIngress())
                val request = MockHttpServletRequest("GET", path).apply { remoteAddr = "192.0.2.1" }
                val response = MockHttpServletResponse()
                bridge.doFilter(request, response) { _, _ ->
                    if (oom) throw OutOfMemoryError("private fixture allocation")
                    error("private fixture resource failure")
                }
                assertEquals(if (path == ComplaintInstallationBootstrapHttpHandler.PATH) 503 else 500, response.status)
                assertEquals("1", response.getHeader("X-Kira-Complaint-Contract"))
                assertTrue(response.contentLength in 1..512)
                assertFalse(response.contentAsString.contains("private"))
                assertNull(response.getHeader("Retry-After"))
                assertNull(response.getHeader("WWW-Authenticate"))
                val closed = MockHttpServletResponse()
                bridge.doFilter(request, closed) { _, _ -> error("Closed bridge resumed") }
                assertEquals(503, closed.status)
            }
        }
    }
}
