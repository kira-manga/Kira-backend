package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.domain.OwnerDeleteAllCapacityCharges
import me.manga.kira.backend.complaint.infrastructure.CommittedOwnerDeleteAllApplyV1
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import java.sql.Connection
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Holds the existing relay's actual warmed APPLY COMMIT reply, never a fabricated commit/result flag. */
internal fun assertOwnerDeleteAllApplyLostCommitResponse(database: PgLifecycleDatabaseFixture) {
    val warm = PgLifecycleDatabaseWarmControl()
    val case = PgLifecycleDatabaseCase(PgLifecycleDatabaseRecipe.DEFAULT, 0, PgLifecycleDatabaseLane.ORDINARY)
    PgLifecycleDatabaseRelay(database, warm.application, case, warm).use { relay ->
        PgLifecycleDatabaseDiagnostics.preservingFailure(
            diagnostic = { println("DELETE_ALL_APPLY_COMMIT_RELAY observation=PRE_RELAY_CLEANUP_MIXED ${relay.diagnostic(-1)}") },
        ) {
            relay.start()
            val endpoint = PgLifecycleDatabaseSettings.endpoint(case, relay.port, warm.application)
            withOwnerDeleteAllAuthorization(database, endpoint) { auth ->
                val candidate = auth.enrolled()
                lostApplyCommit(OwnerDeleteAllApplyFixture(auth, candidate, paidApplyContent(auth, candidate, 1)), relay, warm)
            }
        }
    }
}

private fun lostApplyCommit(f: OwnerDeleteAllApplyFixture, relay: PgLifecycleDatabaseRelay, warm: PgLifecycleDatabaseWarmControl) {
    val counters = f.auth.counters()
    val selected = AtomicReference<StepUpPhaseObservation?>()
    val session = AtomicReference<PgLifecycleDatabaseSession?>()
    val returned = AtomicReference<CommittedOwnerDeleteAllApplyV1?>()
    val committedAt = AtomicReference<Instant?>()
    val callerCompleted = AtomicBoolean()
    OwnedCallerTestScope().use { callers ->
        val start = callers.gate()
        val witness = callers.launch {
            requireConnectionFree()
            // Preopened unpooled observer on a genuinely connection-free actor. It is not an
            // alternate application holder and is never consulted by the production operation.
            checkNotNull(f.auth.observer.dataSource).connection.use { connection ->
                start.hold()
                val observation = checkNotNull(selected.get())
                warm.awaitHeld(PgLifecycleDatabaseDeadline(2_000), relay::progress)
                assertEquals(observation.identity.first, warm.requireHeld().backendPid.get())
                assertFalse(callerCompleted.get())
                assertFalse(observation.lease.completion.quiescent())
                committedAt.set(assertApplyCommittedWhileReplyHeld(connection, f, checkNotNull(session.get()), warm.application))
                warm.witnessed()
                assertFalse(callerCompleted.get())
            }
            requireConnectionFree()
            true
        }
        start.awaitEntered()
        f.afterStep = { step ->
            if (step === ApplyStep.FINAL) {
                val observation = f.observations.last().second
                selected.set(observation)
                assertTrue(
                    callers.launch {
                        requireConnectionFree()
                        val backend = checkNotNull(f.auth.base.ordinary.session(observation.identity.first))
                        assertTrue(backend.inTransaction)
                        session.set(PgLifecycleDatabaseSession(observation.identity.first, backend.backendStart))
                        requireConnectionFree()
                        true
                    }.value(),
                )
                // Last business SQL (final actual retention clock) returned; no provider/SQL follows
                // before the real commit. The unchanged relay identifies its actual protocol frame.
                warm.armCommit(relay.warmedSession(observation.identity.first))
                start.release()
            }
        }
        val failure = try {
            assertThrows<PersistencePhaseException> {
                try {
                    returned.set(f.apply())
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
        val committed = f.auth.state()
        f.assertAccounting(counters, 1, 0)
        f.statements.clear()
        val recovered = f.apply() // New original holder independently rereads exact APPLIED/COMPLETED state and releases after its own known commit.
        assertEquals(checkNotNull(committedAt.get()), recovered.completedAt)
        assertEquals(checkNotNull(committedAt.get()).plus(Duration.ofHours(192)), recovered.expiresAt)
        assertEquals(committed, f.auth.state())
        f.assertNoWrites()
        f.assertCompleted(recovered, f.targets, 1, 0)
        f.assertReleased()
    }
}

private fun assertApplyCommittedWhileReplyHeld(
    connection: Connection,
    f: OwnerDeleteAllApplyFixture,
    session: PgLifecycleDatabaseSession,
    application: String,
): Instant = connection.prepareStatement(
    "SELECT p.state, p.verification_bytes, p.verification_hash, p.object_version, p.ciphertext_hash, p.applied_at, " +
        "d.state, d.response_status, d.completed_at, d.expires_at, i.state, i.terminal_at, c.state, c.credential_version, c.version, c.secret_verifier, " +
        "c.deleted_at, c.verifier_expires_at, c.platform IS NULL AND c.owner_reference IS NULL AND c.last_authenticated_at IS NULL, " +
        "r.state, r.reserved_amounts = ?::bigint[], r.converted_amounts = ?::bigint[], r.converted_at, " +
        "a.event_id = p.event_id AND a.object_version = p.object_version AND a.ciphertext_hash = p.ciphertext_hash " +
        "AND a.writer_generation = p.writer_generation AND a.journal_epoch = p.journal_epoch AND a.target_count = p.target_count, a.applied_at, " +
        "NOT EXISTS (SELECT 1 FROM complaints WHERE owner_id = c.id), " +
        "(SELECT count(*) FROM complaint_resource_ids WHERE id = ANY (?::uuid[]) AND state = 'DELETED' AND deleted_at = d.completed_at), " +
        "s.backend_start, s.xact_start IS NULL, s.application_name, " +
        "d.external_event_id = p.event_id AND d.external_epoch = p.journal_epoch AND d.external_object_version = p.object_version " +
        "AND d.external_ciphertext_hash = p.ciphertext_hash " +
        "FROM installation_deletion_receipts d JOIN complaint_journal_publications p ON p.event_id = d.publication_ref " +
        "JOIN complaint_recovery_capacity_reservations r ON r.event_id = p.event_id " +
        "JOIN complaint_installation_ids i ON i.id = d.installation_id JOIN app_installations c ON c.id = d.installation_id " +
        "JOIN complaint_deletion_journal_applied a ON a.object_key = p.object_key AND a.object_version = p.object_version " +
        "JOIN pg_stat_activity s ON s.pid = ? AND s.datname = current_database() WHERE d.installation_id = ? AND d.deletion_key = ?",
).use { statement ->
    statement.queryTimeout = 1
    statement.setString(1, applyFixtureVector(OwnerDeleteAllCapacityCharges.RECOVERY))
    statement.setString(2, applyFixtureVector(applyFixtureUse(1, 0)))
    statement.setString(3, f.targets.joinToString(",", "{", "}"))
    statement.setInt(4, session.pid)
    statement.setObject(5, f.candidate.installation.id)
    statement.setObject(6, f.candidate.operationKey)
    statement.executeQuery().use { row ->
        assertTrue(row.next())
        assertEquals("APPLIED", row.getString(1))
        assertArrayEquals(f.proof.verificationBytes(), row.getBytes(2))
        assertArrayEquals(f.proof.verificationHash(), row.getBytes(3))
        assertEquals(f.proof.objectVersion, row.getString(4))
        assertEquals(f.proof.ciphertextSha256, HexFormat.of().formatHex(row.getBytes(5)))
        val at = row.getTimestamp(6).toInstant()
        assertEquals("COMPLETED", row.getString(7))
        assertEquals(204, row.getInt(8))
        assertEquals(at, row.getTimestamp(9).toInstant())
        assertEquals(at.plus(Duration.ofHours(192)), row.getTimestamp(10).toInstant())
        assertEquals("DELETED", row.getString(11))
        assertEquals(at, row.getTimestamp(12).toInstant())
        assertEquals("DELETED", row.getString(13))
        assertEquals(f.candidate.credentialVersion + 1, row.getLong(14))
        assertEquals(2L, row.getLong(15))
        assertArrayEquals(f.candidate.credential.verifierBytes(), row.getBytes(16))
        assertEquals(at, row.getTimestamp(17).toInstant())
        assertEquals(at.plus(Duration.ofHours(192)), row.getTimestamp(18).toInstant())
        assertTrue(row.getBoolean(19))
        assertEquals("PARTIAL", row.getString(20))
        assertTrue(row.getBoolean(21) && row.getBoolean(22))
        assertEquals(at, row.getTimestamp(23).toInstant())
        assertTrue(row.getBoolean(24))
        assertEquals(at, row.getTimestamp(25).toInstant())
        assertTrue(row.getBoolean(26))
        assertEquals(f.targets.size.toLong(), row.getLong(27))
        assertEquals(Timestamp.from(session.backendStart), row.getTimestamp(28))
        assertTrue(row.getBoolean(29))
        assertEquals(application, row.getString(30))
        assertTrue(row.getBoolean(31))
        assertFalse(row.next())
        at
    }
}
