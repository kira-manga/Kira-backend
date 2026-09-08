package me.manga.kira.backend.complaint.domain

/** Version-1 lifecycle-max logical charges, not PostgreSQL/MVCC/disk measurements. No implicit charge exists for other row classes. */
object ComplaintCapacityCharges {
    const val MAX_AUDIT_PAYLOAD_BYTES = 2 * 1024

    val MODERATION_GRANT: ComplaintCapacityVector = ComplaintCapacityVector.units(ComplaintCapacityCounter.MODERATION_GRANTS, 1)
        .with(ComplaintCapacityCounter.STORAGE_BYTES, 16L * 1024)

    val AUDIT: ComplaintCapacityVector = ComplaintCapacityVector.units(ComplaintCapacityCounter.AUDIT_ROWS, 1)
        .with(ComplaintCapacityCounter.STORAGE_BYTES, 64L * 1024)
}
