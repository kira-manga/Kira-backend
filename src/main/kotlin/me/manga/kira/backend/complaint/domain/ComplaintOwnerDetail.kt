package me.manga.kira.backend.complaint.domain

import java.util.UUID

/** Explicit dormant owner read only. Neither this context nor an authentication view grants authority. */
internal interface ComplaintOwnerDetailRequestContext

internal interface ComplaintOwnerDetailAuthentication

internal interface ComplaintOwnerDetailReadPort {
    fun authenticate(context: ComplaintOwnerDetailRequestContext, bearer: String, id: UUID): ComplaintOwnerDetailAuthentication
    fun read(context: ComplaintOwnerDetailRequestContext, authentication: ComplaintOwnerDetailAuthentication): ComplaintOwnerDetail
}

/** Exactly one existing closed owner projection, never a list/page or a field map. */
internal sealed interface ComplaintOwnerDetail {
    class Content(val item: ComplaintOwnerHistoryContent) : ComplaintOwnerDetail {
        override fun toString(): String = "ComplaintOwnerDetail.Content(redacted)"
    }

    class Notice(val item: ComplaintOwnerHistoryNotice) : ComplaintOwnerDetail {
        override fun toString(): String = "ComplaintOwnerDetail.Notice(redacted)"
    }
}

internal enum class ComplaintOwnerDetailFailure(val status: Int, val code: String, val title: String) {
    INVALID_REQUEST(400, "VALIDATION_FAILED", "Bad Request"),
    UNAUTHORIZED(401, "UNAUTHORIZED", "Unauthorized"),
    NOT_FOUND(404, "NOT_FOUND", "Not Found"),
    UNSUPPORTED_MEDIA(415, "UNSUPPORTED_MEDIA_TYPE", "Unsupported Media Type"),
    RATE_LIMITED(429, "RATE_LIMITED", "Too Many Requests"),
    UNAVAILABLE(503, "SERVICE_UNAVAILABLE", "Service Unavailable"),
    INTERNAL(500, "INTERNAL_ERROR", "Internal Server Error"),
}

/** No original exception, content, identifier or bearer is retained. */
internal class ComplaintOwnerDetailRejected(val failure: ComplaintOwnerDetailFailure) : RuntimeException("Complaint detail refused.", null, false, false)

internal fun rejectOwnerDetail(failure: ComplaintOwnerDetailFailure): Nothing = throw ComplaintOwnerDetailRejected(failure)
