package me.manga.kira.backend.complaint.api

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import me.manga.kira.backend.complaint.application.ComplaintOwnerDeleteService
import me.manga.kira.backend.complaint.domain.ComplaintIdentifiers
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteInput
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeletePrecondition
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteReceipt
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteStatusQuery
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationFailure
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationRejected
import me.manga.kira.backend.complaint.domain.ComplaintValidationException
import me.manga.kira.backend.complaint.domain.rejectOwnerOperation
import me.manga.kira.backend.security.ComplaintAdmissionRejected
import me.manga.kira.backend.security.ComplaintIngressAdmission
import me.manga.kira.backend.security.ComplaintIngressContext
import org.springframework.web.HttpRequestHandler
import java.io.IOException

/** Unregistered fixed DELETE producer. The existing status owner delegates only its typed DELETE branch here. */
internal class ComplaintOwnerDeleteHttpHandler(
    private val service: ComplaintOwnerDeleteService,
    private val ingress: ComplaintIngressAdmission,
    private val responses: ComplaintOwnerOperationResponse = ComplaintOwnerOperationResponse(),
) : HttpRequestHandler {
    override fun handleRequest(request: HttpServletRequest, response: HttpServletResponse) = responseBoundary(request, response) {
        ingress.withIngress(request) { context -> exchangeHttp(request, response, context) }
    }

    internal fun handleWithinIngress(request: HttpServletRequest, response: HttpServletResponse, context: ComplaintIngressContext) =
        responseBoundary(request, response) {
            ingress.requireLiveContext(context)
            exchangeHttp(request, response, context)
        }

    internal fun sharesOwner(selectedIngress: ComplaintIngressAdmission, selectedResponses: ComplaintOwnerOperationResponse): Boolean =
        ingress === selectedIngress && responses === selectedResponses

    /** No body read, handler claim or nested ingress. The original status dispatcher already owns the permit. */
    internal fun handleStatusWithinIngress(
        response: HttpServletResponse,
        context: ComplaintIngressContext,
        bearer: String,
        query: ComplaintOwnerDeleteStatusQuery,
        permit: ComplaintOwnerHistoryResponses.Permit,
    ) {
        ingress.requireLiveContext(context)
        encodeAndDeliver(response, permit, service.status(context, bearer, query), statusLookup = true)
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun responseBoundary(request: HttpServletRequest, response: HttpServletResponse, operation: () -> Unit) {
        try {
            operation()
        } catch (failure: ComplaintOwnerOperationRejected) {
            if (failure.failure == ComplaintOwnerOperationFailure.INTERNAL) responses.failClosed()
            problem(request, response, failure.failure, if (failure.failure == ComplaintOwnerOperationFailure.IN_PROGRESS) 1 else null)
        } catch (failure: ComplaintAdmissionRejected) {
            problem(
                request,
                response,
                if (failure.status == 429) ComplaintOwnerOperationFailure.RATE_LIMITED else ComplaintOwnerOperationFailure.UNAVAILABLE,
                failure.retryAfterSeconds,
            )
        } catch (failure: IOException) {
            throw IOException("Complaint operation delivery failed.")
        } catch (failure: RuntimeException) {
            responses.failClosed()
            problem(request, response, ComplaintOwnerOperationFailure.INTERNAL)
        } catch (failure: OutOfMemoryError) {
            responses.failClosed()
            problem(request, response, ComplaintOwnerOperationFailure.INTERNAL)
        }
    }

    private fun exchangeHttp(request: HttpServletRequest, response: HttpServletResponse, context: ComplaintIngressContext) {
        val path = DETAIL.matchEntire(request.requestURI.removePrefix(request.contextPath))
        if (request.method != "DELETE" || path == null) rejectOwnerOperation(ComplaintOwnerOperationFailure.NOT_FOUND)
        if (request.queryString != null) rejectOwnerOperation(ComplaintOwnerOperationFailure.INVALID_REQUEST)
        val target = ComplaintIdentifiers.resourceId(path.groupValues[1])
        val bearer = bearer(request)
        val key = single(request, "X-Kira-Idempotency-Key", 36) ?: rejectOwnerOperation(ComplaintOwnerOperationFailure.INVALID_REQUEST)
        val tags = request.getHeaders("If-Match")
        val tag = if (tags.hasMoreElements()) tags.nextElement() else null
        if (tags.hasMoreElements()) rejectOwnerOperation(ComplaintOwnerOperationFailure.PRECONDITION_FAILED)
        val precondition = ComplaintOwnerDeletePrecondition.parse(target, tag)
        val permit = responses.acquire() ?: rejectOwnerOperation(ComplaintOwnerOperationFailure.UNAVAILABLE)
        permit.use {
            emptyBody(request)
            val parsedKey = try {
                ComplaintIdentifiers.idempotencyKey(key)
            } catch (failure: ComplaintValidationException) {
                rejectOwnerOperation(ComplaintOwnerOperationFailure.INVALID_REQUEST)
            }
            val receipt = service.delete(context, bearer, ComplaintOwnerDeleteInput(target, parsedKey, precondition))
            encodeAndDeliver(response, permit, receipt, statusLookup = false)
        }
    }

    private fun bearer(request: HttpServletRequest): String {
        val value = single(request, "Authorization", 4103) ?: rejectOwnerOperation(ComplaintOwnerOperationFailure.UNAUTHORIZED)
        if (!value.startsWith("Bearer ") || value.length == 7) rejectOwnerOperation(ComplaintOwnerOperationFailure.UNAUTHORIZED)
        single(request, "X-Kira-Complaint-Contract", 1)?.let { if (it != "1") rejectOwnerOperation(ComplaintOwnerOperationFailure.INVALID_REQUEST) }
        return value.substring(7)
    }

    @Suppress("SwallowedException")
    private fun emptyBody(request: HttpServletRequest) {
        val length = single(request, "Content-Length", 64)?.trim(' ', '\t')
        val transfer = single(request, "Transfer-Encoding", 64)?.trim(' ', '\t')
        if (length != null && transfer != null) rejectOwnerOperation(ComplaintOwnerOperationFailure.INVALID_REQUEST)
        if (length != null && (length.isEmpty() || length.any { it !in '0'..'9' })) rejectOwnerOperation(ComplaintOwnerOperationFailure.INVALID_REQUEST)
        if (transfer != null && !transfer.equals("chunked", ignoreCase = true)) rejectOwnerOperation(ComplaintOwnerOperationFailure.INVALID_REQUEST)
        val media = single(request, "Content-Type", 128)?.trim(' ', '\t')
        val encoding = single(request, "Content-Encoding", 64)?.trim(' ', '\t')
        if (media != null && !MEDIA.matches(media)) rejectOwnerOperation(ComplaintOwnerOperationFailure.UNSUPPORTED_MEDIA)
        if (encoding != null && !encoding.equals("identity", ignoreCase = true)) rejectOwnerOperation(ComplaintOwnerOperationFailure.UNSUPPORTED_MEDIA)
        val declared = if (transfer != null) -1L else length?.let { it.toLongOrNull() ?: Long.MAX_VALUE } ?: request.contentLengthLong
        if (declared > MAX_BODY_BYTES) rejectOwnerOperation(ComplaintOwnerOperationFailure.PAYLOAD_TOO_LARGE)
        val bytes = try {
            request.inputStream.readNBytes(MAX_BODY_BYTES + 1)
        } catch (failure: IOException) {
            rejectOwnerOperation(ComplaintOwnerOperationFailure.INVALID_REQUEST)
        }
        if (bytes.size > MAX_BODY_BYTES || (declared >= 0 && declared != bytes.size.toLong())) {
            val failure = if (bytes.size > MAX_BODY_BYTES) ComplaintOwnerOperationFailure.PAYLOAD_TOO_LARGE else ComplaintOwnerOperationFailure.INVALID_REQUEST
            bytes.fill(0)
            rejectOwnerOperation(failure)
        }
        val empty = bytes.isEmpty()
        bytes.fill(0)
        if (!empty) rejectOwnerOperation(ComplaintOwnerOperationFailure.INVALID_REQUEST)
    }

    private fun single(request: HttpServletRequest, name: String, maximum: Int): String? {
        val values = request.getHeaders(name)
        if (!values.hasMoreElements()) return null
        val value = values.nextElement()
        if (values.hasMoreElements() || value.length > maximum) {
            rejectOwnerOperation(ComplaintOwnerOperationFailure.INVALID_REQUEST)
        }
        if (value.any { it.code !in 32..126 && it != '\t' }) {
            rejectOwnerOperation(ComplaintOwnerOperationFailure.INVALID_REQUEST)
        }
        return value
    }

    private fun encodeAndDeliver(
        response: HttpServletResponse,
        permit: ComplaintOwnerHistoryResponses.Permit,
        receipt: ComplaintOwnerDeleteReceipt,
        statusLookup: Boolean,
    ) {
        val encoded = responses.encode(permit, receipt, statusLookup)
        try {
            if (!responses.isOpen()) rejectOwnerOperation(ComplaintOwnerOperationFailure.UNAVAILABLE)
            deliver(response, encoded, receipt, statusLookup)
        } finally {
            encoded.destroy()
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun deliver(response: HttpServletResponse, body: ComplaintHistoryEncodedBody, receipt: ComplaintOwnerDeleteReceipt, statusLookup: Boolean) {
        try {
            ingress.requireResponseReady()
            if (!statusLookup && receipt === ComplaintOwnerDeleteReceipt.Applied) {
                response.status = 204
                response.setHeader("X-Kira-Complaint-Contract", "1")
                response.setHeader("Cache-Control", "no-store, no-transform")
                return
            }
            val status = if (!statusLookup && receipt is ComplaintOwnerDeleteReceipt.Rejected) receipt.status else 200
            val media = if (!statusLookup && receipt is ComplaintOwnerDeleteReceipt.Rejected) "application/problem+json" else "application/json"
            headers(response, status, media, body.length)
            body.sendTo(response.outputStream)
        } catch (failure: IOException) {
            throw IOException("Complaint operation delivery failed.")
        } catch (failure: RuntimeException) {
            responses.failClosed()
            throw IOException("Complaint operation delivery failed.")
        } catch (failure: OutOfMemoryError) {
            responses.failClosed()
            throw IOException("Complaint operation delivery failed.")
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun problem(request: HttpServletRequest, response: HttpServletResponse, failure: ComplaintOwnerOperationFailure, retry: Long? = null) {
        if (response.isCommitted) return
        val bytes = checkNotNull(ComplaintOwnerOperationResponse.PROBLEMS[failure])
        try {
            ingress.requireResponseReady()
            headers(response, failure.status, "application/problem+json", bytes.size)
            retry?.let { response.setHeader("Retry-After", it.toString()) }
            if (failure == ComplaintOwnerOperationFailure.UNAUTHORIZED) response.setHeader("WWW-Authenticate", "Bearer realm=\"kira-complaints\"")
            if (request.method != "HEAD") response.outputStream.write(bytes)
        } catch (failure: IOException) {
            throw IOException("Complaint operation delivery failed.")
        } catch (failure: RuntimeException) {
            responses.failClosed()
            throw IOException("Complaint operation delivery failed.")
        }
    }

    private fun headers(response: HttpServletResponse, status: Int, media: String, length: Int) {
        response.status = status
        response.setHeader("X-Kira-Complaint-Contract", "1")
        response.setHeader("Cache-Control", "no-store, no-transform")
        response.contentType = "$media;charset=UTF-8"
        response.setContentLength(length)
    }

    override fun toString(): String = "ComplaintOwnerDeleteHttpHandler(dormant,DELETE-only)"

    companion object {
        const val MAX_BODY_BYTES = 16 * 1024
        private val DETAIL = Regex("/api/v1/complaints/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})")
        private val MEDIA = Regex("""application/json(?:[ \t]*;[ \t]*charset[ \t]*=[ \t]*(?:utf-8|"utf-8"))?""", RegexOption.IGNORE_CASE)
    }
}
