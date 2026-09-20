package me.manga.kira.backend.complaint.application

import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusInput
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusPort
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusReceipt
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusRequestContext

/** Two dormant fixed PATCH operations; the port owns real authentication, admission and atomic persistence. */
internal class ComplaintAdminStatusService(private val port: ComplaintAdminStatusPort) {
    fun change(context: ComplaintAdminStatusRequestContext, bearer: String, proof: String?, input: ComplaintAdminStatusInput): ComplaintAdminStatusReceipt =
        port.change(context, bearer, proof, input)

    override fun toString(): String = "ComplaintAdminStatusService(TEST-only,no-mode-authority)"
}
