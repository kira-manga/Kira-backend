package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.OwnedCallerTestScope
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseDeadline
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseSession
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseSettings
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleTlsCommitForwarder
import me.manga.kira.backend.common.infrastructure.persistence.StepUpPhaseObservation
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationExceptionV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Connection
import java.sql.Timestamp
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Genuine committed flags, withheld encrypted COMMIT reply, native UNKNOWN, and a preconstructed real exchange. */
internal fun assertInitialAdmissionLostCommitResponse(tls: VersionBoundPersistenceConnectedFixture, forwarder: PgLifecycleTlsCommitForwarder) =
    withInitialAdmission(tls) { f ->
        assertEquals(forwarder.port, f.runtime.endpointPort)
        f.withExchange { exchange ->
            val candidate = f.candidate()
            val original = f.begin()
            val unchanged = f.nonControlImage()
            val controls = f.controls(preserved = true)
            val selected = AtomicReference<StepUpPhaseObservation?>()
            val session = AtomicReference<PgLifecycleDatabaseSession?>()
            val callerCompleted = AtomicBoolean()
            OwnedCallerTestScope().use { callers ->
                val start = callers.gate()
                val witness = callers.launch {
                    requireConnectionFree()
                    try {
                        checkNotNull(f.observer.dataSource).connection.use { observer ->
                            observer.setNetworkTimeout({ command -> command.run() }, 2_000)
                            start.hold()
                            val observed = checkNotNull(selected.get())
                            forwarder.awaitHeld()
                            assertFalse(callerCompleted.get())
                            assertFalse(observed.lease.completion.quiescent())
                            assertEquals(PersistenceDatabaseOutcome.NONE, observed.phase.databaseOutcome())
                            assertInitialReleaseCommitVisible(observer, f.p.scope, checkNotNull(session.get()), observed.identity.second)
                            assertEquals(unchanged, f.p.image(observer).filterKeys { it !in setOf("global", "complaint_journal_control") })
                            // The SQL flags are genuinely open, but neither native COMMIT acknowledgment nor cleanup has returned.
                            f.assertNoLatch()
                            exchange.unavailable(candidate)
                            assertTrue(exchange.jdbc.calls.isEmpty())
                            assertTrue(exchange.identityImage().values.all { it.isEmpty() })
                            assertFalse(callerCompleted.get())
                            assertEquals(PersistenceDatabaseOutcome.NONE, observed.lease.state.context.transaction.databaseOutcome())
                            assertFalse(observed.lease.state.context.transaction.uncertain())
                            forwarder.requireHeld()
                            forwarder.loseHeldResponse() // Real channels close only AFTER independent durable SQL/latch observations.
                        }
                    } catch (problem: Throwable) {
                        runCatching(forwarder::close).exceptionOrNull()?.let { if (it !== problem) problem.addSuppressed(it) }
                        throw problem
                    }
                    requireConnectionFree()
                    true
                }
                start.awaitEntered()
                f.probe.afterSql = { step -> if (step == "test-initial-admission-release") {
                    val observed = f.probe.observations.getValue(checkNotNull(PersistencePhaseOwnership.current()))
                    assertTrue(selected.compareAndSet(null, observed))
                    TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                        override fun beforeCommit(readOnly: Boolean) {
                            assertFalse(readOnly)
                            assertSame(observed.phase, PersistencePhaseOwnership.current())
                            session.set(initialAdmissionSession(f.p.holder(f.runtime), observed))
                            forwarder.arm(observed.lease, f.runtime.scope)
                            start.release()
                            // Return normally. Only PostgreSQL's subsequent native COMMIT makes the gate writes durable.
                        }
                    })
                } }
                val attempted = try {
                    runCatching { assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> {
                        original.release(S3CatalogReadbackFixture.credentials, S3CatalogReadbackFixture.credentials)
                    } }
                } finally { callerCompleted.set(true); start.release(); f.probe.afterSql = {} }
                val witnessed = runCatching { assertTrue(witness.value()) }
                rethrowSignerRotationFixtureFailures(listOf(attempted, witnessed))
                forwarder.requireLost()
            }
            requireConnectionFree()
            val observed = checkNotNull(selected.get())
            assertEquals(PersistenceDatabaseOutcome.UNKNOWN, observed.phase.databaseOutcome())
            assertEquals(PersistenceDatabaseOutcome.UNKNOWN, observed.lease.state.context.transaction.databaseOutcome())
            assertTrue(observed.lease.state.context.transaction.uncertain())
            assertTrue(observed.lease.completion.quiescent())
            assertTrue(observed.phase.failureException(PersistencePhaseFailureCode.WORK_FAILED).cleanupProven)
            assertSame(original, SignedActivationObservation.active(f.runtime.pools.catalogCoordinator))
            assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { original.requireActualCleanup() }
            f.gates(open = true)
            assertEquals(controls, f.controls(preserved = true))
            assertEquals(unchanged, f.nonControlImage())
            f.assertNoLatch(); exchange.unavailable(candidate); assertTrue(exchange.jdbc.calls.isEmpty())
            assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { f.begin() }
            assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { f.registration.publishInitialAdmission(original) }
            assertEquals(PersistenceDatabaseOutcome.UNKNOWN, observed.phase.databaseOutcome(), "No reread/retry repairs the original UNKNOWN.")
        }
    }

private fun initialAdmissionSession(connection: Connection, observed: StepUpPhaseObservation): PgLifecycleDatabaseSession = connection.createStatement().use { statement ->
    statement.queryTimeout = 1
    statement.executeQuery("SELECT pg_backend_pid(), backend_start, txid_current() FROM pg_stat_activity WHERE pid = pg_backend_pid()").use { row ->
        assertTrue(row.next()); assertEquals(observed.identity.first, row.getInt(1)); assertEquals(observed.identity.second, row.getLong(3))
        PgLifecycleDatabaseSession(row.getInt(1), row.getTimestamp(2).toInstant()).also { assertFalse(row.next()) }
    }
}

private fun assertInitialReleaseCommitVisible(connection: Connection, scope: UUID, session: PgLifecycleDatabaseSession, transaction: Long) {
    connection.prepareStatement("SELECT c.data_scope_id, NOT c.maintenance_closed AND NOT c.creation_closed, c.xmin::text::bigint, " +
        "s.backend_start, s.state, s.xact_start IS NULL, s.backend_xid IS NULL, s.query = 'COMMIT', s.usename, tls.ssl, pg_backend_pid() " +
        "FROM pg_stat_activity s JOIN pg_stat_ssl tls USING (pid) CROSS JOIN complaint_journal_control c " +
        "WHERE s.pid = ? AND s.datname = current_database() AND c.data_scope_id IN ('00000000-0000-0000-0000-000000000000'::uuid, ?) " +
        "ORDER BY c.data_scope_id").use { statement ->
        statement.queryTimeout = 1; statement.setInt(1, session.pid); statement.setObject(2, scope)
        val deadline = PgLifecycleDatabaseDeadline(1_000)
        while (true) {
            val committed = statement.executeQuery().use { rows ->
                val found = linkedSetOf<UUID>()
                var opened = 0
                while (rows.next()) {
                    assertTrue(found.add(rows.getObject(1, UUID::class.java)))
                    assertEquals(Timestamp.from(session.backendStart), rows.getTimestamp(4))
                    assertEquals(PgLifecycleDatabaseSettings.CANDIDATE, rows.getString(9))
                    assertTrue(rows.getBoolean(10)); assertTrue(rows.getInt(11) != session.pid)
                    if (rows.getBoolean(2)) {
                        opened++
                        assertEquals(transaction and 0xffff_ffffL, rows.getLong(3), "Both gate writes belong to the exact original transaction.")
                        assertEquals("idle", rows.getString(5)); assertTrue(rows.getBoolean(6) && rows.getBoolean(7) && rows.getBoolean(8))
                    }
                }
                assertEquals(setOf(UUID(0L, 0L), scope), found)
                assertTrue(opened == 0 || opened == 2, "Global and scoped release must be atomic.")
                opened == 2
            }
            deadline.checkRemaining()
            if (committed) return
            deadline.pause()
        }
    }
}
