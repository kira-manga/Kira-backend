package me.manga.kira.backend.complaint.domain.catalog

/** Raw observations only. A future authenticated AWS adapter must enforce its own transport/decoder limits. */
internal interface CatalogReadbackPort {
    fun listVersions(request: CatalogListRequest): CatalogListPage
    fun openVersion(request: CatalogGetRequest): CatalogVersionBody
}

internal data class CatalogListCursor(val keyMarker: String, val versionIdMarker: String)

internal data class CatalogListRequest(val location: OfflineCatalogLocationV1, val prefix: String, val cursor: CatalogListCursor?, val maxKeys: Int)

/** contentLength binds S3 ListObjectVersions Size, not an imaginary listing ContentLength field. */
internal data class CatalogListedVersion(val key: String, val versionId: String?, val contentLength: Long)

internal data class CatalogDeleteMarker(val key: String, val versionId: String)

/** Echoed bindings are checked correlation data, not independent evidence of provider/account authority. */
internal data class CatalogListPage(
    val requestBinding: CatalogListRequest,
    val versions: List<CatalogListedVersion>,
    val deleteMarkers: List<CatalogDeleteMarker>,
    val isTruncated: Boolean,
    val nextCursor: CatalogListCursor?,
)

internal data class CatalogGetRequest(val location: OfflineCatalogLocationV1, val key: String, val versionId: String)

internal data class CatalogObjectMetadata(
    val requestBinding: CatalogGetRequest,
    val contentLength: Long,
    val objectLockMode: String?,
    val retainUntilEpochSecond: Long?,
    val replicationStatus: String?,
)

/** Reader owns the destination and closes every opened body. Positive reads must progress, and -1 alone means EOF. */
internal interface CatalogVersionBody {
    fun metadata(): CatalogObjectMetadata
    fun read(destination: ByteArray, offset: Int, length: Int): Int
    fun close()
}
