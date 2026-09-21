package me.manga.kira.backend.complaint.domain

/** One receipt/outbox, bounded owner set, and four TOTAL native versions, not N single events. */
internal object AdminBatchDeleteCapacityCharges {
    fun authorization(targetCount: Int): ComplaintCapacityVector {
        require(targetCount in 1..50)
        return OwnerDeleteCapacityCharges.RECEIPT + OwnerDeleteCapacityCharges.PUBLICATION +
            OwnerDeleteCapacityCharges.RESERVATION + ComplaintCapacityCharges.AUDIT.scaled(targetCount.toLong())
    }
    fun recovery(ownerCount: Int, targetCount: Int): ComplaintCapacityVector {
        require(targetCount in 1..50 && ownerCount in 1..targetCount)
        return ComplaintCapacityCharges.INSTALLATION_ID.scaled(ownerCount.toLong()) +
            ComplaintCapacityCharges.RESOURCE_ID.scaled(targetCount.toLong()) +
            ComplaintCapacityCharges.AUDIT.scaled(targetCount.toLong() + OwnerDeleteCapacityCharges.MAX_RETAINED_CANDIDATES) +
            OwnerDeleteCapacityCharges.APPLIED.scaled(OwnerDeleteCapacityCharges.MAX_RETAINED_CANDIDATES.toLong())
    }
}
