package me.manga.kira.backend.config

import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.web.DisabledComplaintRoutesFilter
import me.manga.kira.backend.complaint.api.ComplaintInstallationBootstrapHttpHandler
import me.manga.kira.backend.complaint.application.ComplaintInstallationBootstrapService
import me.manga.kira.backend.complaint.infrastructure.ComplaintInstallationBootstrapReadAdapter
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationV1
import me.manga.kira.backend.security.ComplaintHttpIngressBridge
import me.manga.kira.backend.security.ComplaintInstallationSecurityChainFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.web.SecurityFilterChain

/**
 * Explicit same-process TEST assembly, never constructed from properties or a diagnostic assessment.
 * Registration is privately issued after actual first PROJECT and target readback. Every response
 * additionally needs its current owned read; retaining this composition does not cache that result.
 * No LIVE/restart/quarantine assembly and no other complaint producer is selected here.
 */
internal class ComplaintTestBootstrapHttpCompositionV1 private constructor(
    registration: ComplaintTestNamespaceRegistrationV1,
    ownership: PersistencePhaseOwnership,
    jdbc: JdbcTemplate,
) {
    private val producer = ComplaintInstallationBootstrapHttpHandler(
        ComplaintInstallationBootstrapService(ComplaintInstallationBootstrapReadAdapter(registration, ownership, jdbc)),
        registration.process.consumers.ingressAdmission,
    )
    private val bridge = ComplaintHttpIngressBridge(registration.process.consumers.ingressAdmission)
    private val factory = ComplaintInstallationSecurityChainFactory.bootstrapOnly(bridge, producer)
    private val disabled = DisabledComplaintRoutesFilter()

    /** Original admission surrounds the fixed bodyless check, generic body guard, Spring and MVC. */
    val ingressFilter: Filter = Filter { request, response, chain ->
        val http = request as HttpServletRequest
        if (http.requestURI != http.contextPath + ComplaintInstallationBootstrapHttpHandler.PATH) {
            disabled.doFilter(request, response, chain) // Includes aliases and every unimplemented/admin complaint route.
        } else {
            bridge.doFilter(request, response, FilterChain { admitted, output ->
                val selected = admitted as HttpServletRequest
                if (producer.validateWithinIngress(selected, output as HttpServletResponse, bridge.authenticationContext(selected))) {
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
    }
}
