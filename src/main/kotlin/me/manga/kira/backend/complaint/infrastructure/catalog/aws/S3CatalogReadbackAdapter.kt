package me.manga.kira.backend.complaint.infrastructure.catalog.aws

import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.CatalogDeleteMarker
import me.manga.kira.backend.complaint.domain.catalog.CatalogGetRequest
import me.manga.kira.backend.complaint.domain.catalog.CatalogListCursor
import me.manga.kira.backend.complaint.domain.catalog.CatalogListPage
import me.manga.kira.backend.complaint.domain.catalog.CatalogListRequest
import me.manga.kira.backend.complaint.domain.catalog.CatalogListedVersion
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackException
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPort
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackProtocol
import me.manga.kira.backend.complaint.domain.catalog.CatalogVersionBody
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainProtocol
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogLocationV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundlePolicy
import me.manga.kira.backend.complaint.domain.catalog.requireCatalogReadback
import me.manga.kira.backend.complaint.infrastructure.catalog.OfflineTrustBundleVerifier
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse
import java.util.concurrent.atomic.AtomicBoolean

/** Dormant read-only adapter. Verified trust pins routes; neither construction nor SDK responses grant catalog authority. */
internal class S3CatalogReadbackAdapter private constructor(private val primary: S3CatalogReadbackClient, private val replica: S3CatalogReadbackClient) :
    CatalogReadbackPort,
    AutoCloseable {
    private val closed = AtomicBoolean()

    override fun listVersions(request: CatalogListRequest): CatalogListPage {
        requireCatalogReadback(request.prefix == CatalogReadbackProtocol.PREFIX, CatalogReadbackFailure.INVALID_LISTING)
        requireCatalogReadback(request.maxKeys in 1..CatalogReadbackProtocol.MAX_PAGE_ENTRIES, CatalogReadbackFailure.INVALID_LISTING)
        request.cursor?.let(::validateCursor)
        val observed = client(request.location).list(request)
        validateListBinding(observed, request)
        val versions = observed.versions()
        val markers = observed.deleteMarkers()
        requireCatalogReadback(versions.size.toLong() + markers.size <= request.maxKeys, CatalogReadbackFailure.LIMIT_EXCEEDED)
        val next = observedCursor(observed.nextKeyMarker(), observed.nextVersionIdMarker())
        val truncated = observed.isTruncated() ?: throw CatalogReadbackException(CatalogReadbackFailure.INVALID_LISTING)
        requireCatalogReadback(truncated == (next != null), CatalogReadbackFailure.INVALID_LISTING)
        return CatalogListPage(
            CatalogListRequest(request.location, observed.prefix(), observedCursor(observed.keyMarker(), observed.versionIdMarker()), observed.maxKeys()),
            versions.map {
                val key = requireKey(it.key())
                requireCatalogReadback(CatalogReadbackProtocol.validVersion(it.versionId()), CatalogReadbackFailure.INVALID_LISTING)
                val size = it.size() ?: throw CatalogReadbackException(CatalogReadbackFailure.INVALID_LISTING)
                requireCatalogReadback(size in 1..OfflineCatalogChainProtocol.MAX_ENVELOPE_BYTES.toLong(), CatalogReadbackFailure.INVALID_LISTING)
                CatalogListedVersion(key, it.versionId(), size)
            },
            markers.map {
                val key = requireKey(it.key())
                val version = it.versionId()
                requireCatalogReadback(CatalogReadbackProtocol.validVersion(version), CatalogReadbackFailure.INVALID_LISTING)
                CatalogDeleteMarker(key, version)
            },
            truncated,
            next,
        )
    }

    override fun openVersion(request: CatalogGetRequest): CatalogVersionBody {
        requireKey(request.key)
        requireCatalogReadback(CatalogReadbackProtocol.validVersion(request.versionId), CatalogReadbackFailure.INVALID_READBACK)
        return client(request.location).open(request)
    }

    private fun client(location: OfflineCatalogLocationV1): S3CatalogReadbackClient {
        requireConnectionFree()
        requireCatalogReadback(!closed.get(), CatalogReadbackFailure.INVALID_READBACK)
        return when (location) {
            primary.location -> primary
            replica.location -> replica
            else -> throw CatalogReadbackException(CatalogReadbackFailure.INVALID_POLICY)
        }
    }

    private fun validateListBinding(observed: ListObjectVersionsResponse, request: CatalogListRequest) {
        requireCatalogReadback(observed.name() == request.location.bucket && observed.prefix() == request.prefix, CatalogReadbackFailure.INVALID_LISTING)
        requireCatalogReadback(observed.maxKeys() == request.maxKeys, CatalogReadbackFailure.INVALID_LISTING)
        requireCatalogReadback(observedCursor(observed.keyMarker(), observed.versionIdMarker()) == request.cursor, CatalogReadbackFailure.INVALID_LISTING)
        requireCatalogReadback(observed.delimiter().isNullOrEmpty() && observed.commonPrefixes().isEmpty(), CatalogReadbackFailure.INVALID_LISTING)
        // SDK2.54.19 decodes URL-encoded key/prefix/marker fields itself; version IDs are never URL-decoded a second time.
        requireCatalogReadback(observed.encodingTypeAsString() == "url", CatalogReadbackFailure.INVALID_LISTING)
    }

    private fun observedCursor(key: String?, version: String?): CatalogListCursor? {
        if (key.isNullOrEmpty() && version.isNullOrEmpty()) return null // S3 represents a null start with empty XML markers.
        requireCatalogReadback(key != null && version != null, CatalogReadbackFailure.INVALID_LISTING)
        return CatalogListCursor(requireKey(key), requireNotNull(version)).also(::validateCursor)
    }

    private fun validateCursor(cursor: CatalogListCursor) {
        requireKey(cursor.keyMarker)
        requireCatalogReadback(CatalogReadbackProtocol.validVersion(cursor.versionIdMarker), CatalogReadbackFailure.INVALID_LISTING)
    }

    override fun close() {
        closed.set(true)
        withS3Cleanup(primary::close, replica::close)
    }

    override fun toString(): String = "S3CatalogReadbackAdapter(read-only,redacted,no-admission-authority)"

    companion object {
        fun open(
            currentBundleBytes: ByteArray,
            trustPolicy: OfflineTrustBundlePolicy,
            primaryCredentials: AwsSessionCredentials,
            replicaCredentials: AwsSessionCredentials,
            limits: S3CatalogReadbackLimits = S3CatalogReadbackLimits(),
        ): S3CatalogReadbackAdapter = create(
            currentBundleBytes,
            trustPolicy,
            primaryCredentials,
            replicaCredentials,
            limits,
            { catalogUrlConnectionClient(limits) },
            System::nanoTime,
        )

        /** Test-only transport substitution still authenticates the same raw trust and exercises the real SDK, not a fake S3Client. */
        fun withHttpFixture(
            currentBundleBytes: ByteArray,
            trustPolicy: OfflineTrustBundlePolicy,
            primaryCredentials: AwsSessionCredentials,
            replicaCredentials: AwsSessionCredentials,
            limits: S3CatalogReadbackLimits,
            httpFactory: () -> SdkHttpClient,
            nanoTime: () -> Long = System::nanoTime,
        ): S3CatalogReadbackAdapter = create(currentBundleBytes, trustPolicy, primaryCredentials, replicaCredentials, limits, httpFactory, nanoTime)

        private fun create(
            bytes: ByteArray,
            policy: OfflineTrustBundlePolicy,
            primaryCredentials: AwsSessionCredentials,
            replicaCredentials: AwsSessionCredentials,
            limits: S3CatalogReadbackLimits,
            httpFactory: () -> SdkHttpClient,
            nanoTime: () -> Long,
        ): S3CatalogReadbackAdapter {
            requireConnectionFree()
            val locations = OfflineTrustBundleVerifier.verify(bytes, policy).body.catalogLocations
            val primary = S3CatalogReadbackClient.create(locations[0], primaryCredentials, limits, httpFactory, nanoTime)
            val replica = runCatching { S3CatalogReadbackClient.create(locations[1], replicaCredentials, limits, httpFactory, nanoTime) }
            replica.exceptionOrNull()?.let { failure -> return withS3Cleanup({ throw failure }, primary::close) }
            return S3CatalogReadbackAdapter(primary, replica.getOrThrow())
        }
    }
}

internal fun requireKey(value: String?): String {
    val key = value ?: throw CatalogReadbackException(CatalogReadbackFailure.INVALID_LISTING)
    val prefix = CatalogReadbackProtocol.PREFIX
    requireCatalogReadback(key.length == prefix.length + CatalogReadbackProtocol.GENERATION_DIGITS + 5, CatalogReadbackFailure.INVALID_LISTING)
    requireCatalogReadback(key.startsWith(prefix) && key.endsWith(".json"), CatalogReadbackFailure.INVALID_LISTING)
    val digits = key.substring(prefix.length, prefix.length + CatalogReadbackProtocol.GENERATION_DIGITS)
    requireCatalogReadback(digits.all { it in '0'..'9' }, CatalogReadbackFailure.INVALID_LISTING)
    val generation = digits.toLongOrNull() ?: throw CatalogReadbackException(CatalogReadbackFailure.INVALID_LISTING)
    requireCatalogReadback(generation in 1..OfflineCatalogChainProtocol.MAX_GENERATIONS, CatalogReadbackFailure.INVALID_LISTING)
    return key
}
