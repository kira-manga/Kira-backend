package me.manga.kira.backend.complaint.application

import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteInput
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeletePort
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteRequestContext

/** Fixed dormant delegate; it never opens an ordinary transaction around deletion phases. */
internal class ComplaintAdminDeleteService(private val operations: ComplaintAdminDeletePort) {
    fun delete(context: ComplaintAdminDeleteRequestContext, bearer: String, proof: String?, input: ComplaintAdminDeleteInput) =
        operations.delete(context, bearer, proof, input)
}
