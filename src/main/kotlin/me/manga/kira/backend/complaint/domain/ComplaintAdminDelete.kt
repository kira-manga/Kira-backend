package me.manga.kira.backend.complaint.domain

import java.util.UUID
import java.util.Collections

/** Closed comparison discriminator, not an admission, SQL selector or publication authority. */
internal enum class ComplaintAdminDeleteFamily(val operation: String) {
    SINGLE("ADMIN_DELETE"), BATCH("ADMIN_BATCH_DELETE");
}

/** Ingress identity only, never a supplied ADMIN role or deletion authority. */
internal interface ComplaintAdminDeleteRequestContext

internal interface ComplaintAdminDeletePort {
    fun delete(context: ComplaintAdminDeleteRequestContext, bearer: String, proof: String?, input: ComplaintAdminDeleteInput): ComplaintAdminDeleteReceipt
}

/** The established grammar is shared; an installation's authentication/receipt is not. */
internal class ComplaintAdminDeletePrecondition private constructor(private val parsed: ComplaintOwnerDeletePrecondition) {
    val targetId: UUID get() = parsed.targetId
    val version: Long get() = parsed.version
    val canonical: String get() = parsed.canonical
    override fun toString(): String = "ComplaintAdminDeletePrecondition(redacted)"
    companion object {
        const val MAX_BYTES = ComplaintOwnerDeletePrecondition.MAX_BYTES
        fun parse(targetId: UUID, header: String?): ComplaintAdminDeletePrecondition = try {
            ComplaintAdminDeletePrecondition(ComplaintOwnerDeletePrecondition.parse(targetId, header))
        } catch (failure: ComplaintOwnerOperationRejected) {
            rejectAdminDelete(if (failure.failure == ComplaintOwnerOperationFailure.PRECONDITION_REQUIRED)
                ComplaintAdminDeleteFailure.PRECONDITION_REQUIRED else ComplaintAdminDeleteFailure.PRECONDITION_FAILED)
        }
    }
}

internal class ComplaintAdminDeleteInput(val scope: ComplaintDataScope, val targetId: UUID, val key: UUID, val precondition: ComplaintAdminDeletePrecondition) {
    init {
        ComplaintIdentifiers.resourceId(targetId.toString())
        ComplaintIdentifiers.idempotencyKey(key.toString())
        require(scope.testOnly && precondition.targetId == targetId)
    }
    override fun toString(): String = "ComplaintAdminDeleteInput(redacted)"
}

/** Comparison data prepared only after normal JWT/current database ADMIN authentication. */
internal class ComplaintAdminDeleteRequest private constructor(
    val scope: ComplaintDataScope, val key: UUID, val family: ComplaintAdminDeleteFamily,
    val targets: List<ComplaintAdminBatchDeleteTarget>,
) {
    val targetId: UUID get() { check(family == ComplaintAdminDeleteFamily.SINGLE); return targets.single().id }
    val precondition: ComplaintAdminDeletePrecondition get() { check(family == ComplaintAdminDeleteFamily.SINGLE); return targets.single().precondition }
    override fun toString(): String = "ComplaintAdminDeleteRequest(redacted)"
    companion object {
        fun normalize(input: ComplaintAdminDeleteInput) = ComplaintAdminDeleteRequest(input.scope, input.key, ComplaintAdminDeleteFamily.SINGLE,
            listOf(ComplaintAdminBatchDeleteTarget(input.targetId, input.precondition)))
        fun normalize(input: ComplaintAdminBatchDeleteInput) = ComplaintAdminDeleteRequest(input.scope, input.key, ComplaintAdminDeleteFamily.BATCH, input.targets)
    }
}

/** Actor kind is always ADMIN; the resolved installation owner belongs only to committed journal work. */
internal class ComplaintAdminDeleteTuple private constructor(val actor: UUID, val scope: ComplaintDataScope, val key: UUID,
    val family: ComplaintAdminDeleteFamily, targets: List<UUID>, fingerprint: ByteArray) {
    constructor(actor: UUID, scope: ComplaintDataScope, key: UUID, targetId: UUID, fingerprint: ByteArray) :
        this(actor, scope, key, ComplaintAdminDeleteFamily.SINGLE, listOf(targetId), fingerprint)
    private val ids = Collections.unmodifiableList(targets.toList())
    val targetId: UUID get() { check(family == ComplaintAdminDeleteFamily.SINGLE); return ids.single() }
    val operation: String get() = family.operation
    private val digest = fingerprint.copyOf()
    init {
        ComplaintIdentifiers.idempotencyKey(key.toString())
        ids.forEach { ComplaintIdentifiers.resourceId(it.toString()) }
        require(scope.testOnly && digest.size == 32)
        require(ids.size in 1..(if (family == ComplaintAdminDeleteFamily.SINGLE) 1 else 50) &&
            ids.distinct().size == ids.size && ids == ids.sortedBy(UUID::toString))
    }
    fun fingerprintBytes(): ByteArray = digest.copyOf()
    fun matches(other: ComplaintAdminDeleteTuple): Boolean = actor == other.actor && scope == other.scope && key == other.key &&
        family == other.family && ids == other.ids && digest.contentEquals(other.digest)
    fun targetIds(): List<UUID> = ids
    override fun toString(): String = "ComplaintAdminDeleteTuple(redacted)"
    companion object {
        const val OPERATION = "ADMIN_DELETE"
        fun batch(actor: UUID, scope: ComplaintDataScope, key: UUID, targets: List<UUID>, fingerprint: ByteArray) =
            ComplaintAdminDeleteTuple(actor, scope, key, ComplaintAdminDeleteFamily.BATCH, targets, fingerprint)
    }
}

internal enum class ComplaintAdminDeleteRejection(val status: Int) {
    COMPLAINT_NOT_FOUND(404), COMPLAINT_DELETION_PENDING(409), PRECONDITION_FAILED(412),
}

/** Historical outcomes only; returned after the actual terminal commit and original physical release. */
internal sealed class ComplaintAdminDeleteReceipt private constructor(val consumedGrantId: UUID?) {
    init { require(consumedGrantId == null || consumedGrantId.version() == 4 && consumedGrantId.variant() == 2) }
    class Applied(consumedGrantId: UUID) : ComplaintAdminDeleteReceipt(consumedGrantId)
    class BatchApplied(targets: List<UUID>, consumedGrantId: UUID) : ComplaintAdminDeleteReceipt(consumedGrantId) {
        val ids: List<UUID> = Collections.unmodifiableList(targets.toList())
        init {
            require(ids.size in 1..50 && ids.distinct().size == ids.size && ids == ids.sortedBy(UUID::toString))
            ids.forEach { ComplaintIdentifiers.resourceId(it.toString()) }
        }
    }
    class Rejected(val code: ComplaintAdminDeleteRejection, consumedGrantId: UUID?) : ComplaintAdminDeleteReceipt(consumedGrantId) {
        val status: Int get() = code.status
        val problemCode: String get() = code.name
    }
    final override fun toString(): String = "ComplaintAdminDeleteReceipt(redacted)"
}

internal enum class ComplaintAdminDeleteFailure(val status: Int, val code: String, val title: String) {
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

/** A known authorization survives a phase-two failure; its grant scalar is not a terminal receipt. */
internal class ComplaintAdminDeleteRejected(val failure: ComplaintAdminDeleteFailure, val consumedGrantId: UUID? = null) :
    RuntimeException("Complaint Admin deletion request refused.", null, false, false) {
    init {
        require(consumedGrantId == null || failure == ComplaintAdminDeleteFailure.UNAVAILABLE &&
            consumedGrantId.version() == 4 && consumedGrantId.variant() == 2)
    }
}
internal fun rejectAdminDelete(failure: ComplaintAdminDeleteFailure): Nothing = throw ComplaintAdminDeleteRejected(failure)
