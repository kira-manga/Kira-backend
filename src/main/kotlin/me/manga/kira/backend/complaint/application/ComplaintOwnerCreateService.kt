package me.manga.kira.backend.complaint.application

import me.manga.kira.backend.complaint.domain.ComplaintOwnerCreateInput
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationContext
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationPort
import me.manga.kira.backend.complaint.domain.ComplaintOwnerReceipt
import me.manga.kira.backend.complaint.domain.ComplaintOwnerStatusQuery

/** Dormant orchestration only; the concrete port owns real authentication, admission and result release. */
internal class ComplaintOwnerCreateService(private val operations: ComplaintOwnerOperationPort) {
    fun create(context: ComplaintOwnerOperationContext, bearer: String, input: ComplaintOwnerCreateInput): ComplaintOwnerReceipt =
        operations.create(context, bearer, input)

    fun status(context: ComplaintOwnerOperationContext, bearer: String, query: ComplaintOwnerStatusQuery): ComplaintOwnerReceipt =
        operations.status(context, bearer, query)

    override fun toString(): String = "ComplaintOwnerCreateService(dormant)"
}
