package me.manga.kira.backend.config

import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.audit.domain.ComplaintInstallationEnrollmentAudit
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.common.web.DisabledComplaintRoutesFilter
import me.manga.kira.backend.complaint.api.ComplaintAdminStepUpHttpHandler
import me.manga.kira.backend.complaint.api.ComplaintInstallationBootstrapHttpHandler
import me.manga.kira.backend.complaint.api.ComplaintInstallationHttpHandler
import me.manga.kira.backend.complaint.api.ComplaintInstallationMeHttpHandler
import me.manga.kira.backend.complaint.api.ComplaintOwnerCreateHttpHandler
import me.manga.kira.backend.complaint.api.ComplaintOwnerDeleteAllHttpHandler
import me.manga.kira.backend.complaint.api.ComplaintOwnerDeleteHttpHandler
import me.manga.kira.backend.complaint.api.ComplaintOwnerDetailHttpHandler
import me.manga.kira.backend.complaint.api.ComplaintOwnerEditHttpHandler
import me.manga.kira.backend.complaint.api.ComplaintOwnerOperationResponse
import me.manga.kira.backend.complaint.api.ComplaintOwnerHistoryHttpHandler
import me.manga.kira.backend.complaint.api.ComplaintOwnerHistoryResponses
import me.manga.kira.backend.complaint.application.ComplaintInstallationBootstrapService
import me.manga.kira.backend.complaint.application.ComplaintInstallationMeService
import me.manga.kira.backend.complaint.application.ComplaintInstallationService
import me.manga.kira.backend.complaint.application.ComplaintOwnerCreateService
import me.manga.kira.backend.complaint.application.ComplaintOwnerDeleteAllService
import me.manga.kira.backend.complaint.application.ComplaintOwnerDeleteService
import me.manga.kira.backend.complaint.application.ComplaintOwnerDetailService
import me.manga.kira.backend.complaint.application.ComplaintOwnerEditService
import me.manga.kira.backend.complaint.application.ComplaintOwnerHistoryService
import me.manga.kira.backend.complaint.domain.ComplaintInstallationMeAuthentication
import me.manga.kira.backend.complaint.domain.ComplaintInstallationMeFailure
import me.manga.kira.backend.complaint.domain.ComplaintInstallationMeReadPort
import me.manga.kira.backend.complaint.domain.ComplaintInstallationRequestContext
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDetailAuthentication
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDetailFailure
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDetailReadPort
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDetailRequestContext
import me.manga.kira.backend.complaint.domain.ComplaintOwnerHistoryAuthentication
import me.manga.kira.backend.complaint.domain.ComplaintOwnerHistoryFailure
import me.manga.kira.backend.complaint.domain.ComplaintOwnerHistoryQuery
import me.manga.kira.backend.complaint.domain.ComplaintOwnerHistoryReadPort
import me.manga.kira.backend.complaint.domain.ComplaintOwnerHistoryRequestContext
import me.manga.kira.backend.complaint.domain.rejectInstallationMe
import me.manga.kira.backend.complaint.domain.rejectOwnerDetail
import me.manga.kira.backend.complaint.domain.rejectOwnerHistory
import me.manga.kira.backend.complaint.infrastructure.ComplaintInstallationBearerAuthenticator
import me.manga.kira.backend.complaint.infrastructure.ComplaintInstallationBootstrapReadAdapter
import me.manga.kira.backend.complaint.infrastructure.ComplaintInstallationExchangeAdapter
import me.manga.kira.backend.complaint.infrastructure.ComplaintInstallationMeReadAdapter
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerCreateAdapter
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerEditAdapter
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDetailReadAdapter
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerHistoryReadAdapter
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerCreateStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerEditStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDetailStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerHistoryStore
import me.manga.kira.backend.complaint.infrastructure.TestOwnerDeleteProcessBindingV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestProcessAssemblyV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestAdminDeleteJournalPublisherFactoryV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestOwnerDeleteAllJournalPublisherFactoryV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestOwnerDeleteJournalPublisherFactoryV1
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerCreatePhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerEditPhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerDetailPhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerHistoryPhaseExecutor
import me.manga.kira.backend.security.ComplaintHttpIngressBridge
import me.manga.kira.backend.security.ComplaintInstallationRoutes
import me.manga.kira.backend.security.ComplaintInstallationSecurityChainFactory
import me.manga.kira.backend.security.ComplaintSecurityFailure
import me.manga.kira.backend.security.ComplaintSecurityRejected
import me.manga.kira.backend.security.ComplaintSecurityResponses
import me.manga.kira.backend.security.InstallationJwtCodec
import org.springframework.http.server.RequestPath
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.web.util.pattern.PathPatternParser
import java.time.Clock
import java.util.UUID

/**
 * Explicit same-process TEST assembly, never constructed from properties or a diagnostic assessment.
 * Registration is privately issued after actual first PROJECT and target readback. Every response
 * additionally needs its current owned read; retaining this composition does not cache that result.
 * Bootstrap-only remains the default. The separate explicit initial-checkpoint factory selects only
 * registered identity exchange and current CREATE/status. A further explicit factory adds the two
 * existing owner reads only. A separate born-with reply selection adds only OWNER_REPLY;
 * a further explicit EDIT selection retains its own operation boundary.
 * An explicit read/CREATE/me sibling adds only the existing installation projection.
 * A further explicit me/REPLY/EDIT selection combines that projection with the existing owner cohort.
 * A separate born-with Admin read sibling adds its fixed normal-ADMIN search/detail/stats cohort.
 * A separate registered owner-deletion sibling selects read/CREATE plus DELETE/status, never direct APPLY.
 * A further explicit sibling adds body-secret owner-delete-all with the same original deletion owners.
 * One explicit complete listener combines the existing owner/Admin families on those original owners.
 * No LIVE/restart/quarantine or broad Core; all earlier selectors remain narrower.
 */
internal class ComplaintTestBootstrapHttpCompositionV1 private constructor(
    registration: ComplaintTestNamespaceRegistrationV1,
    ownership: PersistencePhaseOwnership,
    jdbc: JdbcTemplate,
    assembly: ComplaintTestProcessAssemblyV1? = null,
    audit: AuditService? = null,
    private val reads: RegisteredOwnerReads? = null,
    private val replyStore: JdbcComplaintOwnerCreateStore? = null,
    private val editStore: JdbcComplaintOwnerEditStore? = null,
    private val me: ComplaintInstallationMeHttpHandler? = null,
    private val adminReads: ComplaintTestRegisteredAdminReadsV1? = null,
    private val adminContent: ComplaintTestRegisteredAdminContentV1? = null,
    private val ownerDelete: TestOwnerDeleteProcessBindingV1.OwnerHttpResources? = null,
    private val ownerDeletePublisher: TestOwnerDeleteJournalPublisherFactoryV1? = null,
    private val ownerDeleteAll: TestOwnerDeleteProcessBindingV1.OwnerDeleteAllHttpResources? = null,
    private val ownerDeleteAllPublisher: TestOwnerDeleteAllJournalPublisherFactoryV1? = null,
    private val completeOwnerAdmin: Boolean = false,
) {
    init {
        require((assembly == null) == (audit == null))
        require(reads == null || assembly != null)
        require(replyStore == null || reads != null)
        require(editStore == null || replyStore != null)
        require(me == null || (reads != null &&
            ((replyStore == null && editStore == null) || (replyStore != null && editStore != null))))
        require(adminReads == null || (reads != null && (completeOwnerAdmin || (replyStore == null && editStore == null && me == null))))
        require(adminContent == null || adminReads != null)
        require((ownerDelete == null) == (ownerDeletePublisher == null))
        require(ownerDelete == null || (reads != null && (completeOwnerAdmin ||
            (replyStore == null && editStore == null && me == null && adminReads == null && adminContent == null))))
        require((ownerDeleteAll == null) == (ownerDeleteAllPublisher == null))
        require(ownerDeleteAll == null || ownerDelete != null)
        require(!completeOwnerAdmin || (reads != null && replyStore != null && editStore != null && me != null &&
            adminReads != null && adminContent != null && ownerDelete != null && ownerDeleteAll != null))
    }

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
        val jwt = reads?.jwt ?: InstallationJwtCodec(registration.process.consumers.jwt.installationKeyRing, Clock.systemUTC())
        val store = replyStore ?: JdbcComplaintOwnerCreateStore.registeredInitialCheckpoint(jdbc, selectedAudit, ownership, registration, assembly)
        val responses = ComplaintOwnerOperationResponse()
        val edit = editStore?.let {
            ComplaintOwnerEditHttpHandler(ComplaintOwnerEditService(ComplaintOwnerEditAdapter(scope, jwt,
                ComplaintOwnerEditPhaseExecutor(ownership, it), ingress)), ingress, responses)
        }
        val delete = ownerDelete?.let {
            ComplaintOwnerDeleteHttpHandler(ComplaintOwnerDeleteService(it.adapter(registration, assembly, ownership, jdbc, jwt,
                checkNotNull(ownerDeletePublisher))), ingress, responses)
        }
        val deleteAll = ownerDeleteAll?.let {
            ComplaintOwnerDeleteAllHttpHandler(ComplaintOwnerDeleteAllService(it.adapter(registration, assembly, ownership, jdbc,
                checkNotNull(ownerDelete), checkNotNull(ownerDeleteAllPublisher))), ingress)
        }
        val create = ComplaintOwnerCreateHttpHandler(
            ComplaintOwnerCreateService(ComplaintOwnerCreateAdapter(scope, jwt, ComplaintOwnerCreatePhaseExecutor(ownership, store), ingress)), ingress, responses,
            deleteStatus = delete, editStatus = edit,
        )
        val enrollmentAudit = ComplaintInstallationEnrollmentAudit { selectedScope, allocation, at ->
            selectedAudit.recordInstallationEnrollment(selectedScope, allocation, at)
        }
        val installations = ComplaintInstallationHttpHandler(
            ComplaintInstallationService(ComplaintInstallationExchangeAdapter(registration, ownership, jdbc, enrollmentAudit)), ingress,
        )
        // AUTH remains early rejection only. Direct CREATE constructs no read producer/handler.
        val authentication = ComplaintInstallationBearerAuthenticator(scope, jwt,
            reads?.authenticationPhases ?: ComplaintOwnerHistoryPhaseExecutor(ownership, JdbcComplaintOwnerHistoryStore(jdbc, scope)), ingress)
        if (completeOwnerAdmin) {
            val selectedReads = checkNotNull(reads)
            ComplaintInstallationSecurityChainFactory.registeredCompleteOwnerSubset(
                bridge, producer, authentication, installations, create, selectedReads.history, selectedReads.detail,
                checkNotNull(me), checkNotNull(edit), checkNotNull(delete), checkNotNull(deleteAll))
        } else if (deleteAll != null) {
            val selectedReads = checkNotNull(reads)
            ComplaintInstallationSecurityChainFactory.registeredReadCreateOwnerDeleteAllSubset(
                bridge, producer, authentication, installations, create, selectedReads.history, selectedReads.detail, checkNotNull(delete), deleteAll)
        } else if (delete != null) {
            val selectedReads = checkNotNull(reads)
            ComplaintInstallationSecurityChainFactory.registeredReadCreateOwnerDeleteSubset(
                bridge, producer, authentication, installations, create, selectedReads.history, selectedReads.detail, delete)
        } else if (me != null && edit != null) {
            val selectedReads = checkNotNull(reads)
            ComplaintInstallationSecurityChainFactory.registeredReadCreateMeReplyEditSubset(
                bridge, producer, authentication, installations, create, selectedReads.history, selectedReads.detail, me, edit)
        } else if (me != null) {
            val selectedReads = checkNotNull(reads)
            ComplaintInstallationSecurityChainFactory.registeredReadCreateMeSubset(
                bridge, producer, authentication, installations, create, selectedReads.history, selectedReads.detail, me)
        } else if (edit != null) {
            val selectedReads = checkNotNull(reads)
            ComplaintInstallationSecurityChainFactory.registeredReadCreateReplyEditSubset(
                bridge, producer, authentication, installations, create, selectedReads.history, selectedReads.detail, edit)
        } else if (replyStore != null) {
            val selectedReads = checkNotNull(reads)
            ComplaintInstallationSecurityChainFactory.registeredReadCreateReplySubset(
                bridge, producer, authentication, installations, create, selectedReads.history, selectedReads.detail)
        } else if (reads == null) ComplaintInstallationSecurityChainFactory.registeredCreateSubset(bridge, producer, authentication, installations, create)
        else ComplaintInstallationSecurityChainFactory.registeredReadCreateSubset(bridge, producer, authentication, installations, create, reads.history, reads.detail)
    }
    private val disabled = DisabledComplaintRoutesFilter()
    private val sharedStepUpPath = PathPatternParser().parse(ComplaintAdminStepUpHttpHandler.PATH)

    private val literalPaths: Set<String> = (if (assembly == null) setOf(ComplaintInstallationRoutes.BOOTSTRAP) else setOf(
        ComplaintInstallationRoutes.BOOTSTRAP, ComplaintInstallationRoutes.ENROLLMENT, ComplaintInstallationRoutes.SESSION,
        ComplaintInstallationRoutes.HISTORY, ComplaintInstallationRoutes.STATUS,
    )) + (if (me == null) emptySet() else setOf(ComplaintInstallationRoutes.ME)) +
        (if (ownerDeleteAll == null) emptySet() else setOf(ComplaintInstallationRoutes.DELETE_ALL))

    /** MVC templates do not grant ingress. Both pre-buffer guards use the concrete method/path predicates below. */
    val mappedPaths: Set<String> = literalPaths +
        (if (reads == null) emptySet() else setOf("${ComplaintInstallationRoutes.HISTORY}/{id}")) +
        (if (replyStore == null) emptySet() else setOf("${ComplaintInstallationRoutes.HISTORY}/{id}/replies")) +
        (if (editStore == null) emptySet() else setOf("${ComplaintInstallationRoutes.HISTORY}/{id}/content")) +
        (adminReads?.mappedPaths ?: emptySet()) + (adminContent?.mappedPaths ?: emptySet())

    // The complete filter alone classifies the shared step-up POST. A SOURCE continuation must
    // reach the existing MVC controller, not the complaint factory's earlier template mapping.
    internal val mvcMappedPaths: Set<String> = if (completeOwnerAdmin) mappedPaths - ComplaintAdminStepUpHttpHandler.PATH else mappedPaths

    internal fun mapsRequest(request: HttpServletRequest): Boolean = ComplaintInstallationRoutes.path(request) in literalPaths ||
        (reads != null && request.method == "GET" && ComplaintInstallationRoutes.isDetail(request)) ||
        (ownerDelete != null && request.method == "DELETE" && ComplaintInstallationRoutes.isDetail(request)) ||
        (replyStore != null && request.method == "POST" && ComplaintInstallationRoutes.isReply(request)) ||
        (editStore != null && request.method == "PATCH" && ComplaintInstallationRoutes.isContent(request)) ||
        adminReads?.mapsRequest(request) == true || adminContent?.mapsRequest(request) == true

    /** Owner admission surrounds generic/Spring/MVC; selected Admin reads finish in their own original-ingress handler. */
    val ingressFilter: Filter = Filter { request, response, chain ->
        val http = request as HttpServletRequest
        val path = ComplaintInstallationRoutes.path(http)
        if (completeOwnerAdmin && matchesSharedStepUp(http)) {
            // MVC's decoded aliases must not reach the normal controller with an unclassified
            // COMPLAINT body. Only the exact existing wire POST gets the single bounded codec.
            if (http.method == "POST" && path == ComplaintAdminStepUpHttpHandler.PATH) {
                checkNotNull(adminContent).handleSharedStepUp(http, response as HttpServletResponse, chain)
            } else ComplaintSecurityResponses.problem(http, response as HttpServletResponse, ComplaintSecurityFailure.NOT_FOUND)
        } else if (adminContent?.mapsRequest(http) == true) {
            adminContent.handleRequest(http, response as HttpServletResponse)
        } else if (adminReads?.mapsRequest(http) == true) {
            // This exact existing handler owns original ingress + input bounds + real normal-JWT/current-ADMIN SQL.
            // It finishes here, before generic buffering/user converter; it never falls through unauthenticated.
            adminReads.handleRequest(http, response as HttpServletResponse)
        } else if (!mapsRequest(http) || (path != ComplaintInstallationRoutes.BOOTSTRAP && !factory.implemented(http))) {
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
                if ((selected.method == "GET" && (path == ComplaintInstallationRoutes.ME ||
                        path == ComplaintInstallationRoutes.HISTORY || ComplaintInstallationRoutes.isDetail(selected))) ||
                    (ownerDelete != null && selected.method == "DELETE" && ComplaintInstallationRoutes.isDetail(selected)) ||
                    (ownerDeleteAll != null && selected.method == "POST" && path == ComplaintInstallationRoutes.DELETE_ALL) ||
                    (replyStore != null && selected.method == "POST" && ComplaintInstallationRoutes.isReply(selected)) ||
                    (editStore != null && selected.method == "PATCH" && ComplaintInstallationRoutes.isContent(selected))) {
                    reads?.requireWithinIngress()
                }
                if ((path != ComplaintInstallationRoutes.BOOTSTRAP ||
                    producer.validateWithinIngress(selected, output as HttpServletResponse, bridge.authenticationContext(selected))) &&
                    factory.validateDetailWithinIngress(selected, output as HttpServletResponse)) {
                    chain.doFilter(admitted, output)
                }
            })
        }
    }

    @Suppress("SwallowedException")
    private fun matchesSharedStepUp(request: HttpServletRequest): Boolean = try {
        sharedStepUpPath.matches(RequestPath.parse(request.requestURI, request.contextPath).pathWithinApplication())
    } catch (failure: IllegalArgumentException) {
        false // The existing container/firewall owns globally malformed paths.
    }

    val handler = factory.handler // Fixed dispatcher claims this same ingress once; never map the standalone producer.

    fun securityChain(http: HttpSecurity): SecurityFilterChain = factory.build(http)

    override fun toString(): String = "ComplaintTestBootstrapHttpCompositionV1(registered-TEST-only,no-LIVE)"

    /**
     * Fixed existing read pair on the original registered ordinary owner. Lifetime checks surround
     * each reader AUTH/read; they issue no row/phase/admission result and acquire no provider keys.
     * Existing adapters still own current actor/run SQL, cursor scope, commit and physical release.
     */
    private class RegisteredOwnerReads(
        private val registration: ComplaintTestNamespaceRegistrationV1,
        private val assembly: ComplaintTestProcessAssemblyV1,
        private val ownership: PersistencePhaseOwnership,
        private val jdbc: JdbcTemplate,
        sharedResponses: ComplaintOwnerHistoryResponses? = null,
    ) {
        init { requireCurrent() }

        private val ingress = registration.process.consumers.ingressAdmission
        private val scope = registration.process.desiredSettings().scope
        val jwt = InstallationJwtCodec(registration.process.consumers.jwt.installationKeyRing, Clock.systemUTC())
        val authenticationPhases = ComplaintOwnerHistoryPhaseExecutor(ownership, JdbcComplaintOwnerHistoryStore(jdbc, scope))
        private val historyReader = ComplaintOwnerHistoryReadAdapter(scope, jwt, registration.process.consumers.ownerCursorCodec, authenticationPhases, ingress)
        private val detailReader = ComplaintOwnerDetailReadAdapter(scope, jwt, authenticationPhases,
            ComplaintOwnerDetailPhaseExecutor(ownership, JdbcComplaintOwnerDetailStore(jdbc, scope)), ingress)

        val history = ComplaintOwnerHistoryHttpHandler(ComplaintOwnerHistoryService(object : ComplaintOwnerHistoryReadPort {
            override fun authenticate(context: ComplaintOwnerHistoryRequestContext, bearer: String, query: ComplaintOwnerHistoryQuery) =
                historyUse { historyReader.authenticate(context, bearer, query) }
            override fun read(context: ComplaintOwnerHistoryRequestContext, authentication: ComplaintOwnerHistoryAuthentication) =
                historyUse { historyReader.read(context, authentication) }
        }), ingress, sharedResponses ?: ComplaintOwnerHistoryResponses())
        val detail = ComplaintOwnerDetailHttpHandler(ComplaintOwnerDetailService(object : ComplaintOwnerDetailReadPort {
            override fun authenticate(context: ComplaintOwnerDetailRequestContext, bearer: String, id: UUID) =
                detailUse { detailReader.authenticate(context, bearer, id) }
            override fun read(context: ComplaintOwnerDetailRequestContext, authentication: ComplaintOwnerDetailAuthentication) =
                detailUse { detailReader.read(context, authentication) }
        }), ingress, sharedResponses ?: ComplaintOwnerHistoryResponses())

        /** Constructed only by the explicit me selector; no new phase owner, SQL or mode authority. */
        fun installationMe(): ComplaintInstallationMeHttpHandler {
            val reader = ComplaintInstallationMeReadAdapter(scope, jwt, authenticationPhases, ingress)
            return ComplaintInstallationMeHttpHandler(ComplaintInstallationMeService(object : ComplaintInstallationMeReadPort {
                override fun authenticate(context: ComplaintInstallationRequestContext, bearer: String) =
                    installationUse { reader.authenticate(context, bearer) }
                override fun read(context: ComplaintInstallationRequestContext, authentication: ComplaintInstallationMeAuthentication) =
                    installationUse { reader.read(context, authentication) }
            }), ingress)
        }

        private fun requireCurrent() {
            requireConnectionFree()
            registration.requireActiveIdentityTarget(assembly)
            registration.requireIdentityAdmissionPhaseResources(ownership, jdbc) // Compare the already-bound pair; never rebind.
        }

        @Suppress("SwallowedException")
        fun requireWithinIngress() {
            try { requireCurrent() }
            catch (failure: ComplaintTestNamespaceRegistrationExceptionV1) { throw ComplaintSecurityRejected(ComplaintSecurityFailure.UNAVAILABLE) }
        }

        @Suppress("SwallowedException")
        private fun <T> historyUse(operation: () -> T): T = try {
            requireCurrent()
            operation().also { requireCurrent() }
        } catch (failure: ComplaintTestNamespaceRegistrationExceptionV1) {
            rejectOwnerHistory(ComplaintOwnerHistoryFailure.UNAVAILABLE)
        }

        @Suppress("SwallowedException")
        private fun <T> detailUse(operation: () -> T): T = try {
            requireCurrent()
            operation().also { requireCurrent() }
        } catch (failure: ComplaintTestNamespaceRegistrationExceptionV1) {
            rejectOwnerDetail(ComplaintOwnerDetailFailure.UNAVAILABLE)
        }

        @Suppress("SwallowedException")
        private fun <T> installationUse(operation: () -> T): T = try {
            requireCurrent()
            operation().also { requireCurrent() }
        } catch (failure: ComplaintTestNamespaceRegistrationExceptionV1) {
            rejectInstallationMe(ComplaintInstallationMeFailure.UNAVAILABLE)
        }
    }

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

        /** Separate concrete read + CREATE selection; the two earlier factories remain narrower. */
        fun fromRegisteredInitialCheckpointReadCreate(
            registration: ComplaintTestNamespaceRegistrationV1,
            assembly: ComplaintTestProcessAssemblyV1,
            ownership: PersistencePhaseOwnership,
            jdbc: JdbcTemplate,
            audit: AuditService,
        ): ComplaintTestBootstrapHttpCompositionV1 {
            registration.requireActiveIdentityTarget(assembly)
            registration.requireInstallationResources(ownership, jdbc)
            return ComplaintTestBootstrapHttpCompositionV1(registration, ownership, jdbc, assembly, audit,
                RegisteredOwnerReads(registration, assembly, ownership, jdbc))
        }

        /** Original deletion resources and publisher are retained by startup BEFORE constructing this fixed route cohort. */
        fun fromRegisteredInitialCheckpointReadCreateOwnerDelete(
            registration: ComplaintTestNamespaceRegistrationV1,
            assembly: ComplaintTestProcessAssemblyV1,
            ownership: PersistencePhaseOwnership,
            jdbc: JdbcTemplate,
            audit: AuditService,
            ownerResources: TestOwnerDeleteProcessBindingV1.OwnerHttpResources,
            publisher: TestOwnerDeleteJournalPublisherFactoryV1,
        ): ComplaintTestBootstrapHttpCompositionV1 {
            registration.requireActiveIdentityTarget(assembly)
            registration.requireIdentityAdmissionPhaseResources(ownership, jdbc)
            return ComplaintTestBootstrapHttpCompositionV1(registration, ownership, jdbc, assembly, audit,
                RegisteredOwnerReads(registration, assembly, ownership, jdbc), ownerDelete = ownerResources, ownerDeletePublisher = publisher)
        }

        /** Explicit ALL addition on the same original binding; both native factories are already retained by startup. */
        fun fromRegisteredInitialCheckpointReadCreateOwnerDeleteAll(
            registration: ComplaintTestNamespaceRegistrationV1,
            assembly: ComplaintTestProcessAssemblyV1,
            ownership: PersistencePhaseOwnership,
            jdbc: JdbcTemplate,
            audit: AuditService,
            ownerResources: TestOwnerDeleteProcessBindingV1.OwnerHttpResources,
            publisher: TestOwnerDeleteJournalPublisherFactoryV1,
            allResources: TestOwnerDeleteProcessBindingV1.OwnerDeleteAllHttpResources,
            allPublisher: TestOwnerDeleteAllJournalPublisherFactoryV1,
        ): ComplaintTestBootstrapHttpCompositionV1 {
            registration.requireActiveIdentityTarget(assembly)
            registration.requireIdentityAdmissionPhaseResources(ownership, jdbc)
            return ComplaintTestBootstrapHttpCompositionV1(registration, ownership, jdbc, assembly, audit,
                RegisteredOwnerReads(registration, assembly, ownership, jdbc), ownerDelete = ownerResources, ownerDeletePublisher = publisher,
                ownerDeleteAll = allResources, ownerDeleteAllPublisher = allPublisher)
        }

        /** One fixed listener with all existing born-with families, one response owner and the original deletion binding. */
        fun fromRegisteredComplete(
            registration: ComplaintTestNamespaceRegistrationV1,
            assembly: ComplaintTestProcessAssemblyV1,
            ownership: PersistencePhaseOwnership,
            jdbc: JdbcTemplate,
            audit: AuditService,
            startup: ComplaintTestRegisteredHttpStartupV1,
            userDecoder: JwtDecoder,
            passwordEncoder: PasswordEncoder,
            ownerResources: TestOwnerDeleteProcessBindingV1.OwnerHttpResources,
            ownerPublisher: TestOwnerDeleteJournalPublisherFactoryV1,
            allResources: TestOwnerDeleteProcessBindingV1.OwnerDeleteAllHttpResources,
            allPublisher: TestOwnerDeleteAllJournalPublisherFactoryV1,
            adminResources: TestOwnerDeleteProcessBindingV1.AdminHttpResources,
            adminPublisher: TestAdminDeleteJournalPublisherFactoryV1,
        ): ComplaintTestBootstrapHttpCompositionV1 {
            registration.requireActiveIdentityTarget(assembly)
            registration.requireIdentityAdmissionPhaseResources(ownership, jdbc)
            val responses = ComplaintOwnerHistoryResponses()
            val reads = RegisteredOwnerReads(registration, assembly, ownership, jdbc, responses)
            val admin = ComplaintTestRegisteredAdminReadsV1.fromRegistered(registration, assembly, ownership, jdbc, startup, userDecoder, responses)
            val content = ComplaintTestRegisteredAdminContentV1.fromRegisteredComplete(registration, assembly, ownership, jdbc, audit,
                startup, userDecoder, passwordEncoder, responses, ownerResources, adminResources, adminPublisher)
            val edit = JdbcComplaintOwnerEditStore.registeredInitialCheckpoint(jdbc, audit, ownership, registration, assembly)
            val reply = JdbcComplaintOwnerCreateStore.registeredInitialCheckpointWithReplies(jdbc, audit, ownership, registration, assembly)
            return ComplaintTestBootstrapHttpCompositionV1(registration, ownership, jdbc, assembly, audit,
                reads, reply, edit, me = reads.installationMe(), adminReads = admin, adminContent = content,
                ownerDelete = ownerResources, ownerDeletePublisher = ownerPublisher,
                ownerDeleteAll = allResources, ownerDeleteAllPublisher = allPublisher, completeOwnerAdmin = true)
        }

        /** Concrete me reader on the same registered read/CREATE graph; no earlier selector expands. */
        fun fromRegisteredInitialCheckpointReadCreateMe(
            registration: ComplaintTestNamespaceRegistrationV1,
            assembly: ComplaintTestProcessAssemblyV1,
            ownership: PersistencePhaseOwnership,
            jdbc: JdbcTemplate,
            audit: AuditService,
        ): ComplaintTestBootstrapHttpCompositionV1 {
            registration.requireActiveIdentityTarget(assembly)
            registration.requireInstallationResources(ownership, jdbc)
            val reads = RegisteredOwnerReads(registration, assembly, ownership, jdbc)
            return ComplaintTestBootstrapHttpCompositionV1(registration, ownership, jdbc, assembly, audit,
                reads, me = reads.installationMe())
        }

        /** One explicit read cohort; old selectors keep their routes, policies and response-owner construction. */
        fun fromRegisteredInitialCheckpointReadCreateAdminRead(
            registration: ComplaintTestNamespaceRegistrationV1,
            assembly: ComplaintTestProcessAssemblyV1,
            ownership: PersistencePhaseOwnership,
            jdbc: JdbcTemplate,
            audit: AuditService,
            startup: ComplaintTestRegisteredHttpStartupV1,
            userDecoder: JwtDecoder,
        ): ComplaintTestBootstrapHttpCompositionV1 {
            registration.requireActiveIdentityTarget(assembly)
            registration.requireIdentityAdmissionPhaseResources(ownership, jdbc) // Compare only the original already-bound pair.
            val responses = ComplaintOwnerHistoryResponses()
            val admin = ComplaintTestRegisteredAdminReadsV1.fromRegistered(registration, assembly, ownership, jdbc, startup, userDecoder, responses)
            return ComplaintTestBootstrapHttpCompositionV1(registration, ownership, jdbc, assembly, audit,
                RegisteredOwnerReads(registration, assembly, ownership, jdbc, responses), adminReads = admin)
        }

        /** Further explicit fixed TEST cohort; old Admin-read selection gains no issuer or mutation. */
        fun fromRegisteredInitialCheckpointReadCreateAdminReadContent(
            registration: ComplaintTestNamespaceRegistrationV1,
            assembly: ComplaintTestProcessAssemblyV1,
            ownership: PersistencePhaseOwnership,
            jdbc: JdbcTemplate,
            audit: AuditService,
            startup: ComplaintTestRegisteredHttpStartupV1,
            userDecoder: JwtDecoder,
            passwordEncoder: PasswordEncoder,
        ): ComplaintTestBootstrapHttpCompositionV1 {
            registration.requireActiveIdentityTarget(assembly)
            registration.requireIdentityAdmissionPhaseResources(ownership, jdbc)
            val responses = ComplaintOwnerHistoryResponses()
            val admin = ComplaintTestRegisteredAdminReadsV1.fromRegistered(registration, assembly, ownership, jdbc, startup, userDecoder, responses)
            val content = ComplaintTestRegisteredAdminContentV1.fromRegistered(registration, assembly, ownership, jdbc, audit,
                startup, userDecoder, passwordEncoder, responses)
            return ComplaintTestBootstrapHttpCompositionV1(registration, ownership, jdbc, assembly, audit,
                RegisteredOwnerReads(registration, assembly, ownership, jdbc, responses), adminReads = admin, adminContent = content)
        }

        /** Explicit status/closure addition, sharing the original content issuer and aggregate-eight response owner. */
        fun fromRegisteredInitialCheckpointReadCreateAdminReadContentStatus(
            registration: ComplaintTestNamespaceRegistrationV1,
            assembly: ComplaintTestProcessAssemblyV1,
            ownership: PersistencePhaseOwnership,
            jdbc: JdbcTemplate,
            audit: AuditService,
            startup: ComplaintTestRegisteredHttpStartupV1,
            userDecoder: JwtDecoder,
            passwordEncoder: PasswordEncoder,
        ): ComplaintTestBootstrapHttpCompositionV1 {
            registration.requireActiveIdentityTarget(assembly)
            registration.requireIdentityAdmissionPhaseResources(ownership, jdbc)
            val responses = ComplaintOwnerHistoryResponses()
            val admin = ComplaintTestRegisteredAdminReadsV1.fromRegistered(registration, assembly, ownership, jdbc, startup, userDecoder, responses)
            val content = ComplaintTestRegisteredAdminContentV1.fromRegisteredWithStatus(registration, assembly, ownership, jdbc, audit,
                startup, userDecoder, passwordEncoder, responses)
            return ComplaintTestBootstrapHttpCompositionV1(registration, ownership, jdbc, assembly, audit,
                RegisteredOwnerReads(registration, assembly, ownership, jdbc, responses), adminReads = admin, adminContent = content)
        }

        /** Explicit atomic STATUS addition; one original issuer/current reader and aggregate-eight response owner. */
        fun fromRegisteredInitialCheckpointReadCreateAdminReadContentStatusBatchStatus(
            registration: ComplaintTestNamespaceRegistrationV1,
            assembly: ComplaintTestProcessAssemblyV1,
            ownership: PersistencePhaseOwnership,
            jdbc: JdbcTemplate,
            audit: AuditService,
            startup: ComplaintTestRegisteredHttpStartupV1,
            userDecoder: JwtDecoder,
            passwordEncoder: PasswordEncoder,
        ): ComplaintTestBootstrapHttpCompositionV1 {
            registration.requireActiveIdentityTarget(assembly)
            registration.requireIdentityAdmissionPhaseResources(ownership, jdbc)
            val responses = ComplaintOwnerHistoryResponses()
            val admin = ComplaintTestRegisteredAdminReadsV1.fromRegistered(registration, assembly, ownership, jdbc, startup, userDecoder, responses)
            val content = ComplaintTestRegisteredAdminContentV1.fromRegisteredWithStatusAndBatchStatus(registration, assembly, ownership, jdbc, audit,
                startup, userDecoder, passwordEncoder, responses)
            return ComplaintTestBootstrapHttpCompositionV1(registration, ownership, jdbc, assembly, audit,
                RegisteredOwnerReads(registration, assembly, ownership, jdbc, responses), adminReads = admin, adminContent = content)
        }

        /** Separate concrete reply-capable store/handler selection. All earlier factories retain their narrower routes. */
        fun fromRegisteredInitialCheckpointReadCreateReply(
            registration: ComplaintTestNamespaceRegistrationV1,
            assembly: ComplaintTestProcessAssemblyV1,
            ownership: PersistencePhaseOwnership,
            jdbc: JdbcTemplate,
            audit: AuditService,
        ): ComplaintTestBootstrapHttpCompositionV1 {
            registration.requireActiveIdentityTarget(assembly)
            registration.requireInstallationResources(ownership, jdbc)
            val replyStore = JdbcComplaintOwnerCreateStore.registeredInitialCheckpointWithReplies(jdbc, audit, ownership, registration, assembly)
            return ComplaintTestBootstrapHttpCompositionV1(registration, ownership, jdbc, assembly, audit,
                RegisteredOwnerReads(registration, assembly, ownership, jdbc), replyStore)
        }

        /** Explicit complete read/CREATE/REPLY/EDIT tuple; old factories never receive an EDIT store or status handler. */
        fun fromRegisteredInitialCheckpointReadCreateReplyEdit(
            registration: ComplaintTestNamespaceRegistrationV1,
            assembly: ComplaintTestProcessAssemblyV1,
            ownership: PersistencePhaseOwnership,
            jdbc: JdbcTemplate,
            audit: AuditService,
        ): ComplaintTestBootstrapHttpCompositionV1 {
            registration.requireActiveIdentityTarget(assembly)
            registration.requireInstallationResources(ownership, jdbc)
            val edit = JdbcComplaintOwnerEditStore.registeredInitialCheckpoint(jdbc, audit, ownership, registration, assembly)
            val reply = JdbcComplaintOwnerCreateStore.registeredInitialCheckpointWithReplies(jdbc, audit, ownership, registration, assembly)
            return ComplaintTestBootstrapHttpCompositionV1(registration, ownership, jdbc, assembly, audit,
                RegisteredOwnerReads(registration, assembly, ownership, jdbc), reply, edit)
        }

        /** Explicit me plus complete owner mutation/status tuple, without broad Core or a new persistence owner. */
        fun fromRegisteredInitialCheckpointReadCreateMeReplyEdit(
            registration: ComplaintTestNamespaceRegistrationV1,
            assembly: ComplaintTestProcessAssemblyV1,
            ownership: PersistencePhaseOwnership,
            jdbc: JdbcTemplate,
            audit: AuditService,
        ): ComplaintTestBootstrapHttpCompositionV1 {
            registration.requireActiveIdentityTarget(assembly)
            registration.requireInstallationResources(ownership, jdbc)
            val edit = JdbcComplaintOwnerEditStore.registeredInitialCheckpoint(jdbc, audit, ownership, registration, assembly)
            val reply = JdbcComplaintOwnerCreateStore.registeredInitialCheckpointWithReplies(jdbc, audit, ownership, registration, assembly)
            val reads = RegisteredOwnerReads(registration, assembly, ownership, jdbc)
            return ComplaintTestBootstrapHttpCompositionV1(registration, ownership, jdbc, assembly, audit,
                reads, reply, edit, me = reads.installationMe())
        }
    }
}
