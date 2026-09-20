package me.manga.kira.backend.common.infrastructure.persistence

internal class NativeExtensionCase(val label: String, val key: String, val value: String?, val reason: PersistenceNativeSettingsReason?) {
    override fun toString(): String = label
}

internal object NativeExtensionCases {
    private val socket = PersistenceNativeSettingsReason.UNSUPPORTED_SOCKET_EXTENSION
    private val auth = PersistenceNativeSettingsReason.UNSUPPORTED_AUTHENTICATION_EXTENSION
    private val ssl = PersistenceNativeSettingsReason.UNSUPPORTED_SSL_FACTORY
    private val verifier = PersistenceNativeSettingsReason.UNSUPPORTED_HOSTNAME_VERIFIER
    private val xml = PersistenceNativeSettingsReason.UNSUPPORTED_XML_FACTORY
    val entries = listOf(
        NativeExtensionCase("socket-absent", "socketFactory", null, null),
        NativeExtensionCase("socket-empty", "socketFactory", "", socket),
        NativeExtensionCase("socket-custom", "socketFactory", "synthetic.unavailable.SocketFactory", socket),
        NativeExtensionCase("socket-owned-looking", "socketFactory", "me.manga.kira.backend.TrackedSocketFactory", socket),
        NativeExtensionCase("socket-arg-absent", "socketFactoryArg", null, null),
        NativeExtensionCase("socket-arg-empty", "socketFactoryArg", "", socket),
        NativeExtensionCase("socket-arg-without-factory", "socketFactoryArg", "synthetic-argument", socket),
        NativeExtensionCase("auth-absent", "authenticationPluginClassName", null, null),
        NativeExtensionCase("auth-empty", "authenticationPluginClassName", "", null),
        NativeExtensionCase("auth-custom", "authenticationPluginClassName", "synthetic.unavailable.AuthenticationPlugin", auth),
        NativeExtensionCase("auth-space", "authenticationPluginClassName", " ", auth),
        NativeExtensionCase("ssl-absent", "sslfactory", null, null),
        NativeExtensionCase("ssl-stock", "sslfactory", "org.postgresql.ssl.LibPQFactory", null),
        NativeExtensionCase("ssl-legacy-stock", "sslfactory", "org.postgresql.ssl.jdbc4.LibPQFactory", null),
        NativeExtensionCase("ssl-empty", "sslfactory", "", ssl),
        NativeExtensionCase("ssl-custom", "sslfactory", "synthetic.unavailable.SslFactory", ssl),
        NativeExtensionCase("ssl-nonvalidating", "sslfactory", "org.postgresql.ssl.NonValidatingFactory", ssl),
        NativeExtensionCase("ssl-leading-space", "sslfactory", " org.postgresql.ssl.LibPQFactory", ssl),
        NativeExtensionCase("ssl-wrong-case", "sslfactory", "org.postgresql.ssl.libpqfactory", ssl),
        NativeExtensionCase("verifier-absent", "sslhostnameverifier", null, null),
        NativeExtensionCase("verifier-stock", "sslhostnameverifier", "org.postgresql.ssl.PGjdbcHostnameVerifier", null),
        NativeExtensionCase("verifier-empty", "sslhostnameverifier", "", verifier),
        NativeExtensionCase("verifier-custom", "sslhostnameverifier", "synthetic.unavailable.Verifier", verifier),
        NativeExtensionCase("verifier-trailing-space", "sslhostnameverifier", "org.postgresql.ssl.PGjdbcHostnameVerifier ", verifier),
        NativeExtensionCase("xml-absent", "xmlFactoryFactory", null, null),
        NativeExtensionCase("xml-empty", "xmlFactoryFactory", "", null),
        NativeExtensionCase("xml-private-constructor-is-not-alias", "xmlFactoryFactory", "org.postgresql.xml.DefaultPGXmlFactoryFactory", xml),
        NativeExtensionCase("xml-insecure", "xmlFactoryFactory", "LEGACY_INSECURE", xml),
        NativeExtensionCase("xml-custom", "xmlFactoryFactory", "synthetic.unavailable.XmlFactoryFactory", xml),
        NativeExtensionCase("xml-space", "xmlFactoryFactory", " ", xml),
        NativeExtensionCase("stock-unused-ssl-arg-absent", "sslfactoryarg", null, null),
        NativeExtensionCase("stock-unused-ssl-arg-empty", "sslfactoryarg", "", null),
        NativeExtensionCase("stock-unused-ssl-arg-value", "sslfactoryarg", "synthetic-argument", null),
    )
}
