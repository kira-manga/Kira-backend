package me.manga.kira.backend.security

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.common.web.RequestBodySizeLimitFilter
import me.manga.kira.backend.complaint.api.ComplaintInstallationHttpHandler
import me.manga.kira.backend.complaint.api.ComplaintInstallationMeHttpHandler
import me.manga.kira.backend.complaint.api.ComplaintOwnerCreateHttpHandler
import me.manga.kira.backend.complaint.api.ComplaintOwnerDeleteAllHttpHandler
import me.manga.kira.backend.complaint.api.ComplaintOwnerHistoryHttpHandler
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import me.manga.kira.backend.complaint.infrastructure.ComplaintInstallationBearerAuthenticator
import me.manga.kira.backend.complaint.infrastructure.InstallationHttpPrincipal
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerHistoryPhaseExecutor
import me.manga.kira.backend.support.JwtTestSupport
import me.manga.kira.backend.user.domain.Role
import me.manga.kira.backend.user.domain.User
import me.manga.kira.backend.user.domain.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Answers
import org.mockito.Mockito.mock
import org.slf4j.MDC
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.FilterChainProxy
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository
import java.time.Instant
import java.util.UUID

/** Real Spring chains/codecs. Inert HTTP/current-row stand-ins prove composition only; the existing owned-PG me IT proves actual SQL. */
class ComplaintInstallationSecurityChainTest {
    @Test
    fun `qualified real chains keep installation and account identity separate even for the same UUID`() = Fixture().use { f ->
        val installation = f.request("GET", ComplaintInstallationRoutes.ME, f.installationToken)
        assertEquals(204, installation.status)
        assertTrue(f.observed?.principal is InstallationHttpPrincipal)
        assertNull(f.observed?.credentials)
        assertEquals(listOf("ROLE_INSTALLATION"), f.observed?.authorities?.map { it.authority })
        assertNull(f.observedUser)
        assertNull(f.observedMdcUser)
        assertNull(f.lastRequest.getAttribute(RequestAttributeSecurityContextRepository.DEFAULT_REQUEST_ATTR_NAME))
        assertNull(SecurityContextHolder.getContext().authentication)
        assertEquals(1, f.currentReads)
        assertEquals(0, f.userReads)
        val account = f.request("GET", "/api/v1/auth/me", f.userToken)
        assertEquals(204, account.status)
        assertEquals(f.id, f.observedUser?.id)
        assertEquals(f.id.toString(), f.observedMdcUser)
        assertEquals(1, f.userReads)
        assertEquals(1, f.currentReads)
        assertEquals(401, f.request("GET", ComplaintInstallationRoutes.ME, f.userToken).status)
        assertEquals(401, f.request("GET", "/api/v1/auth/me", f.installationToken).status)
        assertEquals(1, f.userReads)
        assertEquals(1, f.currentReads)
        f.user = f.user.copy(enabled = false)
        assertEquals(401, f.request("GET", "/api/v1/auth/me", f.userToken).status)
        f.user = f.user.copy(enabled = true, role = Role.ADMIN)
        assertEquals(204, f.request("GET", "/api/v1/admin/complaints", f.userToken).status)
        assertEquals(1, f.currentReads)
        assertNull(SecurityContextHolder.getContext().authentication)
        assertNull(MDC.get(AuthenticatedMdcFilter.MDC_USER_ID))
    }

    @Test
    fun `public secret and closed dormant routes never resolve bearer or fall through to the user chain`() = Fixture().use { f ->
        for (path in listOf(ComplaintInstallationRoutes.ENROLLMENT, ComplaintInstallationRoutes.SESSION)) {
            val response = f.request("POST", path, "not-a-token", "{}")
            assertEquals(204, response.status) // Explicit inert body-secret handler, not a session proof.
            assertNull(response.getHeader("WWW-Authenticate"))
        }
        for ((method, path) in listOf(
            "GET" to "/api/v1/installations/bootstrap",
            "POST" to ComplaintInstallationRoutes.DELETE_ALL,
            "DELETE" to ComplaintInstallationRoutes.ENROLLMENT,
            "POST" to ComplaintInstallationRoutes.ME,
            "GET" to "/api/v1/complaints/${f.id}",
            "GET" to "/api/v1/installations/me/",
            "GET" to "/api/v1/installations/%6de",
        )) {
            // Containers decode servletPath; requestURI retains this explicit raw unreserved alias.
            val decodedPath = if (path == "/api/v1/installations/%6de") ComplaintInstallationRoutes.ME else path
            val response = f.request(
                method,
                path,
                "not-a-token",
                content = if (path == ComplaintInstallationRoutes.DELETE_ALL) "{}" else null,
                decodedServletPath = decodedPath,
            )
            assertEquals(404, response.status, "$method $path")
            assertEquals("1", response.getHeader("X-Kira-Complaint-Contract"))
            assertNull(response.getHeader("WWW-Authenticate"))
        }
        for (path in listOf("/api/v1/installations//me", "/api/v1/installations/%2fme", "/api/v1/installations/me;variant=1")) {
            val response = f.request("GET", path, "not-a-token")
            assertEquals(400, response.status, "GET $path") // The real Spring StrictHttpFirewall, not a home-grown path canonicalizer.
            assertNull(response.getHeader("WWW-Authenticate"))
        }
        assertEquals(0, f.currentReads)
        assertEquals(0, f.userReads)
        assertEquals(204, f.request("GET", "/api/v2/source-config/manifest").status)
        val unrelated = f.request("GET", "/api/v1/installations-other")
        assertEquals(401, unrelated.status)
        assertNull(unrelated.getHeader("X-Kira-Complaint-Contract"))
    }

    @Test
    fun `optional delete-all dispatch shares ingress ignores bearer and keeps aliases closed`() = Fixture(includeDeleteAll = true).use { f ->
        for (token in listOf(null, "not-a-token", f.userToken)) {
            val response = f.request("POST", ComplaintInstallationRoutes.DELETE_ALL, token, "{}")
            assertEquals(204, response.status) // Inert handler proves dispatch only, not deletion or a private outcome.
            assertNull(response.getHeader("WWW-Authenticate"))
            assertNull(f.observed)
            assertNull(f.observedUser)
            assertNull(f.observedMdcUser)
        }
        assertEquals(3, f.deleteAllCalls)
        val oversized = f.request("POST", ComplaintInstallationRoutes.DELETE_ALL, f.userToken, " ".repeat(4097))
        assertEquals(413, oversized.status)
        assertEquals("1", oversized.getHeader("X-Kira-Complaint-Contract"))
        assertNull(oversized.getHeader("WWW-Authenticate"))
        for ((method, path) in listOf(
            "GET" to ComplaintInstallationRoutes.DELETE_ALL,
            "PUT" to ComplaintInstallationRoutes.DELETE_ALL,
            "DELETE" to ComplaintInstallationRoutes.DELETE_ALL,
            "POST" to "${ComplaintInstallationRoutes.DELETE_ALL}/",
            "POST" to "/api/v1/installations/%64elete-all",
        )) {
            val decodedPath = if (path.contains("%64")) ComplaintInstallationRoutes.DELETE_ALL else path
            val response = f.request(method, path, "not-a-token", "{}", decodedServletPath = decodedPath)
            assertEquals(404, response.status, "$method $path")
            assertNull(response.getHeader("WWW-Authenticate"))
            assertEquals("1", response.getHeader("X-Kira-Complaint-Contract"))
        }
        assertEquals(3, f.deleteAllCalls)
        assertEquals(0, f.currentReads)
        assertEquals(0, f.userReads)
        assertEquals(0, f.semanticStarts)
        assertThrows<ComplaintSecurityRejected> { f.bridge.authenticationContext(f.lastRequest) }
        requireConnectionFree()
    }

    @Test
    fun `early authentication failures stay bounded distinct and release ingress without semantic admission`() = Fixture().use { f ->
        for (token in listOf(null, f.userToken, JwtTestSupport.tamperSignature(f.installationToken))) {
            val response = f.request("GET", ComplaintInstallationRoutes.ME, token)
            assertEquals(401, response.status)
            assertEquals("Bearer realm=\"kira-complaints\"", response.getHeader("WWW-Authenticate"))
            assertEquals("no-store, no-transform", response.getHeader("Cache-Control"))
            assertFalse(response.contentAsString.contains(f.id.toString()))
            assertTrue(response.contentLength in 1..512)
        }
        assertEquals(0, f.currentReads)
        f.phaseUnavailable = true
        val unavailable = f.request("GET", ComplaintInstallationRoutes.ME, f.installationToken)
        assertEquals(503, unavailable.status)
        assertNull(unavailable.getHeader("WWW-Authenticate"))
        f.phaseUnavailable = false
        f.active = false
        assertEquals(401, f.request("GET", ComplaintInstallationRoutes.ME, f.installationToken).status)
        f.active = true
        assertEquals(204, f.request("GET", ComplaintInstallationRoutes.ME, f.installationToken).status)
        assertEquals(1, f.semanticStarts)
        assertThrows<ComplaintSecurityRejected> { f.bridge.authenticationContext(f.lastRequest) }
        requireConnectionFree()
    }

    private class Fixture(includeDeleteAll: Boolean = false) : AutoCloseable {
        val ingress = historyTestIngress()
        val bridge = ComplaintHttpIngressBridge(ingress)
        val id: UUID = UUID.randomUUID()
        val scope = ComplaintDataScope.of(UUID.randomUUID())
        private val jwt = historyTestJwt()
        val installationToken = jwt.issue(ScopedInstallationId(id, scope), 1, Instant.now()).value
        val userToken = JwtTestSupport.mint(id)
        var user = User(id, "chain@example.test", "{bcrypt}synthetic-unused", Role.USER, true, Instant.now(), Instant.now())
        var currentReads = 0
        var userReads = 0
        var semanticStarts = 0
        var deleteAllCalls = 0
        var active = true
        var phaseUnavailable = false
        var observed: Authentication? = null
        var observedUser: AuthenticatedUser? = null
        var observedMdcUser: String? = null
        lateinit var lastRequest: MockHttpServletRequest
        private var bodyContext: ComplaintIngressContext? = null
        private val users = mock(UserRepository::class.java) { call ->
            if (call.method.name == "findById") {
                userReads += 1
                user
            } else {
                Answers.RETURNS_DEFAULTS.answer(call)
            }
        }
        private val phases = mock(ComplaintOwnerHistoryPhaseExecutor::class.java) { call ->
            if (call.method.name == "authenticate") {
                currentReads += 1
                ingress.requireLiveContext(bridge.authenticationContext(lastRequest))
                if (phaseUnavailable) throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
                active
            } else {
                Answers.RETURNS_DEFAULTS.answer(call)
            }
        }
        private val installations = mock(ComplaintInstallationHttpHandler::class.java) { call ->
            if (call.method.name.substringBefore('$') == "handleWithinIngress") {
                capture(call.getArgument(0), call.getArgument(1))
                null
            } else {
                Answers.RETURNS_DEFAULTS.answer(call)
            }
        }
        private val me = mock(ComplaintInstallationMeHttpHandler::class.java) { call ->
            if (call.method.name.substringBefore('$') == "handleWithinIngress") {
                val request = call.getArgument<HttpServletRequest>(0)
                val response = call.getArgument<HttpServletResponse>(1)
                val admitted = call.getArgument<ComplaintIngressContext>(2)
                ingress.startOwnerHistory(admitted) // A second semantic start would be refused by the real counter owner.
                semanticStarts += 1
                capture(request, response)
                null
            } else {
                Answers.RETURNS_DEFAULTS.answer(call)
            }
        }
        private val history = mock(ComplaintOwnerHistoryHttpHandler::class.java)
        private val create = mock(ComplaintOwnerCreateHttpHandler::class.java)
        private val deleteAll = if (includeDeleteAll) {
            mock(ComplaintOwnerDeleteAllHttpHandler::class.java) { call ->
                if (call.method.name.substringBefore('$') == "handleWithinIngress") {
                    val admitted = call.getArgument<ComplaintIngressContext>(2)
                    ingress.requireLiveContext(admitted)
                    assertSame(bodyContext, admitted)
                    deleteAllCalls += 1
                    capture(call.getArgument(0), call.getArgument(1))
                    null
                } else {
                    Answers.RETURNS_DEFAULTS.answer(call)
                }
            }
        } else {
            null
        }
        private val authentication = ComplaintInstallationBearerAuthenticator(scope, jwt, phases, ingress)
        private val factory = ComplaintInstallationSecurityChainFactory(bridge, authentication, installations, me, history, create, deleteAll)
        private val context = complaintSpringSecurityContext(factory, users)
        private val proxy = context.getBean(FilterChainProxy::class.java)
        private val body = RequestBodySizeLimitFilter(ObjectMapper())

        fun request(method: String, path: String, token: String? = null, content: String? = null, decodedServletPath: String = path): MockHttpServletResponse {
            bodyContext = null
            lastRequest = object : MockHttpServletRequest(method, path) {
                override fun getInputStream(): jakarta.servlet.ServletInputStream {
                    if (ComplaintInstallationRoutes.matches(this)) {
                        bodyContext = bridge.authenticationContext(this)
                        ingress.requireLiveContext(checkNotNull(bodyContext))
                    }
                    return super.getInputStream()
                }
            }.apply {
                remoteAddr = "192.0.2.1"
                servletPath = decodedServletPath
                token?.let { addHeader("Authorization", "Bearer $it") }
                content?.let {
                    contentType = "application/json"
                    setContent(it.toByteArray())
                }
            }
            val response = MockHttpServletResponse()
            bridge.doFilter(lastRequest, response) { admitted, output ->
                body.doFilter(admitted, output) { bounded, target ->
                    proxy.doFilter(bounded, target) { routed, result ->
                        val http = routed as HttpServletRequest
                        if (ComplaintInstallationRoutes.matches(http)) {
                            factory.handler.handleRequest(http, result as HttpServletResponse)
                        } else {
                            capture(http, result as HttpServletResponse)
                        }
                    }
                }
            }
            return response
        }

        private fun capture(request: HttpServletRequest, response: HttpServletResponse) {
            observed = SecurityContextHolder.getContext().authentication
            observedUser = CurrentUser().getOrNull()
            AuthenticatedMdcFilter().doFilter(
                request,
                response,
                FilterChain { _, _ ->
                    observedMdcUser = MDC.get(AuthenticatedMdcFilter.MDC_USER_ID)
                    response.status = 204
                },
            )
        }

        override fun close() {
            context.close()
            assertNull(SecurityContextHolder.getContext().authentication)
        }
    }
}
