package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.stream.Stream

internal class PersistenceTlsSettingsTest {
    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @MethodSource("explicitModes")
    fun `explicit mode governs TLS identity and trust even when ssl requests verification`(vector: NativeSslCase) {
        verifyMode(vector.raw, "true", vector.expected)
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @MethodSource("sslFallbacks")
    fun `ssl fallback follows the pinned boolean rules without normalizing the configured value`(vector: NativeSslCase) {
        verifyMode(null, vector.raw, vector.expected)
    }

    private fun verifyMode(rawMode: String?, rawSsl: String?, expected: String?) {
        val withRoot = nativeTlsEndpoint("sslmode" to rawMode, "ssl" to rawSsl)
        val oracle = NativeDriverSettingsOracle.ssl(withRoot.driverProperties())
        if (expected == null) assertSame(NativeDriverParse.Invalid, oracle) else assertEquals(NativeDriverParse.Parsed(expected), oracle)
        val withoutRoot = nativeTlsEndpoint("sslmode" to rawMode, "ssl" to rawSsl, "sslrootcert" to null)
        val withoutIdentity = nativeEndpoint("sslmode" to rawMode, "ssl" to rawSsl)
        for (lane in NativeSettingsLane.entries) {
            assertNativeSupported(lane.assess(nativeTlsEndpoint()))
            when (expected) {
                null -> {
                    for (endpoint in listOf(withRoot, withoutRoot, withoutIdentity)) {
                        assertNativeRejected(PersistenceNativeSettingsReason.INVALID_SSL_MODE, lane.assess(endpoint))
                    }
                }

                "DISABLE" -> {
                    for (endpoint in listOf(withRoot, withoutRoot, withoutIdentity)) assertNativeSupported(lane.assess(endpoint))
                }

                else -> {
                    assertNativeSupported(lane.assess(withRoot))
                    assertNativeRejected(PersistenceNativeSettingsReason.UNSUPPORTED_TLS_IDENTITY, lane.assess(withoutIdentity))
                    if (expected in setOf("VERIFY_CA", "VERIFY_FULL")) {
                        assertNativeRejected(PersistenceNativeSettingsReason.UNSUPPORTED_TLS_TRUST, lane.assess(withoutRoot))
                    } else {
                        assertNativeSupported(lane.assess(withoutRoot))
                    }
                }
            }
        }
        assertEquals(rawMode, withRoot.driverProperties().getProperty("sslmode"))
        assertEquals(rawSsl, withRoot.driverProperties().getProperty("ssl"))
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @MethodSource("identities")
    fun `TLS identity needs either both explicit no key markers or absolute pkcs8 with a present password`(vector: NativeTlsIdentityCase, password: String?) {
        val endpoint = nativeTlsEndpoint("sslcert" to vector.cert, "sslkey" to vector.key, "sslpassword" to password)
        val before = nativeSnapshot(endpoint)
        val supported = if (password == null) vector.validWithoutPassword else vector.validWithPassword
        for (lane in NativeSettingsLane.entries) {
            assertNativeSupported(lane.assess(nativeTlsEndpoint()))
            val result = lane.assess(endpoint)
            if (supported) {
                val selected = assertNativeSupported(result)
                for (name in listOf("sslcert", "sslkey", "sslpassword", "sslrootcert", "sslmode")) {
                    assertEquals(before[name], selected.driverProperties().getProperty(name))
                }
            } else {
                assertNativeRejected(PersistenceNativeSettingsReason.UNSUPPORTED_TLS_IDENTITY, result)
            }
        }
        assertEquals(before, nativeSnapshot(endpoint))
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["disable", "allow", "prefer", "require", "verify-ca", "verify-full"])
    fun `only verifying modes require an explicit syntactically absolute root`(mode: String) {
        for (root in listOf(null, "", "relative-root.crt", "/synthetic\u0000/root.crt", "/synthetic-unopened/root.crt")) {
            val endpoint = nativeTlsEndpoint("sslmode" to mode, "sslrootcert" to root)
            val accepted = mode !in setOf("verify-ca", "verify-full") || root == "/synthetic-unopened/root.crt"
            for (lane in NativeSettingsLane.entries) {
                assertNativeSupported(lane.assess(nativeTlsEndpoint("sslmode" to mode)))
                if (accepted) {
                    val selected = assertNativeSupported(lane.assess(endpoint))
                    assertEquals(root, selected.driverProperties().getProperty("sslrootcert"))
                } else {
                    assertNativeRejected(PersistenceNativeSettingsReason.UNSUPPORTED_TLS_TRUST, lane.assess(endpoint))
                }
            }
        }
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @MethodSource("callbacks")
    fun `a present TLS password callback is not repaired even when both no key markers are empty`(mode: String, callback: String?) {
        val endpoint = nativeTlsEndpoint("sslmode" to mode, "sslpasswordcallback" to callback)
        for (lane in NativeSettingsLane.entries) {
            assertNativeSupported(lane.assess(nativeTlsEndpoint("sslmode" to mode)))
            if (callback == null || mode == "disable") {
                val selected = assertNativeSupported(lane.assess(endpoint))
                assertEquals(callback, selected.driverProperties().getProperty("sslpasswordcallback"))
            } else {
                assertNativeRejected(PersistenceNativeSettingsReason.UNSUPPORTED_TLS_CALLBACK, lane.assess(endpoint))
            }
        }
    }

    @Test
    fun `TLS disabled preserves unused identity trust password and callback values without inspecting a file`() {
        val endpoint = nativeEndpoint(
            "sslcert" to "not-a-certificate\u0000",
            "sslkey" to "not-a-key",
            "sslrootcert" to "not-a-root",
            "sslpassword" to "synthetic-unused-password",
            "sslpasswordcallback" to "synthetic.unavailable.Callback",
        )
        val before = nativeSnapshot(endpoint)
        for (lane in NativeSettingsLane.entries) {
            val selected = assertNativeSupported(lane.assess(endpoint))
            assertEquals(before.filterKeys { it !in NATIVE_DERIVED_KEYS }, nativeSnapshot(selected).filterKeys { it !in NATIVE_DERIVED_KEYS })
        }
        assertEquals(before, nativeSnapshot(endpoint))
    }

    companion object {
        @JvmStatic
        fun explicitModes(): Stream<NativeSslCase> = NativeTlsCases.modes.stream()

        @JvmStatic
        fun sslFallbacks(): Stream<NativeSslCase> = NativeTlsCases.fallback.stream()

        @JvmStatic
        fun identities(): Stream<Arguments> = NativeTlsCases.identities.flatMap { vector ->
            listOf(null, "", "synthetic-unused-password").map { password -> Arguments.of(vector, password) }
        }.stream()

        @JvmStatic
        fun callbacks(): Stream<Arguments> = listOf("disable", "allow", "prefer", "require", "verify-ca", "verify-full").flatMap { mode ->
            listOf(null, "", "synthetic.unavailable.Callback", " ").map { callback -> Arguments.of(mode, callback) }
        }.stream()
    }
}
