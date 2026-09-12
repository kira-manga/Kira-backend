package me.manga.kira.backend.sourceconfig.domain

import java.time.Instant
import java.util.UUID

/** Metadata only: history reads must not materialize immutable config or validation finding JSON. */
data class SourceRevisionSummary(
    val revisionNumber: Int,
    val status: RevisionStatus,
    val checksum: String,
    val createdBy: UUID,
    val createdAt: Instant,
    val publishedAt: Instant?,
    val valid: Boolean?,
)

data class PublishedDocumentSummary(
    val documentRevision: Long,
    val schemaVersion: Int,
    val checksum: String,
    val sourceCount: Int,
    val createdBy: UUID,
    val createdAt: Instant,
)

/** An ascending history window; the exclusive cursor exists only when older rows remain. */
data class HistoryWindow<T>(val items: List<T>, val nextBeforeRevision: Long?) {
    companion object {
        const val DEFAULT_SIZE = 20
        const val MAX_SIZE = 100

        fun fetchLimit(size: Int): Int {
            require(size in 1..MAX_SIZE)
            return size + 1
        }

        fun <T> fromDescending(rows: List<T>, size: Int, revision: (T) -> Long): HistoryWindow<T> {
            val retained = rows.take(size)
            val next = if (rows.size > size) revision(retained.last()) else null
            return HistoryWindow(retained.asReversed(), next)
        }
    }
}
