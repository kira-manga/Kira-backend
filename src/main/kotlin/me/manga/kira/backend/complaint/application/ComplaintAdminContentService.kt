package me.manga.kira.backend.complaint.application

import me.manga.kira.backend.complaint.domain.ComplaintAdminContentInput
import me.manga.kira.backend.complaint.domain.ComplaintAdminContentPort
import me.manga.kira.backend.complaint.domain.ComplaintAdminContentReceipt
import me.manga.kira.backend.complaint.domain.ComplaintAdminContentRequestContext

/** Dormant fixed content PATCH only; the port owns real authentication, admission and atomic persistence. */
internal class ComplaintAdminContentService(private val port: ComplaintAdminContentPort) {
    fun edit(context: ComplaintAdminContentRequestContext, bearer: String, proof: String?, input: ComplaintAdminContentInput): ComplaintAdminContentReceipt =
        port.edit(context, bearer, proof, input)

    override fun toString(): String = "ComplaintAdminContentService(TEST-only,no-mode-authority)"
}
