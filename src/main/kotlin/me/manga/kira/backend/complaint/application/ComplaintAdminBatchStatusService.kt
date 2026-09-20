package me.manga.kira.backend.complaint.application

import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchStatusInput
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchStatusPort
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchStatusReceipt
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchStatusRequestContext

/** One dormant fixed atomic STATUS batch; the port owns real authentication, admission and atomic persistence. */
internal class ComplaintAdminBatchStatusService(private val port: ComplaintAdminBatchStatusPort) {
    fun change(context: ComplaintAdminBatchStatusRequestContext, bearer: String, proof: String?, input: ComplaintAdminBatchStatusInput): ComplaintAdminBatchStatusReceipt =
        port.change(context, bearer, proof, input)

    override fun toString(): String = "ComplaintAdminBatchStatusService(TEST-only,no-mode-authority)"
}
