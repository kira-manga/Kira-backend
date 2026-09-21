package me.manga.kira.backend.complaint.domain

import java.util.Collections
import java.util.UUID

internal interface ComplaintAdminBatchDeleteRequestContext
internal interface ComplaintAdminBatchDeletePort {
    fun deleteBatch(context: ComplaintAdminBatchDeleteRequestContext, bearer: String, proof: String?, input: ComplaintAdminBatchDeleteInput): ComplaintAdminDeleteReceipt
}

/** The target and its exact strong tag never separate while sorting. */
internal class ComplaintAdminBatchDeleteTarget(val id: UUID, val precondition: ComplaintAdminDeletePrecondition) {
    init {
        ComplaintIdentifiers.resourceId(id.toString())
        require(precondition.targetId == id)
    }
    override fun toString(): String = "ComplaintAdminBatchDeleteTarget(redacted)"
}

/** Fixed destructive batch description, not an authenticated actor or journal work. */
internal class ComplaintAdminBatchDeleteInput(val scope: ComplaintDataScope, val key: UUID, targets: List<ComplaintAdminBatchDeleteTarget>) {
    val targets: List<ComplaintAdminBatchDeleteTarget>
    init {
        ComplaintIdentifiers.idempotencyKey(key.toString())
        if (!scope.testOnly || targets.size !in 1..MAX_TARGETS || targets.map { it.id }.distinct().size != targets.size)
            rejectAdminDelete(ComplaintAdminDeleteFailure.INVALID_REQUEST)
        this.targets = Collections.unmodifiableList(targets.sortedBy { it.id.toString() })
    }
    override fun toString(): String = "ComplaintAdminBatchDeleteInput(redacted)"
    companion object {
        const val MAX_TARGETS = 50
        const val ROUTE = "/api/v1/admin/complaints/batch"
    }
}
