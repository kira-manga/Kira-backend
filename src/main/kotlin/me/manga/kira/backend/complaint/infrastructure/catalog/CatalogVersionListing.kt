package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.complaint.domain.catalog.CatalogListCursor
import me.manga.kira.backend.complaint.domain.catalog.CatalogListPage
import me.manga.kira.backend.complaint.domain.catalog.CatalogListRequest
import me.manga.kira.backend.complaint.domain.catalog.CatalogListedVersion
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackException
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPolicy
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPort
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackProtocol
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogLocationV1
import me.manga.kira.backend.complaint.domain.catalog.requireCatalogReadback

/** Owns a single forward-only complete listing, one bounded page and one continuation tuple. */
internal class CatalogVersionListing(
    private val provider: CatalogReadbackPort,
    private val location: OfflineCatalogLocationV1,
    private val policy: CatalogReadbackPolicy,
) {
    private var page = emptyList<CatalogListedVersion>()
    private var position = 0
    private var cursor: CatalogListCursor? = null
    private var terminal = false
    private var pages = 0
    private var listed = 0

    fun nextOrNull(): CatalogListedVersion? {
        if (position == page.size && !terminal) fetchPage()
        if (position == page.size) return null
        return page[position++]
    }

    private fun fetchPage() {
        requireCatalogReadback(pages < policy.maximumPagesPerLocation, CatalogReadbackFailure.LIMIT_EXCEEDED)
        val remaining = policy.chain.limits.maximumGenerations - listed
        requireCatalogReadback(remaining > 0, CatalogReadbackFailure.LIMIT_EXCEEDED)
        val request = CatalogListRequest(location, CatalogReadbackProtocol.PREFIX, cursor, minOf(policy.pageSize, remaining))
        pages++
        val observed = catalogProviderCall { provider.listVersions(request) }
        requireCatalogReadback(observed.requestBinding == request, CatalogReadbackFailure.INVALID_LISTING)
        val snapshot = snapshotVersions(observed, request.maxKeys)
        snapshot.forEachIndexed { index, version -> validateVersion(version, listed.toLong() + index + 1) }
        validateContinuation(observed, snapshot)
        listed += snapshot.size
        page = snapshot
        position = 0
        terminal = !observed.isTruncated
        cursor = observed.nextCursor
    }

    private fun snapshotVersions(observed: CatalogListPage, maximum: Int): List<CatalogListedVersion> {
        // List implementations are supplied by the port: bound counts before indexed access or allocation.
        val versionCount = catalogProviderCall { observed.versions.size }
        val markerCount = catalogProviderCall { observed.deleteMarkers.size }
        requireCatalogReadback(versionCount >= 0 && markerCount >= 0, CatalogReadbackFailure.INVALID_LISTING)
        requireCatalogReadback(versionCount.toLong() + markerCount <= maximum, CatalogReadbackFailure.LIMIT_EXCEEDED)
        requireCatalogReadback(markerCount == 0, CatalogReadbackFailure.INVALID_LISTING)
        return List(versionCount) { index -> catalogProviderCall { observed.versions[index] } }
    }

    private fun validateVersion(version: CatalogListedVersion, generation: Long) {
        requireCatalogReadback(version.key == CatalogReadbackProtocol.key(generation), CatalogReadbackFailure.INVALID_LISTING)
        requireCatalogReadback(CatalogReadbackProtocol.validVersion(version.versionId), CatalogReadbackFailure.INVALID_LISTING)
        requireCatalogReadback(version.contentLength > 0, CatalogReadbackFailure.INVALID_LISTING)
        requireCatalogReadback(version.contentLength <= policy.chain.limits.maximumEnvelopeBytes.toLong(), CatalogReadbackFailure.LIMIT_EXCEEDED)
    }

    private fun validateContinuation(observed: CatalogListPage, versions: List<CatalogListedVersion>) {
        val next = observed.nextCursor
        if (!observed.isTruncated) {
            requireCatalogReadback(next == null, CatalogReadbackFailure.INVALID_LISTING)
            return
        }
        requireCatalogReadback(versions.isNotEmpty() && next != null, CatalogReadbackFailure.INVALID_LISTING)
        val last = versions.last()
        val continuation = next ?: throw CatalogReadbackException(CatalogReadbackFailure.INVALID_LISTING)
        requireCatalogReadback(
            continuation.keyMarker.length == last.key.length && CatalogReadbackProtocol.validVersion(continuation.versionIdMarker),
            CatalogReadbackFailure.INVALID_LISTING,
        )
        requireCatalogReadback(
            continuation.keyMarker == last.key && continuation.versionIdMarker == last.versionId,
            CatalogReadbackFailure.INVALID_LISTING,
        )
    }
}
