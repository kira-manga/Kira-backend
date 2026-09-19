package me.manga.kira.backend.complaint.domain

import me.manga.kira.backend.complaint.domain.terminal.TestTerminalScanPoolV1
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
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
    fun `partial recovery spending preserves future and other event reserves without freeing capacity`() {
        val originalPromise = vector(30)
        val previousUse = vector(10)
        val otherEventRemaining = vector(40)
        val remaining = originalPromise - previousUse
        val initial = ledger(hard = 100, creation = 50, actual = 10, recovery = 60, test = 5, closed = true)
        assertEquals(remaining + otherEventRemaining, initial.balance.recoveryReserved)

        val usedNow = vector(12)
        val spent = initial.spendRecovery(digest, remaining, usedNow)
        val futureRemaining = originalPromise - (previousUse + usedNow)
        assertBalance(spent, free = 25, actual = 22, recovery = 48, test = 5)
        assertEquals(futureRemaining + otherEventRemaining, spent.balance.recoveryReserved)

        // The caller supplies the newly locked remainder; arithmetic alone is not replay/ownership proof.
        val usedLater = vector(3)
        val later = spent.spendRecovery(digest, futureRemaining, usedLater)
        assertBalance(later, free = 25, actual = 25, recovery = 45, test = 5)
        assertEquals((futureRemaining - usedLater) + otherEventRemaining, later.balance.recoveryReserved)
        assertBalance(initial, free = 25, actual = 10, recovery = 60, test = 5)
        assertSame(initial.configuration, spent.configuration)
        assertNotSame(initial, spent)
    }

    @Test
    fun `partial recovery spending rejects event overspend corrupt aggregate and configuration mismatch`() {
        val initial = ledger(hard = 100, creation = 50, actual = 10, recovery = 60, test = 5, closed = true)
        val remaining = vector(20)
        assertFailure(ComplaintCapacityFailureCode.RESERVATION_EXCEEDED) {
            initial.spendRecovery(digest, remaining, vector(1).with(ComplaintCapacityCounter.TEST_RUNS, 21))
        }
        // Even a small use cannot bless a locked event remainder larger than the aggregate reserve.
        assertFailure(ComplaintCapacityFailureCode.INSUFFICIENT_UNITS) {
            initial.spendRecovery(digest, remaining.with(ComplaintCapacityCounter.TEST_RUNS, 61), vector(1))
        }
        assertFailure(ComplaintCapacityFailureCode.CONFIGURATION_MISMATCH) {
            initial.spendRecovery(ByteArray(32), remaining, vector(1))
        }
        assertFailure(ComplaintCapacityFailureCode.INVALID_CONFIGURATION) {
            initial.spendRecovery(ByteArray(31), remaining, vector(1))
        }
        val unconfigured = ComplaintCapacityLedger(ComplaintCapacityConfiguration.of(null, true), initial.balance)
        assertFailure(ComplaintCapacityFailureCode.CONFIGURATION_MISMATCH) {
            unconfigured.spendRecovery(digest, remaining, vector(1))
        }
        assertSame(initial.balance, unconfigured.balance)
        assertBalance(initial, free = 25, actual = 10, recovery = 60, test = 5)
    }

    @Test
    fun `partial recovery spending at maximum transfers exact units without overflow`() {
        val initial = ledger(hard = Long.MAX_VALUE, creation = 0, recovery = Long.MAX_VALUE, closed = true)
        val spent = initial.spendRecovery(digest, remaining = vector(Long.MAX_VALUE), actualUse = vector(Long.MAX_VALUE - 1))
        assertBalance(spent, free = 0, actual = Long.MAX_VALUE - 1, recovery = 1, test = 0)
        val last = spent.spendRecovery(digest, remaining = vector(1), actualUse = vector(1))
        assertBalance(last, free = 0, actual = Long.MAX_VALUE, recovery = 0, test = 0)
        assertBalance(initial, free = 0, actual = 0, recovery = Long.MAX_VALUE, test = 0)
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
            { ledger, expected -> ledger.recycleTestScanPool(expected, TestTerminalScanPoolV1(1), ComplaintCapacityVector.ZERO, ComplaintCapacityVector.ZERO) },
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

    @Test
    fun testScanPoolRecycleConservesClosedAccountingAndCanBeRespent() {
        val pool = TestTerminalScanPoolV1(3)
        val unused = pool.chargeFor(1, 2)
        val paid = pool.ceiling - unused
        val retainedActual = ComplaintCapacityCharges.AUDIT
        val otherTest = ComplaintCapacityCharges.AUDIT.scaled(2)
        val recovery = ComplaintCapacityCharges.RESOURCE_ID
        val initial = scanLedger(paid + retainedActual, unused + otherTest, recovery)

        // Assumed exact paid-row removal: this fixture does not establish its transaction or authority.
        val recycled = initial.recycleTestScanPool(digest, pool, unused, paid)
        assertEquals(initial.balance.free, recycled.balance.free)
        assertEquals(retainedActual, recycled.balance.actual)
        assertEquals(pool.ceiling + otherTest, recycled.balance.testReserved)
        assertEquals(recovery, recycled.balance.recoveryReserved)
        assertEquals(initial.balance.committedUnits, recycled.balance.committedUnits)
        assertSame(initial.configuration, recycled.configuration)
        assertNotSame(initial, recycled)

        val respent = recycled.spendTestReserve(digest, pool.ceiling, ComplaintCapacityVector.ZERO)
        assertEquals(initial.balance.free, respent.balance.free)
        assertEquals(retainedActual + pool.ceiling, respent.balance.actual)
        assertEquals(otherTest, respent.balance.testReserved)
        assertEquals(recovery, respent.balance.recoveryReserved)
        val recycledAgain = respent.recycleTestScanPool(digest, pool, ComplaintCapacityVector.ZERO, pool.ceiling)
        val finalUnusedRelease = recycledAgain.releaseTestReserve(digest, pool.ceiling)
        assertEquals(initial.balance.free + pool.ceiling, finalUnusedRelease.balance.free)
        assertEquals(retainedActual, finalUnusedRelease.balance.actual)
        assertEquals(otherTest, finalUnusedRelease.balance.testReserved)
        assertEquals(recovery, finalUnusedRelease.balance.recoveryReserved)
        listOf(initial, recycled, respent, recycledAgain, finalUnusedRelease).forEach(::assertConserved)

        assertFailure(ComplaintCapacityFailureCode.CREATION_CLOSED) { initial.reserveTest(digest, paid) }
        val globallyRefunded = initial.refundActual(digest, paid)
        assertFailure(ComplaintCapacityFailureCode.CREATION_CLOSED) { globallyRefunded.reserveTest(digest, paid) }
        assertEquals(paid + retainedActual, initial.balance.actual)
        assertEquals(unused + otherTest, initial.balance.testReserved)
    }

    @Test
    fun testScanPoolRecycleRejectsAggregateMismatchWithoutPartialTransfers() {
        val pool = TestTerminalScanPoolV1(2)
        val unused = pool.chargeFor(1, 1)
        val paid = pool.ceiling - unused
        val dimensions = listOf(ComplaintCapacityCounter.SCAN_RUNS, ComplaintCapacityCounter.SCAN_ENTRIES, ComplaintCapacityCounter.STORAGE_BYTES)
        for (counter in dimensions) {
            val missingActual = scanLedger(paid.with(counter, paid[counter] - 1), unused)
            val missingReserve = scanLedger(paid, unused.with(counter, unused[counter] - 1))
            for (invalid in listOf(missingActual, missingReserve)) {
                val before = invalid.balance
                // The whole declared materialized/unused pool must fit, even for zero or smaller use.
                for (removed in listOf(ComplaintCapacityVector.ZERO, pool.chargeFor(0, 1))) {
                    assertFailure(ComplaintCapacityFailureCode.INSUFFICIENT_UNITS) {
                        invalid.recycleTestScanPool(digest, pool, unused, removed)
                    }
                    assertSame(before, invalid.balance)
                    assertConserved(invalid)
                }
            }
        }
    }

    @Test
    fun testScanPoolRecycleCapsDedicatedCreditDespiteUnrelatedHeadroom() {
        val pool = TestTerminalScanPoolV1(2)
        val unused = pool.chargeFor(1, 2)
        val paid = pool.ceiling - unused
        val otherPaidPools = pool.ceiling.scaled(2)
        val otherUnusedPools = pool.ceiling.scaled(2)
        val initial = scanLedger(paid + otherPaidPools, unused + otherUnusedPools)
        val oneEntryOver = pool.chargeFor(1, 3)
        assertTrue(oneEntryOver.fitsWithin(initial.balance.actual))
        val before = initial.balance
        assertFailure(ComplaintCapacityFailureCode.RESERVATION_EXCEEDED) {
            initial.recycleTestScanPool(digest, pool, unused, oneEntryOver)
        }
        assertSame(before, initial.balance)

        val full = initial.recycleTestScanPool(digest, pool, unused, paid)
        assertEquals(otherPaidPools, full.balance.actual)
        assertEquals(pool.ceiling + otherUnusedPools, full.balance.testReserved)
        assertEquals(initial.balance.free, full.balance.free)
        assertConserved(full)
        // A durable writer must supply the new unused value; numbers alone do not authenticate replay.
        assertFailure(ComplaintCapacityFailureCode.RESERVATION_EXCEEDED) {
            full.recycleTestScanPool(digest, pool, pool.ceiling, pool.chargeFor(0, 1))
        }

        val maximumPool = TestTerminalScanPoolV1(TestTerminalScanPoolV1.MAX_RETAINED_VERSIONS_BY_STORAGE_ARITHMETIC)
        val maximum = ComplaintCapacityLedger(
            ComplaintCapacityConfiguration.of(digest, true),
            ComplaintCapacityBalance(maximumPool.ceiling, ComplaintCapacityVector.ZERO, ComplaintCapacityVector.ZERO, maximumPool.ceiling),
        )
        val transferred = maximum.recycleTestScanPool(digest, maximumPool, ComplaintCapacityVector.ZERO, maximumPool.ceiling)
        assertEquals(ComplaintCapacityVector.ZERO, transferred.balance.actual)
        assertEquals(ComplaintCapacityVector.ZERO, transferred.balance.free)
        assertEquals(ComplaintCapacityVector.ZERO, transferred.balance.recoveryReserved)
        assertEquals(maximumPool.ceiling, transferred.balance.testReserved)
        assertEquals(maximumPool.ceiling, maximum.balance.actual)
        assertConserved(transferred)
    }

    private fun scanLedger(
        actual: ComplaintCapacityVector,
        testReserved: ComplaintCapacityVector,
        recoveryReserved: ComplaintCapacityVector = ComplaintCapacityVector.ZERO,
    ): ComplaintCapacityLedger {
        val free = vector(10)
        val hard = free + actual + recoveryReserved + testReserved
        return ComplaintCapacityLedger(
            ComplaintCapacityConfiguration.of(digest, true),
            ComplaintCapacityBalance(hard, ComplaintCapacityVector.ZERO, free, actual, recoveryReserved, testReserved),
        )
    }

    private fun assertConserved(ledger: ComplaintCapacityLedger) {
        assertEquals(ledger.balance.hardLimit, ledger.balance.free + ledger.balance.actual + ledger.balance.recoveryReserved + ledger.balance.testReserved)
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
