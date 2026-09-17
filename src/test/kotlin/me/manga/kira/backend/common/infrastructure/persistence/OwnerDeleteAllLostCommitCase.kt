package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.domain.InstallationDeletionCandidate
import me.manga.kira.backend.complaint.domain.OwnerDeleteAllCapacityCharges
import me.manga.kira.backend.complaint.infrastructure.CommittedOwnerDeleteAllWork
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import java.sql.Connection
import java.sql.Timestamp
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Existing protocol-aware relay withholds a REAL warmed COMMIT reply, not a mocked transaction outcome. */
internal fun assertOwnerDeleteAllLostCommitResponse(database: PgLifecycleDatabaseFixture) {
    val warm = PgLifecycleDatabaseWarmControl()
    val case = PgLifecycleDatabaseCase(PgLifecycleDatabaseRecipe.DEFAULT, 0, PgLifecycleDatabaseLane.ORDINARY)
    PgLifecycleDatabaseRelay(database, warm.application, case, warm).use { relay ->
        PgLifecycleDatabaseDiagnostics.preservingFailure(
            diagnostic = { println("DELETE_ALL_COMMIT_RELAY observation=PRE_RELAY_CLEANUP_MIXED ${relay.diagnostic(-1)}") },
        ) {
            relay.start()
            val endpoint = PgLifecycleDatabaseSettings.endpoint(case, relay.port, warm.application)
            withOwnerDeleteAllAuthorization(database, endpoint) { f -> lostCommit(f, relay, warm) }
        }
    }
}

private fun lostCommit(f: OwnerDeleteAllAuthorizationFixture, relay: PgLifecycleDatabaseRelay, warm: PgLifecycleDatabaseWarmControl) {
    val candidate = f.enrolled()
    val expected = f.codec.canonicalize(f.journalTuple(candidate), emptyList())
    val before = f.counters()
    val selected = AtomicReference<StepUpPhaseObservation?>()
    val session = AtomicReference<PgLifecycleDatabaseSession?>()
    val returned = AtomicReference<CommittedOwnerDeleteAllWork.Prepared?>()
    val callerCompleted = AtomicBoolean()
    OwnedCallerTestScope().use { callers ->
        val start = callers.gate()
        val witness = callers.launch {
            // Observer connection is open before the phase starts; it is neither an application pool nor a fallback producer.
            checkNotNull(f.observer.dataSource).connection.use { connection ->
                start.hold()
                val observation = checkNotNull(selected.get())
                warm.awaitHeld(PgLifecycleDatabaseDeadline(2_000), relay::progress)
                assertEquals(observation.identity.first, warm.requireHeld().backendPid.get())
                assertFalse(callerCompleted.get())
                assertFalse(observation.lease.completion.quiescent())
                assertCommittedWhileReplyHeld(connection, candidate, checkNotNull(session.get()), warm.application, expected.canonicalBytes())
                warm.witnessed() // Actual committed-state witness precedes caller failure and client-origin disposal.
                assertFalse(callerCompleted.get())
            }
            true
        }
        start.awaitEntered()
        f.afterStep = { step ->
            if (step == DeleteAllStep.AUDIT) {
                val observation = f.observations.last().second
                selected.set(observation)
                val backend = checkNotNull(f.base.ordinary.session(observation.identity.first))
                assertTrue(backend.inTransaction)
                session.set(PgLifecycleDatabaseSession(observation.identity.first, backend.backendStart))
                // Last business SQL reply has fully returned. The relay insists its next held CommandComplete is COMMIT.
                warm.armCommit(relay.warmedSession(observation.identity.first))
                start.release()
            }
        }
        val failure = try {
            assertThrows<PersistencePhaseException> {
                try {
                    returned.set(f.prepared(candidate))
                } finally {
                    callerCompleted.set(true)
                }
            }
        } finally {
            start.release()
            f.afterStep = {}
        }
        assertTrue(witness.value())
        assertNull(returned.get())
        assertEquals(PersistenceDatabaseOutcome.UNKNOWN, failure.databaseOutcome)
        assertTrue(failure.cleanupProven)
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
        val ended = warm.requireStopped()
        relay.awaitClientDisposal(ended.ordinal, PgLifecycleDatabaseDeadline(1_000)) {}
        f.assertReleased()
        f.assertCharged(before)
        val committed = f.state()
        val recovered = f.prepared(candidate) // New real preflight, exact reload and independently known commit/release.
        assertArrayEquals(expected.canonicalBytes(), recovered.canonicalBytes())
        assertEquals(committed, f.state())
        f.assertReleased()
    }
}

private fun assertCommittedWhileReplyHeld(
    connection: Connection,
    candidate: InstallationDeletionCandidate,
    session: PgLifecycleDatabaseSession,
    application: String,
    expectedBytes: ByteArray,
) {
    connection.prepareStatement(
        "SELECT d.state, d.submitted_credential_version, p.state, p.event_bytes, r.state, r.reserved_amounts = ?::bigint[], " +
            "i.state, c.state, c.credential_version, " +
            "(SELECT count(*) FROM audit_log a WHERE a.action = 'COMPLAINT_INSTALLATION_DELETE_AUTHORIZED' AND a.created_at = d.authorized_at), " +
            "s.backend_start, s.xact_start IS NULL, s.application_name " +
            "FROM installation_deletion_receipts d JOIN complaint_journal_publications p ON p.event_id = d.publication_ref " +
            "JOIN complaint_recovery_capacity_reservations r ON r.event_id = p.event_id " +
            "JOIN complaint_installation_ids i ON i.id = d.installation_id JOIN app_installations c ON c.id = d.installation_id " +
            "JOIN pg_stat_activity s ON s.pid = ? AND s.datname = current_database() " +
            "WHERE d.installation_id = ? AND d.deletion_key = ?",
    ).use { statement ->
        statement.queryTimeout = 1
        statement.setString(1, OwnerDeleteAllCapacityCharges.RECOVERY.toLongArray().joinToString(",", "{", "}"))
        statement.setInt(2, session.pid)
        statement.setObject(3, candidate.installation.id)
        statement.setObject(4, candidate.operationKey)
        statement.executeQuery().use { row ->
            assertTrue(row.next())
            assertEquals("AUTHORIZED_DELETE", row.getString(1))
            assertEquals(candidate.credentialVersion, row.getLong(2))
            assertEquals("PREPARED", row.getString(3))
            assertArrayEquals(expectedBytes, row.getBytes(4))
            assertEquals("RESERVED", row.getString(5))
            assertTrue(row.getBoolean(6))
            assertEquals("DELETION_PENDING", row.getString(7))
            assertEquals("DELETION_PENDING", row.getString(8))
            assertEquals(candidate.credentialVersion, row.getLong(9))
            assertEquals(1L, row.getLong(10))
            assertEquals(Timestamp.from(session.backendStart), row.getTimestamp(11))
            assertTrue(row.getBoolean(12))
            assertEquals(application, row.getString(13))
            assertFalse(row.next())
        }
    }
}
