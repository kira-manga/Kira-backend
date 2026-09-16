package me.manga.kira.backend.complaint.infrastructure.catalog.aws

import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.CatalogGetRequest
import me.manga.kira.backend.complaint.domain.catalog.CatalogLogicalInventoryProtocol
import me.manga.kira.backend.complaint.domain.catalog.CatalogObjectMetadata
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackException
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackProtocol
import me.manga.kira.backend.complaint.domain.catalog.CatalogVersionBody
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainProtocol
import me.manga.kira.backend.complaint.domain.catalog.requireCatalogReadback
import software.amazon.awssdk.core.ResponseInputStream
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import java.util.concurrent.atomic.AtomicBoolean

/** Owns the SDK wrapper until explicit close; its underlying transport also checks exact length, EOF and elapsed budget. */
internal class S3CatalogVersionBody(request: CatalogGetRequest, private val stream: ResponseInputStream<GetObjectResponse>, private val release: () -> Unit) :
    CatalogVersionBody {
    private val closed = AtomicBoolean()
    private val observed = metadata(request, stream.response())

    override fun metadata(): CatalogObjectMetadata {
        requireCatalogReadback(!closed.get(), CatalogReadbackFailure.INVALID_READBACK)
        return observed
    }

    // A read failure cannot orphan the returned SDK stream, including cancellation or a fatal Error.
    @Suppress("TooGenericExceptionCaught")
    override fun read(destination: ByteArray, offset: Int, length: Int): Int {
        try {
            requireConnectionFree()
            requireCatalogReadback(!closed.get(), CatalogReadbackFailure.INVALID_READBACK)
            requireCatalogReadback(offset >= 0 && length > 0 && offset <= destination.size - length, CatalogReadbackFailure.INVALID_READBACK)
            return sdkReadbackCall { stream.read(destination, offset, length) }
        } catch (failure: Throwable) {
            return withS3Cleanup({ throw failure }, ::close)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }

    override fun toString(): String = "S3CatalogVersionBody(exact-version,redacted)"

    private fun metadata(request: CatalogGetRequest, response: GetObjectResponse): CatalogObjectMetadata {
        requireCatalogReadback(response.sdkHttpResponse().statusCode() == 200, CatalogReadbackFailure.INVALID_READBACK)
        val version = response.versionId()
        requireCatalogReadback(CatalogReadbackProtocol.validVersion(version) && version == request.versionId, CatalogReadbackFailure.INVALID_READBACK)
        val length = response.contentLength() ?: throw CatalogReadbackException(CatalogReadbackFailure.INVALID_READBACK)
        requireCatalogReadback(length in 1..OfflineCatalogChainProtocol.MAX_ENVELOPE_BYTES.toLong(), CatalogReadbackFailure.INVALID_READBACK)
        requireCatalogReadback(response.deleteMarker() != true && response.contentRange() == null, CatalogReadbackFailure.INVALID_READBACK)
        requireCatalogReadback(
            response.contentEncoding() == null || response.contentEncoding().equals("identity", ignoreCase = true),
            CatalogReadbackFailure.INVALID_READBACK,
        )
        val mode = response.objectLockModeAsString()
        val retention = response.objectLockRetainUntilDate()
        requireCatalogReadback(mode == "COMPLIANCE" && retention != null, CatalogReadbackFailure.RETENTION_MISMATCH)
        val exactRetention = requireNotNull(retention)
        requireCatalogReadback(
            exactRetention.nano == 0 && exactRetention.epochSecond in 0..CatalogLogicalInventoryProtocol.LAST_EPOCH_SECOND,
            CatalogReadbackFailure.RETENTION_MISMATCH,
        )
        val replication = response.replicationStatusAsString()
        val validReplication = if (request.location.role == "REPLICA") replication == "REPLICA" else replication in listOf("PENDING", "COMPLETED")
        requireCatalogReadback(validReplication, CatalogReadbackFailure.REPLICATION_MISMATCH)
        // Actual SDK-observed VersionId, never a requested version echoed to conceal a missing/different response header.
        return CatalogObjectMetadata(request.copy(versionId = version), length, mode, exactRetention.epochSecond, replication)
    }
}
