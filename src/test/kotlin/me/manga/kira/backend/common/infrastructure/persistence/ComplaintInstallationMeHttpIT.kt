package me.manga.kira.backend.common.infrastructure.persistence

import jakarta.servlet.ServletOutputStream
import me.manga.kira.backend.complaint.api.installationMeTestRequest
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintInstallationMeAuthentication
import me.manga.kira.backend.complaint.domain.ComplaintInstallationMeFailure
import me.manga.kira.backend.complaint.domain.ComplaintInstallationMeRejected
import me.manga.kira.backend.complaint.infrastructure.ComplaintInstallationMeReadAdapter
import me.manga.kira.backend.security.ComplaintAdmissionRejected
import me.manga.kira.backend.security.ComplaintIngressContext
import me.manga.kira.backend.support.JwtTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.mock.web.MockHttpServletResponse

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
    fun `invalid missing wrong family and actual foreign scope tokens refuse before unavailable current row SQL`() = withFixture { f ->
        val foreign = OrdinaryComplaintTestInstallationFixture(f.base).use { run -> ComplaintInstallationMeFixture(f.base, run).token }
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
