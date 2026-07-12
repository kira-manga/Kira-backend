package me.manga.kira.backend.completion.api.dto

import me.manga.kira.backend.completion.domain.CompletionView
import java.time.Instant
import java.util.UUID

/**
 * Completion API DTOs (PLAN §4.6). The request carries only `prompt` (required) and an optional
 * `model`; blank-prompt (400) and over-length (413) are enforced at the controller boundary, not by
 * bean-validation annotations, so the length breach can be a 413 rather than a 400 (PLAN §4.5/§4.6).
 */
data class CompletionRequestDto(
    val prompt: String = "",
    val model: String? = null,
)

/**
 * The completion response (PLAN §4.6). On success `result` is set and `errorCode`/`error` are null; on
 * failure `errorCode` (a stable §10 catalog value) and `error` (a sanitized, generic message) are set
 * and `result` is null. The prompt is never echoed back.
 */
data class CompletionResponse(
    val id: UUID,
    val status: String,
    val model: String,
    val provider: String,
    val result: String?,
    val errorCode: String?,
    val error: String?,
    val createdAt: Instant,
) {
    companion object {
        fun from(view: CompletionView): CompletionResponse =
            CompletionResponse(
                id = view.id,
                status = view.status.name,
                model = view.model,
                provider = view.provider,
                result = view.result,
                errorCode = view.errorCode?.name,
                error = view.error,
                createdAt = view.createdAt,
            )
    }
}
