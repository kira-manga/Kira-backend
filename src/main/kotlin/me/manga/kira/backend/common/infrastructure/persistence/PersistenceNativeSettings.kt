package me.manga.kira.backend.common.infrastructure.persistence

import java.util.Properties

/** Unused cold prerequisite. Never register as a bean or substitute settings support for runtime approval. */
internal object PersistenceNativeSettings {
    val deletionLoginPolicy: PersistenceLoginPolicy = PersistenceLoginPolicy.resolve("2", 2000)
    private val SSL_FACTORIES = setOf(null, "org.postgresql.ssl.LibPQFactory", "org.postgresql.ssl.jdbc4.LibPQFactory")
    private val HOSTNAME_VERIFIERS = setOf(null, "org.postgresql.ssl.PGjdbcHostnameVerifier")
    private val TIMEOUT_CAPS = mapOf("connectTimeout" to 1, "socketTimeout" to 2, "cancelSignalTimeout" to 1)

    fun assessOrdinary(endpoint: ResolvedPersistenceEndpoint, pathStyle: PersistencePathStyle): PersistenceNativeSettingsResult =
        assess(endpoint, pathStyle, deletion = false)

    fun deriveDeletion(endpoint: ResolvedPersistenceEndpoint, pathStyle: PersistencePathStyle): PersistenceNativeSettingsResult =
        assess(endpoint, pathStyle, deletion = true)

    private fun assess(endpoint: ResolvedPersistenceEndpoint, pathStyle: PersistencePathStyle, deletion: Boolean): PersistenceNativeSettingsResult {
        val properties = endpoint.driverProperties()
        val shapeFailure = shapeFailure(properties)
        if (shapeFailure != null) return PersistenceNativeSettingsResult.Unsupported(shapeFailure)
        val auth = when (val decision = PersistenceAuthenticationSettings.assess(properties, deletion)) {
            is PersistenceAuthenticationSettings.Decision.Rejected -> return PersistenceNativeSettingsResult.Unsupported(decision.reason)
            is PersistenceAuthenticationSettings.Decision.Selected -> decision
        }
        val tlsFailure = PersistenceTlsSettings.failure(properties, pathStyle)
        if (tlsFailure != null) return PersistenceNativeSettingsResult.Unsupported(tlsFailure)
        if (!deletion) return PersistenceNativeSettingsResult.Supported(endpoint)
        return deletionSettings(properties, auth)
    }

    private fun shapeFailure(properties: Properties): PersistenceNativeSettingsReason? = when {
        !supportedHostCount(properties) -> PersistenceNativeSettingsReason.UNSUPPORTED_HOST_COUNT

        properties.containsKey("socketFactory") || properties.containsKey("socketFactoryArg") ->
            PersistenceNativeSettingsReason.UNSUPPORTED_SOCKET_EXTENSION

        !properties.getProperty("authenticationPluginClassName").isNullOrEmpty() ->
            PersistenceNativeSettingsReason.UNSUPPORTED_AUTHENTICATION_EXTENSION

        properties.getProperty("sslfactory") !in SSL_FACTORIES -> PersistenceNativeSettingsReason.UNSUPPORTED_SSL_FACTORY

        properties.getProperty("sslhostnameverifier") !in HOSTNAME_VERIFIERS -> PersistenceNativeSettingsReason.UNSUPPORTED_HOSTNAME_VERIFIER

        !properties.getProperty("xmlFactoryFactory").isNullOrEmpty() -> PersistenceNativeSettingsReason.UNSUPPORTED_XML_FACTORY

        else -> null
    }

    private fun supportedHostCount(properties: Properties): Boolean {
        val hosts = properties.getProperty("PGHOST")?.split(',') ?: return false
        return hosts.size in 1..8 && hosts.none { it.isEmpty() }
    }

    private fun deletionSettings(properties: Properties, auth: PersistenceAuthenticationSettings.Decision.Selected): PersistenceNativeSettingsResult {
        val selected = properties.stringPropertyNames().associateWith { properties.getProperty(it) }.toMutableMap()
        for ((name, cap) in TIMEOUT_CAPS) {
            val raw = selected[name]
            val value = if (raw == null) cap else persistenceDriverInt(raw)
            if (value == null || value < 0) return PersistenceNativeSettingsResult.Unsupported(PersistenceNativeSettingsReason.INVALID_DRIVER_TIMEOUT)
            selected[name] = (if (value == 0) cap else minOf(value, cap)).toString()
        }
        selected["requireAuth"] = auth.positivePolicy
        selected["gssEncMode"] = "disable"
        selected["scramMaxIterations"] = auth.scramLimit.toString()
        selected["loginTimeout"] = "0"
        return PersistenceNativeSettingsResult.Supported(
            ResolvedPersistenceEndpoint(selected, deletionLoginPolicy),
        )
    }
}
