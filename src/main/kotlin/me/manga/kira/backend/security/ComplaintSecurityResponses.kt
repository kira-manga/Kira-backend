package me.manga.kira.backend.security

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import java.io.IOException

internal enum class ComplaintSecurityFailure(val status: Int, val code: String, val title: String) {
    UNAUTHORIZED(401, "UNAUTHORIZED", "Unauthorized"),
    FORBIDDEN(403, "FORBIDDEN", "Forbidden"),
    NOT_FOUND(404, "NOT_FOUND", "Not Found"),
    RATE_LIMITED(429, "RATE_LIMITED", "Too Many Requests"),
    UNAVAILABLE(503, "SERVICE_UNAVAILABLE", "Service Unavailable"),
    INTERNAL(500, "INTERNAL_ERROR", "Internal Server Error"),
}

/** Fixed diagnostics only. In particular, a persistence exception never becomes a bearer credential error. */
internal class ComplaintSecurityRejected(val failure: ComplaintSecurityFailure) : RuntimeException("Complaint security refused.", null, false, false)

internal object ComplaintSecurityResponses {
    private val problems = ComplaintSecurityFailure.entries.associateWith { failure ->
        (
            """{"type":"about:blank","title":"${failure.title}","status":${failure.status},"errors":[""" +
                """{"code":"${failure.code}","message":"Complaint request refused."}]}"""
            ).toByteArray(Charsets.UTF_8).also { check(it.size <= 512) }
    }

    fun headers(response: HttpServletResponse) {
        requireConnectionFree()
        response.setHeader("X-Kira-Complaint-Contract", "1")
        response.setHeader("Cache-Control", "no-store, no-transform")
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun problem(request: HttpServletRequest, response: HttpServletResponse, failure: ComplaintSecurityFailure, retry: Long? = null) {
        requireConnectionFree()
        if (response.isCommitted) throw IOException(DELIVERY_FAILURE)
        val bytes = checkNotNull(problems[failure])
        try {
            headers(response)
            response.status = failure.status
            response.contentType = "application/problem+json;charset=UTF-8"
            response.setContentLength(bytes.size)
            if (failure == ComplaintSecurityFailure.UNAUTHORIZED) response.setHeader("WWW-Authenticate", "Bearer realm=\"kira-complaints\"")
            retry?.let { response.setHeader("Retry-After", it.toString()) }
            if (request.method != "HEAD") response.outputStream.write(bytes)
        } catch (failure: IOException) {
            throw IOException(DELIVERY_FAILURE)
        } catch (failure: RuntimeException) {
            throw IOException(DELIVERY_FAILURE)
        } catch (failure: OutOfMemoryError) {
            throw IOException(DELIVERY_FAILURE)
        }
    }

    const val DELIVERY_FAILURE = "Complaint security delivery failed."
}
