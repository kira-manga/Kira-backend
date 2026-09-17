package me.manga.kira.backend.complaint.application

import me.manga.kira.backend.complaint.domain.ComplaintInstallationRequestContext
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteAllExchange
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteAllResponse
import me.manga.kira.backend.complaint.domain.InstallationDeletionCandidate

/** No bean, transaction, session refresh or retry; the concrete continuation owns the single attempt. */
internal class ComplaintOwnerDeleteAllService(private val exchange: ComplaintOwnerDeleteAllExchange) {
    fun deleteAll(context: ComplaintInstallationRequestContext, candidate: InstallationDeletionCandidate): ComplaintOwnerDeleteAllResponse =
        exchange.deleteAll(context, candidate)
}
