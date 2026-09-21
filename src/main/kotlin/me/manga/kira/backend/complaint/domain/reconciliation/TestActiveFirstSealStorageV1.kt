package me.manga.kira.backend.complaint.domain.reconciliation

import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalProfileV1

/** One independently paid V26 slot. No terminal reserve, scan pool, disk qualification or allocation authority. */
internal object TestActiveFirstSealStorageV1 {
    const val PROFILE = "TEST_ACTIVE_FIRST_CUT_PAID_SEAL_SLOT_V1"
    const val SCHEMA_VERSION = 1
    const val MAX_SLOTS_PER_RUN = 1
    const val MAX_CANONICAL_BYTES = 65_536
    const val MAX_WIRE_BYTES = 98_304
    const val MAX_METADATA_BYTES = 512
    const val MAX_OBJECT_KEY_BYTES = 1024
    const val MAX_ROUTING_KEY_ID_BYTES = 64
    const val OBJECT_ID_BYTES = 43
    const val STORAGE_BYTES = 2_097_152L

    // Exactly V26's 49 columns: 29 fixed fields individually padded to8, and20
    // variable fields with d(n)=8*ceil((n+4)/8). The nine32-byte hashes are counted
    // in that variable sum, not hidden in the fixed-field allowance.
    const val HEAP_HEADER_BYTES = 32L
    const val PADDED_FIXED_FIELD_BYTES = 296L
    const val VARIABLE_FIELD_BYTES = 166_032L
    const val MAX_HEAP_ROW_BYTES = HEAP_HEADER_BYTES + PADDED_FIXED_FIELD_BYTES + VARIABLE_FIELD_BYTES
    // PK operation UUID; UNIQUE scope UUID; UNIQUE object_key<=1024; UNIQUE object_id43.
    const val MAX_INDEX_BYTES = 48L + 48L + 1064L + 80L
    const val SAFETY_MULTIPLIER = 8L
    const val LOGICAL_ENVELOPE_BYTES = SAFETY_MULTIPLIER * (MAX_HEAP_ROW_BYTES + MAX_INDEX_BYTES)
    val ROW = ComplaintCapacityVector.units(ComplaintCapacityCounter.STORAGE_BYTES, STORAGE_BYTES)

    /** Existing EPOCH_SEAL format commitment only; never TEST terminal lifecycle/spending authority. */
    val sealEncodingSha256: String get() = TestTerminalProfileV1.encodingSha256
}
