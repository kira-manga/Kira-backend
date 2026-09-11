package me.manga.kira.backend.completion.application

import me.manga.kira.backend.completion.domain.CompletionErrorCode
import me.manga.kira.backend.completion.domain.CompletionRequestRepository
import me.manga.kira.backend.completion.domain.CompletionResultRecord
import me.manga.kira.backend.completion.domain.CompletionResultRepository
import me.manga.kira.backend.completion.domain.CompletionStatus
import me.manga.kira.backend.completion.domain.CompletionView
import me.manga.kira.backend.completion.domain.PagedCompletions
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Isolation
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

    /** Transaction 2 — claim PENDING → RUNNING. Only a successful proxy return acknowledges commit. */
    @Transactional
    fun markRunning(id: UUID): Boolean = requests.tryMarkRunning(id, clock.instant())

    /**
     * Transaction 4 — store the sanitized outcome and commit: update the request to its terminal
     * [status] and insert the single `completion_results` row only if the conditional update wins.
     * A losing attempt returns the validated existing winner without writing anything.
     * On success pass a non-null [result] with null [error]/[errorCode]; on failure the reverse (the DB
     * CHECKs `chk_result_xor_error` / `chk_completion_error_code_pairing` enforce this too, PLAN §5/§10).
     */
    @Transactional
    fun storeOutcome(
        id: UUID,
        status: CompletionStatus,
        result: String?,
        error: String?,
        errorCode: CompletionErrorCode?,
        latencyMs: Int?,
    ): CompletionPublication {
        checkOutcome(status, result, error, errorCode)
        val now = clock.instant()
        if (!requests.tryFinish(id, status, now)) return existingWinner(id)
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
        return CompletionPublication(CompletionView.of(request, resultRecord), won = true)
    }

    // Deliberately not a self-call to findView: REQUIRED would not upgrade this writer's isolation.
    private fun existingWinner(id: UUID): CompletionPublication {
        val request = checkNotNull(requests.findById(id)) { "completion request is no longer available" }
        val result = checkNotNull(results.findByRequestId(id)) { "completion winner has no outcome" }
        check(result.requestId == id) { "completion outcome belongs to another request" }
        checkOutcome(request.status, result.result, result.error, result.errorCode)
        return CompletionPublication(CompletionView.of(request, result), won = false)
    }

    private fun checkOutcome(status: CompletionStatus, result: String?, error: String?, errorCode: CompletionErrorCode?) {
        check(
            when (status) {
                CompletionStatus.SUCCEEDED -> result != null && error == null && errorCode == null
                CompletionStatus.FAILED -> result == null && error != null && errorCode != null
                else -> false
            },
        ) { "completion status and outcome are inconsistent" }
    }

    /** One read snapshot across request + outcome; entered through the proxy by the nontransactional service. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun findView(id: UUID): CompletionView? {
        val request = requests.findById(id) ?: return null
        return CompletionView.of(request, results.findByRequestId(id))
    }

    /** One read snapshot across page/count + outcomes; not a freshest-data guarantee (PLAN §4.6). */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun listViews(userId: UUID, page: Int, size: Int): PagedCompletions {
        val pageData = requests.findPageByUser(userId, page, size)
        val resultsById = results.findByRequestIds(pageData.items.map { it.id })
        return PagedCompletions(
            items = pageData.items.map { CompletionView.of(it, resultsById[it.id]) },
            total = pageData.total,
        )
    }
}

/** [won] is committed publication ownership only after the transactional proxy has returned. */
data class CompletionPublication(val view: CompletionView, val won: Boolean)
