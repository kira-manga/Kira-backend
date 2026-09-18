package me.manga.kira.backend.complaint.application

import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteInput
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeletePort
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteReceipt
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteStatusQuery
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationContext

/** Dormant fixed DELETE/status delegation; no bean or activation. */
internal class ComplaintOwnerDeleteService(private val port: ComplaintOwnerDeletePort) {
    fun delete(context: ComplaintOwnerOperationContext, bearer: String, input: ComplaintOwnerDeleteInput): ComplaintOwnerDeleteReceipt =
        port.delete(context, bearer, input)

    fun status(context: ComplaintOwnerOperationContext, bearer: String, query: ComplaintOwnerDeleteStatusQuery): ComplaintOwnerDeleteReceipt =
        port.status(context, bearer, query)
}
