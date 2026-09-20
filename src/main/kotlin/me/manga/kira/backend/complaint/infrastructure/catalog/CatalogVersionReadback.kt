package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.complaint.domain.catalog.CatalogGetRequest
import me.manga.kira.backend.complaint.domain.catalog.CatalogListedVersion
import me.manga.kira.backend.complaint.domain.catalog.CatalogLogicalInventoryProtocol
import me.manga.kira.backend.complaint.domain.catalog.CatalogObjectMetadata
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackException
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPolicy
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPort
import me.manga.kira.backend.complaint.domain.catalog.CatalogVersionBody
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogLocationV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleProtocol
import me.manga.kira.backend.complaint.domain.catalog.requireCatalogReadback

internal data class ReadCatalogVersion(val metadata: CatalogObjectMetadata, val bytes: ByteArray)

/** Accounts one physical location's encoded bytes before open/allocation, and never lets an open body escape. */
internal class CatalogVersionReadback(
    private val provider: CatalogReadbackPort,
    private val location: OfflineCatalogLocationV1,
    private val policy: CatalogReadbackPolicy,
) {
    var encodedBytes = 0L
        private set

    fun read(version: CatalogListedVersion, generation: Long, allowPending: Boolean): ReadCatalogVersion {
        val maximum = if (generation == 1L) {
            minOf(policy.chain.limits.maximumEnvelopeBytes, OfflineTrustBundleProtocol.MAX_ENVELOPE_BYTES)
        } else {
            policy.chain.limits.maximumEnvelopeBytes
        }
        requireCatalogReadback(version.contentLength in 1..maximum.toLong(), CatalogReadbackFailure.LIMIT_EXCEEDED)
        requireCatalogReadback(version.contentLength <= policy.chain.limits.maximumEncodedBytes - encodedBytes, CatalogReadbackFailure.LIMIT_EXCEEDED)
        encodedBytes += version.contentLength
        val versionId = version.versionId ?: throw CatalogReadbackException(CatalogReadbackFailure.INVALID_LISTING)
        val request = CatalogGetRequest(location, version.key, versionId)
        val body = catalogProviderCall { provider.openVersion(request) }
        val readback = withCatalogBody(body) {
            val metadata = catalogProviderCall { body.metadata() }
            validateMetadata(metadata, request, version.contentLength, allowPending)
            ReadCatalogVersion(metadata, readExact(body, version.contentLength.toInt()))
        }
        // The port has seen the destination array. Detach after close, before another provider call or yield.
        return readback.copy(bytes = readback.bytes.copyOf())
    }

    private fun validateMetadata(metadata: CatalogObjectMetadata, request: CatalogGetRequest, length: Long, allowPending: Boolean) {
        requireCatalogReadback(metadata.requestBinding == request && metadata.contentLength == length, CatalogReadbackFailure.INVALID_READBACK)
        val retainUntil = metadata.retainUntilEpochSecond
        requireCatalogReadback(metadata.objectLockMode == "COMPLIANCE" && retainUntil != null, CatalogReadbackFailure.RETENTION_MISMATCH)
        requireCatalogReadback(
            retainUntil != null && retainUntil in policy.requiredRetainUntilEpochSecond..CatalogLogicalInventoryProtocol.LAST_EPOCH_SECOND,
            CatalogReadbackFailure.RETENTION_MISMATCH,
        )
        val allowed = when {
            location.role == "REPLICA" -> metadata.replicationStatus == "REPLICA"
            allowPending -> metadata.replicationStatus == "PENDING" || metadata.replicationStatus == "COMPLETED"
            else -> metadata.replicationStatus == "COMPLETED"
        }
        requireCatalogReadback(allowed, CatalogReadbackFailure.REPLICATION_MISMATCH)
    }

    private fun readExact(body: CatalogVersionBody, size: Int): ByteArray {
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < bytes.size) {
            val count = catalogProviderCall { body.read(bytes, offset, bytes.size - offset) }
            requireCatalogReadback(count in 1..(bytes.size - offset), CatalogReadbackFailure.INVALID_READBACK)
            offset += count
        }
        val eof = catalogProviderCall { body.read(ByteArray(1), 0, 1) }
        requireCatalogReadback(eof == -1, CatalogReadbackFailure.INVALID_READBACK)
        return bytes
    }
}
