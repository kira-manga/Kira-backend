package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.complaint.domain.catalog.CatalogDeleteMarker
import me.manga.kira.backend.complaint.domain.catalog.CatalogListCursor
import me.manga.kira.backend.complaint.domain.catalog.CatalogListedVersion
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackException
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPolicy
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackProtocol
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackResult
import me.manga.kira.backend.complaint.domain.catalog.LocalCatalogSnapshot
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogVersionListing
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CatalogReadbackListingTest {
    private val fixture by lazy { CatalogReadbackFixture() }

    @Test
    fun `fixed keys have exactly twenty decimal digits and no alternative spelling`() {
        assertEquals("complaints/catalog/v1/00000000000000000001.json", CatalogReadbackProtocol.key(1))
        assertEquals("complaints/catalog/v1/00000000000000065536.json", CatalogReadbackProtocol.key(65536))
        val keys = listOf(
            "complaints/catalog/v1/1.json",
            "complaints/catalog/v1/00000000000000000000.json",
            "complaints/catalog/v1/+0000000000000000001.json",
            "complaints/catalog/v2/00000000000000000001.json",
            "complaints/catalog/v1//00000000000000000001.json",
            "complaints/catalog/v1/00000000000000000001.json/",
            "x".repeat(1025),
        )
        keys.forEach { key ->
            val provider = SyntheticCatalogReadbackPort(fixture.bytes)
            provider.primaryVersions[0] = provider.primaryVersions[0].copy(key = key)
            reject(provider, CatalogReadbackFailure.INVALID_LISTING)
            assertTrue(provider.getRequests.isEmpty())
        }
    }

    @Test
    fun `missing literal-null empty whitespace and overlong version identifiers are rejected before GET`() {
        listOf(null, "null", "", " ", "contains space", "tab\tversion", "non-ascii-\u00e9", "x".repeat(1025)).forEach { version ->
            val provider = SyntheticCatalogReadbackPort(fixture.bytes)
            provider.primaryVersions[0] = provider.primaryVersions[0].copy(versionId = version)
            reject(provider, CatalogReadbackFailure.INVALID_LISTING)
            assertTrue(provider.getRequests.isEmpty())
        }
    }

    @Test
    fun `maximum finite version string is accepted without normalization`() {
        val provider = SyntheticCatalogReadbackPort(fixture.bytes.take(1))
        val version = "v".repeat(1024)
        provider.primaryVersions[0] = provider.primaryVersions[0].copy(versionId = version)
        val listing = CatalogVersionListing(provider, OfflineTrustBundleFixture.locations.first(), fixture.policy())
        assertEquals(version, listing.nextOrNull()?.versionId)
        assertNull(listing.nextOrNull())
        assertEquals(1, provider.listRequests.size)
    }

    @Test
    fun `duplicate entries multiple versions at a key and gaps are all rejected before page traversal can yield`() {
        val original = SyntheticCatalogReadbackPort(fixture.bytes).primaryVersions
        val alternatives = listOf(
            listOf(original[0], original[0]),
            listOf(original[0], original[0].copy(versionId = "another-version")),
            listOf(original[0], original[2]),
        )
        alternatives.forEach { versions ->
            val provider = SyntheticCatalogReadbackPort(fixture.bytes)
            provider.primaryVersions.clear()
            provider.primaryVersions.addAll(versions)
            reject(provider, CatalogReadbackFailure.INVALID_LISTING, fixture.policy(pageSize = 3))
            assertTrue(provider.getRequests.isEmpty())
        }
    }

    @Test
    fun `every delete marker fails closed even when its key would otherwise be a valid generation`() {
        val provider = SyntheticCatalogReadbackPort(fixture.bytes)
        provider.transformPage = { it.copy(deleteMarkers = listOf(CatalogDeleteMarker(CatalogReadbackProtocol.key(1), "deleted"))) }
        reject(provider, CatalogReadbackFailure.LIMIT_EXCEEDED, fixture.policy(pageSize = 3))
        assertTrue(provider.getRequests.isEmpty())
        val onlyMarker = SyntheticCatalogReadbackPort(emptyList())
        onlyMarker.transformPage = { it.copy(deleteMarkers = listOf(CatalogDeleteMarker(CatalogReadbackProtocol.key(1), "deleted"))) }
        reject(onlyMarker, CatalogReadbackFailure.INVALID_LISTING)
    }

    @Test
    fun `provider list counts are checked before indexed access copying or allocation`() {
        var accesses = 0
        val oversized = object : AbstractList<CatalogListedVersion>() {
            override val size: Int = Int.MAX_VALUE
            override fun get(index: Int): CatalogListedVersion {
                accesses++
                error("Synthetic oversized list must not be traversed")
            }
        }
        val provider = SyntheticCatalogReadbackPort(fixture.bytes)
        provider.transformPage = { it.copy(versions = oversized) }
        reject(provider, CatalogReadbackFailure.LIMIT_EXCEEDED)
        assertEquals(0, accesses)
        assertTrue(provider.getRequests.isEmpty())
    }

    @Test
    fun `negative counts and combined version-marker overflow are rejected without traversal`() {
        val negative = object : AbstractList<CatalogListedVersion>() {
            override val size: Int = -1
            override fun get(index: Int): CatalogListedVersion = error("Negative list must not be traversed")
        }
        val provider = SyntheticCatalogReadbackPort(fixture.bytes)
        provider.transformPage = { it.copy(versions = negative) }
        reject(provider, CatalogReadbackFailure.INVALID_LISTING)
        val markers = object : AbstractList<CatalogDeleteMarker>() {
            override val size: Int = Int.MAX_VALUE
            override fun get(index: Int): CatalogDeleteMarker = error("Oversized markers must not be traversed")
        }
        val overflow = SyntheticCatalogReadbackPort(fixture.bytes)
        overflow.transformPage = { it.copy(deleteMarkers = markers) }
        reject(overflow, CatalogReadbackFailure.LIMIT_EXCEEDED)
    }

    @Test
    fun `listing must echo the exact location prefix page bound and owned cursor`() {
        val provider = SyntheticCatalogReadbackPort(fixture.bytes)
        provider.transformPage = { it.copy(requestBinding = it.requestBinding.copy(maxKeys = it.requestBinding.maxKeys + 1)) }
        reject(provider, CatalogReadbackFailure.INVALID_LISTING)
        val wrongCursor = SyntheticCatalogReadbackPort(fixture.bytes)
        wrongCursor.transformPage = { page ->
            if (page.requestBinding.cursor != null) page.copy(requestBinding = page.requestBinding.copy(cursor = null)) else page
        }
        reject(wrongCursor, CatalogReadbackFailure.INVALID_LISTING)
        assertEquals(2, wrongCursor.closedBodies)
    }

    @Test
    fun `a truncated page requires nonempty progress and the exact last key-version tuple`() {
        val cursors = listOf(null, CatalogListCursor("wrong", "wrong"), CatalogListCursor(CatalogReadbackProtocol.key(1), "v".repeat(1025)))
        cursors.forEach { cursor ->
            val provider = SyntheticCatalogReadbackPort(fixture.bytes)
            provider.transformPage = { it.copy(isTruncated = true, nextCursor = cursor) }
            reject(provider, CatalogReadbackFailure.INVALID_LISTING)
            assertTrue(provider.getRequests.isEmpty())
        }
        val empty = SyntheticCatalogReadbackPort(emptyList())
        empty.transformPage = { it.copy(isTruncated = true, nextCursor = CatalogListCursor(CatalogReadbackProtocol.key(1), "v")) }
        reject(empty, CatalogReadbackFailure.INVALID_LISTING)
    }

    @Test
    fun `a terminal page cannot carry a continuation and later pages cannot cycle backward`() {
        val terminal = SyntheticCatalogReadbackPort(fixture.bytes.take(1))
        terminal.transformPage = { it.copy(nextCursor = CatalogListCursor(CatalogReadbackProtocol.key(1), "catalog-version-1")) }
        reject(terminal, CatalogReadbackFailure.INVALID_LISTING)
        val cycling = SyntheticCatalogReadbackPort(fixture.bytes)
        cycling.transformPage = { page ->
            if (page.requestBinding.cursor != null) page.copy(nextCursor = page.requestBinding.cursor) else page
        }
        reject(cycling, CatalogReadbackFailure.INVALID_LISTING)
        assertEquals(2, cycling.closedBodies)
    }

    @Test
    fun `finite page budget stops before the extra call and cannot turn a verified prefix into success`() {
        val provider = SyntheticCatalogReadbackPort(fixture.bytes)
        reject(provider, CatalogReadbackFailure.LIMIT_EXCEEDED, fixture.policy(maximumPages = 1))
        assertEquals(2, provider.listRequests.size)
        assertEquals(2, provider.closedBodies)
        val exact = SyntheticCatalogReadbackPort(fixture.bytes)
        assertInstanceOf(CatalogReadbackResult.CurrentHeadObserved::class.java, fixture.verify(exact, policy = fixture.policy(pageSize = 3, maximumPages = 1)))
        assertEquals(2, exact.listRequests.size)
    }

    @Test
    fun `generation budget accepts its exact terminal boundary but not a truncated excess`() {
        val one = OfflineCatalogRotationFixture.limits().copy(maximumGenerations = 1)
        val exact = SyntheticCatalogReadbackPort(fixture.bytes.take(1))
        val result = fixture.verify(exact, LocalCatalogSnapshot.NeverAccepted, fixture.policy(one))
        assertInstanceOf(CatalogReadbackResult.BootstrapObserved::class.java, result)
        val excess = SyntheticCatalogReadbackPort(fixture.bytes)
        val failure = assertThrows(CatalogReadbackException::class.java) {
            fixture.verify(excess, LocalCatalogSnapshot.NeverAccepted, fixture.policy(one))
        }
        assertEquals(CatalogReadbackFailure.LIMIT_EXCEEDED, failure.code)
        assertEquals(2, excess.listRequests.size)
    }

    @Test
    fun `listing Size must be positive and bounded as Long before any GET or narrowing allocation`() {
        listOf(0L, -1L, Long.MIN_VALUE).forEach { size ->
            val provider = SyntheticCatalogReadbackPort(fixture.bytes)
            provider.primaryVersions[0] = provider.primaryVersions[0].copy(contentLength = size)
            reject(provider, CatalogReadbackFailure.INVALID_LISTING)
            assertTrue(provider.getRequests.isEmpty())
        }
        val huge = SyntheticCatalogReadbackPort(fixture.bytes)
        huge.primaryVersions[0] = huge.primaryVersions[0].copy(contentLength = Long.MAX_VALUE)
        reject(huge, CatalogReadbackFailure.LIMIT_EXCEEDED)
        assertTrue(huge.getRequests.isEmpty())
    }

    @Test
    fun `genesis keeps the existing smaller envelope cap before open`() {
        val provider = SyntheticCatalogReadbackPort(fixture.bytes)
        provider.primaryVersions[0] = provider.primaryVersions[0].copy(contentLength = 128L * 1024 + 1)
        provider.replicaVersions[0] = provider.replicaVersions[0].copy(contentLength = 128L * 1024 + 1)
        reject(provider, CatalogReadbackFailure.LIMIT_EXCEEDED)
        assertTrue(provider.getRequests.isEmpty())
    }

    @Test
    fun `aggregate encoded-byte budget is checked per physical location before opening the next body`() {
        val total = fixture.bytes.sumOf { it.size.toLong() }
        val exact = fixture.policy(OfflineCatalogRotationFixture.limits().copy(maximumEncodedBytes = total))
        assertInstanceOf(CatalogReadbackResult.CurrentHeadObserved::class.java, fixture.verify(SyntheticCatalogReadbackPort(fixture.bytes), policy = exact))
        val tooSmall = fixture.policy(OfflineCatalogRotationFixture.limits().copy(maximumEncodedBytes = total - 1))
        val provider = SyntheticCatalogReadbackPort(fixture.bytes)
        reject(provider, CatalogReadbackFailure.LIMIT_EXCEEDED, tooSmall)
        assertEquals(4, provider.closedBodies)
        assertEquals(4, provider.getRequests.size)
    }

    private fun reject(provider: SyntheticCatalogReadbackPort, code: CatalogReadbackFailure, policy: CatalogReadbackPolicy = fixture.policy()) {
        val failure = assertThrows(CatalogReadbackException::class.java) { fixture.verify(provider, policy = policy) }
        assertEquals(code, failure.code)
        assertEquals(0, provider.openBodies)
        assertNull(failure.cause)
    }
}
