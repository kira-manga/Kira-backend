package me.manga.kira.backend.complaint.domain

/** Only the concrete reader recognizes its own one-use, current-row-authenticated handoff. */
internal interface ComplaintInstallationMeAuthentication

internal interface ComplaintInstallationMeReadPort {
    fun authenticate(context: ComplaintInstallationRequestContext, bearer: String): ComplaintInstallationMeAuthentication
    fun read(context: ComplaintInstallationRequestContext, authentication: ComplaintInstallationMeAuthentication): ComplaintInstallationMe
}

/** Minimal active projection, not a principal, credential update, or mode/restore authority. */
internal class ComplaintInstallationMe(val installation: ScopedInstallationId, val credentialVersion: Long) {
    init {
        require(credentialVersion > 0) { "Invalid installation projection." }
    }

    override fun toString(): String = "ComplaintInstallationMe(redacted)"
}

internal enum class ComplaintInstallationMeFailure(val status: Int, val code: String, val title: String) {
    INVALID_REQUEST(400, "VALIDATION_FAILED", "Bad Request"),
    UNAUTHORIZED(401, "UNAUTHORIZED", "Unauthorized"),
    NOT_FOUND(404, "NOT_FOUND", "Not Found"),
    UNSUPPORTED_MEDIA(415, "UNSUPPORTED_MEDIA_TYPE", "Unsupported Media Type"),
    RATE_LIMITED(429, "RATE_LIMITED", "Too Many Requests"),
    UNAVAILABLE(503, "SERVICE_UNAVAILABLE", "Service Unavailable"),
    INTERNAL(500, "INTERNAL_ERROR", "Internal Server Error"),
}

/** Fixed diagnostics only: no original exception, token, request, identifier or credential is retained. */
internal class ComplaintInstallationMeRejected(val failure: ComplaintInstallationMeFailure) :
    RuntimeException("Installation read refused.", null, false, false)

internal fun rejectInstallationMe(failure: ComplaintInstallationMeFailure): Nothing = throw ComplaintInstallationMeRejected(failure)
