package me.manga.kira.backend.complaint.application

import me.manga.kira.backend.complaint.domain.ComplaintOwnerDetail
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDetailReadPort
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDetailRequestContext
import java.util.UUID

/** No bean or activation switch. The exact reader owns both the preflight and the one-use handoff. */
internal class ComplaintOwnerDetailService(private val reader: ComplaintOwnerDetailReadPort) {
    fun read(context: ComplaintOwnerDetailRequestContext, bearer: String, id: UUID): ComplaintOwnerDetail {
        val authenticated = reader.authenticate(context, bearer, id)
        return reader.read(context, authenticated)
    }

    override fun toString(): String = "ComplaintOwnerDetailService(dormant)"
}
