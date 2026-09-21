package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.common.infrastructure.persistence.awaitLifecycleFact
import me.manga.kira.backend.complaint.domain.InstallationEnrollmentDisposition
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveFirstSealStorageV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveFirstCutV1
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.io.IOException
import java.io.InterruptedIOException
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.CancellationException

internal enum class TestFirstCutDriftV1 { RAW_REPLICA, FULL_D, GLOBAL_RESTORE, HISTORY, RUN_XMIN, CURRENT_FOREIGN_LEASE, LEASE_OVERFLOW }
internal enum class TestFirstCutCaptureDriftV1 { FOREIGN_OWNER, EXPIRED_LEASE, FULL_D, GLOBAL_HEAD, RUN_XMIN }
internal enum class TestFirstCutProviderCutV1 { DEADLINE, CANCELLATION, INTERRUPTED_IO, FATAL, CLOSE_RECEIPT }
internal enum class TestFirstCutRequestCutV1 { BEFORE_COMMIT, DEFERRED_COMMIT_UNKNOWN, AFTER_COMMIT, UNRESOLVED_RELEASE }
internal enum class TestFirstCutNativeCutV1 { DEFERRED_COMMIT_UNKNOWN, KNOWN_COMMIT_LATE_RETURN }
internal enum class TestFirstCutClosedV1 { REGISTRATION, ROOT, SEALED_RUN, MAINTENANCE }

/** Genuine original producers on existing TLS/PG/raw SDK fixtures. Negative SQL faults never supply a success. */
internal object TestActiveFirstCutCasesV1 {
    fun paidRequestThenReleasedNativeFirstRange(tls: VersionBoundPersistenceConnectedFixture, enrolled: Boolean) = withTestActiveFirstCut(tls) { f ->
        if (enrolled) f.initial.withExchange { exchange ->
            assertEquals(InstallationEnrollmentDisposition.CREATED, exchange.enroll(f.initial.candidate()).disposition)
            exchange.assertReleased()
        }
        val before = f.counters()
        val outside = f.outsideCut()
        val global = f.globalImage()
        val other = f.otherCounterImage()
        val captured = f.captureWhileShared { _, _ ->
            f.assertCharge(before)
            assertEquals(outside, f.outsideCut(), "REQUEST cannot spend enrollment/terminal reserve or create history/content.")
            assertEquals(global, f.globalImage())
        }.getOrThrow()
        val handoff = captured.claimSeal(f.registration, f.assembly)
        handoff.requireRetained()
        assertSame(f.registration, handoff.registration); assertSame(f.assembly, handoff.assembly); assertSame(f.process, handoff.process)
        val slot = handoff.slot
        val control = f.control()
        val paid = f.paid()
        assertEquals(f.scope, slot.identity.scope)
        assertEquals(1L, slot.rotationSequence); assertEquals(1L, slot.epochStart); assertEquals(1L, slot.epochEnd); assertEquals(2L, slot.epochAfter)
        assertEquals(slot.operationToken, paid["operation_token"]); assertEquals(slot.operationToken, control["rotation_id"])
        assertEquals("CAPTURED", control["rotation_state"]); assertEquals(2L, control["publication_epoch"])
        assertEquals(false, control["scan_requested"]); assertNull(control["lease_owner"]); assertNull(control["lease_expires_at"])
        assertEquals(slot.requestOwner, slot.captureOwner); assertEquals(slot.requestToken, slot.captureToken)
        assertEquals(slot.captureToken, control["lease_token"], "Relinquishment preserves the monotonic token for A's higher-token sealer.")
        assertEquals(TestActiveFirstSealStorageV1.STORAGE_BYTES, paid["charged_storage_bytes"])
        assertEquals("RESERVED", paid["state"]); assertNotNull(paid["captured_at"])
        listOf("object_key", "canonical_bytes", "wire_bytes", "preparing_fencing_token").forEach { assertNull(paid[it]) }
        listOf("seal_state", "seal_bytes", "checkpoint_result", "checkpoint_bytes").forEach { assertNull(control[it]) }
        assertArrayEquals(f.process.configurationHashBytes(), slot.identity.configurationHash())
        assertArrayEquals(f.process.consumers.journalConfiguration.canonicalBytes(), f.p.f.rows.evidence.journal.canonicalBytes())
        val fingerprint = slot.fingerprint(); fingerprint.fill(0)
        assertFalse(fingerprint.contentEquals(slot.fingerprint()))
        assertEquals(global, f.globalImage()); assertEquals(outside, f.outsideCut()); assertEquals(other, f.otherCounterImage())
        f.assertCharge(before)
        assertEquals(PersistenceDatabaseOutcome.COMMITTED, checkNotNull(f.observedNative.get()).failure().databaseOutcome)
        assertTrue(f.nativeEntry().jdbc.terminalCompletion().reclaimed())
        assertEquals(0, f.native.sts.createdClients + f.native.kms.createdClients + f.native.s3Created,
            "C has no actual seal, ordinary publication, scan or content authority.")
        assertEquals(1, f.probe.steps.count { it == "charge:storage_bytes" })
        val request = f.probe.calls.filter { it.path === FIRST_CUT_REQUEST }.map { it.step }
        assertTrue(request.indexOf("test-first-cut-history-lock") < request.indexOf("counters"))
        assertTrue(request.lastIndexOf("counters") < request.indexOf("test-first-cut-run-lock"))
        assertTrue(request.indexOf("test-first-cut-run-lock") < request.indexOf("test-first-cut-slot-lock"))
        assertTrue(request.indexOf("test-first-cut-slot-lock") < request.indexOf("charge:storage_bytes"))
        assertTrue(request.indexOf("charge:storage_bytes") < request.indexOf("test-first-cut-request"))
        assertTrue(request.indexOf("test-first-cut-request") < request.indexOf("test-first-cut-insert"))
        assertThrows<RuntimeException> { captured.claimSeal(f.registration, f.assembly) }
        val exact = f.controlImage() to f.paidImage()
        val paidCounters = f.counters()
        assertThrows<RuntimeException> { f.begin().capture(credentials, credentials) }
        assertEquals(exact, f.controlImage() to f.paidImage()); assertEquals(paidCounters, f.counters())
        assertEquals(1, f.probe.steps.count { it == "charge:storage_bytes" }, "No adopt/second slot/second charge from durable CAPTURED.")
    }

    fun independentOrdinaryHeadroom(tls: VersionBoundPersistenceConnectedFixture, exact: Boolean) = withTestActiveFirstCut(tls) { f ->
        val price = TestActiveFirstSealStorageV1.STORAGE_BYTES
        // Aggregate negative/limit boundary fixture only, selected before the tested current read.
        // Preserve the installed P; do not falsify a price, reserve spend or successful row.
        assertEquals(1, f.initial.foreignUpdate(
            "UPDATE complaint_capacity_counters SET actual_units = creation_limit - recovery_reserved_units - test_reserved_units - ?, " +
                "free_units = hard_limit - creation_limit + ? WHERE name = 'storage_bytes'", price - if (exact) 0 else 1, price - if (exact) 0 else 1))
        val before = f.counters(); val run = f.outsideCut()
        if (exact) {
            f.capture(); f.assertCharge(before)
        } else {
            val original = f.begin()
            assertThrows<RuntimeException> { f.capture(original) }
            assertEquals(before, f.counters()); assertTrue(f.paidImage().isEmpty())
            assertEquals(0L, f.control()["rotation_sequence"])
            assertTrue(f.entries().isEmpty())
            assertThrows<RuntimeException> { TestActiveFirstCutV1.Captured.issue(original) }
        }
        assertEquals(run, f.outsideCut())
    }

    fun rawCurrentAndForeignLeaseRefuse(tls: VersionBoundPersistenceConnectedFixture, cut: TestFirstCutDriftV1) = withTestActiveFirstCut(tls) { f ->
        val original = f.begin()
        val boundary = f.p.f.http.beforeRead
        var injected = false
        var negative = f.controlImage(); var outside = f.outsideCut()
        val before = f.counters()
        f.p.f.http.beforeRead = {
            boundary()
            if (!injected) {
                when (cut) {
                    TestFirstCutDriftV1.RAW_REPLICA -> f.p.f.http.replicaVersion = null
                    TestFirstCutDriftV1.FULL_D -> assertEquals(1, f.initial.foreignUpdate("UPDATE complaint_journal_control SET desired_configuration_hash = decode(repeat('dc',32),'hex') WHERE data_scope_id = ?", f.scope))
                    TestFirstCutDriftV1.GLOBAL_RESTORE -> assertEquals(1, f.initial.foreignUpdate("UPDATE complaint_journal_control SET restore_identity = ? WHERE data_scope_id = ?", UUID.randomUUID(), UUID(0L, 0L)))
                    TestFirstCutDriftV1.HISTORY -> assertEquals(1, f.initial.foreignUpdate("UPDATE complaint_catalog_mutations SET retain_until = retain_until + interval '1 microsecond' WHERE successor_generation = 1"))
                    TestFirstCutDriftV1.RUN_XMIN -> assertEquals(1, f.initial.foreignUpdate("UPDATE complaint_test_runs SET enrolled_count = enrolled_count WHERE data_scope_id = ?", f.scope))
                    TestFirstCutDriftV1.CURRENT_FOREIGN_LEASE -> assertEquals(1, f.initial.foreignUpdate("UPDATE complaint_journal_control SET lease_owner = ?, lease_token = lease_token + 1, lease_expires_at = clock_timestamp() + interval '30 seconds' WHERE data_scope_id = ?", UUID.randomUUID(), f.scope))
                    TestFirstCutDriftV1.LEASE_OVERFLOW -> assertEquals(1, f.initial.foreignUpdate("UPDATE complaint_journal_control SET lease_token = 9223372036854775807 WHERE data_scope_id = ?", f.scope))
                }
                injected = true; negative = f.controlImage(); outside = f.outsideCut()
            }
        }
        try { assertThrows<RuntimeException> { f.capture(original) } } finally { f.p.f.http.beforeRead = boundary }
        assertTrue(injected)
        assertEquals(negative, f.controlImage()); assertEquals(outside, f.outsideCut()); assertEquals(before, f.counters())
        assertTrue(f.paidImage().isEmpty()); assertTrue(f.entries().isEmpty())
        assertFalse(f.probe.steps.contains("test-first-cut-acquire"))
        assertThrows<RuntimeException> { TestActiveFirstCutV1.Captured.issue(original) }
    }

    fun nativeRechecksAfterReleasedRequestAndExclusiveWait(tls: VersionBoundPersistenceConnectedFixture, cut: TestFirstCutCaptureDriftV1) = withTestActiveFirstCut(tls) { f ->
        val before = f.counters()
        var negative = ""; var paid = emptyList<String>()
        val result = f.captureWhileShared { _, _ ->
            when (cut) {
                TestFirstCutCaptureDriftV1.FOREIGN_OWNER -> assertEquals(1, f.initial.foreignUpdate(
                    "UPDATE complaint_journal_control SET lease_owner = ?, lease_token = lease_token + 1, lease_expires_at = clock_timestamp() + interval '30 seconds' WHERE data_scope_id = ?", UUID.randomUUID(), f.scope))
                TestFirstCutCaptureDriftV1.EXPIRED_LEASE -> assertEquals(1, f.initial.foreignUpdate(
                    "UPDATE complaint_journal_control SET lease_expires_at = updated_at WHERE data_scope_id = ?", f.scope))
                TestFirstCutCaptureDriftV1.FULL_D -> assertEquals(1, f.initial.foreignUpdate(
                    "UPDATE complaint_journal_control SET desired_configuration_hash = decode(repeat('cd',32),'hex') WHERE data_scope_id = ?", f.scope))
                TestFirstCutCaptureDriftV1.GLOBAL_HEAD -> assertEquals(1, f.initial.foreignUpdate(
                    "UPDATE complaint_journal_control SET accepted_catalog_hash = decode(repeat('cc',32),'hex') WHERE data_scope_id = ?", UUID(0L, 0L)))
                TestFirstCutCaptureDriftV1.RUN_XMIN -> assertEquals(1, f.initial.foreignUpdate(
                    "UPDATE complaint_test_runs SET enrolled_count = enrolled_count WHERE data_scope_id = ?", f.scope))
            }
            negative = f.controlImage(); paid = f.paidImage()
        }
        assertTrue(result.isFailure)
        assertEquals(negative, f.controlImage()); assertEquals(paid, f.paidImage())
        assertEquals("REQUESTED", f.control()["rotation_state"]); assertEquals(1L, f.control()["publication_epoch"])
        assertNotNull(f.control()["lease_owner"], "Failure never clears a stale/foreign scoped lease.")
        assertNull(f.paid()["capture_owner"]); f.assertCharge(before)
        val charged = f.counters()
        assertThrows<RuntimeException> { f.begin().capture(credentials, credentials) }
        assertEquals(paid, f.paidImage()); assertEquals(charged, f.counters(), "A paid REQUEST is durable, not a resumable original issuer.")
    }

    fun requestCompletionCutsAreAtomic(tls: VersionBoundPersistenceConnectedFixture, cut: TestFirstCutRequestCutV1) = withTestActiveFirstCut(tls) { f ->
        val original = f.begin()
        val counters = f.counters()
        val outside = f.outsideCut()
        var selected: PersistencePhaseContext? = null
        val key = Any(); val sentinel = Any(); var bound = false
        f.probe.afterSql = { step -> if (step == "test-first-cut-insert") {
            selected = checkNotNull(PersistencePhaseOwnership.current())
            if (cut === TestFirstCutRequestCutV1.DEFERRED_COMMIT_UNKNOWN) {
                val jdbc = JdbcTemplate(f.runtime.pools.catalogCoordinator.dataSource)
                jdbc.execute("CREATE TEMP TABLE kira_first_cut_commit_refusal (id int UNIQUE DEFERRABLE INITIALLY DEFERRED)")
                assertEquals(2, jdbc.update("INSERT INTO kira_first_cut_commit_refusal VALUES (1),(1)"))
            } else TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun beforeCommit(readOnly: Boolean) {
                    if (cut === TestFirstCutRequestCutV1.BEFORE_COMMIT) throw IllegalStateException("Synthetic REQUEST beforeCommit refusal.")
                }
                override fun afterCommit() {
                    if (cut === TestFirstCutRequestCutV1.UNRESOLVED_RELEASE) { TransactionSynchronizationManager.bindResource(key, sentinel); bound = true }
                    if (cut !== TestFirstCutRequestCutV1.BEFORE_COMMIT) throw IOException("Synthetic paid REQUEST return receipt loss.")
                }
            })
        } }
        try { assertThrows<RuntimeException> { f.capture(original) } }
        finally {
            f.probe.afterSql = {}
            if (bound) assertSame(sentinel, TransactionSynchronizationManager.unbindResource(key))
            requireConnectionFree()
        }
        val durable = cut in setOf(TestFirstCutRequestCutV1.AFTER_COMMIT, TestFirstCutRequestCutV1.UNRESOLVED_RELEASE)
        assertEquals(when (cut) {
            TestFirstCutRequestCutV1.BEFORE_COMMIT -> PersistenceDatabaseOutcome.ROLLED_BACK
            TestFirstCutRequestCutV1.DEFERRED_COMMIT_UNKNOWN -> PersistenceDatabaseOutcome.UNKNOWN
            else -> PersistenceDatabaseOutcome.COMMITTED
        }, checkNotNull(selected).databaseOutcome())
        if (durable) { f.assertCharge(counters); assertEquals("REQUESTED", f.control()["rotation_state"]); assertEquals(1, f.paidImage().size) }
        else { assertEquals(counters, f.counters()); assertEquals(0L, f.control()["rotation_sequence"]); assertTrue(f.paidImage().isEmpty()) }
        assertEquals(outside, f.outsideCut()); assertTrue(f.entries().isEmpty())
        assertThrows<RuntimeException> { TestActiveFirstCutV1.Captured.issue(original) }
        assertThrows<RuntimeException> { original.capture(credentials, credentials) }
    }

    fun nativeCommitOutcomeCannotBeRepaired(tls: VersionBoundPersistenceConnectedFixture, cut: TestFirstCutNativeCutV1) = withTestActiveFirstCut(tls) { f ->
        val original = f.begin(); val counters = f.counters()
        var installed = false
        f.beforeNativeSample = { session ->
            val stage = ownedCutField(session, "stage").toString()
            if (!installed && ((cut === TestFirstCutNativeCutV1.DEFERRED_COMMIT_UNKNOWN && stage == "REREAD") ||
                    (cut === TestFirstCutNativeCutV1.KNOWN_COMMIT_LATE_RETURN && session.failure().databaseOutcome === PersistenceDatabaseOutcome.COMMITTED))) {
                installed = true
                if (cut === TestFirstCutNativeCutV1.DEFERRED_COMMIT_UNKNOWN) {
                    // Genuine native PG COMMIT failure on the SAME original nonpooled connection, not a constructed UNKNOWN.
                    poolTestField<Connection>(session, "connection").createStatement().use {
                        it.execute("CREATE TEMP TABLE kira_native_first_cut_refusal (id int UNIQUE DEFERRABLE INITIALLY DEFERRED)")
                        assertEquals(2, it.executeUpdate("INSERT INTO kira_native_first_cut_refusal VALUES (1),(1)"))
                    }
                } else f.native.offsetNanos += (original.budget.remainingMillis(10_000) + 1_000) * 1_000_000
            }
        }
        try { assertThrows<RuntimeException> { f.capture(original) } }
        finally { f.beforeNativeSample = {}; f.native.offsetNanos = 0 }
        assertTrue(installed); f.awaitNativeReclaimed(); f.assertCharge(counters)
        val session = checkNotNull(f.observedNative.get())
        val committed = cut === TestFirstCutNativeCutV1.KNOWN_COMMIT_LATE_RETURN
        assertEquals(if (committed) PersistenceDatabaseOutcome.COMMITTED else PersistenceDatabaseOutcome.UNKNOWN, session.failure().databaseOutcome)
        assertEquals(if (committed) "CAPTURED" else "REQUESTED", f.control()["rotation_state"])
        assertEquals(if (committed) 2L else 1L, f.control()["publication_epoch"])
        if (committed) { assertNull(f.control()["lease_owner"]); assertNotNull(f.paid()["captured_at"]) }
        else { assertNotNull(f.control()["lease_owner"]); assertNull(f.paid()["captured_at"]) }
        val exact = f.controlImage() to f.paidImage()
        assertThrows<RuntimeException> { TestActiveFirstCutV1.Captured.issue(original) }
        assertThrows<RuntimeException> { original.capture(credentials, credentials) }
        assertEquals(exact, f.controlImage() to f.paidImage(), "Late physical cleanup cannot reconstruct a failed original.")
    }

    fun rawDeadlineSignalAndCloseAreSticky(tls: VersionBoundPersistenceConnectedFixture, cut: TestFirstCutProviderCutV1) = withTestActiveFirstCut(tls) { f ->
        val original = f.begin(); val before = f.p.image(); val global = f.globalImage()
        val boundary = f.p.f.http.beforeRead
        val fatal = FirstCutFatal()
        var injected = false
        f.p.f.http.beforeRead = {
            boundary()
            if (!injected && cut !== TestFirstCutProviderCutV1.CLOSE_RECEIPT) {
                injected = true
                when (cut) {
                    TestFirstCutProviderCutV1.DEADLINE -> f.native.offsetNanos += 11_000_000_000
                    TestFirstCutProviderCutV1.CANCELLATION -> throw CancellationException("Synthetic first-cut raw cancellation.")
                    TestFirstCutProviderCutV1.INTERRUPTED_IO -> throw InterruptedIOException("Synthetic first-cut raw interrupted I/O.")
                    TestFirstCutProviderCutV1.FATAL -> throw fatal
                    else -> error("Unexpected raw cut.")
                }
            }
        }
        f.p.f.http.afterReadClientClose = { if (!injected && cut === TestFirstCutProviderCutV1.CLOSE_RECEIPT) {
            injected = true; throw IOException("Synthetic raw client physical close lost return.")
        } }
        try {
            when (cut) {
                TestFirstCutProviderCutV1.CANCELLATION -> assertThrows<CancellationException> { f.capture(original) }
                TestFirstCutProviderCutV1.INTERRUPTED_IO -> { assertThrows<InterruptedException> { f.capture(original) }; assertTrue(Thread.currentThread().isInterrupted) }
                TestFirstCutProviderCutV1.FATAL -> assertSame(fatal, assertThrows<FirstCutFatal> { f.capture(original) })
                else -> assertThrows<RuntimeException> { f.capture(original) }
            }
        } finally {
            f.p.f.http.beforeRead = boundary; f.p.f.http.afterReadClientClose = {}; f.native.offsetNanos = 0; Thread.interrupted()
        }
        assertTrue(injected)
        assertEquals(before, f.p.image()); assertEquals(global, f.globalImage()); assertTrue(f.paidImage().isEmpty()); assertTrue(f.entries().isEmpty())
        assertSame(original, SignedActivationObservation.active(f.runtime.pools.catalogCoordinator))
        val reads = f.p.f.http.read.requests.size
        assertTrue(runCatching { original.capture(credentials, credentials) }.isFailure)
        Thread.interrupted()
        assertEquals(reads, f.p.f.http.read.requests.size)
        assertThrows<RuntimeException> { TestActiveFirstCutV1.Captured.issue(original) }
    }

    fun closedAndTerminalNeverCapture(tls: VersionBoundPersistenceConnectedFixture, cut: TestFirstCutClosedV1) = withTestActiveFirstCut(tls) { f ->
        val original = f.begin()
        when (cut) {
            TestFirstCutClosedV1.REGISTRATION -> f.registration.close()
            TestFirstCutClosedV1.ROOT -> f.runtime.stopWithoutWaiting()
            TestFirstCutClosedV1.SEALED_RUN -> assertEquals(1, f.initial.foreignUpdate(
                "UPDATE complaint_test_runs SET state = 'SEALED', sealed_at = clock_timestamp() WHERE data_scope_id = ?", f.scope))
            TestFirstCutClosedV1.MAINTENANCE -> assertEquals(1, f.initial.foreignUpdate(
                "UPDATE complaint_journal_control SET maintenance_closed = true, creation_closed = true WHERE data_scope_id = ?", f.scope))
        }
        val before = f.counters(); val negative = f.controlImage()
        assertThrows<RuntimeException> { f.capture(original) }
        assertEquals(before, f.counters()); assertEquals(negative, f.controlImage()); assertTrue(f.paidImage().isEmpty()); assertTrue(f.entries().isEmpty())
        assertThrows<RuntimeException> { TestActiveFirstCutV1.Captured.issue(original) }
    }

    fun immutableSlotAndUnpaidTransitionsRefuse(tls: VersionBoundPersistenceConnectedFixture) = withTestActiveFirstCut(tls) { f ->
        f.capture()
        val before = f.paidImage(); val counters = f.counters(); val control = f.controlImage()
        assertEquals(0, f.initial.foreignUpdate("UPDATE complaint_test_active_seal_intents SET state = state WHERE data_scope_id = ?", f.scope))
        listOf(
            "DELETE FROM complaint_test_active_seal_intents WHERE data_scope_id = ?",
            "UPDATE complaint_test_active_seal_intents SET charged_storage_bytes = 0 WHERE data_scope_id = ?",
            "UPDATE complaint_test_active_seal_intents SET capture_token = capture_token + 1 WHERE data_scope_id = ?",
            "UPDATE complaint_test_active_seal_intents SET request_token = request_token + 1 WHERE data_scope_id = ?",
            "UPDATE complaint_test_active_seal_intents SET state = 'CANONICAL' WHERE data_scope_id = ?",
            "UPDATE complaint_test_active_seal_intents SET state = 'WIRE_FROZEN' WHERE data_scope_id = ?",
            "UPDATE complaint_test_active_seal_intents SET wire_bytes = decode('00','hex') WHERE data_scope_id = ?",
            "INSERT INTO complaint_test_active_seal_intents SELECT * FROM complaint_test_active_seal_intents WHERE data_scope_id = ?",
        ).forEach { sql ->
            assertThrows<java.sql.SQLException> { f.initial.foreignUpdate(sql, f.scope) }
            assertEquals(before, f.paidImage())
        }
        assertEquals(counters, f.counters()); assertEquals(control, f.controlImage())
    }

    fun realFenceContentionRefusesBeforeCharge(tls: VersionBoundPersistenceConnectedFixture, maintenance: Boolean) = withTestActiveFirstCut(tls) { f ->
        val before = f.p.image(); val global = f.globalImage()
        f.independentTransaction { blocker, jdbc, _ ->
            val fence = if (maintenance) "complaint-maintenance-v1" else "complaint-journal-epoch"
            jdbc.execute("SELECT pg_advisory_xact_lock(hashtextextended('$fence', 0))")
            try { assertThrows<RuntimeException> { f.capture() } } finally { blocker.rollback() }
        }
        assertEquals(before, f.p.image()); assertEquals(global, f.globalImage())
        assertTrue(f.paidImage().isEmpty()); assertTrue(f.entries().isEmpty())
    }

    fun realNativeEpochWaitTimeoutKeepsPaidRequestAndLease(tls: VersionBoundPersistenceConnectedFixture) = withTestActiveFirstCut(tls) { f ->
        val before = f.counters()
        var control = ""; var paid = emptyList<String>()
        val outcome = f.captureWhileShared { _, entry ->
            control = f.controlImage(); paid = f.paidImage()
            awaitLifecycleFact(3_000) { entry.jdbc.terminalCompletion().reclaimed() }
        }
        assertTrue(outcome.isFailure)
        f.assertCharge(before); assertEquals(control, f.controlImage()); assertEquals(paid, f.paidImage())
        assertEquals("REQUESTED", f.control()["rotation_state"]); assertNotNull(f.control()["lease_owner"])
        assertNull(f.paid()["capture_owner"])
    }

    private val credentials get() = S3CatalogReadbackFixture.credentials
    private class FirstCutFatal : Error("Synthetic ACTIVE first-cut fatal signal.")
}
