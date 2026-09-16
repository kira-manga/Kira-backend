package me.manga.kira.backend.common.infrastructure.persistence

import java.util.Properties

/** String-only cold snapshots and exact pgjdbc 42.7.12 collision policy, not native-profile approval. */
internal object PersistenceDriverProperties {
    private val ENDPOINT_KEYS = setOf("PGHOST", "PGPORT", "PGDBNAME", "user", "password")
    private val GUARDED_KEYS = ENDPOINT_KEYS + setOf(
        "currentSchema", "options", "readOnly", "readOnlyMode", "replication",
        "ssl", "sslmode", "sslNegotiation", "sslcert", "sslkey", "sslrootcert", "sslpassword",
        "sslpasswordcallback", "sslfactory", "sslfactoryarg", "sslhostnameverifier", "pemKeyAlgorithm",
        "authenticationPluginClassName", "requireAuth", "channelBinding", "scramMaxIterations", "gssEncMode",
        "gsslib", "gssUseDefaultCreds", "jaasApplicationName", "jaasLogin", "kerberosServerName", "sspiServiceClass", "useSpnego",
        "socketFactory", "socketFactoryArg", "localSocketAddress", "targetServerType", "loadBalanceHosts", "hostRecheckSeconds",
        "tcpKeepAlive", "tcpNoDelay", "protocolVersion", "receiveBufferSize", "sendBufferSize", "maxSendBufferSize",
        "loginTimeout", "connectTimeout", "socketTimeout", "cancelSignalTimeout", "queryTimeout", "gssResponseTimeout", "sslResponseTimeout",
        "loggerFile", "loggerLevel", "logServerErrorDetail", "logUnclosedConnections", "xmlFactoryFactory",
    )

    fun isGuarded(name: String): Boolean = name in GUARDED_KEYS

    fun capture(source: Properties): MutableMap<String, String> {
        // stringPropertyNames alone silently omits non-String entries. Validate direct entries and
        // enumerate inherited names as well; getProperty follows the effective String-default chain.
        if (source.entries.any { it.key !is String || it.value !is String }) invalidProperties()
        val captured = linkedMapOf<String, String>()
        try {
            val names = source.propertyNames()
            while (names.hasMoreElements()) {
                val name = names.nextElement() as? String ?: invalidProperties()
                val value = source.getProperty(name) ?: invalidProperties()
                rejectService(name)
                captured[name] = value
            }
        } catch (_: ClassCastException) {
            invalidProperties()
        }
        return captured
    }

    fun withoutEndpoint(values: Map<String, String>): Map<String, String> = values.filterKeys { it !in ENDPOINT_KEYS }

    /** Higher-tier URL/extension values win only where a recognized guarded key does not disagree. */
    fun merge(lower: Map<String, String>, higher: Map<String, String>): MutableMap<String, String> {
        val merged = lower.toMutableMap()
        for ((name, value) in higher) {
            rejectService(name)
            val previous = merged[name]
            if (isGuarded(name) && previous != null && previous != value) {
                rejectPersistenceBoundary(PersistenceBoundaryFailureCode.JDBC_CONFIGURATION_CONFLICT)
            }
            merged[name] = value
        }
        return merged
    }

    fun rejectService(name: String) {
        if (name == "service") rejectPersistenceBoundary(PersistenceBoundaryFailureCode.UNSUPPORTED_JDBC_SERVICE)
    }

    private fun invalidProperties(): Nothing = rejectPersistenceBoundary(PersistenceBoundaryFailureCode.INVALID_JDBC_PROPERTIES)
}
