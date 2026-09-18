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

    const val INSTALLATION_CONTENT_PROFILE_VERSION = 1

    // Clean-start backend-owned INSTALLATION REPORT and REPLY share this prepaid V14 lifecycle:
    // 4000-byte body, 2000-byte closure, bounded diagnostics/scalars and B-tree/GIN entries,
    // including a reply's parent/notice fields and index. No legacy/SYSTEM profile, physical
    // disk/MVCC measurement or future-erasure allowance is inferred. Every future reply producer
    // must pay this same envelope before it becomes reachable; APPLY refunds only removed rows.
    val INSTALLATION_CONTENT_V1: ComplaintCapacityVector = ComplaintCapacityVector.units(ComplaintCapacityCounter.COMPLAINT_ROWS, 1)
        .with(ComplaintCapacityCounter.STORAGE_BYTES, 256L * 1024)

    /** Numeric-compatible name for the already-paid REPORT profile. */
    val REPORT_CONTENT: ComplaintCapacityVector = INSTALLATION_CONTENT_V1

    val OWNER_CREATE: ComplaintCapacityVector = NORMAL_RECEIPT + RESOURCE_ID + INSTALLATION_CONTENT_V1 + AUDIT

    // The existing INSTALLATION content envelope already covers every legal edit; no new content/ID slot or refund.
    val OWNER_EDIT: ComplaintCapacityVector = NORMAL_RECEIPT + AUDIT
}
