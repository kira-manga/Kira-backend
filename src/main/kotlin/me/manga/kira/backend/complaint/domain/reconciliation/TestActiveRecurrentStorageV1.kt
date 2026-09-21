package me.manga.kira.backend.complaint.domain.reconciliation

import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalCapacityChargesV1

/**
 * V31 ordinary actual-paid envelopes, never the V21 terminal reserve. V26/V27 prices are unchanged.
 * These are bounded logical row/index prices, not PostgreSQL/TOAST/WAL or provider qualification.
 */
internal object TestActiveRecurrentStorageV1 {
    const val PROFILE = "TEST_ACTIVE_RECURRENT_OWNERSHIP_V1"
    const val SCHEMA_VERSION = 1
    // The mandatory final post-denial ordinary seal and terminal seal still need two of sixteen slots.
    const val MAX_ACTIVE_SEALS = 14
    const val MAX_RECURRENT_INTENTS = MAX_ACTIVE_SEALS - 1
    const val MAX_HISTORY_ENTRY_BYTES = 16_384
    const val MAX_CHECKPOINT_BYTES = 65_536
    const val MAX_VERIFICATION_BYTES = 65_536
    const val INTENT_STORAGE_BYTES = 2_097_152L
    const val HISTORY_STORAGE_BYTES = 2_097_152L
    // V26's envelope plus the predecessor UUID, two hashes, and (scope,rotation) index enlargement.
    const val INTENT_LOGICAL_ENVELOPE_BYTES = TestActiveFirstSealStorageV1.LOGICAL_ENVELOPE_BYTES + 8L * (16L + 80L + 8L)
    // 18 history columns. Individually padded fixed values + bounded text/bytes, all three indexes.
    const val HISTORY_HEAP_BYTES = 32L + 144L + 24L + 1_032L + 4L * 40L +
        16_392L + 65_544L + 65_544L
    const val HISTORY_INDEX_BYTES = 56L + 48L + 56L
    const val HISTORY_LOGICAL_ENVELOPE_BYTES = 8L * (HISTORY_HEAP_BYTES + HISTORY_INDEX_BYTES)
    // V14's17 fields plus this branch's UUID/price and (scope,pass) partial index. The old
    // initial-owner fields remain NULL; 21 columns still fit the same three-byte null bitmap.
    const val SCAN_RUN_STORAGE_BYTES = 8L * (32L + 176L + 64L + 56L + 72L + 96L + 56L)
    // Reuse only the unchanged V14 fourteen-column sizing, not terminal accounting or authority.
    const val SCAN_ENTRY_STORAGE_BYTES = TestTerminalCapacityChargesV1.SCAN_ENTRY_STORAGE_BYTES
    const val MAX_SCAN_RUNS = 2
    const val PAGE_ROWS = 32

    val INTENT = ComplaintCapacityVector.units(ComplaintCapacityCounter.STORAGE_BYTES, INTENT_STORAGE_BYTES)
    val HISTORY = ComplaintCapacityVector.units(ComplaintCapacityCounter.STORAGE_BYTES, HISTORY_STORAGE_BYTES)
    val SCAN_RUN = ComplaintCapacityVector.units(ComplaintCapacityCounter.SCAN_RUNS, 1)
        .with(ComplaintCapacityCounter.STORAGE_BYTES, SCAN_RUN_STORAGE_BYTES)
    val SCAN_ENTRY = ComplaintCapacityVector.units(ComplaintCapacityCounter.SCAN_ENTRIES, 1)
        .with(ComplaintCapacityCounter.STORAGE_BYTES, SCAN_ENTRY_STORAGE_BYTES)

    fun scanCharge(runs: Long, entries: Long): ComplaintCapacityVector {
        require(runs in 0..MAX_SCAN_RUNS.toLong() && entries >= 0)
        return SCAN_RUN.scaled(runs) + SCAN_ENTRY.scaled(entries)
    }
}
