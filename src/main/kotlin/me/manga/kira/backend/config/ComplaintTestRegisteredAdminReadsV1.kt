package me.manga.kira.backend.config

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.api.ComplaintAdminReadHttpHandler
import me.manga.kira.backend.complaint.api.ComplaintAdminReadResponses
import me.manga.kira.backend.complaint.api.ComplaintOwnerHistoryResponses
import me.manga.kira.backend.complaint.application.ComplaintAdminReadService
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadAuthentication
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadFailure
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadPort
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadQuery
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadRequestContext
import me.manga.kira.backend.complaint.domain.ComplaintIdentifiers
import me.manga.kira.backend.complaint.domain.ComplaintValidationException
import me.manga.kira.backend.complaint.domain.rejectAdminRead
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminReadAdapter
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintAdminReadStore
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestDeploymentFailureV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestProcessAssemblyV1
import me.manga.kira.backend.complaint.infrastructure.admission.requireTestDeployment
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintAdminReadPhaseExecutor
import me.manga.kira.backend.security.ComplaintAdminReadAdmissionPolicy
import me.manga.kira.backend.security.ComplaintSecurityFailure
import me.manga.kira.backend.security.ComplaintSecurityResponses
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.JwtDecoder

/**
 * Fixed TEST read cohort on the original registered ordinary pair. No Spring bean, mode switch,
 * producer rewrite, credential material, standalone startup or replacement current-state grant.
 * Only the fixed startup resolves its normal-user decoder, once from its configured Spring context.
 */
internal class ComplaintTestRegisteredAdminReadsV1 private constructor(
    private val registration: ComplaintTestNamespaceRegistrationV1,
    private val assembly: ComplaintTestProcessAssemblyV1,
    private val ownership: PersistencePhaseOwnership,
    private val jdbc: JdbcTemplate,
    userDecoder: JwtDecoder,
    responses: ComplaintOwnerHistoryResponses,
) {
    init {
        requireCurrent()
        requireTestDeployment(registration.process.initialCheckpointCreate != null &&
            registration.process.consumers.adminReadPolicy is ComplaintAdminReadAdmissionPolicy.Bounded &&
            registration.process.consumers.adminCursorCodec != null, ComplaintTestDeploymentFailureV1.PROCESS_REFUSED)
    }

    private val consumers = registration.process.consumers
    private val scope = registration.process.desiredSettings().scope
    private val reader = ComplaintAdminReadAdapter(scope, userDecoder, checkNotNull(consumers.adminCursorCodec),
        ComplaintAdminReadPhaseExecutor(ownership, JdbcComplaintAdminReadStore(jdbc, scope)), consumers.ingressAdmission,
        checkNotNull(consumers.jwt.boundUserKeyProvider).versionBoundClockSkew)

    private val handler = ComplaintAdminReadHttpHandler(ComplaintAdminReadService(object : ComplaintAdminReadPort {
        override fun authenticate(context: ComplaintAdminReadRequestContext, bearer: String, query: ComplaintAdminReadQuery) =
            originalUse { reader.authenticate(context, bearer, query) }

        override fun read(context: ComplaintAdminReadRequestContext, authentication: ComplaintAdminReadAuthentication) =
            originalUse { reader.read(context, authentication) }
    }), consumers.ingressAdmission, ComplaintAdminReadResponses(responses))

    val mappedPaths: Set<String> = setOf(SEARCH, STATS, "$DETAIL{id}")

    /** Raw exact method/path only. The real handler still owns scope/query/header/body parsing and rejection. */
    @Suppress("SwallowedException")
    fun mapsRequest(request: HttpServletRequest): Boolean {
        val uri = request.requestURI
        val root = request.contextPath
        if (request.method == "POST") return uri == root + SEARCH
        if (request.method != "GET") return false
        if (uri == root + STATS) return true
        val prefix = root + DETAIL
        if (!uri.startsWith(prefix)) return false
        return try {
            ComplaintIdentifiers.resourceId(uri.substring(prefix.length))
            true
        } catch (failure: ComplaintValidationException) {
            false
        }
    }

    /** The existing handler owns one real ingress and completes here, never generic/security/MVC fallthrough. */
    @Suppress("SwallowedException")
    fun handleRequest(request: HttpServletRequest, response: HttpServletResponse) {
        if (!mapsRequest(request)) {
            ComplaintSecurityResponses.problem(request, response, ComplaintSecurityFailure.NOT_FOUND)
            return
        }
        try {
            requireCurrent() // Before body acquisition as well as around both actual authenticated read phases.
        } catch (failure: ComplaintTestNamespaceRegistrationExceptionV1) {
            ComplaintSecurityResponses.problem(request, response, ComplaintSecurityFailure.UNAVAILABLE)
            return
        }
        handler.handleRequest(request, response)
    }

    private fun requireCurrent() {
        requireConnectionFree()
        registration.requireActiveIdentityTarget(assembly)
        registration.requireIdentityAdmissionPhaseResources(ownership, jdbc)
    }

    @Suppress("SwallowedException")
    private fun <T> originalUse(operation: () -> T): T = try {
        requireCurrent()
        operation().also { requireCurrent() }
    } catch (failure: ComplaintTestNamespaceRegistrationExceptionV1) {
        rejectAdminRead(ComplaintAdminReadFailure.UNAVAILABLE)
    }

    override fun toString(): String = "ComplaintTestRegisteredAdminReadsV1(original-registered-TEST-only,redacted)"

    companion object {
        private const val SEARCH = "/api/v1/admin/complaints/search"
        private const val STATS = "/api/v1/admin/complaints/stats"
        private const val DETAIL = "/api/v1/admin/complaints/"

        internal fun fromRegistered(registration: ComplaintTestNamespaceRegistrationV1, assembly: ComplaintTestProcessAssemblyV1,
            ownership: PersistencePhaseOwnership, jdbc: JdbcTemplate, startup: ComplaintTestRegisteredHttpStartupV1,
            userDecoder: JwtDecoder, responses: ComplaintOwnerHistoryResponses): ComplaintTestRegisteredAdminReadsV1 {
            startup.claimAdminReadComposition(registration, assembly, ownership, jdbc, userDecoder)
            return ComplaintTestRegisteredAdminReadsV1(registration, assembly, ownership, jdbc, userDecoder, responses)
        }
    }
}
