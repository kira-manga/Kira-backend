package me.manga.kira.backend.complaint.domain

import java.util.Collections
import java.util.UUID

/** This request view grants nothing; the original ingress owner retains the one-use batch handoff. */
internal interface ComplaintAdminBatchStatusRequestContext

internal interface ComplaintAdminBatchStatusPort {
    fun change(context: ComplaintAdminBatchStatusRequestContext, bearer: String, proof: String?, input: ComplaintAdminBatchStatusInput): ComplaintAdminBatchStatusReceipt
}

/** Keep the ID and its strong tag inseparable when sorting. No aggregate precondition exists. */
internal class ComplaintAdminBatchStatusTarget(val id: UUID, val precondition: ComplaintAdminStatusPrecondition) {
    init {
        ComplaintIdentifiers.resourceId(id.toString())
        if (precondition.targetId != id) rejectAdminStatus(ComplaintAdminStatusFailure.INVALID_REQUEST)
    }

    override fun toString(): String = "ComplaintAdminBatchStatusTarget(redacted)"
}

/** The sole fixed STATUS shape, bounded before any authentication, admission, lock or mutation. */
internal class ComplaintAdminBatchStatusInput(
    val scope: ComplaintDataScope,
    val key: UUID,
    val status: ComplaintStatus,
    targets: List<ComplaintAdminBatchStatusTarget>,
) {
    val targets: List<ComplaintAdminBatchStatusTarget>

    init {
        ComplaintIdentifiers.idempotencyKey(key.toString())
        if (!scope.testOnly || !ComplaintStateMachine.isStatusTarget(status) || targets.size !in 1..MAX_TARGETS ||
            targets.map { it.id }.distinct().size != targets.size
        ) rejectAdminStatus(ComplaintAdminStatusFailure.INVALID_REQUEST)
        this.targets = Collections.unmodifiableList(targets.sortedBy { it.id.toString() })
    }

    override fun toString(): String = "ComplaintAdminBatchStatusInput(redacted)"

    companion object {
        const val MAX_TARGETS = 50
    }
}

/** Created only after actual normal-JWT/current-DB-ADMIN authentication by the concrete adapter. */
internal class ComplaintAdminBatchStatusRequest private constructor(
    val scope: ComplaintDataScope,
    val key: UUID,
    val status: ComplaintStatus,
    val targets: List<ComplaintAdminBatchStatusTarget>,
) {
    override fun toString(): String = "ComplaintAdminBatchStatusRequest(redacted)"

    companion object {
        fun normalize(input: ComplaintAdminBatchStatusInput): ComplaintAdminBatchStatusRequest =
            ComplaintAdminBatchStatusRequest(input.scope, input.key, input.status, input.targets)
    }
}

/** Exact immutable comparison data, never an admission or resource capability. */
internal class ComplaintAdminBatchStatusTuple(
    val actor: UUID,
    val scope: ComplaintDataScope,
    val key: UUID,
    targetIds: List<UUID>,
    fingerprint: ByteArray,
) {
    private val ids = Collections.unmodifiableList(targetIds.toList())
    private val digest = fingerprint.copyOf()

    init {
        ComplaintIdentifiers.idempotencyKey(key.toString())
        require(scope.testOnly && digest.size == 32 && ids.size in 1..ComplaintAdminBatchStatusInput.MAX_TARGETS)
        ids.forEach { ComplaintIdentifiers.resourceId(it.toString()) }
        require(ids.distinct().size == ids.size && ids == ids.sortedBy(UUID::toString))
    }

    fun targetIds(): List<UUID> = ids
    fun fingerprintBytes(): ByteArray = digest.copyOf()
    fun matches(other: ComplaintAdminBatchStatusTuple): Boolean = actor == other.actor && scope == other.scope && key == other.key &&
        ids == other.ids && digest.contentEquals(other.digest)

    override fun toString(): String = "ComplaintAdminBatchStatusTuple(redacted)"

    companion object {
        const val OPERATION = "ADMIN_BATCH_STATUS"
        const val ROUTE = "/api/v1/admin/complaints/batch"
    }
}

internal class ComplaintAdminBatchStatusAcknowledgement(val id: UUID, val version: Long) {
    init {
        ComplaintIdentifiers.resourceId(id.toString())
        require(version > 0)
    }

    override fun toString(): String = "ComplaintAdminBatchStatusAcknowledgement(redacted)"
}

/** One historical outcome, returned only after the original ordinary transaction committed and released. */
internal sealed class ComplaintAdminBatchStatusReceipt private constructor(val consumedGrantId: UUID?) {
    init {
        // Older NULL is unknown; never associate a newly presented proof during replay.
        require(consumedGrantId == null || consumedGrantId.version() == 4 && consumedGrantId.variant() == 2)
    }

    class Applied(items: List<ComplaintAdminBatchStatusAcknowledgement>, consumedGrantId: UUID? = null) : ComplaintAdminBatchStatusReceipt(consumedGrantId) {
        val items: List<ComplaintAdminBatchStatusAcknowledgement> = Collections.unmodifiableList(items.toList())

        init {
            val ids = this.items.map { it.id }
            require(ids.size in 1..ComplaintAdminBatchStatusInput.MAX_TARGETS && ids.distinct().size == ids.size && ids == ids.sortedBy(UUID::toString))
        }
    }

    class Rejected(val code: ComplaintAdminStatusRejection, consumedGrantId: UUID? = null) : ComplaintAdminBatchStatusReceipt(consumedGrantId) {
        val status: Int get() = code.status
        val problemCode: String get() = code.name
    }

    final override fun toString(): String = "ComplaintAdminBatchStatusReceipt(redacted)"
}
