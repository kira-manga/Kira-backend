package me.manga.kira.backend.complaint.api

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import me.manga.kira.backend.complaint.application.ComplaintAdminBatchStatusService
import me.manga.kira.backend.security.ComplaintIngressAdmission
import org.springframework.web.HttpRequestHandler

/** Strict legacy entry; one shared bounded exchange, with DELETE unavailable rather than delegated. */
internal class ComplaintAdminBatchStatusHttpHandler(service: ComplaintAdminBatchStatusService, ingress: ComplaintIngressAdmission,
    responses: ComplaintAdminBatchStatusResponses) : HttpRequestHandler {
    private val handler = ComplaintAdminBatchHttpHandler(service, ingress, responses)
    override fun handleRequest(request: HttpServletRequest, response: HttpServletResponse) = handler.handleRequest(request, response)
    companion object {
        const val MAX_BODY_BYTES = ComplaintAdminBatchHttpHandler.MAX_BODY_BYTES
        const val CONSUMED_HEADER = ComplaintAdminBatchHttpHandler.CONSUMED_HEADER
        const val CONSUMED_GRANT_HEADER = ComplaintAdminBatchHttpHandler.CONSUMED_GRANT_HEADER
    }
}
