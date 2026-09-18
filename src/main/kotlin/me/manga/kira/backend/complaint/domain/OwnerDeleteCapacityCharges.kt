package me.manga.kira.backend.complaint.domain

/** TEST ordinary one-target logical envelopes. No terminal/retirement rows or free reconstructed rows. */
internal object OwnerDeleteCapacityCharges {
    const val MAX_RETAINED_CANDIDATES = 4
    val RECEIPT = ComplaintCapacityCharges.NORMAL_RECEIPT
    val PUBLICATION = OwnerDeleteAllCapacityCharges.PUBLICATION
    val RESERVATION = OwnerDeleteAllCapacityCharges.RESERVATION
    val APPLIED = OwnerDeleteAllCapacityCharges.APPLIED
    val AUTHORIZATION = RECEIPT + PUBLICATION + RESERVATION + ComplaintCapacityCharges.AUDIT
    val RECOVERY = ComplaintCapacityCharges.INSTALLATION_ID + ComplaintCapacityCharges.RESOURCE_ID +
        ComplaintCapacityCharges.AUDIT.scaled(5) + APPLIED.scaled(MAX_RETAINED_CANDIDATES.toLong())
}
