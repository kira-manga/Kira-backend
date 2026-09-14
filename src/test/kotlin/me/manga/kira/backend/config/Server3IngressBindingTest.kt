package me.manga.kira.backend.config

import org.apache.catalina.valves.RemoteIpValve
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration
import org.springframework.boot.autoconfigure.web.ServerProperties
import org.springframework.boot.autoconfigure.web.embedded.EmbeddedWebServerFactoryCustomizerAutoConfiguration
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory
import org.springframework.boot.web.server.WebServer
import org.springframework.boot.web.servlet.ServletContextInitializer
import org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.mock.web.MockServletContext

/** Actual ConfigData/env binding and factory customizer pipeline; fake final WebServer never opens a listener. */
class Server3IngressBindingTest {
    @Test
    fun `committed profile binds provisioned peers before the customized factory may start`() {
        runEnvironment { context, factory ->
            assertNull(context.startupFailure)
            assertTrue(factory.requested && factory.started)
            assertEquals(listOf(HOST, ADMIN), context.getBean(KiraSecurityProperties::class.java).trustedProxies)
            assertTrue(context.getBean(KiraSecurityProperties::class.java).trustForwardedHeaders)
            assertEquals(ServerProperties.ForwardHeadersStrategy.NONE, context.getBean(ServerProperties::class.java).forwardHeadersStrategy)
            assertFalse(factory.engineValves.any { it is RemoteIpValve })
        }
    }

    @Test
    fun `real environment overrides and absent peers reject initialization before any server is requested`() {
        val overrides = listOf(
            mapOf("KIRA_SECURITY_TRUSTFORWARDEDHEADERS" to "false"),
            mapOf("KIRA_SECURITY_TRUSTEDPROXIES" to ""),
            mapOf("KIRA_SECURITY_TRUSTEDPROXIES" to "$HOST,$ADMIN,192.0.2.5"),
            mapOf("KIRA_SECURITY_TRUSTEDPROXIES" to "172.31.240.0/24,$ADMIN"),
            mapOf("KIRA_SECURITY_TRUSTEDPROXIES" to "192.0.2.5,$ADMIN"),
            mapOf("KIRA_SECURITY_TRUSTEDPROXIES" to "$HOST,$HOST"),
            mapOf("KIRA_SERVER3_HOST_PEER" to null),
            mapOf("KIRA_SERVER3_ADMIN_ADDRESS" to null),
            mapOf("KIRA_SERVER3_HOST_PEER" to "backend"),
            mapOf("KIRA_SERVER3_ADMIN_ADDRESS" to HOST),
            mapOf("SERVER_FORWARDHEADERSSTRATEGY" to "native"),
            mapOf("SERVER_FORWARDHEADERSSTRATEGY" to "framework"),
            mapOf("SERVER_TOMCAT_REMOTEIP_REMOTEIPHEADER" to "X-Forwarded-For"),
            mapOf("SERVER_TOMCAT_REMOTEIP_PROTOCOLHEADER" to "X-Forwarded-Proto"),
            mapOf("SPRING_PROFILES_ACTIVE" to "server3"),
            mapOf("SPRING_PROFILES_ACTIVE" to "prod,dev,server3"),
        )
        overrides.forEach { changes ->
            runEnvironment(changes) { context, factory ->
                assertNotNull(context.startupFailure, changes.keys.toString())
                assertFalse(factory.requested || factory.started, changes.keys.toString())
            }
        }
    }

    @Test
    fun `generic prod and direct profiles do not require server3 or prohibit generic proxy configuration`() {
        listOf("prod", "").forEach { profiles ->
            runEnvironment(
                mapOf(
                    "SPRING_PROFILES_ACTIVE" to profiles,
                    "KIRA_SERVER3_HOST_PEER" to null,
                    "KIRA_SERVER3_ADMIN_ADDRESS" to null,
                    "KIRA_SECURITY_TRUSTFORWARDEDHEADERS" to "true",
                    "KIRA_SECURITY_TRUSTEDPROXIES" to "192.0.2.0/24",
                    "SERVER_FORWARDHEADERSSTRATEGY" to "native",
                ),
            ) { context, factory ->
                assertNull(context.startupFailure)
                assertTrue(factory.requested && factory.started)
                assertTrue(factory.engineValves.any { it is RemoteIpValve }) // Pinned Boot customizer really ran.
            }
        }
    }

    private fun runEnvironment(
        changes: Map<String, String?> = emptyMap(),
        verify: (org.springframework.boot.test.context.assertj.AssertableApplicationContext, NoListenFactory) -> Unit,
    ) {
        val environment = mutableMapOf<String, Any>(
            "SPRING_PROFILES_ACTIVE" to "prod,server3",
            "KIRA_SERVER3_HOST_PEER" to HOST,
            "KIRA_SERVER3_ADMIN_ADDRESS" to ADMIN,
            "KIRA_JWT_SECRET" to "synthetic-binding-only-not-a-real-key",
            "KIRA_SECURITY_EXTERNAL_BASE_URL" to "https://api.example.invalid",
            "KIRA_SECURITY_THROTTLE_BACKEND" to "memory",
            "KIRA_SECURITY_THROTTLE_INSTANCE_COUNT" to "1",
        )
        changes.forEach { (name, value) -> if (value == null) environment.remove(name) else environment[name] = value }
        val factory = NoListenFactory()
        ApplicationContextRunner { AnnotationConfigServletWebServerApplicationContext() }
            .withInitializer { context ->
                context.environment.propertySources.apply {
                    remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
                    remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)
                    addFirst(SystemEnvironmentPropertySource("fixture-systemEnvironment", environment))
                    addFirst(MapPropertySource("fixture-config-location", mapOf("spring.config.location" to "classpath:/application.yml")))
                }
                ConfigDataApplicationContextInitializer().initialize(context)
            }
            .withConfiguration(
                AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration::class.java,
                    ServletWebServerFactoryAutoConfiguration::class.java,
                    EmbeddedWebServerFactoryCustomizerAutoConfiguration::class.java,
                ),
            )
            .withUserConfiguration(Server3IngressConfiguration::class.java)
            .withBean("servletWebServerFactory", NoListenFactory::class.java, { factory })
            .run { context -> verify(context, factory) }
    }

    private class NoListenFactory : TomcatServletWebServerFactory() {
        var requested = false
        var started = false

        override fun getWebServer(vararg initializers: ServletContextInitializer): WebServer {
            requested = true
            val servlet = MockServletContext()
            initializers.forEach { it.onStartup(servlet) }
            return object : WebServer {
                override fun start() {
                    started = true
                }

                override fun stop() = Unit

                override fun getPort(): Int = 0
            }
        }
    }

    private companion object {
        const val HOST = "172.31.240.1" // Synthetic only; provisioning is external.
        const val ADMIN = "172.31.240.3"
    }
}
