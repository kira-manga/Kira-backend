package me.manga.kira.backend.complaint.infrastructure

import me.manga.kira.backend.complaint.domain.ComplaintIdentifiers
import me.manga.kira.backend.complaint.domain.ComplaintType
import java.sql.ResultSet

/** Bounded inherited fields, not authority. Only the retained creation operation runs/locks these queries. */
internal class ComplaintOwnerReplyParentSnapshot(val type: ComplaintType, val subject: String?, val noticeKey: String?) {
    override fun toString(): String = "ComplaintOwnerReplyParentSnapshot(redacted)"
}

internal object ComplaintOwnerReplyParentRows {
    private const val ELIGIBLE = """
        FROM complaints c WHERE c.id = ? AND c.data_scope_id = ? AND c.test_only
            AND ((c.ownership = 'INSTALLATION' AND c.owner_id = ? AND c.kind IN ('REPORT', 'REPLY'))
                OR (c.ownership = 'SYSTEM' AND c.owner_id IS NULL AND c.kind = 'NOTICE' AND c.status = 'PINNED'))
    """
    val candidate = "SELECT EXISTS (SELECT 1 $ELIGIBLE)"
    val content = """
        SELECT c.kind, c.type, c.notice_key,
            CASE WHEN octet_length(c.subject) <= 800 THEN c.subject END AS subject
        $ELIGIBLE FOR UPDATE OF c
    """.trimIndent()
    const val RESOURCE = "SELECT state FROM complaint_resource_ids WHERE id = ? AND data_scope_id = ? AND test_only FOR UPDATE"

    fun read(row: ResultSet): ComplaintOwnerReplyParentSnapshot {
        val noticeKey = row.getString("notice_key")?.let(ComplaintIdentifiers::noticeKey)
        val subject = row.getString("subject")
        val type = row.getString("type")?.let(ComplaintType::valueOf)
        if (row.getString("kind") == "NOTICE") {
            check(noticeKey != null && subject == null && type == null)
            return ComplaintOwnerReplyParentSnapshot(ComplaintType.CUSTOM, null, noticeKey)
        }
        if (noticeKey != null) {
            check(row.getString("kind") == "REPLY" && type === ComplaintType.CUSTOM && subject == null)
        } else {
            check(subject != null)
        }
        return ComplaintOwnerReplyParentSnapshot(checkNotNull(type), subject, noticeKey)
    }
}
