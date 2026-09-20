package me.manga.kira.backend.complaint.application

import me.manga.kira.backend.complaint.domain.ComplaintInstallationEnrollmentResponse
import me.manga.kira.backend.complaint.domain.ComplaintInstallationExchange
import me.manga.kira.backend.complaint.domain.ComplaintInstallationRequestContext
import me.manga.kira.backend.complaint.domain.ComplaintInstallationSessionResponse
import me.manga.kira.backend.complaint.domain.InstallationEnrollmentCandidate
import me.manga.kira.backend.complaint.domain.InstallationSessionCandidate

/** No bean or ambient transaction: admission and the owned persistence phases stay separate. */
internal class ComplaintInstallationService(private val exchange: ComplaintInstallationExchange) {
    fun enroll(context: ComplaintInstallationRequestContext, candidate: InstallationEnrollmentCandidate): ComplaintInstallationEnrollmentResponse =
        exchange.enroll(context, candidate)

    fun session(context: ComplaintInstallationRequestContext, candidate: InstallationSessionCandidate): ComplaintInstallationSessionResponse =
        exchange.session(context, candidate)
}
