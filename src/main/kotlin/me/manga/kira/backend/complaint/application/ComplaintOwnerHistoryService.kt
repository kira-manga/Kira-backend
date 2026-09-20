package me.manga.kira.backend.complaint.application

import me.manga.kira.backend.complaint.domain.ComplaintOwnerHistoryPage
import me.manga.kira.backend.complaint.domain.ComplaintOwnerHistoryQuery
import me.manga.kira.backend.complaint.domain.ComplaintOwnerHistoryReadPort
import me.manga.kira.backend.complaint.domain.ComplaintOwnerHistoryRequestContext

/** No Spring bean or enabling switch. The concrete port authenticates and rechecks before reading. */
internal class ComplaintOwnerHistoryService(private val reader: ComplaintOwnerHistoryReadPort) {
    fun list(context: ComplaintOwnerHistoryRequestContext, bearer: String, query: ComplaintOwnerHistoryQuery): ComplaintOwnerHistoryPage {
        val authenticated = reader.authenticate(context, bearer, query)
        return reader.read(context, authenticated)
    }

    override fun toString(): String = "ComplaintOwnerHistoryService(dormant)"
}
