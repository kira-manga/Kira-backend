package me.manga.kira.backend.config

import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.audit.domain.ComplaintInstallationEnrollmentAudit
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.web.DisabledComplaintRoutesFilter
import me.manga.kira.backend.complaint.api.ComplaintInstallationBootstrapHttpHandler
import me.manga.kira.backend.complaint.api.ComplaintInstallationHttpHandler
import me.manga.kira.backend.complaint.api.ComplaintOwnerCreateHttpHandler
import me.manga.kira.backend.complaint.application.ComplaintInstallationBootstrapService
import me.manga.kira.backend.complaint.application.ComplaintInstallationService
import me.manga.kira.backend.complaint.application.ComplaintOwnerCreateService
import me.manga.kira.backend.complaint.infrastructure.ComplaintInstallationBearerAuthenticator
import me.manga.kira.backend.complaint.infrastructure.ComplaintInstallationBootstrapReadAdapter
import me.manga.kira.backend.complaint.infrastructure.ComplaintInstallationExchangeAdapter
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerCreateAdapter
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerCreateStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerHistoryStore
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestProcessAssemblyV1
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerCreatePhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerHistoryPhaseExecutor
import me.manga.kira.backend.security.ComplaintHttpIngressBridge
import me.manga.kira.backend.security.ComplaintInstallationRoutes
import me.manga.kira.backend.security.ComplaintInstallationSecurityChainFactory
import me.manga.kira.backend.security.ComplaintSecurityFailure
import me.manga.kira.backend.security.ComplaintSecurityResponses
import me.manga.kira.backend.security.InstallationJwtCodec
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.web.SecurityFilterChain
import java.time.Clock

/**
 * Explicit same-process TEST assembly, never constructed from properties or a diagnostic assessment.
 * Registration is privately issued after actual first PROJECT and target readback. Every response
 * additionally needs its current owned read; retaining this composition does not cache that result.
 * Bootstrap-only remains the default. The separate explicit initial-checkpoint factory selects only
 * registered identity exchange and current CREATE/status; no LIVE/restart/quarantine or broad Core.
 */
internal class ComplaintTestBootstrapHttpCompositionV1 private constructor(
    registration: ComplaintTestNamespaceRegistrationV1,
    ownership: PersistencePhaseOwnership,
    jdbc: JdbcTemplate,
    assembly: ComplaintTestProcessAssemblyV1? = null,
    audit: AuditService? = null,
) {
    init { require((assembly == null) == (audit == null)) }

    private val producer = ComplaintInstallationBootstrapHttpHandler(
        ComplaintInstallationBootstrapService(ComplaintInstallationBootstrapReadAdapter(registration, ownership, jdbc)),
        registration.process.consumers.ingressAdmission,
    )
    private val bridge = ComplaintHttpIngressBridge(registration.process.consumers.ingressAdmission)
    private val factory = if (assembly == null) {
        ComplaintInstallationSecurityChainFactory.bootstrapOnly(bridge, producer)
    } else {
        registration.requireActiveIdentityTarget(assembly)
        val selectedAudit = checkNotNull(audit)
        val ingress = registration.process.consumers.ingressAdmission
        val scope = registration.process.desiredSettings().scope
        val jwt = InstallationJwtCodec(registration.process.consumers.jwt.installationKeyRing, Clock.systemUTC())
        val store = JdbcComplaintOwnerCreateStore.registeredInitialCheckpoint(jdbc, selectedAudit, ownership, registration, assembly)
        val create = ComplaintOwnerCreateHttpHandler(
            ComplaintOwnerCreateService(ComplaintOwnerCreateAdapter(scope, jwt, ComplaintOwnerCreatePhaseExecutor(ownership, store), ingress)), ingress,
        )
        val enrollmentAudit = ComplaintInstallationEnrollmentAudit { selectedScope, allocation, at ->
            selectedAudit.recordInstallationEnrollment(selectedScope, allocation, at)
        }
        val installations = ComplaintInstallationHttpHandler(
            ComplaintInstallationService(ComplaintInstallationExchangeAdapter(registration, ownership, jdbc, enrollmentAudit)), ingress,
        )
        // This existing AUTH operation is only early rejection. No history producer/handler is constructed.
        val authentication = ComplaintInstallationBearerAuthenticator(scope, jwt,
            ComplaintOwnerHistoryPhaseExecutor(ownership, JdbcComplaintOwnerHistoryStore(jdbc, scope)), ingress)
        ComplaintInstallationSecurityChainFactory.registeredCreateSubset(bridge, producer, authentication, installations, create)
    }
    private val disabled = DisabledComplaintRoutesFilter()

    /** Literal mappings only, all to the one dispatcher. Presence of a policy never selects this subset. */
    val mappedPaths: Set<String> = if (assembly == null) setOf(ComplaintInstallationRoutes.BOOTSTRAP) else setOf(
        ComplaintInstallationRoutes.BOOTSTRAP, ComplaintInstallationRoutes.ENROLLMENT, ComplaintInstallationRoutes.SESSION,
        ComplaintInstallationRoutes.HISTORY, ComplaintInstallationRoutes.STATUS,
    )

    /** Original admission surrounds the fixed bodyless check, generic body guard, Spring and MVC. */
    val ingressFilter: Filter = Filter { request, response, chain ->
        val http = request as HttpServletRequest
        val path = ComplaintInstallationRoutes.path(http)
        if (path !in mappedPaths || (path != ComplaintInstallationRoutes.BOOTSTRAP && !factory.implemented(http))) {
            disabled.doFilter(request, response, FilterChain { excluded, output ->
                // The broad disabled filter already owns the complaint families. Also close the
                // installation chain's exact status aliases before buffering, never user fallthrough.
                val unopened = excluded as HttpServletRequest
                if (ComplaintInstallationRoutes.matches(unopened)) {
                    ComplaintSecurityResponses.problem(unopened, output as HttpServletResponse, ComplaintSecurityFailure.NOT_FOUND)
                } else chain.doFilter(excluded, output)
            })
        } else {
            bridge.doFilter(request, response, FilterChain { admitted, output ->
                val selected = admitted as HttpServletRequest
                if (path != ComplaintInstallationRoutes.BOOTSTRAP ||
                    producer.validateWithinIngress(selected, output as HttpServletResponse, bridge.authenticationContext(selected))) {
                    chain.doFilter(admitted, output)
                }
            })
        }
    }

    val handler = factory.handler // Fixed dispatcher claims this same ingress once; never map the standalone producer.

    fun securityChain(http: HttpSecurity): SecurityFilterChain = factory.build(http)

    override fun toString(): String = "ComplaintTestBootstrapHttpCompositionV1(registered-TEST-only,no-LIVE)"

    companion object {
        fun fromRegistered(
            registration: ComplaintTestNamespaceRegistrationV1,
            ownership: PersistencePhaseOwnership,
            jdbc: JdbcTemplate,
        ): ComplaintTestBootstrapHttpCompositionV1 = ComplaintTestBootstrapHttpCompositionV1(registration, ownership, jdbc)

        /** Exact retained authorities only; no desired D/P, historical Completed, readiness flag or default bean. */
        fun fromRegisteredInitialCheckpointCreate(
            registration: ComplaintTestNamespaceRegistrationV1,
            assembly: ComplaintTestProcessAssemblyV1,
            ownership: PersistencePhaseOwnership,
            jdbc: JdbcTemplate,
            audit: AuditService,
        ): ComplaintTestBootstrapHttpCompositionV1 = ComplaintTestBootstrapHttpCompositionV1(registration, ownership, jdbc, assembly, audit)
    }
}
