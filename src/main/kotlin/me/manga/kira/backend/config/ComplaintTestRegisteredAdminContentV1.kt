package me.manga.kira.backend.config

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.api.ComplaintAdminContentHttpHandler
import me.manga.kira.backend.complaint.api.ComplaintAdminContentResponses
import me.manga.kira.backend.complaint.api.ComplaintAdminStepUpHttpHandler
import me.manga.kira.backend.complaint.api.ComplaintAdminStepUpHttpPort
import me.manga.kira.backend.complaint.api.ComplaintOwnerHistoryResponses
import me.manga.kira.backend.complaint.application.ComplaintAdminContentService
import me.manga.kira.backend.complaint.domain.ComplaintAdminContentFailure
import me.manga.kira.backend.complaint.domain.ComplaintAdminContentInput
import me.manga.kira.backend.complaint.domain.ComplaintAdminContentPort
import me.manga.kira.backend.complaint.domain.ComplaintAdminContentRequestContext
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadFailure
import me.manga.kira.backend.complaint.domain.ComplaintIdentifiers
import me.manga.kira.backend.complaint.domain.ComplaintValidationException
import me.manga.kira.backend.complaint.domain.rejectAdminContent
import me.manga.kira.backend.complaint.domain.rejectAdminRead
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminContentAdapter
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminJwtIdentityDecoder
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintAdminContentStore
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestProcessAssemblyV1
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintAdminContentPhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintGrantCleanupPhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.OrdinaryPersistencePhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ScopedAdminStepUpPhaseExecutor
import me.manga.kira.backend.security.ComplaintIngressContext
import me.manga.kira.backend.security.ComplaintSecurityFailure
import me.manga.kira.backend.security.ComplaintSecurityResponses
import me.manga.kira.backend.security.IssuedScopedAdminStepUp
import me.manga.kira.backend.security.JdbcComplaintGrantCleanupStore
import me.manga.kira.backend.security.JdbcScopedAdminStepUpStore
import me.manga.kira.backend.security.JdbcSourceGrantCleanupStore
import me.manga.kira.backend.security.ScopedAdminStepUpIssuer
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.jwt.JwtDecoder

/** Fixed registered TEST graph. No bean scan, alternate ingress/provider, seeded grant or caller-authority callback. */
internal class ComplaintTestRegisteredAdminContentV1 private constructor(
    private val registration: ComplaintTestNamespaceRegistrationV1,
    private val assembly: ComplaintTestProcessAssemblyV1,
    private val ownership: PersistencePhaseOwnership,
    private val jdbc: JdbcTemplate,
    audit: AuditService,
    userDecoder: JwtDecoder,
    passwordEncoder: PasswordEncoder,
    responses: ComplaintOwnerHistoryResponses,
) {
    private val consumers = registration.process.consumers
    private val scope = registration.process.desiredSettings().scope
    private val ingress = consumers.ingressAdmission
    private val stepUpSettings = checkNotNull(consumers.adminStepUp)
    private val clockSkew = checkNotNull(consumers.jwt.boundUserKeyProvider).versionBoundClockSkew
    private val store = JdbcComplaintAdminContentStore.registeredInitialCheckpoint(jdbc, audit, ownership, registration, assembly)
    private val current = checkNotNull(store.registeredCurrent)
    private val phases = ComplaintAdminContentPhaseExecutor(ownership, store)
    private val writer = ComplaintAdminContentAdapter(scope, userDecoder, phases, ingress, clockSkew)
    private val identities = ComplaintAdminJwtIdentityDecoder(scope, userDecoder, clockSkew)
    private val capacity = JdbcComplaintCapacityStore(jdbc, consumers.capacityPolicy.digestBytes())
    private val issuer = ScopedAdminStepUpIssuer(
        ScopedAdminStepUpPhaseExecutor(
            ownership, JdbcScopedAdminStepUpStore(jdbc, capacity, stepUpSettings.clock, stepUpSettings.properties),
            OrdinaryPersistencePhaseExecutor(ownership, JdbcSourceGrantCleanupStore(jdbc), stepUpSettings.clock),
            ComplaintGrantCleanupPhaseExecutor(ownership, JdbcComplaintGrantCleanupStore(jdbc, capacity), stepUpSettings.clock),
            current,
        ),
        passwordEncoder, stepUpSettings.throttle,
    )

    private val contentHandler = ComplaintAdminContentHttpHandler(ComplaintAdminContentService(object : ComplaintAdminContentPort {
        @Suppress("SwallowedException")
        override fun edit(context: ComplaintAdminContentRequestContext, bearer: String, proof: String?, input: ComplaintAdminContentInput) = try {
            requireCurrent()
            writer.edit(context, bearer, proof, input).also { requireCurrent() }
        } catch (failure: ComplaintTestNamespaceRegistrationExceptionV1) {
            rejectAdminContent(ComplaintAdminContentFailure.UNAVAILABLE)
        }
    }), ingress, ComplaintAdminContentResponses(responses))

    private val stepUpHandler = ComplaintAdminStepUpHttpHandler(object : ComplaintAdminStepUpHttpPort {
        @Suppress("SwallowedException")
        override fun issue(context: ComplaintIngressContext, bearer: String, password: String, clientIp: String): IssuedScopedAdminStepUp = try {
            requireCurrent()
            ingress.requireLiveContext(context)
            val identity = identities.decode(bearer)
            val authentication = phases.authenticate(identity)
            if (!authentication.contractValid) rejectAdminRead(ComplaintAdminReadFailure.INTERNAL)
            authentication.verdict.failure?.let(::rejectAdminRead)
            val attempt = current.beginStepUp(identity)
            try {
                // Real released snapshot -> connection-free throttle/password -> real cleanup -> counted short issuance.
                issuer.issueComplaint(identity.actor, password, clientIp).also { requireCurrent() }
            } finally {
                current.endStepUp(attempt)
            }
        } catch (failure: ComplaintTestNamespaceRegistrationExceptionV1) {
            rejectAdminRead(ComplaintAdminReadFailure.UNAVAILABLE)
        } catch (failure: PersistencePhaseException) {
            rejectAdminRead(ComplaintAdminReadFailure.UNAVAILABLE)
        }
    }, ingress, consumers.clientIpResolver, responses)

    val mappedPaths: Set<String> = setOf(ComplaintAdminStepUpHttpHandler.PATH, "${PREFIX}{id}$SUFFIX")

    @Suppress("SwallowedException")
    fun mapsRequest(request: HttpServletRequest): Boolean {
        val uri = request.requestURI
        if (request.method == "POST") return uri == request.contextPath + ComplaintAdminStepUpHttpHandler.PATH
        val prefix = request.contextPath + PREFIX
        if (request.method != "PATCH" || !uri.startsWith(prefix) || !uri.endsWith(SUFFIX) || uri.length != prefix.length + 36 + SUFFIX.length) return false
        return try {
            ComplaintIdentifiers.resourceId(uri.substring(prefix.length, uri.length - SUFFIX.length))
            true
        } catch (failure: ComplaintValidationException) {
            false
        }
    }

    @Suppress("SwallowedException")
    fun handleRequest(request: HttpServletRequest, response: HttpServletResponse) {
        if (!mapsRequest(request)) {
            ComplaintSecurityResponses.problem(request, response, ComplaintSecurityFailure.NOT_FOUND)
            return
        }
        try {
            requireCurrent() // Original registration/pair, before body acquisition too.
        } catch (failure: ComplaintTestNamespaceRegistrationExceptionV1) {
            ComplaintSecurityResponses.problem(request, response, ComplaintSecurityFailure.UNAVAILABLE)
            return
        }
        if (request.method == "POST") stepUpHandler.handleRequest(request, response) else contentHandler.handleRequest(request, response)
    }

    private fun requireCurrent() {
        requireConnectionFree()
        registration.requireActiveIdentityTarget(assembly)
        registration.requireIdentityAdmissionPhaseResources(ownership, jdbc)
    }

    override fun toString(): String = "ComplaintTestRegisteredAdminContentV1(original-registered-TEST-only,redacted)"

    companion object {
        private const val PREFIX = "/api/v1/admin/complaints/"
        private const val SUFFIX = "/content"

        internal fun fromRegistered(registration: ComplaintTestNamespaceRegistrationV1, assembly: ComplaintTestProcessAssemblyV1,
            ownership: PersistencePhaseOwnership, jdbc: JdbcTemplate, audit: AuditService, startup: ComplaintTestRegisteredHttpStartupV1,
            userDecoder: JwtDecoder, passwordEncoder: PasswordEncoder, responses: ComplaintOwnerHistoryResponses): ComplaintTestRegisteredAdminContentV1 {
            startup.claimAdminContentComposition(registration, assembly, ownership, jdbc, userDecoder, passwordEncoder)
            return ComplaintTestRegisteredAdminContentV1(registration, assembly, ownership, jdbc, audit, userDecoder, passwordEncoder, responses)
        }
    }
}
