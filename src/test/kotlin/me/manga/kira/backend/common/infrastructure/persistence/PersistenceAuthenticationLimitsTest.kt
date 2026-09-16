package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

internal class PersistenceAuthenticationLimitsTest {
    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @MethodSource("gssModes")
    fun `GSS selection follows Java case rules and never downgrades explicit prefer or require`(raw: String?, expected: String?) {
        val endpoint = nativeEndpoint("gssEncMode" to raw)
        val before = nativeSnapshot(endpoint)
        val oracle = NativeDriverSettingsOracle.gss(endpoint.driverProperties())
        if (expected == null) assertSame(NativeDriverParse.Invalid, oracle) else assertEquals(NativeDriverParse.Parsed(expected), oracle)
        for (lane in NativeSettingsLane.entries) {
            assertNativeSupported(lane.assess(nativeEndpoint()))
            val reason = when {
                expected == null -> PersistenceNativeSettingsReason.INVALID_GSS_MODE
                expected == "DISABLE" || (lane == NativeSettingsLane.DELETION && expected == "ALLOW") -> null
                else -> PersistenceNativeSettingsReason.UNSUPPORTED_GSS_MODE
            }
            for (negotiation in listOf(null, "direct")) {
                val input = nativeEndpoint("gssEncMode" to raw, "sslNegotiation" to negotiation)
                val result = lane.assess(input)
                if (reason != null) {
                    assertNativeRejected(reason, result)
                } else {
                    val supported = assertNativeSupported(result)
                    val selected = supported.driverProperties().getProperty("gssEncMode")
                    assertEquals(if (lane == NativeSettingsLane.DELETION) "disable" else raw, selected)
                }
            }
        }
        assertEquals(before, nativeSnapshot(endpoint))
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @MethodSource("integerCases")
    fun `SCRAM grammar is Java decimal and policy rejects unbounded or nonpositive work`(vector: NativeIntegerCase) {
        val endpoint = nativeEndpoint("scramMaxIterations" to vector.raw)
        val before = nativeSnapshot(endpoint)
        val oracle = NativeDriverSettingsOracle.integer(NativeIntegerProperty.SCRAM, endpoint.driverProperties())
        assertEquals(vector.value, persistenceDriverInt(vector.raw))
        if (vector.value == null) assertSame(NativeDriverParse.Invalid, oracle) else assertEquals(NativeDriverParse.Parsed(vector.value), oracle)
        for (lane in NativeSettingsLane.entries) {
            assertNativeSupported(lane.assess(nativeEndpoint()))
            val allowed = vector.value != null && vector.value > 0 && (lane == NativeSettingsLane.DELETION || vector.value <= 100_000)
            val result = lane.assess(endpoint)
            if (!allowed) {
                assertNativeRejected(PersistenceNativeSettingsReason.UNSUPPORTED_SCRAM_LIMIT, result)
            } else {
                val selected = assertNativeSupported(result).driverProperties().getProperty("scramMaxIterations")
                val expected = if (lane == NativeSettingsLane.ORDINARY) vector.raw else minOf(checkNotNull(vector.value), 100_000).toString()
                assertEquals(expected, selected)
            }
        }
        assertEquals(before, nativeSnapshot(endpoint))
    }

    @Test
    fun `absent SCRAM uses the pinned finite default without rewriting ordinary configuration`() {
        val endpoint = nativeEndpoint()
        assertEquals(NativeDriverParse.Parsed(100_000), NativeDriverSettingsOracle.integer(NativeIntegerProperty.SCRAM, endpoint.driverProperties()))
        assertSame(endpoint, assertNativeSupported(NativeSettingsLane.ORDINARY.assess(endpoint)))
        assertEquals(null, endpoint.driverProperties().getProperty("scramMaxIterations"))
        val deletion = assertNativeSupported(NativeSettingsLane.DELETION.assess(endpoint))
        assertEquals("100000", deletion.driverProperties().getProperty("scramMaxIterations"))
        assertEquals(null, endpoint.driverProperties().getProperty("scramMaxIterations"))
    }

    companion object {
        @JvmStatic
        fun gssModes(): Stream<Arguments> = listOf(
            null to "ALLOW", "disable" to "DISABLE", "DISABLE" to "DISABLE", "dIsAbLe" to "DISABLE",
            "d\u0131sable" to "DISABLE", "d\u0130sable" to "DISABLE", "allow" to "ALLOW", "ALLOW" to "ALLOW",
            "prefer" to "PREFER", "PREFER" to "PREFER", "require" to "REQUIRE", "REQUIRE" to "REQUIRE",
            "" to null, " disable" to null, "disable " to null, "\tdisable" to null, "disabled" to null,
            "none" to null, "allow\u0000" to null, "\u00a0disable" to null,
        ).map { (raw, expected) -> Arguments.of(raw, expected) }.stream()

        @JvmStatic
        fun integerCases(): Stream<NativeIntegerCase> = NativeIntegerCases.exact.stream()
    }
}
