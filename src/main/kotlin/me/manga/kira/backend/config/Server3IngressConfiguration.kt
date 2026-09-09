package me.manga.kira.backend.config

import me.manga.kira.backend.security.NumericIpAddress
import org.springframework.boot.autoconfigure.web.ServerProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.server.WebServerFactoryCustomizer
import org.springframework.boot.web.servlet.server.ConfigurableServletWebServerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.core.env.Environment
import org.springframework.util.StringUtils

/** Factory customization happens before getWebServer/listener start, unlike ApplicationRunner. */
@Configuration(proxyBeanMethods = false)
@Profile("server3")
@EnableConfigurationProperties(KiraServer3Properties::class, KiraSecurityProperties::class, ServerProperties::class)
class Server3IngressConfiguration {
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    fun server3IngressGuard(
        environment: Environment,
        declared: KiraServer3Properties,
        security: KiraSecurityProperties,
        server: ServerProperties,
    ): WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> = WebServerFactoryCustomizer {
        Server3IngressPolicy.validate(environment.activeProfiles.toSet(), declared, security, server)
    }
}

internal object Server3IngressPolicy {
    fun validate(profiles: Set<String>, declared: KiraServer3Properties, security: KiraSecurityProperties, server: ServerProperties) {
        require("prod" in profiles && "dev" !in profiles) { "server3 ingress requires prod without dev" }
        require(security.trustForwardedHeaders) { "server3 ingress requires trusted forwarding" }
        val host = requireNotNull(NumericIpAddress.parse(declared.hostPeer)) { "server3 requires one exact numeric host peer" }
        val admin = requireNotNull(NumericIpAddress.parse(declared.adminAddress)) { "server3 requires one exact numeric Admin address" }
        val expected = setOf(host.canonical, admin.canonical)
        require(expected.size == 2) { "server3 host and Admin peers must differ" }
        val actual = security.trustedProxies.map { NumericIpAddress.parse(it)?.canonical }
        require(actual.size == 2 && actual.toSet() == expected) { "server3 trusted proxies must equal its two provisioned exact peers" }
        require(server.forwardHeadersStrategy == ServerProperties.ForwardHeadersStrategy.NONE) { "server3 requires framework forwarding NONE" }
        val remoteIp = server.tomcat.remoteip
        require(!StringUtils.hasText(remoteIp.remoteIpHeader) && !StringUtils.hasText(remoteIp.protocolHeader)) {
            "server3 forbids Tomcat remote-IP and protocol header rewriting"
        }
    }
}
