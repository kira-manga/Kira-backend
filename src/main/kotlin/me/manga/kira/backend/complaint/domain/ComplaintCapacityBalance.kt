package me.manga.kira.backend.complaint.domain

/** A complete immutable 22-counter snapshot, not evidence that its rows were locked or authenticated. */
data class ComplaintCapacityBalance(
    val hardLimit: ComplaintCapacityVector,
    val creationLimit: ComplaintCapacityVector,
    val free: ComplaintCapacityVector,
    val actual: ComplaintCapacityVector = ComplaintCapacityVector.ZERO,
    val recoveryReserved: ComplaintCapacityVector = ComplaintCapacityVector.ZERO,
    val testReserved: ComplaintCapacityVector = ComplaintCapacityVector.ZERO,
) {
    init {
        if (!creationLimit.fitsWithin(hardLimit)) rejectCapacity(ComplaintCapacityFailureCode.INVALID_CREATION_LIMIT)
        if (free + actual + recoveryReserved + testReserved != hardLimit) rejectCapacity(ComplaintCapacityFailureCode.INCONSISTENT_BALANCE)
    }

    /** Every promise consumes creation headroom even before it becomes an actual row. */
    val committedUnits: ComplaintCapacityVector get() = hardLimit - free
}
