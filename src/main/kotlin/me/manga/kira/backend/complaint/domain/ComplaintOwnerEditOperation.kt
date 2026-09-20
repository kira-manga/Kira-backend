package me.manga.kira.backend.complaint.domain

import java.util.UUID

/** Fixed owner edit only. It cannot select a creation, deletion or Admin operation. */
internal interface ComplaintOwnerEditPort {
    fun edit(context: ComplaintOwnerOperationContext, bearer: String, input: ComplaintOwnerEditInput): ComplaintOwnerEditReceipt
    fun status(context: ComplaintOwnerOperationContext, bearer: String, query: ComplaintOwnerEditStatusQuery): ComplaintOwnerEditReceipt
}

/** A parsed strong target precondition, not a statement about any current resource. */
internal class ComplaintOwnerEditPrecondition private constructor(val targetId: UUID, val version: Long) {
    val canonical: String get() = "\"complaint-$targetId-v$version\""

    override fun toString(): String = "ComplaintOwnerEditPrecondition(redacted)"

    companion object {
        const val MAX_BYTES = 256

        fun parse(targetId: UUID, header: String?): ComplaintOwnerEditPrecondition {
            ComplaintIdentifiers.resourceId(targetId.toString())
            if (header == null) rejectOwnerOperation(ComplaintOwnerOperationFailure.PRECONDITION_REQUIRED)
            if (header.length > MAX_BYTES || header.any { it.code !in 32..126 && it != '\t' }) {
                rejectOwnerOperation(ComplaintOwnerOperationFailure.PRECONDITION_FAILED)
            }
            val match = Regex("\"complaint-$targetId-v([1-9][0-9]{0,18})\"").matchEntire(header.trim(' ', '\t'))
                ?: rejectOwnerOperation(ComplaintOwnerOperationFailure.PRECONDITION_FAILED)
            val version = match.groupValues[1].toLongOrNull()
                ?: rejectOwnerOperation(ComplaintOwnerOperationFailure.PRECONDITION_FAILED)
            return ComplaintOwnerEditPrecondition(targetId, version)
        }
    }
}

/** Raw request-local prose. Null subject is the explicit body-only shape, never inferred from a row. */
internal class ComplaintOwnerEditInput(
    val targetId: UUID,
    val key: UUID,
    val subject: String?,
    val body: String,
    val precondition: ComplaintOwnerEditPrecondition,
) {
    init {
        ComplaintIdentifiers.resourceId(targetId.toString())
        ComplaintIdentifiers.idempotencyKey(key.toString())
        require(precondition.targetId == targetId)
        require(body.length <= 16384 && (subject == null || subject.length <= 16384))
    }

    override fun toString(): String = "ComplaintOwnerEditInput(redacted)"
}

/** The concrete adapter normalizes this only after actual installation authentication. */
internal class ComplaintOwnerEditRequest private constructor(
    val scope: ComplaintDataScope,
    val targetId: UUID,
    val key: UUID,
    val subject: String?,
    val body: String,
    val precondition: ComplaintOwnerEditPrecondition,
) {
    override fun toString(): String = "ComplaintOwnerEditRequest(redacted)"

    companion object {
        fun normalize(scope: ComplaintDataScope, input: ComplaintOwnerEditInput): ComplaintOwnerEditRequest = ComplaintOwnerEditRequest(
            scope,
            input.targetId,
            input.key,
            input.subject?.let { ComplaintReportTextRules.normalize(it, ComplaintReportField.SUBJECT) },
            ComplaintReportTextRules.editedBody(input.body),
            input.precondition,
        )
    }
}

/** One canonical existing target and an independently copied digest; no request prose in status. */
internal class ComplaintOwnerEditStatusQuery(key: String, targetIds: List<String>, fingerprint: String) {
    val key: UUID = ComplaintIdentifiers.idempotencyKey(key)
    val targetId: UUID = if (targetIds.size == 1) {
        ComplaintIdentifiers.resourceId(targetIds.single())
    } else {
        rejectOwnerOperation(ComplaintOwnerOperationFailure.INVALID_REQUEST)
    }
    private val digest = ComplaintIdentifiers.fingerprint(fingerprint)

    fun fingerprintBytes(): ByteArray = digest.copyOf()

    override fun toString(): String = "ComplaintOwnerEditStatusQuery(redacted)"
}

/** Comparison data only. The private ingress and phase owners grant the actual edit capability. */
internal class ComplaintOwnerEditTuple(val installation: ScopedInstallationId, val key: UUID, val targetId: UUID, fingerprint: ByteArray) {
    private val digest = fingerprint.copyOf()

    init {
        ComplaintIdentifiers.idempotencyKey(key.toString())
        ComplaintIdentifiers.resourceId(targetId.toString())
        require(digest.size == 32)
    }

    fun fingerprintBytes(): ByteArray = digest.copyOf()

    fun matches(other: ComplaintOwnerEditTuple): Boolean = installation == other.installation && key == other.key &&
        targetId == other.targetId && digest.contentEquals(other.digest)

    override fun toString(): String = "ComplaintOwnerEditTuple(redacted)"

    companion object {
        const val OPERATION = "OWNER_EDIT"
    }
}

internal enum class ComplaintOwnerEditRejection(val status: Int) {
    COMPLAINT_NOT_FOUND(404),
    COMPLAINT_INVALID_TRANSITION(409),
    COMPLAINT_NO_CHANGE(409),
    COMPLAINT_DELETION_PENDING(409),
    PRECONDITION_FAILED(412),
}

/** Separate from creation's version-one/201/Location receipt. No content snapshot or arbitrary headers. */
internal sealed class ComplaintOwnerEditReceipt private constructor() {
    class Applied(val id: UUID, val version: Long) : ComplaintOwnerEditReceipt() {
        init {
            ComplaintIdentifiers.resourceId(id.toString())
            require(version > 0)
        }

        val etag: String get() = "\"complaint-$id-v$version\""
    }

    class Rejected(val code: ComplaintOwnerEditRejection) : ComplaintOwnerEditReceipt() {
        val status: Int get() = code.status
        val problemCode: String get() = code.name
    }

    final override fun toString(): String = "ComplaintOwnerEditReceipt(redacted)"
}
