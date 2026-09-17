package me.manga.kira.backend.complaint.domain

import java.time.Instant
import java.util.UUID

/** The selected HTTP composition is still closed. These types carry no mode/restore authority. */
internal interface ComplaintOwnerHistoryRequestContext

/** Only the concrete reader recognizes its own short-lived, one-use authenticated attempt. */
internal interface ComplaintOwnerHistoryAuthentication

internal interface ComplaintOwnerHistoryReadPort {
    fun authenticate(context: ComplaintOwnerHistoryRequestContext, bearer: String, query: ComplaintOwnerHistoryQuery): ComplaintOwnerHistoryAuthentication
    fun read(context: ComplaintOwnerHistoryRequestContext, authentication: ComplaintOwnerHistoryAuthentication): ComplaintOwnerHistoryPage
}

internal class ComplaintOwnerHistoryQuery(val limit: Int, val cursor: String?) {
    init {
        if (limit !in 1..50) rejectOwnerHistory(ComplaintOwnerHistoryFailure.INVALID_REQUEST)
        if (cursor != null && (cursor.length > 2048 || !CURSOR.matches(cursor))) rejectOwnerHistory(ComplaintOwnerHistoryFailure.INVALID_CURSOR)
    }

    override fun toString(): String = "ComplaintOwnerHistoryQuery(redacted)"

    private companion object {
        val CURSOR = Regex("v1\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")
    }
}

internal class ComplaintOwnerHistoryPosition(val createdAt: Instant, val id: UUID) {
    init {
        require(id.variant() == 2 && id.version() == 4 && createdAt in MINIMUM_TIME..MAXIMUM_TIME) { "Invalid history position." }
    }

    override fun toString(): String = "ComplaintOwnerHistoryPosition(redacted)"
}

@Suppress("LongParameterList") // Closed owner DTO projection; no arbitrary field map at the output boundary.
internal class ComplaintOwnerHistoryContent(
    val id: UUID,
    val kind: ComplaintKind,
    val type: ComplaintType,
    val subject: String?,
    val body: String,
    val status: ComplaintStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
    val appVersion: String?,
    val platform: ComplaintPlatform,
    val osVersion: String,
    val manufacturer: String,
    val deviceModel: String,
    val closureReason: String?,
    val replyToId: UUID?,
    val noticeKey: String?,
) {
    init {
        require(ComplaintIdentifiers.clientResourceId(id.toString()) == id && version > 0) { "Invalid history row." }
        require(kind == ComplaintKind.REPORT || kind == ComplaintKind.REPLY) { "Invalid history row." }
        require((kind == ComplaintKind.REPLY) == (replyToId != null)) { "Invalid history row." }
        require(replyToId != id && status != ComplaintStatus.UNKNOWN) { "Invalid history row." }
        require(ComplaintTextRules.editedBody(body) == body) { "Invalid history row." }
        if (noticeKey == null) {
            require(subject != null && ComplaintTextRules.subject(subject) == subject) { "Invalid history row." }
        } else {
            require(kind == ComplaintKind.REPLY && type == ComplaintType.CUSTOM && subject == null) { "Invalid history row." }
            ComplaintIdentifiers.noticeKey(noticeKey)
        }
        require((status == ComplaintStatus.CLOSED) == (closureReason != null)) { "Invalid history row." }
        closureReason?.let { require(ComplaintTextRules.closureReason(it) == it) { "Invalid history row." } }
        require(ComplaintTextRules.appVersion(appVersion) == appVersion) { "Invalid history row." }
        require(ComplaintTextRules.osVersion(osVersion) == osVersion) { "Invalid history row." }
        require(ComplaintTextRules.manufacturer(manufacturer) == manufacturer) { "Invalid history row." }
        require(ComplaintTextRules.deviceModel(deviceModel) == deviceModel) { "Invalid history row." }
        historyTimes(createdAt, updatedAt)
    }

    override fun toString(): String = "ComplaintOwnerHistoryContent(redacted)"
}

internal class ComplaintOwnerHistoryNotice(val id: UUID, val noticeKey: String, val createdAt: Instant, val updatedAt: Instant, val version: Long) {
    init {
        ComplaintIdentifiers.noticeKey(noticeKey)
        require(version > 0) { "Invalid history row." }
        historyTimes(createdAt, updatedAt)
    }

    override fun toString(): String = "ComplaintOwnerHistoryNotice(redacted)"
}

internal class ComplaintOwnerHistoryPage(notices: List<ComplaintOwnerHistoryNotice>, items: List<ComplaintOwnerHistoryContent>, val nextCursor: String?) {
    val notices: List<ComplaintOwnerHistoryNotice> = notices.toList()
    val items: List<ComplaintOwnerHistoryContent> = items.toList()

    init {
        require(this.items.size <= 50 && this.notices.size <= 16) { "Invalid history page." }
        val ids = this.items.map { it.id } + this.notices.map { it.id }
        require(ids.toSet().size == ids.size && this.notices.map { it.noticeKey }.toSet().size == this.notices.size) { "Invalid history page." }
        require(
            this.items.zipWithNext().all { (a, b) -> a.createdAt > b.createdAt || a.createdAt == b.createdAt && a.id.toString() > b.id.toString() },
        ) { "Invalid history page." }
        if (nextCursor != null) {
            require(this.items.isNotEmpty()) { "Invalid history page." }
            ComplaintOwnerHistoryQuery(50, nextCursor)
        }
    }

    override fun toString(): String = "ComplaintOwnerHistoryPage(redacted)"
}

internal enum class ComplaintOwnerHistoryFailure(val status: Int, val code: String, val title: String) {
    INVALID_REQUEST(400, "VALIDATION_FAILED", "Bad Request"),
    INVALID_CURSOR(400, "INVALID_CURSOR", "Bad Request"),
    UNAUTHORIZED(401, "UNAUTHORIZED", "Unauthorized"),
    NOT_FOUND(404, "NOT_FOUND", "Not Found"),
    UNSUPPORTED_MEDIA(415, "UNSUPPORTED_MEDIA_TYPE", "Unsupported Media Type"),
    RATE_LIMITED(429, "RATE_LIMITED", "Too Many Requests"),
    UNAVAILABLE(503, "SERVICE_UNAVAILABLE", "Service Unavailable"),
    INTERNAL(500, "INTERNAL_ERROR", "Internal Server Error"),
}

/** No original cause, content, identifiers, cursor or JWT reaches an exception/log boundary. */
internal class ComplaintOwnerHistoryRejected(val failure: ComplaintOwnerHistoryFailure) :
    RuntimeException("Complaint history refused.", null, false, false)

internal fun rejectOwnerHistory(failure: ComplaintOwnerHistoryFailure): Nothing = throw ComplaintOwnerHistoryRejected(failure)

private fun historyTimes(createdAt: Instant, updatedAt: Instant) {
    require(createdAt >= MINIMUM_TIME && updatedAt <= MAXIMUM_TIME) { "Invalid history row." }
    require(updatedAt >= createdAt) { "Invalid history row." }
}

private val MINIMUM_TIME = Instant.parse("0001-01-01T00:00:00Z")
private val MAXIMUM_TIME = Instant.parse("9999-12-31T23:59:59.999999999Z")
