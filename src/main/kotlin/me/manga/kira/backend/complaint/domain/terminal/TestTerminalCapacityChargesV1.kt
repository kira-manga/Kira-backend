package me.manga.kira.backend.complaint.domain.terminal

import me.manga.kira.backend.complaint.domain.ComplaintCapacityCharges
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.OwnerDeleteAllCapacityCharges

/**
 * Closed TEST logical row/index inputs, not a complete qualified reserve or allocation authority.
 * Scoped catalog/run/control/notice prices promote the existing TestTerminalCapacityIT profiles.
 * The scan profiles and durable sidecar still require their independent qualification gates.
 */
internal object TestTerminalCapacityChargesV1 {
    const val PROFILE = "TEST_TERMINAL_CAPACITY_V1"
    const val MAX_CATALOG_DOCUMENT_BYTES = 131_072

    // Scoped SINGLE, both documents/evidence and all five indexes. No general 8-MiB profile.
    private const val CATALOG_DOCUMENT_DATUM_BYTES = 8L * ((MAX_CATALOG_DOCUMENT_BYTES + 4L + 7L) / 8L)
    const val SCOPED_CATALOG_STORAGE_BYTES = 8L * (2L * CATALOG_DOCUMENT_DATUM_BYTES + 140_336L)
    const val ACTIVE_RUN_STORAGE_BYTES = 5_632L
    const val MAXIMUM_TERMINAL_RUN_STORAGE_BYTES = 1_074_240L
    const val TERMINAL_RUN_DELTA_STORAGE_BYTES = MAXIMUM_TERMINAL_RUN_STORAGE_BYTES - ACTIVE_RUN_STORAGE_BYTES
    const val CONTROL_STORAGE_BYTES = 1_599_808L
    const val SYSTEM_NOTICE_STORAGE_BYTES = 16_384L

    // V14 scan run: 17 columns, individually padded fixed fields; d(n)=8*ceil((n+4)/8).
    const val SCAN_RUN_HEAP_BYTES = 32L + 152L + 64L
    const val SCAN_RUN_INDEX_BYTES = 56L + 72L + 96L
    const val SCAN_RUN_STORAGE_BYTES = 8L * (SCAN_RUN_HEAP_BYTES + SCAN_RUN_INDEX_BYTES)

    // V14 scan entry: 14 columns, key/version1024, all four indexes, every replay state.
    const val SCAN_ENTRY_HEAP_BYTES = 32L + 80L + 2_256L
    const val SCAN_ENTRY_INDEX_BYTES = 2_120L + 72L + 80L + 72L
    const val SCAN_ENTRY_STORAGE_BYTES = 8L * (SCAN_ENTRY_HEAP_BYTES + SCAN_ENTRY_INDEX_BYTES)

    // Enrollment audit is ordinary creation, not part of the run's prepaid ID/credential share.
    val INSTALLATION_SHARE: ComplaintCapacityVector =
        ComplaintCapacityCharges.INSTALLATION_ID + ComplaintCapacityCharges.INSTALLATION_CREDENTIAL
    val AUDIT: ComplaintCapacityVector = ComplaintCapacityCharges.AUDIT
    val PUBLICATION_WITH_RESERVATION: ComplaintCapacityVector =
        OwnerDeleteAllCapacityCharges.PUBLICATION + OwnerDeleteAllCapacityCharges.RESERVATION
    val SCOPED_CATALOG: ComplaintCapacityVector = row(ComplaintCapacityCounter.CATALOG_MUTATIONS, SCOPED_CATALOG_STORAGE_BYTES)
    val ACTIVE_RUN: ComplaintCapacityVector = row(ComplaintCapacityCounter.TEST_RUNS, ACTIVE_RUN_STORAGE_BYTES)
    val TERMINAL_RUN_DELTA: ComplaintCapacityVector = ComplaintCapacityVector.units(
        ComplaintCapacityCounter.STORAGE_BYTES,
        TERMINAL_RUN_DELTA_STORAGE_BYTES,
    )
    val CONTROL: ComplaintCapacityVector = row(ComplaintCapacityCounter.JOURNAL_CONTROL, CONTROL_STORAGE_BYTES)
    val SYSTEM_NOTICE: ComplaintCapacityVector = row(ComplaintCapacityCounter.COMPLAINT_ROWS, SYSTEM_NOTICE_STORAGE_BYTES)
    val NOTICE_WITH_RESOURCE: ComplaintCapacityVector = SYSTEM_NOTICE + ComplaintCapacityCharges.RESOURCE_ID
    val SIDECAR: ComplaintCapacityVector = TestTerminalDurableStorageProfileV1.ROW
    val SCAN_RUN: ComplaintCapacityVector = row(ComplaintCapacityCounter.SCAN_RUNS, SCAN_RUN_STORAGE_BYTES)
    val SCAN_ENTRY: ComplaintCapacityVector = row(ComplaintCapacityCounter.SCAN_ENTRIES, SCAN_ENTRY_STORAGE_BYTES)

    private fun row(counter: ComplaintCapacityCounter, storageBytes: Long): ComplaintCapacityVector =
        ComplaintCapacityVector.units(counter, 1).with(ComplaintCapacityCounter.STORAGE_BYTES, storageBytes)
}
