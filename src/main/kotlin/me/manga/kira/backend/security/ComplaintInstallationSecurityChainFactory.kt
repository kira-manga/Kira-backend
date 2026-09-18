package me.manga.kira.backend.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import me.manga.kira.backend.complaint.api.ComplaintInstallationHttpHandler
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
internal class ComplaintInstallationSecurityChainFactory(
    private val bridge: ComplaintHttpIngressBridge,
    private val authentication: ComplaintInstallationBearerAuthenticator,
    private val installations: ComplaintInstallationHttpHandler,
    private val me: ComplaintInstallationMeHttpHandler,
    private val history: ComplaintOwnerHistoryHttpHandler,
    private val create: ComplaintOwnerCreateHttpHandler,
    private val deleteAll: ComplaintOwnerDeleteAllHttpHandler? = null,
    private val detail: ComplaintOwnerDetailHttpHandler? = null,
    private val reply: ComplaintOwnerCreateHttpHandler? = null,
    private val edit: ComplaintOwnerEditHttpHandler? = null,
    private val delete: ComplaintOwnerDeleteHttpHandler? = null,
) {
    init {
        require(create.hasDeleteStatus() == (delete != null)) { "Complaint delete/status composition refused." }
        if (delete != null) require(create.usesDeleteStatus(delete)) { "Complaint delete/status composition refused." }
        require(create.hasEditStatus() == (edit != null)) { "Complaint edit/status composition refused." }
        if (edit != null) require(create.usesEditStatus(edit)) { "Complaint edit/status composition refused." }
    }

    private val entryPoint = AuthenticationEntryPoint { request, response, _ ->
        ComplaintSecurityResponses.problem(request, response, ComplaintSecurityFailure.UNAUTHORIZED)
    }
    private val denied = AccessDeniedHandler { request, response, _ ->
        ComplaintSecurityResponses.problem(request, response, ComplaintSecurityFailure.FORBIDDEN)
    }

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
                AuthenticationManager { candidate -> authentication.authenticate(bridge.authenticationContext(request), candidate) }
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
            !authentication.belongsTo(context, SecurityContextHolder.getContext().authentication)
        ) {
            ComplaintSecurityResponses.problem(request, response, ComplaintSecurityFailure.UNAUTHORIZED)
        } else {
            when (ComplaintInstallationRoutes.path(request)) {
                ComplaintInstallationRoutes.ENROLLMENT, ComplaintInstallationRoutes.SESSION -> installations.handleWithinIngress(request, response, context)

                ComplaintInstallationRoutes.ME -> me.handleWithinIngress(request, response, context)

                ComplaintInstallationRoutes.STATUS -> create.handleWithinIngress(request, response, context)

                ComplaintInstallationRoutes.DELETE_ALL -> checkNotNull(deleteAll).handleWithinIngress(request, response, context)

                ComplaintInstallationRoutes.HISTORY -> if (request.method == "GET") {
                    history.handleWithinIngress(request, response, context)
                } else {
                    create.handleWithinIngress(request, response, context)
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
    private fun implemented(request: HttpServletRequest): Boolean = ComplaintInstallationRoutes.implemented(request) ||
        (deleteAll != null && request.method == "POST" && ComplaintInstallationRoutes.path(request) == ComplaintInstallationRoutes.DELETE_ALL) ||
        (reply != null && request.method == "POST" && ComplaintInstallationRoutes.isReply(request)) ||
        (edit != null && request.method == "PATCH" && ComplaintInstallationRoutes.isContent(request)) ||
        (detail != null && request.method == "GET" && ComplaintInstallationRoutes.isDetail(request)) ||
        (delete != null && request.method == "DELETE" && ComplaintInstallationRoutes.isDetail(request))

    private inner class ClosedUnimplementedRoutes : OncePerRequestFilter() {
        override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
            if (!implemented(request)) {
                ComplaintSecurityResponses.problem(request, response, ComplaintSecurityFailure.NOT_FOUND)
                return
            }
            // Detail's fixed bodyless validation precedes even the converter's current-row SQL.
            if (request.method == "GET" && ComplaintInstallationRoutes.isDetail(request) &&
                !checkNotNull(detail).validateWithinIngress(request, response, bridge.authenticationContext(request))
            ) {
                return
            }
            filterChain.doFilter(request, response)
        }
    }
}

/** Path ownership is independent of the verb; unsupported methods cannot fall through to user authentication. */
internal object ComplaintInstallationRoutes : RequestMatcher {
    const val ENROLLMENT = "/api/v1/installations"
    const val SESSION = "/api/v1/installations/session"
    const val DELETE_ALL = "/api/v1/installations/delete-all"
    const val ME = "/api/v1/installations/me"
    const val HISTORY = "/api/v1/complaints"
    const val STATUS = "/api/v1/complaint-operations/status"
    private val detailPath = Regex("$HISTORY/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val replyPath = Regex("${detailPath.pattern}/replies")
    private val contentPath = Regex("${detailPath.pattern}/content")
    private val publicPaths = setOf(ENROLLMENT, SESSION, "/api/v1/installations/bootstrap", DELETE_ALL)
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
        else -> false // Including bootstrap: no readiness/activation producer exists yet.
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
