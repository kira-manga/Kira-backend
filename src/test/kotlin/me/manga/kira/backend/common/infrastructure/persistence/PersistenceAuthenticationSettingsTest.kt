package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

internal class PersistenceAuthenticationSettingsTest {
    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @MethodSource("positiveSubsets")
    fun `every positive subset preserves ordinary policy and only narrows deletion`(vector: NativeAuthCase) {
        verifyPolicy(vector)
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @MethodSource("negatedSubsets")
    fun `every negated subset rejects empty driver fallbacks or derives only its safe intersection`(vector: NativeAuthCase) {
        verifyPolicy(vector)
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @MethodSource("grammar")
    fun `Java grammar parity does not conflate omission invalid input and unrestricted empty results`(vector: NativeAuthCase) {
        verifyPolicy(vector)
    }

    private fun verifyPolicy(vector: NativeAuthCase) {
        // A positive control ensures rejection is not masked by another settings gate.
        for (lane in NativeSettingsLane.entries) assertNativeSupported(lane.assess(nativeEndpoint()))
        val endpoint = nativeEndpoint("requireAuth" to vector.raw)
        val before = nativeSnapshot(endpoint)
        val oracle = NativeDriverSettingsOracle.auth(vector.raw)
        if (!vector.validGrammar) {
            assertSame(NativeDriverParse.Invalid, oracle)
            for (lane in NativeSettingsLane.entries) {
                assertNativeRejected(PersistenceNativeSettingsReason.INVALID_AUTHENTICATION_POLICY, lane.assess(endpoint))
            }
        } else {
            assertEquals(NativeDriverParse.Parsed(vector.effective), oracle)
            verifySelection(vector, endpoint)
        }
        assertEquals(before, nativeSnapshot(endpoint))
        assertEquals(30_000L, endpoint.loginPolicy.durationMillis)
    }

    private fun verifySelection(vector: NativeAuthCase, endpoint: ResolvedPersistenceEndpoint) {
        val allowed = vector.effective.orEmpty()
        val safe = NATIVE_SAFE_METHODS.filter { it in allowed }
        for (lane in NativeSettingsLane.entries) {
            val result = lane.assess(endpoint)
            val reason = when {
                vector.raw == null && lane == NativeSettingsLane.ORDINARY -> PersistenceNativeSettingsReason.MISSING_AUTHENTICATION_POLICY

                vector.raw == null -> null

                allowed.isEmpty() -> PersistenceNativeSettingsReason.EMPTY_AUTHENTICATION_POLICY

                safe.isEmpty() || (lane == NativeSettingsLane.ORDINARY && safe.size != allowed.size) ->
                    PersistenceNativeSettingsReason.UNSUPPORTED_AUTHENTICATION_METHODS

                else -> null
            }
            if (reason != null) {
                assertNativeRejected(reason, result)
            } else {
                verifySupported(lane, endpoint, result, if (vector.raw == null) NATIVE_SAFE_METHODS else safe)
            }
        }
    }

    private fun verifySupported(
        lane: NativeSettingsLane,
        original: ResolvedPersistenceEndpoint,
        result: PersistenceNativeSettingsResult,
        selected: List<String>,
    ) {
        val actual = assertNativeSupported(result)
        if (lane == NativeSettingsLane.ORDINARY) {
            assertSame(original, actual)
        } else {
            val raw = actual.driverProperties().getProperty("requireAuth")
            assertEquals(selected.joinToString(","), raw)
            assertFalse(raw.isEmpty())
            assertFalse('!' in raw)
            assertEquals(NativeDriverParse.Parsed(selected.toSet()), NativeDriverSettingsOracle.auth(raw))
            assertTrue(selected.all { it in NATIVE_SAFE_METHODS })
        }
    }

    companion object {
        @JvmStatic
        fun positiveSubsets(): Stream<NativeAuthCase> = (0 until 64).map { mask ->
            val subset = NATIVE_AUTH_METHODS.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet()
            NativeAuthCase("positive-mask-$mask", subset.joinToString(",").ifEmpty { "," }, subset.ifEmpty { null })
        }.stream()

        @JvmStatic
        fun negatedSubsets(): Stream<NativeAuthCase> = (0 until 64).map { mask ->
            val denied = NATIVE_AUTH_METHODS.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet()
            // Empty comma split is positive/empty, not an expressible negative allow-all policy.
            val effective = if (denied.isEmpty()) emptySet() else NATIVE_AUTH_METHODS.toSet() - denied
            NativeAuthCase("negative-mask-$mask", denied.joinToString(",") { "!$it" }.ifEmpty { "," }, effective.ifEmpty { null })
        }.stream()

        @JvmStatic
        fun grammar(): Stream<NativeAuthCase> = NativeAuthCases.grammar.stream()
    }
}
