package me.manga.kira.backend.security

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.Filter
import me.manga.kira.backend.config.ComplaintTestBootstrapHttpCompositionV1
import me.manga.kira.backend.config.ComplaintTestBootstrapHttpConfigurationV1
import me.manga.kira.backend.config.KiraSecurityProperties
import me.manga.kira.backend.config.WebDiagnosticsConfig
import me.manga.kira.backend.support.JwtTestSupport
import me.manga.kira.backend.user.domain.UserRepository
import org.junit.jupiter.api.Assertions.assertNull
import org.mockito.Mockito.mock
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor
import org.springframework.beans.factory.support.RootBeanDefinition
import org.springframework.boot.autoconfigure.security.SecurityProperties
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.mock.web.MockServletContext
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.util.function.Supplier

/** Actual production optional configuration/mapping/filter order; no replacement route registry or TEST issuer. */
internal class ComplaintBootstrapSpringTestFixture(composition: ComplaintTestBootstrapHttpCompositionV1? = null) : AutoCloseable {
    val users: UserRepository = mock(UserRepository::class.java)
    val context = AnnotationConfigWebApplicationContext()
    val mvc: MockMvc

    init {
        context.servletContext = MockServletContext()
        context.register(SecurityConfig::class.java, WebDiagnosticsConfig::class.java,
            ComplaintTestBootstrapHttpConfigurationV1::class.java, ComplaintBootstrapMvcTestConfiguration::class.java)
        // Before condition evaluation, just as an explicit selected TEST assembly must register its real bean.
        context.addBeanFactoryPostProcessor(object : BeanDefinitionRegistryPostProcessor {
            override fun postProcessBeanDefinitionRegistry(registry: BeanDefinitionRegistry) {
                composition?.let { selected ->
                    registry.registerBeanDefinition("registeredTestBootstrapComposition", RootBeanDefinition(ComplaintTestBootstrapHttpCompositionV1::class.java).apply {
                        instanceSupplier = Supplier { selected }
                    })
                }
            }

            override fun postProcessBeanFactory(beans: ConfigurableListableBeanFactory) {
                val properties = KiraSecurityProperties(jwtSecret = JwtTestSupport.TEST_JWT_SECRET_BASE64)
                val mapper = ObjectMapper()
                beans.registerSingleton("kiraSecurityProperties", properties)
                beans.registerSingleton("jwtKeyProvider", JwtKeyProvider(properties))
                beans.registerSingleton("userRepository", users)
                beans.registerSingleton("objectMapper", mapper)
                beans.registerSingleton("problemAuthenticationEntryPoint", ProblemAuthenticationEntryPoint(mapper))
                beans.registerSingleton("problemAccessDeniedHandler", ProblemAccessDeniedHandler(mapper))
            }
        })
        try {
            context.refresh()
            val registrations = context.getBeansOfType(FilterRegistrationBean::class.java).values.map { it.order to it.filter }
            val security = SecurityProperties.DEFAULT_FILTER_ORDER to context.getBean("springSecurityFilterChain", Filter::class.java)
            val filters = (registrations + security).sortedBy { it.first }.map { it.second }
            mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(*filters.toTypedArray()).build()
        } catch (failure: Throwable) {
            context.close()
            throw failure
        }
    }

    override fun close() {
        context.close()
        assertNull(SecurityContextHolder.getContext().authentication)
    }
}

@TestConfiguration(proxyBeanMethods = false)
@EnableWebMvc
internal class ComplaintBootstrapMvcTestConfiguration
