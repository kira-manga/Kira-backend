package me.manga.kira.backend.security

import com.fasterxml.jackson.databind.ObjectMapper
import me.manga.kira.backend.config.KiraSecurityProperties
import me.manga.kira.backend.support.JwtTestSupport
import me.manga.kira.backend.user.domain.UserRepository
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.core.annotation.Order
import org.springframework.mock.web.MockServletContext
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.web.SecurityFilterChain
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext
import org.springframework.web.servlet.handler.HandlerMappingIntrospector

/** Thin explicit Spring assembly only: no application scan, server, data source, pool or recovery/activation authority. */
@Suppress("TooGenericExceptionCaught") // Close the explicit test context even when refresh fails.
internal fun complaintSpringSecurityContext(
    factory: ComplaintInstallationSecurityChainFactory,
    users: UserRepository,
): AnnotationConfigWebApplicationContext = AnnotationConfigWebApplicationContext().apply {
    servletContext = MockServletContext()
    register(SecurityConfig::class.java, ComplaintSpringTestConfiguration::class.java)
    addBeanFactoryPostProcessor { beans ->
        val properties = KiraSecurityProperties(jwtSecret = JwtTestSupport.TEST_JWT_SECRET_BASE64)
        val mapper = ObjectMapper()
        beans.registerSingleton("complaintInstallationSecurityChainFactory", factory)
        beans.registerSingleton("kiraSecurityProperties", properties)
        beans.registerSingleton("jwtKeyProvider", JwtKeyProvider(properties))
        beans.registerSingleton("userRepository", users)
        beans.registerSingleton("problemAuthenticationEntryPoint", ProblemAuthenticationEntryPoint(mapper))
        beans.registerSingleton("problemAccessDeniedHandler", ProblemAccessDeniedHandler(mapper))
    }
    try {
        refresh()
    } catch (failure: Throwable) {
        close()
        throw failure
    }
}

@TestConfiguration(proxyBeanMethods = false)
internal class ComplaintSpringTestConfiguration {
    @Bean
    @Order(1)
    fun installationTestSecurityFilterChain(http: HttpSecurity, factory: ComplaintInstallationSecurityChainFactory): SecurityFilterChain = factory.build(http)

    @Bean
    fun mvcHandlerMappingIntrospector(): HandlerMappingIntrospector = HandlerMappingIntrospector()
}
