package me.manga.kira.backend.common.infrastructure.persistence

/** Settings support is not controlled-runtime approval or permission to open a complaint lane. */
internal sealed interface PersistenceNativeSettingsResult {
    class Supported(val endpoint: ResolvedPersistenceEndpoint) : PersistenceNativeSettingsResult {
        override fun toString(): String = "PersistenceNativeSettingsResult.Supported(redacted)"
    }

    class Unsupported(val reason: PersistenceNativeSettingsReason) : PersistenceNativeSettingsResult {
        override fun toString(): String = "PersistenceNativeSettingsResult.Unsupported(${reason.name})"
    }
}

/** Closed diagnostics contain neither rejected values nor configuration keys, paths or throwables. */
internal enum class PersistenceNativeSettingsReason {
    UNSUPPORTED_HOST_COUNT,
    UNSUPPORTED_SOCKET_EXTENSION,
    UNSUPPORTED_AUTHENTICATION_EXTENSION,
    UNSUPPORTED_SSL_FACTORY,
    UNSUPPORTED_HOSTNAME_VERIFIER,
    UNSUPPORTED_XML_FACTORY,
    MISSING_AUTHENTICATION_POLICY,
    INVALID_AUTHENTICATION_POLICY,
    EMPTY_AUTHENTICATION_POLICY,
    UNSUPPORTED_AUTHENTICATION_METHODS,
    INVALID_GSS_MODE,
    UNSUPPORTED_GSS_MODE,
    UNSUPPORTED_SCRAM_LIMIT,
    INVALID_SSL_MODE,
    INVALID_CHANNEL_BINDING,
    UNSUPPORTED_TLS_CALLBACK,
    UNSUPPORTED_TLS_IDENTITY,
    UNSUPPORTED_TLS_TRUST,
    INVALID_DRIVER_TIMEOUT,
}
