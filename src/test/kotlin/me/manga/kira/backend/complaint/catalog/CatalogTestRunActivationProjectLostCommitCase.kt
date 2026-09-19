package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.OwnedCallerTestScope
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseDeadline
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseSession
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseSettings
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleTlsCommitForwarder
import me.manga.kira.backend.common.infrastructure.persistence.ResolvedPersistenceEndpoint
import me.manga.kira.backend.common.infrastructure.persistence.StepUpPhaseObservation
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationReleaseLeafV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunProjectedV1
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Connection
import java.sql.Timestamp
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Real PostgreSQL commit, withheld encrypted reply and real driver UNKNOWN; no deferred constraint or afterCommit failure. */
internal fun assertTestActivationProjectLostCommitResponse(
    tls: VersionBoundPersistenceConnectedFixture,
    forwarder: PgLifecycleTlsCommitForwarder,
) = withPendingProjectionRows(tls) { p ->
    assertEquals(forwarder.port, tls.endpointPort)
    val endpoint: ResolvedPersistenceEndpoint = poolTestField(tls.configuration, "endpoint")
    assertEquals("verify-full", endpoint.driverProperties().getProperty("sslmode"))
    assertEquals("org.postgresql.ssl.LibPQFactory", endpoint.driverProperties().getProperty("sslfactory"))
    p.fresh { failing ->
        assertEquals(tls.endpointPort, failing.endpointPort)
        val process = p.f.rows.evidence.processOn(failing.pools)
        assertArrayEquals(p.f.rows.evidence.process.canonicalBytes(), process.canonicalBytes(), "Fresh PROJECT retains the ORIGINAL full D, including the endpoint.")
        val original = p.begin(failing, process)
        val budget = original.budget
        val probe = p.f.signed.probe(failing)
        val before = p.image()
        val selected = AtomicReference<StepUpPhaseObservation?>()
        val session = AtomicReference<PgLifecycleDatabaseSession?>()
        val returned = AtomicReference<CatalogTestRunProjectedV1?>()
        val callerCompleted = AtomicBoolean()
        val durable = AtomicReference<Map<String, List<String>>?>()
        OwnedCallerTestScope().use { callers ->
            val start = callers.gate()
            val witness = callers.launch {
                requireConnectionFree()
                // Preopened direct observer, never the application holder or an input to the PROJECT owner.
                try {
                    checkNotNull(p.f.rows.observer.dataSource).connection.use { observer ->
                        observer.setNetworkTimeout({ command -> command.run() }, 2_000)
                        start.hold()
                        val observation = checkNotNull(selected.get())
                        forwarder.awaitHeld()
                        assertFalse(callerCompleted.get())
                        assertFalse(observation.lease.completion.quiescent())
                        assertEquals(PersistenceDatabaseOutcome.NONE, observation.phase.databaseOutcome())
                        assertEquals(PersistenceDatabaseOutcome.NONE, observation.lease.state.context.transaction.databaseOutcome())
                        assertProjectCommitVisible(observer, p, checkNotNull(session.get()), observation.identity.second)
                        durable.set(p.assertDurableProjection(observer)) // All ten exact rows, signed tuple/head, paid counters and unused reserve.
                        assertFalse(callerCompleted.get(), "The exact durable SQL effect must be witnessed BEFORE the original caller can return.")
                        assertFalse(observation.lease.completion.quiescent())
                        assertEquals(PersistenceDatabaseOutcome.NONE, observation.phase.databaseOutcome())
                        assertEquals(PersistenceDatabaseOutcome.NONE, observation.lease.state.context.transaction.databaseOutcome(),
                            "Caller-not-returned alone cannot conceal a native timeout that has already made this transaction UNKNOWN.")
                        assertFalse(observation.lease.state.context.transaction.uncertain())
                        forwarder.requireHeld() // Also proves no already-read encrypted chunk escaped after arming.
                        forwarder.loseHeldResponse()
                    }
                } catch (problem: Throwable) {
                    // Failure cleanup only, never accepted as the transport-loss witness; preserve the original assertion too.
                    runCatching(forwarder::close).exceptionOrNull()?.let { if (it !== problem) problem.addSuppressed(it) }
                    throw problem
                }
                requireConnectionFree()
                true
            }
            start.awaitEntered()
            probe.afterSql = { step -> if (p.currentProjectPhase() && step == "test-clear-pending") {
                val observation = checkNotNull(probe.observations[checkNotNull(PersistencePhaseOwnership.current())])
                assertTrue(selected.compareAndSet(null, observation))
                TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun beforeCommit(readOnly: Boolean) {
                        assertFalse(readOnly)
                        assertSame(observation.phase, PersistencePhaseOwnership.current())
                        assertEquals(10L, p.effectCount(p.holder(failing)))
                        assertEquals(before, p.image(), "None of the ten-row effect is durable before the real native commit.")
                        assertTrue(p.f.signed.complete(CatalogTestRunActivationReleaseLeafV1.PROJECT_ARMED))
                        assertEquals(2, p.releasedProjectionReloadCount(probe))
                        session.set(projectSession(p.holder(failing), observation))
                        forwarder.arm(observation.lease, failing.scope)
                        start.release()
                        // Return normally. Only the native driver's following COMMIT and the real server can create the durable effect.
                    }
                })
            } }
            val attempted = try {
                runCatching { assertThrows<CatalogTestRunActivationExceptionV1> { returned.set(p.project(original)) } }
            } finally {
                callerCompleted.set(true)
                start.release()
                probe.afterSql = {}
            }
            val witnessed = runCatching { assertTrue(witness.value()) }
            rethrowSignerRotationFixtureFailures(listOf(attempted, witnessed))
            assertNull(returned.get())
            forwarder.requireLost()
        }

        val observed = checkNotNull(selected.get())
        assertEquals(PROJECT, probe.calls.single { it.step == "test-project" }.path)
        assertEquals(PersistenceDatabaseOutcome.UNKNOWN, observed.phase.databaseOutcome())
        assertEquals(PersistenceDatabaseOutcome.UNKNOWN, observed.lease.state.context.transaction.databaseOutcome(), "Original lower native commit failure, not an injected result flag.")
        assertTrue(observed.lease.state.context.transaction.uncertain())
        assertTrue(observed.lease.completion.quiescent())
        assertTrue(observed.phase.failureException(PersistencePhaseFailureCode.WORK_FAILED).cleanupProven)
        assertSame(observed.phase, ownedCutField(original, "originalPhase"))
        assertTrue(poolTestField<Boolean>(original, "outcomeUncertain"))
        p.assertSticky(original, failing)
        p.assertProjected()
        p.assertProjectOrder(probe)
        assertEquals(checkNotNull(durable.get()), p.image())
        assertFalse(p.f.signed.exists(CatalogTestRunActivationReleaseLeafV1.PROJECT_OUTCOME))
        val custody = p.f.signed.leaves()
        val oldSlot = SignedActivationObservation.active(failing.pools.catalogCoordinator)
        val oldCleanup = observed.phase.failureException(PersistencePhaseFailureCode.WORK_FAILED).cleanupProven

        p.fresh(previous = failing) { cold ->
            assertEquals(tls.endpointPort, cold.endpointPort)
            val coldProcess = p.f.rows.evidence.processOn(cold.pools)
            assertArrayEquals(p.f.rows.evidence.process.canonicalBytes(), coldProcess.canonicalBytes())
            val replay = p.begin(cold, coldProcess)
            p.assertProjected(p.project(replay))
            p.assertReleased(replay, cold)
            val coldProbe = p.f.signed.probe(cold)
            p.assertNoProjectDml(coldProbe)
            assertTrue(coldProbe.calls.any { it.path == PROJECTED_RELOAD })
            assertEquals(checkNotNull(durable.get()), p.image(), "Cold reconciliation cannot recharge, reinsert, rewrite timestamps or churn xmin.")
            assertEquals(custody, p.f.signed.leaves(), "Cold success cannot invent the missing old PROJECT outcome or replace its arm/copies.")
            assertFalse(p.f.signed.exists(CatalogTestRunActivationReleaseLeafV1.PROJECT_OUTCOME))
            assertSame(budget, original.budget)
            assertSame(observed.phase, ownedCutField(original, "originalPhase"))
            assertSame(oldSlot, SignedActivationObservation.active(failing.pools.catalogCoordinator))
            assertEquals(PersistenceDatabaseOutcome.UNKNOWN, observed.phase.databaseOutcome())
            assertEquals(oldCleanup, observed.phase.failureException(PersistencePhaseFailureCode.WORK_FAILED).cleanupProven)
            assertTrue(poolTestField<Boolean>(original, "outcomeUncertain"))
            p.assertSticky(original, failing)
            assertThrows<CatalogTestRunActivationExceptionV1> { original.requireActualCleanup() }
            p.assertReadOnlyProviders() // Exactly the original one Sign/one PUT, unchanged signed bytes, only genuine GETs since.
        }
    }
}

private fun projectSession(connection: Connection, observation: StepUpPhaseObservation): PgLifecycleDatabaseSession = connection.createStatement().use { statement ->
    statement.queryTimeout = 1
    statement.executeQuery("SELECT pg_backend_pid(), backend_start, txid_current() FROM pg_stat_activity WHERE pid = pg_backend_pid()").use { row ->
        assertTrue(row.next())
        assertEquals(observation.identity.first, row.getInt(1))
        assertEquals(observation.identity.second, row.getLong(3))
        PgLifecycleDatabaseSession(row.getInt(1), row.getTimestamp(2).toInstant()).also { assertFalse(row.next()) }
    }
}

private fun assertProjectCommitVisible(
    connection: Connection,
    p: ProjectionActivationObservation,
    session: PgLifecycleDatabaseSession,
    transaction: Long,
) = connection.prepareStatement(
    "SELECT m.projected_at, s.backend_start, s.state, s.xact_start IS NULL, s.backend_xid IS NULL, s.query = 'COMMIT', " +
        "s.usename, tls.ssl, tls.version, tls.bits, pg_backend_pid(), m.xmin::text::bigint, current_database() " +
        "FROM pg_stat_activity s JOIN pg_stat_ssl tls USING (pid) CROSS JOIN complaint_catalog_mutations m " +
        "WHERE s.pid = ? AND s.datname = current_database() AND m.operation_token = ?",
).use { statement ->
    statement.queryTimeout = 1
    statement.setInt(1, session.pid)
    statement.setObject(2, p.f.signed.token)
    val deadline = PgLifecycleDatabaseDeadline(1_000)
    while (true) {
        val committed = statement.executeQuery().use { row ->
            assertTrue(row.next())
            assertEquals(Timestamp.from(session.backendStart), row.getTimestamp(2))
            assertEquals(PgLifecycleDatabaseSettings.CANDIDATE, row.getString(7))
            assertTrue(row.getBoolean(8) && !row.wasNull())
            assertTrue(row.getString(9) in setOf("TLSv1.2", "TLSv1.3") && row.getInt(10) >= 128)
            assertTrue(row.getInt(11) != session.pid)
            assertEquals(PgLifecycleDatabaseSettings.DATABASE, row.getString(13))
            val visible = row.getTimestamp(1) != null
            if (visible) {
                assertEquals("idle", row.getString(3))
                assertTrue(row.getBoolean(4) && row.getBoolean(5) && row.getBoolean(6))
                assertEquals(transaction and 0xffff_ffffL, row.getLong(12), "The marker was written by the selected original PROJECT transaction.")
            }
            assertFalse(row.next())
            visible
        }
        deadline.checkRemaining()
        if (committed) break
        deadline.pause()
    }
}
