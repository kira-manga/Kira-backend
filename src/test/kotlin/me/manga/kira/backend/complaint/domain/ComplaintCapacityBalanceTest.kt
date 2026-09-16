package me.manga.kira.backend.complaint.domain

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class ComplaintCapacityBalanceTest {
    @Test
    fun `all equation terms count independently in every dimension`() {
        val balance = ComplaintCapacityBalance(vector(100), vector(70), vector(40), vector(10), vector(20), vector(30))
        assertArrayEquals(LongArray(22) { 60 }, balance.committedUnits.toLongArray())
        assertEquals(vector(100), balance.hardLimit)
        assertEquals(vector(70), balance.creationLimit)
    }

    @Test
    fun `zero limits and maximum nonoverflowing equation are valid`() {
        val zero = ComplaintCapacityBalance(vector(0), vector(0), vector(0))
        assertEquals(ComplaintCapacityVector.ZERO, zero.committedUnits)
        val maximum = ComplaintCapacityBalance(vector(Long.MAX_VALUE), vector(Long.MAX_VALUE), vector(Long.MAX_VALUE - 3), vector(1), vector(1), vector(1))
        assertEquals(vector(3), maximum.committedUnits)
        val occupied = ComplaintCapacityBalance(vector(Long.MAX_VALUE), vector(0), vector(0), vector(Long.MAX_VALUE))
        assertEquals(vector(Long.MAX_VALUE), occupied.committedUnits)
    }

    @ParameterizedTest
    @EnumSource(ComplaintCapacityCounter::class)
    fun `creation ceiling may be lower but never higher than its hard limit`(counter: ComplaintCapacityCounter) {
        val hard = vector(100)
        ComplaintCapacityBalance(hard, vector(0), hard)
        ComplaintCapacityBalance(hard, hard, hard)
        assertFailure(ComplaintCapacityFailureCode.INVALID_CREATION_LIMIT) {
            ComplaintCapacityBalance(hard, hard.with(counter, 101), hard)
        }
    }

    @ParameterizedTest
    @EnumSource(ComplaintCapacityCounter::class)
    fun `each equation rejects both one missing unit and one excess unit`(counter: ComplaintCapacityCounter) {
        for (invalidFree in listOf(39L, 41L)) {
            assertFailure(ComplaintCapacityFailureCode.INCONSISTENT_BALANCE) {
                ComplaintCapacityBalance(vector(100), vector(100), vector(40).with(counter, invalidFree), vector(10), vector(20), vector(30))
            }
        }
    }

    @Test
    fun `equation overflow cannot wrap into an apparently balanced snapshot`() {
        assertFailure(ComplaintCapacityFailureCode.AMOUNT_OVERFLOW) {
            ComplaintCapacityBalance(vector(Long.MAX_VALUE), vector(0), vector(Long.MAX_VALUE), vector(1))
        }
        assertFailure(ComplaintCapacityFailureCode.AMOUNT_OVERFLOW) {
            ComplaintCapacityBalance(vector(0), vector(0), vector(Long.MAX_VALUE), vector(Long.MAX_VALUE), vector(2))
        }
    }

    @Test
    fun `data class copy cannot bypass validation or damage the previous snapshot`() {
        val balance = ComplaintCapacityBalance(vector(100), vector(70), vector(40), vector(10), vector(20), vector(30))
        assertFailure(ComplaintCapacityFailureCode.INCONSISTENT_BALANCE) { balance.copy(actual = vector(11)) }
        assertFailure(ComplaintCapacityFailureCode.INVALID_CREATION_LIMIT) { balance.copy(creationLimit = vector(101)) }
        assertEquals(vector(10), balance.actual)
        assertEquals(vector(60), balance.committedUnits)
        assertFalse(balance.toString().contains("100"))
    }

    private fun vector(amount: Long): ComplaintCapacityVector = ComplaintCapacityVector.of(LongArray(22) { amount })

    private fun assertFailure(code: ComplaintCapacityFailureCode, action: () -> Unit) {
        assertEquals(code, assertThrows(ComplaintCapacityException::class.java, action).code)
    }
}
