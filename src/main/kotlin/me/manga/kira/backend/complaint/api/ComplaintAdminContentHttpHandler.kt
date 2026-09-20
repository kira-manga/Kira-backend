package me.manga.kira.backend.complaint.api

import jakarta.servlet.DispatcherType
import jakarta.servlet.ServletOutputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpServletResponseWrapper
import me.manga.kira.backend.complaint.application.ComplaintAdminContentService
import me.manga.kira.backend.complaint.domain.ComplaintAdminContentFailure
import me.manga.kira.backend.complaint.domain.ComplaintAdminContentPrecondition
import me.manga.kira.backend.complaint.domain.ComplaintAdminContentReceipt
import me.manga.kira.backend.complaint.domain.ComplaintAdminContentRejected
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintIdentifiers
import me.manga.kira.backend.complaint.domain.ComplaintValidationException
import me.manga.kira.backend.complaint.domain.rejectAdminContent
import me.manga.kira.backend.complaint.parsing.ComplaintAdminContentParser
import me.manga.kira.backend.security.ComplaintAdmissionRejected
import me.manga.kira.backend.security.ComplaintIngressAdmission
import me.manga.kira.backend.security.ComplaintIngressContext
import org.springframework.web.HttpRequestHandler
import java.io.IOException
import java.io.InterruptedIOException
import java.io.PrintWriter
import java.util.UUID

/** Real but UNREGISTERED TEST-only PATCH. It supplies no route, issuer, sourceOnly or activation bypass. */
internal class ComplaintAdminContentHttpHandler(
    private val service: ComplaintAdminContentService,
    private val ingress: ComplaintIngressAdmission,
    private val responses: ComplaintAdminContentResponses,
) : HttpRequestHandler {
    override fun handleRequest(request: HttpServletRequest, response: HttpServletResponse) {
        val output = DeliveryResponse(response)
        guarded(request, output) {
            ingress.withIngress(request) { context ->
                try {
                    // Keep the original ingress and any acquired shared-eight slot through terminal problem delivery.
                    guarded(request, output) { exchange(request, output, context) }
                } finally {
                    output.releasePermit()
                }
            }
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun guarded(request: HttpServletRequest, output: DeliveryResponse, operation: () -> Unit) {
        try {
            operation()
        } catch (failure: ComplaintAdminContentRejected) {
            if (failure.failure == ComplaintAdminContentFailure.INTERNAL) responses.failClosed()
            problem(request, output, failure.failure, if (failure.failure == ComplaintAdminContentFailure.IN_PROGRESS) 1 else null)
        } catch (failure: ComplaintAdmissionRejected) {
            problem(
                request, output,
                if (failure.status == 429) ComplaintAdminContentFailure.RATE_LIMITED else ComplaintAdminContentFailure.UNAVAILABLE,
                failure.retryAfterSeconds,
            )
        } catch (failure: InterruptedIOException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException(DELIVERY_FAILURE)
        } catch (failure: IOException) {
            throw IOException(DELIVERY_FAILURE)
        } catch (failure: RuntimeException) {
            responses.failClosed()
            problem(request, output, ComplaintAdminContentFailure.INTERNAL)
        } catch (failure: OutOfMemoryError) {
            responses.failClosed()
            problem(request, output, ComplaintAdminContentFailure.INTERNAL)
        }
    }

    private fun exchange(request: HttpServletRequest, response: DeliveryResponse, context: ComplaintIngressContext) {
        if (request.dispatcherType != DispatcherType.REQUEST || request.isAsyncStarted) rejectAdminContent(ComplaintAdminContentFailure.UNAVAILABLE)
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException(DELIVERY_FAILURE)
        ingress.requireLiveContext(context)
        val target = target(request)
        val scope = scope(request)
        val headers = headers(request, target)
        val permit = responses.acquire() ?: rejectAdminContent(ComplaintAdminContentFailure.UNAVAILABLE)
        response.retainPermit(permit)
        val bytes = readBody(request)
        val input = try {
            if (bytes.size > MAX_BODY_BYTES) rejectAdminContent(ComplaintAdminContentFailure.TOO_LARGE)
            if (headers.length >= 0 && bytes.size.toLong() != headers.length) rejectAdminContent(ComplaintAdminContentFailure.INVALID_REQUEST)
            ComplaintAdminContentParser.parse(bytes, scope, target, headers.key, headers.precondition)
        } finally {
            bytes.fill(0)
        }
        val receipt = service.edit(context, headers.bearer ?: rejectAdminContent(ComplaintAdminContentFailure.UNAUTHORIZED), headers.proof, input)
        // A returned terminal receipt, not HTTP success, establishes historical operation-level consumption.
        // The supplied replay proof might be different and remains untouched; unknown/rollback outcomes never reach here.
        response.confirmConsumption()
        val encoded = responses.encode(permit, receipt)
        try {
            if (!responses.isOpen()) rejectAdminContent(ComplaintAdminContentFailure.UNAVAILABLE)
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException(DELIVERY_FAILURE)
            ingress.requireResponseReady()
            response.setHeader("ETag", (receipt as? ComplaintAdminContentReceipt.Applied)?.etag)
            val rejection = receipt as? ComplaintAdminContentReceipt.Rejected
            responseHeaders(response, rejection?.status ?: 200, if (rejection == null) "application/json" else "application/problem+json", encoded.length)
            encoded.sendTo(response.outputStream)
        } finally {
            encoded.destroy()
        }
    }

    @Suppress("SwallowedException")
    private fun target(request: HttpServletRequest): UUID {
        val prefix = request.contextPath + PATH_PREFIX
        val path = request.requestURI
        if (request.method != "PATCH" || path.length != prefix.length + 36 + PATH_SUFFIX.length ||
            !path.startsWith(prefix) || !path.endsWith(PATH_SUFFIX)
        ) rejectAdminContent(ComplaintAdminContentFailure.NOT_FOUND)
        return try {
            ComplaintIdentifiers.resourceId(path.substring(prefix.length, path.length - PATH_SUFFIX.length))
        } catch (failure: ComplaintValidationException) {
            rejectAdminContent(ComplaintAdminContentFailure.NOT_FOUND)
        }
    }

    @Suppress("SwallowedException")
    private fun scope(request: HttpServletRequest): ComplaintDataScope {
        val raw = request.queryString ?: rejectAdminContent(ComplaintAdminContentFailure.INVALID_REQUEST)
        if (raw.length != 48 || !raw.startsWith("dataScopeId=")) rejectAdminContent(ComplaintAdminContentFailure.INVALID_REQUEST)
        return try {
            ComplaintIdentifiers.dataScope(raw.substring(12)).also { if (!it.testOnly) rejectAdminContent(ComplaintAdminContentFailure.INVALID_REQUEST) }
        } catch (failure: ComplaintValidationException) {
            rejectAdminContent(ComplaintAdminContentFailure.INVALID_REQUEST)
        }
    }

    private fun headers(request: HttpServletRequest, target: UUID): InputHeaders {
        boundedHeaders(request)
        val authorization = single(request, "Authorization", 4103)
        val contract = single(request, "X-Kira-Complaint-Contract", 1)
        if (contract != null && contract != "1") rejectAdminContent(ComplaintAdminContentFailure.INVALID_REQUEST)
        if (request.getHeaders("If-None-Match").hasMoreElements()) rejectAdminContent(ComplaintAdminContentFailure.INVALID_REQUEST)
        val key = single(request, "X-Kira-Idempotency-Key", 36) ?: rejectAdminContent(ComplaintAdminContentFailure.INVALID_REQUEST)
        val proof = single(request, "X-Kira-Admin-Step-Up", 128)
        val tags = request.getHeaders("If-Match")
        val tag = if (tags.hasMoreElements()) tags.nextElement() else null
        if (tags.hasMoreElements()) rejectAdminContent(ComplaintAdminContentFailure.PRECONDITION_FAILED)
        val precondition = ComplaintAdminContentPrecondition.parse(target, tag)
        val lengthValue = single(request, "Content-Length", 64, true)?.trim(' ', '\t')
        val transfer = single(request, "Transfer-Encoding", 64, true)?.trim(' ', '\t')
        if (lengthValue != null && transfer != null || lengthValue != null && (lengthValue.isEmpty() || lengthValue.any { it !in '0'..'9' })) {
            rejectAdminContent(ComplaintAdminContentFailure.INVALID_REQUEST)
        }
        if (transfer != null && !transfer.equals("chunked", ignoreCase = true)) rejectAdminContent(ComplaintAdminContentFailure.INVALID_REQUEST)
        val length = if (transfer != null) -1L else lengthValue?.let { it.toLongOrNull() ?: Long.MAX_VALUE } ?: request.contentLengthLong
        val media = single(request, "Content-Type", 128, true)?.trim(' ', '\t')
        val encoding = single(request, "Content-Encoding", 64, true)?.trim(' ', '\t')
        if (media == null || !JSON_MEDIA.matches(media) || encoding != null && !encoding.equals("identity", ignoreCase = true)) {
            rejectAdminContent(ComplaintAdminContentFailure.UNSUPPORTED_MEDIA)
        }
        if (length > MAX_BODY_BYTES) rejectAdminContent(ComplaintAdminContentFailure.TOO_LARGE) // Before stream acquisition.
        val bearer = authorization?.let {
            if (!it.startsWith("Bearer ") || it.length == 7) rejectAdminContent(ComplaintAdminContentFailure.UNAUTHORIZED)
            it.substring(7)
        }
        return InputHeaders(bearer, proof, key, precondition, length)
    }

    @Suppress("SwallowedException")
    private fun readBody(request: HttpServletRequest): ByteArray = try {
        request.inputStream.readNBytes(MAX_BODY_BYTES + 1)
    } catch (failure: InterruptedIOException) {
        Thread.currentThread().interrupt()
        throw InterruptedIOException(DELIVERY_FAILURE)
    } catch (failure: IOException) {
        rejectAdminContent(ComplaintAdminContentFailure.INVALID_REQUEST)
    }

    /** Direct-entry finite header work; no reliance on unverified upstream/container bounds. */
    private fun boundedHeaders(request: HttpServletRequest) {
        val names = request.headerNames ?: rejectAdminContent(ComplaintAdminContentFailure.INVALID_REQUEST)
        var nameCount = 0
        var fields = 0
        var total = 0
        while (names.hasMoreElements()) {
            val name = names.nextElement()
            if (++nameCount > 64 || name.length !in 1..128 || !HEADER_NAME.matches(name)) rejectAdminContent(ComplaintAdminContentFailure.INVALID_REQUEST)
            val values = request.getHeaders(name)
            while (values.hasMoreElements()) {
                val value = values.nextElement()
                if (++fields > 64 || value.length > 16 * 1024) rejectAdminContent(ComplaintAdminContentFailure.INVALID_REQUEST)
                total += name.length + value.length
                if (total > 16 * 1024) rejectAdminContent(ComplaintAdminContentFailure.INVALID_REQUEST)
            }
        }
    }

    private fun single(request: HttpServletRequest, name: String, maximum: Int, tab: Boolean = false): String? {
        val values = request.getHeaders(name)
        if (!values.hasMoreElements()) return null
        val value = values.nextElement()
        if (values.hasMoreElements() || value.length > maximum || value.any { it.code !in 32..126 && !(tab && it == '\t') }) {
            rejectAdminContent(ComplaintAdminContentFailure.INVALID_REQUEST)
        }
        return value
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun problem(request: HttpServletRequest, response: DeliveryResponse, failure: ComplaintAdminContentFailure, retry: Long? = null) {
        try {
            if (response.started || response.problemAttempted) throw IOException(DELIVERY_FAILURE)
            response.problemAttempted = true
            if (response.isCommitted) throw IOException(DELIVERY_FAILURE)
            val bytes = checkNotNull(PROBLEMS[failure])
            response.setHeader("ETag", null)
            responseHeaders(response, failure.status, "application/problem+json", bytes.size)
            if (failure.status == 401) response.setHeader("WWW-Authenticate", "Bearer realm=\"kira-complaints\"")
            retry?.let { response.setHeader("Retry-After", it.toString()) }
            if (request.method != "HEAD") response.outputStream.write(bytes)
        } catch (failure: InterruptedIOException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException(DELIVERY_FAILURE)
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

    private fun responseHeaders(response: DeliveryResponse, status: Int, media: String, length: Int) {
        response.status = status
        response.setHeader("X-Kira-Complaint-Contract", "1")
        response.setHeader("Cache-Control", "no-store, no-transform")
        response.setHeader(CONSUMED_HEADER, if (response.consumptionConfirmed) "true" else null)
        response.contentType = "$media;charset=UTF-8"
        response.setContentLength(length)
    }

    override fun toString(): String = "ComplaintAdminContentHttpHandler(unregistered,TEST-only)"

    private class InputHeaders(
        val bearer: String?, val proof: String?, val key: String, val precondition: ComplaintAdminContentPrecondition, val length: Long,
    ) {
        override fun toString(): String = "ComplaintAdminContentInputHeaders(redacted)"
    }

    private class DeliveryResponse(response: HttpServletResponse) : HttpServletResponseWrapper(response) {
        var started = false
            private set
        var problemAttempted = false
        var consumptionConfirmed = false
            private set
        private var permit: ComplaintOwnerHistoryResponses.Permit? = null

        fun confirmConsumption() {
            consumptionConfirmed = true
        }

        fun retainPermit(selected: ComplaintOwnerHistoryResponses.Permit) {
            check(permit == null)
            permit = selected
        }

        fun releasePermit() {
            val selected = permit
            permit = null
            selected?.close()
        }

        override fun getOutputStream(): ServletOutputStream {
            started = true
            return super.getOutputStream()
        }

        override fun getWriter(): PrintWriter {
            started = true
            return super.getWriter()
        }
    }

    companion object {
        const val MAX_BODY_BYTES = ComplaintAdminContentParser.MAX_BODY_BYTES
        const val CONSUMED_HEADER = "X-Kira-Admin-Step-Up-Consumed"
        private const val PATH_PREFIX = "/api/v1/admin/complaints/"
        private const val PATH_SUFFIX = "/content"
        private const val DELIVERY_FAILURE = "Complaint response delivery failed."
        private val HEADER_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
        private val JSON_MEDIA = Regex("application/json(?:[ \\t]*;[ \\t]*charset[ \\t]*=[ \\t]*(?:UTF-8|\"UTF-8\"))?", RegexOption.IGNORE_CASE)
        private val PROBLEMS = ComplaintAdminContentFailure.entries.associateWith { failure ->
            (
                """{"type":"about:blank","title":"${failure.title}","status":${failure.status},"errors":[""" +
                    """{"code":"${failure.code}","message":"Complaint request refused."}]}"""
                ).toByteArray(Charsets.UTF_8).also { check(it.size <= 512) }
        }
    }
}
