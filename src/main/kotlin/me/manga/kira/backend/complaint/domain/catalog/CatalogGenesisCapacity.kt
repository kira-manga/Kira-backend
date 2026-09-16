package me.manga.kira.backend.complaint.domain.catalog

import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector

/**
 * G1/SINGLE only: one up-front maximum-lifecycle logical reservation, NOT the V14 8 MiB
 * general-catalog maximum or a PostgreSQL/MVCC/disk measurement. Every G1 write/read must
 * retain these lower document caps; other catalog profiles need their own reviewed charge.
 */
internal object CatalogGenesisCapacity {
    const val MAX_DOCUMENT_BYTES = 128 * 1024

    // 33-column header/null bitmap; two UUIDs; two generations; all four lifecycle timestamps;
    // six digests; all bounded text; unsigned + envelope; approvals; one signature; both copy
    // evidence blobs; object key + version. G1 scope/test flag and the second signer stay NULL.
    // Each datum conservatively keeps a 4-byte varlena header and rounds independently to 8.
    val maximumHeapTupleBytes: Long = 32 + 2 * 16 + 2 * 8 + 4 * 8 + 6 * datum(32) +
        datum(64) + datum(16) + datum(24) + 2 * datum(128) + datum(16) +
        2 * datum(MAX_DOCUMENT_BYTES) + datum(4096) + datum(1024) + 2 * datum(65536) + 2 * datum(1024)

    // All five V14 indexes, including both partial/future membership and a conservative
    // nonnull scope UUID in the scope/successor key. No INCLUDE columns are assumed.
    val maximumIndexTupleBytes: List<Long> get() = listOf(32L + 16, 32L + 8, 32L + datum(1024), 32L + 8, 32L + 16 + 8)

    val storageBytes: Long = (maximumHeapTupleBytes + maximumIndexTupleBytes.sum()) * 8
    val charge: ComplaintCapacityVector = ComplaintCapacityVector.units(ComplaintCapacityCounter.CATALOG_MUTATIONS, 1)
        .with(ComplaintCapacityCounter.STORAGE_BYTES, storageBytes)

    private fun datum(bytes: Int): Long = (bytes.toLong() + 4 + 7) / 8 * 8
}
