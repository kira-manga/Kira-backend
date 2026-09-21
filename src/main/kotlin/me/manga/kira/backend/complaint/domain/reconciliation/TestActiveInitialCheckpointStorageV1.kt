package me.manga.kira.backend.complaint.domain.reconciliation

import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector

/** V27 initial-owned scan rows only. Logical maximum row/index price, not disk/WAL qualification. */
internal object TestActiveInitialCheckpointStorageV1 {
    const val PROFILE = "TEST_INITIAL_EMPTY_CHECKPOINT_SCAN_STORAGE_V1"
    const val SCHEMA_VERSION = 1
    const val MAX_RUN_ROWS = 2L
    const val MAX_ENTRY_ROWS = 0L
    // V14's17 fields plus populated initial seal UUID/price. d(n)=8*ceil((n+4)/8).
    const val HEAP_HEADER_BYTES = 32L
    const val PADDED_FIXED_FIELD_BYTES = 176L
    const val VARIABLE_FIELD_BYTES = 64L
    const val MAX_HEAP_ROW_BYTES = HEAP_HEADER_BYTES + PADDED_FIXED_FIELD_BYTES + VARIABLE_FIELD_BYTES
    // Three V14 indexes plus the initial-only (scope,pass) unique index; NULL legacy rows do not enter it.
    const val MAX_INDEX_BYTES = 56L + 72L + 96L + 56L
    const val SAFETY_MULTIPLIER = 8L
    const val STORAGE_BYTES = SAFETY_MULTIPLIER * (MAX_HEAP_ROW_BYTES + MAX_INDEX_BYTES)
    const val MAX_PAIR_STORAGE_BYTES = MAX_RUN_ROWS * STORAGE_BYTES
    val ROW = ComplaintCapacityVector.units(ComplaintCapacityCounter.SCAN_RUNS, 1L)
        .with(ComplaintCapacityCounter.STORAGE_BYTES, STORAGE_BYTES)

    fun rows(count: Int): ComplaintCapacityVector {
        require(count in 0..2) { "Invalid initial checkpoint row count" }
        return ROW.scaled(count.toLong())
    }
}
