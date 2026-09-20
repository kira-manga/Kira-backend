package me.manga.kira.backend.complaint.domain.catalog

import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector

/** Fixed schema1/empty-inventory overlap2 only. Its own two-signature lifecycle reservation, not the G1 actual0/1 profile. */
internal object CatalogSignerRotationCapacityV1 {
    const val MAX_DOCUMENT_BYTES = 128 * 1024
    const val MAX_APPROVAL_BYTES = 4096

    // All G1 lifecycle columns plus the second bounded id/algorithm/signature; both evidence blobs
    // and all five indexes are prepaid although this first producer never publishes or completes.
    // Logical conservative sizing only, not measured PostgreSQL/MVCC/device storage.
    val maximumHeapTupleBytes: Long = CatalogGenesisCapacity.maximumHeapTupleBytes + datum(128) + datum(16) + datum(1024)
    val maximumIndexTupleBytes: List<Long> get() = CatalogGenesisCapacity.maximumIndexTupleBytes
    val storageBytes: Long = (maximumHeapTupleBytes + maximumIndexTupleBytes.sum()) * 8
    val charge: ComplaintCapacityVector = ComplaintCapacityVector.units(ComplaintCapacityCounter.CATALOG_MUTATIONS, 1)
        .with(ComplaintCapacityCounter.STORAGE_BYTES, storageBytes)

    private fun datum(bytes: Int): Long = (bytes.toLong() + 4 + 7) / 8 * 8
}
