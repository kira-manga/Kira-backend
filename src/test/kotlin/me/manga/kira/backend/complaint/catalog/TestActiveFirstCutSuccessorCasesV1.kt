package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.InstallationEnrollmentDisposition
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveFirstCutSuccessorV1
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

internal enum class TestFirstCutSuccessorDriftV1 { RAW_REPLICA, FULL_D, GLOBAL_RESTORE, HISTORY, RUN_XMIN, P_CLOSED, P_XMIN, RESERVE_EQUATION }
internal enum class TestFirstCutSuccessorLeaseRefusalV1 { CURRENT_FOREIGN, OVERFLOW }
internal enum class TestFirstCutSuccessorNativeDriftV1 { FOREIGN_OWNER, EXPIRED_LEASE, P_XMIN }

/** Authored connected coverage only. Genuine C paid slots; negative mutations/callback failures never manufacture success. */
internal object TestActiveFirstCutSuccessorCasesV1 {
    fun capturedRequiresOwnCurrentLeaseAndKeepsPaidFingerprint(tls: VersionBoundPersistenceConnectedFixture, enrolled: Boolean) =
        withTestActiveFirstCutSuccessor(tls) { f ->
            val c = f.first
            if (enrolled) c.initial.withExchange { exchange ->
                assertEquals(InstallationEnrollmentDisposition.CREATED, exchange.enroll(c.initial.candidate()).disposition)
                exchange.assertReleased()
            }
            val originalCapture = c.capture().claimSeal(c.registration, c.assembly)
            val counters = c.counters(); val outside = c.outsideCut(); val global = c.globalImage(); val paid = c.paidImage()
            val oldToken = c.control()["lease_token"] as Long
            c.probe.resetObservations()
            val original = f.resume()
            val recovered = f.recover(original)
            val handoff = recovered.claimSeal(c.registration, c.assembly)
            handoff.requireRetained()
            assertSame(c.registration, handoff.registration); assertSame(c.assembly, handoff.assembly); assertSame(c.process, handoff.process)
            assertEquals(originalCapture.slot.operationToken, handoff.slot.operationToken)
            assertEquals(originalCapture.slot.captureToken, handoff.slot.captureToken)
            assertArrayEquals(originalCapture.slot.fingerprint(), handoff.slot.fingerprint())
            assertEquals(oldToken + 1, c.control()["lease_token"])
            assertNull(c.control()["lease_owner"]); assertNull(c.control()["lease_expires_at"])
            assertEquals("CAPTURED", c.control()["rotation_state"]); assertEquals("RESERVED", c.paid()["state"])
            assertEquals(paid, c.paidImage(), "CAPTURED recovery cannot rewrite or churn the already-paid V26 row/xmin.")
            assertEquals(counters, c.counters()); assertEquals(outside, c.outsideCut()); assertEquals(global, c.globalImage())
            assertTrue(f.nativeSessions.isEmpty(), "CAPTURED does not reacquire native E or rotate again.")
            assertEquals(listOf(FIRST_CUT_SUCCESSOR_READ, FIRST_CUT_SUCCESSOR_LEASE, FIRST_CUT_SUCCESSOR_RELEASE), c.probe.calls.map { it.path }.distinct())
            assertNoWritesBeyondLease(c)
            assertThrows<RuntimeException> { recovered.claimSeal(c.registration, c.assembly) }
            assertThrows<RuntimeException> { TestActiveFirstCutSuccessorV1.Recovered.issue(original) }
            assertThrows<RuntimeException> { original.recover(credentials, credentials) }

            // A later genuine successor must surpass CURRENT control, not the unchanged historical capture token.
            val second = f.recover().claimSeal(c.registration, c.assembly)
            assertEquals(handoff.slot.captureToken, second.slot.captureToken)
            assertEquals(oldToken + 2, c.control()["lease_token"])
            assertEquals(paid, c.paidImage()); assertEquals(counters, c.counters())
            assertTrue(f.nativeSessions.isEmpty())
            assertThrows<RuntimeException> { c.begin().capture(credentials, credentials) }
            assertEquals(0, c.native.sts.createdClients + c.native.kms.createdClients + c.native.s3Created,
                "Historical handoff is not an ordinary seal, checkpoint, SUCCESS, content or health capability.")
        }

    fun requestedNaturalExpiryUsesDistinctNativeLineageWithoutRecharge(tls: VersionBoundPersistenceConnectedFixture) =
        withTestActiveFirstCutSuccessor(tls) { f ->
            val c = f.first
            f.requestedAfterRealTimeout(requireSameProcessCleanup = true)
            val request = c.control(); val slot = c.paid(); val counters = c.counters(); val outside = c.outsideCut(); val global = c.globalImage()
            f.waitForNaturalExpiry() // No successor owner/deadline exists during this actual30s wait.
            c.probe.resetObservations()
            val recovered = f.recoverWhileShared {
                assertEquals(counters, c.counters()); assertEquals(outside, c.outsideCut())
                assertEquals(request["rotation_request_token"], c.control()["rotation_request_token"])
                assertTrue((c.control()["lease_token"] as Long) > (request["lease_token"] as Long))
            }.getOrThrow()
            val handoff = recovered.claimSeal(c.registration, c.assembly)
            assertEquals(slot["operation_token"], handoff.slot.operationToken)
            assertEquals(slot["request_owner"], handoff.slot.requestOwner)
            assertEquals(slot["request_token"], handoff.slot.requestToken)
            assertEquals((request["lease_token"] as Long) + 1, handoff.slot.captureToken)
            assertFalse(handoff.slot.captureOwner == handoff.slot.requestOwner)
            assertEquals("CAPTURED", c.control()["rotation_state"]); assertEquals(2L, c.control()["publication_epoch"])
            assertEquals(false, c.control()["scan_requested"]); assertNull(c.control()["lease_owner"])
            assertEquals("RESERVED", c.paid()["state"])
            assertEquals(counters, c.counters()); assertEquals(outside, c.outsideCut()); assertEquals(global, c.globalImage())
            assertEquals(1, f.nativeSessions.size)
            val session = f.nativeSessions.single()
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, session.failure().databaseOutcome)
            assertTrue(session.failure().cleanupProven)
            assertEquals(listOf(FIRST_CUT_SUCCESSOR_READ, FIRST_CUT_SUCCESSOR_LEASE), c.probe.calls.map { it.path }.distinct())
            assertNoWritesBeyondLease(c)
            assertThrows<RuntimeException> { recovered.claimSeal(c.registration, c.assembly) }
        }

    fun absentSlotAndLiveForeignOrOverflowLeaseNeverMintAuthority(tls: VersionBoundPersistenceConnectedFixture, cut: TestFirstCutSuccessorLeaseRefusalV1?) {
        var fixture: TestActiveFirstCutSuccessorFixtureV1? = null
        var point = "FIXTURE_SETUP"
        try { withTestActiveFirstCutSuccessor(tls) { f ->
            fixture = f
            val c = f.first
            if (cut != null) {
                point = "ORIGINAL_CAPTURE"
                c.capture()
                point = "NEGATIVE_LEASE_UPDATE"
                when (cut) {
                    TestFirstCutSuccessorLeaseRefusalV1.CURRENT_FOREIGN -> assertEquals(1, c.initial.foreignUpdate(
                        "UPDATE complaint_journal_control SET lease_owner = ?, lease_token = lease_token + 1, lease_expires_at = clock_timestamp() + interval '30 seconds' WHERE data_scope_id = ?",
                        UUID.randomUUID(), c.scope))
                    TestFirstCutSuccessorLeaseRefusalV1.OVERFLOW -> assertEquals(1, c.initial.foreignUpdate(
                        "UPDATE complaint_journal_control SET lease_token = 9223372036854775807 WHERE data_scope_id = ?", c.scope))
                }
            }
            point = "BEFORE_REFUSAL_SNAPSHOT"
            val state = c.controlImage(); val paid = c.paidImage(); val counters = c.counters(); val global = c.globalImage()
            c.probe.resetObservations()
            point = "SUCCESSOR_CONSTRUCTION"
            val original = f.resume()
            point = "REFUSAL_ASSERTIONS"
            assertThrows<RuntimeException> { f.recover(original) }
            assertEquals(state, c.controlImage()); assertEquals(paid, c.paidImage()); assertEquals(counters, c.counters()); assertEquals(global, c.globalImage())
            assertTrue(f.nativeSessions.isEmpty())
            assertThrows<RuntimeException> { TestActiveFirstCutSuccessorV1.Recovered.issue(original) }
            assertThrows<RuntimeException> { original.recover(credentials, credentials) }
            assertNoWritesBeyondLease(c)
            point = "FIXTURE_CLEANUP"
        } } catch (problem: RuntimeException) {
            TestActiveFirstCutSuccessorDiagnosticsV1.report("LEASE_REFUSAL_${cut?.name ?: "MISSING_SLOT"}_$point", fixture, problem)
            throw problem
        }
    }

    fun rawAndFullCurrentAccountingDriftRefuseBeforeLease(tls: VersionBoundPersistenceConnectedFixture, cut: TestFirstCutSuccessorDriftV1) =
        withTestActiveFirstCutSuccessor(tls) { f ->
            val c = f.first
            c.capture(); c.probe.resetObservations()
            val original = f.resume()
            val boundary = c.p.f.http.beforeRead
            var injected = false
            var control = c.controlImage(); var paid = c.paidImage(); var counters = c.counters(); var outside = c.outsideCut(); var global = c.globalImage()
            c.p.f.http.beforeRead = {
                boundary()
                if (!injected) {
                    when (cut) {
                        TestFirstCutSuccessorDriftV1.RAW_REPLICA -> c.p.f.http.replicaVersion = null
                        TestFirstCutSuccessorDriftV1.FULL_D -> assertEquals(1, c.initial.foreignUpdate(
                            "UPDATE complaint_journal_control SET desired_configuration_hash = decode(repeat('dc',32),'hex') WHERE data_scope_id = ?", c.scope))
                        TestFirstCutSuccessorDriftV1.GLOBAL_RESTORE -> assertEquals(1, c.initial.foreignUpdate(
                            "UPDATE complaint_journal_control SET restore_identity = ? WHERE data_scope_id = ?", UUID.randomUUID(), UUID(0L, 0L)))
                        TestFirstCutSuccessorDriftV1.HISTORY -> assertEquals(1, c.initial.foreignUpdate(
                            "UPDATE complaint_catalog_mutations SET retain_until = retain_until + interval '1 microsecond' WHERE successor_generation = 1"))
                        TestFirstCutSuccessorDriftV1.RUN_XMIN -> assertEquals(1, c.initial.foreignUpdate(
                            "UPDATE complaint_test_runs SET enrolled_count = enrolled_count WHERE data_scope_id = ?", c.scope))
                        TestFirstCutSuccessorDriftV1.P_CLOSED -> assertEquals(22, c.initial.foreignUpdate(
                            "UPDATE complaint_capacity_counters SET configuration_closed = true"))
                        TestFirstCutSuccessorDriftV1.P_XMIN -> assertEquals(1, c.initial.foreignUpdate(
                            "UPDATE complaint_capacity_counters SET actual_units = actual_units WHERE name = 'storage_bytes'"))
                        TestFirstCutSuccessorDriftV1.RESERVE_EQUATION -> assertEquals(1, c.initial.foreignUpdate(
                            "UPDATE complaint_capacity_counters SET test_reserved_units = test_reserved_units - 1, actual_units = actual_units + 1 WHERE name = 'storage_bytes'"))
                    }
                    injected = true
                    control = c.controlImage(); paid = c.paidImage(); counters = c.counters(); outside = c.outsideCut(); global = c.globalImage()
                }
            }
            try { assertThrows<RuntimeException> { f.recover(original) } } finally { c.p.f.http.beforeRead = boundary }
            assertTrue(injected)
            assertEquals(control, c.controlImage()); assertEquals(paid, c.paidImage()); assertEquals(counters, c.counters())
            assertEquals(outside, c.outsideCut()); assertEquals(global, c.globalImage())
            assertFalse(c.probe.steps.contains("test-first-cut-successor-acquire"))
            assertTrue(f.nativeSessions.isEmpty())
            assertThrows<RuntimeException> { TestActiveFirstCutSuccessorV1.Recovered.issue(original) }
        }

    fun capturedReleaseRechecksAfterFreshLease(tls: VersionBoundPersistenceConnectedFixture) = withTestActiveFirstCutSuccessor(tls) { f ->
        val c = f.first
        c.capture(); c.probe.resetObservations()
        val original = f.resume(); val before = c.probe.beforeSql
        var changed = false; var negative = ""
        val paid = c.paidImage(); val counters = c.counters()
        c.probe.beforeSql = { step ->
            before(step)
            if (!changed && step == "test-first-cut-successor-authenticate" && c.probe.calls.last().path === FIRST_CUT_SUCCESSOR_RELEASE) {
                assertEquals(1, c.initial.foreignUpdate("UPDATE complaint_test_runs SET enrolled_count = enrolled_count WHERE data_scope_id = ?", c.scope))
                changed = true; negative = c.controlImage()
            }
        }
        try { assertThrows<RuntimeException> { f.recover(original) } } finally { c.probe.beforeSql = before }
        assertTrue(changed); assertEquals(negative, c.controlImage())
        assertNotNull(c.control()["lease_owner"], "A stale snapshot does not clear even its old local owner.")
        assertEquals(paid, c.paidImage()); assertEquals(counters, c.counters())
        assertFalse(c.probe.steps.contains("test-first-cut-successor-release"))
        assertThrows<RuntimeException> { TestActiveFirstCutSuccessorV1.Recovered.issue(original) }
    }

    fun releaseCompletionFailuresNeverIssueRecovered(tls: VersionBoundPersistenceConnectedFixture, cut: TestFirstCutRequestCutV1) =
        withTestActiveFirstCutSuccessor(tls) { f ->
            val c = f.first
            c.capture(); c.probe.resetObservations()
            val original = f.resume(); val paid = c.paidImage(); val counters = c.counters()
            var phase: PersistencePhaseContext? = null
            val key = Any(); val sentinel = Any(); var bound = false
            c.probe.afterSql = { step -> if (step == "test-first-cut-successor-release") {
                phase = checkNotNull(PersistencePhaseOwnership.current())
                if (cut === TestFirstCutRequestCutV1.DEFERRED_COMMIT_UNKNOWN) {
                    val jdbc = JdbcTemplate(c.runtime.pools.catalogCoordinator.dataSource)
                    jdbc.execute("CREATE TEMP TABLE kira_successor_release_refusal (id int UNIQUE DEFERRABLE INITIALLY DEFERRED)")
                    assertEquals(2, jdbc.update("INSERT INTO kira_successor_release_refusal VALUES (1),(1)"))
                } else TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun beforeCommit(readOnly: Boolean) {
                        if (cut === TestFirstCutRequestCutV1.BEFORE_COMMIT) throw IllegalStateException("Synthetic successor RELEASE beforeCommit failure.")
                    }
                    override fun afterCommit() {
                        if (cut === TestFirstCutRequestCutV1.UNRESOLVED_RELEASE) { TransactionSynchronizationManager.bindResource(key, sentinel); bound = true }
                        if (cut !== TestFirstCutRequestCutV1.BEFORE_COMMIT) throw IOException("Synthetic successor known-COMMIT RELEASE completion failure.")
                    }
                })
            } }
            try { assertThrows<RuntimeException> { f.recover(original) } }
            finally {
                c.probe.afterSql = {}
                if (bound) assertSame(sentinel, TransactionSynchronizationManager.unbindResource(key))
                requireConnectionFree()
            }
            val durable = cut in setOf(TestFirstCutRequestCutV1.AFTER_COMMIT, TestFirstCutRequestCutV1.UNRESOLVED_RELEASE)
            assertEquals(when (cut) {
                TestFirstCutRequestCutV1.BEFORE_COMMIT -> PersistenceDatabaseOutcome.ROLLED_BACK
                TestFirstCutRequestCutV1.DEFERRED_COMMIT_UNKNOWN -> PersistenceDatabaseOutcome.UNKNOWN // Deferred constraint actually rolls back: not durable UNKNOWN qualification.
                else -> PersistenceDatabaseOutcome.COMMITTED
            }, checkNotNull(phase).databaseOutcome())
            if (durable) assertNull(c.control()["lease_owner"]) else assertNotNull(c.control()["lease_owner"])
            assertEquals(paid, c.paidImage()); assertEquals(counters, c.counters())
            assertTrue(f.nativeSessions.isEmpty())
            assertThrows<RuntimeException> { TestActiveFirstCutSuccessorV1.Recovered.issue(original) }
            assertThrows<RuntimeException> { original.recover(credentials, credentials) }
        }

    fun nativeRechecksAfterRealEWait(tls: VersionBoundPersistenceConnectedFixture, cut: TestFirstCutSuccessorNativeDriftV1) =
        withTestActiveFirstCutSuccessor(tls) { f ->
            val c = f.first
            f.requestedAfterRealTimeout(requireSameProcessCleanup = true); f.waitForNaturalExpiry(); c.probe.resetObservations()
            var negative = ""; var paid = emptyList<String>(); var counters = c.counters()
            val result = f.recoverWhileShared {
                when (cut) {
                    TestFirstCutSuccessorNativeDriftV1.FOREIGN_OWNER -> assertEquals(1, c.initial.foreignUpdate(
                        "UPDATE complaint_journal_control SET lease_owner = ?, lease_token = lease_token + 1, lease_expires_at = clock_timestamp() + interval '30 seconds' WHERE data_scope_id = ?", UUID.randomUUID(), c.scope))
                    TestFirstCutSuccessorNativeDriftV1.EXPIRED_LEASE -> assertEquals(1, c.initial.foreignUpdate(
                        "UPDATE complaint_journal_control SET lease_expires_at = updated_at WHERE data_scope_id = ?", c.scope)) // Negative synthetic expiry, not success qualification.
                    TestFirstCutSuccessorNativeDriftV1.P_XMIN -> assertEquals(1, c.initial.foreignUpdate(
                        "UPDATE complaint_capacity_counters SET actual_units = actual_units WHERE name = 'storage_bytes'"))
                }
                negative = c.controlImage(); paid = c.paidImage(); counters = c.counters()
            }
            assertTrue(result.isFailure)
            assertEquals(negative, c.controlImage()); assertEquals(paid, c.paidImage()); assertEquals(counters, c.counters())
            assertEquals("REQUESTED", c.control()["rotation_state"]); assertEquals(1L, c.control()["publication_epoch"])
            assertNotNull(c.control()["lease_owner"]); assertNull(c.paid()["capture_owner"])
        }

    fun nativeCommitFailureOrLateReturnCannotIssueRecovered(tls: VersionBoundPersistenceConnectedFixture, cut: TestFirstCutNativeCutV1) =
        withTestActiveFirstCutSuccessor(tls) { f ->
            val c = f.first
            f.requestedAfterRealTimeout(requireSameProcessCleanup = true); f.waitForNaturalExpiry(); c.probe.resetObservations()
            val counters = c.counters(); val original = f.resume(); var installed = false
            f.beforeNativeSample = { session ->
                if (!installed && ((cut === TestFirstCutNativeCutV1.DEFERRED_COMMIT_UNKNOWN && ownedCutField(session, "stage").toString() == "REREAD") ||
                        (cut === TestFirstCutNativeCutV1.KNOWN_COMMIT_LATE_RETURN && session.failure().databaseOutcome === PersistenceDatabaseOutcome.COMMITTED))) {
                    installed = true
                    if (cut === TestFirstCutNativeCutV1.DEFERRED_COMMIT_UNKNOWN) {
                        poolTestField<Connection>(session, "connection").createStatement().use {
                            it.execute("CREATE TEMP TABLE kira_successor_native_refusal (id int UNIQUE DEFERRABLE INITIALLY DEFERRED)")
                            assertEquals(2, it.executeUpdate("INSERT INTO kira_successor_native_refusal VALUES (1),(1)"))
                        }
                    } else c.native.offsetNanos += (original.budget.remainingMillis(10_000) + 1_000) * 1_000_000 // Synthetic late clock return only.
                }
            }
            val refusal = try { assertThrows<RuntimeException> { f.recover(original) } }
            finally { f.beforeNativeSample = {}; c.native.offsetNanos = 0 }
            assertTrue(installed, "Native injection ${cut.name} was not reached. " +
                TestActiveFirstCutSuccessorDiagnosticsV1.snapshot(f, refusal, original))
            f.awaitNativeReclaimed(); assertEquals(counters, c.counters())
            val committed = cut === TestFirstCutNativeCutV1.KNOWN_COMMIT_LATE_RETURN
            assertEquals(if (committed) PersistenceDatabaseOutcome.COMMITTED else PersistenceDatabaseOutcome.UNKNOWN, f.nativeSessions.single().failure().databaseOutcome)
            assertEquals(if (committed) "CAPTURED" else "REQUESTED", c.control()["rotation_state"])
            val exact = c.controlImage() to c.paidImage()
            assertThrows<RuntimeException> { TestActiveFirstCutSuccessorV1.Recovered.issue(original) }
            assertThrows<RuntimeException> { original.recover(credentials, credentials) }
            assertEquals(exact, c.controlImage() to c.paidImage())
        }

    fun rawSignalDeadlineAndCloseStaySticky(tls: VersionBoundPersistenceConnectedFixture, cut: TestFirstCutProviderCutV1) =
        withTestActiveFirstCutSuccessor(tls) { f ->
            val c = f.first
            c.capture(); c.probe.resetObservations()
            val original = f.resume(); val exact = c.controlImage() to c.paidImage(); val counters = c.counters()
            val boundary = c.p.f.http.beforeRead; val fatal = SuccessorFatal(); var injected = false
            c.p.f.http.beforeRead = {
                boundary()
                if (!injected && cut !== TestFirstCutProviderCutV1.CLOSE_RECEIPT) {
                    injected = true
                    when (cut) {
                        TestFirstCutProviderCutV1.DEADLINE -> c.native.offsetNanos += 11_000_000_000
                        TestFirstCutProviderCutV1.CANCELLATION -> throw CancellationException("Synthetic successor raw cancellation.")
                        TestFirstCutProviderCutV1.INTERRUPTED_IO -> throw InterruptedIOException("Synthetic successor raw interrupted IO.")
                        TestFirstCutProviderCutV1.FATAL -> throw fatal
                        else -> error("Unexpected raw failure cut.")
                    }
                }
            }
            c.p.f.http.afterReadClientClose = { if (!injected && cut === TestFirstCutProviderCutV1.CLOSE_RECEIPT) {
                injected = true; throw IOException("Synthetic raw client close return failure, not native cleanup qualification.")
            } }
            try {
                when (cut) {
                    TestFirstCutProviderCutV1.CANCELLATION -> assertThrows<CancellationException> { f.recover(original) }
                    TestFirstCutProviderCutV1.INTERRUPTED_IO -> { assertThrows<InterruptedException> { f.recover(original) }; assertTrue(Thread.currentThread().isInterrupted) }
                    TestFirstCutProviderCutV1.FATAL -> assertSame(fatal, assertThrows<SuccessorFatal> { f.recover(original) })
                    else -> assertThrows<RuntimeException> { f.recover(original) }
                }
            } finally { c.p.f.http.beforeRead = boundary; c.p.f.http.afterReadClientClose = {}; c.native.offsetNanos = 0; Thread.interrupted() }
            assertTrue(injected); assertEquals(exact, c.controlImage() to c.paidImage()); assertEquals(counters, c.counters())
            assertTrue(f.nativeSessions.isEmpty())
            assertSame(original, SignedActivationObservation.active(c.runtime.pools.catalogCoordinator))
            assertTrue(runCatching { original.recover(credentials, credentials) }.isFailure)
            Thread.interrupted()
            assertThrows<RuntimeException> { TestActiveFirstCutSuccessorV1.Recovered.issue(original) }
        }

    fun closedRegistrationRootOrTerminalStateRefuses(tls: VersionBoundPersistenceConnectedFixture, cut: TestFirstCutClosedV1) =
        withTestActiveFirstCutSuccessor(tls) { f ->
            val c = f.first
            c.capture(); c.probe.resetObservations()
            val original = f.resume()
            when (cut) {
                TestFirstCutClosedV1.REGISTRATION -> c.registration.close()
                TestFirstCutClosedV1.ROOT -> c.runtime.requestIntakeShutdownForRefusal()
                TestFirstCutClosedV1.SEALED_RUN -> assertEquals(1, c.initial.foreignUpdate(
                    "UPDATE complaint_test_runs SET state = 'SEALED', sealed_at = clock_timestamp() WHERE data_scope_id = ?", c.scope))
                TestFirstCutClosedV1.MAINTENANCE -> assertEquals(1, c.initial.foreignUpdate(
                    "UPDATE complaint_journal_control SET maintenance_closed = true, creation_closed = true WHERE data_scope_id = ?", c.scope))
            }
            val control = c.controlImage(); val paid = c.paidImage(); val counters = c.counters()
            assertThrows<RuntimeException> { f.recover(original) }
            assertEquals(control, c.controlImage()); assertEquals(paid, c.paidImage()); assertEquals(counters, c.counters())
            assertTrue(f.nativeSessions.isEmpty())
            assertThrows<RuntimeException> { TestActiveFirstCutSuccessorV1.Recovered.issue(original) }
        }

    private fun assertNoWritesBeyondLease(c: TestActiveFirstCutFixtureV1) {
        assertFalse(c.probe.steps.any { it.startsWith("charge:") || it in setOf("test-first-cut-request", "test-first-cut-insert") })
        c.probe.calls.filter { it.path.testActiveFirstCutSuccessor }.groupBy { it.phase }.forEach { (_, calls) ->
            val steps = calls.map { it.step }
            assertTrue(steps.indexOf("test-first-cut-successor-history-lock") < steps.indexOf("test-first-cut-successor-counters-lock"))
            if ("test-first-cut-successor-run-lock" in steps) {
                assertTrue(steps.indexOf("test-first-cut-successor-counters-lock") < steps.indexOf("test-first-cut-successor-run-lock"))
                assertTrue(steps.indexOf("test-first-cut-successor-run-lock") < steps.indexOf("test-first-cut-successor-slot-lock"))
            }
        }
    }
    private val credentials get() = S3CatalogReadbackFixture.credentials
    private class SuccessorFatal : Error("Synthetic successor fatal raw signal.")
}
