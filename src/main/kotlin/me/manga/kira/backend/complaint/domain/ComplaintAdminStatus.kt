package me.manga.kira.backend.complaint.domain

import java.util.UUID

/** Request-local ingress only. Neither this view nor supplied role/actor scalars authorize moderation. */
internal interface ComplaintAdminStatusRequestContext

internal interface ComplaintAdminStatusPort {
    fun change(context: ComplaintAdminStatusRequestContext, bearer: String, proof: String?, input: ComplaintAdminStatusInput): ComplaintAdminStatusReceipt
}

/** Exactly two nondeleting single-row operations; never a caller-selected SQL/phase registry. */
internal enum class ComplaintAdminStatusOperation(val route: String, val suffix: String) {
    ADMIN_STATUS("/api/v1/admin/complaints/{id}/status", "/status"),
    ADMIN_CLOSURE("/api/v1/admin/complaints/{id}/closure", "/closure"),
}

internal class ComplaintAdminStatusPrecondition private constructor(private val parsed: ComplaintOwnerEditPrecondition) {
    val targetId: UUID get() = parsed.targetId
    val version: Long get() = parsed.version
    val canonical: String get() = parsed.canonical

    override fun toString(): String = "ComplaintAdminStatusPrecondition(redacted)"

    companion object {
        const val MAX_BYTES = ComplaintOwnerEditPrecondition.MAX_BYTES

        fun parse(targetId: UUID, header: String?): ComplaintAdminStatusPrecondition = try {
            ComplaintAdminStatusPrecondition(ComplaintOwnerEditPrecondition.parse(targetId, header))
        } catch (failure: ComplaintOwnerOperationRejected) {
            rejectAdminStatus(
                if (failure.failure == ComplaintOwnerOperationFailure.PRECONDITION_REQUIRED) {
                    ComplaintAdminStatusFailure.PRECONDITION_REQUIRED
                } else {
                    ComplaintAdminStatusFailure.PRECONDITION_FAILED
                },
            )
        }
    }
}

/** Closed request shapes. A status target cannot smuggle closure text, a time or an actor. */
internal sealed class ComplaintAdminStatusInput private constructor(
    val scope: ComplaintDataScope,
    val targetId: UUID,
    val key: UUID,
    val precondition: ComplaintAdminStatusPrecondition,
) {
    init {
        ComplaintIdentifiers.resourceId(targetId.toString())
        ComplaintIdentifiers.idempotencyKey(key.toString())
        if (!scope.testOnly || precondition.targetId != targetId) rejectAdminStatus(ComplaintAdminStatusFailure.INVALID_REQUEST)
    }

    abstract val operation: ComplaintAdminStatusOperation

    class Transition(
        scope: ComplaintDataScope,
        targetId: UUID,
        key: UUID,
        val status: ComplaintStatus,
        precondition: ComplaintAdminStatusPrecondition,
    ) : ComplaintAdminStatusInput(scope, targetId, key, precondition) {
        init {
            if (!ComplaintStateMachine.isStatusTarget(status)) rejectAdminStatus(ComplaintAdminStatusFailure.INVALID_REQUEST)
        }

        override val operation: ComplaintAdminStatusOperation = ComplaintAdminStatusOperation.ADMIN_STATUS
    }

    class Closure(
        scope: ComplaintDataScope,
        targetId: UUID,
        key: UUID,
        val reason: String,
        precondition: ComplaintAdminStatusPrecondition,
    ) : ComplaintAdminStatusInput(scope, targetId, key, precondition) {
        init {
            if (reason.length > 16_384) rejectAdminStatus(ComplaintAdminStatusFailure.INVALID_REQUEST)
        }

        override val operation: ComplaintAdminStatusOperation = ComplaintAdminStatusOperation.ADMIN_CLOSURE
    }

    final override fun toString(): String = "ComplaintAdminStatusInput(redacted)"
}

/** Normalized only after real normal-JWT and current-database-ADMIN authentication. */
internal class ComplaintAdminStatusRequest private constructor(
    val scope: ComplaintDataScope,
    val targetId: UUID,
    val key: UUID,
    val operation: ComplaintAdminStatusOperation,
    val status: ComplaintStatus?,
    val reason: String?,
    val precondition: ComplaintAdminStatusPrecondition,
) {
    override fun toString(): String = "ComplaintAdminStatusRequest(redacted)"

    companion object {
        fun normalize(input: ComplaintAdminStatusInput): ComplaintAdminStatusRequest = when (input) {
            is ComplaintAdminStatusInput.Transition -> ComplaintAdminStatusRequest(
                input.scope, input.targetId, input.key, input.operation, input.status, null, input.precondition,
            )
            is ComplaintAdminStatusInput.Closure -> ComplaintAdminStatusRequest(
                input.scope, input.targetId, input.key, input.operation, null, ComplaintTextRules.closureReason(input.reason), input.precondition,
            )
        }
    }
}

/** Immutable comparisons only; original ingress and phase retain the one-use authority. */
internal class ComplaintAdminStatusTuple(
    val actor: UUID,
    val scope: ComplaintDataScope,
    val key: UUID,
    val targetId: UUID,
    val operation: ComplaintAdminStatusOperation,
    fingerprint: ByteArray,
) {
    private val digest = fingerprint.copyOf()

    init {
        ComplaintIdentifiers.idempotencyKey(key.toString())
        ComplaintIdentifiers.resourceId(targetId.toString())
        require(scope.testOnly && digest.size == 32)
    }

    fun fingerprintBytes(): ByteArray = digest.copyOf()

    fun matches(other: ComplaintAdminStatusTuple): Boolean = actor == other.actor && scope == other.scope && key == other.key &&
        targetId == other.targetId && operation === other.operation && digest.contentEquals(other.digest)

    override fun toString(): String = "ComplaintAdminStatusTuple(redacted)"
}

internal enum class ComplaintAdminStatusRejection(val status: Int) {
    COMPLAINT_NOT_FOUND(404),
    COMPLAINT_INVALID_TRANSITION(409),
    COMPLAINT_NO_CHANGE(409),
    COMPLAINT_DELETION_PENDING(409),
    PRECONDITION_FAILED(412),
}

/** Returned only after the concrete producer's original terminal commit and physical release. */
internal sealed class ComplaintAdminStatusReceipt private constructor(val consumedGrantId: UUID?) {
    init {
        // Historical NULL is unknown association, never consumption of the proof presented on replay.
        require(consumedGrantId == null || consumedGrantId.version() == 4 && consumedGrantId.variant() == 2)
    }

    class Applied(val id: UUID, val version: Long, consumedGrantId: UUID? = null) : ComplaintAdminStatusReceipt(consumedGrantId) {
        init {
            ComplaintIdentifiers.resourceId(id.toString())
            require(version > 0)
        }

        val etag: String get() = "\"complaint-$id-v$version\""
    }

    class Rejected(val code: ComplaintAdminStatusRejection, consumedGrantId: UUID? = null) : ComplaintAdminStatusReceipt(consumedGrantId) {
        val status: Int get() = code.status
        val problemCode: String get() = code.name
    }

    final override fun toString(): String = "ComplaintAdminStatusReceipt(redacted)"
}

internal enum class ComplaintAdminStatusFailure(val status: Int, val code: String, val title: String) {
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

internal class ComplaintAdminStatusRejected(val failure: ComplaintAdminStatusFailure) :
    RuntimeException("Complaint Admin status request refused.", null, false, false)

internal fun rejectAdminStatus(failure: ComplaintAdminStatusFailure): Nothing = throw ComplaintAdminStatusRejected(failure)
