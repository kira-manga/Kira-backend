package me.manga.kira.backend.complaint.api

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpServletResponseWrapper
import me.manga.kira.backend.common.web.RequestBodySizeLimitFilter
import me.manga.kira.backend.complaint.application.ComplaintOwnerDeleteAllService
import me.manga.kira.backend.complaint.domain.ComplaintIdentifiers
import me.manga.kira.backend.complaint.domain.ComplaintInstallationHttpFailure
import me.manga.kira.backend.complaint.domain.ComplaintInstallationHttpRejected
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteAllResponse
import me.manga.kira.backend.complaint.domain.ComplaintValidationException
import me.manga.kira.backend.complaint.domain.rejectInstallationHttp
import me.manga.kira.backend.complaint.parsing.InstallationDeletionRequestFailure
import me.manga.kira.backend.complaint.parsing.InstallationDeletionRequestParser
import me.manga.kira.backend.complaint.parsing.InstallationDeletionRequestRejected
import me.manga.kira.backend.security.ComplaintAdmissionRejected
import me.manga.kira.backend.security.ComplaintIngressAdmission
import me.manga.kira.backend.security.ComplaintIngressContext
import org.springframework.web.HttpRequestHandler
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** Unregistered synchronous adapter only. No mode authority, session refresh, response token or background retry. */
internal class ComplaintOwnerDeleteAllHttpHandler(private val service: ComplaintOwnerDeleteAllService, private val ingress: ComplaintIngressAdmission) :
    HttpRequestHandler {
    private val bodyFilter = RequestBodySizeLimitFilter(ObjectMapper())
    private val closed = AtomicBoolean()

    override fun handleRequest(request: HttpServletRequest, response: HttpServletResponse) = responseBoundary(request, response) {
        ingress.withIngress(request) { context -> exchangeHttp(request, response, context) }
    }

    /** The qualified chain's concrete bridge already owns this exact ingress; never open or renew it. */
    internal fun handleWithinIngress(request: HttpServletRequest, response: HttpServletResponse, context: ComplaintIngressContext) =
        responseBoundary(request, response) {
            ingress.requireLiveContext(context)
            exchangeHttp(request, response, context)
        }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // Only the final closed bug boundary is generic; signals and delivery never become problems.
    private fun responseBoundary(request: HttpServletRequest, response: HttpServletResponse, operation: () -> Unit) {
        try {
            requireNotInterrupted()
            operation()
        } catch (failure: ComplaintInstallationHttpRejected) {
            if (failure.failure == ComplaintInstallationHttpFailure.INTERNAL) closed.set(true)
            problem(request, response, failure.failure, failure.retryAfterSeconds)
        } catch (failure: ComplaintAdmissionRejected) {
            val kind = if (failure.status == 429) ComplaintInstallationHttpFailure.RATE_LIMITED else ComplaintInstallationHttpFailure.UNAVAILABLE
            problem(request, response, kind, failure.retryAfterSeconds)
        } catch (failure: InstallationDeletionRequestRejected) {
            val kind = when (failure.failure) {
                InstallationDeletionRequestFailure.MALFORMED_REQUEST -> ComplaintInstallationHttpFailure.INVALID_REQUEST
                InstallationDeletionRequestFailure.PAYLOAD_TOO_LARGE -> ComplaintInstallationHttpFailure.PAYLOAD_TOO_LARGE
            }
            problem(request, response, kind)
        } catch (_: CancellationException) {
            throw CancellationException("Installation deletion cancelled.")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedException("Installation deletion interrupted.")
        } catch (_: InterruptedIOException) {
            throw InterruptedIOException(DELIVERY_FAILURE)
        } catch (_: IOException) {
            throw IOException(DELIVERY_FAILURE)
        } catch (_: RuntimeException) {
            closed.set(true)
            problem(request, response, ComplaintInstallationHttpFailure.INTERNAL)
        }
    }

    private fun exchangeHttp(request: HttpServletRequest, response: HttpServletResponse, context: ComplaintIngressContext) {
        if (request.method != "POST" || request.requestURI != request.contextPath + PATH) rejectInstallationHttp(ComplaintInstallationHttpFailure.NOT_FOUND)
        if (request.queryString != null) rejectInstallationHttp(ComplaintInstallationHttpFailure.INVALID_REQUEST)
        singleHeader(request, "Authorization", 4096) // Body-secret route: bounded, never authenticated as a user or installation bearer.
        val contract = singleHeader(request, "X-Kira-Complaint-Contract", 64)
        if (contract != null && contract != "1") rejectInstallationHttp(ComplaintInstallationHttpFailure.INVALID_REQUEST)
        val key = idempotencyKey(request)
        if (closed.get()) rejectInstallationHttp(ComplaintInstallationHttpFailure.UNAVAILABLE)
        val bytes = readBody(request, response)
        val candidate = try {
            InstallationDeletionRequestParser.parse(bytes, key)
        } finally {
            // This parser copy is ours. The outer filter's separate replay buffer is not claimed to be wiped.
            bytes.fill(0)
        }
        when (service.deleteAll(context, candidate)) {
            is ComplaintOwnerDeleteAllResponse.Completed -> deliverEmpty(response, pending = false)
            is ComplaintOwnerDeleteAllResponse.Pending -> deliverEmpty(response, pending = true)
        }
    }

    @Suppress("SwallowedException") // Only the actual framing/bounded-input extent maps an ordinary IOException to malformed input.
    private fun readBody(request: HttpServletRequest, response: HttpServletResponse): ByteArray {
        var bytes: ByteArray? = null
        try {
            bodyFilter.doFilter(request, BodyFailureResponse(response)) { bounded, _ ->
                bytes = (bounded as HttpServletRequest).inputStream.readNBytes(InstallationDeletionRequestParser.MAX_BODY_BYTES + 1)
            }
        } catch (_: InterruptedIOException) {
            throw InterruptedIOException(DELIVERY_FAILURE)
        } catch (_: IOException) {
            requireNotInterrupted()
            rejectInstallationHttp(ComplaintInstallationHttpFailure.INVALID_REQUEST)
        }
        return checkNotNull(bytes)
    }

    @Suppress("SwallowedException") // Use the existing canonical identifier rule without retaining input-bearing diagnostics.
    private fun idempotencyKey(request: HttpServletRequest): String {
        val key = singleHeader(request, "X-Kira-Idempotency-Key", 36) ?: rejectInstallationHttp(ComplaintInstallationHttpFailure.INVALID_REQUEST)
        try {
            ComplaintIdentifiers.idempotencyKey(key)
        } catch (_: ComplaintValidationException) {
            rejectInstallationHttp(ComplaintInstallationHttpFailure.INVALID_REQUEST)
        }
        return key
    }

    private fun singleHeader(request: HttpServletRequest, name: String, maximum: Int): String? {
        val values = request.getHeaders(name)
        if (!values.hasMoreElements()) return null
        val value = values.nextElement()
        if (values.hasMoreElements() || value.length > maximum || value.any { it.code !in 32..126 }) {
            rejectInstallationHttp(ComplaintInstallationHttpFailure.INVALID_REQUEST)
        }
        return value
    }

    // Once delivery starts, even an uncommitted container buffer must never receive a second response.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun deliverEmpty(response: HttpServletResponse, pending: Boolean) {
        requireResponseReady(response)
        try {
            headers(response, if (pending) 202 else 204)
            if (pending) {
                response.setHeader("Retry-After", "1")
                response.setContentLength(0)
            }
            // In particular, 204 acquires no output stream, representation metadata or Content-Length.
        } catch (_: CancellationException) {
            throw CancellationException("Installation deletion cancelled.")
        } catch (_: InterruptedIOException) {
            throw InterruptedIOException(DELIVERY_FAILURE)
        } catch (_: IOException) {
            throw IOException(DELIVERY_FAILURE)
        } catch (_: RuntimeException) {
            closed.set(true)
            throw IOException(DELIVERY_FAILURE)
        } catch (_: OutOfMemoryError) {
            closed.set(true)
            throw IOException(DELIVERY_FAILURE)
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // A failed problem send also escapes without a fallback response or repeated continuation.
    private fun problem(request: HttpServletRequest, response: HttpServletResponse, failure: ComplaintInstallationHttpFailure, retry: Long? = null) {
        requireResponseReady(response)
        val bytes = checkNotNull(ComplaintInstallationHttpResponses.PROBLEMS[failure])
        try {
            headers(response, failure.status)
            response.contentType = "application/problem+json;charset=UTF-8"
            response.setContentLength(bytes.size)
            retry?.let { response.setHeader("Retry-After", it.toString()) }
            if (request.method != "HEAD") response.outputStream.write(bytes)
        } catch (_: CancellationException) {
            throw CancellationException("Installation deletion cancelled.")
        } catch (_: InterruptedIOException) {
            throw InterruptedIOException(DELIVERY_FAILURE)
        } catch (_: IOException) {
            throw IOException(DELIVERY_FAILURE)
        } catch (_: RuntimeException) {
            closed.set(true)
            throw IOException(DELIVERY_FAILURE)
        } catch (_: OutOfMemoryError) {
            closed.set(true)
            throw IOException(DELIVERY_FAILURE)
        }
    }

    private fun requireResponseReady(response: HttpServletResponse) {
        ingress.requireResponseReady()
        requireNotInterrupted()
        if (response.isCommitted) throw IOException(DELIVERY_FAILURE)
    }

    private fun requireNotInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Installation deletion interrupted.")
    }

    private fun headers(response: HttpServletResponse, status: Int) {
        response.status = status
        response.setHeader("X-Kira-Complaint-Contract", "1")
        response.setHeader("Cache-Control", "no-store, no-transform")
    }

    override fun toString(): String = "ComplaintOwnerDeleteAllHttpHandler(dormant,POST-only)"

    /** Convert the existing guard's finite rejection before its response prefix can escape. */
    private class BodyFailureResponse(response: HttpServletResponse) : HttpServletResponseWrapper(response) {
        override fun setHeader(name: String, value: String?) = Unit

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
        const val PATH = "/api/v1/installations/delete-all"
        const val DELIVERY_FAILURE = "Installation deletion HTTP exchange failed."
    }
}
