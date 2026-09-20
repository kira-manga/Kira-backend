package me.manga.kira.backend.complaint.domain

import me.manga.kira.backend.complaint.domain.terminal.TestTerminalScanPoolV1

/**
 * Pure accounting only. A caller must lock/revalidate the configuration, exact scoped obligation and
 * affected rows, then commit the resulting balances with those rows in one transaction. These methods
 * neither authenticate erasure evidence nor make a reservation conversion idempotent by themselves.
 */
class ComplaintCapacityLedger(val configuration: ComplaintCapacityConfiguration, val balance: ComplaintCapacityBalance) {
    fun chargeCreation(expectedDigest: ByteArray, charge: ComplaintCapacityVector): ComplaintCapacityLedger {
        configuration.requireCreationAllowed(expectedDigest)
        requireCreationHeadroom(charge)
        return next(balance.copy(free = balance.free - charge, actual = balance.actual + charge))
    }

    /** Refund only the exact charge of rows physically removed or content bytes durably shrunk. */
    fun refundActual(expectedDigest: ByteArray, removed: ComplaintCapacityVector): ComplaintCapacityLedger {
        configuration.requireMatching(expectedDigest)
        return next(balance.copy(free = balance.free + removed, actual = balance.actual - removed))
    }

    /** Privacy obligations use hard-limit headroom, never another event's already promised capacity. */
    fun reserveRecovery(expectedDigest: ByteArray, promised: ComplaintCapacityVector): ComplaintCapacityLedger {
        configuration.requireMatching(expectedDigest)
        return next(balance.copy(free = balance.free - promised, recoveryReserved = balance.recoveryReserved + promised))
    }

    /** Immediate privacy bookkeeping uses unpromised hard-limit units, independently of creation closure. */
    fun chargePrivacyActual(expectedDigest: ByteArray, charge: ComplaintCapacityVector): ComplaintCapacityLedger {
        configuration.requireMatching(expectedDigest)
        return next(balance.copy(free = balance.free - charge, actual = balance.actual + charge))
    }

    /**
     * Convert the locked event's original promise once; release only its proved-unused remainder.
     * The physical reservation row was charged separately and is not part of this future-work vector.
     */
    fun convertRecovery(expectedDigest: ByteArray, originalPromise: ComplaintCapacityVector, actualUse: ComplaintCapacityVector): ComplaintCapacityLedger {
        configuration.requireMatching(expectedDigest)
        if (!actualUse.fitsWithin(originalPromise)) rejectCapacity(ComplaintCapacityFailureCode.RESERVATION_EXCEEDED)
        return next(
            balance.copy(
                free = balance.free + (originalPromise - actualUse),
                actual = balance.actual + actualUse,
                recoveryReserved = balance.recoveryReserved - originalPromise,
            ),
        )
    }

    /**
     * Materialize only this locked event's new rows, keeping its later obligations reserved.
     * Remaining is the immutable original promise minus its already committed cumulative use.
     * The caller must persist that progress with the row changes; this helper proves no authority or replay.
     */
    fun spendRecovery(expectedDigest: ByteArray, remaining: ComplaintCapacityVector, actualUse: ComplaintCapacityVector): ComplaintCapacityLedger {
        configuration.requireMatching(expectedDigest)
        if (!actualUse.fitsWithin(remaining)) rejectCapacity(ComplaintCapacityFailureCode.RESERVATION_EXCEEDED)
        if (!remaining.fitsWithin(balance.recoveryReserved)) rejectCapacity(ComplaintCapacityFailureCode.INSUFFICIENT_UNITS)
        return next(balance.copy(actual = balance.actual + actualUse, recoveryReserved = balance.recoveryReserved - actualUse))
    }

    /** Only an authenticated completed terminal transition can prove a still-reserved slice unused. */
    fun releaseRecovery(expectedDigest: ByteArray, provedUnused: ComplaintCapacityVector): ComplaintCapacityLedger {
        configuration.requireMatching(expectedDigest)
        return next(balance.copy(free = balance.free + provedUnused, recoveryReserved = balance.recoveryReserved - provedUnused))
    }

    fun reserveTest(expectedDigest: ByteArray, promised: ComplaintCapacityVector): ComplaintCapacityLedger {
        configuration.requireCreationAllowed(expectedDigest)
        requireCreationHeadroom(promised)
        return next(balance.copy(free = balance.free - promised, testReserved = balance.testReserved + promised))
    }

    /** A run's exact locked slice pays for actual bookkeeping and future recovery without double charging. */
    fun spendTestReserve(expectedDigest: ByteArray, toActual: ComplaintCapacityVector, toRecovery: ComplaintCapacityVector): ComplaintCapacityLedger {
        configuration.requireMatching(expectedDigest)
        return next(
            balance.copy(
                actual = balance.actual + toActual,
                recoveryReserved = balance.recoveryReserved + toRecovery,
                testReserved = balance.testReserved - (toActual + toRecovery),
            ),
        )
    }

    fun releaseTestReserve(expectedDigest: ByteArray, provedUnused: ComplaintCapacityVector): ComplaintCapacityLedger {
        configuration.requireMatching(expectedDigest)
        return next(balance.copy(free = balance.free + provedUnused, testReserved = balance.testReserved - provedUnused))
    }

    /**
     * Recycle only exact paid scan-pool rows physically removed in the same future fenced transaction.
     * The declared pool/unused values have numerical checks, not scoped-row or replay authority. The
     * writer must reconstruct their exact ownership, retain required witnesses and credit the run's
     * unused vector with the same delete/counter commit. Global free and creation admission are unchanged.
     */
    internal fun recycleTestScanPool(
        expectedDigest: ByteArray,
        pool: TestTerminalScanPoolV1,
        unusedPool: ComplaintCapacityVector,
        removed: ComplaintCapacityVector,
    ): ComplaintCapacityLedger {
        configuration.requireMatching(expectedDigest)
        pool.unusedAfterRecycle(unusedPool, removed)
        if (!unusedPool.fitsWithin(balance.testReserved) || !(pool.ceiling - unusedPool).fitsWithin(balance.actual)) {
            rejectCapacity(ComplaintCapacityFailureCode.INSUFFICIENT_UNITS)
        }
        return next(balance.copy(actual = balance.actual - removed, testReserved = balance.testReserved + removed))
    }

    private fun requireCreationHeadroom(charge: ComplaintCapacityVector) {
        if (!(balance.committedUnits + charge).fitsWithin(balance.creationLimit)) rejectCapacity(ComplaintCapacityFailureCode.CREATION_LIMIT_REACHED)
    }

    private fun next(nextBalance: ComplaintCapacityBalance): ComplaintCapacityLedger = ComplaintCapacityLedger(configuration, nextBalance)

    override fun toString(): String = "ComplaintCapacityLedger(redacted)"
}
