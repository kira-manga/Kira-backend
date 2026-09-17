package me.manga.kira.backend.complaint.domain

/** Version-1 lifecycle-max logical charges, not PostgreSQL/MVCC/disk measurements. No implicit charge exists for other row classes. */
object ComplaintCapacityCharges {
    const val MAX_AUDIT_PAYLOAD_BYTES = 2 * 1024

    val MODERATION_GRANT: ComplaintCapacityVector = ComplaintCapacityVector.units(ComplaintCapacityCounter.MODERATION_GRANTS, 1)
        .with(ComplaintCapacityCounter.STORAGE_BYTES, 16L * 1024)

    val AUDIT: ComplaintCapacityVector = ComplaintCapacityVector.units(ComplaintCapacityCounter.AUDIT_ROWS, 1)
        .with(ComplaintCapacityCounter.STORAGE_BYTES, 64L * 1024)

    // V14 lifecycle envelopes: each heap row <512 bytes, each of the three ID/six credential
    // index tuples <128 bytes. Sixteen KiB per row deliberately exceeds their combined bounds;
    // focused PostgreSQL tests pin all columns/indexes and every legal lifecycle shape. These
    // logical charges are not a promise about page/MVCC/vacuum usage or future erasure headroom.
    val INSTALLATION_ID: ComplaintCapacityVector = ComplaintCapacityVector.units(ComplaintCapacityCounter.INSTALLATION_IDS, 1)
        .with(ComplaintCapacityCounter.STORAGE_BYTES, 16L * 1024)

    val INSTALLATION_CREDENTIAL: ComplaintCapacityVector = ComplaintCapacityVector.units(ComplaintCapacityCounter.APP_INSTALLATIONS, 1)
        .with(ComplaintCapacityCounter.STORAGE_BYTES, 16L * 1024)

    val INSTALLATION_ENROLLMENT: ComplaintCapacityVector = INSTALLATION_ID + INSTALLATION_CREDENTIAL + AUDIT

    // Full V14 normal-receipt lifecycle: <=50 UUID targets/acknowledgements and versions, bounded
    // 1024-byte external version plus all scalars/index tuples fit well below this 128 KiB charge.
    // OWNER_CREATE never writes external evidence. Charge the same maximum for APPLIED/REJECTED.
    val NORMAL_RECEIPT: ComplaintCapacityVector = ComplaintCapacityVector.units(ComplaintCapacityCounter.NORMAL_RECEIPTS, 1)
        .with(ComplaintCapacityCounter.STORAGE_BYTES, 128L * 1024)

    val RESOURCE_ID: ComplaintCapacityVector = ComplaintCapacityVector.units(ComplaintCapacityCounter.RESOURCE_IDS, 1)
        .with(ComplaintCapacityCounter.STORAGE_BYTES, 16L * 1024)

    // Installation report lifecycle includes later 4000-byte edited body/2000-byte closure,
    // diagnostics, all fixed/scalar columns and bounded B-tree/GIN entries. Not a legacy profile,
    // physical disk/MVCC measurement or reserve for future erasure/catalog work.
    val REPORT_CONTENT: ComplaintCapacityVector = ComplaintCapacityVector.units(ComplaintCapacityCounter.COMPLAINT_ROWS, 1)
        .with(ComplaintCapacityCounter.STORAGE_BYTES, 256L * 1024)

    val OWNER_CREATE: ComplaintCapacityVector = NORMAL_RECEIPT + RESOURCE_ID + REPORT_CONTENT + AUDIT
}
