package me.manga.kira.backend.complaint.application

import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchDeleteInput
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchDeletePort
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchDeleteRequestContext
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteReceipt

/** One atomic deletion, never a sequence of scalar service calls or an ACTIVE route issuer. */
internal class ComplaintAdminBatchDeleteService(private val port: ComplaintAdminBatchDeletePort) {
    fun delete(context: ComplaintAdminBatchDeleteRequestContext, bearer: String, proof: String?, input: ComplaintAdminBatchDeleteInput): ComplaintAdminDeleteReceipt =
        port.deleteBatch(context, bearer, proof, input)
}
