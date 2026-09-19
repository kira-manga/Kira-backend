package me.manga.kira.backend.complaint.domain

import java.time.Instant
import java.util.UUID

/** Syntax and detached results only. None of these views supplies ADMIN or TEST activation authority. */
internal interface ComplaintAdminReadRequestContext

/** Only the concrete adapter recognizes its original short-lived, one-use handoff. */
internal interface ComplaintAdminReadAuthentication

internal interface ComplaintAdminReadPort {
    fun authenticate(context: ComplaintAdminReadRequestContext, bearer: String, query: ComplaintAdminReadQuery): ComplaintAdminReadAuthentication
    fun read(context: ComplaintAdminReadRequestContext, authentication: ComplaintAdminReadAuthentication): ComplaintAdminReadResult
}

internal sealed interface ComplaintAdminReadQuery {
    val scope: ComplaintDataScope
}

@Suppress("LongParameterList") // The approved, closed search schema; not an arbitrary query map.
internal class ComplaintAdminSearchQuery(
    override val scope: ComplaintDataScope,
    text: String = "",
    val status: ComplaintStatus? = null,
    val type: ComplaintType? = null,
    val ownership: ComplaintOwnership? = null,
    val updatedFrom: Instant? = null,
    val updatedBefore: Instant? = null,
    val sort: String = "UPDATED_DESC",
    val limit: Int = 50,
    val cursor: String? = null,
) : ComplaintAdminReadQuery {
    val text: String = searchText(text)

    init {
        if (!scope.testOnly || limit !in 1..50 || sort != "UPDATED_DESC" || status == ComplaintStatus.UNKNOWN ||
            ownership == ComplaintOwnership.LEGACY_UNCLAIMED
        ) rejectAdminRead(ComplaintAdminReadFailure.INVALID_REQUEST)
        if (updatedFrom != null && !adminReadTime(updatedFrom) || updatedBefore != null && !adminReadTime(updatedBefore) ||
            updatedFrom != null && updatedBefore != null && updatedFrom >= updatedBefore
        ) rejectAdminRead(ComplaintAdminReadFailure.INVALID_REQUEST)
        if (cursor != null && (cursor.length > 2048 || !CURSOR.matches(cursor))) rejectAdminRead(ComplaintAdminReadFailure.INVALID_CURSOR)
    }

    override fun toString(): String = "ComplaintAdminSearchQuery(redacted)"

    private companion object {
        val CURSOR = Regex("v1\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")

        @Suppress("SwallowedException")
        fun searchText(value: String): String = try {
            ComplaintTextRules.adminSearch(value)
        } catch (failure: ComplaintValidationException) {
            rejectAdminRead(ComplaintAdminReadFailure.INVALID_REQUEST)
        }
    }
}

internal class ComplaintAdminDetailQuery(override val scope: ComplaintDataScope, val id: UUID) : ComplaintAdminReadQuery {
    init {
        if (!scope.testOnly) rejectAdminRead(ComplaintAdminReadFailure.INVALID_REQUEST)
    }

    override fun toString(): String = "ComplaintAdminDetailQuery(redacted)"
}

/** Notice IDs may be non-v4; ordering is the PostgreSQL unsigned UUID byte order, not UUID.compareTo. */
internal class ComplaintAdminReadPosition(val updatedAt: Instant, val id: UUID) {
    init {
        require(adminReadTime(updatedAt)) { "Invalid Admin read position." }
    }

    override fun toString(): String = "ComplaintAdminReadPosition(redacted)"
}

/** Existing owner content validation is reused; only Admin-safe stored ownership/closure facts are added. */
internal sealed interface ComplaintAdminItem {
    val id: UUID
    val updatedAt: Instant
    val version: Long

    class Content(
        val content: ComplaintOwnerHistoryContent,
        val ownerReference: UUID,
        val closedAt: Instant?,
        val closureProvenance: String?,
        val closureActorId: UUID?,
    ) : ComplaintAdminItem {
        override val id: UUID get() = content.id
        override val updatedAt: Instant get() = content.updatedAt
        override val version: Long get() = content.version

        init {
            require(ownerReference.version() == 4 && ownerReference.variant() == 2) { "Invalid Admin read row." }
            require(adminReadTime(content.createdAt) && adminReadTime(content.updatedAt)) { "Invalid Admin read row." }
            if (content.status == ComplaintStatus.CLOSED) {
                require(closedAt != null && adminReadTime(closedAt) && closureProvenance == "ADMIN" && closureActorId != null) {
                    "Invalid Admin read closure."
                }
            } else {
                require(closedAt == null && closureProvenance == null && closureActorId == null) { "Invalid Admin read closure." }
            }
        }

        override fun toString(): String = "ComplaintAdminItem.Content(redacted)"
    }

    class Notice(val notice: ComplaintOwnerHistoryNotice) : ComplaintAdminItem {
        override val id: UUID get() = notice.id
        override val updatedAt: Instant get() = notice.updatedAt
        override val version: Long get() = notice.version

        init {
            require(adminReadTime(notice.createdAt) && adminReadTime(notice.updatedAt)) { "Invalid Admin read row." }
        }

        override fun toString(): String = "ComplaintAdminItem.Notice(redacted)"
    }
}

internal sealed interface ComplaintAdminReadResult {
    class Page(items: List<ComplaintAdminItem>, val nextCursor: String?) : ComplaintAdminReadResult {
        val items: List<ComplaintAdminItem> = items.toList()

        init {
            require(this.items.size <= 50 && this.items.map { it.id }.toSet().size == this.items.size) { "Invalid Admin read page." }
            require(this.items.zipWithNext().all { (a, b) -> a.updatedAt > b.updatedAt || (a.updatedAt == b.updatedAt && a.id.toString() > b.id.toString()) }) {
                "Invalid Admin read order."
            }
            require(nextCursor == null || (this.items.isNotEmpty() && nextCursor.length <= 2048 && CURSOR.matches(nextCursor))) {
                "Invalid Admin read cursor."
            }
        }

        override fun toString(): String = "ComplaintAdminReadResult.Page(redacted)"

        private companion object {
            val CURSOR = Regex("v1\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")
        }
    }

    class Detail(val item: ComplaintAdminItem) : ComplaintAdminReadResult {
        override fun toString(): String = "ComplaintAdminReadResult.Detail(redacted)"
    }
}

internal enum class ComplaintAdminReadFailure(val status: Int, val code: String, val title: String) {
    INVALID_REQUEST(400, "VALIDATION_FAILED", "Bad Request"),
    INVALID_CURSOR(400, "INVALID_CURSOR", "Bad Request"),
    UNAUTHORIZED(401, "UNAUTHORIZED", "Unauthorized"),
    FORBIDDEN(403, "FORBIDDEN", "Forbidden"),
    NOT_FOUND(404, "NOT_FOUND", "Not Found"),
    TOO_LARGE(413, "PAYLOAD_TOO_LARGE", "Payload Too Large"),
    UNSUPPORTED_MEDIA(415, "UNSUPPORTED_MEDIA_TYPE", "Unsupported Media Type"),
    RATE_LIMITED(429, "RATE_LIMITED", "Too Many Requests"),
    UNAVAILABLE(503, "SERVICE_UNAVAILABLE", "Service Unavailable"),
    INTERNAL(500, "INTERNAL_ERROR", "Internal Server Error"),
}

internal class ComplaintAdminReadRejected(val failure: ComplaintAdminReadFailure) : RuntimeException("Complaint Admin read refused.", null, false, false)

internal fun rejectAdminRead(failure: ComplaintAdminReadFailure): Nothing = throw ComplaintAdminReadRejected(failure)

private fun adminReadTime(value: Instant): Boolean = value in MINIMUM_ADMIN_TIME..MAXIMUM_ADMIN_TIME && value.nano % 1000 == 0

private val MINIMUM_ADMIN_TIME = Instant.parse("0001-01-01T00:00:00Z")
private val MAXIMUM_ADMIN_TIME = Instant.parse("9999-12-31T23:59:59.999999Z")
