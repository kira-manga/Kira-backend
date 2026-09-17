package me.manga.kira.backend.complaint.domain

import java.util.UUID

/** A view only: the live ingress owner recognizes its own context, not arbitrary implementations. */
internal interface ComplaintOwnerOperationContext

internal interface ComplaintOwnerOperationPort {
    fun create(context: ComplaintOwnerOperationContext, bearer: String, input: ComplaintOwnerCreateInput): ComplaintOwnerReceipt
    fun status(context: ComplaintOwnerOperationContext, bearer: String, query: ComplaintOwnerStatusQuery): ComplaintOwnerReceipt
}

/** Request-local raw values. Normalization happens only after actual installation authentication. */
internal class ComplaintOwnerCreateInput(
    val id: UUID,
    val key: UUID,
    val type: ComplaintType,
    val subject: String,
    val body: String,
    val metadata: ComplaintReportMetadataInput,
) {
    init {
        ComplaintIdentifiers.clientResourceId(id.toString())
        ComplaintIdentifiers.idempotencyKey(key.toString())
        require(listOfNotNull(subject, body, metadata.appVersion, metadata.osVersion, metadata.manufacturer, metadata.deviceModel).all { it.length <= 16384 })
    }

    override fun toString(): String = "ComplaintOwnerCreateInput(redacted)"
}

/** Status contains no prose. This increment deliberately cannot observe/apply a deferred operation. */
internal class ComplaintOwnerStatusQuery(operation: String, key: String, targetId: String, fingerprint: String) {
    val key: UUID = ComplaintIdentifiers.idempotencyKey(key)
    val targetId: UUID = ComplaintIdentifiers.clientResourceId(targetId)
    private val digest = ComplaintIdentifiers.fingerprint(fingerprint)

    init {
        if (operation != "OWNER_CREATE") rejectOwnerOperation(ComplaintOwnerOperationFailure.INVALID_REQUEST)
    }

    fun fingerprintBytes(): ByteArray = digest.copyOf()

    override fun toString(): String = "ComplaintOwnerStatusQuery(redacted)"
}

/** Immutable comparison tuple, not authentication or a caller-minted admission capability. */
internal class ComplaintOwnerOperationTuple(val installation: ScopedInstallationId, val key: UUID, val targetId: UUID, fingerprint: ByteArray) {
    private val digest = fingerprint.copyOf()

    init {
        require(digest.size == 32)
        ComplaintIdentifiers.idempotencyKey(key.toString())
        ComplaintIdentifiers.clientResourceId(targetId.toString())
    }

    fun fingerprintBytes(): ByteArray = digest.copyOf()

    fun matches(other: ComplaintOwnerOperationTuple): Boolean = installation == other.installation && key == other.key &&
        targetId == other.targetId && digest.contentEquals(other.digest)

    override fun toString(): String = "ComplaintOwnerOperationTuple(OWNER_CREATE,redacted)"
}

/** Only these two business rejections are durable CREATE outcomes; ordinary failures are not receipts. */
internal enum class ComplaintOwnerCreateRejection { COMPLAINT_CAPACITY_REACHED, COMPLAINT_RESOURCE_ID_REUSED }

internal sealed class ComplaintOwnerReceipt private constructor() {
    class Applied(val id: UUID, val version: Long) : ComplaintOwnerReceipt() {
        init {
            ComplaintIdentifiers.clientResourceId(id.toString())
            require(version == 1L) // OWNER_CREATE stores the original creation version, never the current row version.
        }

        val location: String get() = "/api/v1/complaints/$id"
        val etag: String get() = "\"complaint-$id-v$version\""
    }

    class Rejected(val code: ComplaintOwnerCreateRejection) : ComplaintOwnerReceipt()

    final override fun toString(): String = "ComplaintOwnerReceipt(redacted)"
}

internal enum class ComplaintOwnerOperationFailure(val status: Int, val code: String, val title: String) {
    INVALID_REQUEST(400, "VALIDATION_FAILED", "Bad Request"),
    UNAUTHORIZED(401, "UNAUTHORIZED", "Unauthorized"),
    NOT_FOUND(404, "NOT_FOUND", "Not Found"),
    OPERATION_NOT_FOUND(404, "OPERATION_NOT_FOUND", "Not Found"),
    KEY_REUSED(409, "IDEMPOTENCY_KEY_REUSED", "Conflict"),
    IN_PROGRESS(409, "IDEMPOTENCY_IN_PROGRESS", "Conflict"),
    PAYLOAD_TOO_LARGE(413, "PAYLOAD_TOO_LARGE", "Payload Too Large"),
    UNSUPPORTED_MEDIA(415, "UNSUPPORTED_MEDIA_TYPE", "Unsupported Media Type"),
    RATE_LIMITED(429, "RATE_LIMITED", "Too Many Requests"),
    UNAVAILABLE(503, "SERVICE_UNAVAILABLE", "Service Unavailable"),
    INTERNAL(500, "INTERNAL_ERROR", "Internal Server Error"),
}

/** No request, SQL, fingerprint, token, cause or uncontrolled message crosses the exception boundary. */
internal class ComplaintOwnerOperationRejected(val failure: ComplaintOwnerOperationFailure) :
    RuntimeException("Complaint operation refused.", null, false, false)

internal fun rejectOwnerOperation(failure: ComplaintOwnerOperationFailure): Nothing = throw ComplaintOwnerOperationRejected(failure)
