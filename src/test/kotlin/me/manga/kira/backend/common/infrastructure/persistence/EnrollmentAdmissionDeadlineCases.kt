package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.domain.ComplaintInstallationEnrollment
import me.manga.kira.backend.complaint.domain.InstallationEnrollmentCandidate
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintEnrollmentAdmissionCoordinator
import me.manga.kira.backend.security.ComplaintAdmissionFailure
import me.manga.kira.backend.security.ComplaintIngressAdmission
import me.manga.kira.backend.security.admissionTestRefused
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.mock.web.MockHttpServletRequest

internal enum class EnrollmentAdmissionDeadlinePoint { MINTED, ENTRY, NEW_CHARGE, COUNTER, IDENTITY, CREDENTIAL, AUDIT, REPLAY }

/** Real monotonic elapsed time above the existing real-PG fixture. No production clock override or claimed PG lock-wait proof. */
internal class EnrollmentAdmissionDeadlineCases(private val f: OrdinaryComplaintInstallationEnrollmentFixture) {
    private var beforeAdmission = 0L
    private var afterAdmission = 0L
    private var crossed = false

    fun run(point: EnrollmentAdmissionDeadlinePoint) {
        val candidate = f.candidate()
        if (point === EnrollmentAdmissionDeadlinePoint.REPLAY) f.execute(candidate)
        val before = f.state()
        f.jdbc.queries.clear()
        f.jdbc.updates.clear()
        f.observations.clear()
        val ingress = enrollmentAdmissionTestIngress(f, events = 2)
        val port = ComplaintInstallationEnrollment { selected ->
            if (point === EnrollmentAdmissionDeadlinePoint.ENTRY) crossDeadline()
            f.store.enroll(selected)
        }
        val coordinator = ComplaintEnrollmentAdmissionCoordinator(ingress, f.executor(port))
        if (point === EnrollmentAdmissionDeadlinePoint.MINTED) {
            expiredBeforeEntry(ingress, candidate)
        } else {
            coordinator.withIngress(enrollmentAdmissionRequest()) { context ->
                beforeAdmission = System.nanoTime()
                val admitted = coordinator.admitEnrollment(context, candidate)
                afterAdmission = System.nanoTime()
                Thread.sleep(4400)
                assertTrue(System.nanoTime() - beforeAdmission < 4_900_000_000L, "Expired before the intended phase-boundary probe.")
                f.afterStep = { step -> if (!crossed && matches(point, step)) crossDeadline() }
                try {
                    enrollmentAdmissionRefusedPhase(f, PersistenceDatabaseOutcome.ROLLED_BACK) { coordinator.enroll(context, admitted) }
                } finally {
                    f.afterStep = {}
                }
                admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { coordinator.enroll(context, admitted) }
            }
        }
        assertTrue(crossed)
        val expectedQueries = if (point === EnrollmentAdmissionDeadlinePoint.MINTED || point === EnrollmentAdmissionDeadlinePoint.ENTRY) {
            emptyList()
        } else {
            listOf(EnrollmentFixtureStep.COUNTERS, EnrollmentFixtureStep.IDENTITY, EnrollmentFixtureStep.CREDENTIAL, EnrollmentFixtureStep.DATABASE_TIME)
        }
        assertEquals(expectedQueries, f.jdbc.queries)
        assertEquals(expectedUpdates(point), f.jdbc.updates)
        assertEquals(before, f.state()) // Includes every counter, the daily rollover, both identity rows and scoped audit rows.
        coordinator.withIngress(enrollmentAdmissionRequest()) { context ->
            admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { coordinator.admitEnrollment(context, candidate) }
        }
        f.assertReleased()
        assertEquals(expectedQueries, f.jdbc.queries)
        assertEquals(expectedUpdates(point), f.jdbc.updates)
        assertEquals(before, f.state())
    }

    private fun expiredBeforeEntry(ingress: ComplaintIngressAdmission, candidate: InstallationEnrollmentCandidate) {
        ingress.withIngress(enrollmentAdmissionRequest()) { context ->
            beforeAdmission = System.nanoTime()
            val identity = Any()
            ingress.chargeEnrollment(context, identity)
            afterAdmission = System.nanoTime()
            val handoff = ingress.prepareEnrollment(context, identity, candidate)
            Thread.sleep(5100)
            assertTrue(System.nanoTime() - afterAdmission >= 5_000_000_000L)
            crossed = true
            enrollmentAdmissionRefusedPhase(f, PersistenceDatabaseOutcome.NONE) { f.executor().enrollAdmitted(candidate, handoff) }
        }
    }

    private fun crossDeadline() = f.preserveAssertions {
        assertTrue(checkNotNull(PersistencePhaseOwnership.current()).isOriginalCaller())
        assertTrue(System.nanoTime() - beforeAdmission < 4_950_000_000L, "Scheduling exceeded the bounded pre-wait interval.")
        // Less than the existing one-second idle timeout and two-second phase budget; only elapsed admission time expires.
        Thread.sleep(650)
        assertTrue(System.nanoTime() - afterAdmission >= 5_000_000_000L, "The original admission must actually have expired.")
        crossed = true
    }

    private fun matches(point: EnrollmentAdmissionDeadlinePoint, step: EnrollmentFixtureStep): Boolean = when (point) {
        EnrollmentAdmissionDeadlinePoint.MINTED, EnrollmentAdmissionDeadlinePoint.ENTRY -> false
        EnrollmentAdmissionDeadlinePoint.NEW_CHARGE, EnrollmentAdmissionDeadlinePoint.REPLAY -> step === EnrollmentFixtureStep.DATABASE_TIME
        EnrollmentAdmissionDeadlinePoint.COUNTER -> step === EnrollmentFixtureStep.CHARGE && f.jdbc.updates.size == 1
        EnrollmentAdmissionDeadlinePoint.IDENTITY -> step === EnrollmentFixtureStep.CHARGE && f.jdbc.updates.size == 4
        EnrollmentAdmissionDeadlinePoint.CREDENTIAL -> step === EnrollmentFixtureStep.IDENTITY_INSERT
        EnrollmentAdmissionDeadlinePoint.AUDIT -> step === EnrollmentFixtureStep.CREDENTIAL_INSERT
    }

    private fun expectedUpdates(point: EnrollmentAdmissionDeadlinePoint): List<Pair<EnrollmentFixtureStep, Int>> {
        val counters = listOf(EnrollmentFixtureStep.CHARGE, EnrollmentFixtureStep.CHARGE, EnrollmentFixtureStep.DAILY, EnrollmentFixtureStep.CHARGE)
        val steps = when (point) {
            EnrollmentAdmissionDeadlinePoint.MINTED, EnrollmentAdmissionDeadlinePoint.ENTRY,
            EnrollmentAdmissionDeadlinePoint.NEW_CHARGE, EnrollmentAdmissionDeadlinePoint.REPLAY,
            -> emptyList()

            EnrollmentAdmissionDeadlinePoint.COUNTER -> counters.take(1)

            EnrollmentAdmissionDeadlinePoint.IDENTITY -> counters

            EnrollmentAdmissionDeadlinePoint.CREDENTIAL -> counters + EnrollmentFixtureStep.IDENTITY_INSERT

            EnrollmentAdmissionDeadlinePoint.AUDIT -> counters + EnrollmentFixtureStep.IDENTITY_INSERT + EnrollmentFixtureStep.CREDENTIAL_INSERT
        }
        return steps.map { it to 1 }
    }
}

internal fun enrollmentAdmissionRequest(): MockHttpServletRequest = MockHttpServletRequest().apply { remoteAddr = "192.0.2.1" }
