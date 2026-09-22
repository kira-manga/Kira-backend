package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintInstallationCurrentStateAssessment
import me.manga.kira.backend.complaint.domain.InstallationEnrollmentDisposition
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestInitialAdmissionV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationV1
import me.manga.kira.backend.complaint.infrastructure.admission.JdbcInstallationCurrentStateReader
import me.manga.kira.backend.complaint.infrastructure.admission.TestNamespaceActiveRegistrationSqlV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunSealingV1
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintInstallationCurrentStatePhaseExecutor
import me.manga.kira.backend.security.InstallationJwtCodec
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
import java.time.Clock
import java.io.IOException
import software.amazon.awssdk.http.SdkHttpClient

/** Focused source-authored actual fresh-root tests. No synthetic initial/open/latch issuer and no two-JVM claim. */
internal object ComplaintTestNamespaceActiveRegistrationCasesV1 {
    fun freshRootAdmitsIdentityAfterEnrollmentWithoutReprojecting(tls: VersionBoundPersistenceConnectedFixture) = withInitialAdmission(tls) { f ->
        f.release()
        val existing = f.candidate()
        val next = f.candidate()
        f.withExchange { exchange ->
            assertEquals(InstallationEnrollmentDisposition.CREATED, exchange.enroll(existing).disposition)
            exchange.assertReleased()
        }
        val previousD = f.registration.process.canonicalBytes()
        withColdActiveRoot(f) { cold ->
            assertTrue(previousD.contentEquals(cold.assembly.target.canonicalBytes()))
            val before = cold.image()
            val original = cold.begin()
            cold.register(original).use { registration ->
                cold.assertSuccessful(original, registration)
                assertEquals(before, cold.image(), "Admission must not re-PROJECT, recharge, reopen gates, reset a run, rotate writers or touch either lease/checkpoint.")
                assertEquals(20, registration.identityAdmissionArguments().size)
                val rawArguments = registration.activeReadbackArguments()
                assertEquals(6, rawArguments.size)
                val unsigned = (rawArguments[4] as ByteArray).copyOf()
                (rawArguments[4] as ByteArray).fill(0)
                assertTrue(unsigned.contentEquals(registration.activeReadbackArguments()[4] as ByteArray), "Detached facts cannot mutate the private origin.")
                assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { ComplaintTestInitialAdmissionV1.begin(registration, cold.assembly) }
                assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { TestRunSealingV1.begin(registration) }
                assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { registration.requireSealingOwner(cold.runtime.pools.catalogCoordinator.ownership) }
                assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { cold.begin() }
                assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { ComplaintTestNamespaceRegistrationV1.issuedByActiveRegistration(original) }
                cold.withExchange(registration) { exchange ->
                    val codec = InstallationJwtCodec(registration.process.consumers.jwt.installationKeyRing, Clock.systemUTC())
                    val enrolled = f.p.counters()
                    assertEquals(existing.installation, codec.verify(exchange.session(existing).accessToken).installation)
                    assertEquals(InstallationEnrollmentDisposition.EXACT_REPLAY, exchange.enroll(existing).disposition)
                    assertEquals(enrolled, f.p.counters(), "Fresh-root session/exact enrollment replay must not recharge any reserve.")
                    assertEquals(InstallationEnrollmentDisposition.CREATED, exchange.enroll(next).disposition)
                    assertEquals(next.installation, codec.verify(exchange.session(next).accessToken).installation)
                    assertEquals(2L, f.observer.queryForObject("SELECT enrolled_count FROM complaint_test_runs WHERE data_scope_id = ?", Long::class.java, f.p.scope))
                    exchange.assertReleased()
                    val calls = exchange.jdbc.calls.filter { it.first === PersistencePhasePath.COMPLAINT_INSTALLATION_ENROLLMENT }.map { it.second }
                    assertTrue(calls.indexOfFirst { "FROM complaint_capacity_counters" in it } < calls.indexOfFirst { "FROM complaint_test_runs r" in it && "FOR UPDATE" in it })
                    assertTrue(calls.indexOfFirst { "FROM complaint_test_runs r" in it && "FOR UPDATE" in it } < calls.indexOfFirst { "FROM complaint_installation_ids" in it })
                    assertEquals(ComplaintInstallationCurrentStateAssessment.PROVENANCE_REQUIRED,
                        ComplaintInstallationCurrentStatePhaseExecutor(exchange.ownership, JdbcInstallationCurrentStateReader(exchange.jdbc))
                            .assess(registration.process.desiredSettings(), existing.installation.scope))
                    assertEquals(true, f.observer.queryForObject("SELECT checkpoint_result IS NULL AND seal_epoch IS NULL AND scan_requested " +
                        "FROM complaint_journal_control WHERE data_scope_id = ?", Boolean::class.java, f.p.scope), "Identity is not a checkpoint/content capability.")
                    // The new origin still goes through the existing every-request current identity SQL.
                    assertEquals(1, f.foreignUpdate("UPDATE complaint_journal_control SET desired_generation = desired_generation + 1 WHERE data_scope_id = ?", f.p.scope))
                    try {
                        val changed = cold.image()
                        exchange.unavailable(existing)
                        assertEquals(changed, cold.image())
                    } finally {
                        assertEquals(1, f.foreignUpdate("UPDATE complaint_journal_control SET desired_generation = desired_generation - 1 WHERE data_scope_id = ?", f.p.scope))
                    }
                    exchange.assertReleased()
                    registration.close()
                    val count = exchange.jdbc.calls.size
                    exchange.unavailable(next)
                    assertEquals(count, exchange.jdbc.calls.size, "Closed origin cannot revive even a preconstructed real exchange.")
                }
            }
        }
    }

    fun changedColdFullDRefusesBeforeRawRead(tls: VersionBoundPersistenceConnectedFixture) = withInitialAdmission(tls) { f ->
        f.release()
        val previous = f.registration.process.canonicalBytes()
        withColdActiveRoot(f, changeDocument = { document ->
            document.copy(admission = document.admission.copy(ownerCreateGlobalPerHour = document.admission.ownerCreateGlobalPerHour + 1))
        }) { cold ->
            assertFalse(previous.contentEquals(cold.assembly.target.canonicalBytes()))
            val before = cold.image()
            val original = cold.begin()
            assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { cold.register(original) }
            original.requireActualCleanup()
            assertTrue(cold.raw.requests.isEmpty()); assertEquals(before, cold.image())
            assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { cold.begin() }
            assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { ComplaintTestNamespaceRegistrationV1.issuedByActiveRegistration(original) }
        }
    }

    fun currentControlChangedDuringRawReadCannotPublish(tls: VersionBoundPersistenceConnectedFixture) = withInitialAdmission(tls) { f ->
        f.release()
        withColdActiveRoot(f) { cold ->
            var afterExternalChange: Map<String, List<String>>? = null
            val scanRequested = checkNotNull(f.observer.queryForObject("SELECT scan_requested FROM complaint_journal_control WHERE data_scope_id = ?", Boolean::class.java, f.p.scope))
            cold.beforeRaw = {
                cold.beforeRaw = {}
                assertEquals(1, f.foreignUpdate("UPDATE complaint_journal_control SET scan_requested = NOT scan_requested WHERE data_scope_id = ?", f.p.scope))
                afterExternalChange = cold.image()
            }
            val original = cold.begin()
            try {
                assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { cold.register(original) }
                original.requireActualCleanup()
                assertNotNull(afterExternalChange)
                cold.probe.assertCommittedAndReleased()
                assertEquals(2, cold.probe.observations.size, "Both values independently admit identity, but an exact CAPTURE/RECHECK mismatch refuses this original.")
                assertEquals(afterExternalChange, cold.image())
                assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { ComplaintTestNamespaceRegistrationV1.issuedByActiveRegistration(original) }
            } finally {
                cold.beforeRaw = {}
                assertEquals(1, f.foreignUpdate("UPDATE complaint_journal_control SET scan_requested = ? WHERE data_scope_id = ?", scanRequested, f.p.scope))
            }
        }
    }

    fun currentAccountingMismatchIsNotRepaid(tls: VersionBoundPersistenceConnectedFixture) = withInitialAdmission(tls) { f ->
        f.release()
        val candidate = f.candidate()
        f.withExchange { assertEquals(InstallationEnrollmentDisposition.CREATED, it.enroll(candidate).disposition); it.assertReleased() }
        withColdActiveRoot(f) { cold ->
            // Negative corruption stimulus, not an accounting/spending producer. Legal vector, unattributed missing storage unit.
            assertEquals(1, f.observer.update("UPDATE complaint_test_runs SET unused_reserve[21] = unused_reserve[21] - 1 WHERE data_scope_id = ?", f.p.scope))
            val before = cold.image()
            val original = cold.begin()
            assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { cold.register(original) }
            original.requireActualCleanup()
            assertEquals(before, cold.image()); assertTrue(cold.raw.requests.isEmpty())
            assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { ComplaintTestNamespaceRegistrationV1.issuedByActiveRegistration(original) }
        }
    }

    fun retiredCapacityCannotActivateOrRepair(tls: VersionBoundPersistenceConnectedFixture) = withInitialAdmission(tls) { f ->
        f.release()
        withColdActiveRoot(f) { cold ->
            val retired = ComplaintCapacityCounter.IMPORT_ARTIFACTS
            val policy = cold.assembly.target.consumers.capacityPolicy
            assertEquals(0L, policy.hardLimit[retired])
            assertEquals(0L, policy.creationLimit[retired])
            // V14-equation-valid historical limits are representable, but cannot activate against zero-retired P.
            assertEquals(1, f.observer.update(
                "UPDATE complaint_capacity_counters SET hard_limit = 1, free_units = 1 WHERE name = ? " +
                    "AND hard_limit = 0 AND creation_limit = 0 AND free_units = 0 AND actual_units = 0 " +
                    "AND recovery_reserved_units = 0 AND test_reserved_units = 0", retired.storedName,
            ))
            val before = cold.image()
            val original = cold.begin()
            assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { cold.register(original) }
            original.requireActualCleanup()
            assertEquals(before, cold.image(), "Registration must not repair historical limits or mutate any counter/state.")
            assertTrue(cold.raw.requests.isEmpty())
            assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { ComplaintTestNamespaceRegistrationV1.issuedByActiveRegistration(original) }
        }
    }

    fun bothRawCopiesRemainRequired(tls: VersionBoundPersistenceConnectedFixture) = withInitialAdmission(tls) { f ->
        f.release()
        withColdActiveRoot(f) { cold ->
            val replica = f.p.f.http.replicaVersion
            val before = cold.image()
            val original = cold.begin()
            try {
                f.p.f.http.replicaVersion = null
                assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { cold.register(original) }
            } finally { f.p.f.http.replicaVersion = replica }
            original.requireActualCleanup(); cold.probe.assertCommittedAndReleased()
            assertEquals(1, cold.probe.observations.size, "No RECHECK after failed complete dual raw authentication.")
            assertTrue(cold.raw.requests.isNotEmpty()); assertEquals(before, cold.image())
            assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { ComplaintTestNamespaceRegistrationV1.issuedByActiveRegistration(original) }
        }
    }

    fun actualNativeCloseRefusalRetainsOriginalCustody(tls: VersionBoundPersistenceConnectedFixture) = withInitialAdmission(tls) { f ->
        f.release()
        withColdActiveRoot(f) { cold ->
            val before = cold.image()
            val original = cold.begin {
                val actual = cold.raw.httpClient()
                object : SdkHttpClient by actual {
                    override fun close() {
                        actual.close()
                        throw IOException("Synthetic original native-client close acknowledgment failure.")
                    }
                }
            }
            assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { cold.register(original) }
            cold.probe.assertCommittedAndReleased(); cold.assertRawClosed()
            assertEquals(1, cold.probe.observations.size, "No RECHECK/admission after missing native cleanup acknowledgment.")
            assertSame(original, SignedActivationObservation.active(cold.runtime.pools.catalogCoordinator))
            assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { original.requireActualCleanup() }
            assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { ComplaintTestNamespaceRegistrationV1.issuedByActiveRegistration(original) }
            assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { cold.begin() }
            assertEquals(before, cold.image())
        }
    }

    /** Final RECHECK only: targeted native COMMIT/known-COMMIT/JDBC-cleanup boundaries, not a duplicated whole phase matrix. */
    fun actualFinalCommitAndCleanupAreRequired(tls: VersionBoundPersistenceConnectedFixture, cut: TestRegistrationCompletionCut) = withInitialAdmission(tls) { f ->
        f.release()
        withColdActiveRoot(f) { cold ->
            val before = cold.image()
            val original = cold.begin()
            var selected: PersistencePhaseContext? = null
            val key = Any(); val sentinel = Any()
            var bound = false
            cold.probe.after = { call ->
                if (selected == null && cold.probe.observations.size == 2 && call.sql == TestNamespaceActiveRegistrationSqlV1.installationCounts) {
                    selected = call.phase
                    if (cut === TestRegistrationCompletionCut.DEFERRED_COMMIT_UNKNOWN) {
                        // Real deferred constraint fails at native COMMIT. This is not an afterCommit callback mislabeled UNKNOWN.
                        val actual = JdbcTemplate(cold.runtime.pools.catalogCoordinator.dataSource)
                        actual.execute("CREATE TEMP TABLE kira_active_registration_commit_cut (id int UNIQUE DEFERRABLE INITIALLY DEFERRED)")
                        assertEquals(2, actual.update("INSERT INTO kira_active_registration_commit_cut VALUES (1), (1)"))
                    } else TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                        override fun beforeCommit(readOnly: Boolean) {
                            if (cut === TestRegistrationCompletionCut.BEFORE_COMMIT) error("Synthetic ACTIVE registration beforeCommit refusal.")
                        }
                        override fun afterCommit() {
                            if (cut === TestRegistrationCompletionCut.UNRESOLVED_RELEASE) {
                                TransactionSynchronizationManager.bindResource(key, sentinel); bound = true
                            }
                            if (cut !== TestRegistrationCompletionCut.BEFORE_COMMIT) error("Synthetic ACTIVE registration known-COMMIT completion failure.")
                        }
                    })
                }
            }
            try {
                assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { cold.register(original) }
                if (cut === TestRegistrationCompletionCut.UNRESOLVED_RELEASE) {
                    assertTrue(bound); assertSame(selected, PersistencePhaseOwnership.current())
                    assertTrue(checkNotNull(selected).quarantined())
                    assertFalse(checkNotNull(selected).testActiveRegistrationCleanupProven(original))
                    assertSame(original, SignedActivationObservation.active(cold.runtime.pools.catalogCoordinator))
                }
            } finally {
                cold.probe.after = {}
                if (bound) assertSame(sentinel, TransactionSynchronizationManager.unbindResource(key))
                requireConnectionFree() // Later fixture disposal may settle the phase; it must not repair this original.
            }
            assertNotNull(selected)
            val expected = when (cut) {
                TestRegistrationCompletionCut.BEFORE_COMMIT -> PersistenceDatabaseOutcome.ROLLED_BACK
                TestRegistrationCompletionCut.DEFERRED_COMMIT_UNKNOWN -> PersistenceDatabaseOutcome.UNKNOWN
                else -> PersistenceDatabaseOutcome.COMMITTED
            }
            assertEquals(expected, checkNotNull(selected).databaseOutcome())
            assertEquals(2, cold.probe.observations.size); cold.assertRawClosed()
            if (cut in setOf(TestRegistrationCompletionCut.DEFERRED_COMMIT_UNKNOWN, TestRegistrationCompletionCut.UNRESOLVED_RELEASE)) {
                assertSame(original, SignedActivationObservation.active(cold.runtime.pools.catalogCoordinator))
                assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { original.requireActualCleanup() }
            } else {
                original.requireActualCleanup()
                assertNull(SignedActivationObservation.active(cold.runtime.pools.catalogCoordinator))
            }
            assertEquals(before, cold.image(), "Read-only admission changes no durable row even when COMMIT/cleanup fails.")
            assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { ComplaintTestNamespaceRegistrationV1.issuedByActiveRegistration(original) }
            assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { cold.begin() }
            assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { cold.register(original) }
            assertEquals(expected, checkNotNull(selected).databaseOutcome())
        }
    }
}
