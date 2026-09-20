package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.OwnerDeleteLiteralCharges
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.infrastructure.terminal.TestClosedOrdinarySealSqlV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainExceptionV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainSqlV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainStepV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinarySealStepV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOrdinaryDrainResultV1
import me.manga.kira.backend.complaint.journal.TestOrdinaryInventoryHttpFixtureV1
import me.manga.kira.backend.complaint.parsing.catalog.OfflineTrustBundleParser
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

internal enum class TestOrdinaryDrainAuthorityCutV1 { FOREIGN_SIGNER, WRONG_PURPOSE, WRITER, LINEAGE, RANGE, MISSING_PATH, MISSING_BOUND, MUTATED_RAW }
internal enum class TestOrdinaryDrainCommitStepV1 { WITNESS, CONVERT, RECYCLE, SEAL_VERIFY }

/** Failure injection only at existing raw artifact/JDBC/transaction seams. No successful authority is supplied. */
internal object TestOrdinaryDrainFailureCasesV1 {
    fun deniedAuthority(tls: VersionBoundPersistenceConnectedFixture, cut: TestOrdinaryDrainAuthorityCutV1) {
        val inputs = TestOrdinaryDrainFixtureInputsV1()
        withOrdinaryDrainRun(tls, inputs = inputs) { f ->
            val observed = TestOrdinaryDrainAccountingObservationV1(f)
            val before = observed.state()
            val history = observed.previousHistory()
            val original = f.begin()
            val correct = f.approval(original)
            val envelope = OfflineTrustBundleParser.parseOrdinaryDenial(correct)
            val body = when (cut) {
                TestOrdinaryDrainAuthorityCutV1.WRONG_PURPOSE -> envelope.body.copy(purpose = "CATALOG_BOOTSTRAP_ONLY")
                TestOrdinaryDrainAuthorityCutV1.WRITER -> envelope.body.copy(writerGeneration = UUID.randomUUID().toString())
                TestOrdinaryDrainAuthorityCutV1.LINEAGE -> envelope.body.copy(initialWriterRegistrySha256 = "f".repeat(64))
                TestOrdinaryDrainAuthorityCutV1.RANGE -> envelope.body.copy(epochStartInclusive = 2)
                else -> envelope.body
            }
            val approval = if (cut === TestOrdinaryDrainAuthorityCutV1.FOREIGN_SIGNER)
                TestOrdinaryDrainFixtureInputsV1().approval(body, envelope.signature.keyId)
            else inputs.approval(body, envelope.signature.keyId)
            val raw = when (cut) {
                TestOrdinaryDrainAuthorityCutV1.MISSING_PATH -> f.rawEvidence.drop(1)
                TestOrdinaryDrainAuthorityCutV1.MISSING_BOUND -> f.rawEvidence.take(1)
                TestOrdinaryDrainAuthorityCutV1.MUTATED_RAW -> f.rawEvidence.also { values -> values.first()[0] = (values.first()[0].toInt() xor 1).toByte() }
                else -> f.rawEvidence
            }
            val providerCalls = f.provider.requests.size
            assertThrows<TestOrdinaryDrainExceptionV1> { original.drain(approval, raw, f.primaryCredentials, f.readCredentials) }
            f.assertReleased(); f.probe.assertReleased(); f.jdbc.assertReleased()
            assertEquals(before, observed.state(), "Wrong authority cannot spend the first staging row, cut envelope or recovery residual: $cut")
            assertEquals(history, observed.previousHistory())
            assertEquals(providerCalls, f.provider.requests.size)
            assertTrue(f.sealHttp.order.isEmpty())
            assertTrue(f.probe.calls.any { it.sql == TestOrdinaryDrainSqlV1.capture }, "Refusal exercised admission after real registered range capture.")
            assertTrue(f.probe.calls.none { it.step === TestOrdinaryDrainStepV1.BEGIN_PASS || it.step === TestOrdinaryDrainStepV1.CONVERT })
            val calls = f.probe.calls.size + f.jdbc.calls.size
            assertThrows<TestOrdinaryDrainExceptionV1> { original.drain(correct, f.rawEvidence, f.primaryCredentials, f.readCredentials) }
            assertEquals(calls, f.probe.calls.size + f.jdbc.calls.size, "Corrected bytes cannot rehabilitate the failed original.")
        }
    }

    fun completion(tls: VersionBoundPersistenceConnectedFixture, step: TestOrdinaryDrainCommitStepV1, cut: TestRegistrationCompletionCut) =
        withOrdinaryDrainRun(tls, expireClosedSetupPredecessors = true) { f ->
            val values = TestOrdinaryDrainAccountingCasesV1.addRetainedAliases(f)
            val observed = TestOrdinaryDrainAccountingObservationV1(f)
            val initial = observed.state()
            val history = observed.previousHistory()
            val resource = Any(); val sentinel = Any()
            var bound = false
            var selected: PersistencePhaseContext? = null
            var stageBefore: TestOrdinaryDrainAccountingStateV1? = null
            var imageBefore: Map<String, List<String>>? = null
            var requestsAtCut = -1
            TestOrdinaryInventoryHttpFixtureV1(f.provider).use { native ->
                f.probe.after = { call ->
                    if (selected == null && selectedStatement(step, call)) {
                        selected = call.phase
                        stageBefore = observed.state(); imageBefore = observed.image()
                        requestsAtCut = native.requests.size
                        if (cut === TestRegistrationCompletionCut.DEFERRED_COMMIT_UNKNOWN) {
                            // Same actual bound holder, not a supplied database outcome.
                            val jdbc = JdbcTemplate(checkNotNull(f.probe.dataSource))
                            jdbc.execute("CREATE TEMP TABLE kira_ordinary_drain_commit_cut (id int UNIQUE DEFERRABLE INITIALLY DEFERRED)")
                            assertEquals(2, jdbc.update("INSERT INTO kira_ordinary_drain_commit_cut VALUES (1), (1)"))
                        } else TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                            override fun beforeCommit(readOnly: Boolean) {
                                if (cut === TestRegistrationCompletionCut.BEFORE_COMMIT) error("Synthetic ordinary drain beforeCommit refusal.")
                            }
                            override fun afterCommit() {
                                if (cut === TestRegistrationCompletionCut.UNRESOLVED_RELEASE) {
                                    TransactionSynchronizationManager.bindResource(resource, sentinel); bound = true
                                }
                                if (cut !== TestRegistrationCompletionCut.BEFORE_COMMIT) error("Synthetic ordinary drain acknowledgment loss.")
                            }
                        })
                    }
                }
                val original = f.begin()
                val approval = f.approval(original)
                try {
                    assertThrows<TestOrdinaryDrainExceptionV1> { original.drain(approval, f.rawEvidence, f.primaryCredentials, f.readCredentials) }
                    if (cut === TestRegistrationCompletionCut.UNRESOLVED_RELEASE) {
                        assertTrue(bound); assertSame(selected, PersistencePhaseOwnership.current())
                        assertTrue(checkNotNull(selected).quarantined())
                        assertFalse(checkNotNull(selected).testOrdinaryDrainCleanupProven(original))
                        assertThrows<RuntimeException> { f.begin() }
                    }
                } finally {
                    f.probe.after = {}
                    if (bound) assertSame(sentinel, TransactionSynchronizationManager.unbindResource(resource))
                    requireConnectionFree() // Original quarantine can physically settle, not become a successful invocation.
                }
                f.assertReleased()
                assertTrue(selected != null, "Failure reached the intended real SQL cut rather than setup.")
                val outcome = when (cut) {
                    TestRegistrationCompletionCut.BEFORE_COMMIT -> PersistenceDatabaseOutcome.ROLLED_BACK
                    TestRegistrationCompletionCut.DEFERRED_COMMIT_UNKNOWN -> PersistenceDatabaseOutcome.UNKNOWN
                    else -> PersistenceDatabaseOutcome.COMMITTED
                }
                assertEquals(outcome, checkNotNull(selected).databaseOutcome())
                assertEquals(requestsAtCut, native.requests.size, "No native recovery may dispatch past failed/unknown/unreleased SQL.")
                assertEquals(history, observed.previousHistory())
                if (outcome !== PersistenceDatabaseOutcome.COMMITTED) {
                    assertEquals(stageBefore, observed.state()); assertEquals(imageBefore, observed.image(), "All writes of that phase rolled back together.")
                }
                val final = observed.state()
                val committed = outcome === PersistenceDatabaseOutcome.COMMITTED
                when (step) {
                    TestOrdinaryDrainCommitStepV1.WITNESS -> {
                        assertEquals(!committed, final.progressHex == null)
                        assertEquals("PARTIAL", final.recoveryState); assertEquals(initial.used, final.used)
                        val staging = TestOrdinaryDrainLiteralV1.scanRun.scaled(2) + TestOrdinaryDrainLiteralV1.scanEntry.scaled(8)
                        observed.assertTransfer(initial, final, reserveSpend = staging + if (committed) TestOrdinaryDrainLiteralV1.delta else ComplaintCapacityVector.ZERO)
                        assertTrue(f.sealHttp.order.isEmpty())
                    }
                    TestOrdinaryDrainCommitStepV1.CONVERT -> {
                        assertEquals(if (committed) "CONVERTED" else "PARTIAL", final.recoveryState)
                        assertEquals(OwnerDeleteLiteralCharges.ordinaryApply.scaled(4), final.used)
                        assertEquals(8L, final.scanEntries); assertEquals(8L, final.appliedScanEntries)
                        if (committed) observed.assertTransfer(checkNotNull(stageBefore), final, recoveryRelease = final.promise - final.used)
                        assertTrue(f.sealHttp.order.isEmpty())
                    }
                    TestOrdinaryDrainCommitStepV1.RECYCLE -> {
                        assertEquals("CONVERTED", final.recoveryState)
                        assertEquals(if (committed) 0L else 8L, final.scanEntries)
                        assertEquals(if (committed) 0L else 2L, final.scanRuns)
                        if (committed) observed.assertTransfer(checkNotNull(stageBefore), final,
                            recycled = TestOrdinaryDrainLiteralV1.scanRun.scaled(2) + TestOrdinaryDrainLiteralV1.scanEntry.scaled(8))
                        assertTrue(f.sealHttp.order.isEmpty())
                    }
                    TestOrdinaryDrainCommitStepV1.SEAL_VERIFY -> {
                        assertEquals("CONVERTED", final.recoveryState); assertEquals(0L, final.scanEntries)
                        assertEquals(1, f.sealHttp.requests.count { it.kind == "PUT" })
                        assertEquals(committed, f.observer.queryForObject("SELECT seal_set_bytes IS NOT NULL FROM complaint_test_runs WHERE data_scope_id = ?", Boolean::class.java, f.scope))
                        observed.assertTransfer(checkNotNull(stageBefore), final)
                    }
                }
                val failedImage = observed.image(); val calls = f.probe.calls.size + f.jdbc.calls.size
                assertThrows<TestOrdinaryDrainExceptionV1> { original.drain(approval, f.rawEvidence, f.primaryCredentials, f.readCredentials) }
                assertEquals(calls, f.probe.calls.size + f.jdbc.calls.size); assertEquals(failedImage, observed.image())

                // Fresh original, same registration/raw closure, and independently controlled TEST
                // expiry. Failed phase evidence was asserted above before clearing observation logs.
                if (cut === TestRegistrationCompletionCut.AFTER_COMMIT || cut === TestRegistrationCompletionCut.BEFORE_COMMIT) {
                    val paid = final.progressHex
                    val used = final.used
                    val afterRequests = native.requests.size
                    observed.expireLeaseForRetry(); f.probe.reset(); f.jdbc.reset()
                    native.observeExactGetsWithoutPriorList = paid != null
                    val retry = f.begin()
                    assertArrayEquals(approval, f.approval(retry))
                    assertEquals(TestRunOrdinaryDrainResultV1.POST_DENIAL_ORDINARY_SEAL_VERIFIED,
                        retry.drain(approval, f.rawEvidence, f.primaryCredentials, f.readCredentials))
                    f.assertReleased(); f.probe.assertReleased(); f.jdbc.assertReleased()
                    val recovered = observed.state()
                    assertEquals("CONVERTED", recovered.recoveryState)
                    assertEquals(OwnerDeleteLiteralCharges.ordinaryApply.scaled(4), recovered.used)
                    if (paid != null) {
                        assertEquals(paid, recovered.progressHex, "Takeover re-admits the paid witness, never pays/replaces it again.")
                        assertTrue(native.requests.drop(afterRequests).none { it.kind == "LIST" })
                        assertTrue(f.probe.calls.none { it.sql == TestOrdinaryDrainSqlV1.spendAndProgress })
                    }
                    if (step !== TestOrdinaryDrainCommitStepV1.WITNESS) assertEquals(used, recovered.used)
                    if (step === TestOrdinaryDrainCommitStepV1.RECYCLE && cut === TestRegistrationCompletionCut.BEFORE_COMMIT)
                        assertEquals(afterRequests, native.requests.size, "All eight paid APPLIED rows survive rollback and need recycling, not replayed erasure.")
                    TestOrdinaryDrainAccountingCasesV1.assertPaidOrdinaryResult(f, values, approval)
                    assertEquals(1, f.sealHttp.requests.count { it.kind == "PUT" }, "A late seal acknowledgment cannot authorize a second PUT.")
                }
            }
        }

    fun legacyUnpaidProgressRefuses(tls: VersionBoundPersistenceConnectedFixture) = withOrdinaryDrainRun(tls) { f ->
        val observed = TestOrdinaryDrainAccountingObservationV1(f)
        TestOrdinaryInventoryHttpFixtureV1(f.provider).use { native ->
            var committed: PersistencePhaseContext? = null
            f.probe.after = { call ->
                if (committed == null && call.sql == TestOrdinaryDrainSqlV1.spendAndProgress) {
                    committed = call.phase
                    TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                        override fun afterCommit() { error("Synthetic stop after the actual paid cut.") }
                    })
                }
            }
            val original = f.begin(); val approval = f.approval(original)
            assertThrows<TestOrdinaryDrainExceptionV1> { original.drain(approval, f.rawEvidence, f.primaryCredentials, f.readCredentials) }
            f.probe.after = {}; f.assertReleased()
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, checkNotNull(committed).databaseOutcome())
            val paid = observed.runBytes("permanent_denial_bytes")
            // Clearly corrupt/legacy UNPAID shape: keep the real canonical witness, but undo only
            // its charge in a fixture transaction. The product must refuse, never infer payment
            // from blob presence or backfill a missing 1,068,608 bytes from free capacity.
            checkNotNull(f.observer.dataSource).connection.use { connection ->
                connection.autoCommit = false
                try {
                    val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
                    assertEquals(1, jdbc.update("UPDATE complaint_test_runs SET unused_reserve[21] = unused_reserve[21] + 1068608 WHERE data_scope_id = ?", f.scope))
                    assertEquals(1, jdbc.update("UPDATE complaint_capacity_counters SET actual_units = actual_units - 1068608, test_reserved_units = test_reserved_units + 1068608 WHERE name = 'storage_bytes'"))
                    connection.commit()
                } catch (failure: Throwable) { connection.rollback(); throw failure }
            }
            observed.expireLeaseForRetry(); f.probe.reset(); f.jdbc.reset()
            val damaged = observed.image(); val requests = native.requests.size
            val retry = f.begin()
            assertThrows<TestOrdinaryDrainExceptionV1> { retry.drain(approval, f.rawEvidence, f.primaryCredentials, f.readCredentials) }
            f.assertReleased()
            assertEquals(damaged, observed.image()); assertArrayEquals(paid, observed.runBytes("permanent_denial_bytes"))
            assertEquals(requests, native.requests.size)
            assertTrue(f.probe.calls.all { it.step === TestOrdinaryDrainStepV1.OPEN })
            assertTrue(f.sealHttp.order.isEmpty())
        }
    }

    fun staleFenceBeforeRecovery(tls: VersionBoundPersistenceConnectedFixture) = withOrdinaryDrainRun(tls) { f ->
        TestOrdinaryDrainAccountingCasesV1.addRetainedAliases(f)
        val observed = TestOrdinaryDrainAccountingObservationV1(f)
        TestOrdinaryInventoryHttpFixtureV1(f.provider).use { native ->
            var atCut: TestOrdinaryDrainAccountingStateV1? = null
            val original = f.begin()
            native.beforeRequest = { request ->
                if (request.kind == "GET" && original.step === TestOrdinaryDrainStepV1.RECOVERY_NATIVE && atCut == null) {
                    atCut = observed.state()
                    assertEquals(1, f.observer.update("UPDATE complaint_journal_control SET lease_owner = ?, lease_token = lease_token + 1 WHERE data_scope_id = ?", UUID.randomUUID(), f.scope))
                }
            }
            assertThrows<TestOrdinaryDrainExceptionV1> { original.drain(f.approval(original), f.rawEvidence, f.primaryCredentials, f.readCredentials) }
            f.assertReleased()
            assertEquals(checkNotNull(atCut), observed.state(), "Released native evidence is not a stale SQL writer's permission to spend U or release P−U.")
            assertEquals("PARTIAL", observed.state().recoveryState); assertEquals(0L, observed.state().appliedScanEntries)
            assertTrue(f.probe.calls.none { it.step in setOf(TestOrdinaryDrainStepV1.CONVERT, TestOrdinaryDrainStepV1.RECYCLE, TestOrdinaryDrainStepV1.SEAL) })
            assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, f.jdbc.calls.last().phase.databaseOutcome())
            assertTrue(f.sealHttp.order.isEmpty())
        }
    }

    fun inconsistentUsedVectorRefuses(tls: VersionBoundPersistenceConnectedFixture) = withOrdinaryDrainRun(tls) { f ->
        val observed = TestOrdinaryDrainAccountingObservationV1(f)
        TestOrdinaryInventoryHttpFixtureV1(f.provider).use {
            var damaged: TestOrdinaryDrainAccountingStateV1? = null
            f.probe.before = { call ->
                if (call.step === TestOrdinaryDrainStepV1.RECOVERY_PAGE && damaged == null) {
                    // Syntactically legal PARTIAL U, but it claims an extra actual E absent from the
                    // real family. Counters and rows are not fabricated to make it pass.
                    val wrong = (OwnerDeleteLiteralCharges.ordinaryApply + OwnerDeleteLiteralCharges.appliedOnly).toLongArray().joinToString(",", "{", "}")
                    checkNotNull(f.observer.dataSource).connection.use { connection ->
                        connection.prepareStatement("UPDATE complaint_recovery_capacity_reservations SET converted_amounts = ?::bigint[] WHERE event_id = ?").use { statement ->
                            statement.setString(1, wrong); statement.setString(2, f.history.eventId)
                            assertEquals(1, statement.executeUpdate())
                        }
                    }
                    damaged = observed.state()
                }
            }
            val original = f.begin()
            assertThrows<TestOrdinaryDrainExceptionV1> { original.drain(f.approval(original), f.rawEvidence, f.primaryCredentials, f.readCredentials) }
            f.probe.before = {}; f.assertReleased()
            assertEquals(checkNotNull(damaged), observed.state())
            assertEquals("PARTIAL", observed.state().recoveryState)
            assertTrue(f.probe.calls.none { it.sql == TestOrdinaryDrainSqlV1.convert })
            assertTrue(f.sealHttp.order.isEmpty())
        }
    }

    private fun selectedStatement(step: TestOrdinaryDrainCommitStepV1, call: TestOrdinaryDrainSqlCallV1): Boolean = when (step) {
        TestOrdinaryDrainCommitStepV1.WITNESS -> call.step === TestOrdinaryDrainStepV1.WITNESS && call.sql == TestOrdinaryDrainSqlV1.spendAndProgress
        TestOrdinaryDrainCommitStepV1.CONVERT -> call.step === TestOrdinaryDrainStepV1.CONVERT && call.sql == TestOrdinaryDrainSqlV1.convert
        TestOrdinaryDrainCommitStepV1.RECYCLE -> call.step === TestOrdinaryDrainStepV1.RECYCLE && call.sql == TestOrdinaryDrainSqlV1.deleteRun
        TestOrdinaryDrainCommitStepV1.SEAL_VERIFY -> call.sealStep === TestOrdinarySealStepV1.VERIFY && call.sql == TestClosedOrdinarySealSqlV1.verifyRun
    }
}
