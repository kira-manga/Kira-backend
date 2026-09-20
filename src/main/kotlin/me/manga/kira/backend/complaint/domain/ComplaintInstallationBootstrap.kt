package me.manga.kira.backend.complaint.domain

/** The concrete producer owns registration, current-state reads and their committed physical release. */
internal fun interface ComplaintInstallationBootstrapReadPort {
    fun read(context: ComplaintInstallationRequestContext): ComplaintInstallationBootstrap
}

/** Closed wire data only, not registration, an installation credential or permission to enroll. */
internal class ComplaintInstallationBootstrap(val scope: ComplaintDataScope) {
    override fun toString(): String = "ComplaintInstallationBootstrap(redacted)"

    companion object {
        const val CONTRACT_VERSION = 1
    }
}

internal enum class ComplaintInstallationBootstrapFailure(val status: Int, val code: String, val title: String) {
    INVALID_REQUEST(400, "VALIDATION_FAILED", "Bad Request"),
    NOT_FOUND(404, "NOT_FOUND", "Not Found"),
    UNSUPPORTED_MEDIA(415, "UNSUPPORTED_MEDIA_TYPE", "Unsupported Media Type"),
    RATE_LIMITED(429, "RATE_LIMITED", "Too Many Requests"),
    UNAVAILABLE(503, "SERVICE_UNAVAILABLE", "Service Unavailable"),
}

/** No request, identifier, provider diagnostic or inherited cause crosses the public boundary. */
internal class ComplaintInstallationBootstrapRejected(val failure: ComplaintInstallationBootstrapFailure) :
    RuntimeException("Installation bootstrap refused.", null, false, false)

internal fun rejectInstallationBootstrap(failure: ComplaintInstallationBootstrapFailure): Nothing = throw ComplaintInstallationBootstrapRejected(failure)
