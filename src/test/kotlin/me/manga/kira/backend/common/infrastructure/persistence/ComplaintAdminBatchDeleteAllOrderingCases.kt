package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteReceipt
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteRejection
import me.manga.kira.backend.complaint.domain.InstallationDeletionCandidate
import me.manga.kira.backend.complaint.domain.InstallationDeletionPreflightResult
import me.manga.kira.backend.complaint.infrastructure.AdminDeletePersistenceSql
import me.manga.kira.backend.complaint.infrastructure.CommittedOwnerDeleteAllWork
import me.manga.kira.backend.complaint.infrastructure.CommittedTestAdminDeleteWork
import me.manga.kira.backend.complaint.infrastructure.OwnerDeleteAllPersistenceSql
import me.manga.kira.backend.complaint.infrastructure.TestAdminDeleteAuthorizationV1
import me.manga.kira.backend.complaint.infrastructure.VersionBoundTestOwnerDeleteAllConfigurationV1
import me.manga.kira.backend.security.InstallationEnrollmentCredentials
import me.manga.kira.backend.security.historyTestRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Actual held AUTH/SQL wait in both orderings. No two-caller fixture invokes a global-release assertion while the other owns SQL. */
internal object ComplaintAdminBatchDeleteAllOrderingCases {
    fun run(database: PgLifecycleDatabaseFixture) {
        for (batchFirst in listOf(true, false)) withAdminBatchDelete(database) { f ->
            val c = f.core
            val targets = List(2) { c.report() }
            val attempt = f.attempt(targets.map { it to 1L }); val grant = c.proof(); val wire = f.wire(attempt, grant.grantId)
            val all = VersionBoundTestOwnerDeleteAllConfigurationV1(c.graph, c.ordinaryJdbc, c.jdbc, c.base.ordinary.ownership,
                c.existing.ownership, c.existing.audit, c.noDataKeys)
            val candidate = InstallationDeletionCandidate(InstallationEnrollmentCredentials.prepareSession(c.creator.actor, ByteArray(32) { it.toByte() }), 1, UUID.randomUUID())
            val sql = OwnerDeleteAllPersistenceSql.test(c.scope)
            assertEquals("SELECT NOT EXISTS (SELECT 1 FROM complaint_journal_publications WHERE state = 'PREPARED')\n" +
                "    AND NOT EXISTS (SELECT 1 FROM complaint_journal_publications WHERE state = 'VERIFIED')", sql.NO_PENDING)
            val winnerPath = if (batchFirst) PersistencePhasePath.COMPLAINT_ADMIN_DELETE_AUTHORIZE else PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_AUTHORIZE
            val loserPath = if (batchFirst) PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_AUTHORIZE else PersistencePhasePath.COMPLAINT_ADMIN_DELETE_AUTHORIZE
            val winnerLock = if (batchFirst) AdminDeletePersistenceSql.LOCK_INSTALLATION else sql.LOCK_INSTALLATION_ID
            val winnerPid = AtomicInteger(); val loserPid = AtomicInteger(); val loserEntering = CountDownLatch(1); val once = AtomicBoolean()
            c.factory(wire).use { batchPublishers ->
                // Both named factories share the same finite J registry. Neither publishes here.
                wire.factory(all.authorizationStore, c.lanes).use { allPublishers ->
                    fun authorizeBatch(): TestAdminDeleteAuthorizationV1 = batchPublishers.reserve().use {
                        c.ingress.withIngress(f.input(attempt, grant.token)) { context ->
                            c.ingress.startAdminBatchDelete(context)
                            val identity = c.decoder.decode(c.token)
                            assertNull(c.reads.authenticate(identity).verdict.failure)
                            val preflight = c.reads.preflight(identity, attempt.candidate.tuple)
                            val admitted = c.ingress.admitAdminBatchDelete(context, attempt.candidate.tuple)
                            c.phases.authorize(identity, attempt.candidate, preflight, grant.token, admitted)
                        }
                    }
                    fun authorizeAll(): CommittedOwnerDeleteAllWork = allPublishers.reserve().use {
                        c.ingress.withIngress(historyTestRequest()) { context ->
                            c.ingress.startOwnerDeleteAll(context)
                            val observed = assertInstanceOf(InstallationDeletionPreflightResult.Active::class.java, all.preflights.preflight(candidate))
                            all.authorization.authorize(candidate, observed, c.ingress.admitOwnerDeleteAll(context, observed))
                        }
                    }
                    OwnedCallerTestScope().use { callers ->
                        val held = callers.gate()
                        c.afterSql = { statement ->
                            val phase = checkNotNull(PersistencePhaseOwnership.current())
                            if (lifecycleField(phase, "path") == winnerPath && statement == winnerLock && once.compareAndSet(false, true)) {
                                val holder = TransactionSynchronizationManager.getResource(c.existing.pool) as ConnectionHolder
                                winnerPid.set(holder.connection.createStatement().use { s -> s.executeQuery("SELECT pg_backend_pid()").use { r -> check(r.next()); r.getInt(1) } })
                                held.hold()
                            }
                        }
                        c.beforeSql = {
                            val phase = checkNotNull(PersistencePhaseOwnership.current())
                            if (lifecycleField(phase, "path") == loserPath && loserPid.get() == 0) {
                                val holder = TransactionSynchronizationManager.getResource(c.existing.pool) as ConnectionHolder
                                loserPid.set(holder.connection.createStatement().use { s -> s.executeQuery("SELECT pg_backend_pid()").use { r -> check(r.next()); r.getInt(1) } })
                                loserEntering.countDown()
                            }
                        }
                        val winner = callers.launch { if (batchFirst) authorizeBatch() else authorizeAll() }
                        held.awaitEntered()
                        val loser = callers.launch { if (batchFirst) runCatching { authorizeAll() } else authorizeBatch() }
                        assertTrue(loserEntering.await(3, TimeUnit.SECONDS))
                        assertTrue(c.run.awaitBlocked(loserPid.get()), "The second actual AUTH must be blocked while the first retains its owner/controls.")
                        assertTrue(c.observeOne("SELECT ?::integer = ANY(pg_blocking_pids(?::integer))", winnerPid.get(), loserPid.get()) { it.getBoolean(1) })
                        held.release()
                        if (batchFirst) {
                            assertInstanceOf(CommittedTestAdminDeleteWork.Prepared::class.java, assertInstanceOf(TestAdminDeleteAuthorizationV1.Continue::class.java, winner.value()).work)
                            val refused = loser.value() as Result<*>
                            val failure = assertInstanceOf(PersistencePhaseException::class.java, refused.exceptionOrNull())
                            assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, failure.databaseOutcome); assertTrue(failure.cleanupProven)
                            assertTrue(c.statements.contains(sql.NO_PENDING), "The loser must exercise the unchanged global pending guard.")
                        } else {
                            assertInstanceOf(CommittedOwnerDeleteAllWork.Prepared::class.java, winner.value())
                            val result = assertInstanceOf(TestAdminDeleteAuthorizationV1.Completed::class.java, loser.value())
                            assertEquals(ComplaintAdminDeleteRejection.COMPLAINT_DELETION_PENDING,
                                assertInstanceOf(ComplaintAdminDeleteReceipt.Rejected::class.java, result.receipt).code)
                            assertEquals(grant.grantId, result.receipt.consumedGrantId)
                        }
                    }
                }
            }
            c.beforeSql = {}; c.afterSql = {}; c.assertReleased()
            assertEquals(1, c.rows("complaint_journal_publications").size)
            assertEquals(if (batchFirst) "ADMIN_BATCH_DELETE" else "OWNER_DELETE_ALL", c.scalar("SELECT event_kind FROM complaint_journal_publications WHERE data_scope_id = ?", c.scope.id))
            assertEquals(2, c.rows("complaints").size)
            assertEquals(if (batchFirst) "ACTIVE" else "DELETION_PENDING", c.scalar("SELECT state FROM complaint_installation_ids WHERE id = ?", c.creator.actor.id))
            assertTrue(wire.requests.isEmpty()); assertTrue(wire.kms.requests.isEmpty())
            wire.assertClientsClosed()
        }
    }
}
