package me.manga.kira.backend.complaint.api

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import me.manga.kira.backend.complaint.application.ComplaintOwnerDetailService
import me.manga.kira.backend.complaint.domain.ComplaintIdentifiers
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDetail
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDetailFailure
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDetailRejected
import me.manga.kira.backend.complaint.domain.ComplaintValidationException
import me.manga.kira.backend.complaint.domain.rejectOwnerDetail
import me.manga.kira.backend.security.ComplaintAdmissionRejected
import me.manga.kira.backend.security.ComplaintIngressAdmission
import me.manga.kira.backend.security.ComplaintIngressContext
import org.springframework.web.HttpRequestHandler
import java.io.IOException
import java.util.UUID

/** No bean, annotation, registration or activation switch. Only explicit TEST composition can supply this handler. */
internal class ComplaintOwnerDetailHttpHandler(
    private val service: ComplaintOwnerDetailService,
    private val ingress: ComplaintIngressAdmission,
    private val responses: ComplaintOwnerHistoryResponses = ComplaintOwnerHistoryResponses(),
) : HttpRequestHandler {
    override fun handleRequest(request: HttpServletRequest, response: HttpServletResponse) = responseBoundary(request, response) {
        ingress.withIngress(request) { context -> exchange(request, response, context) }
    }

    /** The existing outer bridge owns ingress; this validation cannot start or renew it. */
    internal fun handleWithinIngress(request: HttpServletRequest, response: HttpServletResponse, context: ComplaintIngressContext) =
        responseBoundary(request, response) {
            ingress.requireLiveContext(context)
            exchange(request, response, context)
        }

    /** Fixed pre-bearer check. No parsed request is cached as authority; the handler validates again at use. */
    internal fun validateWithinIngress(request: HttpServletRequest, response: HttpServletResponse, context: ComplaintIngressContext): Boolean {
        var accepted = false
        responseBoundary(request, response) {
            ingress.requireLiveContext(context)
            target(request)
            bearer(request)
            requireBodyless(request)
            accepted = true
        }
        return accepted
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun responseBoundary(request: HttpServletRequest, response: HttpServletResponse, operation: () -> Unit) {
        try {
            operation()
        } catch (failure: ComplaintOwnerDetailRejected) {
            if (failure.failure == ComplaintOwnerDetailFailure.INTERNAL) responses.failClosed()
            problem(request, response, failure.failure)
        } catch (failure: ComplaintAdmissionRejected) {
            val kind = if (failure.status == 429) ComplaintOwnerDetailFailure.RATE_LIMITED else ComplaintOwnerDetailFailure.UNAVAILABLE
            problem(request, response, kind, failure.retryAfterSeconds)
        } catch (failure: IOException) {
            throw IOException(DELIVERY_FAILURE)
        } catch (failure: RuntimeException) {
            responses.failClosed()
            problem(request, response, ComplaintOwnerDetailFailure.INTERNAL)
        } catch (failure: OutOfMemoryError) {
            responses.failClosed()
            problem(request, response, ComplaintOwnerDetailFailure.INTERNAL)
        }
    }

    private fun exchange(request: HttpServletRequest, response: HttpServletResponse, context: ComplaintIngressContext) {
        val id = target(request)
        val bearer = bearer(request)
        requireBodyless(request)
        val permit = responses.acquire() ?: rejectOwnerDetail(ComplaintOwnerDetailFailure.UNAVAILABLE)
        permit.use {
            val detail = service.read(context, bearer, id)
            val body = responses.encodeDetail(permit, detail)
            try {
                if (!responses.isOpen()) rejectOwnerDetail(ComplaintOwnerDetailFailure.UNAVAILABLE)
                val tag = when (detail) {
                    is ComplaintOwnerDetail.Content -> ComplaintOwnerHistoryResponses.actionTag(detail.item)
                    is ComplaintOwnerDetail.Notice -> null
                }
                deliver(response, body, tag)
            } finally {
                body.destroy()
            }
        }
    }

    @Suppress("SwallowedException")
    private fun target(request: HttpServletRequest): UUID {
        val prefix = request.contextPath + PREFIX
        val path = request.requestURI
        if (request.method != "GET" || !path.startsWith(prefix) || path.length != prefix.length + 36) {
            rejectOwnerDetail(ComplaintOwnerDetailFailure.NOT_FOUND)
        }
        return try {
            // Immutable SYSTEM notice IDs are canonical, but deliberately need not be version 4.
            ComplaintIdentifiers.resourceId(path.substring(prefix.length))
        } catch (failure: ComplaintValidationException) {
            rejectOwnerDetail(ComplaintOwnerDetailFailure.INVALID_REQUEST)
        }
    }

    private fun bearer(request: HttpServletRequest): String {
        val value = single(request, "Authorization", 4103) ?: rejectOwnerDetail(ComplaintOwnerDetailFailure.UNAUTHORIZED)
        if (!value.startsWith("Bearer ") || value.length == 7) rejectOwnerDetail(ComplaintOwnerDetailFailure.UNAUTHORIZED)
        return value.substring(7)
    }

    @Suppress("SwallowedException") // Failed EOF is still before service work and before any response prefix.
    private fun requireBodyless(request: HttpServletRequest) {
        if (request.queryString != null || request.contentLengthLong !in -1L..0L) rejectOwnerDetail(ComplaintOwnerDetailFailure.INVALID_REQUEST)
        if (single(request, "Transfer-Encoding", 64) != null || single(request, "Content-Length", 64)?.let { it != "0" } == true) {
            rejectOwnerDetail(ComplaintOwnerDetailFailure.INVALID_REQUEST)
        }
        val encoding = single(request, "Content-Encoding", 64)
        if (encoding != null && !encoding.equals("identity", ignoreCase = true)) rejectOwnerDetail(ComplaintOwnerDetailFailure.UNSUPPORTED_MEDIA)
        val contract = single(request, "X-Kira-Complaint-Contract", 64)
        if (contract != null && contract != "1") rejectOwnerDetail(ComplaintOwnerDetailFailure.INVALID_REQUEST)
        try {
            if (request.inputStream.read() != -1) rejectOwnerDetail(ComplaintOwnerDetailFailure.INVALID_REQUEST)
        } catch (failure: IOException) {
            rejectOwnerDetail(ComplaintOwnerDetailFailure.INVALID_REQUEST)
        }
    }

    private fun single(request: HttpServletRequest, name: String, maximum: Int): String? {
        val values = request.getHeaders(name)
        if (!values.hasMoreElements()) return null
        val value = values.nextElement()
        if (values.hasMoreElements() || value.length > maximum || value.any { it.code !in 32..126 }) {
            rejectOwnerDetail(ComplaintOwnerDetailFailure.INVALID_REQUEST)
        }
        return value
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun deliver(response: HttpServletResponse, body: ComplaintHistoryEncodedBody, tag: String?) {
        try {
            headers(response, 200, "application/json", body.length)
            tag?.let { response.setHeader("ETag", it) }
            body.sendTo(response.outputStream)
        } catch (failure: IOException) {
            throw IOException(DELIVERY_FAILURE)
        } catch (failure: RuntimeException) {
            responses.failClosed()
            // Even an uncommitted container buffer may contain success bytes: never append a problem or retry.
            throw IOException(DELIVERY_FAILURE)
        } catch (failure: OutOfMemoryError) {
            responses.failClosed()
            throw IOException(DELIVERY_FAILURE)
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun problem(request: HttpServletRequest, response: HttpServletResponse, failure: ComplaintOwnerDetailFailure, retry: Long? = null) {
        if (response.isCommitted) return
        val bytes = checkNotNull(PROBLEMS[failure])
        try {
            headers(response, failure.status, "application/problem+json", bytes.size)
            if (failure.status == 401) response.setHeader("WWW-Authenticate", "Bearer realm=\"kira-complaints\"")
            retry?.let { response.setHeader("Retry-After", it.toString()) }
            if (request.method != "HEAD") response.outputStream.write(bytes)
        } catch (problem: IOException) {
            throw IOException(DELIVERY_FAILURE)
        } catch (problem: RuntimeException) {
            responses.failClosed()
            throw IOException(DELIVERY_FAILURE)
        } catch (problem: OutOfMemoryError) {
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

    override fun toString(): String = "ComplaintOwnerDetailHttpHandler(dormant)"

    private companion object {
        const val PREFIX = "/api/v1/complaints/"
        const val DELIVERY_FAILURE = "Complaint detail delivery failed."
        val PROBLEMS = ComplaintOwnerDetailFailure.entries.associateWith { failure ->
            (
                """{"type":"about:blank","title":"${failure.title}","status":${failure.status},"errors":[""" +
                    """{"code":"${failure.code}","message":"Complaint request refused."}]}"""
                ).toByteArray(Charsets.UTF_8)
        }
    }
}
