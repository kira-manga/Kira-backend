package me.manga.kira.backend.completion.domain

import java.util.UUID

/**
 * Port for the `completion_results` table (PLAN §2 DIP / §10). One outcome row per request; the stored
 * `error` is always the sanitized client-visible message, never a raw provider exception (PLAN §10).
 */
interface CompletionResultRepository {
    fun insert(result: CompletionResultRecord)

    fun findByRequestId(requestId: UUID): CompletionResultRecord?

    /** Batch-load outcomes for a page of requests (avoids N+1 on the list endpoint). */
    fun findByRequestIds(requestIds: List<UUID>): Map<UUID, CompletionResultRecord>
}
