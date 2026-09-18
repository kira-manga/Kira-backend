package me.manga.kira.backend.common.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletOutputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import me.manga.kira.backend.common.web.RequestBodySizeLimitFilter
import me.manga.kira.backend.complaint.api.ComplaintInstallationHttpHandler
import me.manga.kira.backend.complaint.api.ComplaintOwnerCreateHttpHandler
import me.manga.kira.backend.complaint.api.ComplaintOwnerDetailHttpHandler
import me.manga.kira.backend.complaint.api.ComplaintOwnerHistoryHttpHandler
import me.manga.kira.backend.complaint.api.ownerDetailTestRequest
import me.manga.kira.backend.complaint.application.ComplaintOwnerDetailService
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDetail
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDetailAuthentication
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDetailFailure
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDetailRejected
import me.manga.kira.backend.complaint.infrastructure.ComplaintInstallationBearerAuthenticator
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDetailReadAdapter
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerHistoryIdentity
import me.manga.kira.backend.complaint.infrastructure.InstallationHttpPrincipal
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDetailStore
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerDetailPhaseExecutor
import me.manga.kira.backend.security.ComplaintAdmissionRejected
import me.manga.kira.backend.security.ComplaintHttpIngressBridge
import me.manga.kira.backend.security.ComplaintIngressContext
import me.manga.kira.backend.security.ComplaintInstallationSecurityChainFactory
import me.manga.kira.backend.security.CurrentUser
import me.manga.kira.backend.security.complaintSpringSecurityContext
import me.manga.kira.backend.user.domain.UserRepository
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.dao.DataAccessException
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.FilterChainProxy
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

/** Existing PG/HTTP fixtures only. Synthetic TEST rows and an actual c1 session are not genuine mode/activation proof. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class ComplaintOwnerDetailIT {
    private val database = lazy { PgLifecycleDatabaseFixture(ComplaintOwnerDetailIT::class.java).also { it.start() } }

    @AfterAll
    fun closeDatabase() {
        if (database.isInitialized()) database.value.close()
    }

    @Test
    fun `optional ordered chain uses an HTTP issued token for exact owned detail and same scope notice after release`() = withFixture { f ->
        val content = f.content()
        val notice = f.rows.notice()
        val before = f.rows.state()
        val readEvents = checkNotNull(lifecycleField(f.session.ingress, "ownerReads"))
        val events = lifecycleField(readEvents, "events") as Int
        for (supplied in listOf(false, true)) {
            val bridge = ComplaintHttpIngressBridge(f.session.ingress)
            val installations = mock(ComplaintInstallationHttpHandler::class.java)
            val history = mock(ComplaintOwnerHistoryHttpHandler::class.java)
            val create = mock(ComplaintOwnerCreateHttpHandler::class.java)
            val authentication = ComplaintInstallationBearerAuthenticator(f.session.run.scope, f.session.jwt, f.session.phases, f.session.ingress)
            val factory = ComplaintInstallationSecurityChainFactory(
                bridge,
                authentication,
                installations,
                f.session.handler,
                history,
                create,
                detail = if (supplied) f.handler else null,
            )
            val users = mock(UserRepository::class.java)
            complaintSpringSecurityContext(factory, users).use { spring ->
                val chain = spring.getBean(FilterChainProxy::class.java)
                val bodyGuard = RequestBodySizeLimitFilter(ObjectMapper())
                if (supplied) malformedBeforeSql(f, bridge, chain, bodyGuard, content)
                for (id in listOf(content, notice)) {
                    val request = ownerDetailTestRequest(id, f.session.token).apply {
                        servletPath = requestURI
                        addHeader("If-None-Match", "*")
                    }
                    var sentReleased = false
                    val response = object : MockHttpServletResponse() {
                        override fun getOutputStream(): ServletOutputStream {
                            f.session.assertReleased()
                            sentReleased = true
                            return super.getOutputStream()
                        }
                    }
                    var dispatched = false
                    val dispatch = FilterChain { routed, result ->
                        dispatched = true
                        f.session.assertReleased()
                        val principal = SecurityContextHolder.getContext().authentication.principal as InstallationHttpPrincipal
                        assertEquals(f.session.id, principal.installation.id)
                        assertNull(CurrentUser().getOrNull())
                        factory.handler.handleRequest(routed as HttpServletRequest, result as HttpServletResponse)
                    }
                    bridge.doFilter(request, response) { admitted, target ->
                        bodyGuard.doFilter(admitted, target) { bounded, output -> chain.doFilter(bounded, output, dispatch) }
                    }
                    assertEquals(supplied, dispatched)
                    assertTrue(sentReleased)
                    if (supplied) {
                        assertEquals(200, response.status)
                        val tree = f.session.json(response)
                        assertEquals(id.toString(), tree["id"].asText())
                        if (id == content) {
                            assertEquals("REPORT", tree["kind"].asText())
                            assertEquals("Synthetic history body", tree["body"].asText())
                            assertEquals("\"complaint-$id-v1\"", response.getHeader("ETag"))
                            assertEquals(response.getHeader("ETag"), tree["actionTag"].asText())
                        } else {
                            assertEquals(
                                setOf("id", "kind", "noticeKey", "status", "createdAt", "updatedAt", "version"),
                                tree.fieldNames().asSequence().toSet(),
                            )
                            assertNull(response.getHeader("ETag"))
                        }
                        assertEquals("1", response.getHeader("X-Kira-Complaint-Contract"))
                        assertEquals("no-store, no-transform", response.getHeader("Cache-Control"))
                        assertEquals(response.contentAsByteArray.size.toString(), response.getHeader("Content-Length"))
                        assertFalse(response.contentAsString.contains(f.session.id.toString()))
                        assertFalse(response.contentAsString.contains(f.session.token))
                    } else {
                        f.session.assertProblem(response, 404, "NOT_FOUND")
                    }
                    assertNull(SecurityContextHolder.getContext().authentication)
                    assertEquals(before, f.rows.state())
                }
            }
            verifyNoInteractions(users, installations, history, create)
        }
        assertEquals(events + 2, lifecycleField(readEvents, "events"), "Only the actual detail reads, not converter preflight, pay the shared read charge.")
    }

    @Test
    fun `missing other owner wrong scope legacy and pending resource details have indistinguishable404`() = withFixture { f ->
        val own = f.content()
        val missing = UUID.randomUUID()
        val excluded = listOf(
            f.rows.content(), // A different genuinely enrolled installation in this same synthetic TEST run.
            f.content(resourceState = "DELETION_PENDING"),
            f.rows.notice(ComplaintDataScope.LIVE),
            f.rows.legacy(),
        )
        val absent = f.request(missing)
        f.session.assertProblem(absent, 404, "NOT_FOUND")
        val before = f.rows.state()
        for (id in excluded) {
            val response = f.request(id)
            f.session.assertProblem(response, 404, "NOT_FOUND")
            assertEquals(absent.contentAsString, response.contentAsString)
            assertFalse(response.contentAsString.contains(id.toString()))
        }
        assertEquals(200, f.request(own).status)
        assertEquals(before, f.rows.state())
        assertThrows<IllegalArgumentException> { JdbcComplaintOwnerDetailStore(f.session.base.ordinary.jdbc, ComplaintDataScope.LIVE) }
        assertThrows<IllegalArgumentException> {
            ComplaintOwnerDetailReadAdapter(ComplaintDataScope.LIVE, f.session.jwt, f.session.phases, f.phases, f.session.ingress)
        }
        val wrongScope = ComplaintOwnerDetailReadAdapter(
            ComplaintDataScope.of(UUID.randomUUID()),
            f.session.jwt,
            f.session.phases,
            f.phases,
            f.session.ingress,
        )
        f.session.ingress.withIngress(ownerDetailTestRequest(own, f.session.token)) { context ->
            denied { wrongScope.authenticate(context, f.session.token, own) }
        }
    }

    @Test
    fun `credential installation reservation and run authority are rechecked after real preflight commits and releases`() = withFixture { f ->
        val id = f.content()
        for ((change, restore) in ROW_CHANGES) {
            f.session.ingress.withIngress(ownerDetailTestRequest(id, f.session.token)) { context ->
                val authenticated = f.reader.authenticate(context, f.session.token, id)
                f.session.assertReleased()
                assertEquals(1, f.session.base.observer.update(change, f.session.id))
                val changed = f.rows.state()
                denied { f.reader.read(context, authenticated) }
                assertEquals(changed, f.rows.state())
            }
            f.session.assertProblem(f.request(id), 401, "UNAUTHORIZED")
            assertEquals(1, f.session.base.observer.update(restore, f.session.id))
            assertEquals(200, f.request(id).status)
        }
        f.session.ingress.withIngress(ownerDetailTestRequest(id, f.session.token)) { context ->
            val authenticated = f.reader.authenticate(context, f.session.token, id)
            f.session.assertReleased()
            f.session.run.terminalState("SEALED")
            denied { f.reader.read(context, authenticated) }
        }
        f.session.assertProblem(f.request(id), 401, "UNAUTHORIZED")
    }

    @Test
    fun `exact target rechecks owner reservation and current content rather than caching or paging from preflight`() = withFixture { f ->
        val id = f.content()
        val foreign = f.rows.content()
        val request = ownerDetailTestRequest(id, f.session.token)
        f.session.ingress.withIngress(request) { context ->
            val authenticated = f.reader.authenticate(context, f.session.token, id)
            f.session.assertReleased()
            request.requestURI = "/api/v1/complaints/$foreign" // Mutable request state cannot retarget the private handoff.
            assertEquals(1, f.session.base.observer.update("UPDATE complaints SET body = 'Changed synthetic body', version = 2 WHERE id = ?", id))
            val detail = f.reader.read(context, authenticated) as ComplaintOwnerDetail.Content
            assertEquals(id, detail.item.id)
            assertEquals(2L, detail.item.version)
            assertEquals("Changed synthetic body", detail.item.body)
        }
        val updated = f.request(id)
        assertEquals(200, updated.status)
        assertEquals("\"complaint-$id-v2\"", updated.getHeader("ETag"))
        assertEquals(updated.getHeader("ETag"), f.session.json(updated)["actionTag"].asText())
        f.session.ingress.withIngress(ownerDetailTestRequest(id, f.session.token)) { context ->
            val authenticated = f.reader.authenticate(context, f.session.token, id)
            f.session.assertReleased()
            assertEquals(1, f.session.base.observer.update("UPDATE complaints SET owner_id = ? WHERE id = ?", f.rows.actor.id, id))
            refused(ComplaintOwnerDetailFailure.NOT_FOUND) { f.reader.read(context, authenticated) }
        }
        assertEquals(1, f.session.base.observer.update("UPDATE complaints SET owner_id = ? WHERE id = ?", f.session.id, id))
        f.session.ingress.withIngress(ownerDetailTestRequest(id, f.session.token)) { context ->
            val authenticated = f.reader.authenticate(context, f.session.token, id)
            f.session.assertReleased()
            assertEquals(1, f.session.base.observer.update("UPDATE complaint_resource_ids SET state = 'DELETION_PENDING' WHERE id = ?", id))
            refused(ComplaintOwnerDetailFailure.NOT_FOUND) { f.reader.read(context, authenticated) }
        }
        f.session.assertProblem(f.request(id), 404, "NOT_FOUND")
        assertEquals(1, f.session.base.observer.update("UPDATE complaint_resource_ids SET state = 'LIVE' WHERE id = ?", id))
        f.session.ingress.withIngress(ownerDetailTestRequest(id, f.session.token)) { context ->
            val authenticated = f.reader.authenticate(context, f.session.token, id)
            f.session.assertReleased()
            f.rows.eraseParent(id)
            refused(ComplaintOwnerDetailFailure.NOT_FOUND) { f.reader.read(context, authenticated) }
        }
    }

    @Test
    fun `only the exact private detail owner caller context and one use can consume the handoff`() = withFixture { f ->
        val id = f.content()
        f.session.ingress.withIngress(ownerDetailTestRequest(id, f.session.token)) { context ->
            val authenticated = f.reader.authenticate(context, f.session.token, id)
            f.session.assertReleased()
            denied { f.reader.read(context, object : ComplaintOwnerDetailAuthentication {}) }
            denied { f.reader.read(ComplaintIngressContext(), authenticated) }
            denied { f.newReader().read(context, authenticated) }
            OwnedCallerTestScope().use { callers ->
                callers.launch { denied { f.reader.read(context, authenticated) } }.value()
            }
            assertEquals(id, (f.reader.read(context, authenticated) as ComplaintOwnerDetail.Content).item.id)
            denied { f.reader.read(context, authenticated) }
        }
        val stale = f.session.ingress.withIngress(ownerDetailTestRequest(id, f.session.token)) { context ->
            context to f.reader.authenticate(context, f.session.token, id)
        }
        assertThrows<ComplaintAdmissionRejected> { f.reader.read(stale.first, stale.second) }
    }

    @Test
    fun `concrete detail operation requires exact read only phase resource completion commit and physical release`() = withFixture { f ->
        val id = f.content()
        assertEquals(PersistencePhaseFailureCode.ENTRY_REFUSED, assertThrows<PersistencePhaseException> { f.store.read(f.identity(), id) }.code)
        f.rows.withPhase { phase ->
            // The otherwise-valid history page phase cannot host a detail operation.
            assertThrows<PersistencePhaseException> { f.store.read(f.identity(), id) }
            assertThrows<PersistencePhaseException> { phase.commit() }
        }
        f.withDetail { phase ->
            val foreign = JdbcComplaintOwnerDetailStore(f.session.base.ordinary.foreignTemplate(), f.session.run.scope)
            assertThrows<PersistencePhaseException> { foreign.read(f.identity(), id) }
            assertThrows<PersistencePhaseException> { phase.commit() }
        }
        f.withDetail { phase ->
            phase.ownerDetail.requireOperation(f.session.base.ordinary.jdbc)
            assertFalse(phase.ownerDetail.completed())
            assertThrows<PersistencePhaseException> { phase.commit() }
        }
        val repeated = f.withDetail { phase ->
            val operation = f.store.read(f.identity(), id)
            assertThrows<PersistencePhaseException> { f.store.read(f.identity(), id) }
            assertThrows<PersistencePhaseException> { phase.commit() }
            operation
        }
        assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, assertThrows<PersistencePhaseException> { repeated.result }.databaseOutcome)
        val committed = f.withDetail { phase ->
            assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            assertEquals("on", f.session.base.ordinary.jdbc.queryForObject("SHOW transaction_read_only", String::class.java))
            val operation = f.store.read(f.identity(), id)
            assertTrue(phase.ownerDetail.completed())
            val early = assertThrows<PersistencePhaseException> { operation.result }
            assertEquals(PersistenceDatabaseOutcome.NONE, early.databaseOutcome)
            assertFalse(early.cleanupProven)
            phase.commit()
            val beforeRelease = assertThrows<PersistencePhaseException> { operation.result }
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, beforeRelease.databaseOutcome)
            assertFalse(beforeRelease.cleanupProven)
            operation
        }
        assertEquals(id, (committed.result.detail as ComplaintOwnerDetail.Content).item.id)
        OwnedCallerTestScope().use { callers ->
            val failure = callers.launch { assertThrows<PersistencePhaseException> { committed.result } }.value()
            assertTrue(failure.cleanupProven)
        }
        assertThrows<PersistencePhaseException> { committed.result }
    }

    @Test
    fun `real detail rollback and committed completion tail failure cannot release a success representation`() = withFixture { f ->
        val id = f.content()
        val before = f.rows.state()
        for (sqlFailure in listOf(true, false)) {
            val operation = f.withDetail { phase ->
                val retained = f.store.read(f.identity(), id)
                if (sqlFailure) {
                    phase.recordFailure(assertThrows<DataAccessException> { f.session.base.ordinary.jdbc.execute("SELECT 1 / 0") })
                } else {
                    TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                        override fun afterCommit(): Unit = throw SyntheticInstallationEnrollmentFailure()
                    })
                }
                phase.recordFailure(checkNotNull(runCatching { phase.commit() }.exceptionOrNull()))
                retained
            }
            val failure = assertThrows<PersistencePhaseException> { operation.result }
            assertEquals(if (sqlFailure) PersistenceDatabaseOutcome.ROLLED_BACK else PersistenceDatabaseOutcome.COMMITTED, failure.databaseOutcome)
            assertTrue(failure.cleanupProven)
            assertNull(failure.cause)
            assertTrue(failure.suppressed.isEmpty())
        }
        assertEquals(before, f.rows.state())
    }

    private fun malformedBeforeSql(f: Fixture, bridge: ComplaintHttpIngressBridge, chain: FilterChainProxy, bodyGuard: RequestBodySizeLimitFilter, id: UUID) {
        OwnedCallerTestScope().use { callers ->
            val gate = callers.gate()
            val held = callers.launch {
                val permit = checkNotNull(f.session.base.ordinary.admission.tryComplaintBoundary())
                try {
                    gate.hold()
                } finally {
                    assertTrue(permit.releaseAfterQuiescence())
                }
                true
            }
            gate.awaitEntered()
            try {
                val requests = listOf(
                    ownerDetailTestRequest(id, f.session.token).apply { queryString = "limit=1" } to 400,
                    ownerDetailTestRequest(id, f.session.token).apply { addHeader("Content-Length", "00") } to 400,
                    ownerDetailTestRequest(id, f.session.token).apply { setContent(byteArrayOf(1)) } to 400,
                    ownerDetailTestRequest(id, f.session.token).apply { requestURI += "/" } to 404,
                    ownerDetailTestRequest(id, f.session.token) to 503, // Valid framing does reach the currently unavailable SQL phase.
                )
                for ((request, status) in requests) {
                    request.servletPath = request.requestURI
                    val response = MockHttpServletResponse()
                    bridge.doFilter(request, response) { admitted, target ->
                        bodyGuard.doFilter(admitted, target) { bounded, output ->
                            chain.doFilter(bounded, output, FilterChain { _, _ -> error("Refused detail reached dispatch") })
                        }
                    }
                    val code = when (status) {
                        400 -> "VALIDATION_FAILED"
                        404 -> "NOT_FOUND"
                        else -> "SERVICE_UNAVAILABLE"
                    }
                    f.session.assertProblem(response, status, code)
                }
            } finally {
                gate.release()
                held.value()
            }
        }
    }

    private fun denied(work: () -> Unit) = refused(ComplaintOwnerDetailFailure.UNAUTHORIZED, work)

    private fun refused(expected: ComplaintOwnerDetailFailure, work: () -> Unit) {
        val failure = assertThrows<ComplaintOwnerDetailRejected> { work() }
        assertEquals(expected, failure.failure)
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
    }

    private fun withFixture(work: (Fixture) -> Unit) {
        withOrdinaryComplaintInstallationEnrollment(database.value) { base ->
            OrdinaryComplaintTestInstallationFixture(base).use { run ->
                ComplaintOwnerHistoryFixture(base, run).use { rows ->
                    val fixture = Fixture(ComplaintInstallationMeFixture(base, run), rows)
                    work(fixture)
                    fixture.session.assertReleased()
                }
            }
        }
    }

    /** Only detail wiring around the existing HTTP-issued-token and owned-row helpers; no new test harness. */
    private class Fixture(val session: ComplaintInstallationMeFixture, val rows: ComplaintOwnerHistoryFixture) {
        private val token = session.jwt.verify(session.token)
        val store = JdbcComplaintOwnerDetailStore(session.base.ordinary.jdbc, session.run.scope)
        val phases = ComplaintOwnerDetailPhaseExecutor(session.base.ordinary.ownership, store)
        val reader = newReader()
        val handler = ComplaintOwnerDetailHttpHandler(ComplaintOwnerDetailService(reader), session.ingress)

        fun newReader(): ComplaintOwnerDetailReadAdapter =
            ComplaintOwnerDetailReadAdapter(session.run.scope, session.jwt, session.phases, phases, session.ingress)

        fun content(resourceState: String = "LIVE"): UUID = rows.content(owner = token.installation, resourceState = resourceState)

        fun identity(): ComplaintOwnerHistoryIdentity = ComplaintOwnerHistoryIdentity(token.installation, token.credentialVersion, token.expiresAt)

        fun request(id: UUID): MockHttpServletResponse = MockHttpServletResponse().also {
            handler.handleRequest(ownerDetailTestRequest(id, session.token), it)
        }

        fun <T> withDetail(work: (PersistencePhaseContext) -> T): T =
            rows.withPhase(enter = session.base.ordinary.ownership::enterComplaintOwnerDetail, work = work)
    }

    private companion object {
        val ROW_CHANGES = listOf(
            "UPDATE app_installations SET credential_version = 2 WHERE id = ?" to
                "UPDATE app_installations SET credential_version = 1 WHERE id = ?",
            "UPDATE app_installations SET state = 'DELETION_PENDING' WHERE id = ?" to
                "UPDATE app_installations SET state = 'ACTIVE' WHERE id = ?",
            "UPDATE complaint_installation_ids SET state = 'DELETION_PENDING' WHERE id = ?" to
                "UPDATE complaint_installation_ids SET state = 'ACTIVE' WHERE id = ?",
            "UPDATE complaint_installation_ids SET state = 'RETIRED', terminal_at = now() WHERE id = ?" to
                "UPDATE complaint_installation_ids SET state = 'ACTIVE', terminal_at = NULL WHERE id = ?",
        )
    }
}
