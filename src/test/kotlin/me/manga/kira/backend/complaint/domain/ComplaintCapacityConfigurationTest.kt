package me.manga.kira.backend.complaint.domain

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ComplaintCapacityConfigurationTest {
    @Test
    fun `unconfigured seed must be closed and cannot authorize work by guessing a digest`() {
        val seed = ComplaintCapacityConfiguration.of(null, true)
        assertNull(seed.digestBytes())
        assertFailure(ComplaintCapacityFailureCode.INVALID_CONFIGURATION) { ComplaintCapacityConfiguration.of(null, false) }
        assertFailure(ComplaintCapacityFailureCode.CONFIGURATION_MISMATCH) { seed.requireMatching(ByteArray(32)) }
        assertFailure(ComplaintCapacityFailureCode.CONFIGURATION_MISMATCH) { seed.requireCreationAllowed(ByteArray(32)) }
    }

    @Test
    fun `closed matching configuration permits checking existing obligations but never new creation`() {
        val digest = ByteArray(32) { it.toByte() }
        val closed = ComplaintCapacityConfiguration.of(digest, true)
        closed.requireMatching(digest.copyOf())
        assertFailure(ComplaintCapacityFailureCode.CREATION_CLOSED) { closed.requireCreationAllowed(digest) }
        ComplaintCapacityConfiguration.of(digest, false).requireCreationAllowed(digest)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 31, 33, 64])
    fun `stored and expected configuration digests both require exactly 32 bytes`(width: Int) {
        for (closed in listOf(false, true)) {
            assertFailure(ComplaintCapacityFailureCode.INVALID_CONFIGURATION) { ComplaintCapacityConfiguration.of(ByteArray(width), closed) }
            val valid = ComplaintCapacityConfiguration.of(ByteArray(32), closed)
            assertFailure(ComplaintCapacityFailureCode.INVALID_CONFIGURATION) { valid.requireMatching(ByteArray(width)) }
            assertFailure(ComplaintCapacityFailureCode.INVALID_CONFIGURATION) { valid.requireCreationAllowed(ByteArray(width)) }
        }
    }

    @Test
    fun `every digest byte participates in matching even when creation is already closed`() {
        val digest = ByteArray(32) { it.toByte() }
        for (closed in listOf(false, true)) {
            val configuration = ComplaintCapacityConfiguration.of(digest, closed)
            repeat(32) { index ->
                val changed = digest.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
                assertFailure(ComplaintCapacityFailureCode.CONFIGURATION_MISMATCH) { configuration.requireMatching(changed) }
                assertFailure(ComplaintCapacityFailureCode.CONFIGURATION_MISMATCH) { configuration.requireCreationAllowed(changed) }
            }
        }
    }

    @Test
    fun `digest inputs and outputs are copied and equality includes closed state`() {
        val input = ByteArray(32) { it.toByte() }
        val expected = input.copyOf()
        val configuration = ComplaintCapacityConfiguration.of(input, false)
        val equal = ComplaintCapacityConfiguration.of(expected, false)
        val hash = configuration.hashCode()
        input.fill(77)
        configuration.digestBytes()!!.fill(88)
        assertArrayEquals(expected, configuration.digestBytes())
        configuration.requireCreationAllowed(expected)
        assertEquals(equal, configuration)
        assertEquals(hash, configuration.hashCode())
        assertEquals(equal.hashCode(), configuration.hashCode())
        assertNotEquals(ComplaintCapacityConfiguration.of(expected, true), configuration)
        assertNotEquals(ComplaintCapacityConfiguration.of(ByteArray(32), false), configuration)
        assertNotEquals(ComplaintCapacityConfiguration.of(null, true), configuration)
        assertNotEquals(configuration, null)
        assertNotEquals(configuration, "configuration")
        val seed = ComplaintCapacityConfiguration.of(null, true)
        assertEquals(seed, ComplaintCapacityConfiguration.of(null, true))
        assertEquals(seed.hashCode(), ComplaintCapacityConfiguration.of(null, true).hashCode())
    }

    @Test
    fun `diagnostics contain neither expected nor stored digest bytes`() {
        val configuration = ComplaintCapacityConfiguration.of(ByteArray(32) { 77 }, false)
        val error = assertThrows(ComplaintCapacityException::class.java) { configuration.requireMatching(ByteArray(32) { 88 }) }
        assertEquals("ComplaintCapacityConfiguration(redacted)", configuration.toString())
        assertFalse(error.toString().contains("77"))
        assertFalse(error.toString().contains("88"))
    }

    private fun assertFailure(code: ComplaintCapacityFailureCode, action: () -> Unit) {
        assertEquals(code, assertThrows(ComplaintCapacityException::class.java, action).code)
    }
}
