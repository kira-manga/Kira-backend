package me.manga.kira.backend.complaint.domain

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.math.BigInteger

class ComplaintCapacityVectorTest {
    @Test
    fun `input and output arrays are defensive snapshots and equality is by value`() {
        val input = LongArray(22) { it.toLong() }
        val expected = input.copyOf()
        val vector = ComplaintCapacityVector.of(input)
        val equal = ComplaintCapacityVector.of(expected)
        input.fill(Long.MAX_VALUE)
        val output = vector.toLongArray()
        output.fill(0)
        assertArrayEquals(expected, vector.toLongArray())
        assertEquals(equal, vector)
        assertEquals(equal.hashCode(), vector.hashCode())
        assertNotSame(output, vector.toLongArray())
        assertNotEquals(ComplaintCapacityVector.ZERO, vector)
        assertNotEquals(vector, null)
        assertNotEquals(vector, "ComplaintCapacityVector(v1, redacted)")
        assertEquals(vector, vector)
    }

    @Test
    fun `zero and maximum values are valid without a cross-dimension sum`() {
        assertTrue(ComplaintCapacityVector.ZERO.isZero())
        assertArrayEquals(LongArray(22), ComplaintCapacityVector.ZERO.toLongArray())
        val maximum = ComplaintCapacityVector.of(LongArray(22) { Long.MAX_VALUE })
        assertFalse(maximum.isZero())
        assertEquals(maximum, maximum + ComplaintCapacityVector.ZERO)
        assertEquals(maximum, maximum - ComplaintCapacityVector.ZERO)
        assertEquals(maximum, maximum.scaled(1))
        assertEquals(ComplaintCapacityVector.ZERO, maximum.scaled(0))
        assertEquals(ComplaintCapacityVector.ZERO, ComplaintCapacityVector.ZERO.scaled(Long.MAX_VALUE))
        assertEquals(ComplaintCapacityVector.ZERO, maximum - maximum)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 21, 23, 44])
    fun `only the exact version one width is accepted`(width: Int) {
        assertFailure(ComplaintCapacityFailureCode.INVALID_VECTOR_WIDTH) { ComplaintCapacityVector.of(LongArray(width)) }
    }

    @Test
    fun `unsupported version fails before array decoding`() {
        assertFailure(ComplaintCapacityFailureCode.UNSUPPORTED_VERSION) { ComplaintCapacityVector.of(LongArray(22), 2) }
        assertFailure(ComplaintCapacityFailureCode.UNSUPPORTED_VERSION) { ComplaintCapacityVector.of(longArrayOf(-1), 0) }
    }

    @ParameterizedTest
    @EnumSource(ComplaintCapacityCounter::class)
    fun `each stored dimension rejects negative input and replacement without mutating existing values`(counter: ComplaintCapacityCounter) {
        for (negative in listOf(-1L, Long.MIN_VALUE)) {
            val values = LongArray(22).also { it[counter.storedOrdinal - 1] = negative }
            assertFailure(ComplaintCapacityFailureCode.NEGATIVE_AMOUNT) { ComplaintCapacityVector.of(values) }
            assertFailure(ComplaintCapacityFailureCode.NEGATIVE_AMOUNT) { ComplaintCapacityVector.ZERO.with(counter, negative) }
            assertFailure(ComplaintCapacityFailureCode.NEGATIVE_AMOUNT) { ComplaintCapacityVector.units(counter, negative) }
            assertArrayEquals(LongArray(22), ComplaintCapacityVector.ZERO.toLongArray())
        }
    }

    @ParameterizedTest
    @EnumSource(ComplaintCapacityCounter::class)
    fun `replacement and unit factory address only the explicit stored dimension`(counter: ComplaintCapacityCounter) {
        val expected = LongArray(22).also { it[counter.storedOrdinal - 1] = 17 }
        val units = ComplaintCapacityVector.units(counter, 17)
        assertEquals(17L, units[counter])
        assertArrayEquals(expected, units.toLongArray())
        assertEquals(units, ComplaintCapacityVector.ZERO.with(counter, 17))
        assertEquals(ComplaintCapacityVector.ZERO, units.with(counter, 0))
        assertArrayEquals(expected, units.toLongArray())
        assertTrue(ComplaintCapacityVector.ZERO.isZero())
    }

    @ParameterizedTest
    @EnumSource(ComplaintCapacityCounter::class)
    fun `addition checks exact maximum and rejects one over in every dimension`(counter: ComplaintCapacityCounter) {
        val near = ComplaintCapacityVector.units(counter, Long.MAX_VALUE - 1)
        val one = ComplaintCapacityVector.units(counter, 1)
        val maximum = near + one
        assertEquals(Long.MAX_VALUE, maximum[counter])
        assertEquals(maximum, one + near)
        assertFailure(ComplaintCapacityFailureCode.AMOUNT_OVERFLOW) { maximum + one }
        assertFailure(ComplaintCapacityFailureCode.AMOUNT_OVERFLOW) { one + maximum }
        assertEquals(Long.MAX_VALUE - 1, near[counter])
        assertEquals(1L, one[counter])
    }

    @ParameterizedTest
    @EnumSource(ComplaintCapacityCounter::class)
    fun `subtraction is componentwise and never borrows from another dimension`(counter: ComplaintCapacityCounter) {
        val one = ComplaintCapacityVector.units(counter, 1)
        assertEquals(ComplaintCapacityVector.ZERO, one - one)
        val otherCapacity = ComplaintCapacityVector.of(LongArray(22) { Long.MAX_VALUE }).with(counter, 0)
        val before = otherCapacity.toLongArray()
        assertFailure(ComplaintCapacityFailureCode.INSUFFICIENT_UNITS) { otherCapacity - one }
        assertArrayEquals(before, otherCapacity.toLongArray())
        assertEquals(1L, one[counter])
    }

    @Test
    fun `a final component failure cannot publish preceding arithmetic or mutate either input`() {
        val left = ComplaintCapacityVector.of(LongArray(22) { 10 }).with(ComplaintCapacityCounter.TEST_RUNS, Long.MAX_VALUE)
        val right = ComplaintCapacityVector.of(LongArray(22) { 1 })
        val leftBefore = left.toLongArray()
        val rightBefore = right.toLongArray()
        assertFailure(ComplaintCapacityFailureCode.AMOUNT_OVERFLOW) { left + right }
        assertFailure(ComplaintCapacityFailureCode.AMOUNT_OVERFLOW) { left.scaled(2) }
        val underflow = left.with(ComplaintCapacityCounter.TEST_RUNS, 0)
        assertFailure(ComplaintCapacityFailureCode.INSUFFICIENT_UNITS) { underflow - right }
        assertArrayEquals(leftBefore, left.toLongArray())
        assertArrayEquals(rightBefore, right.toLongArray())
        assertEquals(10L, underflow[ComplaintCapacityCounter.APP_INSTALLATIONS])
    }

    @Test
    fun `multiplication checks before overflow and rejects negative factors even for zero`() {
        val half = ComplaintCapacityVector.units(ComplaintCapacityCounter.STORAGE_BYTES, Long.MAX_VALUE / 2)
        assertEquals(Long.MAX_VALUE - 1, half.scaled(2)[ComplaintCapacityCounter.STORAGE_BYTES])
        assertFailure(ComplaintCapacityFailureCode.AMOUNT_OVERFLOW) { half.scaled(3) }
        for (negative in listOf(-1L, Long.MIN_VALUE)) {
            assertFailure(ComplaintCapacityFailureCode.NEGATIVE_AMOUNT) { half.scaled(negative) }
            assertFailure(ComplaintCapacityFailureCode.NEGATIVE_AMOUNT) { ComplaintCapacityVector.ZERO.scaled(negative) }
        }
    }

    @Test
    fun `checked arithmetic agrees with independent arbitrary precision boundary values`() {
        val edges = listOf(0L, 1L, 2L, 3L, 65536L, Long.MAX_VALUE / 2, Long.MAX_VALUE - 1, Long.MAX_VALUE)
        val limit = BigInteger.valueOf(Long.MAX_VALUE)
        for (left in edges) {
            for (right in edges) {
                val a = ComplaintCapacityVector.units(ComplaintCapacityCounter.TEST_RUNS, left)
                val b = ComplaintCapacityVector.units(ComplaintCapacityCounter.TEST_RUNS, right)
                val sum = BigInteger.valueOf(left) + BigInteger.valueOf(right)
                val product = BigInteger.valueOf(left) * BigInteger.valueOf(right)
                if (sum <= limit) {
                    assertEquals(sum.longValueExact(), (a + b)[ComplaintCapacityCounter.TEST_RUNS])
                } else {
                    assertFailure(ComplaintCapacityFailureCode.AMOUNT_OVERFLOW) { a + b }
                }
                if (product <= limit) {
                    assertEquals(product.longValueExact(), a.scaled(right)[ComplaintCapacityCounter.TEST_RUNS])
                } else {
                    assertFailure(ComplaintCapacityFailureCode.AMOUNT_OVERFLOW) { a.scaled(right) }
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ComplaintCapacityCounter::class)
    fun `comparison checks every dimension including a last excess after equal prefix`(counter: ComplaintCapacityCounter) {
        val ceiling = ComplaintCapacityVector.of(LongArray(22) { 10 })
        assertTrue(ceiling.fitsWithin(ceiling))
        assertTrue(ceiling.with(counter, 9).fitsWithin(ceiling))
        assertFalse(ceiling.with(counter, 11).fitsWithin(ceiling))
        assertTrue(ComplaintCapacityVector.ZERO.fitsWithin(ceiling))
        assertFalse(ceiling.fitsWithin(ComplaintCapacityVector.ZERO))
    }

    @Test
    fun `diagnostics do not expose logical amounts`() {
        val value = ComplaintCapacityVector.of(LongArray(22) { Long.MAX_VALUE })
        assertEquals("ComplaintCapacityVector(v1, redacted)", value.toString())
        val error = assertThrows(ComplaintCapacityException::class.java) { value + value }
        assertFalse(error.toString().contains(Long.MAX_VALUE.toString()))
    }

    private fun assertFailure(code: ComplaintCapacityFailureCode, action: () -> Unit) {
        assertEquals(code, assertThrows(ComplaintCapacityException::class.java, action).code)
    }
}
