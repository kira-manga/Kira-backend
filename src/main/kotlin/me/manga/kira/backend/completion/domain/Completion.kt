package me.manga.kira.backend.completion.domain

import java.time.Instant
import java.util.UUID

/**
 * Immutable domain records for the completion feature (PLAN §10). These are pure Kotlin — they never
 * carry JPA/Spring types — mirroring the `:domain` discipline of the rest of the backend.
 */

/** A `completion_requests` row. [prompt] never leaves this record into a log or audit row (PLAN §10). */
data class CompletionRequestRecord(
    val id: UUID,
    val userId: UUID,
    val provider: String,
    val model: String,
    val prompt: String,
    val status: CompletionStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** A `completion_results` row (the sanitized outcome). Exactly one per finished request. */
data class CompletionResultRecord(
    val requestId: UUID,
    val result: String?,
    val error: String?,
    val errorCode: CompletionErrorCode?,
    val latencyMs: Int?,
    val createdAt: Instant,
)

/** A page of request rows from the repository (composed into [CompletionView]s by the service). */
data class CompletionRequestPage(
    val items: List<CompletionRequestRecord>,
    val total: Long,
)

/**
 * The composite read model returned to the API (PLAN §4.6): the request plus its optional outcome. On
 * success [result] is set and [error]/[errorCode] are null; on failure [error]/[errorCode] are set and
 * [result] is null; while `RUNNING` all three are null. The [prompt] is deliberately absent — it is
 * never echoed back.
 */
data class CompletionView(
    val id: UUID,
    val userId: UUID,
    val provider: String,
    val model: String,
    val status: CompletionStatus,
    val result: String?,
    val error: String?,
    val errorCode: CompletionErrorCode?,
    val createdAt: Instant,
) {
    companion object {
        fun of(
            request: CompletionRequestRecord,
            result: CompletionResultRecord?,
        ): CompletionView =
            CompletionView(
                id = request.id,
                userId = request.userId,
                provider = request.provider,
                model = request.model,
                status = request.status,
                result = result?.result,
                error = result?.error,
                errorCode = result?.errorCode,
                createdAt = request.createdAt,
            )
    }
}

/** A page of composed completion views for the list endpoint (PLAN §4.6). */
data class PagedCompletions(
    val items: List<CompletionView>,
    val total: Long,
)
