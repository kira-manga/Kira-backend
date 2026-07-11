package me.manga.kira.backend.config

import me.manga.kira.backend.common.web.RequestDiagnosticsFilter
import me.manga.kira.backend.security.AuthenticatedMdcFilter
import org.springframework.boot.autoconfigure.security.SecurityProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered

/**
 * Registers the diagnostics filters (PLAN §6).
 *
 *  - [RequestDiagnosticsFilter] runs at [Ordered.HIGHEST_PRECEDENCE], **before** the Spring Security
 *    chain (`SecurityProperties.DEFAULT_FILTER_ORDER`, -100), so the correlation id is in the MDC
 *    for security-layer logs too and the access-log line captures the final status even for
 *    security-rejected requests.
 *  - [AuthenticatedMdcFilter] runs just **after** the security chain, so it can read the
 *    authenticated principal and add `userId`/`role` to the MDC (PLAN §6). Both filters are
 *    instantiated here (not `@Component`s) to avoid Boot double-registering them.
 */
@Configuration
class WebDiagnosticsConfig {

    @Bean
    fun requestDiagnosticsFilter(): FilterRegistrationBean<RequestDiagnosticsFilter> =
        FilterRegistrationBean(RequestDiagnosticsFilter()).apply {
            order = Ordered.HIGHEST_PRECEDENCE
            addUrlPatterns("/*")
        }

    @Bean
    fun authenticatedMdcFilter(): FilterRegistrationBean<AuthenticatedMdcFilter> =
        FilterRegistrationBean(AuthenticatedMdcFilter()).apply {
            order = SecurityProperties.DEFAULT_FILTER_ORDER + 1 // after the security chain (auth populated)
            addUrlPatterns("/*")
        }
}
