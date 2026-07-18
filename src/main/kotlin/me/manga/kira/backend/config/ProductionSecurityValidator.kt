package me.manga.kira.backend.config

import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import java.net.URI

/** Fails startup when the `prod` profile is combined with unsafe or incomplete security settings. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class ProductionSecurityValidator(private val environment: Environment, private val auth: KiraAuthProperties, private val security: KiraSecurityProperties) :
    ApplicationRunner {
    override fun run(args: ApplicationArguments?) {
        ProductionSecurityPolicy.validate(
            activeProfiles = environment.activeProfiles.toSet(),
            auth = auth,
            security = security,
            datasourceUrl = environment.getProperty("spring.datasource.url"),
        )
    }
}

/** Pure policy seam so every production rejection path is covered without booting a Spring context. */
internal object ProductionSecurityPolicy {
    fun validate(activeProfiles: Set<String>, auth: KiraAuthProperties, security: KiraSecurityProperties, datasourceUrl: String?) {
        if (PROD_PROFILE !in activeProfiles) return

        require(DEV_PROFILE !in activeProfiles) { "The dev and prod profiles must never be active together" }
        require(!auth.registrationEnabled) { "Open registration is forbidden in production" }
        require(security.jwtSecret !in knownInsecureJwtSecrets) {
            "A development or test JWT key is forbidden in production"
        }
        requireHttpsOrigin("kira.security.external-base-url", security.externalBaseUrl)
        security.allowedOrigins.forEach { requireHttpsOrigin("kira.security.allowed-origins", it) }

        val jdbcUrl = requireNotNull(datasourceUrl) { "spring.datasource.url is required in production" }
        require(jdbcUrl.startsWith("jdbc:postgresql://")) { "Production requires a PostgreSQL JDBC URL" }
        require(jdbcUrl.substringAfter('?', "").split('&').any { it.equals("sslmode=verify-full", ignoreCase = true) }) {
            "Production PostgreSQL must use sslmode=verify-full"
        }
    }

    private fun requireHttpsOrigin(property: String, value: String?) {
        val raw = requireNotNull(value?.takeIf { it.isNotBlank() }) { "$property is required in production" }
        val uri = runCatching { URI(raw) }.getOrElse { throw IllegalArgumentException("$property must be a valid HTTPS origin") }
        require(
            uri.scheme.equals("https", ignoreCase = true) &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null &&
                uri.query == null &&
                uri.fragment == null &&
                (uri.path.isNullOrEmpty() || uri.path == "/"),
        ) { "$property must be an HTTPS origin without credentials, path, query, or fragment" }
    }

    private const val PROD_PROFILE = "prod"
    private const val DEV_PROFILE = "dev"
    private val knownInsecureJwtSecrets =
        setOf(
            "a2lyYS1iYWNrZW5kLUxPQ0FMLURFVi1pbnNlY3VyZS1qd3Qtc2VjcmV0LWRvLW5vdC1zaGlwIQ==",
            "a2lyYS1iYWNrZW5kLVRFU1QtT05MWS1qd3Qtc2VjcmV0LWRvLW5vdC11c2UtaW4tcHJvZCE=",
        )
}
