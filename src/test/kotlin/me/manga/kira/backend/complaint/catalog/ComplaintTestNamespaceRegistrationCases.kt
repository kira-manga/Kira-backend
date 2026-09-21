package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.audit.domain.ComplaintInstallationEnrollmentAudit
import me.manga.kira.backend.audit.infrastructure.JpaAuditRepositoryAdapter
import me.manga.kira.backend.audit.infrastructure.SpringDataAuditLogRepository
import me.manga.kira.backend.common.infrastructure.persistence.GuardedJpaTransactionManager
import me.manga.kira.backend.common.infrastructure.persistence.OrdinaryPersistenceAdmission
import me.manga.kira.backend.common.infrastructure.persistence.OrdinarySourceGrantCleanupFixture
import me.manga.kira.backend.common.infrastructure.persistence.OwnedCutPool
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.ordinaryCleanupFactory
import me.manga.kira.backend.common.infrastructure.persistence.ordinaryCleanupReader
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintInstallationHttpFailure
import me.manga.kira.backend.complaint.domain.ComplaintInstallationHttpRejected
import me.manga.kira.backend.complaint.domain.ComplaintPlatform
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleException
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleFailure
import me.manga.kira.backend.complaint.infrastructure.ComplaintInstallationExchangeAdapter
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationAttemptV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationV1
import me.manga.kira.backend.complaint.infrastructure.admission.VersionBoundTestNamespaceProcessV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationHistoryV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunFirstProjectionV1
import me.manga.kira.backend.security.CurrentUser
import me.manga.kira.backend.security.InstallationEnrollmentCredentials
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.orm.jpa.SharedEntityManagerCreator
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.io.IOException
import java.io.InterruptedIOException
import java.util.UUID
import java.util.concurrent.CancellationException

internal enum class TestRegistrationDriftCut { MISSING_REPLICA, ENROLLED_AFTER_CAPTURE, NOTICE_AFTER_CAPTURE, POLICY_AFTER_CAPTURE, LEASE_AFTER_CAPTURE }
internal enum class TestRegistrationProviderCut { CANCELLATION, INTERRUPTED_IO, CLOSE_RECEIPT }
internal enum class TestRegistrationCompletionCut { BEFORE_COMMIT, DEFERRED_COMMIT_UNKNOWN, AFTER_COMMIT, UNRESOLVED_RELEASE }
internal enum class TestRegistrationProjectCut { DEFERRED_COMMIT_UNKNOWN, AFTER_COMMIT, FILE_CLOSE_RECEIPT }

/**
 * Extends the existing signed/COMPLETE/PROJECT and real TLS fixtures. The target has an independent
 * normal runtime root; no synthetic TEST row, receipt/hash, registered fixture issuer or gate opener.
 */
internal object ComplaintTestNamespaceRegistrationCases {
    fun firstFreshBindingIsConsumedByInstallation(tls: VersionBoundPersistenceConnectedFixture, shutdownRoot: Boolean) = withPendingProjectionRows(tls) { p ->
        p.fresh { projector -> withRuntimeRoot(p, projector) { runtime, target, probe ->
            val original = p.begin(projector)
            val completion = original.projectForRegistration(target, p.f.signed.root, p.f.rows.intent, credentials, credentials)
            p.assertProjected()
            p.assertReleased(original, projector)
            assertFalse(projector.pools.ordinary.businessReady(), "The named projector remains permanently sealed; it is not the consumer root.")
            assertArrayEquals(original.process.canonicalBytes(), target.canonicalBytes())
            val before = p.image()
            val global = globalImage(p)
            val requests = p.f.http.read.requests.size
            val clients = p.f.http.read.createdClients
            val attempt = beginRegistration(p, completion)
            p.f.http.beforeRead = {
                p.f.signed.releasedSql()
                assertEquals(0, runtime.pools.catalogCoordinator.activeSnapshotOwners())
                assertEquals(1, probe.observations.size, "Only the target's actual released capture precedes raw verification.")
                assertEquals(PersistenceDatabaseOutcome.COMMITTED, probe.observations.keys.single().databaseOutcome())
                assertTrue(probe.observations.values.single().lease.completion.quiescent())
            }
            val registration = try { attempt.register(credentials, credentials) } finally { p.f.http.beforeRead = p.f.signed::releasedSql }
            registration.use {
                assertSame(target, it.process)
                assertEquals(clients + 2, p.f.http.read.createdClients, "The target opens its own genuine SDK reader pair.")
                assertTrue(p.f.http.read.requests.size > requests)
                assertReleased(p, runtime, probe, attempt, phases = 2)
                assertOrderAndNoWrites(p, probe)
                assertEquals(before, p.image(), "Registration cannot churn any projected row/counter/audit xmin or timestamp.")
                assertEquals(global, globalImage(p), "No acquired/transplanted lease or global-control write.")
                val calls = probe.calls.size
                val reads = p.f.http.read.requests.size
                assertThrows<CatalogTestRunActivationExceptionV1> { beginRegistration(p, completion) }
                assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { attempt.register(credentials, credentials) }
                assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { ComplaintTestNamespaceRegistrationV1.issuedBy(attempt) }
                assertEquals(calls, probe.calls.size)
                assertEquals(reads, p.f.http.read.requests.size)

                withUnseededOrdinary(runtime) { ordinary, audit ->
                    val adapter = ComplaintInstallationExchangeAdapter(it, ordinary.ownership, ordinary.jdbc, audit)
                    assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> {
                        ComplaintInstallationExchangeAdapter(it, ordinary.ownership, JdbcTemplate(runtime.pools.ordinary), audit)
                    }
                    val otherOwner = PersistencePhaseOwnership(OrdinaryPersistenceAdmission(2), GuardedJpaTransactionManager(ordinary.entityManagerFactory, ordinary.pool))
                    assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> {
                        ComplaintInstallationExchangeAdapter(it, otherOwner, ordinary.jdbc, audit)
                    }
                    assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> {
                        ComplaintInstallationExchangeAdapter(it, ordinary.ownership, JdbcTemplate(projector.pools.catalogCoordinator.dataSource), audit)
                    }
                    val installation = ScopedInstallationId(UUID.randomUUID(), target.consumers.journalConfiguration.scope)
                    val candidate = InstallationEnrollmentCredentials.prepare(installation, ComplaintPlatform.ANDROID, ByteArray(32) { index -> index.toByte() })
                    target.consumers.ingressAdmission.withIngress(request()) { context ->
                        assertEquals(ComplaintInstallationHttpFailure.UNAVAILABLE,
                            assertThrows<ComplaintInstallationHttpRejected> { adapter.enroll(context, candidate) }.failure,
                            "The real ordinary enrollment cannot use registration to reopen the still-closed maintenance gate.")
                    }
                    requireConnectionFree()
                    assertEquals(before, p.image())
                    assertEquals(global, globalImage(p))
                    assertEquals(0L, p.f.rows.observer.queryForObject("SELECT count(*) FROM complaint_installation_ids WHERE data_scope_id = ?", Long::class.java, p.scope))

                    if (shutdownRoot) runtime.owner.requestShutdown() else it.close()
                    assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { it.requireUsable() }
                    assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { ComplaintInstallationExchangeAdapter(it, ordinary.ownership, ordinary.jdbc, audit) }
                    val session = InstallationEnrollmentCredentials.prepareSession(installation, ByteArray(32) { index -> index.toByte() })
                    target.consumers.ingressAdmission.withIngress(request()) { context ->
                        assertEquals(ComplaintInstallationHttpFailure.UNAVAILABLE, assertThrows<ComplaintInstallationHttpRejected> { adapter.enroll(context, candidate) }.failure)
                        assertEquals(ComplaintInstallationHttpFailure.UNAVAILABLE, assertThrows<ComplaintInstallationHttpRejected> { adapter.session(context, session) }.failure)
                    }
                    assertEquals(before, p.image())
                }
            }
            p.assertReadOnlyProviders()
        } }
    }

    fun rawCopiesAndUsedEffectCannotRegister(tls: VersionBoundPersistenceConnectedFixture, cut: TestRegistrationDriftCut) = withPendingProjectionRows(tls) { p ->
        p.fresh { projector -> withRuntimeRoot(p, projector) { runtime, target, probe ->
            val original = p.begin(projector)
            val completion = original.projectForRegistration(target, p.f.signed.root, p.f.rows.intent, credentials, credentials)
            p.assertReleased(original, projector)
            var negative = p.image()
            var global = globalImage(p)
            var injected = false
            if (cut === TestRegistrationDriftCut.MISSING_REPLICA) p.f.http.replicaVersion = null
            p.f.http.beforeRead = {
                p.f.signed.releasedSql()
                if (!injected) {
                    assertEquals(1, probe.observations.size)
                    assertEquals(PersistenceDatabaseOutcome.COMMITTED, probe.observations.keys.single().databaseOutcome())
                    val observer = p.f.rows.observer
                    when (cut) {
                        TestRegistrationDriftCut.MISSING_REPLICA -> Unit
                        TestRegistrationDriftCut.ENROLLED_AFTER_CAPTURE -> assertEquals(1, observer.update("UPDATE complaint_test_runs SET enrolled_count = 1 WHERE data_scope_id = ?", p.scope))
                        TestRegistrationDriftCut.NOTICE_AFTER_CAPTURE -> assertEquals(2, observer.update("UPDATE complaints SET version = version + 1 WHERE data_scope_id = ?", p.scope))
                        TestRegistrationDriftCut.POLICY_AFTER_CAPTURE -> assertEquals(22, observer.update("UPDATE complaint_capacity_counters SET configuration_hash = decode(repeat('cd', 32), 'hex')"))
                        TestRegistrationDriftCut.LEASE_AFTER_CAPTURE -> assertEquals(1, observer.update("UPDATE complaint_journal_control SET lease_token = lease_token + 1 WHERE data_scope_id = ?", ComplaintDataScope.LIVE.id))
                    }
                    negative = p.image()
                    global = globalImage(p)
                    injected = true
                }
            }
            val attempt = beginRegistration(p, completion)
            try { assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { attempt.register(credentials, credentials) } }
            finally { p.f.http.beforeRead = p.f.signed::releasedSql }
            assertTrue(injected)
            attempt.requireActualCleanup()
            assertNull(SignedActivationObservation.active(runtime.pools.catalogCoordinator))
            assertEquals(if (cut === TestRegistrationDriftCut.MISSING_REPLICA) 1 else 2, probe.observations.size)
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, probe.observations.keys.first().databaseOutcome())
            if (cut !== TestRegistrationDriftCut.MISSING_REPLICA) assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, probe.observations.keys.last().databaseOutcome())
            assertEquals(negative, p.image(), "Freshness refusal cannot reset enrollment, notices, reserve/P, or any other injected state.")
            assertEquals(global, globalImage(p))
            assertThrows<CatalogTestRunActivationExceptionV1> { beginRegistration(p, completion) }
            assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { ComplaintTestNamespaceRegistrationV1.issuedBy(attempt) }
            p.assertNoProjectDml(probe)
            p.assertReadOnlyProviders()
            probe.assertNoLostAssertions()
        } }
    }

    fun providerFailureCannotIssueOrRetry(tls: VersionBoundPersistenceConnectedFixture, cut: TestRegistrationProviderCut) = withPendingProjectionRows(tls) { p ->
        p.fresh { projector -> withRuntimeRoot(p, projector) { runtime, target, probe ->
            val original = p.begin(projector)
            val completion = original.projectForRegistration(target, p.f.signed.root, p.f.rows.intent, credentials, credentials)
            val before = p.image()
            val attempt = beginRegistration(p, completion)
            var injected = false
            p.f.http.beforeRead = {
                p.f.signed.releasedSql()
                if (!injected && cut !== TestRegistrationProviderCut.CLOSE_RECEIPT) {
                    injected = true
                    if (cut === TestRegistrationProviderCut.CANCELLATION) throw CancellationException("Synthetic raw registration cancellation.")
                    throw InterruptedIOException("Synthetic raw registration interrupted I/O.")
                }
            }
            p.f.http.afterReadClientClose = {
                if (!injected && cut === TestRegistrationProviderCut.CLOSE_RECEIPT) {
                    injected = true
                    throw IOException("Synthetic raw reader physically closed without its original return receipt.")
                }
            }
            try {
                when (cut) {
                    TestRegistrationProviderCut.CANCELLATION -> assertThrows<CancellationException> { attempt.register(credentials, credentials) }
                    TestRegistrationProviderCut.INTERRUPTED_IO -> {
                        assertThrows<InterruptedException> { attempt.register(credentials, credentials) }
                        assertTrue(Thread.currentThread().isInterrupted)
                    }
                    TestRegistrationProviderCut.CLOSE_RECEIPT -> assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { attempt.register(credentials, credentials) }
                }
            } finally {
                p.f.http.beforeRead = p.f.signed::releasedSql
                p.f.http.afterReadClientClose = {}
                Thread.interrupted() // Fixture-only caller cleanup; never clear the original attempt's sticky signal.
            }
            assertTrue(injected)
            requireConnectionFree()
            assertSame(attempt, SignedActivationObservation.active(runtime.pools.catalogCoordinator))
            assertFalse(poolTestField<Boolean>(attempt, "cleanupProven"))
            assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { ComplaintTestNamespaceRegistrationV1.issuedBy(attempt) }
            assertThrows<CatalogTestRunActivationExceptionV1> { beginRegistration(p, completion) }
            assertEquals(1, probe.observations.size)
            assertEquals(before, p.image())
            p.assertReadOnlyProviders()
            probe.assertNoLostAssertions()
        } }
    }

    fun targetCompletionAndOriginalReleaseAreRequired(tls: VersionBoundPersistenceConnectedFixture, cut: TestRegistrationCompletionCut) = withPendingProjectionRows(tls) { p ->
        p.fresh { projector -> withRuntimeRoot(p, projector) { runtime, target, probe ->
            val completion = p.begin(projector).projectForRegistration(target, p.f.signed.root, p.f.rows.intent, credentials, credentials)
            val before = p.image()
            val global = globalImage(p)
            val attempt = beginRegistration(p, completion)
            val key = Any()
            val sentinel = Any()
            var bound = false
            var injected = false
            var selected: PersistencePhaseContext? = null
            probe.afterSql = { step -> if (!injected && probe.observations.size == 2 && step == "test-project-control-read") {
                selected = checkNotNull(PersistencePhaseOwnership.current())
                if (cut === TestRegistrationCompletionCut.DEFERRED_COMMIT_UNKNOWN) {
                    // Genuine native COMMIT failure on the same original connection; not a constructed UNKNOWN exception.
                    val jdbc = JdbcTemplate(runtime.pools.catalogCoordinator.dataSource)
                    jdbc.execute("CREATE TEMP TABLE kira_registration_commit_cut (id int UNIQUE DEFERRABLE INITIALLY DEFERRED)")
                    assertEquals(2, jdbc.update("INSERT INTO kira_registration_commit_cut VALUES (1), (1)"))
                } else TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun beforeCommit(readOnly: Boolean) {
                        // Spring rolls back a beforeCommit RuntimeException/Error, not a Kotlin-thrown checked IOException.
                        if (cut === TestRegistrationCompletionCut.BEFORE_COMMIT) throw IllegalStateException("Synthetic original registration beforeCommit failure.")
                    }
                    override fun afterCommit() {
                        if (cut === TestRegistrationCompletionCut.UNRESOLVED_RELEASE) {
                            TransactionSynchronizationManager.bindResource(key, sentinel)
                            bound = true
                        }
                        if (cut !== TestRegistrationCompletionCut.BEFORE_COMMIT) throw IOException("Synthetic original registration afterCommit failure.")
                    }
                })
                injected = true
            } }
            try {
                assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { attempt.register(credentials, credentials) }
                if (cut === TestRegistrationCompletionCut.UNRESOLVED_RELEASE) {
                    assertTrue(bound)
                    assertSame(selected, PersistencePhaseOwnership.current())
                    assertTrue(checkNotNull(selected).quarantined())
                }
            } finally {
                probe.afterSql = {}
                if (bound) assertSame(sentinel, TransactionSynchronizationManager.unbindResource(key))
                requireConnectionFree() // Truthful eventual same-phase retirement is not a repaired original success.
            }
            assertTrue(injected)
            val outcome = when (cut) {
                TestRegistrationCompletionCut.BEFORE_COMMIT -> PersistenceDatabaseOutcome.ROLLED_BACK
                TestRegistrationCompletionCut.DEFERRED_COMMIT_UNKNOWN -> PersistenceDatabaseOutcome.UNKNOWN
                else -> PersistenceDatabaseOutcome.COMMITTED
            }
            assertEquals(outcome, checkNotNull(selected).databaseOutcome())
            val sticky = cut in setOf(TestRegistrationCompletionCut.DEFERRED_COMMIT_UNKNOWN, TestRegistrationCompletionCut.UNRESOLVED_RELEASE)
            if (sticky) assertSame(attempt, SignedActivationObservation.active(runtime.pools.catalogCoordinator))
            else { attempt.requireActualCleanup(); assertNull(SignedActivationObservation.active(runtime.pools.catalogCoordinator)) }
            assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { ComplaintTestNamespaceRegistrationV1.issuedBy(attempt) }
            assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { attempt.register(credentials, credentials) }
            assertThrows<CatalogTestRunActivationExceptionV1> { beginRegistration(p, completion) }
            assertEquals(outcome, checkNotNull(selected).databaseOutcome())
            assertEquals(before, p.image())
            assertEquals(global, globalImage(p))
            p.assertNoProjectDml(probe)
            p.assertReadOnlyProviders()
            probe.assertNoLostAssertions()
        } }
    }

    fun changedTargetNamedRootAndClosedContinuationRefuse(tls: VersionBoundPersistenceConnectedFixture, closeContinuation: Boolean) = withPendingProjectionRows(tls) { p ->
        p.fresh { projector -> withRuntimeRoot(p, projector) { runtime, target, probe ->
            val named = p.f.rows.evidence.processOn(projector.pools)
            val wrong = p.begin(projector)
            val namedCalls = p.f.signed.probe(projector).calls.size
            try {
                assertThrows<IllegalArgumentException> { wrong.projectForRegistration(named, p.f.signed.root, p.f.rows.intent, credentials, credentials) }
                assertEquals(namedCalls, p.f.signed.probe(projector).calls.size)
            } finally { wrong.close() }
            val selected = if (closeContinuation) target else p.f.rows.evidence.processOn(runtime.pools, desiredGeneration = 8)
            val original = p.begin(projector)
            val completion = original.projectForRegistration(selected, p.f.signed.root, p.f.rows.intent, credentials, credentials)
            p.assertProjected()
            p.assertReleased(original, projector)
            val before = p.image()
            val reads = p.f.http.read.requests.size
            if (closeContinuation) {
                completion.close()
                assertThrows<CatalogTestRunActivationExceptionV1> { beginRegistration(p, completion) }
            } else assertEquals(OfflineTrustBundleFailure.POLICY_MISMATCH, assertThrows<OfflineTrustBundleException> { beginRegistration(p, completion) }.code)
            assertThrows<CatalogTestRunActivationExceptionV1> { beginRegistration(p, completion) }
            assertTrue(probe.calls.isEmpty())
            assertEquals(reads, p.f.http.read.requests.size)
            assertEquals(before, p.image())
            assertNull(SignedActivationObservation.active(runtime.pools.catalogCoordinator))
        } }
    }

    fun historicalProjectAndColdReplayCannotRegister(tls: VersionBoundPersistenceConnectedFixture) = withPendingProjectionRows(tls) { p ->
        p.fresh { historical ->
            withRuntimeRoot(p, historical) { _, target, probe ->
                val original = p.begin(historical)
                p.assertProjected(p.project(original))
                p.assertReleased(original, historical)
                assertThrows<CatalogTestRunActivationExceptionV1> { CatalogTestRunFirstProjectionV1.issuedBy(original, target) }
                assertTrue(probe.calls.isEmpty())
            }
            val before = p.image()
            p.fresh(previous = historical) { replay -> withRuntimeRoot(p, replay) { _, target, probe ->
                val original = p.begin(replay)
                assertThrows<CatalogTestRunActivationExceptionV1> { original.projectForRegistration(target, p.f.signed.root, p.f.rows.intent, credentials, credentials) }
                assertThrows<CatalogTestRunActivationExceptionV1> { CatalogTestRunFirstProjectionV1.issuedBy(original, target) }
                assertTrue(probe.calls.isEmpty())
                p.assertNoProjectDml(p.f.signed.probe(replay))
                assertEquals(before, p.image(), "No PROJECT replay, recharge, row reconstruction or registration recovery.")
            } }
        }
    }

    fun failedOriginalProjectCannotHandoffCompletion(tls: VersionBoundPersistenceConnectedFixture, cut: TestRegistrationProjectCut) = withPendingProjectionRows(tls) { p ->
        p.fresh { projector -> withRuntimeRoot(p, projector) { _, target, targetProbe ->
            val original = p.begin(projector)
            val probe = p.f.signed.probe(projector)
            val before = p.image()
            var selected: PersistencePhaseContext? = null
            var fileClosed: (() -> Boolean)? = null
            var injected = false
            probe.afterSql = { step -> if (p.currentProjectPhase() && step == "test-clear-pending") {
                selected = checkNotNull(PersistencePhaseOwnership.current())
                when (cut) {
                    TestRegistrationProjectCut.DEFERRED_COMMIT_UNKNOWN -> {
                        val jdbc = JdbcTemplate(projector.pools.catalogCoordinator.dataSource)
                        jdbc.execute("CREATE TEMP TABLE kira_registration_project_cut (id int UNIQUE DEFERRABLE INITIALLY DEFERRED)")
                        assertEquals(2, jdbc.update("INSERT INTO kira_registration_project_cut VALUES (1), (1)"))
                    }
                    TestRegistrationProjectCut.AFTER_COMMIT -> TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                        override fun afterCommit() { throw IOException("Synthetic PROJECT acknowledged commit then failed completion.") }
                    })
                    TestRegistrationProjectCut.FILE_CLOSE_RECEIPT -> { fileClosed = p.f.signed.failOriginalFileClose(original) }
                }
                injected = true
            } }
            try { assertThrows<CatalogTestRunActivationExceptionV1> { original.projectForRegistration(target, p.f.signed.root, p.f.rows.intent, credentials, credentials) } }
            finally { probe.afterSql = {} }
            assertTrue(injected)
            val unknown = cut === TestRegistrationProjectCut.DEFERRED_COMMIT_UNKNOWN
            assertEquals(if (unknown) PersistenceDatabaseOutcome.UNKNOWN else PersistenceDatabaseOutcome.COMMITTED, checkNotNull(selected).databaseOutcome())
            if (unknown) assertEquals(before, p.image()) else p.assertProjected()
            fileClosed?.let { assertTrue(it(), "Actual original file closure occurred; its missing return was not inferred successful.") }
            assertThrows<CatalogTestRunActivationExceptionV1> { CatalogTestRunFirstProjectionV1.issuedBy(original, target) }
            assertFalse(poolTestField<Boolean>(original, "completionContinuationIssued"))
            assertTrue(targetProbe.calls.isEmpty())
            p.assertReadOnlyProviders()
        } }
    }

    /** Same actual PROJECT/raw-copy registration fixture; the sealing cases do not mint another registration issuer. */
    internal fun withRegisteredRun(tls: VersionBoundPersistenceConnectedFixture,
        createGlobal: Int = 2,
        ordinarySealHttp: TestOrdinarySealHttpFixtureV1? = null,
        expireClosedSetupPredecessors: Boolean = false,
        ownerDeleteAll: Boolean = false,
        ordinaryDrain: TestOrdinaryDrainFixtureInputsV1? = null,
        registeredAdminDelete: Boolean = false,
        registeredAdminBatchDelete: Boolean = false,
        activeFirstCut: Boolean = false,
        activeSealRecovery: Boolean = false,
        ordinaryRawHttp: TestActiveOrdinaryRawHttpV1? = null,
        activeFirstCutSuccessor: Boolean = false,
        globalScanBeforeActivation: Boolean = false,
        action: (ProjectionActivationObservation, VersionBoundPersistenceConnectedFixture, ComplaintTestNamespaceRegistrationV1, CatalogSignerRotationProbeJdbc) -> Unit,
    ) = withCompletionActivationRows(tls, createGlobal = createGlobal, ordinarySealHttp = ordinarySealHttp, ownerDeleteAll = ownerDeleteAll, ordinaryDrain = ordinaryDrain, registeredAdminDelete = registeredAdminDelete, registeredAdminBatchDelete = registeredAdminBatchDelete, activeFirstCut = activeFirstCut, activeSealRecovery = activeSealRecovery, ordinaryRawHttp = ordinaryRawHttp, activeFirstCutSuccessor = activeFirstCutSuccessor, globalScanBeforeActivation = globalScanBeforeActivation) { f ->
        fun expireClosedSetupPredecessor(previous: VersionBoundPersistenceConnectedFixture) {
            if (!expireClosedSetupPredecessors) return
            requireConnectionFree()
            val scope = ComplaintDataScope.LIVE.id
            val predecessor = f.rows.observer.queryForMap(
                "SELECT lease_owner, lease_token, lease_expires_at, catalog_writer_generation, event_writer_generation " +
                    "FROM complaint_journal_control WHERE data_scope_id = ?", scope,
            )
            previous.close() // Actual root, actor and database-session cleanup must succeed before fixture-only mutation.
            // Only these two explicitly opted-in setup handoffs are synthetic expiry, never a release or natural-expiry proof.
            assertEquals(1, f.rows.observer.update(
                "UPDATE complaint_journal_control SET lease_expires_at = clock_timestamp() - interval '1 second' " +
                    "WHERE data_scope_id = ? AND lease_owner = ? AND lease_token = ? AND lease_expires_at = ? " +
                    "AND catalog_writer_generation = ? AND event_writer_generation = ?",
                scope, checkNotNull(predecessor["lease_owner"]), checkNotNull(predecessor["lease_token"]),
                checkNotNull(predecessor["lease_expires_at"]), checkNotNull(predecessor["catalog_writer_generation"]),
                checkNotNull(predecessor["event_writer_generation"]),
            ), "Fixture expiry must not replace a changed predecessor lease or writer.")
        }
        // Same completion -> PROJECT composition as withPendingProjectionRows; shared recovery fixtures stay unchanged.
        f.rows.retainProjectionRows()
        f.http.replicateOnPut = true
        expireClosedSetupPredecessor(f.signed.tls)
        f.signed.withFreshOwner { publishing -> // Still checks real DB-time expiry and acquires a genuine successor lease.
            val completed = f.begin(publishing)
            f.assertPending(f.deliver(completed))
            f.assertReleased(completed, publishing)
            val p = ProjectionActivationObservation(f, publishing)
            expireClosedSetupPredecessor(publishing)
            p.fresh { projector -> withRuntimeRoot(p, projector) { runtime, target, probe ->
                val original = p.begin(projector)
                val completion = original.projectForRegistration(target, p.f.signed.root, p.f.rows.intent, credentials, credentials)
                p.assertReleased(original, projector)
                val attempt = beginRegistration(p, completion)
                attempt.register(credentials, credentials).use { registration ->
                    assertReleased(p, runtime, probe, attempt, phases = 2)
                    probe.resetObservations()
                    val executor = runtime.pools.catalogCoordinator.testRunSealing
                    executor.javaClass.getDeclaredField("jdbc").apply { check(trySetAccessible()) }.set(executor, probe)
                    probe.beforeSql = {
                        val path = probe.calls.last().path
                        assertTrue(path.testRunSealing)
                        assertTrue(p.advisory(p.holder(runtime), "complaint-maintenance-v1", "ShareLock"))
                        assertEquals(path === PersistencePhasePath.COMPLAINT_TEST_RUN_SEALED_AUDIT,
                            p.advisory(p.holder(runtime), "complaint-journal-epoch", "ShareLock"),
                            "The run-only barrier releases before its successor may acquire E.")
                    }
                    f.rows.globalPredecessor?.assertPreserved(f.rows.observer)
                    action(p, runtime, registration, probe)
                    probe.assertNoLostAssertions()
                }
                p.assertReadOnlyProviders()
            } }
        }
    }

    private fun beginRegistration(p: ProjectionActivationObservation, completion: CatalogTestRunFirstProjectionV1) =
        ComplaintTestNamespaceRegistrationAttemptV1.withHttpFixture(completion, p.f.http::readClient, SignedActivationObservation.WALL_CLOCK)

    private fun assertReleased(p: ProjectionActivationObservation, runtime: VersionBoundPersistenceConnectedFixture, probe: CatalogSignerRotationProbeJdbc,
        attempt: ComplaintTestNamespaceRegistrationAttemptV1, phases: Int) {
        attempt.requireActualCleanup()
        requireConnectionFree()
        assertNull(PersistencePhaseOwnership.current())
        assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty())
        assertEquals(0, runtime.pools.catalogCoordinator.activeSnapshotOwners())
        assertNull(SignedActivationObservation.active(runtime.pools.catalogCoordinator))
        assertEquals(phases, probe.observations.size)
        probe.observations.forEach { (phase, observed) ->
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome())
            assertTrue(phase.testRegistrationCleanupProven(attempt))
            assertTrue(observed.lease.completion.quiescent())
        }
        assertEquals(p.f.http.read.createdClients, p.f.http.read.closedClients)
        probe.assertNoLostAssertions()
    }

    private fun assertOrderAndNoWrites(p: ProjectionActivationObservation, probe: CatalogSignerRotationProbeJdbc) {
        val order = listOf("test-registration-authenticate", "test-project-global-lock", "test-project-control-lock", "catalog", "test-projection-tail",
            "test-projection-history-lock", "counters", "counters", "test-project-run-lock", "test-project-resources-lock", "test-project-notices-lock", "test-project-audits-lock", "test-project-effect", "test-project-control-read")
        probe.calls.groupBy { it.phase }.forEach { (_, calls) ->
            assertTrue(calls.all { it.path === PersistencePhasePath.COMPLAINT_TEST_NAMESPACE_REGISTRATION })
            assertEquals(order, calls.map { it.step })
        }
        p.assertNoProjectDml(probe)
        assertTrue(probe.steps.none { "lease" in it || it.startsWith("charge:") })
    }

    private fun withRuntimeRoot(p: ProjectionActivationObservation, projector: VersionBoundPersistenceConnectedFixture,
        action: (VersionBoundPersistenceConnectedFixture, VersionBoundTestNamespaceProcessV1, CatalogSignerRotationProbeJdbc) -> Unit) {
        val runtime = VersionBoundPersistenceConnectedFixture(projector.database, endpointPort = projector.endpointPort,
            testIntake = p.f.rows.evidence.intakeAssembly, testRegistrationPredecessor = projector)
        var bodyFailure: Throwable? = null
        try {
            runtime.bind() // Existing CONTROLLED_TEST_ONLY fixture choice; production UNKNOWN is neither changed nor qualified.
            runtime.start()
            assertEquals(PersistenceLifecycleObservation.READY, runtime.pools.catalogCoordinator.prepare())
            val target = p.f.rows.evidence.processOn(runtime.pools)
            val coordinator = runtime.pools.catalogCoordinator
            val probe = CatalogSignerRotationProbeJdbc(coordinator, observeTestActivationQueries = true).apply { fetchSize = CatalogTestRunActivationHistoryV1.FETCH_ROWS }
            probe.beforeSql = {
                assertTrue(p.advisory(p.holder(runtime), "complaint-maintenance-v1", "ShareLock"))
                assertTrue(p.advisory(p.holder(runtime), "complaint-journal-epoch", "ShareLock"))
            }
            // Observer-only same JdbcTemplate recipe as existing activation cases; never substitutes SQL results, owner, proof or issuer.
            coordinator.testNamespaceRegistration.javaClass.getDeclaredField("jdbc").apply { check(trySetAccessible()) }.set(coordinator.testNamespaceRegistration, probe)
            action(runtime, target, probe)
            probe.assertNoLostAssertions()
        } catch (failure: Throwable) {
            bodyFailure = failure
            throw failure
        } finally {
            // The shared driver Timer/session assertion requires both real roots to stop before either fixture waits.
            try {
                runtime.closeWith(projector)
            } catch (cleanup: Throwable) {
                val original = bodyFailure
                if (original == null) throw cleanup
                if (cleanup !== original) original.addSuppressed(cleanup)
            }
        }
    }

    private fun withUnseededOrdinary(runtime: VersionBoundPersistenceConnectedFixture, action: (OrdinarySourceGrantCleanupFixture, ComplaintInstallationEnrollmentAudit) -> Unit) =
        withOrdinaryAudit(runtime) { ordinary, service ->
            action(ordinary, ComplaintInstallationEnrollmentAudit { scope, allocation, at -> service.recordInstallationEnrollment(scope, allocation, at) })
        }

    /** Existing real ordinary JPA/JDBC and counted audit adapter, also used to produce earlier authorized history. */
    internal fun withOrdinaryAudit(runtime: VersionBoundPersistenceConnectedFixture, action: (OrdinarySourceGrantCleanupFixture, AuditService) -> Unit) {
        val factory = ordinaryCleanupFactory(runtime.pools.ordinary, includeAuditEntities = true)
        try {
            factory.afterPropertiesSet()
            OrdinarySourceGrantCleanupFixture(OwnedCutPool(runtime.scope, runtime.pools.ordinary), checkNotNull(factory.`object`),
                ordinaryCleanupReader(runtime.database), maximumPoolSize = 2).use { ordinary ->
                val repository = JpaAuditRepositoryAdapter(JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(ordinary.entityManagerFactory))
                    .getRepository(SpringDataAuditLogRepository::class.java))
                val service = AuditService(repository, CurrentUser(), SignedActivationObservation.WALL_CLOCK)
                action(ordinary, service)
            }
        } finally { factory.destroy() }
    }

    private fun globalImage(p: ProjectionActivationObservation): String = checkNotNull(p.f.rows.observer.queryForObject(
        "SELECT jsonb_build_array(to_jsonb(c), c.xmin::text)::text FROM complaint_journal_control c WHERE data_scope_id = ?", String::class.java, ComplaintDataScope.LIVE.id))

    private fun request() = MockHttpServletRequest().apply { remoteAddr = "192.0.2.43" }
    private val credentials get() = S3CatalogReadbackFixture.credentials
}
