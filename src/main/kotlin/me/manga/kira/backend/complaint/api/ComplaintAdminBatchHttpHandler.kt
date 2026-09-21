package me.manga.kira.backend.complaint.api

import jakarta.servlet.DispatcherType
import jakarta.servlet.ServletOutputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpServletResponseWrapper
import me.manga.kira.backend.complaint.application.ComplaintAdminBatchDeleteService
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteReceipt
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteRejected
import me.manga.kira.backend.complaint.parsing.ComplaintAdminBatchInput
import me.manga.kira.backend.complaint.parsing.ComplaintAdminBatchParser
import java.util.concurrent.CancellationException
import me.manga.kira.backend.complaint.application.ComplaintAdminBatchStatusService
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusFailure
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchStatusReceipt
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusRejected
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintIdentifiers
import me.manga.kira.backend.complaint.domain.ComplaintValidationException
import me.manga.kira.backend.complaint.domain.rejectAdminStatus
import me.manga.kira.backend.complaint.parsing.ComplaintAdminBatchStatusParser
import me.manga.kira.backend.security.ComplaintAdmissionRejected
import me.manga.kira.backend.security.ComplaintIngressAdmission
import me.manga.kira.backend.security.ComplaintIngressContext
import org.springframework.web.HttpRequestHandler
import java.io.IOException
import java.io.InterruptedIOException
import java.io.PrintWriter
import java.util.UUID

/** One fixed, UNREGISTERED TEST-only closed STATUS/DELETE dispatcher. No issuer, sourceOnly or activation bypass. */
internal class ComplaintAdminBatchHttpHandler(
    private val service: ComplaintAdminBatchStatusService,
    private val ingress: ComplaintIngressAdmission,
    private val responses: ComplaintAdminBatchStatusResponses,
    private val deletion: ComplaintAdminBatchDeleteService? = null,
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
        } catch (failure: ComplaintAdminStatusRejected) {
            if (failure.failure == ComplaintAdminStatusFailure.INTERNAL) responses.failClosed()
            problem(request, output, failure.failure, if (failure.failure == ComplaintAdminStatusFailure.IN_PROGRESS) 1 else null)
        } catch (failure: ComplaintAdminDeleteRejected) {
            failure.consumedGrantId?.let(output::confirmConsumption)
            val classified = ComplaintAdminStatusFailure.valueOf(failure.failure.name)
            if (classified == ComplaintAdminStatusFailure.INTERNAL) responses.failClosed()
            problem(request, output, classified, if (classified == ComplaintAdminStatusFailure.IN_PROGRESS) 1 else null)
        } catch (failure: ComplaintAdmissionRejected) {
            problem(
                request, output,
                if (failure.status == 429) ComplaintAdminStatusFailure.RATE_LIMITED else ComplaintAdminStatusFailure.UNAVAILABLE,
                failure.retryAfterSeconds,
            )
        } catch (failure: InterruptedIOException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException(DELIVERY_FAILURE)
        } catch (failure: IOException) {
            throw IOException(DELIVERY_FAILURE)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: RuntimeException) {
            responses.failClosed()
            problem(request, output, ComplaintAdminStatusFailure.INTERNAL)
        } catch (failure: OutOfMemoryError) {
            responses.failClosed()
            problem(request, output, ComplaintAdminStatusFailure.INTERNAL)
        }
    }

    private fun exchange(request: HttpServletRequest, response: DeliveryResponse, context: ComplaintIngressContext) {
        if (request.dispatcherType != DispatcherType.REQUEST || request.isAsyncStarted) rejectAdminStatus(ComplaintAdminStatusFailure.UNAVAILABLE)
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException(DELIVERY_FAILURE)
        ingress.requireLiveContext(context)
        route(request)
        val scope = scope(request)
        val headers = headers(request)
        val permit = responses.acquire() ?: rejectAdminStatus(ComplaintAdminStatusFailure.UNAVAILABLE)
        response.retainPermit(permit)
        val bytes = readBody(request)
        val input = try {
            if (bytes.size > MAX_BODY_BYTES) rejectAdminStatus(ComplaintAdminStatusFailure.TOO_LARGE)
            if (headers.length >= 0 && bytes.size.toLong() != headers.length) rejectAdminStatus(ComplaintAdminStatusFailure.INVALID_REQUEST)
            if (deletion == null) ComplaintAdminBatchInput.Status(ComplaintAdminBatchStatusParser.parse(bytes, scope, headers.key))
            else ComplaintAdminBatchParser.parse(bytes, scope, headers.key)
        } finally {
            bytes.fill(0)
        }
        val bearer = headers.bearer ?: rejectAdminStatus(ComplaintAdminStatusFailure.UNAUTHORIZED)
        val result = when (input) {
            is ComplaintAdminBatchInput.Status -> {
                val receipt = service.change(context, bearer, headers.proof, input.input)
                response.confirmConsumption(receipt.consumedGrantId)
                val rejection = receipt as? ComplaintAdminBatchStatusReceipt.Rejected
                Triple(responses.encode(permit, receipt), rejection?.status ?: 200, rejection != null)
            }
            is ComplaintAdminBatchInput.Delete -> {
                val receipt = checkNotNull(deletion).delete(context, bearer, headers.proof, input.input)
                // Single 204 receipts cannot satisfy a batch, including a batch of exactly one.
                check(receipt is ComplaintAdminDeleteReceipt.BatchApplied || receipt is ComplaintAdminDeleteReceipt.Rejected)
                if (receipt is ComplaintAdminDeleteReceipt.BatchApplied) check(receipt.ids == input.input.targets.map { it.id })
                response.confirmConsumption(receipt.consumedGrantId)
                val rejection = receipt as? ComplaintAdminDeleteReceipt.Rejected
                Triple(responses.encodeDelete(permit, receipt), rejection?.status ?: 200, rejection != null)
            }
        }
        val encoded = result.first
        try {
            if (!responses.isOpen()) rejectAdminStatus(ComplaintAdminStatusFailure.UNAVAILABLE)
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException(DELIVERY_FAILURE)
            ingress.requireResponseReady()
            response.setHeader("ETag", null)
            response.setHeader("Location", null)
            responseHeaders(response, result.second, if (result.third) "application/problem+json" else "application/json", encoded.length)
            encoded.sendTo(response.outputStream)
        } finally {
            encoded.destroy()
        }
    }

    private fun route(request: HttpServletRequest) {
        if (request.method != "POST" || request.requestURI != request.contextPath + PATH) rejectAdminStatus(ComplaintAdminStatusFailure.NOT_FOUND)
    }

    @Suppress("SwallowedException")
    private fun scope(request: HttpServletRequest): ComplaintDataScope {
        val raw = request.queryString ?: rejectAdminStatus(ComplaintAdminStatusFailure.INVALID_REQUEST)
        if (raw.length != 48 || !raw.startsWith("dataScopeId=")) rejectAdminStatus(ComplaintAdminStatusFailure.INVALID_REQUEST)
        return try {
            ComplaintIdentifiers.dataScope(raw.substring(12)).also { if (!it.testOnly) rejectAdminStatus(ComplaintAdminStatusFailure.INVALID_REQUEST) }
        } catch (failure: ComplaintValidationException) {
            rejectAdminStatus(ComplaintAdminStatusFailure.INVALID_REQUEST)
        }
    }

    private fun headers(request: HttpServletRequest): InputHeaders {
        boundedHeaders(request)
        val authorization = single(request, "Authorization", 4103)
        val contract = single(request, "X-Kira-Complaint-Contract", 1)
        if (contract != null && contract != "1") rejectAdminStatus(ComplaintAdminStatusFailure.INVALID_REQUEST)
        if (request.getHeaders("If-None-Match").hasMoreElements() || request.getHeaders("If-Match").hasMoreElements()) rejectAdminStatus(ComplaintAdminStatusFailure.INVALID_REQUEST)
        val key = single(request, "X-Kira-Idempotency-Key", 36) ?: rejectAdminStatus(ComplaintAdminStatusFailure.INVALID_REQUEST)
        val proof = single(request, "X-Kira-Admin-Step-Up", 128)
        val lengthValue = single(request, "Content-Length", 64, true)?.trim(' ', '\t')
        val transfer = single(request, "Transfer-Encoding", 64, true)?.trim(' ', '\t')
        if (lengthValue != null && transfer != null || lengthValue != null && (lengthValue.isEmpty() || lengthValue.any { it !in '0'..'9' })) {
            rejectAdminStatus(ComplaintAdminStatusFailure.INVALID_REQUEST)
        }
        if (transfer != null && !transfer.equals("chunked", ignoreCase = true)) rejectAdminStatus(ComplaintAdminStatusFailure.INVALID_REQUEST)
        val length = if (transfer != null) -1L else lengthValue?.let { it.toLongOrNull() ?: Long.MAX_VALUE } ?: request.contentLengthLong
        val media = single(request, "Content-Type", 128, true)?.trim(' ', '\t')
        val encoding = single(request, "Content-Encoding", 64, true)?.trim(' ', '\t')
        if (media == null || !JSON_MEDIA.matches(media) || encoding != null && !encoding.equals("identity", ignoreCase = true)) {
            rejectAdminStatus(ComplaintAdminStatusFailure.UNSUPPORTED_MEDIA)
        }
        if (length > MAX_BODY_BYTES) rejectAdminStatus(ComplaintAdminStatusFailure.TOO_LARGE) // Before stream acquisition.
        val bearer = authorization?.let {
            if (!it.startsWith("Bearer ") || it.length == 7) rejectAdminStatus(ComplaintAdminStatusFailure.UNAUTHORIZED)
            it.substring(7)
        }
        return InputHeaders(bearer, proof, key, length)
    }

    @Suppress("SwallowedException")
    private fun readBody(request: HttpServletRequest): ByteArray = try {
        request.inputStream.readNBytes(MAX_BODY_BYTES + 1)
    } catch (failure: InterruptedIOException) {
        Thread.currentThread().interrupt()
        throw InterruptedIOException(DELIVERY_FAILURE)
    } catch (failure: IOException) {
        rejectAdminStatus(ComplaintAdminStatusFailure.INVALID_REQUEST)
    }

    /** Direct-entry finite header work; no reliance on unverified upstream/container bounds. */
    private fun boundedHeaders(request: HttpServletRequest) {
        val names = request.headerNames ?: rejectAdminStatus(ComplaintAdminStatusFailure.INVALID_REQUEST)
        var nameCount = 0
        var fields = 0
        var total = 0
        while (names.hasMoreElements()) {
            val name = names.nextElement()
            if (++nameCount > 64 || name.length !in 1..128 || !HEADER_NAME.matches(name)) rejectAdminStatus(ComplaintAdminStatusFailure.INVALID_REQUEST)
            val values = request.getHeaders(name)
            while (values.hasMoreElements()) {
                val value = values.nextElement()
                if (++fields > 64 || value.length > 16 * 1024) rejectAdminStatus(ComplaintAdminStatusFailure.INVALID_REQUEST)
                total += name.length + value.length
                if (total > 16 * 1024) rejectAdminStatus(ComplaintAdminStatusFailure.INVALID_REQUEST)
            }
        }
    }

    private fun single(request: HttpServletRequest, name: String, maximum: Int, tab: Boolean = false): String? {
        val values = request.getHeaders(name)
        if (!values.hasMoreElements()) return null
        val value = values.nextElement()
        if (values.hasMoreElements() || value.length > maximum || value.any { it.code !in 32..126 && !(tab && it == '\t') }) {
            rejectAdminStatus(ComplaintAdminStatusFailure.INVALID_REQUEST)
        }
        return value
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun problem(request: HttpServletRequest, response: DeliveryResponse, failure: ComplaintAdminStatusFailure, retry: Long? = null) {
        try {
            if (response.started || response.problemAttempted) throw IOException(DELIVERY_FAILURE)
            response.problemAttempted = true
            if (response.isCommitted) throw IOException(DELIVERY_FAILURE)
            val bytes = checkNotNull(PROBLEMS[failure])
            response.setHeader("ETag", null)
            response.setHeader("Location", null)
            responseHeaders(response, failure.status, "application/problem+json", bytes.size)
            if (failure.status == 401) response.setHeader("WWW-Authenticate", "Bearer realm=\"kira-complaints\"")
            retry?.let { response.setHeader("Retry-After", it.toString()) }
            if (request.method != "HEAD") response.outputStream.write(bytes)
        } catch (failure: InterruptedIOException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException(DELIVERY_FAILURE)
        } catch (failure: IOException) {
            throw IOException(DELIVERY_FAILURE)
        } catch (failure: CancellationException) {
            throw failure
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
        response.setHeader(CONSUMED_GRANT_HEADER, response.consumedGrantId?.toString())
        response.contentType = "$media;charset=UTF-8"
        response.setContentLength(length)
    }

    override fun toString(): String = "ComplaintAdminBatchHttpHandler(unregistered,TEST-only)"

    private class InputHeaders(
        val bearer: String?, val proof: String?, val key: String, val length: Long,
    ) {
        override fun toString(): String = "ComplaintAdminBatchStatusInputHeaders(redacted)"
    }

    private class DeliveryResponse(response: HttpServletResponse) : HttpServletResponseWrapper(response) {
        var started = false
            private set
        var problemAttempted = false
        var consumptionConfirmed = false
            private set
        var consumedGrantId: UUID? = null
            private set
        private var permit: ComplaintOwnerHistoryResponses.Permit? = null

        fun confirmConsumption(grantId: UUID?) {
            consumptionConfirmed = true
            consumedGrantId = grantId
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
        const val MAX_BODY_BYTES = ComplaintAdminBatchStatusParser.MAX_BODY_BYTES
        const val CONSUMED_HEADER = "X-Kira-Admin-Step-Up-Consumed"
        const val CONSUMED_GRANT_HEADER = "X-Kira-Admin-Step-Up-Consumed-Grant-Id"
        private const val PATH = "/api/v1/admin/complaints/batch"
        private const val DELIVERY_FAILURE = "Complaint response delivery failed."
        private val HEADER_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
        private val JSON_MEDIA = Regex("application/json(?:[ \\t]*;[ \\t]*charset[ \\t]*=[ \\t]*(?:UTF-8|\"UTF-8\"))?", RegexOption.IGNORE_CASE)
        private val PROBLEMS = ComplaintAdminStatusFailure.entries.associateWith { failure ->
            (
                """{"type":"about:blank","title":"${failure.title}","status":${failure.status},"errors":[""" +
                    """{"code":"${failure.code}","message":"Complaint request refused."}]}"""
                ).toByteArray(Charsets.UTF_8).also { check(it.size <= 512) }
        }
    }
}
