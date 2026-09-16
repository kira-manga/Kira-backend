package me.manga.kira.backend.common.infrastructure.persistence

import java.util.Properties

/** Stock settings shape, not a certificate, provider, handshake, file-work or runtime attestation. */
internal object PersistenceTlsSettings {
    private val CHANNEL_BINDING = setOf("disable", "prefer", "require")

    fun failure(properties: Properties, pathStyle: PersistencePathStyle): PersistenceNativeSettingsReason? {
        val mode = mode(properties) ?: return PersistenceNativeSettingsReason.INVALID_SSL_MODE
        if (properties.getProperty("channelBinding", "prefer") !in CHANNEL_BINDING) {
            return PersistenceNativeSettingsReason.INVALID_CHANNEL_BINDING
        }
        if (mode == Mode.DISABLE) return null
        if (properties.containsKey("sslpasswordcallback")) return PersistenceNativeSettingsReason.UNSUPPORTED_TLS_CALLBACK
        return identityFailure(properties, pathStyle) ?: if (
            mode.verifies && !pathStyle.accepts(properties.getProperty("sslrootcert") ?: "")
        ) {
            PersistenceNativeSettingsReason.UNSUPPORTED_TLS_TRUST
        } else {
            null
        }
    }

    private fun identityFailure(properties: Properties, pathStyle: PersistencePathStyle): PersistenceNativeSettingsReason? {
        val cert = properties.getProperty("sslcert")
        val key = properties.getProperty("sslkey")
        if (cert == "" && key == "") return null
        if (cert == null || key == null) return PersistenceNativeSettingsReason.UNSUPPORTED_TLS_IDENTITY
        if (!pathStyle.accepts(cert) || !pathStyle.accepts(key)) return PersistenceNativeSettingsReason.UNSUPPORTED_TLS_IDENTITY
        return if (key.endsWith(".pk8") && properties.containsKey("sslpassword")) {
            null
        } else {
            PersistenceNativeSettingsReason.UNSUPPORTED_TLS_IDENTITY
        }
    }

    private fun mode(properties: Properties): Mode? {
        val raw = properties.getProperty("sslmode")
        if (raw != null) return Mode.entries.firstOrNull { it.value.equals(raw, ignoreCase = true) }
        val ssl = properties.getProperty("ssl")
        return if (ssl == "" || "true".equals(ssl, ignoreCase = true)) Mode.VERIFY_FULL else Mode.PREFER
    }

    private enum class Mode(val value: String, val verifies: Boolean = false) {
        DISABLE("disable"),
        ALLOW("allow"),
        PREFER("prefer"),
        REQUIRE("require"),
        VERIFY_CA("verify-ca", verifies = true),
        VERIFY_FULL("verify-full", verifies = true),
    }
}
