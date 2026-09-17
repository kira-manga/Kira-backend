package me.manga.kira.backend.complaint.api

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import me.manga.kira.backend.complaint.application.ComplaintInstallationMeService
import me.manga.kira.backend.complaint.domain.ComplaintInstallationMeFailure
import me.manga.kira.backend.complaint.domain.ComplaintInstallationMeRejected
import me.manga.kira.backend.complaint.domain.ComplaintInstallationRequestContext
import me.manga.kira.backend.complaint.domain.rejectInstallationMe
import me.manga.kira.backend.security.ComplaintAdmissionRejected
import me.manga.kira.backend.security.ComplaintIngressAdmission
import org.springframework.web.HttpRequestHandler
import java.io.IOException

/** No annotation, bean or mapping. Explicit TEST composition only; the production closed filter is unchanged. */
internal class ComplaintInstallationMeHttpHandler(
    private val service: ComplaintInstallationMeService,
    private val ingress: ComplaintIngressAdmission,
    private val responses: ComplaintInstallationMeHttpResponses = ComplaintInstallationMeHttpResponses(),
) : HttpRequestHandler {
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    override fun handleRequest(request: HttpServletRequest, response: HttpServletResponse) {
        try {
            ingress.withIngress(request) { context -> exchange(request, response, context) }
        } catch (failure: ComplaintInstallationMeRejected) {
            if (failure.failure == ComplaintInstallationMeFailure.INTERNAL) responses.failClosed()
            problem(request, response, failure.failure)
        } catch (failure: ComplaintAdmissionRejected) {
            val kind = if (failure.status == 429) ComplaintInstallationMeFailure.RATE_LIMITED else ComplaintInstallationMeFailure.UNAVAILABLE
            problem(request, response, kind, failure.retryAfterSeconds)
        } catch (failure: IOException) {
            throw IOException(DELIVERY_FAILURE)
        } catch (failure: RuntimeException) {
            responses.failClosed()
            problem(request, response, ComplaintInstallationMeFailure.INTERNAL)
        } catch (failure: OutOfMemoryError) {
            responses.failClosed()
            problem(request, response, ComplaintInstallationMeFailure.INTERNAL)
        }
    }

    private fun exchange(request: HttpServletRequest, response: HttpServletResponse, context: ComplaintInstallationRequestContext) {
        if (request.method != "GET" || request.requestURI != request.contextPath + PATH) rejectInstallationMe(ComplaintInstallationMeFailure.NOT_FOUND)
        val bearer = bearer(request)
        requireBodyless(request)
        if (!responses.isOpen()) rejectInstallationMe(ComplaintInstallationMeFailure.UNAVAILABLE)
        val body = responses.encode(service.read(context, bearer))
        try {
            if (!responses.isOpen()) rejectInstallationMe(ComplaintInstallationMeFailure.UNAVAILABLE)
            deliver(response, body)
        } finally {
            body.destroy()
        }
    }

    private fun bearer(request: HttpServletRequest): String {
        val value = single(request, "Authorization", 4096) ?: rejectInstallationMe(ComplaintInstallationMeFailure.UNAUTHORIZED)
        if (!value.startsWith("Bearer ") || value.length == 7) rejectInstallationMe(ComplaintInstallationMeFailure.UNAUTHORIZED)
        return value.substring(7)
    }

    @Suppress("SwallowedException") // A failed input EOF check precedes service/DB work and contains no response prefix.
    private fun requireBodyless(request: HttpServletRequest) {
        if (request.queryString != null || request.contentLengthLong !in -1L..0L) rejectInstallationMe(ComplaintInstallationMeFailure.INVALID_REQUEST)
        if (single(request, "Transfer-Encoding", 64) != null || single(request, "Content-Length", 64)?.let { it != "0" } == true) {
            rejectInstallationMe(ComplaintInstallationMeFailure.INVALID_REQUEST)
        }
        val encoding = single(request, "Content-Encoding", 64)
        if (encoding != null && !encoding.equals("identity", ignoreCase = true)) rejectInstallationMe(ComplaintInstallationMeFailure.UNSUPPORTED_MEDIA)
        val contract = single(request, "X-Kira-Complaint-Contract", 64)
        if (contract != null && contract != "1") rejectInstallationMe(ComplaintInstallationMeFailure.INVALID_REQUEST)
        try {
            if (request.inputStream.read() != -1) rejectInstallationMe(ComplaintInstallationMeFailure.INVALID_REQUEST)
        } catch (failure: IOException) {
            rejectInstallationMe(ComplaintInstallationMeFailure.INVALID_REQUEST)
        }
    }

    private fun single(request: HttpServletRequest, name: String, maximum: Int): String? {
        val values = request.getHeaders(name)
        if (!values.hasMoreElements()) return null
        val value = values.nextElement()
        if (values.hasMoreElements() || value.length > maximum || value.any { it.code !in 32..126 }) {
            rejectInstallationMe(ComplaintInstallationMeFailure.INVALID_REQUEST)
        }
        return value
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun deliver(response: HttpServletResponse, body: ComplaintHistoryEncodedBody) {
        try {
            headers(response, 200, "application/json", body.length)
            body.sendTo(response.outputStream)
        } catch (failure: IOException) {
            throw IOException(DELIVERY_FAILURE)
        } catch (failure: RuntimeException) {
            responses.failClosed()
            // A container buffer may already contain success bytes even when isCommitted is false.
            throw IOException(DELIVERY_FAILURE)
        } catch (failure: OutOfMemoryError) {
            responses.failClosed()
            throw IOException(DELIVERY_FAILURE)
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun problem(request: HttpServletRequest, response: HttpServletResponse, failure: ComplaintInstallationMeFailure, retry: Long? = null) {
        if (response.isCommitted) return
        val bytes = checkNotNull(ComplaintInstallationMeHttpResponses.PROBLEMS[failure])
        try {
            headers(response, failure.status, "application/problem+json", bytes.size)
            if (failure == ComplaintInstallationMeFailure.UNAUTHORIZED) response.setHeader("WWW-Authenticate", "Bearer realm=\"kira-complaints\"")
            retry?.let { response.setHeader("Retry-After", it.toString()) }
            if (request.method != "HEAD") response.outputStream.write(bytes)
        } catch (failure: IOException) {
            throw IOException(DELIVERY_FAILURE)
        } catch (failure: RuntimeException) {
            responses.failClosed()
            throw IOException(DELIVERY_FAILURE)
        } catch (failure: OutOfMemoryError) {
            responses.failClosed()
            throw IOException(DELIVERY_FAILURE)
        }
    }

    private fun headers(response: HttpServletResponse, status: Int, media: String, length: Int) {
        response.status = status
        response.setHeader("X-Kira-Complaint-Contract", "1")
        response.setHeader("Cache-Control", "no-store, no-transform")
        response.contentType = "$media;charset=UTF-8"
        response.setContentLength(length)
    }

    override fun toString(): String = "ComplaintInstallationMeHttpHandler(dormant,GET-only)"

    private companion object {
        const val PATH = "/api/v1/installations/me"
        const val DELIVERY_FAILURE = "Installation read delivery failed."
    }
}
