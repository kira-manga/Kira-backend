package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.OwnedCallerTestScope
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceEpochRotationSession
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceJdbcGuardContext
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseDeadline
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseSession
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseSettings
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleTlsCommitForwarder
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveFirstCutV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import java.sql.Connection
import java.sql.Timestamp
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Actual captured epoch+paid binding+lease-clear COMMIT, withheld opaque TLS reply, native UNKNOWN. No supplied result. */
internal fun assertActiveFirstCutLostCommitResponse(tls: VersionBoundPersistenceConnectedFixture, forwarder: PgLifecycleTlsCommitForwarder) =
    withTestActiveFirstCut(tls) { f ->
        assertEquals(forwarder.port, f.runtime.endpointPort)
        val original = f.begin()
        val before = f.counters(); val outside = f.outsideCut(); val global = f.globalImage()
        val native = AtomicReference<PersistenceEpochRotationSession?>()
        val identity = AtomicReference<Pair<PgLifecycleDatabaseSession, Long>?>()
        val returned = AtomicBoolean()
        OwnedCallerTestScope().use { callers ->
            val start = callers.gate()
            val witness = callers.launch {
                requireConnectionFree()
                try {
                    checkNotNull(f.observer.dataSource).connection.use { observer ->
                        observer.setNetworkTimeout({ command -> command.run() }, 2_000)
                        start.hold() // Pre-open observer before the short real native COMMIT read cap begins.
                        val session = checkNotNull(native.get())
                        val exact = checkNotNull(identity.get())
                        forwarder.awaitHeld()
                        assertFalse(returned.get())
                        assertEquals(PersistenceDatabaseOutcome.NONE, session.failure().databaseOutcome)
                        assertFalse(f.nativeEntry().jdbc.terminalCompletion().reclaimed())
                        // One decisive pre-opened SQL witness only while the original native read cap runs.
                        // Full accounting/unchanged-row assertions follow actual UNKNOWN cleanup below.
                        assertFirstCutCommitVisible(observer, f.scope, exact.first, exact.second)
                        assertFalse(poolTestField<Boolean>(original, "issued"))
                        assertFalse(poolTestField<Boolean>(original, "successful"))
                        forwarder.requireHeld()
                        forwarder.loseHeldResponse() // Real channels close only after both durable rows and exact native transaction are witnessed.
                    }
                } catch (problem: Throwable) {
                    runCatching(forwarder::close).exceptionOrNull()?.let { if (it !== problem) problem.addSuppressed(it) }
                    throw problem
                }
                true
            }
            start.awaitEntered()
            f.beforeNativeSample = { session ->
                if (native.get() == null && ownedCutField(session, "stage").toString() == "REREAD") {
                    assertTrue(native.compareAndSet(null, session))
                    val context = poolTestField<PersistenceJdbcGuardContext>(session, "context")
                    assertNull((ownedCutField(context, "frames") as ThreadLocal<*>).get())
                    // Read-only observation on the same original guarded TLS holder. Never changes SQL/results or transaction facts.
                    identity.set(firstCutNativeIdentity(poolTestField(session, "connection")))
                    forwarder.arm(session, f.runtime.scope)
                    start.release()
                }
            }
            val attempted = try {
                runCatching { assertThrows<RuntimeException> { f.capture(original) } }
            } finally {
                returned.set(true); start.release(); f.beforeNativeSample = {}
            }
            val witnessed = runCatching { assertTrue(witness.value()) }
            rethrowSignerRotationFixtureFailures(listOf(attempted, witnessed))
            forwarder.requireLost()
        }
        f.awaitNativeReclaimed()
        val session = checkNotNull(native.get())
        assertSame(session, f.observedNative.get())
        assertEquals(PersistenceDatabaseOutcome.UNKNOWN, session.failure().databaseOutcome)
        assertTrue(f.nativeEntry().jdbc.terminalCompletion().reclaimed())
        assertEquals("CAPTURED", f.control()["rotation_state"]); assertEquals(2L, f.control()["publication_epoch"])
        assertNull(f.control()["lease_owner"]); assertNull(f.control()["lease_expires_at"])
        assertEquals(f.paid()["capture_token"], f.control()["lease_token"])
        f.assertCharge(before); assertEquals(outside, f.outsideCut()); assertEquals(global, f.globalImage())
        val rows = f.controlImage() to f.paidImage(); val charged = f.counters()
        assertThrows<RuntimeException> { TestActiveFirstCutV1.Captured.issue(original) }
        assertThrows<RuntimeException> { original.capture(S3CatalogReadbackFixture.credentials, S3CatalogReadbackFixture.credentials) }
        assertThrows<RuntimeException> { f.begin().capture(S3CatalogReadbackFixture.credentials, S3CatalogReadbackFixture.credentials) }
        assertEquals(rows, f.controlImage() to f.paidImage()); assertEquals(charged, f.counters())
        assertEquals(PersistenceDatabaseOutcome.UNKNOWN, session.failure().databaseOutcome, "Durable slot visibility or later cleanup cannot repair the original UNKNOWN.")
    }

private fun firstCutNativeIdentity(connection: Connection): Pair<PgLifecycleDatabaseSession, Long> = connection.createStatement().use { statement ->
    statement.queryTimeout = 1
    statement.executeQuery("SELECT pg_backend_pid(), backend_start, txid_current() FROM pg_stat_activity WHERE pid = pg_backend_pid()").use { rows ->
        assertTrue(rows.next())
        (PgLifecycleDatabaseSession(rows.getInt(1), rows.getTimestamp(2).toInstant()) to rows.getLong(3)).also { assertFalse(rows.next()) }
    }
}

private fun assertFirstCutCommitVisible(connection: Connection, scope: UUID, session: PgLifecycleDatabaseSession, transaction: Long) {
    connection.prepareStatement(
        "SELECT c.rotation_state, c.publication_epoch, c.lease_owner IS NULL AND c.lease_expires_at IS NULL, " +
            "c.rotation_id = i.operation_token AND c.rotation_capture_owner = i.capture_owner AND c.rotation_capture_token = i.capture_token " +
            "AND c.rotation_captured_at = i.captured_at AND c.rotation_epoch_after = i.epoch_after AND c.lease_token = i.capture_token, " +
            "c.xmin::text::bigint, i.xmin::text::bigint, s.backend_start, s.state, s.xact_start IS NULL, s.backend_xid IS NULL, s.query = 'COMMIT', s.usename, tls.ssl " +
            "FROM pg_stat_activity s JOIN pg_stat_ssl tls USING (pid) CROSS JOIN complaint_journal_control c " +
            "JOIN complaint_test_active_seal_intents i ON i.data_scope_id = c.data_scope_id " +
            "WHERE s.pid = ? AND s.datname = current_database() AND c.data_scope_id = ?").use { statement ->
        statement.queryTimeout = 1
        statement.setInt(1, session.pid); statement.setObject(2, scope)
        val deadline = PgLifecycleDatabaseDeadline(1_000)
        while (true) {
            val visible = statement.executeQuery().use { rows ->
                assertTrue(rows.next())
                assertEquals(Timestamp.from(session.backendStart), rows.getTimestamp(7))
                assertEquals(PgLifecycleDatabaseSettings.CANDIDATE, rows.getString(12)); assertTrue(rows.getBoolean(13))
                val captured = rows.getString(1) == "CAPTURED"
                if (captured) {
                    assertEquals(2L, rows.getLong(2)); assertTrue(rows.getBoolean(3)); assertTrue(rows.getBoolean(4))
                    assertEquals(transaction and 0xffff_ffffL, rows.getLong(5))
                    assertEquals(transaction and 0xffff_ffffL, rows.getLong(6), "Control epoch+lease-clear and paid CAPTURE binding share the exact transaction.")
                    assertEquals("idle", rows.getString(8)); assertTrue(rows.getBoolean(9) && rows.getBoolean(10) && rows.getBoolean(11))
                }
                assertFalse(rows.next()); captured
            }
            deadline.checkRemaining()
            if (visible) return
            deadline.pause()
        }
    }
}
