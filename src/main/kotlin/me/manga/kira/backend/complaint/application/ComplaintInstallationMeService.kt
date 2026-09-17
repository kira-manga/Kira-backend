package me.manga.kira.backend.complaint.application

import me.manga.kira.backend.complaint.domain.ComplaintInstallationMe
import me.manga.kira.backend.complaint.domain.ComplaintInstallationMeReadPort
import me.manga.kira.backend.complaint.domain.ComplaintInstallationRequestContext

/** No Spring bean or ambient transaction; the concrete port owns the two released current-row reads. */
internal class ComplaintInstallationMeService(private val reader: ComplaintInstallationMeReadPort) {
    fun read(context: ComplaintInstallationRequestContext, bearer: String): ComplaintInstallationMe {
        val authenticated = reader.authenticate(context, bearer)
        return reader.read(context, authenticated)
    }

    override fun toString(): String = "ComplaintInstallationMeService(dormant)"
}
