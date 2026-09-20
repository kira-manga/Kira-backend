package me.manga.kira.backend.complaint.application

import me.manga.kira.backend.complaint.domain.ComplaintOwnerEditInput
import me.manga.kira.backend.complaint.domain.ComplaintOwnerEditPort
import me.manga.kira.backend.complaint.domain.ComplaintOwnerEditReceipt
import me.manga.kira.backend.complaint.domain.ComplaintOwnerEditStatusQuery
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationContext

/** Dormant fixed edit/status delegation. Persistence and ingress remain owned by the concrete port. */
internal class ComplaintOwnerEditService(private val port: ComplaintOwnerEditPort) {
    fun edit(context: ComplaintOwnerOperationContext, bearer: String, input: ComplaintOwnerEditInput): ComplaintOwnerEditReceipt =
        port.edit(context, bearer, input)

    fun status(context: ComplaintOwnerOperationContext, bearer: String, query: ComplaintOwnerEditStatusQuery): ComplaintOwnerEditReceipt =
        port.status(context, bearer, query)
}
