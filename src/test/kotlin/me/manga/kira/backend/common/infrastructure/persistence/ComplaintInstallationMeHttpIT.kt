package me.manga.kira.backend.common.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletOutputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import me.manga.kira.backend.common.web.RequestBodySizeLimitFilter
import me.manga.kira.backend.complaint.api.ComplaintInstallationHttpHandler
import me.manga.kira.backend.complaint.api.ComplaintOwnerCreateHttpHandler
import me.manga.kira.backend.complaint.api.ComplaintOwnerHistoryHttpHandler
import me.manga.kira.backend.complaint.api.installationMeTestRequest
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintInstallationMeAuthentication
import me.manga.kira.backend.complaint.domain.ComplaintInstallationMeFailure
import me.manga.kira.backend.complaint.domain.ComplaintInstallationMeRejected
import me.manga.kira.backend.complaint.infrastructure.ComplaintInstallationBearerAuthenticator
import me.manga.kira.backend.complaint.infrastructure.ComplaintInstallationMeReadAdapter
import me.manga.kira.backend.complaint.infrastructure.InstallationHttpPrincipal
import me.manga.kira.backend.security.ComplaintAdmissionRejected
import me.manga.kira.backend.security.ComplaintHttpIngressBridge
import me.manga.kira.backend.security.ComplaintIngressContext
import me.manga.kira.backend.security.ComplaintInstallationSecurityChainFactory
import me.manga.kira.backend.security.CurrentUser
import me.manga.kira.backend.security.complaintSpringSecurityContext
import me.manga.kira.backend.support.JwtTestSupport
import me.manga.kira.backend.user.domain.UserRepository
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.FilterChainProxy

/** Actual c1 producer, JWT, ingress, and owned PostgreSQL phases; never a directly signed grant or enabled route. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class ComplaintInstallationMeHttpIT {
    private val database = lazy { PgLifecycleDatabaseFixture(ComplaintInstallationMeHttpIT::class.java).also { it.start() } }

    @AfterAll
    fun closeDatabase() {
        if (database.isInitialized()) database.value.close()
    }

    @Test
    fun `real ordered chain keeps c1 session identity and rechecks changed rows without a second semantic charge`() = withFixture { f ->
        val bridge = ComplaintHttpIngressBridge(f.ingress)
        val authentication = ComplaintInstallationBearerAuthenticator(f.run.scope, f.jwt, f.phases, f.ingress)
        val factory = ComplaintInstallationSecurityChainFactory(
            bridge,
            authentication,
            mock(ComplaintInstallationHttpHandler::class.java), // Other routes are not exercised by this read-only probe.
            f.handler,
            mock(ComplaintOwnerHistoryHttpHandler::class.java),
            mock(ComplaintOwnerCreateHttpHandler::class.java),
        )
        val users = mock(UserRepository::class.java)
        val before = f.run.state()
        val readEvents = checkNotNull(lifecycleField(f.ingress, "ownerReads"))
        val events = lifecycleField(readEvents, "events") as Int
        complaintSpringSecurityContext(factory, users).use { spring ->
            val chain = spring.getBean(FilterChainProxy::class.java)
            val body = RequestBodySizeLimitFilter(ObjectMapper())
            for (revoke in listOf(false, true)) {
                val request = installationMeTestRequest(f.token).apply { servletPath = f.location }
                val response = MockHttpServletResponse()
                var dispatched = false
                val dispatch = FilterChain { routed, result ->
                    dispatched = true
                    f.assertReleased()
                    val principal = SecurityContextHolder.getContext().authentication.principal as InstallationHttpPrincipal
                    assertEquals(f.id, principal.installation.id)
                    assertNull(CurrentUser().getOrNull())
                    if (revoke) assertEquals(1, f.base.observer.update("UPDATE app_installations SET credential_version = 2 WHERE id = ?", f.id))
                    val selected = f.run.state()
                    factory.handler.handleRequest(routed as HttpServletRequest, result as HttpServletResponse)
                    assertEquals(selected, f.run.state())
                    f.assertReleased()
                }
                bridge.doFilter(request, response) { admitted, target ->
                    body.doFilter(admitted, target) { bounded, output ->
                        chain.doFilter(bounded, output, dispatch)
                    }
                }
                assertTrue(dispatched)
                if (revoke) {
                    f.assertProblem(response, 401, "UNAUTHORIZED")
                } else {
                    assertEquals(200, response.status)
                    assertEquals(f.id.toString(), f.json(response)["installationId"].asText())
                    assertEquals(before, f.run.state())
                }
                assertEquals(
                    events + 1,
                    lifecycleField(readEvents, "events"),
                    "Only the successful producer read, not the early converter, pays the owner-read charge.",
                )
                assertNull(SecurityContextHolder.getContext().authentication)
            }
        }
        verifyNoInteractions(users)
    }

    @Test
    fun `actual c1 HTTP session token yields exact me projection after release without writes or creation capacity`() = withFixture { f ->
        assertEquals(22, f.base.observer.update("UPDATE complaint_capacity_counters SET configuration_closed = true"))
        val before = f.run.state()
        var sentConnectionFree = false
        val response = object : MockHttpServletResponse() {
            override fun getOutputStream(): ServletOutputStream {
                f.assertReleased()
                sentConnectionFree = true
                return super.getOutputStream()
            }
        }
        f.handler.handleRequest(installationMeTestRequest(f.token).apply { requestURI = f.location }, response)
        assertEquals(200, response.status)
        assertTrue(sentConnectionFree)
        assertEquals(
            "{\"installationId\":\"${f.id}\",\"credentialVersion\":1,\"dataScopeId\":\"${f.run.scope.id}\"}",
            response.contentAsString,
        )
        assertEquals("1", response.getHeader("X-Kira-Complaint-Contract"))
        assertEquals("no-store, no-transform", response.getHeader("Cache-Control"))
        assertEquals(response.contentAsByteArray.size.toString(), response.getHeader("Content-Length"))
        assertNull(response.getHeader("ETag"))
        assertNull(response.getHeader("Location"))
        assertEquals(before, f.run.state())
    }

    @Test
    fun `invalid missing wrong family and actual foreign scope tokens refuse before unavailable current row SQL`() {
        lateinit var foreign: String
        withFixture { foreign = it.token } // Fully close this run before creating the next nonterminal run.
        withFixture { f ->
            assertTrue(f.jwt.verify(foreign).installation.scope != f.run.scope)
            assertThrows<IllegalArgumentException> { ComplaintInstallationMeReadAdapter(ComplaintDataScope.LIVE, f.jwt, f.phases, f.ingress) }
            withoutDatabaseAdmission(f) {
                f.assertProblem(f.request(), 503, "SERVICE_UNAVAILABLE") // A valid actual token reaches the unavailable named phase.
                for (token in listOf(null, "malformed", JwtTestSupport.tamperSignature(f.token), foreign)) {
                    f.assertProblem(f.request(token), 401, "UNAUTHORIZED")
                }
                for (role in listOf("USER", "ADMIN")) {
                    f.assertProblem(f.request(JwtTestSupport.mint(f.base.ordinary.userId, role = role)), 401, "UNAUTHORIZED")
                }
                assertEquals(0L, f.base.ordinary.ownedPool.lifecycle.activeAcquisitions())
            }
            assertEquals(200, f.request().status)
            assertEquals(1, f.base.observer.update("DELETE FROM app_installations WHERE id = ?", f.id))
            f.assertProblem(f.request(), 401, "UNAUTHORIZED")
        }
    }

    @Test
    fun `current credential reservation and run state are rechecked after the authenticated preflight releases`() = withFixture { f ->
        for ((change, restore) in ROW_CHANGES) {
            f.ingress.withIngress(installationMeTestRequest(f.token)) { context ->
                val authenticated = f.reader.authenticate(context, f.token)
                f.assertReleased()
                assertEquals(1, f.base.observer.update(change, f.id))
                val changed = f.run.state()
                denied { f.reader.read(context, authenticated) }
                assertEquals(changed, f.run.state())
            }
            f.assertProblem(f.request(), 401, "UNAUTHORIZED")
            assertEquals(1, f.base.observer.update(restore, f.id))
            assertEquals(200, f.request().status)
        }
        f.ingress.withIngress(installationMeTestRequest(f.token)) { context ->
            val authenticated = f.reader.authenticate(context, f.token)
            f.assertReleased()
            f.run.terminalState("SEALED")
            denied { f.reader.read(context, authenticated) }
        }
        f.assertProblem(f.request(), 401, "UNAUTHORIZED")
        for (state in listOf("PURGING", "PURGED")) {
            f.run.terminalState(state)
            f.assertProblem(f.request(), 401, "UNAUTHORIZED")
        }
    }

    @Test
    fun `only private owner caller and live ingress can consume the authenticated me handoff once`() = withFixture { f ->
        f.ingress.withIngress(installationMeTestRequest(f.token)) { context ->
            val authenticated = f.reader.authenticate(context, f.token)
            f.assertReleased()
            denied { f.reader.read(context, object : ComplaintInstallationMeAuthentication {}) }
            denied { f.reader.read(ComplaintIngressContext(), authenticated) }
            denied { f.newReader().read(context, authenticated) }
            OwnedCallerTestScope().use { callers ->
                callers.launch { denied { f.reader.read(context, authenticated) } }.value()
            }
            assertEquals(f.id, f.reader.read(context, authenticated).installation.id)
            denied { f.reader.read(context, authenticated) }
        }
        val stale = f.ingress.withIngress(installationMeTestRequest(f.token)) { context ->
            context to f.reader.authenticate(context, f.token)
        }
        assertThrows<ComplaintAdmissionRejected> { f.reader.read(stale.first, stale.second) }
    }

    private fun withoutDatabaseAdmission(f: ComplaintInstallationMeFixture, work: () -> Unit) {
        OwnedCallerTestScope().use { callers ->
            val gate = callers.gate()
            val held = callers.launch {
                val permit = checkNotNull(f.base.ordinary.admission.tryComplaintBoundary())
                try {
                    gate.hold()
                } finally {
                    assertTrue(permit.releaseAfterQuiescence())
                }
                true
            }
            gate.awaitEntered()
            try {
                work()
            } finally {
                gate.release()
                held.value()
            }
        }
    }

    private fun denied(work: () -> Unit) {
        val failure = assertThrows<ComplaintInstallationMeRejected> { work() }
        assertEquals(ComplaintInstallationMeFailure.UNAUTHORIZED, failure.failure)
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
    }

    private fun withFixture(work: (ComplaintInstallationMeFixture) -> Unit) {
        withOrdinaryComplaintInstallationEnrollment(database.value) { base ->
            OrdinaryComplaintTestInstallationFixture(base).use { run ->
                val fixture = ComplaintInstallationMeFixture(base, run)
                work(fixture)
                fixture.assertReleased()
            }
        }
    }

    private companion object {
        val ROW_CHANGES = listOf(
            "UPDATE app_installations SET credential_version = 2 WHERE id = ?" to
                "UPDATE app_installations SET credential_version = 1 WHERE id = ?",
            "UPDATE app_installations SET state = 'DELETION_PENDING' WHERE id = ?" to
                "UPDATE app_installations SET state = 'ACTIVE' WHERE id = ?",
            "UPDATE complaint_installation_ids SET state = 'RETIRED', terminal_at = now() WHERE id = ?" to
                "UPDATE complaint_installation_ids SET state = 'ACTIVE', terminal_at = NULL WHERE id = ?",
        )
    }
}
