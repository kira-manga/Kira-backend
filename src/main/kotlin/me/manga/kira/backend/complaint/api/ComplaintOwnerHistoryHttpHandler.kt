package me.manga.kira.backend.complaint.api

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import me.manga.kira.backend.complaint.application.ComplaintOwnerHistoryService
import me.manga.kira.backend.complaint.domain.ComplaintOwnerHistoryFailure
import me.manga.kira.backend.complaint.domain.ComplaintOwnerHistoryQuery
import me.manga.kira.backend.complaint.domain.ComplaintOwnerHistoryRejected
import me.manga.kira.backend.complaint.domain.rejectOwnerHistory
import me.manga.kira.backend.security.ComplaintAdmissionRejected
import me.manga.kira.backend.security.ComplaintIngressAdmission
import org.springframework.web.HttpRequestHandler
import java.io.IOException

/** No annotation/bean/mapping. The registered production closed filter is deliberately unchanged. */
internal class ComplaintOwnerHistoryHttpHandler(
    private val service: ComplaintOwnerHistoryService,
    private val ingress: ComplaintIngressAdmission,
    private val responses: ComplaintOwnerHistoryResponses = ComplaintOwnerHistoryResponses(),
) : HttpRequestHandler {
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    override fun handleRequest(request: HttpServletRequest, response: HttpServletResponse) {
        try {
            ingress.withIngress(request) { context ->
                if (request.method != "GET" || request.requestURI != request.contextPath + PATH) rejectOwnerHistory(ComplaintOwnerHistoryFailure.NOT_FOUND)
                val bearer = bearer(request)
                val query = query(request)
                val permit = responses.acquire() ?: rejectOwnerHistory(ComplaintOwnerHistoryFailure.UNAVAILABLE)
                permit.use {
                    val body = responses.encode(permit, service.list(context, bearer, query))
                    try {
                        if (!responses.isOpen()) rejectOwnerHistory(ComplaintOwnerHistoryFailure.UNAVAILABLE)
                        deliver(response, body)
                    } finally {
                        body.destroy()
                    }
                }
            }
        } catch (failure: ComplaintOwnerHistoryRejected) {
            if (failure.failure == ComplaintOwnerHistoryFailure.INTERNAL) responses.failClosed()
            problem(request, response, failure.failure)
        } catch (failure: ComplaintAdmissionRejected) {
            failure.retryAfterSeconds?.let { response.setHeader("Retry-After", it.toString()) }
            problem(request, response, if (failure.status == 429) ComplaintOwnerHistoryFailure.RATE_LIMITED else ComplaintOwnerHistoryFailure.UNAVAILABLE)
        } catch (failure: IOException) {
            // A failed send is not retried and cannot be replaced with a JSON suffix.
            throw IOException("Complaint response delivery failed.")
        } catch (failure: RuntimeException) {
            responses.failClosed()
            problem(request, response, ComplaintOwnerHistoryFailure.INTERNAL)
        } catch (failure: OutOfMemoryError) {
            responses.failClosed()
            problem(request, response, ComplaintOwnerHistoryFailure.INTERNAL)
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun deliver(response: HttpServletResponse, body: ComplaintHistoryEncodedBody) {
        try {
            headers(response, 200, "application/json", body.length)
            body.sendTo(response.outputStream)
        } catch (failure: IOException) {
            throw IOException("Complaint response delivery failed.")
        } catch (failure: RuntimeException) {
            responses.failClosed()
            // Even an uncommitted container buffer may hold a success prefix: never append a problem.
            throw IOException("Complaint response delivery failed.")
        } catch (failure: OutOfMemoryError) {
            responses.failClosed()
            throw IOException("Complaint response delivery failed.")
        }
    }

    private fun bearer(request: HttpServletRequest): String {
        val authorization = single(request, "Authorization", 4103) ?: rejectOwnerHistory(ComplaintOwnerHistoryFailure.UNAUTHORIZED)
        if (!authorization.startsWith("Bearer ") || authorization.length == 7) rejectOwnerHistory(ComplaintOwnerHistoryFailure.UNAUTHORIZED)
        return authorization.substring(7)
    }

    private fun query(request: HttpServletRequest): ComplaintOwnerHistoryQuery {
        val encoding = single(request, "Content-Encoding", 64)
        if (encoding != null && !encoding.equals("identity", ignoreCase = true)) rejectOwnerHistory(ComplaintOwnerHistoryFailure.UNSUPPORTED_MEDIA)
        if (single(request, "Transfer-Encoding", 64) != null || single(request, "Content-Length", 64)?.let { it != "0" } == true) {
            rejectOwnerHistory(ComplaintOwnerHistoryFailure.INVALID_REQUEST)
        }
        val raw = request.queryString ?: return ComplaintOwnerHistoryQuery(50, null)
        if (raw.length > 2200) rejectOwnerHistory(ComplaintOwnerHistoryFailure.INVALID_REQUEST)
        val fields = linkedMapOf<String, String>()
        for (field in raw.split('&')) {
            val pair = field.split('=', limit = 2)
            if (pair.size != 2 || pair[0] !in setOf("limit", "cursor") || fields.put(pair[0], pair[1]) != null) {
                rejectOwnerHistory(ComplaintOwnerHistoryFailure.INVALID_REQUEST)
            }
        }
        val limit = fields["limit"]?.let {
            if (!Regex("[1-9][0-9]?").matches(it)) rejectOwnerHistory(ComplaintOwnerHistoryFailure.INVALID_REQUEST)
            it.toInt()
        } ?: 50
        return ComplaintOwnerHistoryQuery(limit, fields["cursor"])
    }

    private fun single(request: HttpServletRequest, name: String, maximum: Int): String? {
        val values = request.getHeaders(name)
        if (!values.hasMoreElements()) return null
        val value = values.nextElement()
        if (values.hasMoreElements() || value.length > maximum) rejectOwnerHistory(ComplaintOwnerHistoryFailure.INVALID_REQUEST)
        return value
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun problem(request: HttpServletRequest, response: HttpServletResponse, failure: ComplaintOwnerHistoryFailure) {
        if (response.isCommitted) return
        val bytes = checkNotNull(PROBLEMS[failure])
        try {
            headers(response, failure.status, "application/problem+json", bytes.size)
            if (failure.status == 401) response.setHeader("WWW-Authenticate", "Bearer realm=\"kira-complaints\"")
            if (request.method != "HEAD") response.outputStream.write(bytes)
        } catch (problem: IOException) {
            throw IOException("Complaint response delivery failed.")
        } catch (problem: RuntimeException) {
            responses.failClosed()
            throw IOException("Complaint response delivery failed.")
        }
    }

    private fun headers(response: HttpServletResponse, status: Int, media: String, length: Int) {
        response.status = status
        response.setHeader("X-Kira-Complaint-Contract", "1")
        response.setHeader("Cache-Control", "no-store, no-transform")
        response.contentType = "$media;charset=UTF-8"
        response.setContentLength(length)
    }

    override fun toString(): String = "ComplaintOwnerHistoryHttpHandler(dormant)"

    private companion object {
        const val PATH = "/api/v1/complaints"
        val PROBLEMS = ComplaintOwnerHistoryFailure.entries.associateWith { failure ->
            ("""{"type":"about:blank","title":"${failure.title}","status":${failure.status},"errors":[""" +
                """{"code":"${failure.code}","message":"Complaint request refused."}]}""").toByteArray(Charsets.UTF_8)
        }
    }
}
