package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.domain.ComplaintCapacityCharges
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationExceptionV1
import me.manga.kira.backend.complaint.journal.TestOwnerDeleteJournalPublisherFixture
import me.manga.kira.backend.security.TestAdminDeleteJournalTupleV1
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

/** Lower old-snapshot cases only. No registered scan, deployment restore or provider-policy qualification. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class ComplaintAdminDeleteRecoveryIT {
    private val database = lazy { PgLifecycleDatabaseFixture(ComplaintAdminDeleteRecoveryIT::class.java).also { it.start() } }
    @AfterAll fun closeDatabase() { if (database.isInitialized()) database.value.close() }

    @Test
    fun `genuine old-backup journal reconstruction creates no user grant or installation credential and preserves original scalar`() = fixture { f ->
        val attempt = f.attempt(f.report())
        val grant = f.proof()
        val wire = f.wire(attempt, grant.grantId)
        f.factory(wire).use { publishers ->
            val work = f.prepared(attempt, grant.token, publishers)
            publishers.reserve().use { it.publish(work) }
            f.existing.transaction { sql ->
                // Explicit lost-snapshot comparison rows only. These edits issue no work/verification/recovery capability.
                assertEquals(2, sql.update("DELETE FROM complaint_idempotency_receipts WHERE data_scope_id = ?", f.scope.id))
                assertEquals(1, sql.update("DELETE FROM complaint_recovery_capacity_reservations WHERE data_scope_id = ?", f.scope.id))
                assertEquals(1, sql.update("DELETE FROM complaint_journal_publications WHERE data_scope_id = ?", f.scope.id))
                assertEquals(1, sql.update("DELETE FROM complaints WHERE id = ?", attempt.id))
                assertEquals(1, sql.update("DELETE FROM complaint_resource_ids WHERE id = ?", attempt.id))
                assertEquals(1, sql.update("DELETE FROM app_installations WHERE id = ?", f.creator.actor.id))
                assertEquals(1, sql.update("DELETE FROM complaint_installation_ids WHERE id = ?", f.creator.actor.id))
                assertEquals(1, sql.update("DELETE FROM audit_log WHERE actor_user_id = ?", f.base.ordinary.userId))
                assertEquals(1, sql.update("DELETE FROM admin_step_up_grants WHERE id = ?", grant.grantId))
                assertEquals(1, sql.update("DELETE FROM users WHERE id = ?", f.base.ordinary.userId))
                forgetPaidRows(sql, OwnerDeleteLiteralCharges.missingBookkeeping + OwnerDeleteLiteralCharges.receipt +
                    OwnerDeleteLiteralCharges.installation + OwnerDeleteLiteralCharges.credential + OwnerDeleteLiteralCharges.resource +
                    OwnerDeleteLiteralCharges.content + OwnerDeleteLiteralCharges.audit + ComplaintCapacityCharges.MODERATION_GRANT,
                    OwnerDeleteLiteralCharges.promise)
            }
            val before = f.counters()
            val calls = wire.requests.size
            val readback = publishers.readExisting(wire.event.adminTuple, attempt.id, wire.event.route.routingKeyId)
            assertEquals(listOf("LIST", "GET"), wire.requests.drop(calls).map { it.kind })
            f.statements.clear()
            f.phases.recover(readback)
            f.assertReleased()
            f.assertCounterDelta(before, OwnerDeleteLiteralCharges.missingBookkeeping, OwnerDeleteLiteralCharges.promise, OwnerDeleteLiteralCharges.reconstructAbsent)
            assertEquals("RECOVERY_RESERVED", f.scalar("SELECT state FROM complaint_installation_ids WHERE id = ?", f.creator.actor.id))
            assertEquals("DELETED", f.scalar("SELECT state FROM complaint_resource_ids WHERE id = ?", attempt.id))
            assertEquals(0, count(f, "SELECT count(*) FROM users WHERE id = ?", f.base.ordinary.userId))
            assertEquals(0, count(f, "SELECT count(*) FROM admin_step_up_grants WHERE id = ?", grant.grantId))
            assertEquals(0, count(f, "SELECT count(*) FROM app_installations WHERE id = ?", f.creator.actor.id))
            assertEquals(0, count(f, "SELECT count(*) FROM complaints WHERE id = ?", attempt.id))
            assertEquals(grant.grantId.toString(), f.scalar("SELECT consumed_grant_id::text FROM complaint_idempotency_receipts WHERE actor_kind = 'ADMIN' AND idempotency_key = ?", attempt.key))
            assertEquals("COMPLETED", f.scalar("SELECT state FROM complaint_idempotency_receipts WHERE actor_kind = 'ADMIN' AND idempotency_key = ?", attempt.key))
            assertEquals(1, count(f, "SELECT count(*) FROM audit_log WHERE complaint_data_scope_id = ? AND action = 'COMPLAINT_RECOVERY_APPLIED' " +
                "AND actor_user_id IS NULL AND complaint_actor_kind = 'SYSTEM' AND detail = '{}'::jsonb", f.scope.id))
            val claim = f.statements.indexOfFirst { it.startsWith("INSERT INTO complaint_idempotency_receipts") }
            val publication = f.statements.indexOfFirst { it.contains("FROM complaint_journal_publications") }
            assertTrue(claim >= 0 && publication > claim, "Recovery cannot claim an absent API receipt below a publication lock")
            val snapshot = f.state()
            f.phases.recover(publishers.readExisting(wire.event.adminTuple, attempt.id, wire.event.route.routingKeyId))
            assertEquals(snapshot, f.state())
            val refused = f.send(f.input(attempt), f.http(publishers))
            assertEquals(401, refused.status, "Historical journal actor does not resurrect normal JWT authority")
            assertEquals(snapshot, f.state())
        }
        wire.assertClientsClosed()
    }

    @Test
    fun `retained-key alias recovery never completes primary receipt nor rewrites primary proof`() = fixture { f ->
        val attempt = f.attempt(f.report())
        val grant = f.proof()
        val wire = f.wire(attempt, grant.grantId)
        val credentials = f.rows("app_installations")
        f.factory(wire).use { publishers ->
            f.prepared(attempt, grant.token, publishers)
            val primaryRows = f.rows("complaint_journal_publications")
            val primaryReceipts = f.rows("complaint_idempotency_receipts")
            val aliases = f.routing.derive(wire.event.adminTuple).candidates().filter { it != wire.event.route }
                .map { f.codec.canonicalizeAdmin(wire.event.adminTuple, attempt.id, it.routingKeyId) }
            assertEquals(3, aliases.size)
            for ((index, event) in aliases.withIndex()) {
                wire.objects.add(wire.objectFor(wire.envelope(event), event, "admin-alias-${index + 1}"))
                f.phases.recover(publishers.readExisting(event.adminTuple, attempt.id, event.route.routingKeyId))
                assertEquals(primaryRows, f.rows("complaint_journal_publications"))
                assertEquals(primaryReceipts, f.rows("complaint_idempotency_receipts"))
                assertEquals(credentials, f.rows("app_installations"))
                val snapshot = f.state()
                f.phases.recover(publishers.readExisting(event.adminTuple, attempt.id, event.route.routingKeyId))
                assertEquals(snapshot, f.state())
            }
            assertEquals(204, f.send(f.input(attempt), f.http(publishers)).status)
            assertEquals(4, f.rows("complaint_deletion_journal_applied").size)
            assertEquals(TestOwnerDeleteJournalPublisherFixture.VERSION, f.scalar("SELECT external_object_version FROM complaint_idempotency_receipts " +
                "WHERE actor_kind = 'ADMIN' AND idempotency_key = ?", attempt.key))
            assertEquals(grant.grantId.toString(), f.scalar("SELECT consumed_grant_id::text FROM complaint_idempotency_receipts WHERE actor_kind = 'ADMIN' AND idempotency_key = ?", attempt.key))
        }
        wire.assertClientsClosed()
    }

    @Test
    fun `readback rejects a different grant or resolved owner even when deterministic route collides`() = fixture { f ->
        val attempt = f.attempt(f.report())
        val grant = f.proof()
        val wire = f.wire(attempt, grant.grantId)
        f.factory(wire).use { publishers ->
            val work = f.prepared(attempt, grant.token, publishers)
            publishers.reserve().use { it.publish(work) }
            val original = wire.event.adminTuple
            val before = f.state()
            for ((changedGrant, changedOwner) in listOf(UUID.randomUUID() to original.ownerInstallationId, grant.grantId to UUID.randomUUID())) {
                val other = TestAdminDeleteJournalTupleV1(original.epoch, original.actorId, original.operationKey, original.fingerprintBytes(), original.scope, changedGrant, changedOwner)
                assertEquals(f.routing.derive(original).active, f.routing.derive(other).active)
                assertThrows<JournalPublicationExceptionV1> { publishers.readExisting(other, attempt.id, wire.event.route.routingKeyId) }
                assertEquals(before, f.state())
            }
            assertTrue(f.rows("complaint_deletion_journal_applied").isEmpty())
            assertFalse(f.rows("complaints").isEmpty())
        }
    }

    private fun fixture(test: (ComplaintAdminDeleteFixture) -> Unit) = withAdminDelete(database.value, test)
    private fun count(f: ComplaintAdminDeleteFixture, sql: String, vararg values: Any) = f.observer.queryForObject(sql, Int::class.java, *values)
    private fun forgetPaidRows(sql: JdbcTemplate, actual: ComplaintCapacityVector, promised: ComplaintCapacityVector) {
        for (counter in ComplaintCapacityCounter.entries) {
            if (actual[counter] == 0L && promised[counter] == 0L) continue
            assertEquals(1, sql.update("UPDATE complaint_capacity_counters SET free_units = free_units + ? + ?, actual_units = actual_units - ?, " +
                "recovery_reserved_units = recovery_reserved_units - ? WHERE name = ? AND actual_units >= ? AND recovery_reserved_units >= ?",
                actual[counter], promised[counter], actual[counter], promised[counter], counter.storedName, actual[counter], promised[counter]))
        }
    }
}
