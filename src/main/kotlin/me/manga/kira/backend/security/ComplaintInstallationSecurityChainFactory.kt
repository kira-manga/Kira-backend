package me.manga.kira.backend.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import me.manga.kira.backend.complaint.api.ComplaintInstallationHttpHandler
import me.manga.kira.backend.complaint.api.ComplaintInstallationBootstrapHttpHandler
import me.manga.kira.backend.complaint.api.ComplaintInstallationMeHttpHandler
import me.manga.kira.backend.complaint.api.ComplaintOwnerCreateHttpHandler
import me.manga.kira.backend.complaint.api.ComplaintOwnerDeleteHttpHandler
import me.manga.kira.backend.complaint.api.ComplaintOwnerDeleteAllHttpHandler
import me.manga.kira.backend.complaint.api.ComplaintOwnerDetailHttpHandler
import me.manga.kira.backend.complaint.api.ComplaintOwnerEditHttpHandler
import me.manga.kira.backend.complaint.api.ComplaintOwnerHistoryHttpHandler
import me.manga.kira.backend.complaint.infrastructure.ComplaintInstallationBearerAuthenticator
import org.springframework.http.server.RequestPath
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.config.ObjectPostProcessor
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.security.web.context.NullSecurityContextRepository
import org.springframework.security.web.savedrequest.NullRequestCache
import org.springframework.security.web.util.matcher.RequestMatcher
import org.springframework.web.HttpRequestHandler
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.pattern.PathPatternParser

/**
 * No bean, annotation, mapping or registration: explicit TEST assembly supplies this chain at
 * @Order(1), ahead of the existing qualified user/admin @Order(2) chain. Its outer ingress bridge
 * MUST surround the body guard too, not merely be inserted ahead of bearer authentication.
 */
internal class ComplaintInstallationSecurityChainFactory private constructor(
    private val bridge: ComplaintHttpIngressBridge,
    private val core: Core?,
    private val bootstrap: ComplaintInstallationBootstrapHttpHandler?,
    private val initialCreate: InitialCreate? = null,
    private val initialOwnerDelete: InitialOwnerDelete? = null,
) {
    /** Existing explicit core construction is unchanged; bootstrap is an optional concrete producer, not a ready flag. */
    constructor(
        bridge: ComplaintHttpIngressBridge,
        authentication: ComplaintInstallationBearerAuthenticator,
        installations: ComplaintInstallationHttpHandler,
        me: ComplaintInstallationMeHttpHandler,
        history: ComplaintOwnerHistoryHttpHandler,
        create: ComplaintOwnerCreateHttpHandler,
        deleteAll: ComplaintOwnerDeleteAllHttpHandler? = null,
        detail: ComplaintOwnerDetailHttpHandler? = null,
        reply: ComplaintOwnerCreateHttpHandler? = null,
        edit: ComplaintOwnerEditHttpHandler? = null,
        delete: ComplaintOwnerDeleteHttpHandler? = null,
        bootstrap: ComplaintInstallationBootstrapHttpHandler? = null,
    ) : this(bridge, Core(authentication, installations, me, history, create, deleteAll, detail, reply, edit, delete), bootstrap)

    init {
        require(core == null || (initialCreate == null && initialOwnerDelete == null))
        require(initialCreate == null || initialOwnerDelete == null)
        initialCreate?.let {
            require(!it.create.hasDeleteStatus() && it.create.hasEditStatus() == (it.edit != null)) { "Complaint CREATE subset refused." }
            if (it.edit != null) require(it.reply != null && it.create.usesEditStatus(it.edit)) { "Complaint EDIT subset refused." }
            require(it.reply == null || it.reply === it.create) { "Complaint REPLY subset refused." }
            require(it.me == null || (it.reads != null &&
                ((it.reply == null && it.edit == null) || (it.reply === it.create && it.edit != null)))) { "Installation read subset refused." }
        }
        initialOwnerDelete?.let {
            require(it.create.hasDeleteStatus() && it.create.usesDeleteStatus(it.delete) && !it.create.hasEditStatus()) {
                "Complaint owner DELETE subset refused."
            }
        }
        core?.let {
            require(it.create.hasDeleteStatus() == (it.delete != null)) { "Complaint delete/status composition refused." }
            if (it.delete != null) require(it.create.usesDeleteStatus(it.delete)) { "Complaint delete/status composition refused." }
            require(it.create.hasEditStatus() == (it.edit != null)) { "Complaint edit/status composition refused." }
            if (it.edit != null) require(it.create.usesEditStatus(it.edit)) { "Complaint edit/status composition refused." }
        }
    }

    private val entryPoint = AuthenticationEntryPoint { request, response, _ ->
        ComplaintSecurityResponses.problem(request, response, ComplaintSecurityFailure.UNAUTHORIZED)
    }
    private val denied = AccessDeniedHandler { request, response, _ ->
        ComplaintSecurityResponses.problem(request, response, ComplaintSecurityFailure.FORBIDDEN)
    }
    private val authentication: ComplaintInstallationBearerAuthenticator? get() = core?.authentication ?: initialCreate?.authentication ?: initialOwnerDelete?.authentication
    private val detail: ComplaintOwnerDetailHttpHandler? get() = core?.detail ?: initialCreate?.reads?.detail ?: initialOwnerDelete?.reads?.detail
    private val reply: ComplaintOwnerCreateHttpHandler? get() = core?.reply ?: initialCreate?.reply
    private val edit: ComplaintOwnerEditHttpHandler? get() = core?.edit ?: initialCreate?.edit
    private val me: ComplaintInstallationMeHttpHandler? get() = core?.me ?: initialCreate?.me
    private val delete: ComplaintOwnerDeleteHttpHandler? get() = core?.delete ?: initialOwnerDelete?.delete

    fun build(http: HttpSecurity): SecurityFilterChain {
        http.securityMatcher(ComplaintInstallationRoutes)
        http.csrf { it.disable() }
        http.httpBasic { it.disable() }
        http.formLogin { it.disable() }
        http.logout { it.disable() }
        http.cors { it.disable() }
        http.sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        http.securityContext { it.securityContextRepository(NullSecurityContextRepository()) }
        http.requestCache { it.requestCache(NullRequestCache()) }
        http.exceptionHandling {
            it.authenticationEntryPoint(entryPoint)
            it.accessDeniedHandler(denied)
        }
        http.authorizeHttpRequests {
            it.requestMatchers(RequestMatcher { request -> !ComplaintInstallationRoutes.requiresBearer(request) }).permitAll()
                .anyRequest().hasAuthority(InstallationJwtCodec.ROLE)
        }
        http.oauth2ResourceServer { resource ->
            resource.bearerTokenResolver(ComplaintInstallationBearerResolver)
            resource.authenticationManagerResolver { request ->
                AuthenticationManager { candidate ->
                    val selected = authentication ?: throw InvalidBearerTokenException("Installation credential refused.")
                    selected.authenticate(bridge.authenticationContext(request), candidate)
                }
            }
            resource.authenticationEntryPoint(entryPoint)
            resource.accessDeniedHandler(denied)
            // The bearer filter has its own request-attribute repository, independent of http.securityContext.
            resource.addObjectPostProcessor(object : ObjectPostProcessor<BearerTokenAuthenticationFilter> {
                override fun <O : BearerTokenAuthenticationFilter> postProcess(filter: O): O {
                    filter.setSecurityContextRepository(NullSecurityContextRepository())
                    return filter
                }
            })
        }
        http.addFilterBefore(ClosedUnimplementedRoutes(), BearerTokenAuthenticationFilter::class.java)
        return http.build()
    }

    /** Fixed dispatch only. The existing producers reverify bearer facts and current rows themselves. */
    val handler = HttpRequestHandler { request, response ->
        val context = bridge.claimHandler(request)
        if (!implemented(request)) {
            ComplaintSecurityResponses.problem(request, response, ComplaintSecurityFailure.NOT_FOUND)
        } else if (ComplaintInstallationRoutes.requiresBearer(request) &&
            authentication?.belongsTo(context, SecurityContextHolder.getContext().authentication) != true
        ) {
            ComplaintSecurityResponses.problem(request, response, ComplaintSecurityFailure.UNAUTHORIZED)
        } else {
            when (ComplaintInstallationRoutes.path(request)) {
                ComplaintInstallationRoutes.BOOTSTRAP -> checkNotNull(bootstrap).handleWithinIngress(request, response, context)

                ComplaintInstallationRoutes.ENROLLMENT, ComplaintInstallationRoutes.SESSION ->
                    checkNotNull(core?.installations ?: initialCreate?.installations ?: initialOwnerDelete?.installations).handleWithinIngress(request, response, context)

                ComplaintInstallationRoutes.ME -> checkNotNull(me).handleWithinIngress(request, response, context)

                ComplaintInstallationRoutes.STATUS -> checkNotNull(core?.create ?: initialCreate?.create ?: initialOwnerDelete?.create).handleWithinIngress(request, response, context)

                ComplaintInstallationRoutes.DELETE_ALL -> checkNotNull(core?.deleteAll).handleWithinIngress(request, response, context)

                ComplaintInstallationRoutes.HISTORY -> if (request.method == "GET") {
                    checkNotNull(core?.history ?: initialCreate?.reads?.history ?: initialOwnerDelete?.reads?.history).handleWithinIngress(request, response, context)
                } else {
                    checkNotNull(core?.create ?: initialCreate?.create ?: initialOwnerDelete?.create).handleWithinIngress(request, response, context)
                }

                else -> if (ComplaintInstallationRoutes.isReply(request)) {
                    checkNotNull(reply).handleWithinIngress(request, response, context)
                } else if (ComplaintInstallationRoutes.isContent(request)) {
                    checkNotNull(edit).handleWithinIngress(request, response, context)
                } else if (request.method == "DELETE") {
                    checkNotNull(delete).handleWithinIngress(request, response, context)
                } else {
                    checkNotNull(detail).handleWithinIngress(request, response, context)
                }
            }
        }
    }

    override fun toString(): String = "ComplaintInstallationSecurityChainFactory(dormant,explicit-TEST-only)"

    /** Concrete optional composition only; no public readiness flag can open this route. */
    internal fun implemented(request: HttpServletRequest): Boolean = (core != null && ComplaintInstallationRoutes.implemented(request)) ||
        ((initialCreate != null || initialOwnerDelete != null) && request.method == "POST" && ComplaintInstallationRoutes.path(request) in INITIAL_CREATE_PATHS) ||
        ((initialCreate?.reads != null || initialOwnerDelete != null) && request.method == "GET" && ComplaintInstallationRoutes.path(request) == ComplaintInstallationRoutes.HISTORY) ||
        (me != null && request.method == "GET" && ComplaintInstallationRoutes.path(request) == ComplaintInstallationRoutes.ME) ||
        (bootstrap != null && request.method == "GET" && ComplaintInstallationRoutes.path(request) == ComplaintInstallationRoutes.BOOTSTRAP) ||
        (core?.deleteAll != null && request.method == "POST" && ComplaintInstallationRoutes.path(request) == ComplaintInstallationRoutes.DELETE_ALL) ||
        (reply != null && request.method == "POST" && ComplaintInstallationRoutes.isReply(request)) ||
        (edit != null && request.method == "PATCH" && ComplaintInstallationRoutes.isContent(request)) ||
        (detail != null && request.method == "GET" && ComplaintInstallationRoutes.isDetail(request)) ||
        (delete != null && request.method == "DELETE" && ComplaintInstallationRoutes.isDetail(request))

    /** Used by the registered outer filter before generic buffering, and rechecked before bearer SQL. */
    internal fun validateDetailWithinIngress(request: HttpServletRequest, response: HttpServletResponse): Boolean =
        request.method != "GET" || !ComplaintInstallationRoutes.isDetail(request) ||
            checkNotNull(detail).validateWithinIngress(request, response, bridge.authenticationContext(request))

    private inner class ClosedUnimplementedRoutes : OncePerRequestFilter() {
        override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
            if (!implemented(request)) {
                ComplaintSecurityResponses.problem(request, response, ComplaintSecurityFailure.NOT_FOUND)
                return
            }
            // Detail's fixed bodyless validation precedes even the converter's current-row SQL.
            if (!validateDetailWithinIngress(request, response)) return
            filterChain.doFilter(request, response)
        }
    }

    /** Fixed existing tuple, not an extensible route registry or authority carrier. */
    private class Core(
        val authentication: ComplaintInstallationBearerAuthenticator,
        val installations: ComplaintInstallationHttpHandler,
        val me: ComplaintInstallationMeHttpHandler,
        val history: ComplaintOwnerHistoryHttpHandler,
        val create: ComplaintOwnerCreateHttpHandler,
        val deleteAll: ComplaintOwnerDeleteAllHttpHandler?,
        val detail: ComplaintOwnerDetailHttpHandler?,
        val reply: ComplaintOwnerCreateHttpHandler?,
        val edit: ComplaintOwnerEditHttpHandler?,
        val delete: ComplaintOwnerDeleteHttpHandler?,
    )

    /** Fixed narrower tuple: me may join read/CREATE or the complete reply/EDIT cohort, never delete. */
    private class InitialCreate(
        val authentication: ComplaintInstallationBearerAuthenticator,
        val installations: ComplaintInstallationHttpHandler,
        val create: ComplaintOwnerCreateHttpHandler,
        val reads: OwnerReads? = null,
        val reply: ComplaintOwnerCreateHttpHandler? = null,
        val edit: ComplaintOwnerEditHttpHandler? = null,
        val me: ComplaintInstallationMeHttpHandler? = null,
    )

    /** Separate fixed tuple: no /me, reply, EDIT, delete-all or weakening of the original CREATE guard. */
    private class InitialOwnerDelete(
        val authentication: ComplaintInstallationBearerAuthenticator,
        val installations: ComplaintInstallationHttpHandler,
        val create: ComplaintOwnerCreateHttpHandler,
        val reads: OwnerReads,
        val delete: ComplaintOwnerDeleteHttpHandler,
    )

    private class OwnerReads(val history: ComplaintOwnerHistoryHttpHandler, val detail: ComplaintOwnerDetailHttpHandler)

    companion object {
        private val INITIAL_CREATE_PATHS = setOf(ComplaintInstallationRoutes.ENROLLMENT, ComplaintInstallationRoutes.SESSION,
            ComplaintInstallationRoutes.HISTORY, ComplaintInstallationRoutes.STATUS)

        /** Does not fabricate core handlers or enable any non-bootstrap route. Registration belongs to its concrete assembly. */
        fun bootstrapOnly(bridge: ComplaintHttpIngressBridge, bootstrap: ComplaintInstallationBootstrapHttpHandler): ComplaintInstallationSecurityChainFactory =
            ComplaintInstallationSecurityChainFactory(bridge, null, bootstrap)

        /** Concrete handlers come from the registered assembly; this route selection itself issues no authority. */
        fun registeredCreateSubset(bridge: ComplaintHttpIngressBridge, bootstrap: ComplaintInstallationBootstrapHttpHandler,
            authentication: ComplaintInstallationBearerAuthenticator, installations: ComplaintInstallationHttpHandler,
            create: ComplaintOwnerCreateHttpHandler): ComplaintInstallationSecurityChainFactory =
            ComplaintInstallationSecurityChainFactory(bridge, null, bootstrap, InitialCreate(authentication, installations, create))

        /** Explicit read + initial CREATE only. No fabricated /me or borrowed broad Core handlers. */
        fun registeredReadCreateSubset(bridge: ComplaintHttpIngressBridge, bootstrap: ComplaintInstallationBootstrapHttpHandler,
            authentication: ComplaintInstallationBearerAuthenticator, installations: ComplaintInstallationHttpHandler,
            create: ComplaintOwnerCreateHttpHandler, history: ComplaintOwnerHistoryHttpHandler,
            detail: ComplaintOwnerDetailHttpHandler): ComplaintInstallationSecurityChainFactory =
            ComplaintInstallationSecurityChainFactory(bridge, null, bootstrap, InitialCreate(authentication, installations, create, OwnerReads(history, detail)))

        /** Explicit original owner-deletion cohort; the older read/CREATE selector still refuses DELETE status. */
        fun registeredReadCreateOwnerDeleteSubset(bridge: ComplaintHttpIngressBridge, bootstrap: ComplaintInstallationBootstrapHttpHandler,
            authentication: ComplaintInstallationBearerAuthenticator, installations: ComplaintInstallationHttpHandler,
            create: ComplaintOwnerCreateHttpHandler, history: ComplaintOwnerHistoryHttpHandler,
            detail: ComplaintOwnerDetailHttpHandler, delete: ComplaintOwnerDeleteHttpHandler): ComplaintInstallationSecurityChainFactory =
            ComplaintInstallationSecurityChainFactory(bridge, null, bootstrap,
                initialOwnerDelete = InitialOwnerDelete(authentication, installations, create, OwnerReads(history, detail), delete))

        /** Same read/CREATE subset plus one concrete me handler; not the broader Core constructor. */
        fun registeredReadCreateMeSubset(bridge: ComplaintHttpIngressBridge, bootstrap: ComplaintInstallationBootstrapHttpHandler,
            authentication: ComplaintInstallationBearerAuthenticator, installations: ComplaintInstallationHttpHandler,
            create: ComplaintOwnerCreateHttpHandler, history: ComplaintOwnerHistoryHttpHandler,
            detail: ComplaintOwnerDetailHttpHandler, me: ComplaintInstallationMeHttpHandler): ComplaintInstallationSecurityChainFactory =
            ComplaintInstallationSecurityChainFactory(bridge, null, bootstrap,
                InitialCreate(authentication, installations, create, OwnerReads(history, detail), me = me))

        /** Explicit reply-capable sibling only; original CREATE/read-CREATE selectors do not acquire this handler. */
        fun registeredReadCreateReplySubset(bridge: ComplaintHttpIngressBridge, bootstrap: ComplaintInstallationBootstrapHttpHandler,
            authentication: ComplaintInstallationBearerAuthenticator, installations: ComplaintInstallationHttpHandler,
            create: ComplaintOwnerCreateHttpHandler, history: ComplaintOwnerHistoryHttpHandler,
            detail: ComplaintOwnerDetailHttpHandler): ComplaintInstallationSecurityChainFactory =
            ComplaintInstallationSecurityChainFactory(bridge, null, bootstrap,
                InitialCreate(authentication, installations, create, OwnerReads(history, detail), create))

        /** Separate explicit EDIT/status sibling; old subsets still reject a create handler carrying EDIT status. */
        fun registeredReadCreateReplyEditSubset(bridge: ComplaintHttpIngressBridge, bootstrap: ComplaintInstallationBootstrapHttpHandler,
            authentication: ComplaintInstallationBearerAuthenticator, installations: ComplaintInstallationHttpHandler,
            create: ComplaintOwnerCreateHttpHandler, history: ComplaintOwnerHistoryHttpHandler,
            detail: ComplaintOwnerDetailHttpHandler, edit: ComplaintOwnerEditHttpHandler): ComplaintInstallationSecurityChainFactory =
            ComplaintInstallationSecurityChainFactory(bridge, null, bootstrap,
                InitialCreate(authentication, installations, create, OwnerReads(history, detail), create, edit))

        /** Explicit me/REPLY/EDIT cohort; CREATE owns the exact reply/status handlers and no deletion custody. */
        fun registeredReadCreateMeReplyEditSubset(bridge: ComplaintHttpIngressBridge, bootstrap: ComplaintInstallationBootstrapHttpHandler,
            authentication: ComplaintInstallationBearerAuthenticator, installations: ComplaintInstallationHttpHandler,
            create: ComplaintOwnerCreateHttpHandler, history: ComplaintOwnerHistoryHttpHandler,
            detail: ComplaintOwnerDetailHttpHandler, me: ComplaintInstallationMeHttpHandler,
            edit: ComplaintOwnerEditHttpHandler): ComplaintInstallationSecurityChainFactory =
            ComplaintInstallationSecurityChainFactory(bridge, null, bootstrap,
                InitialCreate(authentication, installations, create, OwnerReads(history, detail), reply = create, edit = edit, me = me))
    }
}

/** Path ownership is independent of the verb; unsupported methods cannot fall through to user authentication. */
internal object ComplaintInstallationRoutes : RequestMatcher {
    const val ENROLLMENT = "/api/v1/installations"
    const val BOOTSTRAP = "/api/v1/installations/bootstrap"
    const val SESSION = "/api/v1/installations/session"
    const val DELETE_ALL = "/api/v1/installations/delete-all"
    const val ME = "/api/v1/installations/me"
    const val HISTORY = "/api/v1/complaints"
    const val STATUS = "/api/v1/complaint-operations/status"
    private val detailPath = Regex("$HISTORY/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val replyPath = Regex("${detailPath.pattern}/replies")
    private val contentPath = Regex("${detailPath.pattern}/content")
    private val publicPaths = setOf(ENROLLMENT, SESSION, BOOTSTRAP, DELETE_ALL)
    private val patterns = (
        publicPaths + setOf(ME, HISTORY, STATUS, "$HISTORY/{id}", "$HISTORY/{id}/replies", "$HISTORY/{id}/content")
        ).flatMap { listOf(it, "$it/") }.map(PathPatternParser()::parse)

    @Suppress("SwallowedException")
    override fun matches(request: HttpServletRequest): Boolean = try {
        val selected = RequestPath.parse(request.requestURI, request.contextPath).pathWithinApplication()
        patterns.any { it.matches(selected) }
    } catch (failure: IllegalArgumentException) {
        false // Retain the existing container/firewall malformed-path policy; no broad prefix ownership.
    }

    fun path(request: HttpServletRequest): String = request.requestURI.removePrefix(request.contextPath)

    fun isDetail(request: HttpServletRequest): Boolean = detailPath.matches(path(request))

    fun isReply(request: HttpServletRequest): Boolean = replyPath.matches(path(request))

    fun isContent(request: HttpServletRequest): Boolean = contentPath.matches(path(request))

    fun requiresBearer(request: HttpServletRequest): Boolean = path(request) !in publicPaths

    fun implemented(request: HttpServletRequest): Boolean = when (path(request)) {
        ENROLLMENT, SESSION, STATUS -> request.method == "POST"
        ME -> request.method == "GET"
        HISTORY -> request.method == "GET" || request.method == "POST"
        else -> false // Optional producers, including bootstrap, require their concrete factory composition.
    }
}

/** Never asks for parameters or consumes a body; public secret routes deliberately ignore bearer credentials. */
internal object ComplaintInstallationBearerResolver : BearerTokenResolver {
    override fun resolve(request: HttpServletRequest): String? {
        if (!ComplaintInstallationRoutes.requiresBearer(request)) return null
        val values = request.getHeaders("Authorization")
        if (!values.hasMoreElements()) return null
        val value = values.nextElement()
        if (values.hasMoreElements() || value.length > InstallationJwtCodec.MAX_COMPACT_BYTES + 7 ||
            value.any { it.code !in 32..126 }
        ) {
            throw InvalidBearerTokenException("Installation credential refused.")
        }
        if (!value.startsWith("Bearer ") || value.length == 7) {
            throw InvalidBearerTokenException("Installation credential refused.")
        }
        return value.substring(7)
    }
}
