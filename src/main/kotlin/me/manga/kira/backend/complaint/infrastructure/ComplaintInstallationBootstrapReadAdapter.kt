package me.manga.kira.backend.complaint.infrastructure

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintInstallationBootstrap
import me.manga.kira.backend.complaint.domain.ComplaintInstallationBootstrapFailure
import me.manga.kira.backend.complaint.domain.ComplaintInstallationBootstrapReadPort
import me.manga.kira.backend.complaint.domain.ComplaintInstallationRequestContext
import me.manga.kira.backend.complaint.domain.rejectInstallationBootstrap
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationV1
import me.manga.kira.backend.complaint.infrastructure.admission.JdbcInstallationCurrentStateReader
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintInstallationCurrentStatePhaseExecutor
import me.manga.kira.backend.security.ComplaintIngressContext
import org.springframework.jdbc.core.JdbcTemplate

/** Actual same-process TEST provenance + current committed/released read. No cold/LIVE fallback or journal-health gate. */
internal class ComplaintInstallationBootstrapReadAdapter(
    private val registration: ComplaintTestNamespaceRegistrationV1,
    private val ownership: PersistencePhaseOwnership,
    private val jdbc: JdbcTemplate,
) : ComplaintInstallationBootstrapReadPort {
    init { registration.requireInstallationResources(ownership, jdbc) }

    private val ingress = registration.process.consumers.ingressAdmission
    private val phases = ComplaintInstallationCurrentStatePhaseExecutor(ownership, JdbcInstallationCurrentStateReader(jdbc))

    @Suppress("SwallowedException")
    override fun read(context: ComplaintInstallationRequestContext): ComplaintInstallationBootstrap {
        requireConnectionFree()
        try {
            registration.requireInstallationResources(ownership, jdbc)
            val original = context as? ComplaintIngressContext ?: rejectInstallationBootstrap(ComplaintInstallationBootstrapFailure.UNAVAILABLE)
            ingress.requireLiveContext(original)
            ingress.chargeBootstrap(original) // Exactly once, before any transaction/checkout; only the existing trusted-IP dimension.
            val result = phases.bootstrap(registration)
            registration.requireInstallationResources(ownership, jdbc)
            ingress.requireLiveContext(original)
            return result
        } catch (failure: PersistencePhaseException) {
            rejectInstallationBootstrap(ComplaintInstallationBootstrapFailure.UNAVAILABLE)
        } catch (failure: ComplaintTestNamespaceRegistrationExceptionV1) {
            rejectInstallationBootstrap(ComplaintInstallationBootstrapFailure.UNAVAILABLE)
        }
    }

    override fun toString(): String = "ComplaintInstallationBootstrapReadAdapter(registered-TEST,current-read,no-LIVE)"
}
