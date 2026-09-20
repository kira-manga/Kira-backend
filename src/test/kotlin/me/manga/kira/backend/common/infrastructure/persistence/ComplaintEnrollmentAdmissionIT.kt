package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.domain.ComplaintInstallationEnrollment
import me.manga.kira.backend.complaint.domain.ComplaintPlatform
import me.manga.kira.backend.complaint.domain.InstallationEnrollmentCandidate
import me.manga.kira.backend.complaint.domain.InstallationEnrollmentDisposition
import me.manga.kira.backend.complaint.domain.InstallationEnrollmentRejection
import me.manga.kira.backend.complaint.domain.InstallationEnrollmentResult
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintEnrollmentAdmissionCoordinator
import me.manga.kira.backend.complaint.infrastructure.capacity.ComplaintInstallationEnrollmentOperation
import me.manga.kira.backend.config.KiraSecurityProperties
import me.manga.kira.backend.security.ClientIpResolver
import me.manga.kira.backend.security.ComplaintAdmissionFailure
import me.manga.kira.backend.security.ComplaintAdmissionNanoClock
import me.manga.kira.backend.security.ComplaintAdmittedEnrollmentWrite
import me.manga.kira.backend.security.ComplaintEnrollmentAdmissionPolicy
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
import java.util.UUID

/** Existing owned PG/JPA/counter fixture only; its explicit configuration remains synthetic, not mode or restore authority. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class ComplaintEnrollmentAdmissionIT {
    private val database = lazy { PgLifecycleDatabaseFixture(ComplaintEnrollmentAdmissionIT::class.java).also { it.start() } }

    @AfterAll
    fun closeDatabase() {
        if (database.isInitialized()) database.value.close()
    }

    @Test
    fun `real enrollment admission owns new and exact replay without refund or duplicate durable charges`() {
        withOrdinaryComplaintInstallationEnrollment(database.value) { f ->
            f.seedDaily(f.databaseDay().minusDays(1), 99, 100)
            val candidate = f.candidate()
            val before = f.state()
            val ingress = enrollmentAdmissionTestIngress(f, events = 4)
            val ownerReferences = mutableListOf<UUID>()
            val coordinator = ComplaintEnrollmentAdmissionCoordinator(ingress, f.executor(observeOwnerReference(f, ownerReferences)))
            var guarded = false
            val created = coordinator.withIngress(enrollmentAdmissionRequest()) { context ->
                val admitted = coordinator.admitEnrollment(context, candidate)
                assertTrue(f.jdbc.queries.isEmpty() && f.jdbc.updates.isEmpty())
                f.afterStep = { step ->
                    if (step === EnrollmentFixtureStep.COUNTERS) {
                        val failure = assertThrows<PersistencePhaseException> { ingress.chargeBootstrap(context) }
                        assertEquals(PersistencePhaseFailureCode.ENTRY_REFUSED, failure.code)
                        guarded = true
                    }
                }
                try {
                    coordinator.enroll(context, admitted) as InstallationEnrollmentResult.Enrolled
                } finally {
                    f.afterStep = {}
                }
            }
            f.assertReleased()
            assertTrue(guarded)
            assertEquals(InstallationEnrollmentDisposition.CREATED, created.disposition)
            assertEquals(candidate.installation, created.installation)
            assertEquals(f.jdbc.databaseTimes.single(), created.issuedAt)
            f.assertCreationCharge(before)
            assertEquals(1L, f.state().counters.getValue("installation_ids").dailyCount)
            assertEquals(ownerReferences.single(), storedOwnerReference(f, candidate))

            exhaustCreationWithoutChangingBounds(f)
            val beforeReplay = f.state()
            f.jdbc.updates.clear()
            val replay = coordinator.withIngress(enrollmentAdmissionRequest()) { context ->
                coordinator.enroll(context, coordinator.admitEnrollment(context, candidate)) as InstallationEnrollmentResult.Enrolled
            }
            f.assertReleased()
            assertEquals(InstallationEnrollmentDisposition.EXACT_REPLAY, replay.disposition)
            assertEquals(created.credentialVersion, replay.credentialVersion)
            assertEquals(listOf(EnrollmentFixtureStep.REPLAY to 1), f.jdbc.updates)
            assertEquals(2, ownerReferences.size)
            assertNotEquals(ownerReferences.first(), ownerReferences.last())
            assertEquals(ownerReferences.first(), storedOwnerReference(f, candidate))
            val afterReplay = f.state()
            assertNotEquals(beforeReplay.credentials, afterReplay.credentials)
            assertEquals(beforeReplay.copy(credentials = afterReplay.credentials), afterReplay)
            val queries = f.jdbc.queries.size
            coordinator.withIngress(enrollmentAdmissionRequest()) { context ->
                admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { coordinator.admitEnrollment(context, candidate) }
            }
            assertEquals(queries, f.jdbc.queries.size)
            assertEquals(afterReplay, f.state()) // Exact replay used the same finite semantic budget.
        }
    }

    @Test
    fun `declared quota binding is rechecked against genuine locked digest reservation and daily limits before any write`() {
        BindingDrift.entries.forEach { drift ->
            withOrdinaryComplaintInstallationEnrollment(database.value) { f ->
                val candidate = f.candidate()
                val limits = if (drift === BindingDrift.DIGEST) {
                    ComplaintEnrollmentAdmissionPolicy.Bounded(9, ByteArray(32) { 99 }, 9_000_000, 100)
                } else {
                    enrollmentAdmissionTestLimits(f)
                }
                val coordinator = ComplaintEnrollmentAdmissionCoordinator(enrollmentAdmissionTestIngress(f, events = 2, limits = limits), f.executor())
                coordinator.withIngress(enrollmentAdmissionRequest()) { context ->
                    val admitted = coordinator.admitEnrollment(context, candidate)
                    when (drift) {
                        BindingDrift.DIGEST -> Unit

                        BindingDrift.RESERVATION -> assertEquals(
                            1,
                            f.observer.update("UPDATE complaint_capacity_counters SET creation_limit = 9 WHERE name = 'installation_ids'"),
                        )

                        BindingDrift.DAILY -> f.seedDaily(f.databaseDay(), 0, 9)

                        BindingDrift.CHANGED_BUT_LOOSER -> assertEquals(
                            1,
                            f.observer.update("UPDATE complaint_capacity_counters SET creation_limit = 9000001 WHERE name = 'installation_ids'"),
                        )
                    }
                    val before = f.state()
                    enrollmentAdmissionRefusedPhase(f, PersistenceDatabaseOutcome.ROLLED_BACK) { coordinator.enroll(context, admitted) }
                    assertEquals(listOf(EnrollmentFixtureStep.COUNTERS), f.jdbc.queries)
                    assertTrue(f.jdbc.updates.isEmpty())
                    assertEquals(before, f.state())
                }
                coordinator.withIngress(enrollmentAdmissionRequest()) { context ->
                    admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { coordinator.admitEnrollment(context, candidate) }
                }
            }
        }
    }

    @Test
    fun `private handoff rejects forged stale substituted and raw fallback paths while exact coordinator use remains one shot`() {
        withOrdinaryComplaintInstallationEnrollment(database.value) { f ->
            val candidate = f.candidate()
            val ingress = enrollmentAdmissionTestIngress(f)
            val phases = f.executor()
            val before = f.state()
            enrollmentAdmissionRefusedPhase(f, PersistenceDatabaseOutcome.NONE) {
                phases.enrollAdmitted(candidate, object : ComplaintAdmittedEnrollmentWrite {})
            }
            ingress.withIngress(enrollmentAdmissionRequest()) { context ->
                admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { phases.enroll(candidate) }
                directRawEnrollmentRefused(f, candidate)
                val identity = Any()
                ingress.chargeEnrollment(context, identity)
                val handoff = ingress.prepareEnrollment(context, identity, candidate)
                val substitute = f.candidate(candidate.installation.id, platform = ComplaintPlatform.IOS)
                enrollmentAdmissionRefusedPhase(f, PersistenceDatabaseOutcome.ROLLED_BACK) { phases.enrollAdmitted(substitute, handoff) }
                enrollmentAdmissionRefusedPhase(f, PersistenceDatabaseOutcome.NONE) { phases.enrollAdmitted(candidate, handoff) }
            }
            val stale = ingress.withIngress(enrollmentAdmissionRequest()) { context ->
                val identity = Any()
                ingress.chargeEnrollment(context, identity)
                ingress.prepareEnrollment(context, identity, candidate)
            }
            enrollmentAdmissionRefusedPhase(f, PersistenceDatabaseOutcome.NONE) { phases.enrollAdmitted(candidate, stale) }
            assertTrue(f.jdbc.queries.isEmpty() && f.jdbc.updates.isEmpty())
            assertEquals(before, f.state())

            val coordinator = ComplaintEnrollmentAdmissionCoordinator(ingress, phases)
            val foreign = ComplaintEnrollmentAdmissionCoordinator(ingress, phases)
            coordinator.withIngress(enrollmentAdmissionRequest()) { context ->
                val admitted = coordinator.admitEnrollment(context, candidate)
                admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { foreign.enroll(context, admitted) }
                admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { coordinator.enroll(ComplaintIngressContext(), admitted) }
                OwnedCallerTestScope().use { callers ->
                    callers.launch { admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { coordinator.enroll(context, admitted) } }.value()
                }
                val enrolled = coordinator.enroll(context, admitted) as InstallationEnrollmentResult.Enrolled
                assertEquals(InstallationEnrollmentDisposition.CREATED, enrolled.disposition)
                admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { coordinator.enroll(context, admitted) }
            }
            f.assertReleased()
        }
    }

    @Test
    fun `custom clock and retained connection cannot enter admitted enrollment or charge from database ownership`() {
        withOrdinaryComplaintInstallationEnrollment(database.value) { f ->
            val candidate = f.candidate()
            var reads = 0
            val clock = ComplaintAdmissionNanoClock {
                requireConnectionFree()
                reads += 1
                0L
            }
            val ingress = enrollmentAdmissionTestIngress(f, clock = clock)
            val coordinator = ComplaintEnrollmentAdmissionCoordinator(ingress, f.executor())
            val before = f.state()
            coordinator.withIngress(enrollmentAdmissionRequest()) { context ->
                val connection = f.ordinary.pool.connection
                try {
                    assertThrows<PersistencePhaseException> { coordinator.admitEnrollment(context, candidate) }
                } finally {
                    connection.close()
                }
                f.assertReleased()
                val admitted = coordinator.admitEnrollment(context, candidate)
                val lastRead = reads
                admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { coordinator.enroll(context, admitted) }
                assertEquals(lastRead, reads)
            }
            assertTrue(f.jdbc.queries.isEmpty() && f.jdbc.updates.isEmpty())
            assertEquals(before, f.state())
            f.assertReleased()
        }
    }

    @Test
    fun `normal locked rejection and copied completion cannot refund quota or produce an unowned success`() {
        withOrdinaryComplaintInstallationEnrollment(database.value) { f ->
            val candidate = f.candidate()
            f.execute(candidate)
            val wrong = f.candidate(candidate.installation.id, secret = ByteArray(32) { 99 })
            val coordinator = ComplaintEnrollmentAdmissionCoordinator(enrollmentAdmissionTestIngress(f, events = 2), f.executor())
            val before = f.state()
            f.jdbc.updates.clear()
            coordinator.withIngress(enrollmentAdmissionRequest()) { context ->
                val result = coordinator.enroll(context, coordinator.admitEnrollment(context, wrong)) as InstallationEnrollmentResult.Rejected
                assertEquals(InstallationEnrollmentRejection.INSTALLATION_CREDENTIAL_REJECTED, result.reason)
            }
            f.assertReleased()
            assertTrue(f.jdbc.updates.isEmpty())
            assertEquals(before, f.state())
            coordinator.withIngress(enrollmentAdmissionRequest()) { context ->
                admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { coordinator.admitEnrollment(context, candidate) }
            }
        }
        withOrdinaryComplaintInstallationEnrollment(database.value) { f ->
            val candidate = f.candidate()
            val port = ComplaintInstallationEnrollment { selected ->
                val real = f.store.enroll(selected) as InstallationEnrollmentResult.Enrolled
                InstallationEnrollmentResult.Enrolled(real.disposition, real.installation, real.credentialVersion, real.issuedAt)
            }
            val coordinator = ComplaintEnrollmentAdmissionCoordinator(enrollmentAdmissionTestIngress(f, events = 2), f.executor(port))
            val before = f.state()
            coordinator.withIngress(enrollmentAdmissionRequest()) { context ->
                val admitted = coordinator.admitEnrollment(context, candidate)
                enrollmentAdmissionRefusedPhase(f, PersistenceDatabaseOutcome.ROLLED_BACK) { coordinator.enroll(context, admitted) }
            }
            assertTrue(f.jdbc.updates.any { it.first === EnrollmentFixtureStep.CREDENTIAL_INSERT })
            assertEquals(before, f.state())
            coordinator.withIngress(enrollmentAdmissionRequest()) { context ->
                admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { coordinator.admitEnrollment(context, candidate) }
            }
        }
    }

    @Test
    fun `original enrollment deadline is enforced before phase entry after begin and before the first capacity charge`() {
        listOf(EnrollmentAdmissionDeadlinePoint.MINTED, EnrollmentAdmissionDeadlinePoint.ENTRY, EnrollmentAdmissionDeadlinePoint.NEW_CHARGE).forEach {
            withOrdinaryComplaintInstallationEnrollment(database.value) { f -> EnrollmentAdmissionDeadlineCases(f).run(it) }
        }
    }

    @Test
    fun `expired enrollment between counter identity credential and audit writes rolls back all earlier work`() {
        listOf(
            EnrollmentAdmissionDeadlinePoint.COUNTER,
            EnrollmentAdmissionDeadlinePoint.IDENTITY,
            EnrollmentAdmissionDeadlinePoint.CREDENTIAL,
            EnrollmentAdmissionDeadlinePoint.AUDIT,
        ).forEach { withOrdinaryComplaintInstallationEnrollment(database.value) { f -> EnrollmentAdmissionDeadlineCases(f).run(it) } }
    }

    @Test
    fun `exact replay checks the original deadline after real row locks before its activity update`() {
        withOrdinaryComplaintInstallationEnrollment(database.value) { f -> EnrollmentAdmissionDeadlineCases(f).run(EnrollmentAdmissionDeadlinePoint.REPLAY) }
    }

    private fun observeOwnerReference(f: OrdinaryComplaintInstallationEnrollmentFixture, references: MutableList<UUID>): ComplaintInstallationEnrollment =
        ComplaintInstallationEnrollment { candidate ->
            val queries = f.jdbc.queries.toList()
            val updates = f.jdbc.updates.toList()
            val operation = ComplaintInstallationEnrollmentOperation.prepare(f.jdbc, candidate)
            val phase = checkNotNull(PersistencePhaseOwnership.current())
            val reference = phase.installationEnrollment.ownerReference(operation, f.jdbc)
            f.preserveAssertions {
                assertEquals(queries, f.jdbc.queries) // Already retained before any enrollment query or write; no generator seam.
                assertEquals(updates, f.jdbc.updates)
                assertEquals(reference, phase.installationEnrollment.ownerReference(operation, f.jdbc))
                assertEquals(4, reference.version())
                assertEquals(2, reference.variant())
                assertNotEquals(candidate.installation.id, reference)
            }
            references.add(reference)
            operation.enroll(f.capacity, f.audit)
        }

    private fun storedOwnerReference(f: OrdinaryComplaintInstallationEnrollmentFixture, candidate: InstallationEnrollmentCandidate): UUID = checkNotNull(
        f.observer.queryForObject(
            "SELECT owner_reference FROM app_installations WHERE id = ?",
            { row, _ -> row.getObject(1, UUID::class.java) },
            candidate.installation.id,
        ),
    )

    private fun exhaustCreationWithoutChangingBounds(f: OrdinaryComplaintInstallationEnrollmentFixture) {
        f.seedDaily(f.databaseDay().plusDays(2), 100, 100)
        assertEquals(22, f.observer.update("UPDATE complaint_capacity_counters SET configuration_closed = true"))
        assertEquals(
            1,
            f.observer.update(
                "UPDATE complaint_capacity_counters SET free_units = hard_limit - creation_limit, " +
                    "actual_units = creation_limit - recovery_reserved_units - test_reserved_units WHERE name = 'installation_ids'",
            ),
        )
        assertEquals(100L, f.state().counters.getValue("installation_ids").dailyCount)
        assertEquals(
            true,
            f.observer.queryForObject(
                "SELECT hard_limit - free_units = creation_limit FROM complaint_capacity_counters WHERE name = 'installation_ids'",
                Boolean::class.java,
            ),
        )
    }

    private fun directRawEnrollmentRefused(f: OrdinaryComplaintInstallationEnrollmentFixture, candidate: InstallationEnrollmentCandidate) {
        val phase = f.ordinary.ownership.enterComplaintInstallationEnrollment()
        try {
            phase.begin()
            assertThrows<PersistencePhaseException> { f.store.enroll(candidate) }
            assertThrows<PersistencePhaseException> { phase.commit() }
        } finally {
            phase.finish()
        }
        enrollmentAdmissionRefusedPhase(f, PersistenceDatabaseOutcome.ROLLED_BACK) { phase.installationEnrollment.result(null) }
    }

    private enum class BindingDrift { DIGEST, RESERVATION, DAILY, CHANGED_BUT_LOOSER }
}

internal fun enrollmentAdmissionTestLimits(f: OrdinaryComplaintInstallationEnrollmentFixture): ComplaintEnrollmentAdmissionPolicy.Bounded =
    ComplaintEnrollmentAdmissionPolicy.Bounded(9, f.counters.syntheticPolicyDigest(), 9_000_000, 100)

internal fun enrollmentAdmissionTestIngress(
    f: OrdinaryComplaintInstallationEnrollmentFixture,
    events: Int = 131072,
    clock: ComplaintAdmissionNanoClock = SystemComplaintAdmissionNanoClock,
    limits: ComplaintEnrollmentAdmissionPolicy = enrollmentAdmissionTestLimits(f),
): ComplaintIngressAdmission = ComplaintIngressAdmission(
    ClientIpResolver(KiraSecurityProperties()),
    admissionTestPolicy(semanticEvents = events, enrollment = limits),
    admissionTestKeys(),
    clock,
)

internal fun enrollmentAdmissionRefusedPhase(f: OrdinaryComplaintInstallationEnrollmentFixture, outcome: PersistenceDatabaseOutcome, operation: () -> Unit) {
    val failure = assertThrows<PersistencePhaseException> { operation() }
    assertEquals(PersistencePhaseFailureCode.WORK_FAILED, failure.code)
    assertEquals(outcome, failure.databaseOutcome)
    assertTrue(failure.cleanupProven)
    f.assertReleased()
}
