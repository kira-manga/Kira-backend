package me.manga.kira.backend.completion.application

import me.manga.kira.backend.completion.domain.CompletionErrorCode
import me.manga.kira.backend.completion.domain.CompletionRequestRepository
import me.manga.kira.backend.completion.domain.CompletionResultRecord
import me.manga.kira.backend.completion.domain.CompletionResultRepository
import me.manga.kira.backend.completion.domain.CompletionStatus
import me.manga.kira.backend.completion.domain.CompletionView
import me.manga.kira.backend.completion.domain.PagedCompletions
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * The transactional persistence seam for completions (PLAN §10). It exists as a SEPARATE bean from
 * [CompletionService] precisely so the orchestration is split into three short, independent
 * transactions with the provider call between them — a DB transaction is NEVER held open across the
 * provider call. Calling one `@Transactional` method from another on the SAME bean would bypass the
 * Spring proxy (self-invocation), so [CompletionService] (which is NOT `@Transactional`) invokes these
 * proxied methods in sequence instead.
 *
 * All timestamps come from the injected [Clock] so application and DB time never diverge.
 */
@Component
class CompletionPersistence(private val requests: CompletionRequestRepository, private val results: CompletionResultRepository, private val clock: Clock) {
    /** Transaction 1 — insert the `PENDING` request row and commit; returns its id. */
    @Transactional
    fun createPending(userId: UUID, provider: String, model: String, prompt: String): UUID =
        requests.insertPending(userId, provider, model, prompt, clock.instant())

    /** Transaction 2 — flip the request to `RUNNING` and commit (before the provider call). */
    @Transactional
    fun markRunning(id: UUID) = requests.updateStatus(id, CompletionStatus.RUNNING, clock.instant())

    /**
     * Transaction 4 — store the sanitized outcome and commit: update the request to its terminal
     * [status] and insert the single `completion_results` row. Returns the composed view for the API.
     * On success pass a non-null [result] with null [error]/[errorCode]; on failure the reverse (the DB
     * CHECKs `chk_result_xor_error` / `chk_completion_error_code_pairing` enforce this too, PLAN §5/§10).
     */
    @Transactional
    fun storeOutcome(id: UUID, status: CompletionStatus, result: String?, error: String?, errorCode: CompletionErrorCode?, latencyMs: Int?): CompletionView {
        val now = clock.instant()
        requests.updateStatus(id, status, now)
        val resultRecord =
            CompletionResultRecord(
                requestId = id,
                result = result,
                error = error,
                errorCode = errorCode,
                latencyMs = latencyMs,
                createdAt = now,
            )
        results.insert(resultRecord)
        val request = requireNotNull(requests.findById(id)) { "completion request $id vanished mid-transaction" }
        return CompletionView.of(request, resultRecord)
    }

    /** Load one completion (request + optional outcome). Ownership is enforced by the caller (PLAN §4.6). */
    @Transactional(readOnly = true)
    fun findView(id: UUID): CompletionView? {
        val request = requests.findById(id) ?: return null
        return CompletionView.of(request, results.findByRequestId(id))
    }

    /** A page of a user's completions, newest first, each composed with its outcome (PLAN §4.6). */
    @Transactional(readOnly = true)
    fun listViews(userId: UUID, page: Int, size: Int): PagedCompletions {
        val pageData = requests.findPageByUser(userId, page, size)
        val resultsById = results.findByRequestIds(pageData.items.map { it.id })
        return PagedCompletions(
            items = pageData.items.map { CompletionView.of(it, resultsById[it.id]) },
            total = pageData.total,
        )
    }
}
