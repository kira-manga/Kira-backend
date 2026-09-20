package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceNanoClock
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.OwnerDeletePersistenceSql
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOwnerDeleteContinuationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunPreparedOwnerDeleteExceptionV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Timestamp
import java.util.UUID
import java.util.concurrent.CancellationException

/** New page boundaries only; existing PREPARED/VERIFIED cases retain the per-primary failure matrix. */
internal object TestRunOwnerDeletePageCases {
    fun mixedPageAndReplay(tls: VersionBoundPersistenceConnectedFixture) =
        withVerifiedOwnerDeleteRun(tls, verified = false, additionalVerified = 2) { f ->
            val before = f.image()
            val counters = f.p.counters()
            val original = f.beginPage()
            var sawReleasedSelection = false
            f.provider.beforePrepare = {
                f.assertDatabaseReleased()
                val selection = f.jdbc.observations.keys.first()
                assertEquals(PersistenceDatabaseOutcome.COMMITTED, selection.databaseOutcome())
                assertTrue(selection.testRunOwnerDeleteCleanupProven(original))
                assertEquals(before, f.image(), "Selection and per-primary RELOAD are read-only and released before provider work.")
                val child = ownedCutField(f.jdbc.observations.keys.last(), "testRunOwnerDelete") as TestRunOwnerDeleteContinuationV1
                assertSame(original.budget, child.budget, "A selected primary cannot start a fresh enclosing deadline.")
                sawReleasedSelection = true
            }
            val first = try { original.completePage() } finally { f.provider.beforePrepare = f::assertDatabaseReleased }
            assertTrue(sawReleasedSelection)
            assertEquals(2, first.completedPrimaries); assertTrue(first.moreObserved)
            f.assertReleased()
            f.assertApplyCounters(counters, 2)
            f.histories.take(2).forEach { f.assertAppliedRows(it.target, it.key, it.eventId) }
            assertEquals("VERIFIED", f.publicationState(f.histories.last().eventId))
            assertEquals("AUTHORIZED_DELETE", f.receiptState(f.histories.last().key))
            assertEquals(1, f.jdbc.calls.count { it.sql == OwnerDeletePersistenceSql.SELECT_REGISTERED_PRIMARY_PAGE })
            assertTrue(f.jdbc.calls.filter { it.phase === f.jdbc.observations.keys.first() }.none {
                it.sql.trimStart().startsWith("UPDATE") || it.sql.trimStart().startsWith("INSERT") || it.sql.trimStart().startsWith("DELETE")
            })
            assertThrows<TestRunPreparedOwnerDeleteExceptionV1> { original.completePage() }

            f.jdbc.reset()
            val afterFirst = f.p.counters()
            val second = f.beginPage().completePage()
            assertEquals(1, second.completedPrimaries); assertFalse(second.moreObserved)
            f.assertApplyCounters(afterFirst)
            f.histories.forEach { f.assertAppliedRows(it.target, it.key, it.eventId) }
            val after = f.image()
            val providers = f.providerImage()
            f.jdbc.reset()
            val empty = f.beginPage().completePage()
            assertEquals(0, empty.completedPrimaries); assertFalse(empty.moreObserved)
            assertEquals(after, f.image(), "An empty local page cannot convert PARTIAL or release future identity/alias allowances.")
            assertEquals(providers, f.providerImage())
            assertEquals(1, f.provider.requests.count { it.kind == "PUT" })
            assertEquals(1, f.jdbc.observations.size, "An empty page performs only the original read-only selection.")
            f.assertReleased()
        }

    fun outstandingCorruptionIsNotSkipped(tls: VersionBoundPersistenceConnectedFixture) = withVerifiedOwnerDeleteRun(tls, verified = false) { f ->
        val receipt = checkNotNull(f.observer.queryForObject("SELECT to_jsonb(r)::text FROM complaint_idempotency_receipts r WHERE actor_id = ? AND idempotency_key = ?",
            String::class.java, f.actor.id, f.key))
        val publication = checkNotNull(f.observer.queryForObject("SELECT to_jsonb(p)::text FROM complaint_journal_publications p WHERE event_id = ?", String::class.java, f.eventId))
        for (cut in Corruption.entries) {
            val strayKey = UUID.randomUUID()
            try {
                when (cut) {
                    Corruption.ORPHANED_PUBLICATION -> assertEquals(1, f.observer.update("DELETE FROM complaint_idempotency_receipts WHERE actor_id = ? AND idempotency_key = ?", f.actor.id, f.key))
                    Corruption.IN_PROGRESS_RECEIPT -> assertEquals(1, f.observer.update("INSERT INTO complaint_idempotency_receipts " +
                        "(actor_kind, actor_id, idempotency_key, operation, fingerprint, target_ids, data_scope_id, test_only, state, created_at) " +
                        "VALUES ('INSTALLATION', ?, ?, 'OWNER_DELETE', ?, ARRAY[?::uuid], ?, true, 'IN_PROGRESS', clock_timestamp())",
                        f.actor.id, strayKey, ByteArray(32) { 91 }, f.target, f.scope.id))
                    Corruption.LATER_AUTHORIZATION -> {
                        val late = f.observer.queryForObject("SELECT sealed_at + interval '1 minute' FROM complaint_test_runs WHERE data_scope_id = ?", Timestamp::class.java, f.scope.id)
                        assertEquals(1, f.observer.update("UPDATE complaint_idempotency_receipts SET authorized_at = ? WHERE actor_id = ? AND idempotency_key = ?", late, f.actor.id, f.key))
                        assertEquals(1, f.observer.update("UPDATE complaint_journal_publications SET created_at = ? WHERE event_id = ?", late, f.eventId))
                    }
                    Corruption.FOREIGN_WRITER -> assertEquals(1, f.observer.update("UPDATE complaint_journal_publications SET writer_generation = ? WHERE event_id = ?", UUID.randomUUID(), f.eventId))
                    Corruption.RECEIPT_MISMATCH -> assertEquals(1, f.observer.update("UPDATE complaint_idempotency_receipts SET fingerprint = ? WHERE actor_id = ? AND idempotency_key = ?", ByteArray(32) { 91 }, f.actor.id, f.key))
                }
                val before = f.image()
                val providers = f.providerImage()
                val original = f.beginPage()
                assertThrows<TestRunPreparedOwnerDeleteExceptionV1> { original.completePage() }
                f.assertReleased()
                assertEquals(before, f.image(), "A pending orphan/invalid/later/foreign row is a refusal, not omitted work or a repair.")
                assertEquals(providers, f.providerImage())
                assertTrue(f.jdbc.calls.all { it.path === PersistencePhasePath.COMPLAINT_OWNER_DELETE_RELOAD })
                val calls = f.jdbc.calls.size
                assertThrows<TestRunPreparedOwnerDeleteExceptionV1> { original.completePage() }
                assertEquals(calls, f.jdbc.calls.size)
            } finally {
                // Restore only this fixture's deliberately corrupted paid history; never a production recovery issuer.
                f.observer.update("UPDATE complaint_journal_publications SET (created_at, writer_generation) = " +
                    "(SELECT x.created_at, x.writer_generation FROM jsonb_populate_record(NULL::complaint_journal_publications, ?::jsonb) x) WHERE event_id = ?", publication, f.eventId)
                f.observer.update("DELETE FROM complaint_idempotency_receipts WHERE actor_id = ? AND idempotency_key = ?", f.actor.id, strayKey)
                f.observer.update("DELETE FROM complaint_idempotency_receipts WHERE actor_id = ? AND idempotency_key = ?", f.actor.id, f.key)
                assertEquals(1, f.observer.update("INSERT INTO complaint_idempotency_receipts SELECT (jsonb_populate_record(NULL::complaint_idempotency_receipts, ?::jsonb)).*", receipt))
                f.jdbc.reset()
            }
        }
    }

    fun selectionRequiresOriginalCommitAndRelease(tls: VersionBoundPersistenceConnectedFixture) = withVerifiedOwnerDeleteRun(tls, verified = false) { f ->
        val before = f.image()
        for (cut in listOf(TestRegistrationCompletionCut.DEFERRED_COMMIT_UNKNOWN, TestRegistrationCompletionCut.UNRESOLVED_RELEASE)) {
            val original = f.beginPage()
            val resource = Any()
            val sentinel = Any()
            var bound = false
            var selected: PersistencePhaseContext? = null
            f.jdbc.after = { call ->
                if (call.sql == OwnerDeletePersistenceSql.SELECT_REGISTERED_PRIMARY_PAGE) {
                    selected = call.phase
                    if (cut === TestRegistrationCompletionCut.DEFERRED_COMMIT_UNKNOWN) {
                        val jdbc = JdbcTemplate(f.runtime.pools.deletion)
                        jdbc.execute("CREATE TEMP TABLE kira_owner_delete_page_commit_cut (id int UNIQUE DEFERRABLE INITIALLY DEFERRED)")
                        assertEquals(2, jdbc.update("INSERT INTO kira_owner_delete_page_commit_cut VALUES (1), (1)"))
                    } else TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                        override fun afterCommit() {
                            TransactionSynchronizationManager.bindResource(resource, sentinel)
                            bound = true
                            error("Synthetic page selection acknowledgment loss.")
                        }
                    })
                }
            }
            try {
                assertThrows<TestRunPreparedOwnerDeleteExceptionV1> { original.completePage() }
                if (bound) {
                    assertSame(selected, PersistencePhaseOwnership.current())
                    assertTrue(checkNotNull(selected).quarantined())
                    assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { f.beginPage() }
                }
            } finally {
                f.jdbc.after = {}
                if (bound) assertSame(sentinel, TransactionSynchronizationManager.unbindResource(resource))
                requireConnectionFree()
            }
            f.assertReleased()
            assertEquals(if (cut === TestRegistrationCompletionCut.DEFERRED_COMMIT_UNKNOWN) PersistenceDatabaseOutcome.UNKNOWN else PersistenceDatabaseOutcome.COMMITTED,
                checkNotNull(selected).databaseOutcome())
            assertEquals(before, f.image())
            assertEquals(1, f.jdbc.observations.size, "No child may start from an unknown or unreleased selection.")
            assertTrue(f.provider.requests.isEmpty() && f.provider.kms.requests.isEmpty())
            val calls = f.jdbc.calls.size
            assertThrows<TestRunPreparedOwnerDeleteExceptionV1> { original.completePage() }
            assertEquals(calls, f.jdbc.calls.size)
            f.jdbc.reset()
        }
    }

    fun outerCancellationAndBudgetStopThePage(tls: VersionBoundPersistenceConnectedFixture) {
        var nanos = 0L
        withVerifiedOwnerDeleteRun(tls, verified = false, clock = PersistenceNanoClock { nanos }, additionalVerified = 1) { f ->
            val before = f.image()
            val counters = f.p.counters()
            val cancelled = f.beginPage()
            f.jdbc.after = { call -> if (call.sql == OwnerDeletePersistenceSql.SELECT_REGISTERED_PRIMARY_PAGE) throw CancellationException("Synthetic page cancellation.") }
            try { assertThrows<CancellationException> { cancelled.completePage() } } finally { f.jdbc.after = {} }
            f.assertReleased()
            assertEquals(before, f.image())
            assertEquals(1, f.jdbc.observations.size)
            assertTrue(f.provider.requests.isEmpty() && f.provider.kms.requests.isEmpty())
            val calls = f.jdbc.calls.size
            assertThrows<CancellationException> { cancelled.completePage() }
            assertEquals(calls, f.jdbc.calls.size)
            f.jdbc.reset()

            val original = f.beginPage()
            f.jdbc.after = { call ->
                if (call.sql == OwnerDeletePersistenceSql.SELECT_REGISTERED_PRIMARY_PAGE) {
                    TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                        override fun afterCommit() { nanos = 100_000_000L }
                    })
                }
            }
            // Selection consumes 100ms of the outer budget. A wrongly restarted child's deadline
            // would still have 99ms, but the original page has expired after actual native close.
            f.provider.onClientClose = { nanos = (f.process.catalogReadback.totalAttemptMillis + 1) * 1_000_000L }
            try { assertThrows<TestRunPreparedOwnerDeleteExceptionV1> { original.completePage() } }
            finally { f.jdbc.after = {}; f.provider.onClientClose = {} }
            f.assertReleased()
            assertEquals(before, f.image())
            assertEquals(2, f.jdbc.observations.size, "Only selection and the first primary RELOAD; no VERIFY/APPLY or next child.")
            assertEquals(0L, f.process.publicationLanes.activeOwners().totalOwners)
            assertTrue(f.jdbc.calls.all { it.path === PersistencePhasePath.COMPLAINT_OWNER_DELETE_RELOAD })
            assertEquals(1, f.provider.requests.count { it.kind == "PUT" })
            val providers = f.providerImage()
            assertThrows<TestRunPreparedOwnerDeleteExceptionV1> { original.completePage() }
            assertEquals(providers, f.providerImage())
            f.jdbc.reset()

            val fresh = f.beginPage().completePage()
            assertEquals(2, fresh.completedPrimaries); assertFalse(fresh.moreObserved)
            f.assertApplyCounters(counters, 2)
            f.histories.forEach { f.assertAppliedRows(it.target, it.key, it.eventId) }
            assertEquals(1, f.provider.requests.count { it.kind == "PUT" }, "An explicit fresh page adopts the same durable version, never republishes it.")
            f.assertReleased()
        }
    }

    private enum class Corruption { ORPHANED_PUBLICATION, IN_PROGRESS_RECEIPT, LATER_AUTHORIZATION, FOREIGN_WRITER, RECEIPT_MISMATCH }
}
