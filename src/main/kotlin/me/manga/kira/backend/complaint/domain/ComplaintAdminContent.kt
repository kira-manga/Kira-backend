package me.manga.kira.backend.complaint.domain

import java.util.UUID

/** Request-local ingress only. Neither this view nor a supplied user/role can authorize a mutation. */
internal interface ComplaintAdminContentRequestContext

internal interface ComplaintAdminContentPort {
    fun edit(context: ComplaintAdminContentRequestContext, bearer: String, proof: String?, input: ComplaintAdminContentInput): ComplaintAdminContentReceipt
}

/** Reuse the established strong-tag grammar without admitting an owner operation or its handoff. */
internal class ComplaintAdminContentPrecondition private constructor(private val parsed: ComplaintOwnerEditPrecondition) {
    val targetId: UUID get() = parsed.targetId
    val version: Long get() = parsed.version
    val canonical: String get() = parsed.canonical

    override fun toString(): String = "ComplaintAdminContentPrecondition(redacted)"

    companion object {
        const val MAX_BYTES = ComplaintOwnerEditPrecondition.MAX_BYTES

        fun parse(targetId: UUID, header: String?): ComplaintAdminContentPrecondition = try {
            ComplaintAdminContentPrecondition(ComplaintOwnerEditPrecondition.parse(targetId, header))
        } catch (failure: ComplaintOwnerOperationRejected) {
            rejectAdminContent(
                if (failure.failure == ComplaintOwnerOperationFailure.PRECONDITION_REQUIRED) {
                    ComplaintAdminContentFailure.PRECONDITION_REQUIRED
                } else {
                    ComplaintAdminContentFailure.PRECONDITION_FAILED
                },
            )
        }
    }
}

/** Null type AND subject is the explicit notice-reply body-only shape, never inferred by the parser. */
internal class ComplaintAdminContentInput(
    val scope: ComplaintDataScope,
    val targetId: UUID,
    val key: UUID,
    val type: ComplaintType?,
    val subject: String?,
    val body: String,
    val precondition: ComplaintAdminContentPrecondition,
) {
    init {
        ComplaintIdentifiers.resourceId(targetId.toString())
        ComplaintIdentifiers.idempotencyKey(key.toString())
        if (!scope.testOnly || precondition.targetId != targetId || (type == null) != (subject == null) ||
            body.length > 16_384 || (subject != null && subject.length > 16_384)
        ) rejectAdminContent(ComplaintAdminContentFailure.INVALID_REQUEST)
    }

    override fun toString(): String = "ComplaintAdminContentInput(redacted)"
}

/** Normalization occurs only after actual normal-JWT and current-database-ADMIN authentication. */
internal class ComplaintAdminContentRequest private constructor(
    val scope: ComplaintDataScope,
    val targetId: UUID,
    val key: UUID,
    val type: ComplaintType?,
    val subject: String?,
    val body: String,
    val precondition: ComplaintAdminContentPrecondition,
) {
    override fun toString(): String = "ComplaintAdminContentRequest(redacted)"

    companion object {
        fun normalize(input: ComplaintAdminContentInput): ComplaintAdminContentRequest = ComplaintAdminContentRequest(
            input.scope, input.targetId, input.key, input.type,
            input.subject?.let { ComplaintReportTextRules.normalize(it, ComplaintReportField.SUBJECT) },
            ComplaintReportTextRules.editedBody(input.body), input.precondition,
        )
    }
}

/** Comparison data only. The concrete ingress and original phase retain the one-use write authority. */
internal class ComplaintAdminContentTuple(val actor: UUID, val scope: ComplaintDataScope, val key: UUID, val targetId: UUID, fingerprint: ByteArray) {
    private val digest = fingerprint.copyOf()

    init {
        ComplaintIdentifiers.idempotencyKey(key.toString())
        ComplaintIdentifiers.resourceId(targetId.toString())
        require(scope.testOnly && digest.size == 32)
    }

    fun fingerprintBytes(): ByteArray = digest.copyOf()

    fun matches(other: ComplaintAdminContentTuple): Boolean = actor == other.actor && scope == other.scope && key == other.key &&
        targetId == other.targetId && digest.contentEquals(other.digest)

    override fun toString(): String = "ComplaintAdminContentTuple(redacted)"

    companion object {
        const val OPERATION = "ADMIN_EDIT"
    }
}

internal enum class ComplaintAdminContentRejection(val status: Int) {
    COMPLAINT_NOT_FOUND(404),
    COMPLAINT_INVALID_TRANSITION(409),
    COMPLAINT_NO_CHANGE(409),
    COMPLAINT_DELETION_PENDING(409),
    PRECONDITION_FAILED(412),
}

/** The concrete port returns these only after its exact terminal receipt commit and physical release. */
internal sealed class ComplaintAdminContentReceipt private constructor(val consumedGrantId: UUID?) {
    init {
        // Historical NULL is unknown association, never consumption of the proof presented on replay.
        require(consumedGrantId == null || consumedGrantId.version() == 4 && consumedGrantId.variant() == 2)
    }

    class Applied(val id: UUID, val version: Long, consumedGrantId: UUID? = null) : ComplaintAdminContentReceipt(consumedGrantId) {
        init {
            ComplaintIdentifiers.resourceId(id.toString())
            require(version > 0)
        }

        val etag: String get() = "\"complaint-$id-v$version\""
    }

    class Rejected(val code: ComplaintAdminContentRejection, consumedGrantId: UUID? = null) : ComplaintAdminContentReceipt(consumedGrantId) {
        val status: Int get() = code.status
        val problemCode: String get() = code.name
    }

    final override fun toString(): String = "ComplaintAdminContentReceipt(redacted)"
}

internal enum class ComplaintAdminContentFailure(val status: Int, val code: String, val title: String) {
    INVALID_REQUEST(400, "VALIDATION_FAILED", "Bad Request"),
    UNAUTHORIZED(401, "UNAUTHORIZED", "Unauthorized"),
    STEP_UP_REQUIRED(401, "ADMIN_STEP_UP_REQUIRED", "Unauthorized"),
    FORBIDDEN(403, "FORBIDDEN", "Forbidden"),
    NOT_FOUND(404, "NOT_FOUND", "Not Found"),
    KEY_REUSED(409, "IDEMPOTENCY_KEY_REUSED", "Conflict"),
    IN_PROGRESS(409, "IDEMPOTENCY_IN_PROGRESS", "Conflict"),
    PRECONDITION_FAILED(412, "PRECONDITION_FAILED", "Precondition Failed"),
    TOO_LARGE(413, "PAYLOAD_TOO_LARGE", "Payload Too Large"),
    UNSUPPORTED_MEDIA(415, "UNSUPPORTED_MEDIA_TYPE", "Unsupported Media Type"),
    PRECONDITION_REQUIRED(428, "PRECONDITION_REQUIRED", "Precondition Required"),
    RATE_LIMITED(429, "RATE_LIMITED", "Too Many Requests"),
    UNAVAILABLE(503, "SERVICE_UNAVAILABLE", "Service Unavailable"),
    INTERNAL(500, "INTERNAL_ERROR", "Internal Server Error"),
}

internal class ComplaintAdminContentRejected(val failure: ComplaintAdminContentFailure) :
    RuntimeException("Complaint Admin content request refused.", null, false, false)

internal fun rejectAdminContent(failure: ComplaintAdminContentFailure): Nothing = throw ComplaintAdminContentRejected(failure)
