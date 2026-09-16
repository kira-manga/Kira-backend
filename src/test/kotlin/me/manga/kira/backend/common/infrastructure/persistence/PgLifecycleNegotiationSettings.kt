package me.manga.kira.backend.common.infrastructure.persistence

import java.nio.file.Path

/** Exact immutable opening settings, not settings-only provider or TLS evidence. */
internal object PgLifecycleNegotiationSettings {
    fun endpoint(peer: PgLifecycleNegotiationPeer, mode: PgLifecycleNegotiationMode, root: Path?): ResolvedPersistenceEndpoint {
        check((root != null) == mode.requiresMaterial && root?.isAbsolute != false)
        val extras = mapOf(
            "sslmode" to mode.sslMode,
            "sslfactory" to "org.postgresql.ssl.LibPQFactory",
            "sslNegotiation" to "postgres",
            "sslcert" to "",
            "sslkey" to "",
            "sslResponseTimeout" to mode.sslResponseTimeout,
            "channelBinding" to "disable",
            "scramMaxIterations" to "100000",
            "queryTimeout" to "0",
            "readOnly" to "false",
            "targetServerType" to "any",
            "loadBalanceHosts" to "false",
            "hostRecheckSeconds" to "0",
        ) + (root?.let { mapOf("sslrootcert" to it.toString()) } ?: emptyMap()) +
            if (mode === PgLifecycleNegotiationMode.NEXT_HOST) {
                mapOf("PGHOST" to "127.0.0.1,127.0.0.1", "PGPORT" to "${peer.port},${peer.secondPort}")
            } else {
                emptyMap()
            }
        return pgProbeEndpoint(peer.port, extras)
    }

    fun verify(opening: PersistencePgDriverOpening, control: PersistenceOwnedCallerControl, deletion: Boolean, expected: ResolvedPersistenceEndpoint) {
        val endpoint = lifecycleField(opening, "endpoint") as ResolvedPersistenceEndpoint
        val properties = endpoint.driverProperties()
        val selected = expected.driverProperties()
        if (deletion) {
            selected.setProperty("connectTimeout", "1")
            selected.setProperty("socketTimeout", "2")
            selected.setProperty("cancelSignalTimeout", "1")
        }
        check(properties == selected) { "Negotiation changed or weakened the immutable opening settings." }
        check(properties.getProperty("sslfactory") == "org.postgresql.ssl.LibPQFactory")
        check(properties.getProperty("sslNegotiation") == "postgres" && properties.getProperty("sslcert") == "" && properties.getProperty("sslkey") == "")
        check(properties.getProperty("requireAuth") == "password" && properties.getProperty("gssEncMode") == "disable")
        check(properties.getProperty("channelBinding") == "disable" && properties.getProperty("loginTimeout") == "0")
        check(properties.getProperty("queryTimeout") == "0" && properties.getProperty("readOnly") == "false")
        check(properties.getProperty("targetServerType") == "any" && properties.getProperty("loadBalanceHosts") == "false")
        check(properties.getProperty("hostRecheckSeconds") == "0")
        val forbidden = listOf(
            "socketFactory",
            "socketFactoryArg",
            "sslhostnameverifier",
            "sslpasswordcallback",
            "authenticationPluginClassName",
            "xmlFactoryFactory",
        )
        check(forbidden.none(properties::containsKey))
        val allowance = if (deletion) 2_000L else 6_000L
        check(opening.loginPolicy.durationMillis == allowance)
        check(lifecycleField(control.budget, "allowanceNanos") == allowance * 1_000_000)
        check(properties.getProperty("connectTimeout") == if (deletion) "1" else "2")
        check(properties.getProperty("socketTimeout") == if (deletion) "2" else "3")
        check(properties.getProperty("cancelSignalTimeout") == if (deletion) "1" else "2")
    }
}
