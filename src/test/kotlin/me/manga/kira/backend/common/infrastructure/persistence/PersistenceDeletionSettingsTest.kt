package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

internal class PersistenceDeletionSettingsTest {
    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @MethodSource("timeoutCases")
    fun `deletion timeouts cap only valid positive or unlimited values and never repair malformed input`(
        property: NativeIntegerProperty,
        vector: NativeIntegerCase,
        cap: Int,
    ) {
        val endpoint = nativeEndpoint(property.key to vector.raw, "loginTimeout" to ".001")
        val before = nativeSnapshot(endpoint)
        assertSame(endpoint, assertNativeSupported(NativeSettingsLane.ORDINARY.assess(endpoint)))
        assertNativeSupported(NativeSettingsLane.DELETION.assess(nativeEndpoint()))
        val oracle = NativeDriverSettingsOracle.integer(property, endpoint.driverProperties())
        if (vector.value == null) assertSame(NativeDriverParse.Invalid, oracle) else assertEquals(NativeDriverParse.Parsed(vector.value), oracle)
        val result = NativeSettingsLane.DELETION.assess(endpoint)
        if (vector.value == null || vector.value < 0) {
            assertNativeRejected(PersistenceNativeSettingsReason.INVALID_DRIVER_TIMEOUT, result)
        } else {
            val selected = if (vector.value == 0) cap else minOf(vector.value, cap)
            verifyDerived(endpoint, assertNativeSupported(result), before + defaultDerivation() + (property.key to selected.toString()))
        }
        assertEquals(before, nativeSnapshot(endpoint))
        assertEquals(1L, endpoint.loginPolicy.durationMillis)
    }

    @Test
    fun `absent timeout defaults are explicitly bounded in a separate copy not inherited from driver defaults`() {
        val endpoint = nativeEndpoint()
        val before = nativeSnapshot(endpoint)
        for (property in listOf(NativeIntegerProperty.CONNECT, NativeIntegerProperty.SOCKET, NativeIntegerProperty.CANCEL)) {
            assertEquals(NativeDriverParse.Parsed(property.driverDefault), NativeDriverSettingsOracle.integer(property, endpoint.driverProperties()))
            assertEquals(null, before[property.key])
        }
        verifyDerived(endpoint, assertNativeSupported(NativeSettingsLane.DELETION.assess(endpoint)), before + defaultDerivation())
        assertEquals(before, nativeSnapshot(endpoint))
        assertEquals(30_000L, endpoint.loginPolicy.durationMillis)
    }

    @Test
    fun `already bounded keys need not differ and stricter auth SCRAM and socket values are retained`() {
        val endpoint = nativeTlsEndpoint(
            "requireAuth" to "md5",
            "gssEncMode" to "disable",
            "scramMaxIterations" to "1",
            "connectTimeout" to "1",
            "socketTimeout" to "1",
            "cancelSignalTimeout" to "1",
            "loginTimeout" to "1.25",
            "sslNegotiation" to "direct",
            "channelBinding" to "require",
        )
        val before = nativeSnapshot(endpoint)
        val deletion = assertNativeSupported(NativeSettingsLane.DELETION.assess(endpoint))
        verifyDerived(endpoint, deletion, before)
        assertEquals(1250L, endpoint.loginPolicy.durationMillis)
        assertEquals(2000L, deletion.loginPolicy.durationMillis)
        assertEquals(before, nativeSnapshot(assertNativeSupported(NativeSettingsLane.ORDINARY.assess(endpoint))))
    }

    @Test
    fun `only the declared derived keys change while all unrelated transport security and principal values survive`() {
        val endpoint = nativeTlsEndpoint(
            "requireAuth" to "!none,!gss,!sspi",
            "gssEncMode" to "DISABLE",
            "scramMaxIterations" to "+00001",
            "connectTimeout" to "0",
            "socketTimeout" to "9",
            "cancelSignalTimeout" to "10",
            "loginTimeout" to "17.125",
            "currentSchema" to "synthetic_schema,public",
            "ApplicationName" to "synthetic-ordinary-service",
            "targetServerType" to "primary",
            "loadBalanceHosts" to "true",
            "sslNegotiation" to "direct",
            "channelBinding" to "require",
            "sslfactory" to "org.postgresql.ssl.jdbc4.LibPQFactory",
            "sslfactoryarg" to "synthetic-unused-argument",
            "sslhostnameverifier" to "org.postgresql.ssl.PGjdbcHostnameVerifier",
            "jaasApplicationName" to "synthetic-untouched-jaas",
            "gsslib" to "sspi",
            "kerberosServerName" to "synthetic-untouched-service",
            "options" to "-c synthetic.setting=untouched",
            url = "jdbc:postgresql://same.example:05432,other.example:6543,same.example:05432/synthetic-db",
        )
        val before = nativeSnapshot(endpoint)
        val deletion = assertNativeSupported(NativeSettingsLane.DELETION.assess(endpoint))
        verifyDerived(endpoint, deletion, before + defaultDerivation() + ("scramMaxIterations" to "1"))
        assertEquals(before, nativeSnapshot(endpoint))
        assertEquals(17_125L, endpoint.loginPolicy.durationMillis)
        assertEquals("same.example,other.example,same.example", deletion.driverProperties().getProperty("PGHOST"))
        assertEquals("05432,6543,05432", deletion.driverProperties().getProperty("PGPORT"))
        assertTrue(deletion.credentialsMatch(ENDPOINT_TEST_USER, ENDPOINT_TEST_PASSWORD))
    }

    private fun verifyDerived(original: ResolvedPersistenceEndpoint, derived: ResolvedPersistenceEndpoint, expected: Map<String, String>) {
        val before = nativeSnapshot(original)
        val actual = nativeSnapshot(derived)
        assertNotSame(original, derived)
        assertNotSame(original.loginPolicy, derived.loginPolicy)
        assertEquals(expected, actual)
        assertEquals(before.filterKeys { it !in NATIVE_DERIVED_KEYS }, actual.filterKeys { it !in NATIVE_DERIVED_KEYS })
        assertTrue((before.keys + actual.keys).filter { before[it] != actual[it] }.all { it in NATIVE_DERIVED_KEYS })
        assertEquals("jdbc:postgresql://", derived.driverUrl)
        assertEquals("0", actual["loginTimeout"])
        assertEquals(2000L, derived.loginPolicy.durationMillis)
        assertEquals(2, derived.loginPolicy.jdbcSeconds)
        derived.driverProperties().clear()
        assertEquals(actual, nativeSnapshot(derived))
        assertEquals(before, nativeSnapshot(original))
    }

    private fun defaultDerivation(): Map<String, String> = mapOf(
        "requireAuth" to NATIVE_SAFE_AUTH,
        "gssEncMode" to "disable",
        "scramMaxIterations" to "100000",
        "loginTimeout" to "0",
        "connectTimeout" to "1",
        "socketTimeout" to "2",
        "cancelSignalTimeout" to "1",
    )

    companion object {
        @JvmStatic
        fun timeoutCases(): Stream<Arguments> = listOf(
            NativeIntegerProperty.CONNECT to 1,
            NativeIntegerProperty.SOCKET to 2,
            NativeIntegerProperty.CANCEL to 1,
        ).flatMap { (property, cap) ->
            NativeIntegerCases.exact.map { vector -> Arguments.of(property, vector, cap) }
        }.stream()
    }
}
