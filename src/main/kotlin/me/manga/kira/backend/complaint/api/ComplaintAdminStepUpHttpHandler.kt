package me.manga.kira.backend.complaint.api

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import jakarta.servlet.AsyncContext
import jakarta.servlet.DispatcherType
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.ServletOutputStream
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpServletResponseWrapper
import me.manga.kira.backend.common.exception.ServiceUnavailableException
import me.manga.kira.backend.common.exception.TooManyRequestsException
import me.manga.kira.backend.common.exception.UnauthorizedException
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadFailure
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadRejected
import me.manga.kira.backend.complaint.domain.rejectAdminRead
import me.manga.kira.backend.security.ClientIpResolver
import me.manga.kira.backend.security.ComplaintAdmissionRejected
import me.manga.kira.backend.security.ComplaintIngressAdmission
import me.manga.kira.backend.security.ComplaintIngressContext
import me.manga.kira.backend.security.IssuedScopedAdminStepUp
import me.manga.kira.backend.security.ScopedAdminStepUpScope
import org.springframework.web.HttpRequestHandler
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.InterruptedIOException
import java.io.PrintWriter
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Fixed inbound operation only. It supplies neither a principal, provider, phase nor current-state authority. */
internal interface ComplaintAdminStepUpHttpPort {
    fun issue(context: ComplaintIngressContext, bearer: String, password: String, clientIp: String): IssuedScopedAdminStepUp
}

/** Original TEST ingress/codec. A selected shared endpoint may continue SOURCE through normal servlet security. */
internal class ComplaintAdminStepUpHttpHandler(
    private val issuer: ComplaintAdminStepUpHttpPort,
    private val ingress: ComplaintIngressAdmission,
    private val clientIps: ClientIpResolver,
    private val responses: ComplaintOwnerHistoryResponses,
) : HttpRequestHandler {
    override fun handleRequest(request: HttpServletRequest, response: HttpServletResponse) = handle(request, response, null)

    /** Fixed servlet continuation only; no supplied principal, source issuer or complaint-failure fallback. */
    internal fun handleSharedRequest(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) =
        handle(request, response, chain)

    private fun handle(request: HttpServletRequest, response: HttpServletResponse, sourceChain: FilterChain?) {
        val output = DeliveryResponse(response)
        guarded(request, output) {
            ingress.withIngress(request) { context ->
                try {
                    guarded(request, output) { exchange(request, output, context, sourceChain) }
                } finally {
                    output.releasePermit()
                }
            }
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun guarded(request: HttpServletRequest, response: DeliveryResponse, operation: () -> Unit) {
        try {
            operation()
        } catch (failure: ComplaintAdminReadRejected) {
            problem(request, response, failure.failure)
        } catch (failure: UnauthorizedException) {
            problem(request, response, ComplaintAdminReadFailure.UNAUTHORIZED, password = failure.code == "INVALID_STEP_UP_CREDENTIALS")
        } catch (failure: TooManyRequestsException) {
            problem(request, response, ComplaintAdminReadFailure.RATE_LIMITED, failure.retryAfterSeconds)
        } catch (failure: ServiceUnavailableException) {
            problem(request, response, ComplaintAdminReadFailure.UNAVAILABLE, failure.retryAfterSeconds)
        } catch (failure: ComplaintAdmissionRejected) {
            problem(request, response, if (failure.status == 429) ComplaintAdminReadFailure.RATE_LIMITED else ComplaintAdminReadFailure.UNAVAILABLE,
                failure.retryAfterSeconds)
        } catch (failure: InterruptedIOException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException(DELIVERY_FAILURE)
        } catch (failure: IOException) {
            throw IOException(DELIVERY_FAILURE)
        } catch (failure: RuntimeException) {
            responses.failClosed()
            problem(request, response, ComplaintAdminReadFailure.INTERNAL)
        } catch (failure: OutOfMemoryError) {
            responses.failClosed()
            problem(request, response, ComplaintAdminReadFailure.INTERNAL)
        }
    }

    private fun exchange(request: HttpServletRequest, response: DeliveryResponse, context: ComplaintIngressContext, sourceChain: FilterChain?) {
        if (request.dispatcherType != DispatcherType.REQUEST || request.isAsyncStarted) rejectAdminRead(ComplaintAdminReadFailure.UNAVAILABLE)
        interrupted()
        ingress.requireLiveContext(context)
        if (request.method != "POST" || request.requestURI != request.contextPath + PATH) rejectAdminRead(ComplaintAdminReadFailure.NOT_FOUND)
        if (request.queryString != null) invalid()
        val headers = headers(request)
        val permit = responses.acquire() ?: rejectAdminRead(ComplaintAdminReadFailure.UNAVAILABLE)
        response.retainPermit(permit)
        val bytes = readBody(request)
        try {
            if (bytes.size > MAX_BODY_BYTES) rejectAdminRead(ComplaintAdminReadFailure.TOO_LARGE)
            if (headers.length >= 0 && bytes.size.toLong() != headers.length) invalid()
            val input = input(bytes)
            if (input.scope === ScopedAdminStepUpScope.SOURCE) {
                val continuation = sourceChain ?: invalid() // Earlier complaint-only selectors stay narrow.
                responses.requirePermit(permit)
                interrupted()
                ingress.requireLiveContext(context)
                // The normal controller is synchronous. Replay only this request's bounded, validated
                // bytes; the original ingress/response permit and outer servlet owner survive its tail.
                continuation.doFilter(SourceRequest(request, bytes), response)
                return
            }
            bytes.fill(0) // The complaint branch no longer needs raw password bytes during issuance.
            writeComplaint(request, response, context, headers, input.password, permit)
        } finally {
            bytes.fill(0)
        }
    }

    private fun writeComplaint(request: HttpServletRequest, response: DeliveryResponse, context: ComplaintIngressContext,
        headers: InputHeaders, password: String, permit: ComplaintOwnerHistoryResponses.Permit) {
        val issued = issuer.issue(context, headers.bearer ?: rejectAdminRead(ComplaintAdminReadFailure.UNAUTHORIZED), password, clientIps.resolve(request))
        check(issued.scope === ScopedAdminStepUpScope.COMPLAINT && TOKEN.matches(issued.token) && issued.grantId.version() == 4 && issued.grantId.variant() == 2)
        responses.requirePermit(permit)
        val encoded = ComplaintHistoryEncodedBody(MAX_RESPONSE_BYTES)
        try {
            JSON.createGenerator(encoded).use { json ->
                json.writeStartObject()
                json.writeStringField("token", issued.token)
                json.writeStringField("expiresAt", issued.expiresAt.toString())
                json.writeStringField("scope", issued.scope.storedName)
                json.writeEndObject()
            }
            responses.requirePermit(permit)
            interrupted()
            ingress.requireResponseReady()
            response.setHeader(GRANT_ID_HEADER, issued.grantId.toString())
            responseHeaders(response, 200, "application/json", encoded.length)
            encoded.sendTo(response.outputStream)
        } finally {
            encoded.destroy()
        }
    }

    @Suppress("SwallowedException")
    private fun input(bytes: ByteArray): StepUpInput = try {
        val decoded = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes))
        try {
            JSON.createParser(decoded.toString()).use { json ->
                if (json.nextToken() != JsonToken.START_OBJECT) invalid()
                var password: String? = null
                var scope: String? = null
                var fields = 0
                while (json.nextToken() != JsonToken.END_OBJECT) {
                    if (json.currentToken != JsonToken.FIELD_NAME || ++fields > 2) invalid()
                    val name = json.currentName()
                    if (json.nextToken() != JsonToken.VALUE_STRING) invalid()
                    when (name) {
                        "password" -> password = json.text.also { if (it.isBlank() || it.length > MAX_PASSWORD_CHARACTERS) invalid() }
                        "scope" -> scope = json.text.also {
                            if (it != ScopedAdminStepUpScope.COMPLAINT.storedName && it != ScopedAdminStepUpScope.SOURCE.storedName) invalid()
                        }
                        else -> invalid()
                    }
                }
                if (json.nextToken() != null || password == null || fields !in 1..2) invalid()
                StepUpInput(checkNotNull(password), if (scope == ScopedAdminStepUpScope.COMPLAINT.storedName)
                    ScopedAdminStepUpScope.COMPLAINT else ScopedAdminStepUpScope.SOURCE)
            }
        } finally {
            if (decoded.hasArray()) decoded.array().fill('\u0000')
        }
    } catch (failure: IOException) {
        invalid()
    } catch (failure: IllegalArgumentException) {
        invalid()
    }

    private fun headers(request: HttpServletRequest): InputHeaders {
        boundedHeaders(request)
        val authorization = single(request, "Authorization", 4103)
        val contract = single(request, "X-Kira-Complaint-Contract", 1)
        if (contract != null && contract != "1") invalid()
        val lengthValue = single(request, "Content-Length", 64, true)?.trim(' ', '\t')
        val transfer = single(request, "Transfer-Encoding", 64, true)?.trim(' ', '\t')
        if (lengthValue != null && transfer != null || lengthValue != null && (lengthValue.isEmpty() || lengthValue.any { it !in '0'..'9' })) invalid()
        if (transfer != null && !transfer.equals("chunked", ignoreCase = true)) invalid()
        val length = if (transfer != null) -1L else lengthValue?.let { it.toLongOrNull() ?: Long.MAX_VALUE } ?: request.contentLengthLong
        val media = single(request, "Content-Type", 128, true)?.trim(' ', '\t')
        val encoding = single(request, "Content-Encoding", 64, true)?.trim(' ', '\t')
        if (media == null || !JSON_MEDIA.matches(media) || encoding != null && !encoding.equals("identity", ignoreCase = true)) {
            rejectAdminRead(ComplaintAdminReadFailure.UNSUPPORTED_MEDIA)
        }
        if (length > MAX_BODY_BYTES) rejectAdminRead(ComplaintAdminReadFailure.TOO_LARGE)
        val bearer = authorization?.let {
            if (!it.startsWith("Bearer ") || it.length == 7) rejectAdminRead(ComplaintAdminReadFailure.UNAUTHORIZED)
            it.substring(7)
        }
        return InputHeaders(bearer, length)
    }

    private fun boundedHeaders(request: HttpServletRequest) {
        val names = request.headerNames ?: invalid()
        var count = 0
        var fields = 0
        var total = 0
        while (names.hasMoreElements()) {
            val name = names.nextElement()
            if (++count > 64 || name.length !in 1..128 || !HEADER_NAME.matches(name)) invalid()
            val values = request.getHeaders(name)
            while (values.hasMoreElements()) {
                val value = values.nextElement()
                if (++fields > 64 || value.length > 16 * 1024) invalid()
                total += name.length + value.length
                if (total > 16 * 1024) invalid()
            }
        }
    }

    private fun single(request: HttpServletRequest, name: String, maximum: Int, tab: Boolean = false): String? {
        val values = request.getHeaders(name)
        if (!values.hasMoreElements()) return null
        val value = values.nextElement()
        if (values.hasMoreElements() || value.length > maximum || value.any { it.code !in 32..126 && !(tab && it == '\t') }) invalid()
        return value
    }

    @Suppress("SwallowedException")
    private fun readBody(request: HttpServletRequest): ByteArray = try {
        request.inputStream.readNBytes(MAX_BODY_BYTES + 1)
    } catch (failure: InterruptedIOException) {
        Thread.currentThread().interrupt()
        throw InterruptedIOException(DELIVERY_FAILURE)
    } catch (failure: IOException) {
        invalid()
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun problem(request: HttpServletRequest, response: DeliveryResponse, failure: ComplaintAdminReadFailure, retry: Long? = null, password: Boolean = false) {
        try {
            if (response.started || response.problemAttempted || response.isCommitted) throw IOException(DELIVERY_FAILURE)
            response.problemAttempted = true
            val bytes = if (password) PASSWORD_PROBLEM else checkNotNull(PROBLEMS[failure])
            response.setHeader(GRANT_ID_HEADER, null)
            responseHeaders(response, failure.status, "application/problem+json", bytes.size)
            if (failure.status == 401) response.setHeader("WWW-Authenticate", "Bearer realm=\"kira-complaints\"")
            retry?.let { response.setHeader("Retry-After", it.coerceIn(1, 3600).toString()) }
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

    private fun interrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException(DELIVERY_FAILURE)
    }

    private class InputHeaders(val bearer: String?, val length: Long) {
        override fun toString(): String = "ComplaintAdminStepUpHeaders(redacted)"
    }

    private class StepUpInput(val password: String, val scope: ScopedAdminStepUpScope) {
        override fun toString(): String = "StepUpInput(redacted)"
    }

    /** Request-local replay, never a cached scope/principal attribute accepted from another dispatch. */
    private class SourceRequest(request: HttpServletRequest, private val bytes: ByteArray) : HttpServletRequestWrapper(request) {
        override fun getContentLength(): Int = bytes.size
        override fun getContentLengthLong(): Long = bytes.size.toLong()
        override fun getCharacterEncoding(): String = "UTF-8"
        override fun isAsyncSupported(): Boolean = false
        override fun startAsync(): AsyncContext = throw IllegalStateException("Step-up is synchronous")
        override fun startAsync(request: ServletRequest, response: ServletResponse): AsyncContext =
            throw IllegalStateException("Step-up is synchronous")
        override fun getInputStream(): ServletInputStream {
            val input = ByteArrayInputStream(bytes)
            return object : ServletInputStream() {
                override fun read(): Int = input.read()
                override fun read(target: ByteArray, offset: Int, length: Int): Int = input.read(target, offset, length)
                override fun isFinished(): Boolean = input.available() == 0
                override fun isReady(): Boolean = true
                override fun setReadListener(listener: ReadListener) = throw IllegalStateException("Step-up is synchronous")
            }
        }
        override fun getReader(): BufferedReader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
        override fun toString(): String = "StepUpSourceRequest(redacted)"
    }

    private class DeliveryResponse(response: HttpServletResponse) : HttpServletResponseWrapper(response) {
        var started = false
            private set
        var problemAttempted = false
        private var permit: ComplaintOwnerHistoryResponses.Permit? = null

        fun retainPermit(selected: ComplaintOwnerHistoryResponses.Permit) { check(permit == null); permit = selected }
        fun releasePermit() { val selected = permit; permit = null; selected?.close() }
        override fun getOutputStream(): ServletOutputStream { started = true; return super.getOutputStream() }
        override fun getWriter(): PrintWriter { started = true; return super.getWriter() }
    }

    override fun toString(): String = "ComplaintAdminStepUpHttpHandler(registered-TEST-only,redacted)"

    companion object {
        const val PATH = "/api/v1/admin/step-up"
        const val GRANT_ID_HEADER = "X-Kira-Admin-Step-Up-Grant-Id"
        const val MAX_BODY_BYTES = 4096
        const val MAX_PASSWORD_CHARACTERS = 256
        const val MAX_RESPONSE_BYTES = 1024
        private const val DELIVERY_FAILURE = "Complaint response delivery failed."
        private val TOKEN = Regex("[A-Za-z0-9_-]{43}")
        private val HEADER_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
        private val JSON_MEDIA = Regex("application/json(?:[ \\t]*;[ \\t]*charset[ \\t]*=[ \\t]*(?:UTF-8|\"UTF-8\"))?", RegexOption.IGNORE_CASE)
        private val JSON = JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(2).maxNameLength(64).maxStringLength(MAX_PASSWORD_CHARACTERS).maxNumberLength(20).build()).build()
        private val PROBLEMS = ComplaintAdminReadFailure.entries.associateWith { failure -> encodedProblem(failure.status, failure.title, failure.code) }
        private val PASSWORD_PROBLEM = encodedProblem(401, "Unauthorized", "INVALID_STEP_UP_CREDENTIALS")

        private fun encodedProblem(status: Int, title: String, code: String): ByteArray =
            ("""{"type":"about:blank","title":"$title","status":$status,"errors":[""" +
                """{"code":"$code","message":"Complaint request refused."}]}""").toByteArray(Charsets.UTF_8).also { check(it.size <= 512) }

        private fun invalid(): Nothing = rejectAdminRead(ComplaintAdminReadFailure.INVALID_REQUEST)
    }
}
