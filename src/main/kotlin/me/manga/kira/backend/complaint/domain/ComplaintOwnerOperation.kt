package me.manga.kira.backend.complaint.domain

import java.util.UUID

/** A view only: the live ingress owner recognizes its own context, not arbitrary implementations. */
internal interface ComplaintOwnerOperationContext

internal interface ComplaintOwnerOperationPort {
    fun create(context: ComplaintOwnerOperationContext, bearer: String, input: ComplaintOwnerCreateInput): ComplaintOwnerReceipt
    fun reply(context: ComplaintOwnerOperationContext, bearer: String, input: ComplaintOwnerReplyInput): ComplaintOwnerReceipt =
        rejectOwnerOperation(ComplaintOwnerOperationFailure.UNAVAILABLE)

    fun status(context: ComplaintOwnerOperationContext, bearer: String, query: ComplaintOwnerStatusQuery): ComplaintOwnerReceipt
}

/** Only these fixed content-producing operations are implemented; never a generic mutation selector. */
internal enum class ComplaintOwnerCreationOperation { OWNER_CREATE, OWNER_REPLY }

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

/** Reply identity is ordered parent then new client UUID. Parent notice UUIDs need not be version 4. */
internal class ComplaintOwnerReplyInput(val parentId: UUID, val id: UUID, val key: UUID, val body: String, val metadata: ComplaintReportMetadataInput) {
    init {
        ComplaintIdentifiers.resourceId(parentId.toString())
        ComplaintIdentifiers.clientResourceId(id.toString())
        ComplaintIdentifiers.idempotencyKey(key.toString())
        require(parentId != id)
        require(
            listOfNotNull(body, metadata.appVersion, metadata.osVersion, metadata.manufacturer, metadata.deviceModel)
                .all { it.length <= 16384 },
        )
    }

    override fun toString(): String = "ComplaintOwnerReplyInput(redacted)"
}

/** Status contains no prose. It observes only exact completed create/reply receipts, never deferred work. */
internal class ComplaintOwnerStatusQuery(operation: String, key: String, targetIds: List<String>, fingerprint: String) {
    val operation: ComplaintOwnerCreationOperation = when (operation) {
        "OWNER_CREATE" -> ComplaintOwnerCreationOperation.OWNER_CREATE
        "OWNER_REPLY" -> ComplaintOwnerCreationOperation.OWNER_REPLY
        else -> rejectOwnerOperation(ComplaintOwnerOperationFailure.INVALID_REQUEST)
    }
    val key: UUID = ComplaintIdentifiers.idempotencyKey(key)
    private val targets = checkedOwnerTargets(this.operation, targetIds)
    val targetId: UUID get() = targets.last()
    private val digest = ComplaintIdentifiers.fingerprint(fingerprint)

    constructor(operation: String, key: String, targetId: String, fingerprint: String) : this(operation, key, listOf(targetId), fingerprint)

    fun targetIds(): List<UUID> = targets.toList()
    fun fingerprintBytes(): ByteArray = digest.copyOf()

    override fun toString(): String = "ComplaintOwnerStatusQuery(redacted)"
}

/** Immutable comparison tuple, not authentication or a caller-minted admission capability. */
internal class ComplaintOwnerOperationTuple(
    val installation: ScopedInstallationId,
    val key: UUID,
    val operation: ComplaintOwnerCreationOperation,
    targetIds: List<UUID>,
    fingerprint: ByteArray,
) {
    private val targets = checkedOwnerTargets(operation, targetIds.also { require(it.size in 1..2) }.map(UUID::toString))
    val targetId: UUID get() = targets.last()
    val parentId: UUID? get() = if (operation === ComplaintOwnerCreationOperation.OWNER_REPLY) targets.first() else null
    private val digest = fingerprint.copyOf()

    constructor(installation: ScopedInstallationId, key: UUID, targetId: UUID, fingerprint: ByteArray) :
        this(installation, key, ComplaintOwnerCreationOperation.OWNER_CREATE, listOf(targetId), fingerprint)

    init {
        require(digest.size == 32)
        ComplaintIdentifiers.idempotencyKey(key.toString())
    }

    fun targetIds(): List<UUID> = targets.toList()
    fun fingerprintBytes(): ByteArray = digest.copyOf()

    fun matches(other: ComplaintOwnerOperationTuple): Boolean = installation == other.installation && key == other.key &&
        operation === other.operation && targets == other.targets && digest.contentEquals(other.digest)

    override fun toString(): String = "ComplaintOwnerOperationTuple(redacted)"
}

private fun checkedOwnerTargets(operation: ComplaintOwnerCreationOperation, ids: List<String>): List<UUID> {
    val expected = if (operation === ComplaintOwnerCreationOperation.OWNER_REPLY) 2 else 1
    if (ids.size != expected) {
        rejectOwnerOperation(ComplaintOwnerOperationFailure.INVALID_REQUEST)
    }
    val targets = ids.mapIndexed { index, id ->
        if (operation === ComplaintOwnerCreationOperation.OWNER_REPLY && index == 0) {
            ComplaintIdentifiers.resourceId(id)
        } else {
            ComplaintIdentifiers.clientResourceId(id)
        }
    }
    if (targets.distinct().size != targets.size) rejectOwnerOperation(ComplaintOwnerOperationFailure.INVALID_REQUEST)
    return targets
}

/** Only these two business rejections are durable CREATE outcomes; ordinary failures are not receipts. */
internal enum class ComplaintOwnerCreateRejection { COMPLAINT_CAPACITY_REACHED, COMPLAINT_RESOURCE_ID_REUSED }

/** Additional fixed reply outcomes; hidden/missing parents have the same no-content404. */
internal enum class ComplaintOwnerReplyRejection(val status: Int) { COMPLAINT_PARENT_NOT_FOUND(404), COMPLAINT_DELETION_PENDING(409) }

internal sealed class ComplaintOwnerReceipt private constructor() {
    class Applied(val id: UUID, val version: Long) : ComplaintOwnerReceipt() {
        init {
            ComplaintIdentifiers.clientResourceId(id.toString())
            require(version == 1L) // CREATE/REPLY store the original creation version, never the current row version.
        }

        val location: String get() = "/api/v1/complaints/$id"
        val etag: String get() = "\"complaint-$id-v$version\""
    }

    class Rejected private constructor(val problemCode: String, val status: Int) : ComplaintOwnerReceipt() {
        constructor(code: ComplaintOwnerCreateRejection) : this(code.name, 409)
        constructor(code: ComplaintOwnerReplyRejection) : this(code.name, code.status)
    }

    final override fun toString(): String = "ComplaintOwnerReceipt(redacted)"
}

internal enum class ComplaintOwnerOperationFailure(val status: Int, val code: String, val title: String) {
    INVALID_REQUEST(400, "VALIDATION_FAILED", "Bad Request"),
    UNAUTHORIZED(401, "UNAUTHORIZED", "Unauthorized"),
    NOT_FOUND(404, "NOT_FOUND", "Not Found"),
    OPERATION_NOT_FOUND(404, "OPERATION_NOT_FOUND", "Not Found"),
    KEY_REUSED(409, "IDEMPOTENCY_KEY_REUSED", "Conflict"),
    IN_PROGRESS(409, "IDEMPOTENCY_IN_PROGRESS", "Conflict"),
    PRECONDITION_FAILED(412, "PRECONDITION_FAILED", "Precondition Failed"),
    PAYLOAD_TOO_LARGE(413, "PAYLOAD_TOO_LARGE", "Payload Too Large"),
    UNSUPPORTED_MEDIA(415, "UNSUPPORTED_MEDIA_TYPE", "Unsupported Media Type"),
    PRECONDITION_REQUIRED(428, "PRECONDITION_REQUIRED", "Precondition Required"),
    RATE_LIMITED(429, "RATE_LIMITED", "Too Many Requests"),
    UNAVAILABLE(503, "SERVICE_UNAVAILABLE", "Service Unavailable"),
    INTERNAL(500, "INTERNAL_ERROR", "Internal Server Error"),
}

/** No request, SQL, fingerprint, token, cause or uncontrolled message crosses the exception boundary. */
internal class ComplaintOwnerOperationRejected(val failure: ComplaintOwnerOperationFailure) :
    RuntimeException("Complaint operation refused.", null, false, false)

internal fun rejectOwnerOperation(failure: ComplaintOwnerOperationFailure): Nothing = throw ComplaintOwnerOperationRejected(failure)
