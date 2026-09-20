package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.lang.reflect.Modifier
import java.util.stream.Stream

internal class PersistenceNativeSettingsTest {
    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(ints = [1, 8, 9])
    fun `native host subset never rewrites endpoint order duplicates or port spelling`(count: Int) {
        val hosts = (0 until count).joinToString(",") { if (it % 2 == 0) "repeat.example" else "host$it.example" }
        val ports = (0 until count).joinToString(",") { if (it % 2 == 0) "05432" else "6543" }
        val endpoint = nativeEndpoint(
            "PGHOST" to hosts,
            "PGPORT" to ports,
            "PGDBNAME" to "synthetic-db",
            "currentSchema" to "synthetic_schema,public",
            "loginTimeout" to "1.25",
        )
        val before = nativeSnapshot(endpoint)
        for (lane in NativeSettingsLane.entries) {
            assertNativeSupported(lane.assess(nativeEndpoint()))
            if (count > 8) {
                assertNativeRejected(PersistenceNativeSettingsReason.UNSUPPORTED_HOST_COUNT, lane.assess(endpoint))
            } else {
                val selected = assertNativeSupported(lane.assess(endpoint))
                assertEquals(before.filterKeys { it !in NATIVE_DERIVED_KEYS }, nativeSnapshot(selected).filterKeys { it !in NATIVE_DERIVED_KEYS })
                assertEquals(hosts, selected.driverProperties().getProperty("PGHOST"))
                assertEquals(ports, selected.driverProperties().getProperty("PGPORT"))
                assertTrue(selected.credentialsMatch(ENDPOINT_TEST_USER, ENDPOINT_TEST_PASSWORD))
            }
        }
        assertEquals(before, nativeSnapshot(endpoint))
        assertEquals(1250L, endpoint.loginPolicy.durationMillis)
    }

    @Test
    fun `ordinary unsupported settings are a typed result while independently bounded deletion can still be derived`() {
        val endpoint = nativeEndpoint("requireAuth" to null, "gssEncMode" to null, "loginTimeout" to ".001")
        val before = nativeSnapshot(endpoint)
        assertNativeRejected(PersistenceNativeSettingsReason.MISSING_AUTHENTICATION_POLICY, NativeSettingsLane.ORDINARY.assess(endpoint))
        val deletion = assertNativeSupported(NativeSettingsLane.DELETION.assess(endpoint))
        assertEquals(NATIVE_SAFE_AUTH, deletion.driverProperties().getProperty("requireAuth"))
        assertEquals("disable", deletion.driverProperties().getProperty("gssEncMode"))
        assertEquals(before, nativeSnapshot(endpoint))
        assertEquals(1L, endpoint.loginPolicy.durationMillis)
    }

    @Test
    fun `accepted ordinary snapshot and every returned properties object remain isolated from caller mutation`() {
        val properties = nativeProperties("requireAuth" to " !none, !gss, !sspi ", "loginTimeout" to "1.25")
        val endpoint = resolveEndpoint(properties = properties)
        val before = nativeSnapshot(endpoint)
        val ordinary = assertNativeSupported(NativeSettingsLane.ORDINARY.assess(endpoint))
        val deletion = assertNativeSupported(NativeSettingsLane.DELETION.assess(endpoint))
        val deletionBefore = nativeSnapshot(deletion)
        assertSame(endpoint, ordinary)
        assertSame(endpoint.loginPolicy, ordinary.loginPolicy)
        assertNotSame(ordinary, deletion)
        properties.clear()
        properties.setProperty("user", "synthetic-mutated-user")
        val first = ordinary.driverProperties()
        val second = ordinary.driverProperties()
        assertNotSame(first, second)
        first.clear()
        second.setProperty("password", "synthetic-mutated-password")
        deletion.driverProperties().setProperty("requireAuth", "none")
        assertEquals(before, nativeSnapshot(ordinary))
        assertEquals(deletionBefore, nativeSnapshot(deletion))
        assertTrue(ordinary.credentialsMatch(ENDPOINT_TEST_USER, ENDPOINT_TEST_PASSWORD))
        assertTrue(deletion.credentialsMatch(ENDPOINT_TEST_USER, ENDPOINT_TEST_PASSWORD))
        assertEquals(1250L, ordinary.loginPolicy.durationMillis)
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @MethodSource("rejections")
    fun `every fixed rejection reason has a reachable isolated settings trigger`(vector: NativeRejectionCase) {
        assertNativeSupported(vector.lane.assess(nativeEndpoint()))
        assertNativeSupported(vector.lane.assess(nativeTlsEndpoint()))
        val endpoint = vector.input()
        val before = nativeSnapshot(endpoint)
        val login = endpoint.loginPolicy
        repeat(2) { assertNativeRejected(vector.reason, vector.lane.assess(endpoint)) }
        assertEquals(before, nativeSnapshot(endpoint))
        assertSame(login, endpoint.loginPolicy)
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @EnumSource(PersistenceNativeSettingsReason::class)
    fun `unsupported result retains only a fixed immutable reason rather than input or a throwable graph`(reason: PersistenceNativeSettingsReason) {
        val result = PersistenceNativeSettingsResult.Unsupported(reason)
        assertNativeRejected(reason, result)
        val fields = result.javaClass.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
        assertEquals(listOf(PersistenceNativeSettingsReason::class.java), fields.map { it.type })
        assertTrue(fields.all { Modifier.isFinal(it.modifiers) })
        assertFalse(Throwable::class.java.isInstance(result))
    }

    @Test
    fun `diagnostics remain opaque and settings support exposes no runtime approval flag`() {
        val result = NativeSettingsLane.ORDINARY.assess(nativeEndpoint("ApplicationName" to "synthetic-diagnostic-marker"))
        val endpoint = assertNativeSupported(result)
        assertEquals("PersistenceNativeSettingsResult.Supported(redacted)", result.toString())
        assertEquals("ResolvedPersistenceEndpoint(redacted)", endpoint.toString())
        assertEquals("PersistenceLoginPolicy(redacted)", endpoint.loginPolicy.toString())
        val fields = result.javaClass.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
        assertEquals(listOf(ResolvedPersistenceEndpoint::class.java), fields.map { it.type })
        assertTrue(fields.all { Modifier.isFinal(it.modifiers) })
        assertEquals(PersistenceNativeSettingsReason.entries.toSet(), NativeRejectionCases.entries.map { it.reason }.toSet())
        assertEquals(PersistenceNativeSettingsReason.entries.size, NativeRejectionCases.entries.size)
    }

    @Test
    fun `multiple invalid fields select one deterministic fixed reason without revealing rejected values`() {
        val endpoint = nativeTlsEndpoint(
            "socketFactory" to "synthetic.unavailable.SocketFactory",
            "authenticationPluginClassName" to "synthetic.unavailable.Plugin",
            "requireAuth" to "synthetic-sensitive-invalid-policy",
            "sslkey" to "synthetic-sensitive-invalid-path",
            "socketTimeout" to "synthetic-sensitive-invalid-timeout",
        )
        val before = nativeSnapshot(endpoint)
        for (lane in NativeSettingsLane.entries) {
            repeat(3) { assertNativeRejected(PersistenceNativeSettingsReason.UNSUPPORTED_SOCKET_EXTENSION, lane.assess(endpoint)) }
        }
        assertEquals(before, nativeSnapshot(endpoint))
    }

    companion object {
        @JvmStatic
        fun rejections(): Stream<NativeRejectionCase> = NativeRejectionCases.entries.stream()
    }
}
