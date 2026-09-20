package me.manga.kira.backend.complaint.infrastructure

import me.manga.kira.backend.audit.domain.ComplaintInstallationEnrollmentAudit
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.complaint.domain.ComplaintInstallationEnrollmentResponse
import me.manga.kira.backend.complaint.domain.ComplaintInstallationExchange
import me.manga.kira.backend.complaint.domain.ComplaintInstallationHttpFailure
import me.manga.kira.backend.complaint.domain.ComplaintInstallationRequestContext
import me.manga.kira.backend.complaint.domain.ComplaintInstallationSessionResponse
import me.manga.kira.backend.complaint.domain.InstallationEnrollmentCandidate
import me.manga.kira.backend.complaint.domain.InstallationEnrollmentRejection
import me.manga.kira.backend.complaint.domain.InstallationEnrollmentResult
import me.manga.kira.backend.complaint.domain.InstallationSessionCandidate
import me.manga.kira.backend.complaint.domain.InstallationSessionRejection
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import me.manga.kira.backend.complaint.domain.SessionRefreshResult
import me.manga.kira.backend.complaint.domain.rejectInstallationHttp
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintEnrollmentAdmissionCoordinator
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintSessionAdmissionCoordinator
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintSessionAdmissionResult
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationV1
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintInstallationEnrollmentStore
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintInstallationEnrollmentPhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintInstallationSessionPhaseExecutor
import me.manga.kira.backend.security.ComplaintIngressContext
import me.manga.kira.backend.security.InstallationJwtCodec
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.time.Instant

/** Dormant registered TEST exchange. Only the actual non-operator process binding selects its consumers and full D. */
@Suppress("LongParameterList")
internal class ComplaintInstallationExchangeAdapter(
    private val registration: ComplaintTestNamespaceRegistrationV1,
    private val ownership: PersistencePhaseOwnership,
    private val jdbc: JdbcTemplate,
    audit: ComplaintInstallationEnrollmentAudit,
) : ComplaintInstallationExchange {
    init { registration.requireInstallationResources(ownership, jdbc) }

    private val desired = registration.process.desiredSettings()
    private val ingress = registration.process.consumers.ingressAdmission
    private val capacity = JdbcComplaintCapacityStore(jdbc, registration.process.consumers.capacityPolicy.digestBytes())
    private val jwt = InstallationJwtCodec(registration.process.consumers.jwt.installationKeyRing, Clock.systemUTC())

    private val testScope = desired.scope
    private val enrollment = ComplaintEnrollmentAdmissionCoordinator(
        ingress,
        ComplaintInstallationEnrollmentPhaseExecutor(ownership, JdbcComplaintInstallationEnrollmentStore(jdbc, capacity, audit, desired)),
    )
    private val sessions = ComplaintSessionAdmissionCoordinator(
        ingress,
        ComplaintInstallationSessionPhaseExecutor(ownership, JdbcComplaintInstallationSessionStore(jdbc, desired)),
    )

    @Suppress("SwallowedException")
    override fun enroll(context: ComplaintInstallationRequestContext, candidate: InstallationEnrollmentCandidate): ComplaintInstallationEnrollmentResponse {
        val request = requestContext(context)
        val result = try {
            enrollment.enroll(request, enrollment.admitEnrollment(request, candidate))
        } catch (failure: PersistencePhaseException) {
            rejectInstallationHttp(ComplaintInstallationHttpFailure.UNAVAILABLE)
        }
        return when (result) {
            is InstallationEnrollmentResult.Enrolled -> ComplaintInstallationEnrollmentResponse(
                result.disposition,
                issue(candidate.installation, result.installation, result.credentialVersion, result.issuedAt),
            )

            is InstallationEnrollmentResult.Rejected -> rejectInstallationHttp(enrollmentFailure(result.reason), result.retryAfterSeconds)
        }
    }

    @Suppress("SwallowedException")
    override fun session(context: ComplaintInstallationRequestContext, candidate: InstallationSessionCandidate): ComplaintInstallationSessionResponse {
        val request = requestContext(context)
        val result = try {
            when (val admitted = sessions.admitSession(request, candidate)) {
                is ComplaintSessionAdmissionResult.Rejected -> rejectInstallationHttp(sessionFailure(admitted.reason))
                is ComplaintSessionAdmissionResult.Admitted -> sessions.refresh(request, admitted)
            }
        } catch (failure: PersistencePhaseException) {
            rejectInstallationHttp(ComplaintInstallationHttpFailure.UNAVAILABLE)
        }
        return when (result) {
            is SessionRefreshResult.Refreshed -> issue(candidate.installation, result.installation, result.credentialVersion, result.issuedAt)
            is SessionRefreshResult.Rejected -> rejectInstallationHttp(sessionFailure(result.reason))
        }
    }

    private fun requestContext(context: ComplaintInstallationRequestContext): ComplaintIngressContext {
        requireRegistered()
        return context as? ComplaintIngressContext ?: rejectInstallationHttp(ComplaintInstallationHttpFailure.UNAVAILABLE)
    }

    private fun issue(
        expected: ScopedInstallationId,
        installation: ScopedInstallationId,
        version: Long,
        databaseTime: Instant,
    ): ComplaintInstallationSessionResponse {
        // Reached only through the concrete admitted executors after commit AND actual release.
        requireRegistered()
        check(installation == expected && installation.scope == testScope)
        val issued = jwt.issue(installation, version, databaseTime)
        requireRegistered() // Close/shutdown during signing cannot return a token.
        check(issued.expiresInSeconds == ComplaintInstallationSessionResponse.EXPIRES_IN_SECONDS)
        return ComplaintInstallationSessionResponse(installation, version, issued.value, issued.issuedAt)
    }

    private fun requireRegistered() {
        try { registration.requireInstallationResources(ownership, jdbc) }
        catch (_: ComplaintTestNamespaceRegistrationExceptionV1) { rejectInstallationHttp(ComplaintInstallationHttpFailure.UNAVAILABLE) }
    }

    override fun toString(): String = "ComplaintInstallationExchangeAdapter(registered-TEST,dormant,no-reopen-authority)"

    private fun enrollmentFailure(reason: InstallationEnrollmentRejection): ComplaintInstallationHttpFailure = when (reason) {
        InstallationEnrollmentRejection.INSTALLATION_CREDENTIAL_REJECTED -> ComplaintInstallationHttpFailure.INSTALLATION_CREDENTIAL_REJECTED
        InstallationEnrollmentRejection.INSTALLATION_PLATFORM_MISMATCH -> ComplaintInstallationHttpFailure.INSTALLATION_PLATFORM_MISMATCH
        InstallationEnrollmentRejection.INSTALLATION_SCOPE_MISMATCH -> ComplaintInstallationHttpFailure.INSTALLATION_SCOPE_MISMATCH
        InstallationEnrollmentRejection.INSTALLATION_SCOPE_RETIRED -> ComplaintInstallationHttpFailure.INSTALLATION_SCOPE_RETIRED
        InstallationEnrollmentRejection.INSTALLATION_DELETION_PENDING -> ComplaintInstallationHttpFailure.INSTALLATION_DELETION_PENDING
        InstallationEnrollmentRejection.INSTALLATION_RETIRED -> ComplaintInstallationHttpFailure.INSTALLATION_RETIRED
        InstallationEnrollmentRejection.INSTALLATION_DELETED -> ComplaintInstallationHttpFailure.INSTALLATION_DELETED
        InstallationEnrollmentRejection.DAILY_LIMIT_REACHED -> ComplaintInstallationHttpFailure.RATE_LIMITED
        InstallationEnrollmentRejection.CAPACITY_UNAVAILABLE -> ComplaintInstallationHttpFailure.UNAVAILABLE
    }

    private fun sessionFailure(reason: InstallationSessionRejection): ComplaintInstallationHttpFailure = when (reason) {
        InstallationSessionRejection.INSTALLATION_NOT_FOUND -> ComplaintInstallationHttpFailure.INSTALLATION_NOT_FOUND
        InstallationSessionRejection.INSTALLATION_RETIRED -> ComplaintInstallationHttpFailure.INSTALLATION_RETIRED
        InstallationSessionRejection.INSTALLATION_DELETED -> ComplaintInstallationHttpFailure.INSTALLATION_DELETED
        InstallationSessionRejection.INSTALLATION_CREDENTIAL_REJECTED -> ComplaintInstallationHttpFailure.INSTALLATION_CREDENTIAL_REJECTED
        InstallationSessionRejection.INSTALLATION_DELETION_PENDING -> ComplaintInstallationHttpFailure.INSTALLATION_DELETION_PENDING
        InstallationSessionRejection.INSTALLATION_SCOPE_MISMATCH -> ComplaintInstallationHttpFailure.INSTALLATION_SCOPE_MISMATCH
        InstallationSessionRejection.INSTALLATION_SCOPE_RETIRED -> ComplaintInstallationHttpFailure.INSTALLATION_SCOPE_RETIRED
    }
}
