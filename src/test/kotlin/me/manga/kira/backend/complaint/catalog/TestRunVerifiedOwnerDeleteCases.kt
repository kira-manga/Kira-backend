package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.GuardedJdbcTransactionManager
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteReceipt
import me.manga.kira.backend.complaint.infrastructure.OwnerDeletePersistenceSql
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunSealingSqlV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunVerifiedOwnerDeleteExceptionV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunVerifiedOwnerDeleteV1
import me.manga.kira.backend.complaint.infrastructure.transaction.DeletionPersistenceAdmission
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

internal enum class TestVerifiedDeleteRefusalCut { PREPARED, MISSING_RECEIPT, FULL_D, PROOF_BYTES, WRITER, MISSING_SEALED_AUDIT }

/** New source is exercised only after genuine prior authorization/VERIFY and the real registered sealer. */
internal object TestRunVerifiedOwnerDeleteCases {
    fun verifiedPrimaryAndExactReplay(tls: VersionBoundPersistenceConnectedFixture) = withVerifiedOwnerDeleteRun(tls) { f ->
        val before = f.image()
        val counters = f.p.counters()
        val original = f.begin()
        var releasedReload = false
        var atomicApply = false
        f.jdbc.before = { call ->
            if (call.path === PersistencePhasePath.COMPLAINT_OWNER_DELETE_APPLY && !releasedReload) {
                val prior = f.jdbc.observations.keys.first()
                assertEquals(PersistencePhasePath.COMPLAINT_OWNER_DELETE_RELOAD, TestRunVerifiedOwnerDeleteFixture.path(prior))
                assertEquals(PersistenceDatabaseOutcome.COMMITTED, prior.databaseOutcome())
                assertTrue(prior.testRunOwnerDeleteCleanupProven(original))
                assertTrue(f.jdbc.observations.getValue(prior).lease.completion.quiescent())
                assertEquals(before, f.image(), "A freshly committed/released reload is read-only, not another authorization.")
                releasedReload = true
            }
        }
        f.jdbc.after = { call ->
            if (call.sql == OwnerDeletePersistenceSql.COMPLETE_RECEIPT) {
                assertEquals(before, f.image(), "Erasure, audit, receipt, publication and counter/reserve progress are one uncommitted transaction.")
                atomicApply = true
            }
        }
        try { assertSame(ComplaintOwnerDeleteReceipt.Applied, original.complete()) }
        finally { f.jdbc.before = {}; f.jdbc.after = {} }
        assertTrue(releasedReload); assertTrue(atomicApply)
        f.assertSuccessfulPhases(original)
        f.assertApplied(counters)
        val after = f.image()
        assertEquals(before.filterKeys { it !in changed }, after.filterKeys { it !in changed },
            "Credentials, identity, run/unused reserve, closed gates, epochs, catalog and retirement evidence are untouched.")
        assertLockOrder(f)
        assertThrows<TestRunVerifiedOwnerDeleteExceptionV1> { original.complete() }

        f.jdbc.reset()
        val replay = f.begin()
        assertSame(ComplaintOwnerDeleteReceipt.Applied, replay.complete())
        f.assertSuccessfulPhases(replay)
        assertEquals(after, f.image(), "Exact replay must not churn any row, audit, charge, refund, reserve, xmin or timestamp.")
        assertTrue(f.jdbc.calls.none { it.sql.trimStart().startsWith("UPDATE") || it.sql.trimStart().startsWith("DELETE") || it.sql.trimStart().startsWith("INSERT") })

        val generic = f.ownership.enterComplaintOwnerDeleteApply()
        try {
            generic.recordFailure(assertThrows<PersistencePhaseException> { generic.begin() })
        } finally { generic.finish() }
        f.assertReleased()
        assertEquals(after, f.image(), "The ordinary unowned APPLY entry still rejects this closed gate.")

        // Locators carry no authority, even with the correct real registration.
        for ((actor, key) in listOf(UUID.randomUUID() to f.key, f.actor.id to UUID.randomUUID())) {
            f.jdbc.reset()
            assertThrows<TestRunVerifiedOwnerDeleteExceptionV1> { f.begin(actor, key).complete() }
            f.assertReleased()
            assertEquals(after, f.image())
            assertTrue(f.jdbc.calls.all { it.path === PersistencePhasePath.COMPLAINT_OWNER_DELETE_RELOAD })
        }
        val calls = f.jdbc.calls.size
        assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> {
            TestRunVerifiedOwnerDeleteV1.begin(f.registration, f.ownership, JdbcTemplate(f.runtime.pools.deletion), f.audit, f.actor.id, f.key)
        }
        val otherOwner = PersistencePhaseOwnership.deletion(DeletionPersistenceAdmission(), GuardedJdbcTransactionManager(f.runtime.pools.deletion))
        assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> {
            TestRunVerifiedOwnerDeleteV1.begin(f.registration, otherOwner, f.jdbc, f.audit, f.actor.id, f.key)
        }
        f.registration.close()
        assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { f.begin() }
        assertEquals(calls, f.jdbc.calls.size, "A replacement owner/template or closed binding cannot even start SQL.")
        assertEquals(after, f.image())
    }

    fun refusesWithoutRepair(tls: VersionBoundPersistenceConnectedFixture, cut: TestVerifiedDeleteRefusalCut) =
        withVerifiedOwnerDeleteRun(tls, verified = cut !== TestVerifiedDeleteRefusalCut.PREPARED) { f ->
            when (cut) {
                TestVerifiedDeleteRefusalCut.PREPARED -> Unit
                TestVerifiedDeleteRefusalCut.MISSING_RECEIPT -> assertEquals(1, f.observer.update(
                    "DELETE FROM complaint_idempotency_receipts WHERE actor_id = ? AND idempotency_key = ?", f.actor.id, f.key))
                TestVerifiedDeleteRefusalCut.FULL_D -> assertEquals(1, f.observer.update(
                    "UPDATE complaint_journal_control SET desired_configuration_hash = ? WHERE data_scope_id = ?", ByteArray(32) { 23 }, f.scope.id))
                TestVerifiedDeleteRefusalCut.PROOF_BYTES -> assertEquals(1, f.observer.update(
                    "UPDATE complaint_journal_publications SET verification_bytes = verification_bytes || decode('20', 'hex'), " +
                        "verification_hash = sha256(verification_bytes || decode('20', 'hex')) WHERE event_id = ?", f.eventId))
                TestVerifiedDeleteRefusalCut.WRITER -> assertEquals(1, f.observer.update(
                    "UPDATE complaint_journal_publications SET writer_generation = ? WHERE event_id = ?", UUID.randomUUID(), f.eventId))
                TestVerifiedDeleteRefusalCut.MISSING_SEALED_AUDIT -> assertEquals(1, f.observer.update(
                    "DELETE FROM audit_log WHERE complaint_data_scope_id = ? AND action = 'COMPLAINT_TEST_RUN_SEALED'", f.scope.id))
            }
            val damaged = f.image()
            val original = f.begin()
            assertThrows<TestRunVerifiedOwnerDeleteExceptionV1> { original.complete() }
            f.assertReleased()
            assertEquals(damaged, f.image(), "Refusal cannot repair/mint proof, reconstruct missing primary rows or erase content: $cut")
            assertTrue(f.jdbc.calls.isNotEmpty())
            assertTrue(f.jdbc.calls.all { it.path === PersistencePhasePath.COMPLAINT_OWNER_DELETE_RELOAD })
            val calls = f.jdbc.calls.size
            assertThrows<TestRunVerifiedOwnerDeleteExceptionV1> { original.complete() }
            assertEquals(calls, f.jdbc.calls.size)
        }

    fun originalCompletionFailure(tls: VersionBoundPersistenceConnectedFixture, cut: TestRegistrationCompletionCut, applyPhase: Boolean) =
        withVerifiedOwnerDeleteRun(tls) { f ->
            val before = f.image()
            val counters = f.p.counters()
            val original = f.begin()
            val resource = Any()
            val sentinel = Any()
            var bound = false
            var selected: PersistencePhaseContext? = null
            f.jdbc.after = { call ->
                if (selected == null && call.sql == (if (applyPhase) OwnerDeletePersistenceSql.COMPLETE_RECEIPT else TestRunSealingSqlV1.readAudit)) {
                    selected = call.phase
                    if (cut === TestRegistrationCompletionCut.DEFERRED_COMMIT_UNKNOWN) {
                        val jdbc = JdbcTemplate(f.runtime.pools.deletion)
                        jdbc.execute("CREATE TEMP TABLE kira_verified_delete_commit_cut (id int UNIQUE DEFERRABLE INITIALLY DEFERRED)")
                        assertEquals(2, jdbc.update("INSERT INTO kira_verified_delete_commit_cut VALUES (1), (1)"))
                    } else TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                        override fun beforeCommit(readOnly: Boolean) {
                            if (cut === TestRegistrationCompletionCut.BEFORE_COMMIT) throw IllegalStateException("Synthetic verified deletion beforeCommit failure.")
                        }
                        override fun afterCommit() {
                            if (cut === TestRegistrationCompletionCut.UNRESOLVED_RELEASE) {
                                TransactionSynchronizationManager.bindResource(resource, sentinel)
                                bound = true
                            }
                            if (cut !== TestRegistrationCompletionCut.BEFORE_COMMIT) throw IllegalStateException("Synthetic verified deletion acknowledgment loss.")
                        }
                    })
                }
            }
            try {
                assertThrows<TestRunVerifiedOwnerDeleteExceptionV1> { original.complete() }
                if (cut === TestRegistrationCompletionCut.UNRESOLVED_RELEASE) {
                    assertTrue(bound)
                    assertSame(selected, PersistencePhaseOwnership.current())
                    assertTrue(checkNotNull(selected).quarantined())
                    val calls = f.jdbc.calls.size
                    assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { f.begin() }
                    assertEquals(calls, f.jdbc.calls.size, "Unproven original physical/Spring release blocks even a fresh attempt.")
                }
            } finally {
                f.jdbc.after = {}
                if (bound) assertSame(sentinel, TransactionSynchronizationManager.unbindResource(resource))
                requireConnectionFree() // Same original cleanup settles; it never rehabilitates that original result.
            }
            f.assertReleased()
            val outcome = when (cut) {
                TestRegistrationCompletionCut.BEFORE_COMMIT -> PersistenceDatabaseOutcome.ROLLED_BACK
                TestRegistrationCompletionCut.DEFERRED_COMMIT_UNKNOWN -> PersistenceDatabaseOutcome.UNKNOWN
                else -> PersistenceDatabaseOutcome.COMMITTED
            }
            assertEquals(outcome, checkNotNull(selected).databaseOutcome())
            val applied = applyPhase && outcome === PersistenceDatabaseOutcome.COMMITTED
            if (applied) f.assertApplied(counters) else assertEquals(before, f.image())
            if (!applyPhase) assertTrue(f.jdbc.calls.all { it.path === PersistencePhasePath.COMPLAINT_OWNER_DELETE_RELOAD }, "Failed/unknown/unreleased reload cannot issue work to APPLY.")
            val afterFailure = f.image()
            val calls = f.jdbc.calls.size
            assertThrows<TestRunVerifiedOwnerDeleteExceptionV1> { original.complete() }
            assertEquals(calls, f.jdbc.calls.size)
            assertEquals(afterFailure, f.image())

            f.jdbc.reset()
            val fresh = f.begin() // Same retained registration/resources; a new genuine exact reload, never a supplied cleanup receipt.
            assertSame(ComplaintOwnerDeleteReceipt.Applied, fresh.complete())
            f.assertSuccessfulPhases(fresh)
            f.assertApplied(counters)
            if (applied) assertEquals(afterFailure, f.image(), "Lost acknowledgment replay cannot duplicate committed erasure/accounting/audit.")
            assertEquals(before.filterKeys { it !in changed }, f.image().filterKeys { it !in changed })
            assertThrows<TestRunVerifiedOwnerDeleteExceptionV1> { original.complete() }
        }

    private fun assertLockOrder(f: TestRunVerifiedOwnerDeleteFixture) {
        f.jdbc.calls.groupBy { it.phase }.forEach { (_, calls) ->
            val sql = calls.map { it.sql }
            val receipt = sql.indexOfFirst { it.contains("FROM complaint_idempotency_receipts r") }
            val publication = sql.indexOfFirst { it.contains("FROM complaint_journal_publications WHERE") }
            val reserve = sql.indexOfFirst { it.contains("FROM complaint_recovery_capacity_reservations WHERE") }
            val counters = sql.indexOfFirst { it.contains("FROM complaint_capacity_counters") }
            val run = sql.indexOf(TestRunSealingSqlV1.lockRun)
            assertTrue(receipt >= 0 && publication > receipt && reserve > publication && counters > reserve && run > counters)
            assertEquals(1, sql.count { it == TestRunSealingSqlV1.readAudit })
            assertFalse(sql.any { it == TestRunSealingSqlV1.lockAudit }, "The pre-domain audit comparison is not an inverted audit row lock.")
            if (calls.first().path === PersistencePhasePath.COMPLAINT_OWNER_DELETE_APPLY) assertTrue(sql.indexOf(OwnerDeletePersistenceSql.LOCK_INSTALLATION) > run)
        }
    }

    private val changed = setOf("complaint_resource_ids", "complaints", "complaint_idempotency_receipts", "complaint_journal_publications",
        "complaint_recovery_capacity_reservations", "complaint_deletion_journal_applied", "audits", "counters")
}
