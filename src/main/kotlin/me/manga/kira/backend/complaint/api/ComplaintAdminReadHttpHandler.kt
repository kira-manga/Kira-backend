package me.manga.kira.backend.complaint.api

import jakarta.servlet.DispatcherType
import jakarta.servlet.ServletOutputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpServletResponseWrapper
import me.manga.kira.backend.complaint.application.ComplaintAdminReadService
import me.manga.kira.backend.complaint.domain.ComplaintAdminDetailQuery
import me.manga.kira.backend.complaint.domain.ComplaintAdminItem
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadFailure
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadRejected
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadResult
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatsQuery
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintIdentifiers
import me.manga.kira.backend.complaint.domain.ComplaintValidationException
import me.manga.kira.backend.complaint.domain.rejectAdminRead
import me.manga.kira.backend.complaint.parsing.ComplaintAdminSearchParser
import me.manga.kira.backend.security.ComplaintAdmissionRejected
import me.manga.kira.backend.security.ComplaintIngressAdmission
import me.manga.kira.backend.security.ComplaintIngressContext
import org.springframework.web.HttpRequestHandler
import java.io.IOException
import java.io.InterruptedIOException
import java.io.PrintWriter
import java.util.UUID

/** Real but UNREGISTERED TEST-only HTTP producer. Its own ingress/bounds precede parsing, crypto and SQL. */
internal class ComplaintAdminReadHttpHandler(
    private val service: ComplaintAdminReadService,
    private val ingress: ComplaintIngressAdmission,
    private val responses: ComplaintAdminReadResponses,
) : HttpRequestHandler {
    override fun handleRequest(request: HttpServletRequest, response: HttpServletResponse) {
        val output = DeliveryResponse(response)
        guarded(request, output) {
            ingress.withIngress(request) { context ->
                try {
                    // An already acquired slot and the original ingress survive bounded problem delivery too.
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
        } catch (failure: ComplaintAdminReadRejected) {
            if (failure.failure == ComplaintAdminReadFailure.INTERNAL) responses.failClosed()
            problem(request, output, failure.failure)
        } catch (failure: ComplaintAdmissionRejected) {
            problem(request, output, if (failure.status == 429) ComplaintAdminReadFailure.RATE_LIMITED else ComplaintAdminReadFailure.UNAVAILABLE, failure.retryAfterSeconds)
        } catch (failure: InterruptedIOException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException(DELIVERY_FAILURE)
        } catch (failure: IOException) {
            throw IOException(DELIVERY_FAILURE)
        } catch (failure: RuntimeException) {
            responses.failClosed()
            problem(request, output, ComplaintAdminReadFailure.INTERNAL)
        } catch (failure: OutOfMemoryError) {
            responses.failClosed()
            problem(request, output, ComplaintAdminReadFailure.INTERNAL)
        }
    }

    private fun exchange(request: HttpServletRequest, response: DeliveryResponse, context: ComplaintIngressContext) {
        if (request.dispatcherType != DispatcherType.REQUEST || request.isAsyncStarted) rejectAdminRead(ComplaintAdminReadFailure.UNAVAILABLE)
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException(DELIVERY_FAILURE)
        ingress.requireLiveContext(context)
        val search = request.method == "POST" && request.requestURI == request.contextPath + SEARCH_PATH
        val stats = request.method == "GET" && request.requestURI == request.contextPath + STATS_PATH
        val id = if (search || stats) null else detailId(request)
        if (search && request.queryString != null) rejectAdminRead(ComplaintAdminReadFailure.INVALID_REQUEST)
        val headers = headers(request, search)
        val bodyless = when {
            stats -> ComplaintAdminStatsQuery(queryScope(request))
            id != null -> ComplaintAdminDetailQuery(queryScope(request), id)
            else -> null
        }
        val permit = responses.acquire() ?: rejectAdminRead(ComplaintAdminReadFailure.UNAVAILABLE)
        response.retainPermit(permit)
        val query = if (search) {
            val bytes = request.inputStream.readNBytes(SEARCH_MAX_BYTES + 1)
            try {
                if (bytes.size > SEARCH_MAX_BYTES) rejectAdminRead(ComplaintAdminReadFailure.TOO_LARGE)
                if (headers.length >= 0 && bytes.size.toLong() != headers.length) rejectAdminRead(ComplaintAdminReadFailure.INVALID_REQUEST)
                ComplaintAdminSearchParser.parse(bytes)
            } finally {
                bytes.fill(0)
            }
        } else {
            if (request.inputStream.read() != -1) rejectAdminRead(ComplaintAdminReadFailure.INVALID_REQUEST)
            checkNotNull(bodyless)
        }
        val result = service.read(context, headers.bearer ?: rejectAdminRead(ComplaintAdminReadFailure.UNAUTHORIZED), query)
        val encoded = responses.encode(permit, result)
        try {
            if (!responses.isOpen()) rejectAdminRead(ComplaintAdminReadFailure.UNAVAILABLE)
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException(DELIVERY_FAILURE)
            val mutable = (result as? ComplaintAdminReadResult.Detail)?.item as? ComplaintAdminItem.Content
            mutable?.let { response.setHeader("ETag", ComplaintOwnerHistoryResponses.actionTag(it.content)) }
            responseHeaders(response, 200, "application/json", encoded.length)
            encoded.sendTo(response.outputStream)
        } finally {
            encoded.destroy()
        }
    }

    @Suppress("SwallowedException")
    private fun detailId(request: HttpServletRequest): UUID {
        val prefix = request.contextPath + DETAIL_PREFIX
        if (request.method != "GET" || !request.requestURI.startsWith(prefix)) rejectAdminRead(ComplaintAdminReadFailure.NOT_FOUND)
        return try {
            ComplaintIdentifiers.resourceId(request.requestURI.substring(prefix.length))
        } catch (failure: ComplaintValidationException) {
            rejectAdminRead(ComplaintAdminReadFailure.NOT_FOUND)
        }
    }

    @Suppress("SwallowedException")
    private fun queryScope(request: HttpServletRequest): ComplaintDataScope {
        val raw = request.queryString ?: rejectAdminRead(ComplaintAdminReadFailure.INVALID_REQUEST)
        if (raw.length != 48 || !raw.startsWith("dataScopeId=")) rejectAdminRead(ComplaintAdminReadFailure.INVALID_REQUEST)
        return try {
            ComplaintIdentifiers.dataScope(raw.substring(12))
        } catch (failure: ComplaintValidationException) {
            rejectAdminRead(ComplaintAdminReadFailure.INVALID_REQUEST)
        }
    }

    private fun headers(request: HttpServletRequest, search: Boolean): InputHeaders {
        boundedHeaders(request)
        val authorization = single(request, "Authorization", 4103)
        val contract = single(request, "X-Kira-Complaint-Contract", 1)
        if (contract != null && contract != "1") rejectAdminRead(ComplaintAdminReadFailure.INVALID_REQUEST)
        if (MUTATION_HEADERS.any { request.getHeaders(it).hasMoreElements() }) rejectAdminRead(ComplaintAdminReadFailure.INVALID_REQUEST)
        val lengthValue = single(request, "Content-Length", 64, true)?.trim(' ', '\t')
        val transfer = single(request, "Transfer-Encoding", 64, true)?.trim(' ', '\t')
        if (lengthValue != null && transfer != null || lengthValue != null && (lengthValue.isEmpty() || lengthValue.any { it !in '0'..'9' })) {
            rejectAdminRead(ComplaintAdminReadFailure.INVALID_REQUEST)
        }
        if (transfer != null && (!search || !transfer.equals("chunked", ignoreCase = true))) rejectAdminRead(ComplaintAdminReadFailure.INVALID_REQUEST)
        val length = lengthValue?.let { it.toLongOrNull() ?: Long.MAX_VALUE } ?: -1L
        if (!search && length > 0) rejectAdminRead(ComplaintAdminReadFailure.INVALID_REQUEST)
        val media = single(request, "Content-Type", 128, true)?.trim(' ', '\t')
        val encoding = single(request, "Content-Encoding", 64, true)?.trim(' ', '\t')
        if (media == null && search || media != null && !JSON_MEDIA.matches(media) || encoding != null && !encoding.equals("identity", ignoreCase = true)) {
            rejectAdminRead(ComplaintAdminReadFailure.UNSUPPORTED_MEDIA)
        }
        if (search && length > SEARCH_MAX_BYTES) rejectAdminRead(ComplaintAdminReadFailure.TOO_LARGE)
        val bearer = authorization?.let {
            if (!it.startsWith("Bearer ") || it.length == 7) rejectAdminRead(ComplaintAdminReadFailure.UNAUTHORIZED)
            it.substring(7)
        }
        return InputHeaders(bearer, length)
    }

    /** Finite direct-entry header work; ordinary servlet/container limits are not treated as proof for this handler. */
    private fun boundedHeaders(request: HttpServletRequest) {
        val names = request.headerNames ?: rejectAdminRead(ComplaintAdminReadFailure.INVALID_REQUEST)
        var nameCount = 0
        var fields = 0
        var total = 0
        while (names.hasMoreElements()) {
            val name = names.nextElement()
            if (++nameCount > 64 || name.length !in 1..128 || !HEADER_NAME.matches(name)) rejectAdminRead(ComplaintAdminReadFailure.INVALID_REQUEST)
            val values = request.getHeaders(name)
            while (values.hasMoreElements()) {
                val value = values.nextElement()
                if (++fields > 64 || value.length > 16 * 1024) rejectAdminRead(ComplaintAdminReadFailure.INVALID_REQUEST)
                total += name.length + value.length
                if (total > 16 * 1024) rejectAdminRead(ComplaintAdminReadFailure.INVALID_REQUEST)
            }
        }
    }

    private fun single(request: HttpServletRequest, name: String, maximum: Int, tab: Boolean = false): String? {
        val values = request.getHeaders(name)
        if (!values.hasMoreElements()) return null
        val value = values.nextElement()
        if (values.hasMoreElements() || value.length > maximum || value.any { it.code !in 32..126 && !(tab && it == '\t') }) {
            rejectAdminRead(ComplaintAdminReadFailure.INVALID_REQUEST)
        }
        return value
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun problem(request: HttpServletRequest, response: DeliveryResponse, failure: ComplaintAdminReadFailure, retry: Long? = null) {
        try {
            // A container may buffer without being committed. Guard failures cannot cause a second send either.
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

    private fun responseHeaders(response: HttpServletResponse, status: Int, media: String, length: Int) {
        response.status = status
        response.setHeader("X-Kira-Complaint-Contract", "1")
        response.setHeader("Cache-Control", "no-store, no-transform")
        response.contentType = "$media;charset=UTF-8"
        response.setContentLength(length)
    }

    override fun toString(): String = "ComplaintAdminReadHttpHandler(unregistered,TEST-only)"

    private class InputHeaders(val bearer: String?, val length: Long) {
        override fun toString(): String = "ComplaintAdminReadInputHeaders(redacted)"
    }

    private class DeliveryResponse(response: HttpServletResponse) : HttpServletResponseWrapper(response) {
        var started = false
            private set
        var problemAttempted = false
        private var permit: ComplaintOwnerHistoryResponses.Permit? = null

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

    private companion object {
        const val SEARCH_PATH = "/api/v1/admin/complaints/search"
        const val STATS_PATH = "/api/v1/admin/complaints/stats"
        const val DETAIL_PREFIX = "/api/v1/admin/complaints/"
        const val SEARCH_MAX_BYTES = 32 * 1024
        const val DELIVERY_FAILURE = "Complaint response delivery failed."
        val HEADER_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
        val JSON_MEDIA = Regex("application/json(?:[ \\t]*;[ \\t]*charset[ \\t]*=[ \\t]*(?:UTF-8|\"UTF-8\"))?", RegexOption.IGNORE_CASE)
        val MUTATION_HEADERS = listOf("X-Kira-Idempotency-Key", "X-Kira-Admin-Step-Up", "If-Match", "If-None-Match")
        val PROBLEMS = ComplaintAdminReadFailure.entries.associateWith { failure ->
            (
                """{"type":"about:blank","title":"${failure.title}","status":${failure.status},"errors":[""" +
                    """{"code":"${failure.code}","message":"Complaint request refused."}]}"""
                ).toByteArray(Charsets.UTF_8).also { check(it.size <= 512) }
        }
    }
}
