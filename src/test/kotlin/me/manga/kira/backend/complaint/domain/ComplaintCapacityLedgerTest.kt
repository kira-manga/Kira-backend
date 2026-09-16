package me.manga.kira.backend.complaint.domain

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/** Aggregate arithmetic fixtures, not proofs of locking, scope ownership, replay or erasure authorization. */
class ComplaintCapacityLedgerTest {
    @Test
    fun `creation charges and physical removal refunds are exact inverse transfers`() {
        val initial = ledger(hard = 100, creation = 70)
        val charged = initial.chargeCreation(digest, vector(70))
        assertBalance(charged, free = 30, actual = 70, recovery = 0, test = 0)
        assertFailure(ComplaintCapacityFailureCode.CREATION_LIMIT_REACHED) { charged.chargeCreation(digest, vector(1)) }
        val partial = charged.refundActual(digest, vector(20))
        assertBalance(partial, free = 50, actual = 50, recovery = 0, test = 0)
        assertEquals(initial.balance, partial.refundActual(digest, vector(50)).balance)
        assertBalance(initial, free = 100, actual = 0, recovery = 0, test = 0)
        assertSame(initial.configuration, charged.configuration)
        assertNotSame(initial, charged)
    }

    @Test
    fun `both recovery and unused test promises consume normal creation headroom`() {
        val initial = ledger(hard = 100, creation = 70)
            .chargeCreation(digest, vector(10))
            .reserveRecovery(digest, vector(20))
            .reserveTest(digest, vector(30))
        assertBalance(initial, free = 40, actual = 10, recovery = 20, test = 30)
        val exact = initial.chargeCreation(digest, vector(10))
        assertBalance(exact, free = 30, actual = 20, recovery = 20, test = 30)
        assertFailure(ComplaintCapacityFailureCode.CREATION_LIMIT_REACHED) { initial.chargeCreation(digest, vector(11)) }
        assertFailure(ComplaintCapacityFailureCode.CREATION_LIMIT_REACHED) { initial.reserveTest(digest, vector(11)) }
    }

    @Test
    fun `privacy reservation can use remaining hard headroom when ordinary creation is closed`() {
        val initial = ledger(hard = 100, creation = 50, actual = 50, closed = true)
        val reserved = initial.reserveRecovery(digest, vector(50))
        assertBalance(reserved, free = 0, actual = 50, recovery = 50, test = 0)
        assertFailure(ComplaintCapacityFailureCode.INSUFFICIENT_UNITS) { reserved.reserveRecovery(digest, vector(1)) }
        assertFailure(ComplaintCapacityFailureCode.CREATION_CLOSED) { initial.chargeCreation(digest, vector(1)) }
        assertFailure(ComplaintCapacityFailureCode.CREATION_CLOSED) { initial.reserveTest(digest, vector(1)) }
    }

    @Test
    fun `committed usage beyond creation ceiling cannot admit even a zero creation charge`() {
        val reserved = ledger(hard = 100, creation = 50, actual = 50).reserveRecovery(digest, vector(10))
        assertFailure(ComplaintCapacityFailureCode.CREATION_LIMIT_REACHED) { reserved.chargeCreation(digest, vector(0)) }
        assertFailure(ComplaintCapacityFailureCode.CREATION_LIMIT_REACHED) { reserved.reserveTest(digest, vector(0)) }
        assertBalance(reserved, free = 40, actual = 50, recovery = 10, test = 0)
    }

    @Test
    fun `recovery conversion moves exact use and releases only the original unused remainder`() {
        val initial = ledger(hard = 100, creation = 50, actual = 10, recovery = 60, closed = true)
        val converted = initial.convertRecovery(digest, originalPromise = vector(20), actualUse = vector(12))
        assertBalance(converted, free = 38, actual = 22, recovery = 40, test = 0)
        // Another event's aggregate40 remains reserved; this helper cannot establish either event's authority.
        val completed = converted.convertRecovery(digest, originalPromise = vector(40), actualUse = vector(40))
        assertBalance(completed, free = 38, actual = 62, recovery = 0, test = 0)
        assertBalance(initial, free = 30, actual = 10, recovery = 60, test = 0)
    }

    @Test
    fun `proved unused recovery capacity returns to free even while creation is closed`() {
        val initial = ledger(hard = 100, creation = 50, actual = 10, recovery = 40, closed = true)
        val released = initial.releaseRecovery(digest, vector(15))
        assertBalance(released, free = 65, actual = 10, recovery = 25, test = 0)
        val unused = released.convertRecovery(digest, originalPromise = vector(25), actualUse = vector(0))
        assertBalance(unused, free = 90, actual = 10, recovery = 0, test = 0)
    }

    @ParameterizedTest
    @EnumSource(ComplaintCapacityCounter::class)
    fun `conversion cannot enlarge any dimension of its promise even when other reserved work has room`(counter: ComplaintCapacityCounter) {
        val initial = ledger(hard = 100, creation = 100, recovery = 60)
        val promise = vector(20)
        assertFailure(ComplaintCapacityFailureCode.RESERVATION_EXCEEDED) {
            initial.convertRecovery(digest, promise, promise.with(counter, 21))
        }
        assertBalance(initial, free = 40, actual = 0, recovery = 60, test = 0)
    }

    @Test
    fun `converting or releasing more than aggregate recovery fails without creating capacity`() {
        val initial = ledger(hard = 100, creation = 100, recovery = 20)
        assertFailure(ComplaintCapacityFailureCode.INSUFFICIENT_UNITS) {
            initial.convertRecovery(digest, originalPromise = vector(21), actualUse = vector(21))
        }
        assertFailure(ComplaintCapacityFailureCode.INSUFFICIENT_UNITS) { initial.releaseRecovery(digest, vector(21)) }
        assertBalance(initial, free = 80, actual = 0, recovery = 20, test = 0)
    }

    @Test
    fun `physical reservation row charge is separate and cannot be released by future work conversion`() {
        val initial = ledger(hard = 100, creation = 100).chargeCreation(digest, vector(3)).reserveRecovery(digest, vector(20))
        val converted = initial.convertRecovery(digest, originalPromise = vector(20), actualUse = vector(7))
        assertBalance(converted, free = 90, actual = 10, recovery = 0, test = 0)
        assertEquals(vector(10), converted.balance.committedUnits)
        assertFailure(ComplaintCapacityFailureCode.INSUFFICIENT_UNITS) { converted.releaseRecovery(digest, vector(1)) }
    }

    @Test
    fun `test reserve moves to actual and recovery without charging free capacity again`() {
        val initial = ledger(hard = 100, creation = 60).reserveTest(digest, vector(60))
        assertBalance(initial, free = 40, actual = 0, recovery = 0, test = 60)
        val spent = initial.spendTestReserve(digest, toActual = vector(10), toRecovery = vector(30))
        assertBalance(spent, free = 40, actual = 10, recovery = 30, test = 20)
        assertEquals(vector(60), spent.balance.committedUnits)
        val released = spent.releaseTestReserve(digest, vector(20))
        assertBalance(released, free = 60, actual = 10, recovery = 30, test = 0)
        assertEquals(vector(40), released.balance.committedUnits)
    }

    @Test
    fun `already reserved test work can finish above lower ceiling while new creation is closed`() {
        val initial = ledger(hard = 100, creation = 0, actual = 20, test = 80, closed = true)
        val spent = initial.spendTestReserve(digest, toActual = vector(25), toRecovery = vector(55))
        assertBalance(spent, free = 0, actual = 45, recovery = 55, test = 0)
        val converted = spent.convertRecovery(digest, originalPromise = vector(55), actualUse = vector(55))
        assertBalance(converted, free = 0, actual = 100, recovery = 0, test = 0)
        assertBalance(initial.refundActual(digest, vector(20)), free = 20, actual = 0, recovery = 0, test = 80)
        assertBalance(initial.releaseTestReserve(digest, vector(80)), free = 80, actual = 20, recovery = 0, test = 0)
    }

    @Test
    fun `test overspend overrelease and addition overflow leave the original reserve intact`() {
        val initial = ledger(hard = 100, creation = 100, test = 60)
        assertFailure(ComplaintCapacityFailureCode.INSUFFICIENT_UNITS) {
            initial.spendTestReserve(digest, toActual = vector(30), toRecovery = vector(31))
        }
        assertFailure(ComplaintCapacityFailureCode.INSUFFICIENT_UNITS) { initial.releaseTestReserve(digest, vector(61)) }
        val maximum = ledger(hard = Long.MAX_VALUE, creation = Long.MAX_VALUE, test = Long.MAX_VALUE)
        assertFailure(ComplaintCapacityFailureCode.AMOUNT_OVERFLOW) {
            maximum.spendTestReserve(digest, toActual = vector(Long.MAX_VALUE), toRecovery = vector(1))
        }
        assertBalance(initial, free = 40, actual = 0, recovery = 0, test = 60)
        assertEquals(vector(Long.MAX_VALUE), maximum.balance.testReserved)
    }

    @Test
    fun `refund cannot remove another balance category or refund the same actual units twice`() {
        val initial = ledger(hard = 100, creation = 100, actual = 10, recovery = 20, test = 30)
        assertFailure(ComplaintCapacityFailureCode.INSUFFICIENT_UNITS) { initial.refundActual(digest, vector(11)) }
        val refunded = initial.refundActual(digest, vector(10))
        assertBalance(refunded, free = 50, actual = 0, recovery = 20, test = 30)
        assertFailure(ComplaintCapacityFailureCode.INSUFFICIENT_UNITS) { refunded.refundActual(digest, vector(1)) }
        assertBalance(initial, free = 40, actual = 10, recovery = 20, test = 30)
    }

    @ParameterizedTest
    @EnumSource(ComplaintCapacityCounter::class)
    fun `one exhausted dimension rejects the entire creation without partial changes`(counter: ComplaintCapacityCounter) {
        val balance = ComplaintCapacityBalance(vector(100), vector(100).with(counter, 5), vector(100))
        val initial = ComplaintCapacityLedger(ComplaintCapacityConfiguration.of(digest, false), balance)
        assertFailure(ComplaintCapacityFailureCode.CREATION_LIMIT_REACHED) { initial.chargeCreation(digest, vector(6)) }
        assertEquals(balance, initial.balance)
        assertBalance(initial, free = 100, actual = 0, recovery = 0, test = 0)
    }

    @Test
    fun `failure in final accounting component preserves all prior vectors`() {
        val actual = vector(10).with(ComplaintCapacityCounter.TEST_RUNS, 0)
        val free = vector(90).with(ComplaintCapacityCounter.TEST_RUNS, 100)
        val balance = ComplaintCapacityBalance(vector(100), vector(100), free, actual)
        val initial = ComplaintCapacityLedger(ComplaintCapacityConfiguration.of(digest, false), balance)
        assertFailure(ComplaintCapacityFailureCode.INSUFFICIENT_UNITS) { initial.refundActual(digest, vector(1)) }
        assertSame(balance, initial.balance)
        assertEquals(actual, initial.balance.actual)
        assertEquals(free, initial.balance.free)
    }

    @Test
    fun `all transitions reject unconfigured mismatched or malformed configuration without changing balances`() {
        val balance = ComplaintCapacityBalance(vector(100), vector(100), vector(70), vector(10), vector(10), vector(10))
        val open = ComplaintCapacityLedger(ComplaintCapacityConfiguration.of(digest, false), balance)
        val closed = ComplaintCapacityLedger(ComplaintCapacityConfiguration.of(digest, true), balance)
        val seed = ComplaintCapacityLedger(ComplaintCapacityConfiguration.of(null, true), balance)
        val operations: List<(ComplaintCapacityLedger, ByteArray) -> ComplaintCapacityLedger> = listOf(
            { ledger, expected -> ledger.chargeCreation(expected, vector(1)) },
            { ledger, expected -> ledger.refundActual(expected, vector(1)) },
            { ledger, expected -> ledger.reserveRecovery(expected, vector(1)) },
            { ledger, expected -> ledger.convertRecovery(expected, vector(1), vector(1)) },
            { ledger, expected -> ledger.releaseRecovery(expected, vector(1)) },
            { ledger, expected -> ledger.reserveTest(expected, vector(1)) },
            { ledger, expected -> ledger.spendTestReserve(expected, vector(1), vector(1)) },
            { ledger, expected -> ledger.releaseTestReserve(expected, vector(1)) },
        )
        for (operation in operations) {
            for (current in listOf(open, closed)) {
                assertFailure(ComplaintCapacityFailureCode.CONFIGURATION_MISMATCH) { operation(current, ByteArray(32)) }
                assertFailure(ComplaintCapacityFailureCode.INVALID_CONFIGURATION) { operation(current, ByteArray(31)) }
                assertSame(balance, current.balance)
            }
            assertFailure(ComplaintCapacityFailureCode.CONFIGURATION_MISMATCH) { operation(seed, digest) }
            assertSame(balance, seed.balance)
        }
    }

    @Test
    fun `maximum charge and full conversion stay in range and one more cannot wrap`() {
        val empty = ledger(hard = Long.MAX_VALUE, creation = Long.MAX_VALUE)
        val charged = empty.chargeCreation(digest, vector(Long.MAX_VALUE))
        assertBalance(charged, free = 0, actual = Long.MAX_VALUE, recovery = 0, test = 0)
        assertFailure(ComplaintCapacityFailureCode.AMOUNT_OVERFLOW) { charged.chargeCreation(digest, vector(1)) }
        assertEquals(empty.balance, charged.refundActual(digest, vector(Long.MAX_VALUE)).balance)
        val reserved = empty.reserveRecovery(digest, vector(Long.MAX_VALUE))
        val converted = reserved.convertRecovery(digest, vector(Long.MAX_VALUE), vector(Long.MAX_VALUE))
        assertEquals(charged.balance, converted.balance)
    }

    @Test
    fun `ledger diagnostic does not expose balances or trusted configuration`() {
        assertEquals("ComplaintCapacityLedger(redacted)", ledger(hard = 100, creation = 100).toString())
    }

    private fun ledger(hard: Long, creation: Long, actual: Long = 0, recovery: Long = 0, test: Long = 0, closed: Boolean = false): ComplaintCapacityLedger =
        ComplaintCapacityLedger(
            ComplaintCapacityConfiguration.of(digest, closed),
            ComplaintCapacityBalance(vector(hard), vector(creation), vector(hard - actual - recovery - test), vector(actual), vector(recovery), vector(test)),
        )

    private fun assertBalance(ledger: ComplaintCapacityLedger, free: Long, actual: Long, recovery: Long, test: Long) {
        assertArrayEquals(LongArray(22) { free }, ledger.balance.free.toLongArray())
        assertArrayEquals(LongArray(22) { actual }, ledger.balance.actual.toLongArray())
        assertArrayEquals(LongArray(22) { recovery }, ledger.balance.recoveryReserved.toLongArray())
        assertArrayEquals(LongArray(22) { test }, ledger.balance.testReserved.toLongArray())
        assertEquals(ledger.balance.hardLimit, ledger.balance.free + ledger.balance.actual + ledger.balance.recoveryReserved + ledger.balance.testReserved)
    }

    private fun vector(amount: Long): ComplaintCapacityVector = ComplaintCapacityVector.of(LongArray(22) { amount })

    private fun assertFailure(code: ComplaintCapacityFailureCode, action: () -> Unit) {
        assertEquals(code, assertThrows(ComplaintCapacityException::class.java, action).code)
    }

    private val digest = ByteArray(32) { 1 }
}
