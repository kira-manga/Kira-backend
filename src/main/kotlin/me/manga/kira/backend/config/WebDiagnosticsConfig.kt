package me.manga.kira.backend.config

import me.manga.kira.backend.common.web.RequestDiagnosticsFilter
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered

/**
 * Registers [RequestDiagnosticsFilter] at [Ordered.HIGHEST_PRECEDENCE] so it runs **before** the
 * Spring Security filter chain (registered at `SecurityProperties.DEFAULT_FILTER_ORDER`, -100).
 * That way the correlation id is in the MDC for security-layer logs too, and the access-log line
 * captures the final status even for requests security rejects (PLAN §6).
 */
@Configuration
class WebDiagnosticsConfig {

    @Bean
    fun requestDiagnosticsFilter(): FilterRegistrationBean<RequestDiagnosticsFilter> =
        FilterRegistrationBean(RequestDiagnosticsFilter()).apply {
            order = Ordered.HIGHEST_PRECEDENCE
            addUrlPatterns("/*")
        }
}
