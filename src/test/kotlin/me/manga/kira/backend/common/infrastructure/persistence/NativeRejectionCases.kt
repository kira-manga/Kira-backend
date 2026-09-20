package me.manga.kira.backend.common.infrastructure.persistence

internal class NativeRejectionCase(
    val reason: PersistenceNativeSettingsReason,
    val lane: NativeSettingsLane = NativeSettingsLane.ORDINARY,
    val input: () -> ResolvedPersistenceEndpoint,
) {
    override fun toString(): String = reason.name
}

internal object NativeRejectionCases {
    val entries = listOf(
        NativeRejectionCase(PersistenceNativeSettingsReason.UNSUPPORTED_HOST_COUNT) {
            nativeEndpoint("PGHOST" to List(9) { "synthetic.example" }.joinToString(","), "PGPORT" to List(9) { "5432" }.joinToString(","))
        },
        NativeRejectionCase(PersistenceNativeSettingsReason.UNSUPPORTED_SOCKET_EXTENSION) { nativeEndpoint("socketFactoryArg" to "") },
        NativeRejectionCase(PersistenceNativeSettingsReason.UNSUPPORTED_AUTHENTICATION_EXTENSION) {
            nativeEndpoint("authenticationPluginClassName" to "synthetic.unavailable.Plugin")
        },
        NativeRejectionCase(PersistenceNativeSettingsReason.UNSUPPORTED_SSL_FACTORY) { nativeEndpoint("sslfactory" to "") },
        NativeRejectionCase(PersistenceNativeSettingsReason.UNSUPPORTED_HOSTNAME_VERIFIER) { nativeEndpoint("sslhostnameverifier" to "") },
        NativeRejectionCase(PersistenceNativeSettingsReason.UNSUPPORTED_XML_FACTORY) { nativeEndpoint("xmlFactoryFactory" to "LEGACY_INSECURE") },
        NativeRejectionCase(PersistenceNativeSettingsReason.MISSING_AUTHENTICATION_POLICY) { nativeEndpoint("requireAuth" to null) },
        NativeRejectionCase(PersistenceNativeSettingsReason.INVALID_AUTHENTICATION_POLICY) { nativeEndpoint("requireAuth" to "password,password") },
        NativeRejectionCase(PersistenceNativeSettingsReason.EMPTY_AUTHENTICATION_POLICY) { nativeEndpoint("requireAuth" to ",") },
        NativeRejectionCase(PersistenceNativeSettingsReason.UNSUPPORTED_AUTHENTICATION_METHODS) { nativeEndpoint("requireAuth" to "none") },
        NativeRejectionCase(PersistenceNativeSettingsReason.INVALID_GSS_MODE) { nativeEndpoint("gssEncMode" to "not-a-mode") },
        NativeRejectionCase(PersistenceNativeSettingsReason.UNSUPPORTED_GSS_MODE) { nativeEndpoint("gssEncMode" to "require") },
        NativeRejectionCase(PersistenceNativeSettingsReason.UNSUPPORTED_SCRAM_LIMIT) { nativeEndpoint("scramMaxIterations" to "0") },
        NativeRejectionCase(PersistenceNativeSettingsReason.INVALID_SSL_MODE) { nativeEndpoint("sslmode" to "not-a-mode") },
        NativeRejectionCase(PersistenceNativeSettingsReason.INVALID_CHANNEL_BINDING) { nativeEndpoint("channelBinding" to "not-a-mode") },
        NativeRejectionCase(PersistenceNativeSettingsReason.UNSUPPORTED_TLS_CALLBACK) { nativeTlsEndpoint("sslpasswordcallback" to "") },
        NativeRejectionCase(PersistenceNativeSettingsReason.UNSUPPORTED_TLS_IDENTITY) { nativeTlsEndpoint("sslcert" to null) },
        NativeRejectionCase(PersistenceNativeSettingsReason.UNSUPPORTED_TLS_TRUST) { nativeTlsEndpoint("sslrootcert" to "synthetic-relative.crt") },
        NativeRejectionCase(PersistenceNativeSettingsReason.INVALID_DRIVER_TIMEOUT, NativeSettingsLane.DELETION) {
            nativeEndpoint("socketTimeout" to "-1")
        },
    )
}
