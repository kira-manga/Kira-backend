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
import me.manga.kira.backend.complaint.api.ComplaintAdminStatusHttpHandler
import me.manga.kira.backend.complaint.api.ComplaintAdminStatusResponses
import me.manga.kira.backend.complaint.api.ComplaintAdminBatchStatusHttpHandler
import me.manga.kira.backend.complaint.api.ComplaintAdminBatchStatusResponses
import me.manga.kira.backend.complaint.api.ComplaintOwnerHistoryResponses
import me.manga.kira.backend.complaint.application.ComplaintAdminContentService
import me.manga.kira.backend.complaint.application.ComplaintAdminStatusService
import me.manga.kira.backend.complaint.application.ComplaintAdminBatchStatusService
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchStatusInput
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchStatusPort
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchStatusRequestContext
import me.manga.kira.backend.complaint.domain.ComplaintAdminContentFailure
import me.manga.kira.backend.complaint.domain.ComplaintAdminContentInput
import me.manga.kira.backend.complaint.domain.ComplaintAdminContentPort
import me.manga.kira.backend.complaint.domain.ComplaintAdminContentRequestContext
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadFailure
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusFailure
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusInput
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusOperation
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusPort
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusRequestContext
import me.manga.kira.backend.complaint.domain.ComplaintIdentifiers
import me.manga.kira.backend.complaint.domain.ComplaintValidationException
import me.manga.kira.backend.complaint.domain.rejectAdminContent
import me.manga.kira.backend.complaint.domain.rejectAdminRead
import me.manga.kira.backend.complaint.domain.rejectAdminStatus
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminContentAdapter
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminStatusAdapter
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminBatchStatusAdapter
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminJwtIdentityDecoder
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintAdminContentStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintAdminStatusStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintAdminBatchStatusStore
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestProcessAssemblyV1
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintAdminContentPhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintAdminStatusPhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintAdminBatchStatusPhaseExecutor
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
    selectStatus: Boolean = false,
    selectBatchStatus: Boolean = false,
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

    // Built only by the separate explicit startup claim; the existing content-only recipe never maps these routes.
    private val statusHandler = if (selectStatus) {
        val statusStore = JdbcComplaintAdminStatusStore.registeredInitialCheckpoint(jdbc, audit, ownership, registration, assembly, current)
        val statusPhases = ComplaintAdminStatusPhaseExecutor(ownership, statusStore)
        val statusWriter = ComplaintAdminStatusAdapter(scope, userDecoder, statusPhases, ingress, clockSkew)
        ComplaintAdminStatusHttpHandler(ComplaintAdminStatusService(object : ComplaintAdminStatusPort {
            @Suppress("SwallowedException")
            override fun change(context: ComplaintAdminStatusRequestContext, bearer: String, proof: String?, input: ComplaintAdminStatusInput) = try {
                requireCurrent()
                statusWriter.change(context, bearer, proof, input).also { requireCurrent() }
            } catch (failure: ComplaintTestNamespaceRegistrationExceptionV1) {
                rejectAdminStatus(ComplaintAdminStatusFailure.UNAVAILABLE)
            }
        }), ingress, ComplaintAdminStatusResponses(responses))
    } else null

    private val batchStatusHandler = if (selectBatchStatus) {
        checkNotNull(statusHandler)
        val batchStore = JdbcComplaintAdminBatchStatusStore.registeredInitialCheckpoint(jdbc, audit, ownership, registration, assembly, current)
        val batchPhases = ComplaintAdminBatchStatusPhaseExecutor(ownership, batchStore)
        val batchWriter = ComplaintAdminBatchStatusAdapter(scope, userDecoder, batchPhases, ingress, clockSkew)
        ComplaintAdminBatchStatusHttpHandler(ComplaintAdminBatchStatusService(object : ComplaintAdminBatchStatusPort {
            @Suppress("SwallowedException")
            override fun change(context: ComplaintAdminBatchStatusRequestContext, bearer: String, proof: String?, input: ComplaintAdminBatchStatusInput) = try {
                requireCurrent()
                batchWriter.change(context, bearer, proof, input).also { requireCurrent() }
            } catch (failure: ComplaintTestNamespaceRegistrationExceptionV1) {
                rejectAdminStatus(ComplaintAdminStatusFailure.UNAVAILABLE)
            }
        }), ingress, ComplaintAdminBatchStatusResponses(responses))
    } else null

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

    private val mutationSuffixes = listOf(SUFFIX) + if (statusHandler == null) emptyList() else ComplaintAdminStatusOperation.entries.map { it.suffix }
    val mappedPaths: Set<String> = setOf(ComplaintAdminStepUpHttpHandler.PATH) + mutationSuffixes.map { "${PREFIX}{id}$it" } +
        (if (batchStatusHandler == null) emptySet() else setOf(BATCH_PATH))

    @Suppress("SwallowedException")
    fun mapsRequest(request: HttpServletRequest): Boolean {
        val uri = request.requestURI
        if (request.method == "POST") return uri == request.contextPath + ComplaintAdminStepUpHttpHandler.PATH ||
            batchStatusHandler != null && uri == request.contextPath + BATCH_PATH
        val prefix = request.contextPath + PREFIX
        if (request.method != "PATCH" || !uri.startsWith(prefix)) return false
        val suffix = mutationSuffixes.singleOrNull { uri.endsWith(it) && uri.length == prefix.length + 36 + it.length } ?: return false
        return try {
            ComplaintIdentifiers.resourceId(uri.substring(prefix.length, uri.length - suffix.length))
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
        when {
            request.method == "POST" && request.requestURI == request.contextPath + BATCH_PATH -> checkNotNull(batchStatusHandler).handleRequest(request, response)
            request.method == "POST" -> stepUpHandler.handleRequest(request, response)
            request.requestURI.endsWith(SUFFIX) -> contentHandler.handleRequest(request, response)
            else -> checkNotNull(statusHandler).handleRequest(request, response)
        }
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
        private const val BATCH_PATH = "${PREFIX}batch"

        internal fun fromRegistered(registration: ComplaintTestNamespaceRegistrationV1, assembly: ComplaintTestProcessAssemblyV1,
            ownership: PersistencePhaseOwnership, jdbc: JdbcTemplate, audit: AuditService, startup: ComplaintTestRegisteredHttpStartupV1,
            userDecoder: JwtDecoder, passwordEncoder: PasswordEncoder, responses: ComplaintOwnerHistoryResponses): ComplaintTestRegisteredAdminContentV1 {
            startup.claimAdminContentComposition(registration, assembly, ownership, jdbc, userDecoder, passwordEncoder)
            return ComplaintTestRegisteredAdminContentV1(registration, assembly, ownership, jdbc, audit, userDecoder, passwordEncoder, responses)
        }

        internal fun fromRegisteredWithStatus(registration: ComplaintTestNamespaceRegistrationV1, assembly: ComplaintTestProcessAssemblyV1,
            ownership: PersistencePhaseOwnership, jdbc: JdbcTemplate, audit: AuditService, startup: ComplaintTestRegisteredHttpStartupV1,
            userDecoder: JwtDecoder, passwordEncoder: PasswordEncoder, responses: ComplaintOwnerHistoryResponses): ComplaintTestRegisteredAdminContentV1 {
            startup.claimAdminContentComposition(registration, assembly, ownership, jdbc, userDecoder, passwordEncoder)
            startup.claimAdminStatusComposition(registration, assembly, ownership, jdbc, userDecoder, passwordEncoder)
            return ComplaintTestRegisteredAdminContentV1(registration, assembly, ownership, jdbc, audit, userDecoder, passwordEncoder, responses, selectStatus = true)
        }

        internal fun fromRegisteredWithStatusAndBatchStatus(registration: ComplaintTestNamespaceRegistrationV1, assembly: ComplaintTestProcessAssemblyV1,
            ownership: PersistencePhaseOwnership, jdbc: JdbcTemplate, audit: AuditService, startup: ComplaintTestRegisteredHttpStartupV1,
            userDecoder: JwtDecoder, passwordEncoder: PasswordEncoder, responses: ComplaintOwnerHistoryResponses): ComplaintTestRegisteredAdminContentV1 {
            startup.claimAdminContentComposition(registration, assembly, ownership, jdbc, userDecoder, passwordEncoder)
            startup.claimAdminStatusComposition(registration, assembly, ownership, jdbc, userDecoder, passwordEncoder)
            startup.claimAdminBatchStatusComposition(registration, assembly, ownership, jdbc, userDecoder, passwordEncoder)
            return ComplaintTestRegisteredAdminContentV1(registration, assembly, ownership, jdbc, audit, userDecoder, passwordEncoder, responses,
                selectStatus = true, selectBatchStatus = true)
        }
    }
}
