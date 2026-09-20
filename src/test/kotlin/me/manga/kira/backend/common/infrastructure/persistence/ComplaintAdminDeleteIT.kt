package me.manga.kira.backend.common.infrastructure.persistence

import jakarta.servlet.ServletOutputStream
import me.manga.kira.backend.complaint.api.ComplaintAdminDeleteHttpHandler
import me.manga.kira.backend.complaint.infrastructure.CommittedTestAdminDeleteWork
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherFixture.Companion.errorReply
import me.manga.kira.backend.security.ScopedAdminStepUpScope
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.io.PrintWriter
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Authored only: real original owners with raw provider fixtures, NOT registered deployment evidence. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class ComplaintAdminDeleteIT {
    private val database = lazy { PgLifecycleDatabaseFixture(ComplaintAdminDeleteIT::class.java).also { it.start() } }
    @AfterAll fun closeDatabase() { if (database.isInitialized()) database.value.close() }

    @Test
    fun `normal ADMIN single delete commits AUTH before provider and VERIFIED before erasure then replays original grant without proof`() = fixture { f ->
        val parent = f.report()
        val child = f.creator.replyAttempt(parent).also { assertEquals(201, f.creator.reply(it).status) }.id
        val otherToken = f.creator.json(f.creator.exchange(UUID.randomUUID(), session = false))["accessToken"].asText()
        val foreign = f.report(otherToken)
        val unrelated = listOf(child, foreign).associateWith { content(f, it) }
        val identities = f.rows("complaint_installation_ids")
        val credentials = f.rows("app_installations")
        val attempt = f.attempt(parent)
        val grant = f.proof()
        val wire = f.wire(attempt, grant.grantId)
        val counters = f.counters()
        wire.beforePrepare = {
            f.assertReleased()
            assertEquals("AUTHORIZED_DELETE", receiptState(f, attempt))
            assertEquals("PREPARED", publicationState(f))
            assertTrue(present(f, parent))
            assertEquals("DELETION_PENDING", f.scalar("SELECT state FROM complaint_resource_ids WHERE id = ?", parent))
            assertTrue(used(f, grant.grantId))
        }
        var erasureReached = false
        f.beforeSql = { sql -> if (sql.startsWith("DELETE FROM complaints")) {
            erasureReached = true
            assertEquals("VERIFIED", publicationState(f), "Independent connection must see a committed VERIFIED record")
            assertEquals("AUTHORIZED_DELETE", receiptState(f, attempt))
            assertTrue(wire.requests.any { it.kind == "GET" })
            assertTrue(f.observations.filter { lifecycleField(it.second.phase, "path") == PersistencePhasePath.COMPLAINT_ADMIN_DELETE_VERIFY }
                .all { it.second.lease.completion.quiescent() })
        } }
        f.factory(wire).use { publishers ->
            val handler = f.http(publishers)
            val response = object : MockHttpServletResponse() {
                override fun setStatus(sc: Int) {
                    f.assertReleased()
                    assertEquals("COMPLETED", receiptState(f, attempt))
                    assertEquals("APPLIED", publicationState(f))
                    super.setStatus(sc)
                }
                override fun getOutputStream(): ServletOutputStream = error("204 must not acquire a stream")
                override fun getWriter(): PrintWriter = error("204 must not acquire a writer")
            }
            f.observations.clear()
            handler.handleRequest(f.input(attempt, grant.token), response)
            applied(response, grant.grantId)
            assertTrue(erasureReached)
            // This observer sees JdbcTemplate statements, not normal authentication's phase-selected raw connection.
            val observedPhases = f.observations.map { it.second.phase }.distinct()
            assertEquals(
                listOf(
                    PersistencePhasePath.COMPLAINT_ADMIN_DELETE_PREFLIGHT,
                    PersistencePhasePath.COMPLAINT_ADMIN_DELETE_AUTHORIZE,
                    PersistencePhasePath.COMPLAINT_ADMIN_DELETE_VERIFY,
                    PersistencePhasePath.COMPLAINT_ADMIN_DELETE_APPLY,
                ),
                observedPhases.map { lifecycleField(it, "path") },
            )
            observedPhases.forEach { phase ->
                assertEquals(PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome())
                assertEquals("CLOSED", lifecycleField(phase, "stage").toString())
            }
            assertTrue(f.observations.all { it.second.lease.completion.quiescent() })
            assertFalse(present(f, parent))
            assertEquals(identities, f.rows("complaint_installation_ids"))
            assertEquals(credentials, f.rows("app_installations"))
            unrelated.forEach { (id, old) -> assertEquals(old, content(f, id)) }
            f.assertCounterDelta(counters, OwnerDeleteLiteralCharges.authorization, OwnerDeleteLiteralCharges.promise,
                OwnerDeleteLiteralCharges.ordinaryApply, OwnerDeleteLiteralCharges.content)
            val fresh = f.proof()
            val snapshot = f.state()
            val calls = wire.requests.size
            f.beforeSql = { sql -> assertFalse(sql.contains("admin_step_up_grants"), "Replay must precede the proof/grant branch") }
            applied(f.send(f.input(attempt), handler), grant.grantId)
            applied(f.send(f.input(attempt, fresh.token), handler), grant.grantId)
            assertFalse(used(f, fresh.grantId))
            assertEquals(snapshot, f.state())
            assertEquals(calls, wire.requests.size)
            assertEquals(1, wire.requests.count { it.kind == "PUT" })
            assertEquals(2, f.observer.queryForObject("SELECT count(*) FROM audit_log WHERE complaint_data_scope_id = ? AND entity_id = ? " +
                "AND actor_user_id = ? AND complaint_actor_kind = 'ADMIN' AND detail = '{\"version\":1}'::jsonb", Int::class.java,
                f.scope.id, parent.toString(), f.base.ordinary.userId))
        }
        wire.assertClientsClosed()
    }

    @Test
    fun `receipt replay still requires normal JWT and current enabled DB ADMIN and changed tuple is not replay`() = fixture { f ->
        val attempt = f.attempt(f.report())
        val grant = f.proof()
        val wire = f.wire(attempt, grant.grantId)
        f.factory(wire).use { publishers ->
            val handler = f.http(publishers)
            applied(f.send(f.input(attempt, grant.token), handler), grant.grantId)
            val before = f.state()
            for ((column, value, status, code) in listOf(
                listOf("role", "USER", 403, "FORBIDDEN"), listOf("enabled", false, 401, "UNAUTHORIZED"),
                listOf("credential_version", f.user().credentialVersion + 1, 401, "UNAUTHORIZED"),
            )) {
                val user = f.user()
                // Fixed allowlisted fixture columns; no request-derived SQL.
                val name = column as String
                f.observer.update("UPDATE users SET $name = ? WHERE id = ?", value, user.id)
                try { problem(f.send(f.input(attempt), handler), status as Int, code as String) }
                finally { f.observer.update("UPDATE users SET role = ?, enabled = ?, credential_version = ? WHERE id = ?", user.role.name, user.enabled, user.credentialVersion, user.id) }
            }
            problem(f.send(f.input(attempt, bearer = f.creator.token), handler), 401, "UNAUTHORIZED")
            problem(f.send(f.input(attempt, bearer = "not-a-token"), handler), 401, "UNAUTHORIZED")
            val changed = f.attempt(attempt.id, version = 2, key = attempt.key)
            problem(f.send(f.input(changed), handler), 409, "IDEMPOTENCY_KEY_REUSED")
            assertEquals(before, f.state())
        }
    }

    @Test
    fun `source proof and missing proof cannot authorize a new complaint delete or create a terminal receipt`() = fixture { f ->
        val attempt = f.attempt(f.report())
        val source = f.proof(ScopedAdminStepUpScope.SOURCE)
        val wire = f.wire(attempt, source.grantId)
        val before = f.state()
        f.factory(wire).use { publishers ->
            val handler = f.http(publishers)
            problem(f.send(f.input(attempt), handler), 401, "ADMIN_STEP_UP_REQUIRED")
            problem(f.send(f.input(attempt, source.token), handler), 401, "ADMIN_STEP_UP_REQUIRED")
            assertEquals(before, f.state())
            assertFalse(used(f, source.grantId))
            assertTrue(wire.requests.isEmpty()); assertTrue(wire.kms.requests.isEmpty())
        }
    }

    @Test
    fun `NOTICE missing stale and pending are receipted terminal rejections with original consumed grant only`() = fixture { f ->
        val report = f.report()
        val notice = f.creator.replyNotice()
        val pending = f.report()
        f.observer.update("UPDATE complaint_resource_ids SET state = 'DELETION_PENDING' WHERE id = ?", pending) // Comparison only, not AUTH work.
        val attempts = listOf(
            Triple(f.attempt(notice), 404, "COMPLAINT_NOT_FOUND"), Triple(f.attempt(UUID.randomUUID()), 404, "COMPLAINT_NOT_FOUND"),
            Triple(f.attempt(report, 2), 412, "PRECONDITION_FAILED"), Triple(f.attempt(pending), 409, "COMPLAINT_DELETION_PENDING"),
        )
        for ((attempt, status, code) in attempts) {
            val grant = f.proof()
            val wire = f.wire(attempt, grant.grantId)
            val before = f.state()
            val counters = f.counters()
            f.factory(wire).use { publishers ->
                val handler = f.http(publishers)
                val rejected = f.send(f.input(attempt, grant.token), handler)
                problem(rejected, status, code, grant.grantId)
                val replay = f.send(f.input(attempt, "not-a-new-proof"), handler)
                problem(replay, status, code, grant.grantId)
                assertArrayEquals(rejected.contentAsByteArray, replay.contentAsByteArray)
                for (table in listOf("complaints", "complaint_resource_ids", "complaint_installation_ids", "app_installations", "complaint_journal_publications",
                    "complaint_recovery_capacity_reservations", "complaint_deletion_journal_applied", "audit")) assertEquals(before.getValue(table), f.state().getValue(table), table)
                f.assertCounterDelta(counters, actual = OwnerDeleteLiteralCharges.receipt)
                assertTrue(used(f, grant.grantId)); assertTrue(wire.requests.isEmpty()); assertTrue(wire.kms.requests.isEmpty())
            }
        }
    }

    @Test
    fun `known phase two 503 retains original grant and PREPARED retry bypasses proof admission and later ETag`() = fixture { f ->
        val attempt = f.attempt(f.report())
        val grant = f.proof()
        val wire = f.wire(attempt, grant.grantId)
        wire.respond = { errorReply(503) }
        f.factory(wire).use { publishers ->
            val handler = f.http(publishers)
            problem(f.send(f.input(attempt, grant.token), handler), 503, "SERVICE_UNAVAILABLE", grant.grantId)
            assertEquals("AUTHORIZED_DELETE", receiptState(f, attempt)); assertEquals("PREPARED", publicationState(f)); assertTrue(present(f, attempt.id))
            val fresh = f.proof()
            val admitted = semanticEvents(f)
            // Synthetic later-state change: irrevocable deletion may not revalidate the historical tag.
            f.observer.update("UPDATE complaints SET version = version + 1 WHERE id = ?", attempt.id)
            f.beforeSql = { sql -> assertFalse(sql.contains("admin_step_up_grants")) }
            wire.respond = wire::statefulReply
            applied(f.send(f.input(attempt, fresh.token), handler), grant.grantId)
            assertEquals(admitted, semanticEvents(f)); assertFalse(used(f, fresh.grantId))
        }
        wire.assertClientsClosed()
    }

    @Test
    fun `VERIFIED continuation needs no further provider call and pending content survives failed APPLY`() = fixture { f ->
        val attempt = f.attempt(f.report())
        val grant = f.proof()
        val wire = f.wire(attempt, grant.grantId)
        f.factory(wire).use { publishers ->
            val handler = f.http(publishers)
            f.beforeSql = { sql -> if (sql.startsWith("DELETE FROM complaints")) throw SyntheticInstallationEnrollmentFailure() }
            problem(f.send(f.input(attempt, grant.token), handler), 503, "SERVICE_UNAVAILABLE", grant.grantId)
            assertEquals("VERIFIED", publicationState(f)); assertEquals("AUTHORIZED_DELETE", receiptState(f, attempt)); assertTrue(present(f, attempt.id))
            f.beforeSql = {}
            assertInstanceOf(CommittedTestAdminDeleteWork.RecordedVerified::class.java, f.reload(attempt))
            val calls = wire.requests.size
            wire.beforePrepare = { error("Recorded VERIFIED retry must not access S3/KMS") }
            applied(f.send(f.input(attempt), handler), grant.grantId)
            assertEquals(calls, wire.requests.size)
        }
    }

    @Test
    fun `unconfirmed authorization tail fault neither guesses consumption nor publishes but exact retry observes original grant`() = fixture { f ->
        val attempt = f.attempt(f.report())
        val grant = f.proof()
        val wire = f.wire(attempt, grant.grantId)
        val once = AtomicBoolean()
        f.afterSql = { sql -> if (sql == "AUDIT" && once.compareAndSet(false, true)) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit(): Unit = throw SyntheticInstallationEnrollmentFailure()
            })
        } }
        f.factory(wire).use { publishers ->
            val handler = f.http(publishers)
            problem(f.send(f.input(attempt, grant.token), handler), 503, "SERVICE_UNAVAILABLE")
            assertEquals("AUTHORIZED_DELETE", receiptState(f, attempt)); assertTrue(used(f, grant.grantId)); assertTrue(wire.requests.isEmpty())
            f.afterSql = {}
            applied(f.send(f.input(attempt), handler), grant.grantId)
        }
    }

    private fun fixture(test: (ComplaintAdminDeleteFixture) -> Unit) = withAdminDelete(database.value, test)
    private fun receiptState(f: ComplaintAdminDeleteFixture, attempt: AdminDeleteFixtureAttempt) = f.scalar(
        "SELECT state FROM complaint_idempotency_receipts WHERE actor_kind = 'ADMIN' AND actor_id = ? AND idempotency_key = ?", f.base.ordinary.userId, attempt.key)
    private fun publicationState(f: ComplaintAdminDeleteFixture) = f.scalar("SELECT state FROM complaint_journal_publications WHERE data_scope_id = ?", f.scope.id)
    private fun used(f: ComplaintAdminDeleteFixture, id: UUID) = f.observer.queryForObject("SELECT used_at IS NOT NULL FROM admin_step_up_grants WHERE id = ?", Boolean::class.java, id) == true
    private fun present(f: ComplaintAdminDeleteFixture, id: UUID) = f.observeOne("SELECT EXISTS (SELECT 1 FROM complaints WHERE id = ?)", id) { it.getBoolean(1) }
    private fun content(f: ComplaintAdminDeleteFixture, id: UUID) = f.scalar("SELECT to_jsonb(c)::text FROM complaints c WHERE id = ?", id)
    private fun semanticEvents(f: ComplaintAdminDeleteFixture): Int = lifecycleField(checkNotNull(lifecycleField(f.ingress, "semantics")), "events") as Int
    private fun applied(response: MockHttpServletResponse, grantId: UUID) {
        assertEquals(204, response.status); assertEquals(0, response.contentAsByteArray.size)
        for (name in listOf("ETag", "Location", "Content-Type", "Content-Length", "Transfer-Encoding")) assertNull(response.getHeader(name), name)
        assertEquals("no-store, no-transform", response.getHeader("Cache-Control")); assertEquals("1", response.getHeader("X-Kira-Complaint-Contract"))
        association(response, grantId)
    }
    private fun problem(response: MockHttpServletResponse, status: Int, code: String, grantId: UUID? = null) {
        assertEquals(status, response.status); assertTrue(response.contentAsString.contains("\"code\":\"$code\"")); assertTrue(response.contentAsByteArray.size <= 32 * 1024)
        assertNull(response.getHeader("ETag")); assertEquals("no-store, no-transform", response.getHeader("Cache-Control")); association(response, grantId)
        if (status == 401) assertEquals("Bearer realm=\"kira-complaints\"", response.getHeader("WWW-Authenticate"))
    }
    private fun association(response: MockHttpServletResponse, grantId: UUID?) {
        assertEquals(grantId?.let { "true" }, response.getHeader(ComplaintAdminDeleteHttpHandler.CONSUMED_HEADER))
        assertEquals(grantId?.toString(), response.getHeader(ComplaintAdminDeleteHttpHandler.CONSUMED_GRANT_HEADER))
    }
}
