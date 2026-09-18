package me.manga.kira.backend.complaint.domain

import java.util.UUID

/** Fixed one-target owner deletion. None of these comparison values grants publication authority. */
internal interface ComplaintOwnerDeletePort {
    fun delete(context: ComplaintOwnerOperationContext, bearer: String, input: ComplaintOwnerDeleteInput): ComplaintOwnerDeleteReceipt
    fun status(context: ComplaintOwnerOperationContext, bearer: String, query: ComplaintOwnerDeleteStatusQuery): ComplaintOwnerDeleteReceipt
}

internal class ComplaintOwnerDeletePrecondition private constructor(val targetId: UUID, val version: Long) {
    val canonical: String get() = "\"complaint-$targetId-v$version\""

    override fun toString(): String = "ComplaintOwnerDeletePrecondition(redacted)"

    companion object {
        const val MAX_BYTES = 256

        fun parse(targetId: UUID, header: String?): ComplaintOwnerDeletePrecondition {
            ComplaintIdentifiers.resourceId(targetId.toString())
            if (header == null) rejectOwnerOperation(ComplaintOwnerOperationFailure.PRECONDITION_REQUIRED)
            if (header.length > MAX_BYTES || header.any { it.code !in 32..126 && it != '\t' }) {
                rejectOwnerOperation(ComplaintOwnerOperationFailure.PRECONDITION_FAILED)
            }
            val match = Regex("\"complaint-$targetId-v([1-9][0-9]{0,18})\"").matchEntire(header.trim(' ', '\t'))
                ?: rejectOwnerOperation(ComplaintOwnerOperationFailure.PRECONDITION_FAILED)
            val version = match.groupValues[1].toLongOrNull()
                ?: rejectOwnerOperation(ComplaintOwnerOperationFailure.PRECONDITION_FAILED)
            return ComplaintOwnerDeletePrecondition(targetId, version)
        }
    }
}

internal class ComplaintOwnerDeleteInput(val targetId: UUID, val key: UUID, val precondition: ComplaintOwnerDeletePrecondition) {
    init {
        ComplaintIdentifiers.resourceId(targetId.toString())
        ComplaintIdentifiers.idempotencyKey(key.toString())
        require(precondition.targetId == targetId)
    }

    override fun toString(): String = "ComplaintOwnerDeleteInput(redacted)"
}

internal class ComplaintOwnerDeleteRequest private constructor(
    val scope: ComplaintDataScope,
    val targetId: UUID,
    val key: UUID,
    val precondition: ComplaintOwnerDeletePrecondition,
) {
    override fun toString(): String = "ComplaintOwnerDeleteRequest(redacted)"

    companion object {
        fun normalize(scope: ComplaintDataScope, input: ComplaintOwnerDeleteInput): ComplaintOwnerDeleteRequest {
            require(scope.testOnly) { "Owner deletion requires TEST scope." }
            return ComplaintOwnerDeleteRequest(scope, input.targetId, input.key, input.precondition)
        }
    }
}

internal class ComplaintOwnerDeleteStatusQuery(key: String, targetIds: List<String>, fingerprint: String) {
    val key: UUID = ComplaintIdentifiers.idempotencyKey(key)
    val targetId: UUID = if (targetIds.size == 1) {
        ComplaintIdentifiers.resourceId(targetIds.single())
    } else {
        rejectOwnerOperation(ComplaintOwnerOperationFailure.INVALID_REQUEST)
    }
    private val digest = ComplaintIdentifiers.fingerprint(fingerprint)

    fun fingerprintBytes(): ByteArray = digest.copyOf()

    override fun toString(): String = "ComplaintOwnerDeleteStatusQuery(redacted)"
}

internal class ComplaintOwnerDeleteTuple(val installation: ScopedInstallationId, val key: UUID, val targetId: UUID, fingerprint: ByteArray) {
    private val digest = fingerprint.copyOf()

    init {
        require(installation.scope.testOnly)
        ComplaintIdentifiers.idempotencyKey(key.toString())
        ComplaintIdentifiers.resourceId(targetId.toString())
        require(digest.size == 32)
    }

    fun fingerprintBytes(): ByteArray = digest.copyOf()

    fun matches(other: ComplaintOwnerDeleteTuple): Boolean = installation == other.installation && key == other.key &&
        targetId == other.targetId && digest.contentEquals(other.digest)

    override fun toString(): String = "ComplaintOwnerDeleteTuple(redacted)"

    companion object {
        const val OPERATION = "OWNER_DELETE"
    }
}

internal enum class ComplaintOwnerDeleteRejection(val status: Int) {
    COMPLAINT_NOT_FOUND(404),
    COMPLAINT_DELETION_PENDING(409),
    PRECONDITION_FAILED(412),
}

/** Historical response value, never verified publication or apply custody. */
internal sealed class ComplaintOwnerDeleteReceipt private constructor() {
    data object Applied : ComplaintOwnerDeleteReceipt()

    class Rejected(val code: ComplaintOwnerDeleteRejection) : ComplaintOwnerDeleteReceipt() {
        val status: Int get() = code.status
        val problemCode: String get() = code.name
    }

    final override fun toString(): String = "ComplaintOwnerDeleteReceipt(redacted)"
}
