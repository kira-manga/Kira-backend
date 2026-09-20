package me.manga.kira.backend.complaint.api

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.application.ComplaintInstallationBootstrapService
import me.manga.kira.backend.complaint.domain.ComplaintInstallationBootstrapFailure
import me.manga.kira.backend.complaint.domain.ComplaintInstallationBootstrapRejected
import me.manga.kira.backend.complaint.domain.rejectInstallationBootstrap
import me.manga.kira.backend.security.ComplaintAdmissionRejected
import me.manga.kira.backend.security.ComplaintIngressAdmission
import me.manga.kira.backend.security.ComplaintIngressContext
import org.springframework.web.HttpRequestHandler
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CancellationException

/** Only the explicit registered TEST composition maps this producer through the original outer bridge. */
internal class ComplaintInstallationBootstrapHttpHandler(
    private val service: ComplaintInstallationBootstrapService,
    private val ingress: ComplaintIngressAdmission,
    private val responses: ComplaintInstallationBootstrapHttpResponses = ComplaintInstallationBootstrapHttpResponses(),
) : HttpRequestHandler {
    override fun handleRequest(request: HttpServletRequest, response: HttpServletResponse) = responseBoundary(request, response) {
        ingress.withIngress(request) { context -> exchange(request, response, context) }
    }

    internal fun handleWithinIngress(request: HttpServletRequest, response: HttpServletResponse, context: ComplaintIngressContext) =
        responseBoundary(request, response) {
            ingress.requireLiveContext(context)
            exchange(request, response, context)
        }

    /** Fixed bodyless validation before the generic body buffer; no semantic charge, auth or database call. */
    internal fun validateWithinIngress(request: HttpServletRequest, response: HttpServletResponse, context: ComplaintIngressContext): Boolean {
        var valid = false
        responseBoundary(request, response) {
            ingress.requireLiveContext(context)
            requireRequest(request)
            valid = true
        }
        return valid
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun responseBoundary(request: HttpServletRequest, response: HttpServletResponse, operation: () -> Unit) {
        try {
            requireNotInterrupted()
            operation()
        } catch (failure: ComplaintInstallationBootstrapRejected) {
            problem(request, response, failure.failure)
        } catch (failure: ComplaintAdmissionRejected) {
            val kind = if (failure.status == 429) ComplaintInstallationBootstrapFailure.RATE_LIMITED else ComplaintInstallationBootstrapFailure.UNAVAILABLE
            problem(request, response, kind, failure.retryAfterSeconds)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: InterruptedIOException) {
            Thread.currentThread().interrupt()
            throw failure
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw failure
        } catch (failure: IOException) {
            throw IOException(DELIVERY_FAILURE)
        } catch (failure: RuntimeException) {
            requireNotInterrupted()
            responses.failClosed()
            problem(request, response, ComplaintInstallationBootstrapFailure.UNAVAILABLE)
        } catch (failure: OutOfMemoryError) {
            responses.failClosed()
            problem(request, response, ComplaintInstallationBootstrapFailure.UNAVAILABLE)
        }
    }

    private fun exchange(request: HttpServletRequest, response: HttpServletResponse, context: ComplaintIngressContext) {
        requireRequest(request) // Deliberately never inspects any supplied Authorization value.
        if (!responses.isOpen()) rejectInstallationBootstrap(ComplaintInstallationBootstrapFailure.UNAVAILABLE)
        val body = responses.encode(service.read(context))
        try {
            requireNotInterrupted()
            ingress.requireLiveContext(context)
            if (!responses.isOpen()) rejectInstallationBootstrap(ComplaintInstallationBootstrapFailure.UNAVAILABLE)
            deliver(response, body)
        } finally {
            body.destroy()
        }
    }

    @Suppress("SwallowedException")
    private fun requireRequest(request: HttpServletRequest) {
        if (request.method != "GET" || request.requestURI != request.contextPath + PATH) rejectInstallationBootstrap(ComplaintInstallationBootstrapFailure.NOT_FOUND)
        if (request.queryString != null || single(request, "Transfer-Encoding", 64) != null ||
            single(request, "Content-Length", 64)?.let { it != "0" } == true
        ) rejectInstallationBootstrap(ComplaintInstallationBootstrapFailure.INVALID_REQUEST)
        val encoding = single(request, "Content-Encoding", 64)
        if (encoding != null && !encoding.equals("identity", ignoreCase = true)) rejectInstallationBootstrap(ComplaintInstallationBootstrapFailure.UNSUPPORTED_MEDIA)
        val contract = single(request, "X-Kira-Complaint-Contract", 64)
        if (contract != null && contract != "1") rejectInstallationBootstrap(ComplaintInstallationBootstrapFailure.INVALID_REQUEST)
        single(request, "Content-Type", 128) // Optional on this bodyless read, but never duplicate/unbounded/malformed.
        if (request.contentLengthLong !in -1L..0L) rejectInstallationBootstrap(ComplaintInstallationBootstrapFailure.INVALID_REQUEST)
        try {
            if (request.inputStream.read() != -1) rejectInstallationBootstrap(ComplaintInstallationBootstrapFailure.INVALID_REQUEST)
        } catch (failure: InterruptedIOException) {
            Thread.currentThread().interrupt()
            throw failure
        } catch (failure: IOException) {
            rejectInstallationBootstrap(ComplaintInstallationBootstrapFailure.INVALID_REQUEST)
        }
        requireNotInterrupted()
    }

    private fun single(request: HttpServletRequest, name: String, maximum: Int): String? {
        val values = request.getHeaders(name)
        if (!values.hasMoreElements()) return null
        val value = values.nextElement()
        if (values.hasMoreElements() || value.length > maximum || value.any { it.code !in 32..126 }) {
            rejectInstallationBootstrap(ComplaintInstallationBootstrapFailure.INVALID_REQUEST)
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
            throw IOException(DELIVERY_FAILURE) // Even an uncommitted container buffer may contain a success prefix.
        } catch (failure: OutOfMemoryError) {
            responses.failClosed()
            throw IOException(DELIVERY_FAILURE)
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun problem(request: HttpServletRequest, response: HttpServletResponse, failure: ComplaintInstallationBootstrapFailure, retry: Long? = null) {
        requireNotInterrupted()
        requireConnectionFree() // An unavailable read cannot turn unresolved original custody into HTTP delivery.
        if (response.isCommitted) return
        val bytes = checkNotNull(ComplaintInstallationBootstrapHttpResponses.PROBLEMS[failure])
        try {
            headers(response, failure.status, "application/problem+json", bytes.size)
            if (failure == ComplaintInstallationBootstrapFailure.RATE_LIMITED) retry?.let { response.setHeader("Retry-After", it.toString()) }
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
        requireConnectionFree()
        response.status = status
        response.setHeader("X-Kira-Complaint-Contract", "1")
        response.setHeader("Cache-Control", "no-store, no-transform")
        response.contentType = "$media;charset=UTF-8"
        response.setContentLength(length)
    }

    private fun requireNotInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Installation bootstrap interrupted.")
    }

    override fun toString(): String = "ComplaintInstallationBootstrapHttpHandler(public,GET-only,no-mode-fallback)"

    companion object {
        const val PATH = "/api/v1/installations/bootstrap"
        private const val DELIVERY_FAILURE = "Installation bootstrap delivery failed."
    }
}
