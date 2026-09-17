package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintDeleteAllFingerprint
import me.manga.kira.backend.complaint.domain.InstallationDeletionCandidate
import me.manga.kira.backend.complaint.domain.InstallationDeletionPreflightResult
import me.manga.kira.backend.complaint.domain.InstallationDeletionPreflightTuple
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import me.manga.kira.backend.complaint.infrastructure.ComplaintInstallationDeletionPreflightOperation
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintInstallationDeletionPreflightStore
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintInstallationDeletionPreflightPhaseExecutor
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import me.manga.kira.backend.complaint.domain.InstallationDeletionPreflightRejection as Rejection

/** Real V14 SQL plus the existing owned fixture. Seeded publication rows are NOT codec/provider or HTTP204 authority. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class InstallationDeletionPreflightIT {
    private val database = lazy { PgLifecycleDatabaseFixture(InstallationDeletionPreflightIT::class.java).also { it.start() } }

    @AfterAll
    fun closeDatabase() {
        if (database.isInitialized()) database.value.close()
    }

    @Test
    fun `ACTIVE preflight authenticates one DB-clock read-only snapshot and releases without refreshing or writing`() = withFixture { f ->
        val candidate = f.enrolled()
        val before = f.state()
        val floor = f.databaseTime()
        f.jdbc.afterRead = {
            assertEquals("on", f.ordinary.jdbc.queryForObject("SHOW transaction_read_only", String::class.java))
            assertEquals(PersistencePhasePath.COMPLAINT_INSTALLATION_DELETION_PREFLIGHT.name, TransactionSynchronizationManager.getCurrentTransactionName())
            assertEquals(
                0L,
                f.ordinary.jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE pid = pg_backend_pid() AND locktype = 'advisory'", Long::class.java),
            )
        }
        val result = assertInstanceOf(InstallationDeletionPreflightResult.Active::class.java, f.preflight(candidate))
        f.phases.requireOwned(result)
        val ceiling = f.databaseTime()
        assertEquals(candidate.installation, result.installation)
        assertEquals(candidate.credentialVersion, result.submittedCredentialVersion)
        assertEquals(candidate.operationKey, result.operationKey)
        assertEquals(ComplaintDeleteAllFingerprint.of(candidate).encoded, result.fingerprint.encoded)
        assertEquals(1, f.jdbc.queries)
        assertEquals(0, f.jdbc.updates)
        assertTrue(f.jdbc.times.single() in floor..ceiling)
        assertEquals(PersistenceDatabaseOutcome.COMMITTED, f.jdbc.observations.single().phase.databaseOutcome())
        assertEquals(before, f.state()) // Includes unchanged activity/version, receipt, publication, counters and audit rows.
        assertEquals("InstallationDeletionPreflightTuple(redacted)", result.toString())
    }

    @Test
    fun `authorized and completed observations bind original version and publication applied links without provider authority`() = withFixture { f ->
        val candidate = f.enrolled()
        val event = f.authorize(candidate)
        val before = f.state()
        val pending = assertInstanceOf(InstallationDeletionPreflightResult.Authorized::class.java, f.preflight(candidate))
        f.phases.requireOwned(pending)
        assertEquals(event, pending.publicationReference)
        assertEquals(candidate.credentialVersion, pending.submittedCredentialVersion)
        assertEquals(before, f.state())
        // A structurally VERIFIED outbox is still only an authorized continuation, never 202 or completed erasure.
        f.observer.update(
            "UPDATE complaint_journal_publications SET state = 'VERIFIED', object_version = 'synthetic-v1', ciphertext_hash = ?, " +
                "object_created_at = now(), retain_until = now() + interval '70 days', verified_at = now(), " +
                "verification_bytes = decode('01','hex'), verification_hash = sha256(decode('01','hex')) WHERE event_id = ?",
            ByteArray(32) { 31 },
            event,
        )
        assertInstanceOf(InstallationDeletionPreflightResult.Authorized::class.java, f.preflight(candidate))
        f.complete(candidate)
        val completedState = f.state()
        val completed = assertInstanceOf(InstallationDeletionPreflightResult.Completed::class.java, f.preflight(candidate))
        f.phases.requireOwned(completed)
        assertEquals(event, completed.publicationReference)
        assertEquals(candidate.credentialVersion, completed.submittedCredentialVersion)
        assertEquals(
            candidate.credentialVersion + 1,
            f.observer.queryForObject("SELECT credential_version FROM app_installations WHERE id = ?", Long::class.java, candidate.installation.id),
        )
        f.rejected(f.request(candidate.installation, candidate.credentialVersion + 1, candidate.operationKey), Rejection.INSTALLATION_CREDENTIAL_REJECTED)
        assertEquals(completedState, f.state())
        f.observer.update(
            "UPDATE installation_deletion_receipts SET external_ciphertext_hash = ? WHERE installation_id = ?",
            ByteArray(32) { 9 },
            candidate.installation.id,
        )
        refusedStorage(f, candidate)
        f.observer.update(
            "UPDATE installation_deletion_receipts d SET external_ciphertext_hash = p.ciphertext_hash " +
                "FROM complaint_journal_publications p WHERE d.publication_ref = p.event_id AND d.installation_id = ?",
            candidate.installation.id,
        )
        f.observer.update("DELETE FROM complaint_deletion_journal_applied WHERE event_id = ?", event)
        refusedStorage(f, candidate) // APPLIED/204 labels without the matching permanent evidence do not suffice.
    }

    @Test
    fun `wrong verifier version scope key and fingerprint cannot continue including a foreign PURGED requested scope`() = withFixture { f ->
        val candidate = f.enrolled()
        f.rejected(
            f.request(candidate.installation, candidate.credentialVersion, candidate.operationKey, ByteArray(32) { 91 }),
            Rejection.INSTALLATION_CREDENTIAL_REJECTED,
        )
        f.rejected(f.request(candidate.installation, candidate.credentialVersion + 1, candidate.operationKey), Rejection.INSTALLATION_CREDENTIAL_REJECTED)
        f.rejected(f.request(ScopedInstallationId(UUID.randomUUID(), ComplaintDataScope.LIVE)), Rejection.INSTALLATION_NOT_FOUND)
        f.authorize(candidate)
        f.rejected(f.request(candidate.installation, candidate.credentialVersion), Rejection.IDEMPOTENCY_KEY_REUSED)
        f.observer.update(
            "UPDATE installation_deletion_receipts SET fingerprint = ? WHERE installation_id = ?",
            ByteArray(32) { 17 },
            candidate.installation.id,
        )
        f.rejected(candidate, Rejection.IDEMPOTENCY_KEY_REUSED)
        f.observer.update(
            "UPDATE installation_deletion_receipts SET fingerprint = ? WHERE installation_id = ?",
            ComplaintDeleteAllFingerprint.of(candidate).bytes(),
            candidate.installation.id,
        )
        OrdinaryComplaintTestInstallationFixture(f.base).use { scoped ->
            scoped.terminalState("PURGED") // Empty B; the authenticated pending installation belongs to LIVE/A, not B.
            val wrongScope = candidate.installation.copy(scope = scoped.scope)
            f.rejected(f.request(wrongScope, candidate.credentialVersion, candidate.operationKey), Rejection.INSTALLATION_SCOPE_MISMATCH)
            f.rejected(
                f.request(wrongScope, candidate.credentialVersion, candidate.operationKey, ByteArray(32) { 91 }),
                Rejection.INSTALLATION_CREDENTIAL_REJECTED,
            )
        }
        assertInstanceOf(InstallationDeletionPreflightResult.Authorized::class.java, f.preflight(candidate))
    }

    @Test
    fun `terminal reservations DB expiry and absent verifier deny without replay while orphan or same-scope PURGED residue stays corrupt`() = withFixture { f ->
        noVerifierAndExpired(f)
        val orphan = f.enrolled()
        f.observer.update("DELETE FROM app_installations WHERE id = ?", orphan.installation.id)
        refusedStorage(f, orphan)
        OrdinaryComplaintTestInstallationFixture(f.base).use { scoped ->
            val enrollment = scoped.candidate()
            val enrolled = f.base.execute(enrollment, scoped.store)
            val candidate = f.request(enrollment.installation, enrolled.credentialVersion)
            scoped.terminalState("SEALED")
            f.rejected(candidate, Rejection.INSTALLATION_SCOPE_RETIRED)
            scoped.terminalState("PURGED")
            refusedStorage(f, candidate) // This credential really belongs to the now-PURGED scope.
        }
    }

    @Test
    fun `concurrent durable completion and run sealing after the real cursor starts cannot tear the joined receipt snapshot`() = withFixture { f ->
        OrdinaryComplaintTestInstallationFixture(f.base).use { scoped ->
            val enrollment = scoped.candidate()
            val enrolled = f.base.execute(enrollment, scoped.store)
            val candidate = f.request(enrollment.installation, enrolled.credentialVersion)
            val event = f.authorize(candidate)
            var crossed = false
            f.jdbc.beforeMap = {
                // PostgreSQL has produced the actual joined row. The independent real transaction
                // now changes credential version/state, receipt, publication, applied evidence AND run.
                f.complete(candidate, sealRun = true)
                crossed = true
                assertEquals(
                    "COMPLETED",
                    f.observer.queryForObject(
                        "SELECT state FROM installation_deletion_receipts WHERE installation_id = ?",
                        String::class.java,
                        candidate.installation.id,
                    ),
                )
            }
            try {
                val old = assertInstanceOf(InstallationDeletionPreflightResult.Authorized::class.java, f.preflight(candidate))
                assertEquals(event, old.publicationReference)
                assertEquals(candidate.credentialVersion, old.submittedCredentialVersion)
            } finally {
                f.jdbc.beforeMap = {}
            }
            assertTrue(crossed)
            assertEquals(1, f.jdbc.queries)
            val current = assertInstanceOf(InstallationDeletionPreflightResult.Completed::class.java, f.preflight(candidate))
            assertEquals(event, current.publicationReference)
            assertEquals(2, f.jdbc.queries)
            assertEquals(0, f.jdbc.updates)
        }
    }

    @Test
    fun `private observations require exact owner issuer caller and actual phase release rather than forged views or transaction flags`() = withFixture { f ->
        val candidate = f.enrolled()
        val phase = f.ordinary.ownership.enterComplaintInstallationDeletionPreflight()
        var operation: ComplaintInstallationDeletionPreflightOperation? = null
        try {
            phase.begin()
            operation = f.store.read(candidate)
            assertUnreleased(operation, PersistenceDatabaseOutcome.NONE)
            phase.commit()
            assertUnreleased(operation, PersistenceDatabaseOutcome.COMMITTED)
            assertFalse(f.jdbc.observations.last().lease.completion.quiescent())
        } finally {
            phase.finish()
        }
        val retained = checkNotNull(operation)
        val result = assertInstanceOf(InstallationDeletionPreflightResult.Active::class.java, retained.result)
        assertSame(result, retained.result)
        f.assertReleased()
        f.phases.requireOwned(result)
        assertThrows<PersistencePhaseException> { f.phases.requireOwned(object : InstallationDeletionPreflightResult.Active by result {}) }
        val otherIssuer = ComplaintInstallationDeletionPreflightPhaseExecutor(f.ordinary.ownership, JdbcComplaintInstallationDeletionPreflightStore(f.jdbc))
        assertThrows<PersistencePhaseException> { otherIssuer.requireOwned(result) }
        OwnedCallerTestScope().use { callers ->
            callers.launch { assertThrows<PersistencePhaseException> { f.phases.requireOwned(result) } }.value()
        }
        val queries = f.jdbc.queries
        val wrongPath = f.ordinary.ownership.enterSourceGrantCleanup()
        try {
            wrongPath.begin()
            assertThrows<PersistencePhaseException> { f.store.read(candidate) }
            assertThrows<PersistencePhaseException> { wrongPath.commit() }
        } finally {
            wrongPath.finish()
        }
        assertEquals(queries, f.jdbc.queries)
        f.assertReleased()
        differentOwner(f, candidate) // Paired shutdown must follow the last ordinary-pool operation.
    }

    @Test
    fun `real rollback native commit loss and retained completion tail never release a successful receipt observation early`() = withFixture { f ->
        val candidate = f.enrolled()
        f.authorize(candidate)
        f.complete(candidate)
        refusedCompletion(f, candidate, CompletionFault.ROLLBACK)
        refusedCompletion(f, candidate, CompletionFault.NATIVE_COMMIT)
        refusedCompletion(f, candidate, CompletionFault.AFTER_COMMIT)
        heldCompletionTail(f, candidate)
    }

    private fun noVerifierAndExpired(f: InstallationDeletionPreflightFixture) {
        for (state in listOf("RETIRED", "RECOVERY_RESERVED", "DELETED")) {
            val candidate = f.request(ScopedInstallationId(UUID.randomUUID(), ComplaintDataScope.LIVE))
            f.base.ids.add(candidate.installation.id)
            f.observer.update(
                "INSERT INTO complaint_installation_ids (id, data_scope_id, test_only, state, created_at, terminal_at) " +
                    "VALUES (?, ?, false, ?, now(), CASE WHEN ? = 'RECOVERY_RESERVED' THEN NULL ELSE now() END)",
                candidate.installation.id,
                candidate.installation.scope.id,
                state,
                state,
            )
            f.rejected(candidate, if (state == "DELETED") Rejection.INSTALLATION_DELETED else Rejection.INSTALLATION_RETIRED)
        }
        val restored = f.enrolled()
        f.authorize(restored)
        f.complete(restored)
        f.observer.update("DELETE FROM app_installations WHERE id = ?", restored.installation.id)
        f.rejected(restored, Rejection.INSTALLATION_DELETED) // Even an exact receipt cannot authenticate without the retained verifier.
        val expired = f.enrolled()
        f.authorize(expired)
        f.complete(expired)
        f.expire(expired)
        f.rejected(expired, Rejection.INSTALLATION_DELETED) // Rows remain; the production SELECT's DB clock closes the window.
    }

    private fun differentOwner(f: InstallationDeletionPreflightFixture, candidate: InstallationDeletionCandidate) {
        val movable = JdbcTemplate(f.ordinary.pool)
        val store = JdbcComplaintInstallationDeletionPreflightStore(movable)
        val first = ComplaintInstallationDeletionPreflightPhaseExecutor(f.ordinary.ownership, store)
        val ticket = assertInstanceOf(InstallationDeletionPreflightResult.Active::class.java, first.preflight(candidate))
        withOrdinarySourceGrantCleanup(database.value, SystemPersistenceNanoClock, maximumPoolSize = 2, companion = f.ordinary.ownedPool) { other ->
            val second = ComplaintInstallationDeletionPreflightPhaseExecutor(other.ownership, store)
            movable.dataSource = other.pool
            try {
                assertThrows<PersistencePhaseException> { second.requireOwned(ticket) }
                val own = assertInstanceOf(InstallationDeletionPreflightResult.Active::class.java, second.preflight(candidate))
                second.requireOwned(own) // Same real DB and same mutable template/store still have different phase owners.
                assertThrows<PersistencePhaseException> { first.requireOwned(own) }
            } finally {
                movable.dataSource = f.ordinary.pool
            }
            assertEquals(0, other.admission.activeOwners())
        }
        first.requireOwned(ticket)
    }

    private fun assertUnreleased(operation: ComplaintInstallationDeletionPreflightOperation, outcome: PersistenceDatabaseOutcome) {
        val failure = assertThrows<PersistencePhaseException> { operation.result }
        assertEquals(outcome, failure.databaseOutcome)
        assertFalse(failure.cleanupProven)
    }

    private fun refusedStorage(f: InstallationDeletionPreflightFixture, candidate: InstallationDeletionCandidate) {
        val before = f.state()
        val failure = assertThrows<PersistencePhaseException> { f.preflight(candidate) }
        assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, failure.databaseOutcome)
        assertTrue(failure.cleanupProven)
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
        assertEquals(before, f.state())
    }

    private fun refusedCompletion(f: InstallationDeletionPreflightFixture, candidate: InstallationDeletionCandidate, mode: CompletionFault) {
        val before = f.state()
        var fired = false
        f.jdbc.afterRead = {
            when (mode) {
                CompletionFault.ROLLBACK -> {
                    fired = true
                    throw SyntheticInstallationEnrollmentFailure()
                }

                CompletionFault.NATIVE_COMMIT -> {
                    val pid = f.jdbc.observations.last().identity.first
                    TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                        override fun beforeCommit(readOnly: Boolean) {
                            assertTrue(readOnly)
                            f.ordinary.terminateSession(pid) // Real PG socket/transaction failure, not a fabricated outcome.
                            fired = true
                        }
                    })
                }

                CompletionFault.AFTER_COMMIT -> TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun afterCommit() {
                        fired = true
                        throw SyntheticInstallationEnrollmentFailure()
                    }
                })
            }
        }
        var escaped: InstallationDeletionPreflightResult? = null
        val failure = try {
            assertThrows<PersistencePhaseException> { escaped = f.phases.preflight(candidate) }
        } finally {
            f.jdbc.afterRead = {}
        }
        f.assertReleased()
        assertTrue(fired)
        assertNull(escaped)
        val expected = when (mode) {
            CompletionFault.ROLLBACK -> PersistenceDatabaseOutcome.ROLLED_BACK
            CompletionFault.NATIVE_COMMIT -> PersistenceDatabaseOutcome.UNKNOWN
            CompletionFault.AFTER_COMMIT -> PersistenceDatabaseOutcome.COMMITTED
        }
        assertEquals(expected, failure.databaseOutcome)
        assertEquals(expected, f.jdbc.observations.last().lease.completion.databaseOutcome())
        assertTrue(failure.cleanupProven)
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
        assertEquals(before, f.state())
        assertInstanceOf(InstallationDeletionPreflightResult.Completed::class.java, f.preflight(candidate))
    }

    private fun heldCompletionTail(f: InstallationDeletionPreflightFixture, candidate: InstallationDeletionCandidate) {
        val escaped = AtomicReference<InstallationDeletionPreflightResult?>()
        OwnedCallerTestScope().use { callers ->
            val gate = callers.gate()
            callers.beforeClose { f.jdbc.afterRead = {} }
            f.jdbc.afterRead = {
                TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun afterCommit() = gate.hold()
                })
            }
            val call = callers.launch {
                f.phases.preflight(candidate).also {
                    f.phases.requireOwned(it as InstallationDeletionPreflightTuple)
                    escaped.set(it)
                }
            }
            try {
                gate.awaitEntered()
                val observed = f.jdbc.observations.last()
                assertEquals(PersistenceDatabaseOutcome.COMMITTED, observed.lease.completion.databaseOutcome())
                assertFalse(observed.lease.completion.quiescent())
                assertEquals(1, f.ordinary.admission.activeOwners())
                assertNull(escaped.get())
                assertThrows<PersistencePhaseException> { f.phases.preflight(candidate) }
            } finally {
                gate.release()
            }
            assertInstanceOf(InstallationDeletionPreflightResult.Completed::class.java, call.value())
        }
        assertInstanceOf(InstallationDeletionPreflightResult.Completed::class.java, escaped.get())
        f.assertReleased()
    }

    private fun withFixture(test: (InstallationDeletionPreflightFixture) -> Unit) = withInstallationDeletionPreflight(database.value, test)

    private enum class CompletionFault { ROLLBACK, NATIVE_COMMIT, AFTER_COMMIT }
}
