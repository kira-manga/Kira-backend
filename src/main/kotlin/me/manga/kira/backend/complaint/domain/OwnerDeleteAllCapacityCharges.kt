package me.manga.kira.backend.complaint.domain

/**
 * Fixed V14 logical lifecycle envelopes, not page/MVCC measurements or full-D reserve sizing proof.
 * Deletion receipts have one <=1024-byte version plus bounded scalars/indexes (32KiB). Publications
 * and retirements have two <=65536-byte documents plus bounded keys/version/scalars/indexes (256KiB).
 * Recovery rows have two 22-long vectors and bounded scalar/index entries (16KiB); applied rows have
 * <=1024-byte key/version and bounded scalar/index entries (32KiB). No content or extra row is free.
 */
internal object OwnerDeleteAllCapacityCharges {
    val RECEIPT = ComplaintCapacityVector.units(ComplaintCapacityCounter.INSTALLATION_RECEIPTS, 1)
        .with(ComplaintCapacityCounter.STORAGE_BYTES, 32L * 1024)
    val PUBLICATION = ComplaintCapacityVector.units(ComplaintCapacityCounter.JOURNAL_PUBLICATIONS, 1)
        .with(ComplaintCapacityCounter.STORAGE_BYTES, 256L * 1024)
    val RESERVATION = ComplaintCapacityVector.units(ComplaintCapacityCounter.RECOVERY_RESERVATIONS, 1)
        .with(ComplaintCapacityCounter.STORAGE_BYTES, 16L * 1024)
    val APPLIED = ComplaintCapacityVector.units(ComplaintCapacityCounter.JOURNAL_APPLIED, 1)
        .with(ComplaintCapacityCounter.STORAGE_BYTES, 32L * 1024)
    val RETIREMENT = ComplaintCapacityVector.units(ComplaintCapacityCounter.JOURNAL_RETIREMENTS, 1)
        .with(ComplaintCapacityCounter.STORAGE_BYTES, 256L * 1024)

    // The reservation's own physical row is actual, never recursively part of its future promise.
    val AUTHORIZATION = RECEIPT + PUBLICATION + RESERVATION + ComplaintCapacityCharges.AUDIT

    // Conservative 100-target snapshot and the protocol's maximum four retained routing candidates.
    // Existing IDs are already paid: apply/recovery must convert only genuinely new evidence once,
    // never claim future terminal obligations unused merely because no terminal row exists yet.
    val RECOVERY = ComplaintCapacityCharges.INSTALLATION_ID + ComplaintCapacityCharges.RESOURCE_ID.scaled(100) +
        ComplaintCapacityCharges.AUDIT.scaled(101) + (APPLIED + RETIREMENT).scaled(4)
}
