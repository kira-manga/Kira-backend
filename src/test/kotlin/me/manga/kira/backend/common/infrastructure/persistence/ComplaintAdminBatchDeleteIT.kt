package me.manga.kira.backend.common.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import me.manga.kira.backend.complaint.api.ComplaintAdminBatchHttpHandler
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.infrastructure.AdminDeletePersistenceSql
import me.manga.kira.backend.complaint.infrastructure.CommittedTestAdminDeleteWork
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherFixture.Companion.errorReply
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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** SOURCE ONLY / NOT RUN. Actual existing PG/JWT/grant/phase owners with synthetic lower comparisons and raw SDK replies. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class ComplaintAdminBatchDeleteIT {
    private val database = lazy { PgLifecycleDatabaseFixture(ComplaintAdminBatchDeleteIT::class.java).also { it.start() } }
    private val mapper = ObjectMapper()
    @AfterAll fun closeDatabase() { if (database.isInitialized()) database.value.close() }

    @Test
    fun mixedThreeTargetTwoOwnerBatchCommitsOneGrantOutboxAndExactUsageBeforeFreeHistoricalReplay() = fixture { f ->
        val c = f.core
        val parent = c.report()
        val reply = c.creator.replyAttempt(parent).also { assertEquals(201, c.creator.reply(it).status) }.id
        val untouched = c.creator.replyAttempt(parent).also { assertEquals(201, c.creator.reply(it).status) }.id
        val otherOwner = UUID.randomUUID()
        val otherToken = c.creator.json(c.creator.exchange(otherOwner, session = false))["accessToken"].asText()
        val other = c.report(otherToken)
        assertEquals(1, c.observer.update("UPDATE complaints SET version = ? WHERE id = ?", Long.MAX_VALUE, parent))
        val attempt = f.attempt(listOf(other to 1L, reply to 1L, parent to Long.MAX_VALUE))
        val grant = c.proof()
        val wire = f.wire(attempt, grant.grantId, listOf(otherOwner, c.creator.actor.id))
        val identities = c.rows("complaint_installation_ids"); val credentials = c.rows("app_installations")
        val unselected = c.scalar("SELECT to_jsonb(c)::text FROM complaints c WHERE id = ?", untouched)
        val before = c.counters()
        wire.beforePrepare = {
            c.assertReleased()
            assertEquals("AUTHORIZED_DELETE", receiptState(c, attempt))
            assertEquals("PREPARED", publicationState(c))
            attempt.ids.forEach { assertTrue(present(c, it)); assertEquals("DELETION_PENDING", resourceState(c, it)) }
            assertTrue(used(c, grant.grantId))
        }
        var deletions = 0
        c.beforeSql = { sql -> if (sql == AdminDeletePersistenceSql.DELETE_CONTENT) {
            deletions++
            assertEquals("VERIFIED", publicationState(c), "The independent observer must see committed VERIFY before any erasure.")
            assertEquals("AUTHORIZED_DELETE", receiptState(c, attempt))
        } }
        c.factory(wire).use { publishers ->
            val handler = f.http(publishers)
            c.observations.clear(); c.sqlCalls.clear()
            applied(f.send(f.input(attempt, grant.token), handler), attempt.ids, grant.grantId)
            assertEquals(3, deletions)
            assertEquals(1, wire.requests.count { it.kind == "PUT" })
            assertEquals(1, c.rows("complaint_journal_publications").size); assertEquals(1, c.rows("complaint_deletion_journal_applied").size)
            assertEquals("ADMIN_BATCH_DELETE", c.scalar("SELECT event_kind FROM complaint_journal_publications WHERE data_scope_id = ?", c.scope.id))
            assertEquals("3", c.scalar("SELECT target_count::text FROM complaint_journal_publications WHERE data_scope_id = ?", c.scope.id))
            attempt.ids.forEach { assertFalse(present(c, it)); assertEquals("DELETED", resourceState(c, it)) }
            assertEquals(identities, c.rows("complaint_installation_ids")); assertEquals(credentials, c.rows("app_installations"))
            assertEquals(unselected, c.scalar("SELECT to_jsonb(c)::text FROM complaints c WHERE id = ?", untouched))
            val owners = listOf(c.creator.actor.id, otherOwner).sortedBy(UUID::toString)
            val expectedLocks = owners.flatMap { listOf(AdminDeletePersistenceSql.LOCK_INSTALLATION to it, AdminDeletePersistenceSql.LOCK_CREDENTIAL to it) } +
                attempt.ids.map { AdminDeletePersistenceSql.LOCK_RESOURCE to it } + attempt.ids.map { AdminDeletePersistenceSql.LOCK_CONTENT to it }
            val selectedSql = expectedLocks.map { it.first }.toSet()
            assertEquals(expectedLocks + expectedLocks, c.sqlCalls.filter { it.first in selectedSql }.map { it.first to it.second.first() },
                "Both AUTH and APPLY must lock all text-sorted owner pairs, then all resources, then all content.")
            c.observations.map { it.second.phase }.distinct().forEach { assertEquals(PersistenceDatabaseOutcome.COMMITTED, it.databaseOutcome()) }
            val literal = OwnerDeleteLiteralCharges
            val auth = literal.receipt + literal.publication + literal.reservation + literal.audit.scaled(3)
            val promise = literal.installation.scaled(2) + literal.resource.scaled(3) + literal.audit.scaled(7) + literal.appliedOnly.scaled(4)
            val use = literal.audit.scaled(3) + literal.appliedOnly
            c.assertCounterDelta(before, auth, promise, use, literal.content.scaled(3))
            assertVector(c, "reserved_amounts", promise); assertVector(c, "converted_amounts", use)
            assertEquals(3L, count(c, "SELECT count(*) FROM audit_log WHERE complaint_data_scope_id = ? AND action = 'COMPLAINT_DELETED' AND complaint_actor_kind = 'ADMIN' AND actor_user_id = ?", c.scope.id, c.base.ordinary.userId))
            assertEquals(0L, count(c, "SELECT count(*) FROM audit_log WHERE complaint_data_scope_id = ? AND action = 'COMPLAINT_RECOVERY_APPLIED'", c.scope.id))
            val fresh = c.proof(); val snapshot = c.state(); val calls = wire.requests.size; val admitted = semanticEvents(c)
            c.beforeSql = { sql -> assertFalse(sql.contains("admin_step_up_grants")) }
            applied(f.send(f.input(attempt), handler), attempt.ids, grant.grantId)
            applied(f.send(f.input(attempt, fresh.token), handler), attempt.ids, grant.grantId)
            assertFalse(used(c, fresh.grantId)); assertEquals(admitted, semanticEvents(c)); assertEquals(calls, wire.requests.size)
            val single = c.attempt(parent, Long.MAX_VALUE, attempt.key)
            assertEquals(409, c.send(c.input(single), c.http(publishers)).status, "Batch1/scalar operation identity never aliases a receipt.")
            assertEquals(snapshot, c.state())
        }
        wire.assertClientsClosed()
    }

    @Test
    fun invalidLastTargetRejectsWholeBatchAndReplaysOneConsumedGrantWithoutAnyPendingOrJournalRows() = fixture { f ->
        val c = f.core
        val ids = List(2) { c.report() }.sortedBy(UUID::toString)
        val missing = UUID.fromString("ffffffff-ffff-4fff-bfff-ffffffffffff")
        val attempts = listOf(f.attempt(listOf(ids.first() to 1L, ids.last() to 2L)) to 412,
            f.attempt(listOf(ids.first() to 1L, missing to 1L)) to 404,
            f.attempt(listOf(ids.first() to 1L, c.creator.replyNotice() to 1L)) to 404)
        for ((attempt, status) in attempts) {
            val grant = c.proof(); val wire = f.wire(attempt, grant.grantId); val before = c.state(); val counters = c.counters()
            c.factory(wire).use { publishers ->
                val handler = f.http(publishers)
                val first = f.send(f.input(attempt, grant.token), handler)
                problem(first, status, grant.grantId)
                val replay = f.send(f.input(attempt, "not-new-authority"), handler)
                problem(replay, status, grant.grantId); assertArrayEquals(first.contentAsByteArray, replay.contentAsByteArray)
                for (table in listOf("complaints", "complaint_resource_ids", "complaint_installation_ids", "app_installations", "complaint_journal_publications", "complaint_recovery_capacity_reservations", "complaint_deletion_journal_applied", "audit"))
                    assertEquals(before.getValue(table), c.state().getValue(table), table)
                c.assertCounterDelta(counters, actual = OwnerDeleteLiteralCharges.receipt)
                assertTrue(used(c, grant.grantId)); assertTrue(wire.requests.isEmpty()); assertTrue(wire.kms.requests.isEmpty())
            }
        }
    }

    @Test
    fun secondPendingAndSecondDeleteFaultsRollbackTheEntireOriginalBatchAndExactRetryDoesNotPartiallyApply() {
        for (authorize in listOf(true, false)) fixture { f ->
            val c = f.core; val attempt = f.attempt(List(3) { c.report() to 1L }); val grant = c.proof(); val wire = f.wire(attempt, grant.grantId)
            c.factory(wire).use { publishers ->
                val handler = f.http(publishers)
                if (!authorize) {
                    val work = f.prepared(attempt, grant.token, publishers)
                    c.phases.verify(publishers.reserve().use { it.publish(work) })
                    assertInstanceOf(CommittedTestAdminDeleteWork.RecordedVerified::class.java, f.reload(attempt))
                }
                val before = c.state(); val providerCalls = wire.requests.size
                var visited = 0
                c.beforeSql = { sql -> if (sql == if (authorize) AdminDeletePersistenceSql.PEND_RESOURCE else AdminDeletePersistenceSql.DELETE_CONTENT) {
                    if (++visited == 2) throw SyntheticInstallationEnrollmentFailure()
                } }
                problem(f.send(f.input(attempt, if (authorize) grant.token else null), handler), 503, if (authorize) null else grant.grantId)
                assertEquals(2, visited); assertEquals(before, c.state()); assertEquals(providerCalls, wire.requests.size)
                val path = if (authorize) PersistencePhasePath.COMPLAINT_ADMIN_DELETE_AUTHORIZE else PersistencePhasePath.COMPLAINT_ADMIN_DELETE_APPLY
                assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, c.observations.last { lifecycleField(it.second.phase, "path") == path }.second.phase.databaseOutcome())
                c.beforeSql = {}
                if (!authorize) wire.beforePrepare = { error("Recorded VERIFIED batch retry must not rebuild a provider.") }
                applied(f.send(f.input(attempt, if (authorize) grant.token else null), handler), attempt.ids, grant.grantId)
                assertEquals(1, c.rows("complaint_deletion_journal_applied").size)
            }
            wire.assertClientsClosed()
        }
    }

    @Test
    fun authorizationAcknowledgmentLossNativeFailureAndVerifyFailureKeepOriginalKeyGrantAndFreeContinuation() {
        for (cut in listOf("AUTH_TAIL", "NATIVE", "VERIFY")) fixture { f ->
            val c = f.core; val attempt = f.attempt(List(2) { c.report() to 1L }); val grant = c.proof(); val wire = f.wire(attempt, grant.grantId)
            val once = AtomicBoolean()
            if (cut == "AUTH_TAIL") c.afterSql = { sql -> if (sql == "AUDIT" && once.compareAndSet(false, true)) {
                TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun afterCommit(): Unit = throw SyntheticInstallationEnrollmentFailure()
                })
            } }
            if (cut == "NATIVE") wire.respond = { errorReply(503) }
            if (cut == "VERIFY") c.beforeSql = { sql -> if (sql == AdminDeletePersistenceSql.RECORD_VERIFIED) throw SyntheticInstallationEnrollmentFailure() }
            c.factory(wire).use { publishers ->
                val handler = f.http(publishers)
                problem(f.send(f.input(attempt, grant.token), handler), 503, if (cut == "AUTH_TAIL") null else grant.grantId)
                assertEquals("AUTHORIZED_DELETE", receiptState(c, attempt)); assertEquals("PREPARED", publicationState(c))
                attempt.ids.forEach { assertTrue(present(c, it)); assertEquals("DELETION_PENDING", resourceState(c, it)) }
                val publication = c.rows("complaint_journal_publications"); val fresh = c.proof(); val admitted = semanticEvents(c)
                c.afterSql = {}; c.beforeSql = { sql -> assertFalse(sql.contains("admin_step_up_grants")) }; wire.respond = wire::statefulReply
                assertEquals(1, publication.size)
                applied(f.send(f.input(attempt, fresh.token), handler), attempt.ids, grant.grantId)
                assertFalse(used(c, fresh.grantId)); assertEquals(admitted, semanticEvents(c))
                assertEquals(1, wire.requests.count { it.kind == "PUT" }); assertEquals(1, c.rows("complaint_recovery_capacity_reservations").size)
                assertEquals(grant.grantId.toString(), c.scalar("SELECT consumed_grant_id::text FROM complaint_idempotency_receipts WHERE actor_kind = 'ADMIN' AND idempotency_key = ?", attempt.key))
            }
            wire.assertClientsClosed()
        }
    }

    @Test
    fun partialOldSnapshotRecoveryPaysOnlyActuallyRebuiltIdentitiesTwoRemovalsAndOneEventSummary() = fixture { f ->
        val c = f.core
        val retained = List(2) { c.report() }
        val absentOwner = UUID.randomUUID()
        val otherToken = c.creator.json(c.creator.exchange(absentOwner, session = false))["accessToken"].asText()
        val absent = c.report(otherToken)
        val attempt = f.attempt((retained + absent).map { it to 1L }); val grant = c.proof()
        val wire = f.wire(attempt, grant.grantId, listOf(c.creator.actor.id, absentOwner))
        val literal = OwnerDeleteLiteralCharges
        val promise = literal.installation.scaled(2) + literal.resource.scaled(3) + literal.audit.scaled(7) + literal.appliedOnly.scaled(4)
        c.factory(wire).use { publishers ->
            val work = f.prepared(attempt, grant.token, publishers)
            publishers.reserve().use { it.publish(work) }
            val grantImage = c.scalar("SELECT to_jsonb(g)::text FROM admin_step_up_grants g WHERE id = ?", grant.grantId)
            val retainedCredential = c.scalar("SELECT to_jsonb(i)::text FROM app_installations i WHERE id = ?", c.creator.actor.id)
            c.existing.transaction { sql ->
                // Explicit lost-snapshot comparison only, never an issued capability or successful APPLY.
                assertEquals(1, sql.update("DELETE FROM complaint_idempotency_receipts WHERE actor_kind = 'ADMIN' AND actor_id = ? AND idempotency_key = ?", c.base.ordinary.userId, attempt.key))
                assertEquals(1, sql.update("DELETE FROM complaint_recovery_capacity_reservations WHERE data_scope_id = ?", c.scope.id))
                assertEquals(1, sql.update("DELETE FROM complaint_journal_publications WHERE data_scope_id = ?", c.scope.id))
                assertEquals(1, sql.update("DELETE FROM complaints WHERE id = ?", absent))
                assertEquals(1, sql.update("DELETE FROM complaint_resource_ids WHERE id = ?", absent))
                assertEquals(1, sql.update("DELETE FROM app_installations WHERE id = ?", absentOwner))
                assertEquals(1, sql.update("DELETE FROM complaint_installation_ids WHERE id = ?", absentOwner))
                assertEquals(3, sql.update("DELETE FROM audit_log WHERE complaint_data_scope_id = ? AND action = 'COMPLAINT_DELETE_AUTHORIZED'", c.scope.id))
                forgetPaidRows(sql, literal.missingBookkeeping + literal.installation + literal.credential + literal.resource + literal.content + literal.audit.scaled(3), promise)
            }
            val before = c.counters()
            c.phases.recover(publishers.readExistingBatch(wire.event.adminBatchTuple, attempt.ids, wire.event.route.routingKeyId))
            c.assertReleased()
            val use = literal.installation + literal.resource + literal.audit.scaled(3) + literal.appliedOnly
            c.assertCounterDelta(before, literal.missingBookkeeping, promise, use, literal.content.scaled(2))
            assertVector(c, "converted_amounts", use)
            assertEquals("RECOVERY_RESERVED", c.scalar("SELECT state FROM complaint_installation_ids WHERE id = ?", absentOwner))
            assertEquals(0L, count(c, "SELECT count(*) FROM app_installations WHERE id = ?", absentOwner))
            attempt.ids.forEach { assertFalse(present(c, it)); assertEquals("DELETED", resourceState(c, it)) }
            assertEquals(retainedCredential, c.scalar("SELECT to_jsonb(i)::text FROM app_installations i WHERE id = ?", c.creator.actor.id))
            assertEquals(grantImage, c.scalar("SELECT to_jsonb(g)::text FROM admin_step_up_grants g WHERE id = ?", grant.grantId))
            val summary = mapper.readTree(checkNotNull(c.scalar("SELECT detail::text FROM audit_log WHERE complaint_data_scope_id = ? AND action = 'COMPLAINT_RECOVERY_APPLIED' AND complaint_actor_kind = 'SYSTEM' AND actor_user_id IS NULL", c.scope.id)))
            assertEquals(setOf("eventId", "removed", "reconstructed", "installation"), summary.fieldNames().asSequence().toSet())
            assertEquals(wire.event.route.eventId, summary["eventId"].asText()); assertEquals(2, summary["removed"].asInt())
            assertEquals(1, summary["reconstructed"].asInt()); assertEquals(1, summary["installation"].asInt())
            val snapshot = c.state()
            c.phases.recover(publishers.readExistingBatch(wire.event.adminBatchTuple, attempt.ids, wire.event.route.routingKeyId))
            assertEquals(snapshot, c.state())
            applied(f.send(f.input(attempt), f.http(publishers)), attempt.ids, grant.grantId)
            assertEquals(snapshot, c.state())
        }
        wire.assertClientsClosed()
    }

    @Test
    fun sharedOwnerAllAndBatchAuthorizeInBothBarrierControlledOrdersWithoutWeakeningGlobalPendingGuard() =
        ComplaintAdminBatchDeleteAllOrderingCases.run(database.value)

    private fun fixture(test: (ComplaintAdminBatchDeleteFixture) -> Unit) = withAdminBatchDelete(database.value, test)
    private fun receiptState(c: ComplaintAdminDeleteFixture, attempt: AdminBatchDeleteFixtureAttempt) = c.scalar("SELECT state FROM complaint_idempotency_receipts WHERE actor_kind = 'ADMIN' AND actor_id = ? AND idempotency_key = ?", c.base.ordinary.userId, attempt.key)
    private fun publicationState(c: ComplaintAdminDeleteFixture) = c.scalar("SELECT state FROM complaint_journal_publications WHERE data_scope_id = ?", c.scope.id)
    private fun resourceState(c: ComplaintAdminDeleteFixture, id: UUID) = c.scalar("SELECT state FROM complaint_resource_ids WHERE id = ?", id)
    private fun present(c: ComplaintAdminDeleteFixture, id: UUID) = c.observeOne("SELECT EXISTS (SELECT 1 FROM complaints WHERE id = ?)", id) { it.getBoolean(1) }
    private fun used(c: ComplaintAdminDeleteFixture, id: UUID) = c.observeOne("SELECT used_at IS NOT NULL FROM admin_step_up_grants WHERE id = ?", id) { it.getBoolean(1) }
    private fun count(c: ComplaintAdminDeleteFixture, sql: String, vararg args: Any) = c.observeOne(sql, *args) { it.getLong(1) }
    private fun semanticEvents(c: ComplaintAdminDeleteFixture) = lifecycleField(checkNotNull(lifecycleField(c.ingress, "semantics")), "events") as Int
    private fun assertVector(c: ComplaintAdminDeleteFixture, column: String, expected: ComplaintCapacityVector) {
        require(column in setOf("reserved_amounts", "converted_amounts"))
        val observed = c.observeOne("SELECT $column FROM complaint_recovery_capacity_reservations WHERE data_scope_id = ?", c.scope.id) { row ->
            val sql = row.getArray(1)
            try { ComplaintCapacityVector.of((sql.array as Array<*>).map { (it as Number).toLong() }.toLongArray()) } finally { sql.free() }
        }
        assertEquals(expected, observed)
    }
    private fun applied(response: MockHttpServletResponse, ids: List<UUID>, grant: UUID) {
        assertEquals(200, response.status)
        assertEquals("{\"items\":[${ids.joinToString(",") { "{\"id\":\"$it\"}" }}]}", response.contentAsString)
        assertEquals(grant.toString(), response.getHeader(ComplaintAdminBatchHttpHandler.CONSUMED_GRANT_HEADER))
        assertEquals("true", response.getHeader(ComplaintAdminBatchHttpHandler.CONSUMED_HEADER))
        assertNull(response.getHeader("ETag")); assertNull(response.getHeader("Location"))
    }
    private fun problem(response: MockHttpServletResponse, status: Int, grant: UUID?) {
        assertEquals(status, response.status); assertFalse(response.contentAsString.contains("items"))
        assertEquals(grant?.toString(), response.getHeader(ComplaintAdminBatchHttpHandler.CONSUMED_GRANT_HEADER))
        assertEquals(if (grant == null) null else "true", response.getHeader(ComplaintAdminBatchHttpHandler.CONSUMED_HEADER))
    }
    private fun forgetPaidRows(sql: JdbcTemplate, actual: ComplaintCapacityVector, promised: ComplaintCapacityVector) {
        for (counter in ComplaintCapacityCounter.entries) {
            if (actual[counter] == 0L && promised[counter] == 0L) continue
            assertEquals(1, sql.update("UPDATE complaint_capacity_counters SET free_units = free_units + ? + ?, actual_units = actual_units - ?, " +
                "recovery_reserved_units = recovery_reserved_units - ? WHERE name = ? AND actual_units >= ? AND recovery_reserved_units >= ?",
                actual[counter], promised[counter], actual[counter], promised[counter], counter.storedName, actual[counter], promised[counter]))
        }
    }
}
