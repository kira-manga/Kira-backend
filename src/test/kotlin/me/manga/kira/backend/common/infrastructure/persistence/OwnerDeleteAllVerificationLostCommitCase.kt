package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.domain.OwnerDeleteAllCapacityCharges
import me.manga.kira.backend.complaint.infrastructure.CommittedOwnerDeleteAllVerificationV1
import me.manga.kira.backend.complaint.infrastructure.journal.OwnerDeleteAllVerificationRecordV1
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Existing bounded protocol relay holds the actual warmed VERIFY COMMIT reply, never a mocked commit result. */
internal fun assertOwnerDeleteAllVerificationLostCommitResponse(database: PgLifecycleDatabaseFixture) {
    val warm = PgLifecycleDatabaseWarmControl()
    val case = PgLifecycleDatabaseCase(PgLifecycleDatabaseRecipe.DEFAULT, 0, PgLifecycleDatabaseLane.ORDINARY)
    PgLifecycleDatabaseRelay(database, warm.application, case, warm).use { relay ->
        PgLifecycleDatabaseDiagnostics.preservingFailure(
            diagnostic = { println("DELETE_ALL_VERIFY_COMMIT_RELAY observation=PRE_RELAY_CLEANUP_MIXED ${relay.diagnostic(-1)}") },
        ) {
            relay.start()
            val endpoint = PgLifecycleDatabaseSettings.endpoint(case, relay.port, warm.application)
            withOwnerDeleteAllAuthorization(database, endpoint) { auth ->
                val candidate = auth.enrolled()
                lostVerificationCommit(OwnerDeleteAllVerificationFixture(auth, candidate, auth.content(candidate, 1)), relay, warm)
            }
        }
    }
}

private fun lostVerificationCommit(f: OwnerDeleteAllVerificationFixture, relay: PgLifecycleDatabaseRelay, warm: PgLifecycleDatabaseWarmControl) {
    val readback = f.readback()
    val expected = f.codec.observed(readback)
    val expectedBytes = f.codec.canonicalBytes(expected)
    val expectedHash = ownerDeleteAllTestDigest(expectedBytes)
    val before = f.auth.state()
    val selected = AtomicReference<StepUpPhaseObservation?>()
    val session = AtomicReference<PgLifecycleDatabaseSession?>()
    val returned = AtomicReference<CommittedOwnerDeleteAllVerificationV1?>()
    val callerCompleted = AtomicBoolean()
    OwnedCallerTestScope().use { callers ->
        val start = callers.gate()
        val witness = callers.launch {
            // Preopened unpooled observer, not an alternate application resource/producer.
            checkNotNull(f.auth.observer.dataSource).connection.use { connection ->
                start.hold()
                val observation = checkNotNull(selected.get())
                warm.awaitHeld(PgLifecycleDatabaseDeadline(2_000), relay::progress)
                assertEquals(observation.identity.first, warm.requireHeld().backendPid.get())
                assertFalse(callerCompleted.get())
                assertFalse(observation.lease.completion.quiescent())
                assertVerificationCommittedWhileReplyHeld(connection, f, checkNotNull(session.get()), warm.application, expected, expectedBytes, expectedHash)
                warm.witnessed()
                assertFalse(callerCompleted.get())
            }
            true
        }
        start.awaitEntered()
        f.afterStep = { step ->
            if (step == VerificationStep.VERIFIED) {
                val observation = f.observations.last().second
                selected.set(observation)
                // Await the foreign observation on its own connection-free caller before arming
                // the unchanged relay boundary; VERIFY still holds its original sole resource.
                assertTrue(callers.launch {
                    requireConnectionFree()
                    val backend = checkNotNull(f.auth.base.ordinary.session(observation.identity.first))
                    assertTrue(backend.inTransaction)
                    session.set(PgLifecycleDatabaseSession(observation.identity.first, backend.backendStart))
                    requireConnectionFree()
                    true
                }.value())
                // UPDATE RETURNING and its last business SQL have returned. No parser/provider SQL follows.
                warm.armCommit(relay.warmedSession(observation.identity.first))
                start.release()
            }
        }
        val failure = try {
            assertThrows<PersistencePhaseException> {
                try {
                    returned.set(f.phases.verify(readback))
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
        assertEquals(before.filterKeys { it != "publications" }, committed.filterKeys { it != "publications" })
        f.statements.clear()
        val recovered = f.phases.verify(readback) // Fresh transaction observes first exact proof, commits, and actually cleans its own holder.
        assertArrayEquals(expectedBytes, recovered.verificationBytes())
        assertArrayEquals(expectedHash, recovered.verificationHash())
        assertEquals(committed, f.auth.state())
        f.assertOnlyVerificationStatements(write = false)
        f.assertReleased()
    }
}

private fun assertVerificationCommittedWhileReplyHeld(
    connection: Connection,
    f: OwnerDeleteAllVerificationFixture,
    session: PgLifecycleDatabaseSession,
    application: String,
    record: OwnerDeleteAllVerificationRecordV1,
    bytes: ByteArray,
    hash: ByteArray,
) {
    connection.prepareStatement(
        "SELECT p.state, p.verification_bytes, p.verification_hash, p.object_version, p.ciphertext_hash, p.object_created_at, p.retain_until, p.verified_at, " +
            "d.state, d.completed_at, d.expires_at, i.state, c.state, c.credential_version, r.state, r.reserved_amounts = ?::bigint[], " +
            "s.backend_start, s.xact_start IS NULL, s.application_name, p.applied_at " +
            "FROM installation_deletion_receipts d JOIN complaint_journal_publications p ON p.event_id = d.publication_ref " +
            "JOIN complaint_recovery_capacity_reservations r ON r.event_id = p.event_id " +
            "JOIN complaint_installation_ids i ON i.id = d.installation_id JOIN app_installations c ON c.id = d.installation_id " +
            "JOIN pg_stat_activity s ON s.pid = ? AND s.datname = current_database() WHERE d.installation_id = ? AND d.deletion_key = ?",
    ).use { statement ->
        statement.queryTimeout = 1
        statement.setString(1, OwnerDeleteAllCapacityCharges.RECOVERY.toLongArray().joinToString(",", "{", "}"))
        statement.setInt(2, session.pid)
        statement.setObject(3, f.candidate.installation.id)
        statement.setObject(4, f.candidate.operationKey)
        statement.executeQuery().use { row ->
            assertTrue(row.next())
            assertEquals("VERIFIED", row.getString(1))
            assertArrayEquals(bytes, row.getBytes(2))
            assertArrayEquals(hash, row.getBytes(3))
            assertEquals(record.objectVersion, row.getString(4))
            assertEquals(record.ciphertextSha256, HexFormat.of().formatHex(row.getBytes(5)))
            assertEquals(Instant.parse(record.objectCreatedAt), row.getTimestamp(6).toInstant())
            assertEquals(Instant.parse(record.retainUntil), row.getTimestamp(7).toInstant())
            assertEquals(Instant.parse(record.verifiedAt), row.getTimestamp(8).toInstant())
            assertEquals("AUTHORIZED_DELETE", row.getString(9))
            assertNull(row.getTimestamp(10))
            assertNull(row.getTimestamp(11))
            assertEquals("DELETION_PENDING", row.getString(12))
            assertEquals("DELETION_PENDING", row.getString(13))
            assertEquals(f.candidate.credentialVersion, row.getLong(14))
            assertEquals("RESERVED", row.getString(15))
            assertTrue(row.getBoolean(16))
            assertEquals(Timestamp.from(session.backendStart), row.getTimestamp(17))
            assertTrue(row.getBoolean(18))
            assertEquals(application, row.getString(19))
            assertNull(row.getTimestamp(20))
            assertFalse(row.next())
        }
    }
}
