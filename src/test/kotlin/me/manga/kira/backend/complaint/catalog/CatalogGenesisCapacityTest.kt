package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.catalog.CatalogGenesisCapacity
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleProtocol
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CatalogGenesisCapacityTest {
    @Test
    fun `G1 prepays both documents both future copy evidences timestamps and all five indexes with an eightfold margin`() {
        assertEquals(128 * 1024, CatalogGenesisCapacity.MAX_DOCUMENT_BYTES)
        assertEquals(OfflineTrustBundleProtocol.MAX_ENVELOPE_BYTES, CatalogGenesisCapacity.MAX_DOCUMENT_BYTES)
        assertEquals(401224L, CatalogGenesisCapacity.maximumHeapTupleBytes)
        assertEquals(listOf(48L, 40L, 1064L, 40L, 56L), CatalogGenesisCapacity.maximumIndexTupleBytes)
        assertEquals(3219776L, CatalogGenesisCapacity.storageBytes)
        val expected = LongArray(22).also {
            it[2] = 1
            it[20] = 3219776
        }
        assertArrayEquals(expected, CatalogGenesisCapacity.charge.toLongArray())
        CatalogGenesisCapacity.charge.toLongArray().fill(0)
        assertEquals(1L, CatalogGenesisCapacity.charge[ComplaintCapacityCounter.CATALOG_MUTATIONS])
        assertArrayEquals(expected, CatalogGenesisCapacity.charge.toLongArray())
    }
}
