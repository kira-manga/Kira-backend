package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import java.util.Properties

internal const val NATIVE_SAFE_AUTH = "password,md5,scram-sha-256"
internal val NATIVE_AUTH_METHODS = listOf("none", "password", "md5", "gss", "sspi", "scram-sha-256")
internal val NATIVE_SAFE_METHODS = listOf("password", "md5", "scram-sha-256")
internal val NATIVE_DERIVED_KEYS = setOf(
    "requireAuth",
    "gssEncMode",
    "scramMaxIterations",
    "loginTimeout",
    "connectTimeout",
    "socketTimeout",
    "cancelSignalTimeout",
)

internal enum class NativeSettingsLane {
    ORDINARY,
    DELETION,
    ;

    fun assess(endpoint: ResolvedPersistenceEndpoint, pathStyle: PersistencePathStyle = PersistencePathStyle.POSIX): PersistenceNativeSettingsResult =
        when (this) {
            ORDINARY -> PersistenceNativeSettings.assessOrdinary(endpoint, pathStyle)
            DELETION -> PersistenceNativeSettings.deriveDeletion(endpoint, pathStyle)
        }
}

internal fun nativeProperties(vararg changes: Pair<String, String?>): Properties = endpointProperties(
    "requireAuth" to NATIVE_SAFE_AUTH,
    "gssEncMode" to "disable",
    "sslmode" to "disable",
).apply {
    for ((name, value) in changes) {
        if (value == null) remove(name) else setProperty(name, value)
    }
}

internal fun nativeEndpoint(
    vararg changes: Pair<String, String?>,
    url: String = "jdbc:postgresql://",
    checkoutMillis: Long = 30_000,
): ResolvedPersistenceEndpoint = resolveEndpoint(url, nativeProperties(*changes), checkoutMillis = checkoutMillis)

internal fun nativeTlsEndpoint(vararg changes: Pair<String, String?>, url: String = "jdbc:postgresql://"): ResolvedPersistenceEndpoint = nativeEndpoint(
    "sslmode" to "verify-full",
    "sslcert" to "",
    "sslkey" to "",
    "sslrootcert" to "/synthetic-unopened/root.crt",
    *changes,
    url = url,
)

internal fun nativeSnapshot(endpoint: ResolvedPersistenceEndpoint): Map<String, String> {
    val properties = endpoint.driverProperties()
    return properties.stringPropertyNames().associateWith { properties.getProperty(it) }
}

internal fun assertNativeSupported(result: PersistenceNativeSettingsResult): ResolvedPersistenceEndpoint =
    assertInstanceOf(PersistenceNativeSettingsResult.Supported::class.java, result).endpoint

internal fun assertNativeRejected(reason: PersistenceNativeSettingsReason, result: PersistenceNativeSettingsResult) {
    val rejected = assertInstanceOf(PersistenceNativeSettingsResult.Unsupported::class.java, result)
    assertEquals(reason, rejected.reason)
    assertEquals("PersistenceNativeSettingsResult.Unsupported(" + reason.name + ")", rejected.toString())
}
