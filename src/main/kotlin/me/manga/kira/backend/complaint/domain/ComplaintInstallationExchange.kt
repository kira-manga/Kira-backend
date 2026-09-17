package me.manga.kira.backend.complaint.domain

import java.time.Instant

/** Only the existing ingress owner recognizes a live context; this view grants nothing. */
internal interface ComplaintInstallationRequestContext

internal interface ComplaintInstallationExchange {
    fun enroll(context: ComplaintInstallationRequestContext, candidate: InstallationEnrollmentCandidate): ComplaintInstallationEnrollmentResponse
    fun session(context: ComplaintInstallationRequestContext, candidate: InstallationSessionCandidate): ComplaintInstallationSessionResponse
}

internal class ComplaintInstallationEnrollmentResponse(val disposition: InstallationEnrollmentDisposition, val session: ComplaintInstallationSessionResponse) {
    override fun toString(): String = "ComplaintInstallationEnrollmentResponse(redacted)"
}

/** Output metadata only, not a principal or mode/restore grant. Tokens remain memory-only. */
internal class ComplaintInstallationSessionResponse(
    val installation: ScopedInstallationId,
    val credentialVersion: Long,
    val accessToken: String,
    val issuedAt: Instant,
) {
    init {
        require(credentialVersion > 0 && accessToken.length + 7 <= 4096 && COMPACT.matches(accessToken)) { "Invalid installation response." }
        require(issuedAt.nano == 0 && issuedAt in MINIMUM_TIME..MAXIMUM_TIME) { "Invalid installation response." }
    }

    override fun toString(): String = "ComplaintInstallationSessionResponse(redacted)"

    companion object {
        const val EXPIRES_IN_SECONDS = 900L
        private val COMPACT = Regex("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")
        private val MINIMUM_TIME = Instant.parse("0001-01-01T00:00:00Z")
        private val MAXIMUM_TIME = Instant.parse("9999-12-31T23:59:59Z")
    }
}

internal enum class ComplaintInstallationHttpFailure(val status: Int, val code: String, val title: String) {
    INVALID_REQUEST(400, "VALIDATION_FAILED", "Bad Request"),
    NOT_FOUND(404, "NOT_FOUND", "Not Found"),
    PAYLOAD_TOO_LARGE(413, "PAYLOAD_TOO_LARGE", "Payload Too Large"),
    UNSUPPORTED_MEDIA(415, "UNSUPPORTED_MEDIA_TYPE", "Unsupported Media Type"),
    INSTALLATION_NOT_FOUND(404, "INSTALLATION_NOT_FOUND", "Not Found"),
    INSTALLATION_CREDENTIAL_REJECTED(403, "INSTALLATION_CREDENTIAL_REJECTED", "Forbidden"),
    INSTALLATION_PLATFORM_MISMATCH(409, "INSTALLATION_PLATFORM_MISMATCH", "Conflict"),
    INSTALLATION_SCOPE_MISMATCH(409, "INSTALLATION_SCOPE_MISMATCH", "Conflict"),
    INSTALLATION_DELETION_PENDING(409, "INSTALLATION_DELETION_PENDING", "Conflict"),
    INSTALLATION_SCOPE_RETIRED(410, "INSTALLATION_SCOPE_RETIRED", "Gone"),
    INSTALLATION_RETIRED(410, "INSTALLATION_RETIRED", "Gone"),
    INSTALLATION_DELETED(410, "INSTALLATION_DELETED", "Gone"),
    RATE_LIMITED(429, "RATE_LIMITED", "Too Many Requests"),
    UNAVAILABLE(503, "SERVICE_UNAVAILABLE", "Service Unavailable"),
    INTERNAL(500, "INTERNAL_ERROR", "Internal Server Error"),
}

/** Fixed diagnostics only: no input, token, verifier or originating exception is retained. */
internal class ComplaintInstallationHttpRejected(val failure: ComplaintInstallationHttpFailure, val retryAfterSeconds: Long? = null) :
    RuntimeException("Installation request refused.", null, false, false) {
    init {
        require((failure == ComplaintInstallationHttpFailure.RATE_LIMITED) == (retryAfterSeconds != null))
        require(retryAfterSeconds == null || retryAfterSeconds > 0)
    }
}

internal fun rejectInstallationHttp(failure: ComplaintInstallationHttpFailure, retryAfterSeconds: Long? = null): Nothing =
    throw ComplaintInstallationHttpRejected(failure, retryAfterSeconds)
