package me.manga.kira.backend.complaint.domain.terminal

import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector

/**
 * Proposed V21 logical sidecar envelope, pending independent PostgreSQL/index/TOAST qualification.
 * Not a complete terminal reserve, a physical disk/MVCC bound, or permission to allocate any row.
 */
internal object TestTerminalDurableStorageProfileV1 {
    const val PROFILE = "TEST_TERMINAL_DURABLE_STORAGE_V1"
    const val SCHEMA_VERSION = 1
    const val CANONICALIZER = "kcj-1"
    const val MAX_CANONICAL_BYTES = TestTerminalProfileV1.MAX_PLAINTEXT_BYTES
    const val MAX_WIRE_BYTES = TestTerminalProfileV1.MAX_ENVELOPE_BYTES
    const val MAX_METADATA_BYTES = 512
    const val MAX_OBJECT_KEY_BYTES = 1024
    const val MAX_ROUTING_KEY_ID_BYTES = 64
    const val CONTENT_TYPE = "application/octet-stream"
    const val OBJECT_LOCK_MODE = "COMPLIANCE"
    const val LAST_EPOCH_SECOND = 253_402_300_799L

    // d(n)=8*ceil((n+4)/8); all variable fields use their V21 byte ceilings, including
    // the sidecar's own canonical copy alongside the maximum future wire/metadata copy.
    // Header32 + individually padded fixed fields136 + sum(d(variable ceilings))166040.
    const val HEAP_HEADER_BYTES = 32L
    const val PADDED_FIXED_FIELD_BYTES = 136L
    const val VARIABLE_FIELD_BYTES = 166_040L
    const val MAX_HEAP_ROW_BYTES = HEAP_HEADER_BYTES + PADDED_FIXED_FIELD_BYTES + VARIABLE_FIELD_BYTES

    // Thirty-two bytes of conservative tuple overhead plus padded key fields per index.
    const val OPERATION_INDEX_BYTES = 48L
    const val SCOPE_KIND_ORDINAL_INDEX_BYTES = 96L
    const val OBJECT_KEY_INDEX_BYTES = 1064L
    const val OBJECT_ID_INDEX_BYTES = 80L
    const val MAX_INDEX_BYTES = OPERATION_INDEX_BYTES + SCOPE_KIND_ORDINAL_INDEX_BYTES +
        OBJECT_KEY_INDEX_BYTES + OBJECT_ID_INDEX_BYTES
    const val SAFETY_MULTIPLIER = 8L
    const val LIFECYCLE_MAX_STORAGE_BYTES = SAFETY_MULTIPLIER * (MAX_HEAP_ROW_BYTES + MAX_INDEX_BYTES)

    /** Pay once on canonical insertion; freezing neither refunds canonical bytes nor pays a second row. */
    val ROW: ComplaintCapacityVector = ComplaintCapacityVector.units(
        ComplaintCapacityCounter.STORAGE_BYTES,
        LIFECYCLE_MAX_STORAGE_BYTES,
    )

    /**
     * Existing TEST_TERMINAL_V1 ceilings: <=4096 chunks, one purge, <=15 pre-terminal seals
     * plus the terminal seal. N=0 has no manifest chunk, but still reserves purge/seal slots.
     * This count does not establish the run's declared limit, writer lineage or admitted reserve.
     */
    fun maximumIntentCount(installationCount: Long): Long =
        TestTerminalSyntaxV1.chunkCount(installationCount).toLong() + 1L + TestTerminalProfileV1.MAX_SEALS

    /** Storage sidecars ONLY. Existing publications/reservations and all other obligations are additional. */
    fun sidecarStorageHighWater(installationCount: Long): ComplaintCapacityVector =
        ROW.scaled(maximumIntentCount(installationCount))
}
