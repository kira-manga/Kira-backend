package me.manga.kira.backend.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.web.SecurityFilterChain
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping

/**
 * A real optional mount, not a property activation mechanism. The explicit assembly must register
 * its concrete composition before this configuration is evaluated. The normal application has no
 * such bean, so the existing pre-buffer 404 boundary remains in force. No standalone handler bean.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBean(ComplaintTestBootstrapHttpCompositionV1::class)
internal class ComplaintTestBootstrapHttpConfigurationV1 {
    @Bean
    @Order(1)
    fun complaintTestBootstrapSecurityChain(http: HttpSecurity, composition: ComplaintTestBootstrapHttpCompositionV1): SecurityFilterChain =
        composition.securityChain(http)

    @Bean
    fun complaintTestBootstrapHandlerMapping(composition: ComplaintTestBootstrapHttpCompositionV1): SimpleUrlHandlerMapping =
        SimpleUrlHandlerMapping(composition.mappedPaths.associateWith { composition.handler }, -1)
}
