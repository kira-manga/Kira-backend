package me.manga.kira.backend.complaint.api

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpServletResponseWrapper
import me.manga.kira.backend.common.web.RequestBodySizeLimitFilter
import me.manga.kira.backend.complaint.application.ComplaintInstallationService
import me.manga.kira.backend.complaint.domain.ComplaintInstallationHttpFailure
import me.manga.kira.backend.complaint.domain.ComplaintInstallationHttpRejected
import me.manga.kira.backend.complaint.domain.ComplaintInstallationSessionResponse
import me.manga.kira.backend.complaint.domain.InstallationEnrollmentDisposition
import me.manga.kira.backend.complaint.domain.rejectInstallationHttp
import me.manga.kira.backend.complaint.parsing.InstallationEnrollmentRequestParser
import me.manga.kira.backend.complaint.parsing.InstallationEnrollmentRequestRejected
import me.manga.kira.backend.complaint.parsing.InstallationSessionRequestParser
import me.manga.kira.backend.complaint.parsing.InstallationSessionRequestRejected
import me.manga.kira.backend.security.ComplaintAdmissionRejected
import me.manga.kira.backend.security.ComplaintIngressAdmission
import me.manga.kira.backend.security.ComplaintIngressContext
import org.springframework.web.HttpRequestHandler
import java.io.IOException

/** No bean/mapping. Explicit TEST composition only; the registered closed filter remains selected. */
internal class ComplaintInstallationHttpHandler(
    private val service: ComplaintInstallationService,
    private val ingress: ComplaintIngressAdmission,
    private val responses: ComplaintInstallationHttpResponses = ComplaintInstallationHttpResponses(),
) : HttpRequestHandler {
    private val bodyFilter = RequestBodySizeLimitFilter(ObjectMapper())

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    override fun handleRequest(request: HttpServletRequest, response: HttpServletResponse) {
        try {
            ingress.withIngress(request) { context ->
                val path = request.requestURI.removePrefix(request.contextPath)
                if (request.method != "POST" || path !in PATHS) rejectInstallationHttp(ComplaintInstallationHttpFailure.NOT_FOUND)
                if (request.queryString != null) rejectInstallationHttp(ComplaintInstallationHttpFailure.INVALID_REQUEST)
                boundedHeader(request, "Authorization", 4096)
                boundedHeader(request, "X-Kira-Complaint-Contract", 64)
                if (!responses.isOpen()) rejectInstallationHttp(ComplaintInstallationHttpFailure.UNAVAILABLE)
                var result: Result? = null
                // Reuse the actual framing/media/stream cap inside ingress, before either parser or DB work.
                try {
                    bodyFilter.doFilter(request, BodyFailureResponse(response)) { bounded, _ ->
                        result = exchange(bounded as HttpServletRequest, context, path)
                    }
                } catch (failure: IOException) {
                    rejectInstallationHttp(ComplaintInstallationHttpFailure.INVALID_REQUEST)
                }
                val completed = checkNotNull(result)
                val body = responses.encode(completed.session)
                try {
                    if (!responses.isOpen()) rejectInstallationHttp(ComplaintInstallationHttpFailure.UNAVAILABLE)
                    deliver(response, body, completed.created)
                } finally {
                    body.destroy()
                }
            }
        } catch (failure: ComplaintInstallationHttpRejected) {
            if (failure.failure == ComplaintInstallationHttpFailure.INTERNAL) responses.failClosed()
            problem(request, response, failure.failure, failure.retryAfterSeconds)
        } catch (failure: ComplaintAdmissionRejected) {
            val kind = if (failure.status == 429) ComplaintInstallationHttpFailure.RATE_LIMITED else ComplaintInstallationHttpFailure.UNAVAILABLE
            problem(request, response, kind, failure.retryAfterSeconds)
        } catch (failure: InstallationEnrollmentRequestRejected) {
            problem(request, response, ComplaintInstallationHttpFailure.INVALID_REQUEST)
        } catch (failure: InstallationSessionRequestRejected) {
            problem(request, response, ComplaintInstallationHttpFailure.INVALID_REQUEST)
        } catch (failure: IOException) {
            throw IOException("Installation HTTP exchange failed.")
        } catch (failure: RuntimeException) {
            responses.failClosed()
            problem(request, response, ComplaintInstallationHttpFailure.INTERNAL)
        } catch (failure: OutOfMemoryError) {
            responses.failClosed()
            problem(request, response, ComplaintInstallationHttpFailure.INTERNAL)
        }
    }

    private fun boundedHeader(request: HttpServletRequest, name: String, maximum: Int) {
        val values = request.getHeaders(name)
        if (!values.hasMoreElements()) return
        val value = values.nextElement()
        if (values.hasMoreElements() || value.length > maximum || value.any { it.code !in 32..126 }) {
            rejectInstallationHttp(ComplaintInstallationHttpFailure.INVALID_REQUEST)
        }
    }

    private fun exchange(request: HttpServletRequest, context: ComplaintIngressContext, path: String): Result {
        val bytes = request.inputStream.readNBytes(InstallationEnrollmentRequestParser.MAX_BODY_BYTES + 1)
        try {
            if (bytes.size > InstallationEnrollmentRequestParser.MAX_BODY_BYTES) rejectInstallationHttp(ComplaintInstallationHttpFailure.PAYLOAD_TOO_LARGE)
            return if (path == ENROLLMENT) {
                val result = service.enroll(context, InstallationEnrollmentRequestParser.parse(bytes))
                Result(result.session, result.disposition == InstallationEnrollmentDisposition.CREATED)
            } else {
                Result(service.session(context, InstallationSessionRequestParser.parse(bytes)), false)
            }
        } finally {
            bytes.fill(0)
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun deliver(response: HttpServletResponse, body: ComplaintHistoryEncodedBody, created: Boolean) {
        try {
            headers(response, if (created) 201 else 200, "application/json", body.length)
            if (created) response.setHeader("Location", "/api/v1/installations/me")
            body.sendTo(response.outputStream)
        } catch (failure: IOException) {
            throw IOException("Installation HTTP exchange failed.")
        } catch (failure: RuntimeException) {
            responses.failClosed()
            throw IOException("Installation HTTP exchange failed.")
        } catch (failure: OutOfMemoryError) {
            responses.failClosed()
            throw IOException("Installation HTTP exchange failed.")
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun problem(request: HttpServletRequest, response: HttpServletResponse, failure: ComplaintInstallationHttpFailure, retry: Long? = null) {
        if (response.isCommitted) return
        val bytes = checkNotNull(ComplaintInstallationHttpResponses.PROBLEMS[failure])
        try {
            headers(response, failure.status, "application/problem+json", bytes.size)
            retry?.let { response.setHeader("Retry-After", it.toString()) }
            if (request.method != "HEAD") response.outputStream.write(bytes)
        } catch (failure: IOException) {
            throw IOException("Installation HTTP exchange failed.")
        } catch (failure: RuntimeException) {
            responses.failClosed()
            throw IOException("Installation HTTP exchange failed.")
        }
    }

    private fun headers(response: HttpServletResponse, status: Int, media: String, length: Int) {
        response.status = status
        response.setHeader("X-Kira-Complaint-Contract", "1")
        response.setHeader("Cache-Control", "no-store, no-transform")
        response.contentType = "$media;charset=UTF-8"
        response.setContentLength(length)
    }

    override fun toString(): String = "ComplaintInstallationHttpHandler(dormant,POST-only)"

    private class Result(val session: ComplaintInstallationSessionResponse, val created: Boolean)

    /** The existing body filter sets status before writing. Convert its finite failures before any body prefix escapes. */
    private class BodyFailureResponse(response: HttpServletResponse) : HttpServletResponseWrapper(response) {
        override fun setStatus(status: Int): Unit = rejectInstallationHttp(
            when (status) {
                400 -> ComplaintInstallationHttpFailure.INVALID_REQUEST
                413 -> ComplaintInstallationHttpFailure.PAYLOAD_TOO_LARGE
                415 -> ComplaintInstallationHttpFailure.UNSUPPORTED_MEDIA
                else -> ComplaintInstallationHttpFailure.INTERNAL
            },
        )
    }

    private companion object {
        const val ENROLLMENT = "/api/v1/installations"
        val PATHS = setOf(ENROLLMENT, "/api/v1/installations/session")
    }
}
