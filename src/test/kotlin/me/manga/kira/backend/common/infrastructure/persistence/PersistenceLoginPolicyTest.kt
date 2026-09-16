package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.stream.Stream

class PersistenceLoginPolicyTest {
    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @MethodSource("supportedDecimals")
    fun `supported exact decimal seconds and zero fallback produce finite millisecond policies`(seconds: String?, expectedMillis: Long) {
        val policy = PersistenceLoginPolicy.resolve(seconds, 30_000)
        assertEquals(expectedMillis, policy.durationMillis)
        assertTrue(policy.durationMillis > 0 && policy.durationMillis <= Long.MAX_VALUE / 1_000_000)
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(
        strings = [
            "", " ", "+", ".", "-0", "-1", "1f", "1d", "0x1.0p0", "NaN", "Infinity", "+Infinity",
            "1e", "1e-", "1e+", "1_000", "1,000", "1\n", " 1", "1 ", "\t1", "١", "１", "١e3",
            "0.0001", "0.0011", "1.0001", "2147483647.001", "2147483648", "1e2147483647", "1e-2147483647",
            "1e2147483648", "1e-2147483648", "1e999999999999", "0.0e-2147483647", "-0e0", "1e1.5",
        ],
    )
    fun `invalid nonfinite imprecise and extreme magnitude policies reject without rounding or scale expansion`(seconds: String) {
        assertEndpointFailure(PersistenceBoundaryFailureCode.INVALID_LOGIN_POLICY) { PersistenceLoginPolicy.resolve(seconds, 30_000) }
    }

    @Test
    fun `sixty four character bound is inclusive and checked before decimal parsing`() {
        assertEquals(1000L, PersistenceLoginPolicy.resolve("0".repeat(63) + "1", 30_000).durationMillis)
        assertEquals(1000L, PersistenceLoginPolicy.resolve("1." + "0".repeat(62), 30_000).durationMillis)
        assertEquals(1L, PersistenceLoginPolicy.resolve(".001" + "0".repeat(60), 30_000).durationMillis)
        assertEquals(30_000L, PersistenceLoginPolicy.resolve("0".repeat(64), 30_000).durationMillis)
        for (input in listOf("0".repeat(65), "0".repeat(64) + "1", "1." + "0".repeat(63), "9".repeat(64))) {
            assertEndpointFailure(PersistenceBoundaryFailureCode.INVALID_LOGIN_POLICY) { PersistenceLoginPolicy.resolve(input, 30_000) }
        }
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(longs = [Long.MIN_VALUE, -1, 0, 2147483647001, 9223372036854, Long.MAX_VALUE])
    fun `checkout fallback is validated even when an explicit positive login policy would replace it`(checkoutMillis: Long) {
        for (seconds in listOf(null, "0", "1")) {
            assertEndpointFailure(PersistenceBoundaryFailureCode.INVALID_LOGIN_POLICY) { PersistenceLoginPolicy.resolve(seconds, checkoutMillis) }
        }
    }

    @Test
    fun `integer getter ceiling never grants a differing or unlimited setter duration`() {
        val fractional = PersistenceLoginPolicy.resolve("1.25", 30_000)
        assertEquals(2, fractional.jdbcSeconds)
        for (value in listOf(Int.MIN_VALUE, -1, 0, 1, 2, Int.MAX_VALUE)) assertFalse(fractional.acceptsJdbcSeconds(value))
        val millisecond = PersistenceLoginPolicy.resolve(".001", 30_000)
        assertEquals(1, millisecond.jdbcSeconds)
        assertFalse(millisecond.acceptsJdbcSeconds(1))
        val integral = PersistenceLoginPolicy.resolve("1.000", 30_000)
        assertEquals(1, integral.jdbcSeconds)
        assertTrue(integral.acceptsJdbcSeconds(1))
        assertFalse(integral.acceptsJdbcSeconds(0))
        assertFalse(integral.acceptsJdbcSeconds(2))
        val maximum = PersistenceLoginPolicy.resolve("2147483647", 30_000)
        assertEquals(2_147_483_647_000L, maximum.durationMillis)
        assertEquals(Int.MAX_VALUE, maximum.jdbcSeconds)
        assertTrue(maximum.acceptsJdbcSeconds(Int.MAX_VALUE))
        val below = PersistenceLoginPolicy.resolve("2147483646.999", 30_000)
        assertEquals(Int.MAX_VALUE, below.jdbcSeconds)
        assertFalse(below.acceptsJdbcSeconds(Int.MAX_VALUE))
    }

    @Test
    fun `finite checkout fallback retains its exact fractional value rather than becoming driver unlimited`() {
        for (seconds in listOf(null, "0", "0e5")) {
            assertEquals(1L, PersistenceLoginPolicy.resolve(seconds, 1).durationMillis)
            assertEquals(1234L, PersistenceLoginPolicy.resolve(seconds, 1234).durationMillis)
            assertEquals(2_147_483_647_000L, PersistenceLoginPolicy.resolve(seconds, 2_147_483_647_000L).durationMillis)
        }
        val endpoint = resolveEndpoint("jdbc:postgresql://?loginTimeout=1.25")
        assertEquals(1250L, endpoint.loginPolicy.durationMillis)
        assertEquals("0", endpoint.driverProperties().getProperty("loginTimeout"))
        assertEndpointFailure(PersistenceBoundaryFailureCode.JDBC_CONFIGURATION_CONFLICT) {
            resolveEndpoint("jdbc:postgresql://?loginTimeout=1.0", endpointProperties("loginTimeout" to "1"))
        }
    }

    companion object {
        @JvmStatic
        fun supportedDecimals(): Stream<Arguments> = listOf(
            null to 30_000L, "0" to 30_000L, ".0" to 30_000L, "0." to 30_000L,
            "+0e5" to 30_000L, "0e2147483647" to 30_000L, "0e-2147483647" to 30_000L,
            "1" to 1000L, "+1." to 1000L, ".001" to 1L, "1e-3" to 1L, "10e-3" to 10L,
            "1.001" to 1001L, "0.010" to 10L, "1.000000" to 1000L, "+12.5E+1" to 125_000L,
            "2147483647" to 2_147_483_647_000L, "2147483646.999" to 2_147_483_646_999L,
        ).map { (seconds, millis) -> Arguments.of(seconds, millis) }.stream()
    }
}
