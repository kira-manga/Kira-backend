package me.manga.kira.backend.complaint.application

import me.manga.kira.backend.complaint.domain.ComplaintAdminReadPort
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadQuery
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadRequestContext
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadResult

/** No bean or route registration. Authentication is retained by the concrete read producer, not a supplied principal. */
internal class ComplaintAdminReadService(private val reader: ComplaintAdminReadPort) {
    fun read(context: ComplaintAdminReadRequestContext, bearer: String, query: ComplaintAdminReadQuery): ComplaintAdminReadResult {
        val authenticated = reader.authenticate(context, bearer, query)
        return reader.read(context, authenticated)
    }

    override fun toString(): String = "ComplaintAdminReadService(TEST-only,no-mode-authority)"
}
