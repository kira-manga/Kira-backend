package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintInstallationSession
import me.manga.kira.backend.complaint.domain.InstallationSessionCandidate
import me.manga.kira.backend.complaint.domain.InstallationSessionPreflight
import me.manga.kira.backend.complaint.domain.InstallationSessionRejection
import me.manga.kira.backend.complaint.domain.SessionPreflightResult
import me.manga.kira.backend.complaint.domain.SessionRefreshResult
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintSessionAdmissionCoordinator
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintSessionAdmissionResult
import me.manga.kira.backend.complaint.infrastructure.transaction.DeletionPersistenceAdmission
import me.manga.kira.backend.config.KiraSecurityProperties
import me.manga.kira.backend.security.ClientIpResolver
import me.manga.kira.backend.security.ComplaintAdmissionFailure
import me.manga.kira.backend.security.ComplaintAdmissionNanoClock
import me.manga.kira.backend.security.ComplaintAdmittedSessionRefresh
import me.manga.kira.backend.security.ComplaintIngressAdmission
import me.manga.kira.backend.security.ComplaintIngressContext
import me.manga.kira.backend.security.SystemComplaintAdmissionNanoClock
import me.manga.kira.backend.security.admissionTestKeys
import me.manga.kira.backend.security.admissionTestPolicy
import me.manga.kira.backend.security.admissionTestRefused
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.mock.web.MockHttpServletRequest
import java.util.UUID

/** Real-PG composition on the accepted fixture; no HTTP, JWT, mode authority or injected phase clock. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class ComplaintSessionAdmissionIT {
    private val database = lazy { PgLifecycleDatabaseFixture(ComplaintSessionAdmissionIT::class.java).also { it.start() } }

    @AfterAll
    fun closeDatabase() {
        if (database.isInitialized()) database.value.close()
    }

    @Test
    fun `real session preflight releases before scoped admission and the coordinator owns genuine one-use refresh`() {
        withOrdinaryComplaintInstallationEnrollment(database.value) { f ->
            val enrollment = f.candidate()
            val enrolled = f.execute(enrollment)
            val candidate = f.sessionCandidate(enrollment.installation.id)
            // Two physical events permit exactly one actor+IP admission, not a synthetic authentication quota.
            val ingress = ComplaintIngressAdmission(
                ClientIpResolver(KiraSecurityProperties()),
                admissionTestPolicy(semanticEvents = 2),
                admissionTestKeys(),
                SystemComplaintAdmissionNanoClock,
            )
            val coordinator = ComplaintSessionAdmissionCoordinator(ingress, f.sessionExecutor())
            f.jdbc.queries.clear()
            f.jdbc.updates.clear()
            f.observations.clear()
            genuineAdmissionAndRefresh(f, candidate, ingress, coordinator, enrolled.credentialVersion)
            refusedRequestsCannotWriteActivity(f, candidate, coordinator)
            fabricatedCoreResultCannotAdmit(f, candidate, ingress)
            retainedPersistenceCannotAdmit(f, candidate, coordinator)
            f.assertReleased()
        }
    }

    @Test
    fun `custom clock remains a connection-free counter seam and cannot enter admitted database refresh`() {
        withOrdinaryComplaintInstallationEnrollment(database.value) { f ->
            val enrollment = f.candidate()
            f.execute(enrollment)
            val candidate = f.sessionCandidate(enrollment.installation.id)
            var reads = 0
            val ingress = ComplaintIngressAdmission(
                ClientIpResolver(KiraSecurityProperties()),
                admissionTestPolicy(),
                admissionTestKeys(),
                ComplaintAdmissionNanoClock {
                    requireConnectionFree()
                    reads += 1
                    0L
                },
            )
            val coordinator = ComplaintSessionAdmissionCoordinator(ingress, f.sessionExecutor())
            val before = f.state()
            f.jdbc.queries.clear()
            f.jdbc.updates.clear()
            coordinator.withIngress(request()) { context ->
                val beforePreflight = reads
                f.afterStep = { step ->
                    if (step === EnrollmentFixtureStep.SESSION_SNAPSHOT) assertEquals(beforePreflight, reads)
                }
                val admitted = try {
                    coordinator.admitSession(context, candidate) as ComplaintSessionAdmissionResult.Admitted
                } finally {
                    f.afterStep = {}
                }
                assertTrue(reads > beforePreflight)
                val beforeRefresh = reads
                admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { coordinator.refresh(context, admitted) }
                assertEquals(beforeRefresh, reads)
            }
            f.assertReleased()
            assertEquals(listOf(EnrollmentFixtureStep.SESSION_SNAPSHOT), f.jdbc.queries)
            assertTrue(f.jdbc.updates.isEmpty())
            assertEquals(before, f.state())
        }
    }

    @Test
    fun `opaque refresh rejects forged scope substituted genuine continuation and repeat or stale handoffs`() {
        withOrdinaryComplaintInstallationEnrollment(database.value) { f ->
            val enrollment = f.candidate()
            f.execute(enrollment)
            val candidate = f.sessionCandidate(enrollment.installation.id)
            val first = (f.preflight(candidate) as SessionPreflightResult.Ready).continuation
            val substitute = (f.preflight(candidate) as SessionPreflightResult.Ready).continuation
            val phases = f.sessionExecutor()
            val ingress = systemIngress()
            val before = f.state()
            f.jdbc.queries.clear()
            f.jdbc.updates.clear()
            val forged = object : ComplaintAdmittedSessionRefresh {}
            refusedPhase(f, PersistenceDatabaseOutcome.NONE) { phases.refreshAdmitted(first, forged) }
            // Lower handoff controls still use real released SQL continuations; these calls alone are not authentication evidence.
            ingress.withIngress(request()) { context ->
                ingress.startSession(context)
                val identity = Any()
                ingress.chargeSession(context, first.installation, identity)
                val foreignScope = object : InstallationSessionPreflight {
                    override val installation = first.installation.copy(scope = ComplaintDataScope.of(UUID.randomUUID()))
                    override val credentialVersion = first.credentialVersion
                }
                admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ingress.prepareSessionRefresh(context, identity, foreignScope) }
                val handoff = ingress.prepareSessionRefresh(context, identity, first)
                admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ingress.prepareSessionRefresh(context, identity, first) }
                // Same actor/version is insufficient: the bound phase must consume the exact genuine continuation.
                refusedPhase(f, PersistenceDatabaseOutcome.ROLLED_BACK) { phases.refreshAdmitted(substitute, handoff) }
                refusedPhase(f, PersistenceDatabaseOutcome.NONE) { phases.refreshAdmitted(first, handoff) }
            }
            val stale = ingress.withIngress(request()) { context ->
                ingress.startSession(context)
                val identity = Any()
                ingress.chargeSession(context, first.installation, identity)
                ingress.prepareSessionRefresh(context, identity, first)
            }
            refusedPhase(f, PersistenceDatabaseOutcome.NONE) { phases.refreshAdmitted(first, stale) }
            assertTrue(f.jdbc.queries.isEmpty())
            assertTrue(f.jdbc.updates.isEmpty())
            assertEquals(before, f.state())
        }
    }

    @Test
    fun `admitted genuine refresh releases a normal locked rejection without requiring an activity write`() {
        withOrdinaryComplaintInstallationEnrollment(database.value) { f ->
            val enrollment = f.candidate()
            f.execute(enrollment)
            val candidate = f.sessionCandidate(enrollment.installation.id)
            val coordinator = ComplaintSessionAdmissionCoordinator(systemIngress(), f.sessionExecutor())
            coordinator.withIngress(request()) { context ->
                val admitted = coordinator.admitSession(context, candidate) as ComplaintSessionAdmissionResult.Admitted
                assertEquals(
                    1,
                    f.observer.update("UPDATE complaint_installation_ids SET state = 'DELETION_PENDING' WHERE id = ?", candidate.installation.id),
                )
                assertEquals(
                    1,
                    f.observer.update("UPDATE app_installations SET state = 'DELETION_PENDING' WHERE id = ?", candidate.installation.id),
                )
                val before = f.state()
                f.jdbc.queries.clear()
                f.jdbc.updates.clear()
                val result = coordinator.refresh(context, admitted) as SessionRefreshResult.Rejected
                assertEquals(InstallationSessionRejection.INSTALLATION_DELETION_PENDING, result.reason)
                f.assertReleased()
                assertEquals(listOf(EnrollmentFixtureStep.SESSION_IDENTITY, EnrollmentFixtureStep.SESSION_CREDENTIAL), f.jdbc.queries)
                assertTrue(f.jdbc.updates.isEmpty())
                assertEquals(before, f.state())
            }
        }
    }

    @Test
    fun `real elapsed five seconds are enforced after phase begin before identity locking`() {
        deadlineCrossedDuringRefresh(afterLocks = false)
    }

    @Test
    fun `real elapsed five seconds are rechecked after locked rows and before activity SQL`() {
        deadlineCrossedDuringRefresh(afterLocks = true)
    }

    private fun deadlineCrossedDuringRefresh(afterLocks: Boolean) {
        withOrdinaryComplaintInstallationEnrollment(database.value) { f ->
            val enrollment = f.candidate()
            f.execute(enrollment)
            val candidate = f.sessionCandidate(enrollment.installation.id)
            val before = f.state()
            var beforeAdmission = 0L
            var afterAdmission = 0L
            var crossed = false

            fun crossDeadline() = f.preserveAssertions {
                assertTrue(checkNotNull(PersistencePhaseOwnership.current()).isOriginalCaller())
                assertTrue(System.nanoTime() - beforeAdmission < 4_950_000_000L, "Scheduling exceeded the bounded pre-wait interval.")
                // Below the existing one-second idle and two-second phase budgets. This is not a PG lock-contention claim.
                Thread.sleep(650)
                assertTrue(System.nanoTime() - afterAdmission >= 5_000_000_000L, "The original admission must really have expired.")
                crossed = true
            }

            val port = object : ComplaintInstallationSession by f.sessionStore {
                override fun refresh(preflight: InstallationSessionPreflight): SessionRefreshResult {
                    if (!afterLocks) crossDeadline()
                    return f.sessionStore.refresh(preflight)
                }
            }
            val coordinator = ComplaintSessionAdmissionCoordinator(systemIngress(semanticEvents = 2), f.sessionExecutor(port))
            coordinator.withIngress(request()) { context ->
                // These timestamps bracket the original semantic charge; neither is a production clock override.
                beforeAdmission = System.nanoTime()
                val admitted = coordinator.admitSession(context, candidate) as ComplaintSessionAdmissionResult.Admitted
                afterAdmission = System.nanoTime()
                f.assertReleased()
                f.jdbc.queries.clear()
                f.jdbc.updates.clear()
                Thread.sleep(4400)
                assertTrue(System.nanoTime() - beforeAdmission < 4_900_000_000L, "Admission expired before the intended phase-boundary probe.")
                f.afterStep = { step -> if (afterLocks && step === EnrollmentFixtureStep.SESSION_CREDENTIAL) crossDeadline() }
                try {
                    refusedPhase(f, PersistenceDatabaseOutcome.ROLLED_BACK) { coordinator.refresh(context, admitted) }
                } finally {
                    f.afterStep = {}
                }
                assertTrue(crossed)
                admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { coordinator.refresh(context, admitted) }
                val expected = if (afterLocks) {
                    listOf(EnrollmentFixtureStep.SESSION_IDENTITY, EnrollmentFixtureStep.SESSION_CREDENTIAL, EnrollmentFixtureStep.SESSION_TIME)
                } else {
                    emptyList()
                }
                assertEquals(expected, f.jdbc.queries)
                assertTrue(f.jdbc.updates.isEmpty())
                assertEquals(before, f.state())
            }
            // A failed refresh consumes, but never refunds, its previously accepted semantic charge.
            coordinator.withIngress(request()) { context ->
                admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { coordinator.admitSession(context, candidate) }
            }
            f.assertReleased()
            assertTrue(f.jdbc.updates.isEmpty())
            assertEquals(before, f.state())
        }
    }

    private fun refusedPhase(f: OrdinaryComplaintInstallationEnrollmentFixture, outcome: PersistenceDatabaseOutcome, operation: () -> Unit) {
        val failure = assertThrows<PersistencePhaseException> { operation() }
        assertEquals(PersistencePhaseFailureCode.WORK_FAILED, failure.code)
        assertEquals(outcome, failure.databaseOutcome)
        assertTrue(failure.cleanupProven)
        f.assertReleased()
    }

    private fun systemIngress(semanticEvents: Int = 131072): ComplaintIngressAdmission = ComplaintIngressAdmission(
        ClientIpResolver(KiraSecurityProperties()),
        admissionTestPolicy(semanticEvents = semanticEvents),
        admissionTestKeys(),
        SystemComplaintAdmissionNanoClock,
    )

    private fun genuineAdmissionAndRefresh(
        f: OrdinaryComplaintInstallationEnrollmentFixture,
        candidate: InstallationSessionCandidate,
        ingress: ComplaintIngressAdmission,
        coordinator: ComplaintSessionAdmissionCoordinator,
        credentialVersion: Long,
    ) {
        val before = f.state()
        coordinator.withIngress(request()) { context ->
            var snapshotGuarded = false
            f.afterStep = { step ->
                if (step === EnrollmentFixtureStep.SESSION_SNAPSHOT) {
                    val failure = assertThrows<PersistencePhaseException> { ingress.chargeSession(context, candidate.installation, Any()) }
                    assertEquals(PersistencePhaseFailureCode.ENTRY_REFUSED, failure.code)
                    snapshotGuarded = true
                }
            }
            val admitted = try {
                coordinator.admitSession(context, candidate) as ComplaintSessionAdmissionResult.Admitted
            } finally {
                f.afterStep = {}
            }
            f.assertReleased()
            assertTrue(snapshotGuarded)
            assertEquals(before, f.state())
            assertEquals(listOf(EnrollmentFixtureStep.SESSION_SNAPSHOT), f.jdbc.queries)
            assertTrue(f.jdbc.updates.isEmpty())
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, f.observations.single().second.phase.databaseOutcome())
            val foreign = ComplaintSessionAdmissionCoordinator(ingress, f.sessionExecutor())
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { foreign.refresh(context, admitted) }
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { coordinator.refresh(ComplaintIngressContext(), admitted) }
            OwnedCallerTestScope().use { callers ->
                callers.launch { admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { coordinator.refresh(context, admitted) } }.value()
            }
            val refreshed = coordinator.refresh(context, admitted) as SessionRefreshResult.Refreshed
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { coordinator.refresh(context, admitted) }
            assertEquals(candidate.installation, refreshed.installation)
            assertEquals(credentialVersion, refreshed.credentialVersion)
            assertEquals(listOf(EnrollmentFixtureStep.SESSION_REFRESH to 1), f.jdbc.updates)
        }
        val after = f.state()
        assertNotEquals(before.credentials, after.credentials)
        assertEquals(before.copy(credentials = after.credentials), after)
    }

    private fun refusedRequestsCannotWriteActivity(
        f: OrdinaryComplaintInstallationEnrollmentFixture,
        candidate: InstallationSessionCandidate,
        coordinator: ComplaintSessionAdmissionCoordinator,
    ) {
        val before = f.state()
        f.jdbc.queries.clear()
        f.jdbc.updates.clear()
        coordinator.withIngress(request()) { context ->
            admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { coordinator.admitSession(context, candidate) }
        }
        f.assertReleased()
        assertEquals(listOf(EnrollmentFixtureStep.SESSION_SNAPSHOT), f.jdbc.queries)
        assertEquals(PersistenceDatabaseOutcome.COMMITTED, f.observations.last().second.phase.databaseOutcome())
        assertEquals(before, f.state())
        val wrongSecret = f.sessionCandidate(candidate.installation.id, ByteArray(32) { 99 })
        coordinator.withIngress(request()) { context ->
            val rejected = coordinator.admitSession(context, wrongSecret) as ComplaintSessionAdmissionResult.Rejected
            assertEquals(InstallationSessionRejection.INSTALLATION_CREDENTIAL_REJECTED, rejected.reason)
        }
        f.assertReleased()
        assertEquals(List(2) { EnrollmentFixtureStep.SESSION_SNAPSHOT }, f.jdbc.queries)
        assertTrue(f.jdbc.updates.isEmpty())
        assertEquals(before, f.state())
    }

    private fun fabricatedCoreResultCannotAdmit(
        f: OrdinaryComplaintInstallationEnrollmentFixture,
        candidate: InstallationSessionCandidate,
        ingress: ComplaintIngressAdmission,
    ) {
        val before = f.state()
        val fake = object : InstallationSessionPreflight {
            override val installation = candidate.installation
            override val credentialVersion = 1L
        }
        val phases = f.sessionExecutor(
            object : ComplaintInstallationSession by f.sessionStore {
                override fun preflight(candidate: InstallationSessionCandidate): SessionPreflightResult = SessionPreflightResult.Ready(fake)
            },
        )
        val coordinator = ComplaintSessionAdmissionCoordinator(ingress, phases)
        coordinator.withIngress(request()) { context ->
            val failure = assertThrows<PersistencePhaseException> { coordinator.admitSession(context, candidate) }
            assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, failure.databaseOutcome)
            assertTrue(failure.cleanupProven)
        }
        f.assertReleased()
        assertEquals(before, f.state())
    }

    private fun retainedPersistenceCannotAdmit(
        f: OrdinaryComplaintInstallationEnrollmentFixture,
        candidate: InstallationSessionCandidate,
        coordinator: ComplaintSessionAdmissionCoordinator,
    ) {
        val before = f.state()
        val queries = f.jdbc.queries.size
        coordinator.withIngress(request()) { context ->
            val connection = f.ordinary.pool.connection
            try {
                assertThrows<PersistencePhaseException> { coordinator.admitSession(context, candidate) }
            } finally {
                connection.close()
            }
            f.assertReleased()
            val deletion = DeletionPersistenceAdmission()
            val permit = checkNotNull(deletion.tryPrivacyDeletion())
            try {
                assertThrows<PersistencePhaseException> { coordinator.admitSession(context, candidate) }
            } finally {
                // This permit performed no persistence work; all earlier real loans are already released.
                assertTrue(permit.releaseAfterQuiescence())
            }
            assertEquals(0, deletion.activeOwners().totalOwners)
        }
        assertEquals(queries, f.jdbc.queries.size)
        assertEquals(before, f.state())
    }

    private fun request(): MockHttpServletRequest = MockHttpServletRequest().apply { remoteAddr = "192.0.2.1" }
}
