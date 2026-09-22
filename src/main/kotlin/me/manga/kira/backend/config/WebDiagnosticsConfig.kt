package me.manga.kira.backend.config

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.DispatcherType
import jakarta.servlet.Filter
import jakarta.servlet.ServletRequestListener
import me.manga.kira.backend.common.web.DisabledComplaintRoutesFilter
import me.manga.kira.backend.common.web.RequestBodySizeLimitFilter
import me.manga.kira.backend.common.web.RequestDiagnosticsFilter
import me.manga.kira.backend.security.AuthenticatedMdcFilter
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.security.SecurityProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered

/**
 * Registers the diagnostics filters (PLAN §6).
 *
 *  - [RequestDiagnosticsFilter] runs at [Ordered.HIGHEST_PRECEDENCE] (one later for the selected
 *    original-lifetime guard), **before** the Spring Security
 *    chain (`SecurityProperties.DEFAULT_FILTER_ORDER`, -100), so the correlation id is in the MDC
 *    for security-layer logs too and the access-log line captures the final status even for
 *    security-rejected requests.
 *  - [AuthenticatedMdcFilter] runs just **after** the security chain, so it can read the
 *    authenticated principal and add `userId`/`role` to the MDC (PLAN §6). Both filters are
 *    instantiated here (not `@Component`s) to avoid Boot double-registering them.
 */
@Configuration
class WebDiagnosticsConfig {

    @Bean("registeredOrdinaryHttpLifetimeFilter")
    @ConditionalOnBean(ComplaintTestRegisteredHttpStartupV1::class)
    internal fun registeredOrdinaryHttpLifetimeFilter(startup: ComplaintTestRegisteredHttpStartupV1): FilterRegistrationBean<Filter> =
        FilterRegistrationBean(startup.ordinaryHttpFilter()).apply {
            order = Ordered.HIGHEST_PRECEDENCE
            setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR)
            addUrlPatterns("/*")
            isAsyncSupported = true
        }

    @Bean("registeredOrdinaryHttpLifetimeListener")
    @ConditionalOnBean(ComplaintTestRegisteredHttpStartupV1::class)
    internal fun registeredOrdinaryHttpLifetimeListener(startup: ComplaintTestRegisteredHttpStartupV1): ServletListenerRegistrationBean<ServletRequestListener> =
        ServletListenerRegistrationBean(startup.ordinaryHttpListener()).apply {
            order = Ordered.HIGHEST_PRECEDENCE // Init first/destroy last; actual Tomcat ordering is checked before admission opens.
        }

    @Bean("requestDiagnosticsFilter")
    internal fun requestDiagnosticsFilter(startup: ComplaintTestRegisteredHttpStartupV1? = null): FilterRegistrationBean<RequestDiagnosticsFilter> = FilterRegistrationBean(RequestDiagnosticsFilter()).apply {
        order = Ordered.HIGHEST_PRECEDENCE + if (startup == null) 0 else 1
        addUrlPatterns("/*")
        isAsyncSupported = true
    }

    @Bean("disabledComplaintRoutesFilter")
    internal fun disabledComplaintRoutesFilter(bootstrap: ComplaintTestBootstrapHttpCompositionV1? = null,
        startup: ComplaintTestRegisteredHttpStartupV1? = null): FilterRegistrationBean<Filter> =
        FilterRegistrationBean<Filter>(bootstrap?.ingressFilter ?: DisabledComplaintRoutesFilter()).apply {
            // Only a real selected TEST bootstrap may replace its literal route's denial. All other complaints stay closed.
            order = Ordered.HIGHEST_PRECEDENCE + if (startup == null) 1 else 2
            addUrlPatterns("/*")
            isAsyncSupported = true
        }

    @Bean("requestBodySizeLimitFilter")
    internal fun requestBodySizeLimitFilter(objectMapper: ObjectMapper, startup: ComplaintTestRegisteredHttpStartupV1? = null): FilterRegistrationBean<RequestBodySizeLimitFilter> =
        FilterRegistrationBean(RequestBodySizeLimitFilter(objectMapper)).apply {
            // Diagnostics and the closed/registered-bootstrap ingress boundary precede buffering; security/MVC stay downstream.
            order = Ordered.HIGHEST_PRECEDENCE + if (startup == null) 2 else 3
            addUrlPatterns("/*")
            isAsyncSupported = true
        }

    @Bean
    fun authenticatedMdcFilter(): FilterRegistrationBean<AuthenticatedMdcFilter> = FilterRegistrationBean(AuthenticatedMdcFilter()).apply {
        order = SecurityProperties.DEFAULT_FILTER_ORDER + 1 // after the security chain (auth populated)
        addUrlPatterns("/*")
        isAsyncSupported = true
    }
}
