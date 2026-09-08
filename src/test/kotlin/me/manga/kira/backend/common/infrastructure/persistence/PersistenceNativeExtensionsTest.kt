package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

internal class PersistenceNativeExtensionsTest {
    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @MethodSource("extensions")
    fun `extension eligibility is presence sensitive even when TLS is disabled`(vector: NativeExtensionCase) {
        for (mode in listOf("disable", "verify-full")) {
            val endpoint = nativeTlsEndpoint("sslmode" to mode, vector.key to vector.value)
            val before = nativeSnapshot(endpoint)
            for (lane in NativeSettingsLane.entries) {
                assertNativeSupported(lane.assess(nativeTlsEndpoint("sslmode" to mode)))
                val result = lane.assess(endpoint)
                if (vector.reason != null) {
                    assertNativeRejected(vector.reason, result)
                } else {
                    val selected = assertNativeSupported(result)
                    assertEquals(vector.value, selected.driverProperties().getProperty(vector.key))
                    assertEquals(before.filterKeys { it !in NATIVE_DERIVED_KEYS }, nativeSnapshot(selected).filterKeys { it !in NATIVE_DERIVED_KEYS })
                }
            }
            assertEquals(before, nativeSnapshot(endpoint))
        }
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @MethodSource("channelBindings")
    fun `source derived channel binding grammar retains require and rejects invalid values without private reflection`(raw: String?, valid: Boolean) {
        // pgjdbc 42.7.12 ChannelBinding is package-private. This is a source-derived, not differential, oracle.
        for (mode in listOf("disable", "verify-full")) {
            val endpoint = nativeTlsEndpoint("sslmode" to mode, "channelBinding" to raw)
            for (lane in NativeSettingsLane.entries) {
                assertNativeSupported(lane.assess(nativeTlsEndpoint("sslmode" to mode)))
                if (valid) {
                    val selected = assertNativeSupported(lane.assess(endpoint))
                    assertEquals(raw, selected.driverProperties().getProperty("channelBinding"))
                } else {
                    assertNativeRejected(PersistenceNativeSettingsReason.INVALID_CHANNEL_BINDING, lane.assess(endpoint))
                }
            }
        }
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @MethodSource("negotiations")
    fun `negotiation remains exact so only lowercase direct selects the public driver DIRECT mode`(raw: String?, expected: String) {
        assertEquals(NativeDriverParse.Parsed(expected), NativeDriverSettingsOracle.negotiation(raw))
        val endpoint = nativeTlsEndpoint("sslNegotiation" to raw, "channelBinding" to "require")
        val before = nativeSnapshot(endpoint)
        for (lane in NativeSettingsLane.entries) {
            val selected = assertNativeSupported(lane.assess(endpoint))
            assertEquals(raw, selected.driverProperties().getProperty("sslNegotiation"))
            assertEquals("require", selected.driverProperties().getProperty("channelBinding"))
            assertEquals(NativeDriverParse.Parsed(expected), NativeDriverSettingsOracle.negotiation(selected.driverProperties().getProperty("sslNegotiation")))
        }
        assertEquals(before, nativeSnapshot(endpoint))
    }

    companion object {
        @JvmStatic
        fun extensions(): Stream<NativeExtensionCase> = NativeExtensionCases.entries.stream()

        @JvmStatic
        fun channelBindings(): Stream<Arguments> = listOf(
            null to true, "disable" to true, "prefer" to true, "require" to true,
            "" to false, "DISABLE" to false, "Prefer" to false, "REQUIRE" to false, " require" to false,
            "require " to false, "prefer\u0000" to false, "\u00a0prefer" to false, "unknown" to false,
        ).map { (raw, valid) -> Arguments.of(raw, valid) }.stream()

        @JvmStatic
        fun negotiations(): Stream<Arguments> = listOf(
            null to "POSTGRES", "postgres" to "POSTGRES", "direct" to "DIRECT", "DIRECT" to "POSTGRES",
            "" to "POSTGRES", " direct" to "POSTGRES", "direct " to "POSTGRES", "Direct" to "POSTGRES",
            "d\u0131rect" to "POSTGRES", "direct\u0000" to "POSTGRES", "synthetic-negotiation" to "POSTGRES",
        ).map { (raw, expected) -> Arguments.of(raw, expected) }.stream()
    }
}
