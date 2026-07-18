package me.manga.kira.backend.completion.application

import jakarta.annotation.PreDestroy
import me.manga.kira.backend.completion.domain.CompletionErrorCode
import me.manga.kira.backend.completion.domain.CompletionOutcome
import me.manga.kira.backend.completion.domain.CompletionProvider
import me.manga.kira.backend.completion.domain.CompletionStatus
import me.manga.kira.backend.completion.domain.CompletionView
import me.manga.kira.backend.completion.domain.InvalidProviderResponseException
import me.manga.kira.backend.completion.domain.PagedCompletions
import me.manga.kira.backend.completion.domain.ProviderUnavailableException
import me.manga.kira.backend.config.KiraCompletionProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Completion orchestration (PLAN §10). Deliberately small — a persistence + abstraction skeleton, not
 * an AI platform. It is the ONLY thing the controller talks to, and it talks only to the [CompletionProvider]
 * port and the [CompletionPersistence] transactional seam.
 *
 * **Three-transaction orchestration (normative — a DB transaction is NEVER held open across the provider
 * call):** [create] is intentionally NOT `@Transactional`. It calls three proxied [CompletionPersistence]
 * methods — insert `PENDING` (commit), mark `RUNNING` (commit), then, AFTER the provider call, store the
 * sanitized outcome (commit). The provider runs on a bounded executor with a `Future.get(timeout)` so a
 * slow/hung provider cannot pin a connection-pool slot or a row lock. A crash between `RUNNING` and the
 * final store leaves a harmless, visible `RUNNING` row (PLAN §10).
 *
 * **Provider selection:** `kira.completion.provider` picks the bean by name from the injected
 * `List<CompletionProvider>`; an unknown name throws here at construction → fail-fast startup (PLAN §10).
 *
 * **Error mapping (§10 catalog):** timeout → `PROVIDER_TIMEOUT`; [CompletionOutcome.Failure] →
 * `PROVIDER_REJECTED`; [ProviderUnavailableException] → `PROVIDER_UNAVAILABLE`;
 * [InvalidProviderResponseException] → `INVALID_PROVIDER_RESPONSE`; anything else →
 * `INTERNAL_COMPLETION_ERROR`. The client-visible `error` is ALWAYS the sanitized generic message;
 * raw provider exceptions are logged server-side only (request-id-correlated) and never stored/returned.
 */
@Service
class CompletionService(
    providers: List<CompletionProvider>,
    private val properties: KiraCompletionProperties,
    private val persistence: CompletionPersistence,
) {
    /** The selected provider, resolved once at construction — an unknown name fails startup (PLAN §10). */
    private val provider: CompletionProvider =
        providers.firstOrNull { it.name == properties.provider }
            ?: throw IllegalStateException(
                "No CompletionProvider named '${properties.provider}' is registered " +
                    "(available: ${providers.map { it.name }}). Check kira.completion.provider (PLAN §10).",
            )

    /** Fixed workers plus a bounded queue; saturation fails fast instead of accumulating unbounded work. */
    private val executor =
        BoundedCompletionExecutor(
            threads = properties.executorThreads,
            queueCapacity = properties.queueCapacity,
            threadFactory = namedThreadFactory("completion-provider"),
        )

    /**
     * Submit a completion (PLAN §4.6/§10): insert `PENDING`, mark `RUNNING`, invoke the provider with a
     * timeout OUTSIDE any transaction, then store the sanitized outcome. Returns the composed view.
     * [prompt] is assumed already validated (non-blank, within max length) by the controller boundary.
     */
    fun create(userId: UUID, prompt: String, model: String?): CompletionView {
        checkQuota(userId)
        val effectiveModel = model?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL

        val id = persistence.createPending(userId, provider.name, effectiveModel, prompt)
        // `model` is client-supplied — sanitize control chars before it reaches the log line (§6 log-hygiene).
        log.info("Completion {} PENDING provider={} model={}", id, provider.name, sanitizeForLog(effectiveModel))

        persistence.markRunning(id)
        log.info("Completion {} RUNNING", id)

        val resolved = invokeProvider(prompt, effectiveModel)
        val view =
            when (resolved) {
                is Resolved.Success -> {
                    val (stored, truncated) = truncate(resolved.result)
                    if (truncated) {
                        // Server-log only, lengths only — never the result text (PLAN §6/§10).
                        log.warn(
                            "Completion {} result truncated from {} to {} chars",
                            id,
                            resolved.result.length,
                            properties.maxResultLength,
                        )
                    }
                    persistence.storeOutcome(
                        id = id,
                        status = CompletionStatus.SUCCEEDED,
                        result = stored,
                        error = null,
                        errorCode = null,
                        latencyMs = resolved.latencyMs,
                    )
                }

                is Resolved.Failure -> {
                    logFailure(id, resolved)
                    persistence.storeOutcome(
                        id = id,
                        status = CompletionStatus.FAILED,
                        result = null,
                        error = SANITIZED_FAILURE_MESSAGE,
                        errorCode = resolved.code,
                        latencyMs = resolved.latencyMs,
                    )
                }
            }

        val latencyMs = if (resolved is Resolved.Success) resolved.latencyMs else (resolved as Resolved.Failure).latencyMs
        log.info("Completion {} {} errorCode={} latencyMs={}", id, view.status, view.errorCode, latencyMs)
        return view
    }

    /** Fetch one completion, enforcing owner-or-ADMIN visibility (others → null → 404, PLAN §4.6). */
    fun getForReader(id: UUID, readerId: UUID, readerIsAdmin: Boolean): CompletionView? {
        val view = persistence.findView(id) ?: return null
        return view.takeIf { readerIsAdmin || it.userId == readerId }
    }

    /**
     * List completions, newest first (PLAN §4.6). A caller sees only their own; an ADMIN may target
     * another user via [userIdParam]. For a non-admin the parameter is ignored (own list only) so it
     * can never be used to read another user's history.
     */
    fun list(readerId: UUID, readerIsAdmin: Boolean, userIdParam: UUID?, page: Int, size: Int): PagedCompletions {
        val targetUserId = if (readerIsAdmin && userIdParam != null) userIdParam else readerId
        return persistence.listViews(targetUserId, page, size)
    }

    /**
     * Quota seam (PLAN §10) — NO-OP in v1, single call site. A real quota needs only a count query over
     * the per-user request history already persisted; documented future work.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun checkQuota(userId: UUID) {
        // Intentionally empty: rate limits / quotas are not implemented in v1 (PLAN §10).
    }

    /** Run the provider on the bounded executor with the configured timeout, mapping every failure mode. */
    private fun invokeProvider(prompt: String, model: String): Resolved {
        val startNanos = System.nanoTime()
        val future =
            try {
                executor.submit(Callable { provider.complete(prompt, model) })
            } catch (ex: RejectedExecutionException) {
                return Resolved.Failure(
                    CompletionErrorCode.PROVIDER_UNAVAILABLE,
                    "provider execution capacity is exhausted",
                    ex,
                    elapsedMs(startNanos),
                )
            }
        return try {
            when (val outcome = future.get(properties.timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                is CompletionOutcome.Success -> Resolved.Success(outcome.result, outcome.latencyMs)

                // A deliberate refusal — the provider's own reason is logged server-side, never returned.
                is CompletionOutcome.Failure ->
                    Resolved.Failure(CompletionErrorCode.PROVIDER_REJECTED, outcome.error, null, elapsedMs(startNanos))
            }
        } catch (ex: TimeoutException) {
            future.cancel(true) // interrupt the worker so it does not linger past the timeout
            Resolved.Failure(CompletionErrorCode.PROVIDER_TIMEOUT, "provider call timed out", ex, elapsedMs(startNanos))
        } catch (ex: ExecutionException) {
            val cause = ex.cause
            val code =
                when (cause) {
                    is ProviderUnavailableException -> CompletionErrorCode.PROVIDER_UNAVAILABLE
                    is InvalidProviderResponseException -> CompletionErrorCode.INVALID_PROVIDER_RESPONSE
                    else -> CompletionErrorCode.INTERNAL_COMPLETION_ERROR
                }
            Resolved.Failure(code, cause?.message, cause ?: ex, elapsedMs(startNanos))
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            future.cancel(true)
            Resolved.Failure(CompletionErrorCode.INTERNAL_COMPLETION_ERROR, "interrupted", ex, elapsedMs(startNanos))
        }
    }

    private fun logFailure(id: UUID, failure: Resolved.Failure) {
        when (failure.code) {
            // The ONLY place a full stack trace is recorded (secured server log, request-id-correlated, §6).
            CompletionErrorCode.INTERNAL_COMPLETION_ERROR ->
                log.error("Completion {} FAILED code={} — unexpected provider error", id, failure.code, failure.cause)

            // Recoverable/expected provider failures — code + a sanitized provider reason, no prompt/result (§6).
            else ->
                log.warn(
                    "Completion {} FAILED code={} providerDetail={}",
                    id,
                    failure.code,
                    sanitizeForLog(failure.providerDetail),
                )
        }
    }

    private fun truncate(result: String): Pair<String, Boolean> = if (result.length > properties.maxResultLength) {
        result.take(properties.maxResultLength) to true
    } else {
        result to false
    }

    @PreDestroy
    fun shutdown() {
        executor.shutdownNow()
    }

    /** Internal normalized outcome of the provider call — [providerDetail]/[cause] are server-log-only. */
    private sealed interface Resolved {
        data class Success(val result: String, val latencyMs: Int) : Resolved

        data class Failure(val code: CompletionErrorCode, val providerDetail: String?, val cause: Throwable?, val latencyMs: Int?) : Resolved
    }

    private companion object {
        val log = LoggerFactory.getLogger(CompletionService::class.java)

        /** The v1 default model recorded when a request omits `model` (PLAN §4.6). */
        const val DEFAULT_MODEL = "echo-1"

        /** The single sanitized, bounded, generic client-visible failure message (PLAN §10). */
        const val SANITIZED_FAILURE_MESSAGE = "The completion request could not be completed."

        fun elapsedMs(startNanos: Long): Int = ((System.nanoTime() - startNanos) / 1_000_000L).toInt()

        /** Replace ALL control characters (newlines included) and bound the length — §6 log-hygiene. */
        val CONTROL_CHARS = Regex("\\p{Cntrl}")

        fun sanitizeForLog(value: String?): String = value?.replace(CONTROL_CHARS, " ")?.take(256) ?: "none"

        fun namedThreadFactory(prefix: String): ThreadFactory {
            val counter = AtomicInteger(0)
            return ThreadFactory { runnable ->
                Thread(runnable, "$prefix-${counter.incrementAndGet()}").apply { isDaemon = true }
            }
        }
    }
}
