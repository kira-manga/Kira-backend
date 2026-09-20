package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceNanoClock
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteReceipt
import me.manga.kira.backend.complaint.infrastructure.OwnerDeletePersistenceSql
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunPreparedOwnerDeleteExceptionV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunSealingSqlV1
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherFixture
import me.manga.kira.backend.complaint.journal.TestOwnerDeleteJournalPublisherFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

internal enum class TestPreparedDeleteRefusalCut { MISSING_RECEIPT, SEALED_EPOCH, AUTHORIZED_AFTER_SEAL }
internal enum class TestPreparedDeleteProviderCut { READBACK_FAILURE, LATE_NATIVE_CLOSE, CLOSED_REGISTRATION }

/** Same real history/registration/sealer and existing raw SDK fixture; no supplied work or cleanup proof. */
internal object TestRunPreparedOwnerDeleteCases {
    fun primaryAndReplay(tls: VersionBoundPersistenceConnectedFixture) = withVerifiedOwnerDeleteRun(tls, verified = false) { f ->
        val before = f.image()
        val counters = f.p.counters()
        val original = f.beginPrepared()
        var sawProvider = false
        var sawAtomicVerify = false
        var sawReleasedVerify = false
        f.provider.beforePrepare = {
            f.assertDatabaseReleased()
            val reload = f.jdbc.observations.keys.single()
            assertEquals(PersistencePhasePath.COMPLAINT_OWNER_DELETE_RELOAD, TestRunVerifiedOwnerDeleteFixture.path(reload))
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, reload.databaseOutcome())
            assertTrue(reload.testRunOwnerDeleteCleanupProven(original))
            assertEquals(before, f.image(), "Original RELOAD is positively committed/released and read-only before every provider call.")
            sawProvider = true
        }
        f.jdbc.after = { call ->
            if (call.sql == OwnerDeletePersistenceSql.RECORD_VERIFIED) {
                assertEquals(before, f.image(), "Uncommitted VERIFY cannot be used to erase content.")
                sawAtomicVerify = true
            }
        }
        f.jdbc.before = { call ->
            if (call.path === PersistencePhasePath.COMPLAINT_OWNER_DELETE_APPLY && !sawReleasedVerify) {
                val verify = f.jdbc.observations.keys.single { TestRunVerifiedOwnerDeleteFixture.path(it) === PersistencePhasePath.COMPLAINT_OWNER_DELETE_VERIFY }
                assertEquals(PersistenceDatabaseOutcome.COMMITTED, verify.databaseOutcome())
                assertTrue(verify.testRunOwnerDeleteCleanupProven(original))
                assertTrue(f.jdbc.observations.getValue(verify).lease.completion.quiescent())
                assertEquals("VERIFIED", f.publicationState())
                assertEquals(before.filterKeys { it != "complaint_journal_publications" }, f.image().filterKeys { it != "complaint_journal_publications" })
                sawReleasedVerify = true
            }
        }
        try { assertSame(ComplaintOwnerDeleteReceipt.Applied, original.complete()) }
        finally { f.jdbc.before = {}; f.jdbc.after = {}; f.provider.beforePrepare = f::assertDatabaseReleased }
        assertTrue(sawProvider && sawAtomicVerify && sawReleasedVerify)
        f.assertSuccessfulPhases(original, published = true)
        f.assertApplied(counters)
        assertEquals(1, f.provider.requests.count { it.kind == "PUT" })
        f.provider.requests.forEach(f.provider::assertSigned)
        f.provider.kms.requests.forEach { f.provider.assertKmsContext(it) }
        assertEquals(TestOwnerDeleteJournalPublisherFixture.VERSION,
            f.observer.queryForObject("SELECT object_version FROM complaint_journal_publications WHERE event_id = ?", String::class.java, f.eventId))
        val verifySql = f.jdbc.calls.filter { it.path === PersistencePhasePath.COMPLAINT_OWNER_DELETE_VERIFY }.map { it.sql }
        assertEquals(listOf(OwnerDeletePersistenceSql.LOCK_RECEIPT, OwnerDeletePersistenceSql.LOCK_PUBLICATION), verifySql.filter { it.contains("FOR UPDATE") })
        assertTrue(verifySql.containsAll(listOf(TestRunSealingSqlV1.readGlobalControl, TestRunSealingSqlV1.readScopeControl, TestRunSealingSqlV1.readRun, TestRunSealingSqlV1.readAudit)))
        val after = f.image()
        assertEquals(before.filterKeys { it !in changed }, after.filterKeys { it !in changed })
        val providers = f.providerImage()
        assertThrows<TestRunPreparedOwnerDeleteExceptionV1> { original.complete() }
        f.jdbc.reset()
        val replay = f.beginPrepared()
        assertSame(ComplaintOwnerDeleteReceipt.Applied, replay.complete())
        f.assertSuccessfulPhases(replay)
        assertEquals(after, f.image(), "An exact APPLIED replay is zero-delta and does not publish again.")
        assertEquals(providers, f.providerImage())
        assertTrue(f.jdbc.calls.none { it.sql.trimStart().startsWith("UPDATE") || it.sql.trimStart().startsWith("DELETE") || it.sql.trimStart().startsWith("INSERT") })

        val generic = f.ownership.enterComplaintOwnerDeleteVerify()
        try { generic.recordFailure(assertThrows<PersistencePhaseException> { generic.begin() }) }
        finally { generic.finish() }
        f.jdbc.reset()
        assertThrows<TestRunPreparedOwnerDeleteExceptionV1> { f.beginPrepared(operationKey = UUID.randomUUID()).complete() }
        f.assertReleased()
        assertEquals(after, f.image())
        assertEquals(providers, f.providerImage(), "Neither the generic closed-gate entry nor locators grant a provider operation.")
    }

    fun refusesBeforeDispatch(tls: VersionBoundPersistenceConnectedFixture, cut: TestPreparedDeleteRefusalCut) =
        withVerifiedOwnerDeleteRun(tls, verified = false) { f ->
            when (cut) {
                TestPreparedDeleteRefusalCut.MISSING_RECEIPT -> assertEquals(1, f.observer.update(
                    "DELETE FROM complaint_idempotency_receipts WHERE actor_id = ? AND idempotency_key = ?", f.actor.id, f.key))
                TestPreparedDeleteRefusalCut.SEALED_EPOCH -> assertEquals(1, f.observer.update(
                    "UPDATE complaint_journal_control SET publication_epoch = 12, seal_epoch = 11 WHERE data_scope_id = ?", f.scope.id))
                TestPreparedDeleteRefusalCut.AUTHORIZED_AFTER_SEAL -> {
                    val late = f.observer.queryForObject("SELECT sealed_at + interval '1 minute' FROM complaint_test_runs WHERE data_scope_id = ?", java.sql.Timestamp::class.java, f.scope.id)
                    assertEquals(1, f.observer.update("UPDATE complaint_idempotency_receipts SET authorized_at = ? WHERE actor_id = ? AND idempotency_key = ?", late, f.actor.id, f.key))
                    assertEquals(1, f.observer.update("UPDATE complaint_journal_publications SET created_at = ? WHERE event_id = ?", late, f.eventId))
                }
            }
            val before = f.image()
            val providers = f.providerImage()
            val original = f.beginPrepared()
            assertThrows<TestRunPreparedOwnerDeleteExceptionV1> { original.complete() }
            f.assertReleased()
            assertEquals(before, f.image())
            assertEquals(providers, f.providerImage(), "No network bytes without a valid earlier unsealed PREPARED primary.")
            assertTrue(f.jdbc.calls.all { it.path === PersistencePhasePath.COMPLAINT_OWNER_DELETE_RELOAD })
            val calls = f.jdbc.calls.size
            assertThrows<TestRunPreparedOwnerDeleteExceptionV1> { original.complete() }
            assertEquals(calls, f.jdbc.calls.size)
        }

    fun providerBoundary(tls: VersionBoundPersistenceConnectedFixture, cut: TestPreparedDeleteProviderCut) {
        var elapsedOffset = 0L
        val clock = PersistenceNanoClock { System.nanoTime() + elapsedOffset }
        withVerifiedOwnerDeleteRun(tls, verified = false, clock = clock) { f ->
            val before = f.image()
            val counters = f.p.counters()
            val wire = f.provider
            val original = f.beginPrepared()
            when (cut) {
                TestPreparedDeleteProviderCut.READBACK_FAILURE -> wire.respond = { request ->
                    if (request.kind == "GET") OwnerDeleteAllJournalPublisherFixture.errorReply(503) else wire.statefulReply(request)
                }
                TestPreparedDeleteProviderCut.LATE_NATIVE_CLOSE -> wire.onClientClose = {
                    // Advance only the original enclosing clock: a fresh provider-local deadline cannot rescue it.
                    elapsedOffset += (f.process.catalogReadback.totalAttemptMillis + 1) * 1_000_000L
                }
                TestPreparedDeleteProviderCut.CLOSED_REGISTRATION -> wire.respond = { request ->
                    wire.statefulReply(request).also { if (request.kind == "PUT") f.registration.close() }
                }
            }
            try { assertThrows<TestRunPreparedOwnerDeleteExceptionV1> { original.complete() } }
            finally { wire.respond = wire::statefulReply; wire.onClientClose = {} }
            f.assertReleased()
            assertEquals(0L, f.process.publicationLanes.activeOwners().totalOwners, "These returned original closes, not a deadline, release J.")
            assertEquals(before, f.image(), "Provider failure/late close/closed registration cannot reach VERIFY or APPLY.")
            assertEquals("PREPARED", f.publicationState())
            assertEquals(1, wire.requests.count { it.kind == "PUT" })
            assertTrue(f.jdbc.calls.all { it.path === PersistencePhasePath.COMPLAINT_OWNER_DELETE_RELOAD })
            val requests = f.providerImage()
            val calls = f.jdbc.calls.size
            assertThrows<TestRunPreparedOwnerDeleteExceptionV1> { original.complete() }
            assertEquals(requests, f.providerImage()); assertEquals(calls, f.jdbc.calls.size)
            if (cut === TestPreparedDeleteProviderCut.CLOSED_REGISTRATION) {
                assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { f.beginPrepared() }
            } else {
                f.jdbc.reset()
                val fresh = f.beginPrepared()
                assertSame(ComplaintOwnerDeleteReceipt.Applied, fresh.complete())
                f.assertSuccessfulPhases(fresh, published = true)
                f.assertApplied(counters)
                assertEquals(1, wire.requests.count { it.kind == "PUT" }, "Fresh exact reload/readback adopts only the original version; never a replacement PUT.")
                assertThrows<TestRunPreparedOwnerDeleteExceptionV1> { original.complete() }
            }
        }
    }

    fun completionBoundary(tls: VersionBoundPersistenceConnectedFixture, cut: TestRegistrationCompletionCut, verifyPhase: Boolean) =
        withVerifiedOwnerDeleteRun(tls, verified = false) { f ->
            val before = f.image()
            val counters = f.p.counters()
            val original = f.beginPrepared()
            val resource = Any()
            val sentinel = Any()
            var bound = false
            var selected: PersistencePhaseContext? = null
            val expectedPath = if (verifyPhase) PersistencePhasePath.COMPLAINT_OWNER_DELETE_VERIFY else PersistencePhasePath.COMPLAINT_OWNER_DELETE_RELOAD
            f.jdbc.after = { call ->
                if (selected == null && call.path === expectedPath && call.sql ==
                    (if (verifyPhase) OwnerDeletePersistenceSql.RECORD_VERIFIED else TestRunSealingSqlV1.readAudit)) {
                    selected = call.phase
                    if (cut === TestRegistrationCompletionCut.DEFERRED_COMMIT_UNKNOWN) {
                        val jdbc = JdbcTemplate(f.runtime.pools.deletion)
                        jdbc.execute("CREATE TEMP TABLE kira_prepared_owner_delete_commit_cut (id int UNIQUE DEFERRABLE INITIALLY DEFERRED)")
                        assertEquals(2, jdbc.update("INSERT INTO kira_prepared_owner_delete_commit_cut VALUES (1), (1)"))
                    } else TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                        override fun beforeCommit(readOnly: Boolean) {
                            if (cut === TestRegistrationCompletionCut.BEFORE_COMMIT) error("Synthetic prepared continuation beforeCommit failure.")
                        }
                        override fun afterCommit() {
                            if (cut === TestRegistrationCompletionCut.UNRESOLVED_RELEASE) {
                                TransactionSynchronizationManager.bindResource(resource, sentinel)
                                bound = true
                            }
                            if (cut !== TestRegistrationCompletionCut.BEFORE_COMMIT) error("Synthetic prepared continuation acknowledgment loss.")
                        }
                    })
                }
            }
            try {
                assertThrows<TestRunPreparedOwnerDeleteExceptionV1> { original.complete() }
                if (cut === TestRegistrationCompletionCut.UNRESOLVED_RELEASE) {
                    assertTrue(bound)
                    assertSame(selected, PersistencePhaseOwnership.current())
                    assertTrue(checkNotNull(selected).quarantined())
                    assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { f.beginPrepared() }
                }
            } finally {
                f.jdbc.after = {}
                if (bound) assertSame(sentinel, TransactionSynchronizationManager.unbindResource(resource))
                requireConnectionFree() // Original cleanup, never a supplied success predicate.
            }
            f.assertReleased()
            val outcome = when (cut) {
                TestRegistrationCompletionCut.BEFORE_COMMIT -> PersistenceDatabaseOutcome.ROLLED_BACK
                TestRegistrationCompletionCut.DEFERRED_COMMIT_UNKNOWN -> PersistenceDatabaseOutcome.UNKNOWN
                else -> PersistenceDatabaseOutcome.COMMITTED
            }
            assertEquals(outcome, checkNotNull(selected).databaseOutcome())
            val recorded = verifyPhase && outcome === PersistenceDatabaseOutcome.COMMITTED
            assertEquals(if (recorded) "VERIFIED" else "PREPARED", f.publicationState())
            assertEquals("AUTHORIZED_DELETE", f.receiptState())
            assertEquals(counters, f.p.counters())
            if (recorded) assertEquals(before.filterKeys { it != "complaint_journal_publications" }, f.image().filterKeys { it != "complaint_journal_publications" })
            else assertEquals(before, f.image())
            assertTrue(f.jdbc.calls.none { it.path === PersistencePhasePath.COMPLAINT_OWNER_DELETE_APPLY })
            if (!verifyPhase) assertTrue(f.provider.requests.isEmpty() && f.provider.kms.requests.isEmpty(), "No private work/provider after failed, UNKNOWN or unreleased reload.")
            val failedState = f.image()
            val requests = f.providerImage()
            val calls = f.jdbc.calls.size
            assertThrows<TestRunPreparedOwnerDeleteExceptionV1> { original.complete() }
            assertEquals(calls, f.jdbc.calls.size); assertEquals(requests, f.providerImage()); assertEquals(failedState, f.image())
            f.jdbc.reset()
            val fresh = f.beginPrepared()
            assertSame(ComplaintOwnerDeleteReceipt.Applied, fresh.complete())
            f.assertSuccessfulPhases(fresh, published = !recorded)
            f.assertApplied(counters)
            if (recorded) assertEquals(requests, f.providerImage(), "A committed VERIFY tail resumes without reopening a provider.")
            assertEquals(1, f.provider.requests.count { it.kind == "PUT" })
            assertEquals(before.filterKeys { it !in changed }, f.image().filterKeys { it !in changed })
            assertThrows<TestRunPreparedOwnerDeleteExceptionV1> { original.complete() }
        }

    private val changed = setOf("complaint_resource_ids", "complaints", "complaint_idempotency_receipts", "complaint_journal_publications",
        "complaint_recovery_capacity_reservations", "complaint_deletion_journal_applied", "audits", "counters")
}
