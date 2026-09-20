package me.manga.kira.backend.complaint.domain.catalog

import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector

/**
 * Fixed schema1/empty-inventory activation3 only. One distinct up-front maximum-lifecycle
 * logical reservation; not a measured PostgreSQL/MVCC/disk charge and not a widened G1 profile.
 *
 * Like G1 this V14 row has 33 columns, five indexes, no scope/test flag and no second signer.
 * Both documents are at most 128 KiB, approval 4096 bytes, the single signature at most 1024
 * bytes (this producer requires RSA's 384), and each independent evidence blob at most 65536.
 * The 64/16/24/128/128/16-byte operation/canonicalizer/policy/id/algorithm/state text bounds,
 * two 1024-byte key/version fields, six hashes and four timestamps fit G1's conservative
 * tuple calculation exactly. SIGNER_ROTATION_ACTIVATION is within that 64-byte operation cap.
 * All lifecycle columns and future partial-index membership are prepaid at PREPARE, once.
 */
internal object CatalogSignerRotationActivationCapacityV1 {
    const val MAX_DOCUMENT_BYTES = 128 * 1024
    const val MAX_APPROVAL_BYTES = 4096

    val maximumHeapTupleBytes: Long = CatalogGenesisCapacity.maximumHeapTupleBytes
    val maximumIndexTupleBytes: List<Long> get() = CatalogGenesisCapacity.maximumIndexTupleBytes
    val storageBytes: Long = (maximumHeapTupleBytes + maximumIndexTupleBytes.sum()) * 8
    val charge: ComplaintCapacityVector = ComplaintCapacityVector.units(ComplaintCapacityCounter.CATALOG_MUTATIONS, 1)
        .with(ComplaintCapacityCounter.STORAGE_BYTES, storageBytes)
}
