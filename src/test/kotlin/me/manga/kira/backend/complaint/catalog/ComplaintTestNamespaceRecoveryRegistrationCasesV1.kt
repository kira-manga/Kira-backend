package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.audit.domain.ComplaintInstallationEnrollmentAudit
import me.manga.kira.backend.common.infrastructure.persistence.GuardedJdbcTransactionManager
import me.manga.kira.backend.common.infrastructure.persistence.OwnerDeleteLiteralCharges
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.infrastructure.ComplaintInstallationExchangeAdapter
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestDeploymentDocumentV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestInitialAdmissionV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRecoveryRegistrationAttemptV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestProcessAssemblyV1
import me.manga.kira.backend.complaint.infrastructure.admission.TestNamespaceRecoveryRegistrationSqlV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationSqlV1
import me.manga.kira.backend.complaint.infrastructure.catalog.TRY_CATALOG_LOCK
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainExceptionV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainSqlV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunInstallationManifestResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOrdinaryDrainResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOrdinaryDrainV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunSealingV1
import me.manga.kira.backend.complaint.infrastructure.transaction.DeletionPersistenceAdmission
import me.manga.kira.backend.complaint.journal.TestOrdinaryInventoryHttpFixtureV1
import me.manga.kira.backend.security.TestTerminalJsonV1
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

internal enum class TestRecoveryRegistrationIdentityCutV1 { FULL_D, DATABASE, RESTORE, EVENT_WRITER, CATALOG_WRITER }
internal enum class TestRecoveryRegistrationRawCutV1 { MISSING_REPLICA, CHANGED_ENVELOPE }
internal enum class TestRecoveryRegistrationPhaseCutV1(val count: Int) { CAPTURE(1), RECHECK(2) }

/**
 * New actual normal-root assembly after the predecessor roots physically ended. Reuses the original
 * signed PROJECT, protected input, immutable-secret HTTP, SQL, raw object and native drain fixtures.
 * All provider policy/denial/horizon inputs are synthetic TEST declarations, not deployment proof.
 * Fresh-root SAME-JVM only: no claim of a genuine separate-process handoff.
 * SOURCE ONLY: NOT_COMPILED / NOT_RUN / NOT_INDEPENDENTLY_REVIEWED.
 */
internal object ComplaintTestNamespaceRecoveryRegistrationCasesV1 {
    fun freshRootContinuesWithoutReplayingProjection(tls: VersionBoundPersistenceConnectedFixture, paidCut: Boolean) =
        withRecoveryRun(tls) { f ->
            val objects = TestOrdinaryDrainAccountingCasesV1.addRetainedAliases(f)
            val observed = TestOrdinaryDrainAccountingObservationV1(f)
            val initial = observed.state()
            val primary = observed.primaryImage()
            val historicalSeal = observed.previousHistory()
            val oldProcess = f.registration.process
            val oldDrain = f.begin()
            val approval = f.approval(oldDrain) // Real signed input artifact; it confers no registered authority.
            var stoppedPhase: PersistencePhaseContext? = null
            if (paidCut) {
                TestOrdinaryInventoryHttpFixtureV1(f.provider).use { native ->
                    f.probe.after = { call -> if (stoppedPhase == null && call.sql == TestOrdinaryDrainSqlV1.spendAndProgress) {
                        stoppedPhase = call.phase
                        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                            override fun afterCommit() { error("Synthetic stop after original committed paid witness.") }
                        })
                    } }
                    try {
                        assertThrows<TestOrdinaryDrainExceptionV1> {
                            oldDrain.drain(approval, f.rawEvidence, f.primaryCredentials, f.readCredentials)
                        }
                    } finally { f.probe.after = {} }
                    f.assertReleased()
                    assertEquals(PersistenceDatabaseOutcome.COMMITTED, checkNotNull(stoppedPhase).databaseOutcome())
                    assertTrue(native.requests.any { it.kind == "LIST" })
                    val paid = observed.state()
                    assertNotNull(paid.progressHex)
                    assertEquals("PARTIAL", paid.recoveryState)
                    assertEquals(initial.promise, paid.promise)
                    assertEquals(initial.used, paid.used, "No recovery is dispatched after WITNESS acknowledgment loss.")
                    assertEquals(2L, paid.scanRuns); assertEquals(8L, paid.scanEntries)
                    assertEquals(0L, paid.appliedScanEntries)
                    assertTrue(f.sealHttp.order.isEmpty())
                }
            }
            val preRestart = observed.state()
            val oldFence = leaseToken(f)
            // Observation reset only AFTER actual failure/release assertions; no retained product state changes.
            f.probe.reset(); f.jdbc.reset()
            withColdRoot(f) { cold ->
                assertArrayEquals(oldProcess.canonicalBytes(), cold.assembly.target.canonicalBytes(), "Complete D, not merely TEST J.")
                assertEquals(oldProcess.databaseIdentity, cold.assembly.target.databaseIdentity)
                assertEquals(oldProcess.restoreIdentity, cold.assembly.target.restoreIdentity)
                assertEquals(oldProcess.consumers.journalConfiguration.declaration().writer,
                    cold.assembly.target.consumers.journalConfiguration.declaration().writer)
                val before = image(f)
                val attempt = cold.begin()
                cold.register(attempt).use { registration ->
                    assertEquals(before, image(f), "CAPTURE/raw dual read/RECHECK do not repay rows, touch xmin or take a lease.")
                    assertEquals(preRestart, observed.state())
                    assertEquals(oldFence, leaseToken(f))
                    cold.assertSuccessful(attempt)
                    assertSame(cold.assembly.target, registration.process)
                    val sql = cold.probe.calls.size
                    val requests = cold.raw.requests.size
                    assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { cold.begin() }
                    assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { cold.register(attempt) }
                    assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { ComplaintTestNamespaceRegistrationV1.issuedByRecovery(attempt) }
                    assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { registration.bootstrapExpectedArguments() }
                    assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { TestRunSealingV1.begin(registration) }
                    assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { ComplaintTestInitialAdmissionV1.begin(registration, cold.assembly) }
                    assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { registration.requireReleasedIdentityAdmission() }
                    assertEquals(sql, cold.probe.calls.size); assertEquals(requests, cold.raw.requests.size)

                    withColdDrain(f, cold, registration) { drain, coordinator, deletion ->
                        TestOrdinaryInventoryHttpFixtureV1(f.provider).use { native ->
                            native.observeExactGetsWithoutPriorList = paidCut
                            assertEquals(TestRunOrdinaryDrainResultV1.POST_DENIAL_ORDINARY_SEAL_VERIFIED,
                                drain.drain(approval, f.rawEvidence, f.primaryCredentials, f.readCredentials))
                            coordinator.assertReleased(); deletion.assertReleased(); cold.probe.assertCommittedAndReleased()
                            assertTrue(leaseToken(f) > oldFence, "New drain takes its own fence after actual natural expiry.")
                            if (paidCut) {
                                assertEquals(preRestart.progressHex, observed.state().progressHex)
                                assertTrue(native.requests.none { it.kind == "LIST" }, "Paid staging is adopted, never rescanned/recharged.")
                                assertTrue(coordinator.calls.none { it.sql in setOf(TestOrdinaryDrainSqlV1.spendAndProgress,
                                    TestOrdinaryDrainSqlV1.insertRun, TestOrdinaryDrainSqlV1.insertEntry) })
                            } else {
                                assertEquals(1, coordinator.calls.count { it.sql == TestOrdinaryDrainSqlV1.spendAndProgress })
                                assertEquals(2, coordinator.calls.count { it.sql == TestOrdinaryDrainSqlV1.insertRun })
                            }
                        }
                    }
                    val completed = observed.state()
                    assertEquals("CONVERTED", completed.recoveryState)
                    assertEquals(OwnerDeleteLiteralCharges.ordinaryApply.scaled(4), completed.used)
                    assertEquals(0L, completed.scanRuns); assertEquals(0L, completed.scanEntries)
                    assertEquals(1L, completed.sidecars)
                    assertEquals(primary, observed.primaryImage(), "Registered recovery does not replay already APPLIED primary/audit work.")
                    assertEquals(historicalSeal, observed.previousHistory())
                    TestOrdinaryDrainAccountingCasesV1.assertPaidOrdinaryResult(f, objects, approval)
                    assertEquals(1, f.sealHttp.requests.count { it.kind == "PUT" })
                    f.assertReleased()
                }
            }
        }

    fun changedIdentityRefuses(tls: VersionBoundPersistenceConnectedFixture, cut: TestRecoveryRegistrationIdentityCutV1) =
        withRecoveryRun(tls) { f ->
            withColdRoot(f, changeDocument = { document ->
                if (cut === TestRecoveryRegistrationIdentityCutV1.FULL_D)
                    document.copy(admission = document.admission.copy(ownerCreateGlobalPerHour = document.admission.ownerCreateGlobalPerHour + 1))
                else document
            }) { cold ->
                val column = when (cut) {
                    TestRecoveryRegistrationIdentityCutV1.FULL_D -> null
                    TestRecoveryRegistrationIdentityCutV1.DATABASE -> "database_identity"
                    TestRecoveryRegistrationIdentityCutV1.RESTORE -> "restore_identity"
                    TestRecoveryRegistrationIdentityCutV1.EVENT_WRITER -> "event_writer_generation"
                    TestRecoveryRegistrationIdentityCutV1.CATALOG_WRITER -> "catalog_writer_generation"
                }
                val original = column?.let { f.observer.queryForObject("SELECT $it FROM complaint_journal_control WHERE data_scope_id = ?", UUID::class.java, f.scope) }
                try {
                    if (column != null) {
                        // Explicit SQL-corruption stimulus AFTER old physical cleanup. Never a supplied authority/restore proof.
                        assertEquals(1, f.observer.update("UPDATE complaint_journal_control SET $column = ? WHERE data_scope_id = ?", UUID.randomUUID(), f.scope))
                    } else assertFalse(f.registration.process.canonicalBytes().contentEquals(cold.assembly.target.canonicalBytes()))
                    val before = image(f)
                    val attempt = cold.begin()
                    assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { cold.register(attempt) }
                    attempt.requireActualCleanup()
                    assertEquals(before, image(f), "Refusal must not reset changed identity/full D or any accounting.")
                    assertTrue(cold.raw.requests.isEmpty(), "Locked identity refusal precedes native readback.")
                    assertNull(SignedActivationObservation.active(cold.runtime.pools.catalogCoordinator))
                    assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { cold.begin() }
                    assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { ComplaintTestNamespaceRegistrationV1.issuedByRecovery(attempt) }
                } finally {
                    if (column != null) assertEquals(1, f.observer.update("UPDATE complaint_journal_control SET $column = ? WHERE data_scope_id = ?", checkNotNull(original), f.scope))
                }
            }
        }

    fun rawCopiesAreIndependentlyRequired(tls: VersionBoundPersistenceConnectedFixture, cut: TestRecoveryRegistrationRawCutV1) =
        withRecoveryRun(tls) { f ->
            withColdRoot(f) { cold ->
                val provider = f.history.p.f.http
                val replica = provider.replicaVersion
                val bytes = provider.primaryBytes
                val before = image(f)
                val attempt = cold.begin()
                try {
                    when (cut) {
                        TestRecoveryRegistrationRawCutV1.MISSING_REPLICA -> provider.replicaVersion = null
                        TestRecoveryRegistrationRawCutV1.CHANGED_ENVELOPE -> provider.primaryBytes = bytes.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
                    }
                    assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { cold.register(attempt) }
                } finally { provider.replicaVersion = replica; provider.primaryBytes = bytes }
                attempt.requireActualCleanup()
                assertEquals(1, cold.probe.observations.size, "CAPTURE committed; failed raw verification cannot enter RECHECK.")
                cold.probe.assertCommittedAndReleased()
                assertTrue(cold.raw.requests.isNotEmpty())
                cold.assertRawClosed()
                assertEquals(before, image(f))
                assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { ComplaintTestNamespaceRegistrationV1.issuedByRecovery(attempt) }
                assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { cold.begin() }
            }
        }

    fun currentReserveMismatchIsNotRepaid(tls: VersionBoundPersistenceConnectedFixture) =
        withRecoveryRun(tls) { f ->
            withColdRoot(f) { cold ->
                // Legal vector but unattributed missing reserve unit. This is corruption, not a spending producer.
                assertEquals(1, f.observer.update("UPDATE complaint_test_runs SET unused_reserve[21] = unused_reserve[21] - 1 WHERE data_scope_id = ?", f.scope))
                val before = image(f)
                val attempt = cold.begin()
                assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { cold.register(attempt) }
                attempt.requireActualCleanup()
                assertEquals(before, image(f)); assertTrue(cold.raw.requests.isEmpty())
                assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { ComplaintTestNamespaceRegistrationV1.issuedByRecovery(attempt) }
            }
        }

    fun manifestProgressRemainsOutsideColdSlice(tls: VersionBoundPersistenceConnectedFixture) =
        withRecoveryRun(tls) { f ->
            TestOrdinaryInventoryHttpFixtureV1(f.provider).use {
                val original = f.begin()
                assertEquals(TestRunOrdinaryDrainResultV1.POST_DENIAL_ORDINARY_SEAL_VERIFIED,
                    original.drain(f.approval(original), f.rawEvidence, f.primaryCredentials, f.readCredentials))
                assertEquals(TestRunInstallationManifestResultV1.ALL_CHUNKS_PREPARED_NO_NETWORK, original.prepareInstallationManifest())
                // PREPARE persists two installation reads and canonical chunks, not final run roots/counts or purge state.
                val progress = TestTerminalJsonV1(f.registration.process.consumers.journalConfiguration)
                    .progress(TestOrdinaryDrainAccountingObservationV1(f).runBytes("permanent_denial_bytes"))
                assertEquals(2, progress.installationReads().size)
                assertTrue(checkNotNull(f.observer.queryForObject(
                    "SELECT state = 'SEALED' AND purging_at IS NULL AND purged_at IS NULL " +
                        "AND installation_manifest_count IS NULL AND installation_manifest_root IS NULL AND installation_chunk_count IS NULL " +
                        "FROM complaint_test_runs WHERE data_scope_id = ?", Boolean::class.java, f.scope)))
            }
            withColdRoot(f) { cold ->
                val before = image(f)
                val attempt = cold.begin()
                assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { cold.register(attempt) }
                attempt.requireActualCleanup()
                assertEquals(before, image(f)); assertTrue(cold.raw.requests.isEmpty())
                assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { ComplaintTestNamespaceRegistrationV1.issuedByRecovery(attempt) }
            }
        }

    fun actualCompletionAndCleanupAreRequired(tls: VersionBoundPersistenceConnectedFixture,
        phaseCut: TestRecoveryRegistrationPhaseCutV1, cut: TestRegistrationCompletionCut) =
        withRecoveryRun(tls) { f ->
            withColdRoot(f) { cold ->
                val before = image(f)
                val attempt = cold.begin()
                var selected: PersistencePhaseContext? = null
                val key = Any(); val sentinel = Any()
                var bound = false
                cold.probe.after = { call ->
                    if (selected == null && cold.probe.observations.size == phaseCut.count && call.sql == TestNamespaceRecoveryRegistrationSqlV1.runFingerprint) {
                        selected = call.phase
                        if (cut === TestRegistrationCompletionCut.DEFERRED_COMMIT_UNKNOWN) {
                            // Native COMMIT fails on the original RC holder. No fabricated UNKNOWN or completion flag.
                            val actual = JdbcTemplate(cold.runtime.pools.catalogCoordinator.dataSource)
                            actual.execute("CREATE TEMP TABLE kira_cold_registration_commit_cut (id int UNIQUE DEFERRABLE INITIALLY DEFERRED)")
                            assertEquals(2, actual.update("INSERT INTO kira_cold_registration_commit_cut VALUES (1), (1)"))
                        } else TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                            override fun beforeCommit(readOnly: Boolean) {
                                if (cut === TestRegistrationCompletionCut.BEFORE_COMMIT) error("Synthetic cold registration beforeCommit refusal.")
                            }
                            override fun afterCommit() {
                                if (cut === TestRegistrationCompletionCut.UNRESOLVED_RELEASE) {
                                    TransactionSynchronizationManager.bindResource(key, sentinel); bound = true
                                }
                                if (cut !== TestRegistrationCompletionCut.BEFORE_COMMIT) error("Synthetic cold registration acknowledgment loss.")
                            }
                        })
                    }
                }
                try {
                    assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { cold.register(attempt) }
                    if (cut === TestRegistrationCompletionCut.UNRESOLVED_RELEASE) {
                        assertTrue(bound); assertSame(selected, PersistencePhaseOwnership.current())
                        assertTrue(checkNotNull(selected).quarantined())
                        assertFalse(checkNotNull(selected).testRecoveryRegistrationCleanupProven(attempt))
                        assertSame(attempt, SignedActivationObservation.active(cold.runtime.pools.catalogCoordinator))
                    }
                } finally {
                    cold.probe.after = {}
                    if (bound) assertSame(sentinel, TransactionSynchronizationManager.unbindResource(key))
                    requireConnectionFree() // Fixture disposal can settle the phase; it cannot repair original issuance.
                }
                assertNotNull(selected, "The original transaction reached the selected cut.")
                val outcome = when (cut) {
                    TestRegistrationCompletionCut.BEFORE_COMMIT -> PersistenceDatabaseOutcome.ROLLED_BACK
                    TestRegistrationCompletionCut.DEFERRED_COMMIT_UNKNOWN -> PersistenceDatabaseOutcome.UNKNOWN
                    else -> PersistenceDatabaseOutcome.COMMITTED
                }
                assertEquals(outcome, checkNotNull(selected).databaseOutcome())
                assertEquals(phaseCut.count, cold.probe.observations.size)
                assertEquals(phaseCut === TestRecoveryRegistrationPhaseCutV1.CAPTURE, cold.raw.requests.isEmpty())
                cold.assertRawClosed()
                val retained = cut in setOf(TestRegistrationCompletionCut.DEFERRED_COMMIT_UNKNOWN, TestRegistrationCompletionCut.UNRESOLVED_RELEASE)
                if (retained) {
                    assertSame(attempt, SignedActivationObservation.active(cold.runtime.pools.catalogCoordinator))
                    assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { attempt.requireActualCleanup() }
                } else {
                    attempt.requireActualCleanup()
                    assertNull(SignedActivationObservation.active(cold.runtime.pools.catalogCoordinator))
                }
                assertEquals(before, image(f), "No projected/run/counter write even if the read-only COMMIT acknowledgment fails.")
                assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { ComplaintTestNamespaceRegistrationV1.issuedByRecovery(attempt) }
                assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { cold.begin() }
                assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { cold.register(attempt) }
            }
        }

    /** Select the legal 30-second synthetic TEST scan bound BEFORE J/full D/signing, never after a cut. */
    private fun withRecoveryRun(tls: VersionBoundPersistenceConnectedFixture, action: (TestRunOrdinaryDrainFixtureV1) -> Unit) =
        withOrdinaryDrainRun(tls, inputs = TestOrdinaryDrainFixtureInputsV1(scanMillis = 30_000),
            expireClosedSetupPredecessors = true, protectedIntake = true, action = action)

    private fun withColdRoot(f: TestRunOrdinaryDrainFixtureV1,
        changeDocument: (ComplaintTestDeploymentDocumentV1) -> ComplaintTestDeploymentDocumentV1 = { it },
        action: (ColdRuntime) -> Unit) {
        f.assertReleased()
        val previous = f.registration.process
        val p = f.history.p
        f.registration.close()
        f.history.runtime.closeRegisteredRuntimeForRecovery() // Includes the actual predecessor, trust files, actors and sessions.
        assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { f.registration.requireUsable() }
        awaitNaturalLeaseExpiry(f) // Outside the new admission's single parent deadline; never manufacture expiry.
        p.f.rows.evidence.reassembleRecoveryIntake(f.sealHttp, changeDocument).use { assembly ->
            val runtime = VersionBoundPersistenceConnectedFixture(f.history.runtime.database,
                endpointPort = f.history.runtime.endpointPort, testIntake = assembly)
            try {
                runtime.bind(); runtime.start()
                assertEquals(PersistenceLifecycleObservation.READY, runtime.pools.catalogCoordinator.prepare())
                assertEquals(PersistenceLifecycleObservation.READY, runtime.pools.deletion.prepareDeletion())
                val target = assembly.target
                assertNotSame(previous, target); assertNotSame(previous.pools, target.pools)
                assertNotSame(previous.consumers, target.consumers)
                assertNotSame(previous.consumers.journalRouting, target.consumers.journalRouting)
                assertNotSame(previous.publicationLanes, target.publicationLanes)
                assertNotSame(previous.ordinarySeal, target.ordinarySeal)
                assertNotSame(f.history.runtime.owner, runtime.owner)
                val cold = ColdRuntime(f, assembly, runtime)
                val executor = runtime.pools.catalogCoordinator.testNamespaceRecoveryRegistration
                val field = executor.javaClass.getDeclaredField("jdbc").apply { check(trySetAccessible()) }
                val actual = field.get(executor) as JdbcTemplate
                assertSame(actual.dataSource, cold.probe.dataSource)
                field.set(executor, cold.probe) // Passive SQL observer only, never a proof/operation substitute.
                try { action(cold); cold.probe.assertNoLostAssertions() }
                finally {
                    assertSame(cold.probe, field.get(executor)); field.set(executor, actual)
                    cold.probe.assertPhysicallyReleased(); cold.assertRawClosed()
                }
            } finally { runtime.close() }
        }
    }

    private fun withColdDrain(f: TestRunOrdinaryDrainFixtureV1, cold: ColdRuntime, registration: ComplaintTestNamespaceRegistrationV1,
        action: (TestRunOrdinaryDrainV1, TestOrdinaryDrainSqlProbeV1, TestOrdinaryDrainSqlProbeV1) -> Unit) {
        ComplaintTestNamespaceRegistrationCases.withOrdinaryAudit(cold.runtime) { ordinary, audit ->
            val enrollmentAudit = ComplaintInstallationEnrollmentAudit { scope, allocation, at -> audit.recordInstallationEnrollment(scope, allocation, at) }
            assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> {
                ComplaintInstallationExchangeAdapter(registration, ordinary.ownership, ordinary.jdbc, enrollmentAudit)
            }
            val owner = PersistencePhaseOwnership.deletion(DeletionPersistenceAdmission(), GuardedJdbcTransactionManager(cold.runtime.pools.deletion))
            assertNotSame(f.ownership, owner)
            val deletion = TestOrdinaryDrainSqlProbeV1(f.history.p, cold.runtime, deletion = true)
            val coordinator = TestOrdinaryDrainSqlProbeV1(f.history.p, cold.runtime)
            val originals = listOf<Any>(cold.runtime.pools.catalogCoordinator.testOrdinaryDrain, cold.runtime.pools.catalogCoordinator.testOrdinarySeal).map { executor ->
                val field = executor.javaClass.getDeclaredField("jdbc").apply { check(trySetAccessible()) }
                Triple(executor, field, field.get(executor) as JdbcTemplate)
            }
            originals.forEach { (executor, field, previous) -> assertSame(previous.dataSource, coordinator.dataSource); field.set(executor, coordinator) }
            val priorBoundary = f.sealHttp.boundary
            val priorNative = f.sealHttp.nativeBoundary
            val priorPrepare = f.provider.beforePrepare
            val priorClose = f.provider.onClientClose
            val priorKmsClose = f.provider.kms.onClientClose
            fun physical() { cold.probe.assertCommittedAndReleased(); coordinator.assertPhysicallyReleased(); deletion.assertPhysicallyReleased() }
            fun dispatch() { physical(); coordinator.assertReleased(); deletion.assertReleased() }
            f.sealHttp.boundary = { priorBoundary(); dispatch() }
            f.sealHttp.nativeBoundary = { priorNative(); physical() }
            f.provider.beforePrepare = { priorPrepare(); dispatch() }
            f.provider.onClientClose = { physical(); priorClose() }
            f.provider.kms.onClientClose = { physical(); priorKmsClose() }
            try {
                val drain = TestRunOrdinaryDrainV1.withHttpFixture(registration, owner, deletion, audit, f.clock, f.nanoTime,
                    { dispatch(); f.provider.beforeOpen(); f.provider.httpClient() }, { dispatch(); f.provider.kms.httpClient() })
                coordinator.original = drain; deletion.original = drain
                action(drain, coordinator, deletion)
                coordinator.assertReleased(); deletion.assertReleased()
            } finally {
                f.sealHttp.boundary = priorBoundary; f.sealHttp.nativeBoundary = priorNative
                f.provider.beforePrepare = priorPrepare; f.provider.onClientClose = priorClose; f.provider.kms.onClientClose = priorKmsClose
                originals.forEach { (executor, field, previous) -> assertSame(coordinator, field.get(executor)); field.set(executor, previous) }
            }
        }
    }

    private fun awaitNaturalLeaseExpiry(f: TestRunOrdinaryDrainFixtureV1) {
        requireConnectionFree()
        val deadline = System.nanoTime() + 35_000_000_000L
        while (true) {
            val expired = f.observer.queryForObject("SELECT count(*) = 2 AND bool_and(" +
                "(lease_owner IS NULL AND lease_expires_at IS NULL) OR lease_expires_at <= clock_timestamp()) " +
                "FROM complaint_journal_control WHERE data_scope_id IN (?, ?)", Boolean::class.java, ComplaintDataScope.LIVE.id, f.scope)
            if (expired == true) return
            assertTrue(System.nanoTime() < deadline, "The actual predecessor leases did not naturally expire within 35 seconds.")
            Thread.sleep(100)
        }
    }

    private fun leaseToken(f: TestRunOrdinaryDrainFixtureV1): Long = checkNotNull(f.observer.queryForObject(
        "SELECT lease_token FROM complaint_journal_control WHERE data_scope_id = ?", Long::class.java, f.scope))

    private fun image(f: TestRunOrdinaryDrainFixtureV1): Map<String, List<String>> =
        TestOrdinaryDrainAccountingObservationV1(f).image().toMutableMap().apply {
            put("all-controls", f.observer.query("SELECT jsonb_build_array(to_jsonb(c), c.xmin::text)::text FROM complaint_journal_control c ORDER BY data_scope_id",
                { row, _ -> row.getString(1) }))
        }

    private class ColdRuntime(val f: TestRunOrdinaryDrainFixtureV1, val assembly: ComplaintTestProcessAssemblyV1,
        val runtime: VersionBoundPersistenceConnectedFixture) {
        val probe = TestRecoveryRegistrationSqlProbeV1(f.history.p, runtime)
        // Fresh native clients read the SAME actual retained fixture object versions, not old readback/chain/proof objects.
        // Do not increment the original primary-history fixture's catalog-reader counter during cold registration.
        val raw = S3CatalogReadbackFixture().apply {
            respond = { request ->
                f.assertDatabaseReleased(); probe.assertCommittedAndReleased()
                f.history.p.f.http.read.respond(request).apply {
                    val priorRead = beforeRead; val priorClose = onClose; val priorAbort = onAbort
                    beforeRead = { probe.assertCommittedAndReleased(); priorRead() }
                    onClose = { probe.assertCommittedAndReleased(); priorClose() }
                    onAbort = { probe.assertCommittedAndReleased(); priorAbort() }
                }
            }
        }

        fun begin(): ComplaintTestNamespaceRecoveryRegistrationAttemptV1 =
            ComplaintTestNamespaceRecoveryRegistrationAttemptV1.withHttpFixture(assembly, raw::httpClient, SignedActivationObservation.WALL_CLOCK)
                .also { probe.original = it }

        fun register(attempt: ComplaintTestNamespaceRecoveryRegistrationAttemptV1): ComplaintTestNamespaceRegistrationV1 =
            attempt.register(S3CatalogReadbackFixture.credentials, S3CatalogReadbackFixture.credentials)

        fun assertSuccessful(attempt: ComplaintTestNamespaceRecoveryRegistrationAttemptV1) {
            attempt.requireActualCleanup()
            probe.assertCommittedAndReleased()
            assertEquals(2, probe.observations.size)
            assertNull(SignedActivationObservation.active(runtime.pools.catalogCoordinator))
            assertEquals(2, raw.createdClients)
            assertTrue(raw.requests.isNotEmpty() && raw.requests.all { it.method().name == "GET" })
            assertRawClosed()
            probe.calls.groupBy { it.phase }.values.forEach { calls ->
                val controls = calls.filter { it.sql == TestNamespaceRecoveryRegistrationSqlV1.lockControl }
                assertEquals(listOf(ComplaintDataScope.LIVE.id, f.scope, ComplaintDataScope.LIVE.id, f.scope), controls.map { it.arguments.last() })
                val firstGlobal = calls.indexOf(controls[0]); val firstScope = calls.indexOf(controls[1])
                val catalog = calls.indexOfFirst { it.sql == TRY_CATALOG_LOCK }
                val history = calls.indexOfFirst { it.sql == CatalogTestRunActivationSqlV1.lockRecoveryRegistrationHistory }
                val run = calls.indexOfFirst { it.sql == TestNamespaceRecoveryRegistrationSqlV1.lockRunIdentity }
                assertTrue(firstGlobal < firstScope && firstScope < catalog && catalog < history && history < run)
                assertTrue(calls.none { it.sql.trimStart().startsWith("UPDATE") || it.sql.trimStart().startsWith("INSERT") || it.sql.trimStart().startsWith("DELETE") })
            }
        }

        fun assertRawClosed() {
            assertEquals(raw.createdClients, raw.closedClients)
            raw.replies.filter { it.calls > 0 && it.bodyPresent }.forEach { assertTrue(it.closes > 0) }
        }
    }
}
