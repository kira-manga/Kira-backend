package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.OwnedCallerTestScope
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCharges
import me.manga.kira.backend.complaint.domain.ComplaintCapacityEncoding
import me.manga.kira.backend.complaint.domain.ComplaintInstallationCurrentStateAssessment
import me.manga.kira.backend.complaint.domain.ComplaintInstallationHttpFailure
import me.manga.kira.backend.complaint.domain.ComplaintInstallationHttpRejected
import me.manga.kira.backend.complaint.domain.InstallationEnrollmentDisposition
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestInitialAdmissionV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestProcessAssemblyV1
import me.manga.kira.backend.complaint.infrastructure.admission.JdbcInstallationCurrentStateReader
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunSealingResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunSealingV1
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintInstallationCurrentStatePhaseExecutor
import me.manga.kira.backend.security.InstallationJwtCodec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.io.IOException
import java.io.InterruptedIOException
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CancellationException

internal enum class InitialAdmissionDriftCut { MISSING_REPLICA, CHANGED_RAW, FULL_D, GLOBAL_RESTORE, HISTORY, COUNTER_XMIN, ENROLLED, NOTICE, LEASE }
internal enum class InitialAdmissionProviderCut { DEADLINE, CANCELLATION, INTERRUPTED_IO, FATAL, CLOSE_RECEIPT }
internal enum class InitialAdmissionLockCut { CAPTURE_M, RELEASE_M, RELEASE_E }
internal enum class InitialAdmissionLifetimeCut { WRONG_ASSEMBLY, SPENT_CLOSED, REGISTRATION_CLOSE, ROOT_SHUTDOWN, SEALED }
internal enum class InitialIdentityDriftCut { SCOPED_D, GLOBAL_D, HEAD, BETWEEN_PREFLIGHT_AND_REFRESH, BEFORE_ENROLL_WRITE, CREDENTIAL_VERSION, SEALED_RUN }

/** Identity-only success and refusals on real protected/PROJECT/registration owners. No gate/credential success is seeded. */
internal object ComplaintTestInitialAdmissionCases {
    fun protectedRootReleasesOnlyIdentity(tls: VersionBoundPersistenceConnectedFixture) = withInitialAdmission(tls) { f ->
        f.withExchange { exchange ->
            val candidate = f.candidate()
            val controls = f.controls(preserved = true)
            val unchanged = f.nonControlImage()
            val rawClients = f.p.f.http.read.createdClients
            val original = f.begin()
            f.gates(open = false)
            f.assertNoLatch()
            exchange.unavailable(candidate)
            assertTrue(exchange.jdbc.calls.isEmpty(), "A preconstructed exchange cannot start identity SQL before the local release latch.")
            f.release(original)
            f.gates(open = true)
            assertEquals(controls, f.controls(preserved = true), "Only the two flags and server timestamps change, not either lease/D/head/epoch.")
            assertEquals(unchanged, f.nonControlImage(), "No counter/run/reserve/catalog/notice/audit xmin changes during initial release.")
            assertEquals(rawClients + 2, f.p.f.http.read.createdClients)
            assertOrder(f)
            val before = f.p.counters()
            val enrolled = exchange.enroll(candidate)
            assertEquals(InstallationEnrollmentDisposition.CREATED, enrolled.disposition)
            assertEquals(candidate.installation, enrolled.session.installation)
            val codec = InstallationJwtCodec(f.registration.process.consumers.jwt.installationKeyRing, Clock.systemUTC())
            val verified = codec.verify(enrolled.session.accessToken) // Never directly issue a token in this test.
            assertEquals(candidate.installation, verified.installation)
            assertEquals(1L, verified.credentialVersion)
            exchange.assertReleased()
            assertTrue(exchange.jdbc.observations.keys.all { it.databaseOutcome() === PersistenceDatabaseOutcome.COMMITTED })
            val share = ComplaintCapacityCharges.INSTALLATION_ID + ComplaintCapacityCharges.INSTALLATION_CREDENTIAL
            val after = f.p.counters()
            ComplaintCapacityEncoding.lockOrder().forEach { counter ->
                val old = before.getValue(counter.storedName)
                val current = after.getValue(counter.storedName)
                assertEquals(old.free - ComplaintCapacityCharges.AUDIT[counter], current.free)
                assertEquals(old.actual + share[counter] + ComplaintCapacityCharges.AUDIT[counter], current.actual)
                assertEquals(old.reserved - share[counter], current.reserved)
                assertEquals(old.recovery, current.recovery)
                assertEquals(current.hard, current.free + current.actual + current.reserved + current.recovery)
            }
            val originalReserve = f.p.run.accounting.originalUnusedReserve
            val expectedUnused = originalReserve.mapIndexed { index, value -> value - share.toLongArray()[index] }
            assertEquals(true, f.observer.queryForObject("SELECT enrolled_count = 1 AND original_reserve = ?::bigint[] AND unused_reserve = ?::bigint[] " +
                "FROM complaint_test_runs WHERE data_scope_id = ?", Boolean::class.java,
                originalReserve.joinToString(",", "{", "}"), expectedUnused.joinToString(",", "{", "}"), f.p.scope))
            assertEquals(1L, f.observer.queryForObject("SELECT count(*) FROM audit_log WHERE complaint_data_scope_id = ? AND action = 'COMPLAINT_INSTALLATION_ENROLLED'", Long::class.java, f.p.scope))
            val replay = exchange.enroll(candidate)
            assertEquals(InstallationEnrollmentDisposition.EXACT_REPLAY, replay.disposition)
            val session = exchange.session(candidate)
            assertEquals(candidate.installation, codec.verify(session.accessToken).installation)
            assertEquals(after, f.p.counters(), "Exact replay and session do not recharge the enrollment or terminal reserve.")
            exchange.assertReleased()
            assertEquals(setOf(PersistencePhasePath.COMPLAINT_INSTALLATION_ENROLLMENT,
                PersistencePhasePath.COMPLAINT_INSTALLATION_SESSION_PREFLIGHT, PersistencePhasePath.COMPLAINT_INSTALLATION_SESSION_REFRESH), exchange.jdbc.calls.map { it.first }.toSet())
            exchange.jdbc.calls.groupBy { it.first }.forEach { (_, calls) -> assertTrue(calls.any { "AS identity_current" in it.second }) }
            val enrollment = exchange.jdbc.calls.filter { it.first === PersistencePhasePath.COMPLAINT_INSTALLATION_ENROLLMENT }.map { it.second }
            assertTrue(enrollment.indexOfFirst { "FROM complaint_capacity_counters" in it } < enrollment.indexOfFirst { "FROM complaint_test_runs r" in it && "FOR UPDATE" in it })
            assertTrue(enrollment.indexOfFirst { "FROM complaint_test_runs r" in it && "FOR UPDATE" in it } < enrollment.indexOfFirst { "FROM complaint_installation_ids" in it })
            assertEquals(ComplaintInstallationCurrentStateAssessment.PROVENANCE_REQUIRED,
                ComplaintInstallationCurrentStatePhaseExecutor(exchange.ordinary.ownership, JdbcInstallationCurrentStateReader(exchange.jdbc))
                    .assess(f.registration.process.desiredSettings(), candidate.installation.scope))
            assertEquals(true, f.observer.queryForObject("SELECT publication_epoch = 1 AND scan_requested AND checkpoint_result IS NULL " +
                "AND checkpoint_completed_at IS NULL AND seal_epoch IS NULL FROM complaint_journal_control WHERE data_scope_id = ?", Boolean::class.java, f.p.scope),
                "Identity release is not first reconciliation, a checkpoint, content/Admin admission or a seal.")
            assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { f.begin() }
            assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { original.release(credentials, credentials) }
            assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { f.registration.publishInitialAdmission(original) }
            val admitted = exchange.identityImage()
            f.registration.close()
            exchange.unavailable(candidate)
            assertEquals(admitted, exchange.identityImage(), "Closing the retained registration revokes an already-built exchange.")
        }
    }

    fun preconstructedExchangeWaitsForActualCleanup(tls: VersionBoundPersistenceConnectedFixture) = withInitialAdmission(tls) { f ->
        f.withExchange { exchange ->
            val candidate = f.candidate()
            var witnessed = false
            OwnedCallerTestScope().use { callers ->
                f.probe.afterSql = { step -> if (step == "test-initial-admission-release") {
                    val phase = checkNotNull(PersistencePhaseOwnership.current())
                    val observed = f.probe.observations.getValue(phase)
                    TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                        override fun afterCommit() {
                            assertSame(phase, PersistencePhaseOwnership.current())
                            assertFalse(observed.lease.completion.quiescent())
                            // Another caller: same-thread refusal would merely prove connection-free ingress, not the latch.
                            val attempt = callers.launch {
                                requireConnectionFree()
                                f.gates(open = true)
                                f.assertNoLatch()
                                exchange.unavailable(candidate)
                                assertTrue(exchange.jdbc.calls.isEmpty())
                                assertTrue(exchange.identityImage().values.all { it.isEmpty() })
                                requireConnectionFree()
                                true
                            }
                            attempt.thread.join(1_000)
                            assertFalse(attempt.thread.isAlive, "The bounded latch witness must return before RELEASE's original short budget.")
                            witnessed = attempt.value()
                        }
                    })
                } }
                try { f.release() } finally { f.probe.afterSql = {} }
            }
            assertTrue(witnessed)
            assertEquals(InstallationEnrollmentDisposition.CREATED, exchange.enroll(candidate).disposition)
            exchange.assertReleased()
        }
    }

    fun completionFailureCannotOpenLocalAdmission(tls: VersionBoundPersistenceConnectedFixture, cut: TestRegistrationCompletionCut) = withInitialAdmission(tls) { f ->
        f.withExchange { exchange ->
            val candidate = f.candidate()
            val unchanged = f.nonControlImage()
            val controls = f.controls()
            val original = f.begin()
            val resource = Any(); val sentinel = Any()
            var bound = false
            var selected: PersistencePhaseContext? = null
            f.probe.afterSql = { step -> if (step == "test-initial-admission-release") {
                selected = checkNotNull(PersistencePhaseOwnership.current())
                if (cut === TestRegistrationCompletionCut.DEFERRED_COMMIT_UNKNOWN) {
                    val jdbc = JdbcTemplate(f.runtime.pools.catalogCoordinator.dataSource)
                    jdbc.execute("CREATE TEMP TABLE kira_initial_admission_commit_cut (id int UNIQUE DEFERRABLE INITIALLY DEFERRED)")
                    assertEquals(2, jdbc.update("INSERT INTO kira_initial_admission_commit_cut VALUES (1), (1)"))
                } else TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun beforeCommit(readOnly: Boolean) {
                        if (cut === TestRegistrationCompletionCut.BEFORE_COMMIT) throw IllegalStateException("Synthetic initial release beforeCommit cut.")
                    }
                    override fun afterCommit() {
                        if (cut === TestRegistrationCompletionCut.UNRESOLVED_RELEASE) {
                            TransactionSynchronizationManager.bindResource(resource, sentinel); bound = true
                        }
                        if (cut !== TestRegistrationCompletionCut.BEFORE_COMMIT) throw IOException("Synthetic initial release completion receipt failure.")
                    }
                })
            } }
            try {
                assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { original.release(credentials, credentials) }
                if (cut === TestRegistrationCompletionCut.UNRESOLVED_RELEASE) {
                    assertTrue(bound); assertSame(selected, PersistencePhaseOwnership.current()); assertTrue(checkNotNull(selected).quarantined())
                }
            } finally {
                f.probe.afterSql = {}
                if (bound) assertSame(sentinel, TransactionSynchronizationManager.unbindResource(resource))
                requireConnectionFree() // Eventual physical retirement cannot repair the failed original/latch.
            }
            val outcome = when (cut) {
                TestRegistrationCompletionCut.BEFORE_COMMIT -> PersistenceDatabaseOutcome.ROLLED_BACK
                TestRegistrationCompletionCut.DEFERRED_COMMIT_UNKNOWN -> PersistenceDatabaseOutcome.UNKNOWN
                else -> PersistenceDatabaseOutcome.COMMITTED
            }
            assertEquals(outcome, checkNotNull(selected).databaseOutcome())
            f.gates(open = outcome === PersistenceDatabaseOutcome.COMMITTED)
            if (outcome !== PersistenceDatabaseOutcome.COMMITTED) assertEquals(controls, f.controls())
            assertEquals(unchanged, f.nonControlImage())
            f.assertNoLatch(); exchange.unavailable(candidate); assertTrue(exchange.jdbc.calls.isEmpty())
            assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { f.begin() }
            assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { original.release(credentials, credentials) }
            assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { f.registration.publishInitialAdmission(original) }
            if (cut in setOf(TestRegistrationCompletionCut.DEFERRED_COMMIT_UNKNOWN, TestRegistrationCompletionCut.UNRESOLVED_RELEASE)) {
                assertSame(original, SignedActivationObservation.active(f.runtime.pools.catalogCoordinator))
            } else { original.requireActualCleanup(); assertNull(SignedActivationObservation.active(f.runtime.pools.catalogCoordinator)) }
        }
    }

    fun rawIdentityAndPhysicalEffectDriftRefuse(tls: VersionBoundPersistenceConnectedFixture, cut: InitialAdmissionDriftCut) = withInitialAdmission(tls) { f ->
        val original = f.begin()
        var injected = false
        var negative = f.p.image(); var controls = f.controls()
        val boundary = f.p.f.http.beforeRead
        f.p.f.http.beforeRead = {
            boundary()
            if (!injected) {
                when (cut) {
                    InitialAdmissionDriftCut.MISSING_REPLICA -> f.p.f.http.replicaVersion = null
                    InitialAdmissionDriftCut.CHANGED_RAW -> f.p.f.http.replicaBytes = f.p.f.http.replicaBytes.copyOf().also { it[it.lastIndex] = ' '.code.toByte() }
                    InitialAdmissionDriftCut.FULL_D -> assertEquals(1, f.foreignUpdate("UPDATE complaint_journal_control SET desired_configuration_hash = decode(repeat('dc', 32), 'hex') WHERE data_scope_id = ?", f.p.scope))
                    InitialAdmissionDriftCut.GLOBAL_RESTORE -> assertEquals(1, f.foreignUpdate("UPDATE complaint_journal_control SET restore_identity = ? WHERE data_scope_id = ?", UUID.randomUUID(), UUID(0L, 0L)))
                    InitialAdmissionDriftCut.HISTORY -> assertEquals(1, f.foreignUpdate("UPDATE complaint_catalog_mutations SET retain_until = retain_until + interval '1 microsecond' WHERE successor_generation = 1"))
                    InitialAdmissionDriftCut.COUNTER_XMIN -> assertEquals(1, f.foreignUpdate("UPDATE complaint_capacity_counters SET actual_units = actual_units WHERE name = 'test_runs'"))
                    InitialAdmissionDriftCut.ENROLLED -> assertEquals(1, f.foreignUpdate("UPDATE complaint_test_runs SET enrolled_count = 1 WHERE data_scope_id = ?", f.p.scope))
                    InitialAdmissionDriftCut.NOTICE -> assertEquals(2, f.foreignUpdate("UPDATE complaints SET version = version + 1 WHERE data_scope_id = ?", f.p.scope))
                    InitialAdmissionDriftCut.LEASE -> assertEquals(1, f.foreignUpdate("UPDATE complaint_journal_control SET lease_token = lease_token + 1 WHERE data_scope_id = ?", UUID(0L, 0L)))
                }
                negative = f.p.image(); controls = f.controls(); injected = true
            }
        }
        try { assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { original.release(credentials, credentials) } }
        finally { f.p.f.http.beforeRead = boundary }
        assertTrue(injected)
        original.requireActualCleanup()
        f.assertNoLatch(); f.gates(open = false)
        assertEquals(negative, f.p.image()); assertEquals(controls, f.controls())
        assertTrue(f.probe.steps.none { it == "test-initial-admission-release" })
        f.p.assertNoProjectDml(f.probe)
        assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { f.begin() }
    }

    fun providerDeadlineAndSignalsAreSticky(tls: VersionBoundPersistenceConnectedFixture, cut: InitialAdmissionProviderCut) = withInitialAdmission(tls) { f ->
        val before = f.p.image(); val controls = f.controls()
        val original = f.begin(); val budget = original.budget
        val fatal = InitialAdmissionFatal()
        var injected = false
        val boundary = f.p.f.http.beforeRead
        f.p.f.http.beforeRead = {
            boundary()
            if (!injected && cut !== InitialAdmissionProviderCut.CLOSE_RECEIPT) {
                injected = true
                when (cut) {
                    InitialAdmissionProviderCut.DEADLINE -> f.native.offsetNanos += (f.registration.process.catalogReadback.totalAttemptMillis + 1_000) * 1_000_000
                    InitialAdmissionProviderCut.CANCELLATION -> throw CancellationException("Synthetic initial release cancellation.")
                    InitialAdmissionProviderCut.INTERRUPTED_IO -> throw InterruptedIOException("Synthetic initial release interrupted I/O.")
                    InitialAdmissionProviderCut.FATAL -> throw fatal
                    else -> error("Unexpected provider cut.")
                }
            }
        }
        f.p.f.http.afterReadClientClose = {
            if (!injected && cut === InitialAdmissionProviderCut.CLOSE_RECEIPT) {
                injected = true; throw IOException("Synthetic physical raw client close without return receipt.")
            }
        }
        try {
            when (cut) {
                InitialAdmissionProviderCut.CANCELLATION -> assertThrows<CancellationException> { original.release(credentials, credentials) }
                InitialAdmissionProviderCut.INTERRUPTED_IO -> { assertThrows<InterruptedException> { original.release(credentials, credentials) }; assertTrue(Thread.currentThread().isInterrupted) }
                InitialAdmissionProviderCut.FATAL -> assertSame(fatal, assertThrows<InitialAdmissionFatal> { original.release(credentials, credentials) })
                else -> assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { original.release(credentials, credentials) }
            }
        } finally {
            f.p.f.http.beforeRead = boundary; f.p.f.http.afterReadClientClose = {}; f.native.offsetNanos = 0; Thread.interrupted()
        }
        assertTrue(injected); assertSame(budget, original.budget)
        f.assertNoLatch(); f.gates(open = false)
        assertEquals(before, f.p.image()); assertEquals(controls, f.controls())
        assertEquals(1, f.probe.observations.size)
        assertSame(original, SignedActivationObservation.active(f.runtime.pools.catalogCoordinator))
        assertFalse(poolTestField<Boolean>(original, "cleanupProven"))
        assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { f.begin() }
        val reads = f.p.f.http.read.requests.size
        assertTrue(runCatching { original.release(credentials, credentials) }.isFailure)
        Thread.interrupted()
        assertEquals(reads, f.p.f.http.read.requests.size)
    }

    fun fencesRefuseWithoutLockUpgrade(tls: VersionBoundPersistenceConnectedFixture, cut: InitialAdmissionLockCut) = withInitialAdmission(tls) { f ->
        val before = f.p.image(); val controls = f.controls()
        checkNotNull(f.observer.dataSource).connection.use { blocker ->
            blocker.autoCommit = false
            fun lock() = blocker.createStatement().use { statement ->
                statement.queryTimeout = 1
                statement.executeQuery(when (cut) {
                    InitialAdmissionLockCut.CAPTURE_M -> "SELECT pg_advisory_xact_lock(hashtextextended('complaint-maintenance-v1', 0))"
                    InitialAdmissionLockCut.RELEASE_M -> "SELECT pg_advisory_xact_lock_shared(hashtextextended('complaint-maintenance-v1', 0))"
                    InitialAdmissionLockCut.RELEASE_E -> "SELECT pg_advisory_xact_lock(hashtextextended('complaint-journal-epoch', 0))"
                }).use { assertTrue(it.next() && !it.next()) }
            }
            var acquired = false
            if (cut === InitialAdmissionLockCut.CAPTURE_M) { lock(); acquired = true }
            val boundary = f.p.f.http.beforeRead
            f.p.f.http.beforeRead = { boundary(); if (!acquired) { lock(); acquired = true } }
            val original = f.begin()
            try { assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { original.release(credentials, credentials) } }
            finally { f.p.f.http.beforeRead = boundary; blocker.rollback() }
            assertTrue(acquired)
            original.requireActualCleanup()
            f.assertNoLatch(); f.gates(open = false)
            assertEquals(before, f.p.image()); assertEquals(controls, f.controls())
            assertEquals(if (cut === InitialAdmissionLockCut.CAPTURE_M) 0 else 1, f.probe.observations.size,
                "Fence refusal occurs before the new original can dispatch authentication/business SQL.")
            assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { f.begin() }
        }
    }

    fun wrongRootSpentClosedAndTerminalRefuse(tls: VersionBoundPersistenceConnectedFixture, cut: InitialAdmissionLifetimeCut) = withInitialAdmission(tls) { f ->
        val reads = f.p.f.http.read.requests.size
        when (cut) {
            InitialAdmissionLifetimeCut.WRONG_ASSEMBLY -> ComplaintTestProcessAssemblyV1.begin().use { wrong ->
                assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { ComplaintTestInitialAdmissionV1.begin(f.registration, wrong) }
            }
            InitialAdmissionLifetimeCut.SPENT_CLOSED -> { val closed = f.begin(); closed.close(); assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { closed.release(credentials, credentials) } }
            InitialAdmissionLifetimeCut.REGISTRATION_CLOSE -> f.registration.close()
            InitialAdmissionLifetimeCut.ROOT_SHUTDOWN -> f.runtime.owner.requestShutdown()
            InitialAdmissionLifetimeCut.SEALED -> {
                assertEquals(TestRunSealingResultV1.SEALED_AND_AUDITED, TestRunSealingV1.begin(f.registration).seal())
                f.probe.resetObservations()
                val sealed = f.p.image()
                val original = f.begin()
                assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { original.release(credentials, credentials) }
                original.requireActualCleanup()
                assertEquals(sealed, f.p.image(), "A genuine one-way run barrier is never reopened by initial admission.")
            }
        }
        f.assertNoLatch()
        assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { f.begin() }
        assertEquals(reads, f.p.f.http.read.requests.size)
        assertTrue(f.probe.steps.none { it == "test-initial-admission-release" })
        f.gates(open = false)
    }

    fun everyRealIdentityPhaseRechecksCurrentRows(tls: VersionBoundPersistenceConnectedFixture, cut: InitialIdentityDriftCut) = withInitialAdmission(tls) { f ->
        f.withExchange { exchange ->
            f.release()
            val candidate = f.candidate()
            val fresh = f.candidate()
            val token = exchange.enroll(candidate).session.accessToken
            val before = exchange.identityImage(); val counters = f.p.counters()
            var injected = false
            var driftedIdentities = before
            fun drift() {
                when (cut) {
                    InitialIdentityDriftCut.SCOPED_D, InitialIdentityDriftCut.BEFORE_ENROLL_WRITE -> assertEquals(1, f.foreignUpdate("UPDATE complaint_journal_control SET desired_configuration_hash = decode(repeat('dc', 32), 'hex') WHERE data_scope_id = ?", f.p.scope))
                    InitialIdentityDriftCut.GLOBAL_D, InitialIdentityDriftCut.BETWEEN_PREFLIGHT_AND_REFRESH -> assertEquals(1, f.foreignUpdate("UPDATE complaint_journal_control SET desired_generation = desired_generation + 1 WHERE data_scope_id = ?", UUID(0L, 0L)))
                    InitialIdentityDriftCut.HEAD -> assertEquals(1, f.foreignUpdate("UPDATE complaint_journal_control SET accepted_catalog_hash = decode(repeat('ab', 32), 'hex') WHERE data_scope_id = ?", f.p.scope))
                    InitialIdentityDriftCut.CREDENTIAL_VERSION -> assertEquals(1, f.foreignUpdate("UPDATE app_installations SET credential_version = credential_version + 1, version = version + 1 WHERE id = ?", candidate.installation.id))
                    InitialIdentityDriftCut.SEALED_RUN -> assertEquals(1, f.foreignUpdate("UPDATE complaint_test_runs SET state = 'SEALED', sealed_at = clock_timestamp() WHERE data_scope_id = ?", f.p.scope))
                }
                injected = true
                driftedIdentities = exchange.identityImage()
            }
            if (cut in setOf(InitialIdentityDriftCut.BETWEEN_PREFLIGHT_AND_REFRESH, InitialIdentityDriftCut.CREDENTIAL_VERSION, InitialIdentityDriftCut.SEALED_RUN)) {
                exchange.jdbc.after = { path, sql -> if (!injected && path === PersistencePhasePath.COMPLAINT_INSTALLATION_SESSION_PREFLIGHT && "AS identity_current" in sql) {
                    TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization { override fun afterCommit() { drift() } })
                } }
            } else if (cut === InitialIdentityDriftCut.BEFORE_ENROLL_WRITE) {
                exchange.jdbc.after = { path, sql -> if (!injected && path === PersistencePhasePath.COMPLAINT_INSTALLATION_ENROLLMENT && "AS identity_current" in sql) drift() }
            } else drift()
            try {
                val failure = assertThrows<ComplaintInstallationHttpRejected> {
                    if (cut === InitialIdentityDriftCut.BEFORE_ENROLL_WRITE) exchange.enroll(fresh) else exchange.session(candidate)
                }.failure
                assertEquals(when (cut) {
                    InitialIdentityDriftCut.CREDENTIAL_VERSION -> ComplaintInstallationHttpFailure.INSTALLATION_CREDENTIAL_REJECTED
                    InitialIdentityDriftCut.SEALED_RUN -> ComplaintInstallationHttpFailure.INSTALLATION_SCOPE_RETIRED
                    else -> ComplaintInstallationHttpFailure.UNAVAILABLE
                }, failure)
            } finally { exchange.jdbc.after = { _, _ -> } }
            assertTrue(injected)
            exchange.assertReleased()
            assertEquals(counters, f.p.counters(), "Refusal cannot charge, recycle or repair the original reserve.")
            assertEquals(driftedIdentities, exchange.identityImage(), "Neither successful refresh nor new identity SQL may follow the observed drift.")
            if (cut === InitialIdentityDriftCut.CREDENTIAL_VERSION) assertNotEquals(before, exchange.identityImage())
            assertEquals(candidate.installation, InstallationJwtCodec(f.registration.process.consumers.jwt.installationKeyRing, Clock.systemUTC()).verify(token).installation,
                "Still-valid JWT bytes do not bypass current SQL/credential/run checks.")
            if (cut !in setOf(InitialIdentityDriftCut.CREDENTIAL_VERSION, InitialIdentityDriftCut.SEALED_RUN)) {
                exchange.unavailable(candidate)
                assertEquals(ComplaintInstallationHttpFailure.UNAVAILABLE, assertThrows<ComplaintInstallationHttpRejected> { exchange.enroll(fresh) }.failure)
            }
            if (cut === InitialIdentityDriftCut.BETWEEN_PREFLIGHT_AND_REFRESH) assertTrue(exchange.jdbc.calls.any {
                it.first === PersistencePhasePath.COMPLAINT_INSTALLATION_SESSION_REFRESH && "AS identity_current" in it.second
            })
            f.gates(open = true)
            f.assertSqlReleased()
        }
    }

    private fun assertOrder(f: InitialAdmissionFixture) {
        val order = listOf("test-initial-admission-authenticate", "test-project-global-lock", "test-project-control-lock", "test-initial-admission-controls",
            "catalog", "test-projection-history-lock", "counters", "counters", "test-project-run-lock", "test-project-resources-lock",
            "test-project-notices-lock", "test-project-audits-lock", "test-project-effect", "test-project-control-read", "test-initial-admission-controls")
        f.probe.calls.groupBy { it.phase }.forEach { (_, calls) ->
            assertEquals(order + if (calls.first().path === INITIAL_RELEASE) listOf("test-initial-admission-release") else emptyList(), calls.map { it.step })
        }
        f.p.assertNoProjectDml(f.probe)
        assertTrue(f.probe.steps.none { "lease" in it && it != "test-initial-admission-release" })
    }

    private class InitialAdmissionFatal : Error("Synthetic original provider fatal signal.")
    private val credentials get() = S3CatalogReadbackFixture.credentials
}
