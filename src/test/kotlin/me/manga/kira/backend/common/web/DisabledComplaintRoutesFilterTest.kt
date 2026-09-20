package me.manga.kira.backend.common.web

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletInputStream
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import me.manga.kira.backend.config.WebDiagnosticsConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.security.SecurityProperties
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class DisabledComplaintRoutesFilterTest {
    private val mapper = ObjectMapper().findAndRegisterModules()
    private val configuration = WebDiagnosticsConfig()

    @Test
    fun `disabled namespaces return constant no-store 404 before body or any token work`() {
        val paths = listOf(
            "/api/v1/installations/bootstrap", "/api/v1/installations", "/api/v1/installations/session",
            "/api/v1/installations/me", "/api/v1/installations/delete-all", "/api/v1/complaints",
            "/api/v1/complaints/private-id/content", "/api/v1/complaints/private-id/replies",
            "/api/v1/admin/complaints/search", "/api/v1/admin/complaints/private-id/status",
            "/api/v1/complaint-operations/status",
        )
        val requests = paths.map { unreadable("POST", it) } +
            listOf("GET", "PATCH", "DELETE", "OPTIONS", "HEAD").map { unreadable(it, "/api/v1/complaints") } +
            listOf("Bearer user-token", "Bearer installation-token", "malformed").map { token ->
                unreadable("POST", "/api/v1/installations/session").apply { addHeader(HttpHeaders.AUTHORIZATION, token) }
            }
        for (request in requests) {
            request.addHeader(HttpHeaders.CONTENT_LENGTH, Long.MAX_VALUE.toString())
            request.addHeader(HttpHeaders.CONTENT_TYPE, "invalid/private-content")
            request.queryString = "secret=must-not-be-read"
            val response = MockHttpServletResponse()
            filters().doFilter(request, response)
            assertProblem(response, request.method == "HEAD")
        }
    }

    @Test
    fun `servlet-relative encoded and matrix routes cannot evade the closed boundary`() {
        for (path in listOf(
            "/service/api/v1/installations/session",
            "/service/api/v1/%69nstallations/session",
            "/service/api/v1/installations;private=ignored/session",
            "/service/api/v1/complaints/",
            "/service/api/v1/complaints/private-id;version=1/content",
            "/service/api/v1/admin/complaints/stats",
        )) {
            val request = unreadable("POST", path).apply { contextPath = "/service" }
            val response = MockHttpServletResponse()
            filters().doFilter(request, response)
            assertProblem(response)
        }
    }

    @Test
    fun `registration order keeps diagnostics outermost and blocks before buffering and Spring security`() {
        val diagnostic = configuration.requestDiagnosticsFilter()
        val closed = configuration.disabledComplaintRoutesFilter()
        val body = configuration.requestBodySizeLimitFilter(mapper)
        assertTrue(diagnostic.order < closed.order)
        assertTrue(closed.order < body.order)
        assertTrue(body.order < SecurityProperties.DEFAULT_FILTER_ORDER)
        assertEquals(listOf("/*"), closed.urlPatterns.toList())
        val response = MockHttpServletResponse()
        filters().doFilter(unreadable("POST", "/api/v1/installations"), response)
        assertTrue(response.getHeader(RequestDiagnosticsFilter.REQUEST_ID_HEADER)?.matches(RequestDiagnosticsFilter.REQUEST_ID_PATTERN) == true)
        assertProblem(response)
    }

    @Test
    fun `unrelated auth admin source and namespace-prefix routes keep their existing body and security chain`() {
        for (path in listOf(
            "/api/v1/auth/login", "/api/v1/auth/me", "/api/v1/admin/users", "/api/v2/source-config/manifest",
            "/actuator/health/readiness", "/api/v1/installations-other", "/api/v1/complaints-other",
            "/api/v1/admin/complaints-other", "/api/v1/complaint-operations/status-other",
        )) {
            val body = "unchanged-body".toByteArray()
            val request = MockHttpServletRequest("POST", path).apply { setContent(body) }
            val response = MockHttpServletResponse()
            var downstream = false
            filters(
                FilterChain { received, output ->
                    downstream = true
                    assertEquals(body.toList(), received.inputStream.readAllBytes().toList())
                    (output as MockHttpServletResponse).status = 401
                },
            ).doFilter(request, response)
            assertTrue(downstream)
            assertEquals(401, response.status)
            assertNull(response.getHeader("X-Kira-Complaint-Contract"))
            assertNull(response.getHeader(HttpHeaders.CACHE_CONTROL))
        }
    }

    @Test
    fun `closed responses neither echo secrets nor enter error or authentication dispatch`() {
        val response = MockHttpServletResponse()
        filters().doFilter(unreadable("POST", "/api/v1/complaint-operations/status"), response)
        assertProblem(response)
        assertFalse(response.contentAsString.contains("secret"))
        assertNull(response.errorMessage)
        assertNull(response.redirectedUrl)
        assertNull(response.forwardedUrl)
        assertNull(response.getHeader(HttpHeaders.WWW_AUTHENTICATE))
        assertNull(response.getHeader(HttpHeaders.ETAG))
        assertNull(response.getHeader(HttpHeaders.LOCATION))
    }

    private fun assertProblem(response: MockHttpServletResponse, head: Boolean = false) {
        assertEquals(404, response.status)
        assertEquals("1", response.getHeader("X-Kira-Complaint-Contract"))
        assertEquals("no-store, no-transform", response.getHeader(HttpHeaders.CACHE_CONTROL))
        assertEquals("application/problem+json", response.contentType)
        assertTrue(response.contentLength in 1..512)
        if (head) {
            assertTrue(response.contentAsByteArray.isEmpty())
        } else {
            assertEquals(response.contentLength, response.contentAsByteArray.size)
            assertEquals(mapper.readTree(PROBLEM), mapper.readTree(response.contentAsByteArray))
        }
    }

    private fun unreadable(method: String, path: String): MockHttpServletRequest = object : MockHttpServletRequest(method, path) {
        override fun getInputStream(): ServletInputStream = error("Disabled complaints must not acquire the request body")
    }

    private fun filters(downstream: FilterChain = FilterChain { _, _ -> error("Disabled complaints reached downstream authentication/MVC") }): FilterChain {
        val filters = listOf(
            configuration.requestDiagnosticsFilter(),
            configuration.disabledComplaintRoutesFilter(),
            configuration.requestBodySizeLimitFilter(mapper),
        ).sortedBy { it.order }.map { it.filter }
        return RegisteredFilters(filters, downstream)
    }

    private class RegisteredFilters(private val filters: List<Filter>, private val downstream: FilterChain) : FilterChain {
        private var next = 0

        override fun doFilter(request: ServletRequest, response: ServletResponse) {
            if (next < filters.size) filters[next++].doFilter(request, response, this) else downstream.doFilter(request, response)
        }
    }

    private companion object {
        const val PROBLEM = """{"type":"about:blank","title":"Not Found","status":404,"detail":"Not found."}"""
    }
}
