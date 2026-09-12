package me.manga.kira.backend.completion.application

import jakarta.annotation.PreDestroy
import me.manga.kira.backend.common.exception.ServiceUnavailableException
import me.manga.kira.backend.completion.domain.CompletionErrorCode
import me.manga.kira.backend.completion.domain.CompletionOutcome
import me.manga.kira.backend.completion.domain.CompletionProvider
import me.manga.kira.backend.completion.domain.CompletionProviderLifetime
import me.manga.kira.backend.completion.domain.CompletionStatus
import me.manga.kira.backend.completion.domain.CompletionView
import me.manga.kira.backend.completion.domain.InvalidProviderResponseException
import me.manga.kira.backend.completion.domain.PagedCompletions
import me.manga.kira.backend.completion.domain.ProviderUnavailableException
import me.manga.kira.backend.config.KiraCompletionProperties
import me.manga.kira.backend.observability.KiraMetrics
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
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
 * final store leaves a visible `RUNNING` row for bounded retention recovery (PLAN §10). Failed startup
 * may move directly from `PENDING` to `FAILED`; terminal publication is conditional and insert-once.
 *
 * **Provider selection:** `kira.completion.provider` picks the bean by name from the injected
 * `List<CompletionProvider>`; unknown names or lifetime capabilities fail before this service allocates
 * its executor. Only audited synchronous providers whose every exit ends all work may execute (PLAN §10).
 *
 * **Error mapping (§10 catalog):** timeout → `PROVIDER_TIMEOUT`; [CompletionOutcome.Failure] →
 * `PROVIDER_REJECTED`; [ProviderUnavailableException] → `PROVIDER_UNAVAILABLE`;
 * [InvalidProviderResponseException] → `INVALID_PROVIDER_RESPONSE`; anything else →
 * `INTERNAL_COMPLETION_ERROR`. The client-visible `error` is ALWAYS the sanitized generic message;
 * raw provider exceptions are logged server-side only (request-id-correlated) and never stored/returned.
 */
@Service
@ConditionalOnProperty(prefix = "kira.completion", name = ["enabled"], havingValue = "true")
class CompletionService(
    providers: List<CompletionProvider>,
    private val properties: KiraCompletionProperties,
    private val persistence: CompletionPersistence,
    private val admission: CompletionAdmission,
    private val metrics: KiraMetrics,
) {
    /** Validate before allocating the executor, including direct or non-production construction. */
    private val defaultModel = properties.requireDefaultModel()

    /** Resolve and check the lifetime contract before allocating this service's executor (PLAN §10). */
    private val provider: CompletionProvider = selectProvider(providers)

    /** Fixed workers plus a bounded queue; saturation fails fast instead of accumulating unbounded work. */
    private val executor =
        BoundedCompletionExecutor(
            threads = properties.executorThreads,
            queueCapacity = properties.queueCapacity,
            threadFactory = namedThreadFactory("completion-provider"),
        )

    init {
        metrics.bindCompletionExecutor(executor)
    }

    private fun selectProvider(providers: List<CompletionProvider>): CompletionProvider {
        val selected =
            providers.firstOrNull { it.name == properties.provider }
                ?: throw IllegalStateException(
                    "No CompletionProvider named '${properties.provider}' is registered " +
                        "(available: ${providers.map { it.name }}). Check kira.completion.provider (PLAN §10).",
                )
        check(selected.lifetime == CompletionProviderLifetime.SYNCHRONOUS) {
            "Selected CompletionProvider must end all work on every synchronous method exit"
        }
        return selected
    }

    /**
     * Submit a completion (PLAN §4.6/§10): insert `PENDING`, mark `RUNNING`, pin admission, authorize and
     * invoke OUTSIDE any transaction, then store the outcome. Caller wait is not worker termination.
     * [prompt] is assumed already validated (non-blank, within max length) by the controller boundary.
     */
    fun create(userId: UUID, prompt: String, model: String?): CompletionView {
        CompletionExecutionOwnership(admission.acquire(userId)).use { execution ->
            val effectiveModel = model?.takeIf { it.isNotBlank() } ?: defaultModel

            val id = persistence.createPending(userId, provider.name, effectiveModel, prompt)
            // `model` is client-supplied — sanitize control chars before it reaches the log line (§6 log-hygiene).
            log.info("Completion {} PENDING provider={} model={}", id, provider.name, sanitizeForLog(effectiveModel))

            val resolved = invokeProvider(id, prompt, effectiveModel, execution)
            try {
                val publication =
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

                val view = publication.view
                if (publication.won) {
                    if (resolved is Resolved.Failure) logFailure(id, resolved)
                    val latencyMs = if (resolved is Resolved.Success) resolved.latencyMs else (resolved as Resolved.Failure).latencyMs
                    log.info("Completion {} {} errorCode={} latencyMs={}", id, view.status, view.errorCode, latencyMs)
                    metrics.completionFinished(view.status.name, view.errorCode?.name ?: "none")
                }
                if (publication.won && resolved is Resolved.Failure) rejectIfUnavailable(resolved)
                return view
            } finally {
                if (resolved is Resolved.Failure && resolved.interrupted) Thread.currentThread().interrupt()
            }
        }
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

    /** Bounded queue + committed startup, followed by a separate timed provider wait. */
    private fun invokeProvider(id: UUID, prompt: String, model: String, execution: CompletionExecutionOwnership): Resolved {
        val startNanos = System.nanoTime()
        val startup = CompletionStartup(properties.queueTimeout)
        val future =
            try {
                executor.submit(Callable { invokeOwnedProvider(id, prompt, model, startup, execution) })
            } catch (ex: RejectedExecutionException) {
                return Resolved.Failure(
                    CompletionErrorCode.PROVIDER_UNAVAILABLE,
                    "provider execution capacity is exhausted",
                    ex,
                    elapsedMs(startNanos),
                    overloaded = true,
                )
            }
        return try {
            val started = when (val decision = startup.await()) {
                is CompletionStartup.Decision.Authorized -> decision

                is CompletionStartup.Decision.Failed -> return executionFailure(decision.cause, startNanos)

                is CompletionStartup.Decision.AdmissionDenied -> return Resolved.Failure(
                    CompletionErrorCode.PROVIDER_UNAVAILABLE,
                    "provider admission activation denied",
                    null,
                    elapsedMs(startNanos),
                    activation = decision.activation,
                )

                CompletionStartup.Decision.Expired -> {
                    startup.cancel()
                    future.cancel(true)
                    return Resolved.Failure(
                        CompletionErrorCode.PROVIDER_UNAVAILABLE,
                        "provider startup timeout",
                        null,
                        elapsedMs(startNanos),
                        overloaded = true,
                    )
                }

                CompletionStartup.Decision.Rejected, CompletionStartup.Decision.Cancelled -> return Resolved.Failure(
                    CompletionErrorCode.INTERNAL_COMPLETION_ERROR,
                    "provider startup was not authorized",
                    null,
                    elapsedMs(startNanos),
                )
            }
            when (val outcome = checkNotNull(startup.awaitProvider(future, started, properties.timeout))) {
                is CompletionOutcome.Success -> Resolved.Success(outcome.result, outcome.latencyMs)

                // A deliberate refusal — the provider's own reason is logged server-side, never returned.
                is CompletionOutcome.Failure ->
                    Resolved.Failure(CompletionErrorCode.PROVIDER_REJECTED, outcome.error, null, elapsedMs(startNanos))
            }
        } catch (ex: TimeoutException) {
            startup.cancel()
            future.cancel(true) // Requests interruption, not acknowledged worker/remote termination.
            Resolved.Failure(CompletionErrorCode.PROVIDER_TIMEOUT, "provider call timed out", ex, elapsedMs(startNanos))
        } catch (ex: ExecutionException) {
            executionFailure(ex.cause ?: ex, startNanos)
        } catch (ex: InterruptedException) {
            startup.cancel()
            future.cancel(true)
            Thread.interrupted() // clear while the sanitized outcome is persisted; restored by create()
            Resolved.Failure(CompletionErrorCode.INTERNAL_COMPLETION_ERROR, "interrupted", ex, elapsedMs(startNanos), interrupted = true)
        }
    }

    private fun invokeOwnedProvider(
        id: UUID,
        prompt: String,
        model: String,
        startup: CompletionStartup,
        execution: CompletionExecutionOwnership,
    ): CompletionOutcome? {
        if (!execution.tryEnter()) {
            startup.rejected()
            return null
        }
        try {
            return runCatching {
                if (!startup.canClaim()) return@runCatching null
                if (!persistence.markRunning(id)) {
                    startup.rejected()
                    return@runCatching null
                }
                val activation = execution.activate()
                if (activation != CompletionActivation.ACTIVATED) {
                    startup.admissionDenied(activation)
                    return@runCatching null
                }
                // RUNNING is committed and the exact reservation pinned; cancellation/deadline still get a vote.
                if (!startup.authorize()) return@runCatching null
                log.info("Completion {} RUNNING", id)
                provider.complete(prompt, model)
            }.onFailure(startup::failed).getOrThrow()
        } finally {
            // Exit-first only records the fact: release I/O must not delay normal Future completion.
            execution.workerExited()
        }
    }

    private fun rejectIfUnavailable(failure: Resolved.Failure) {
        val (code, retry) = when {
            failure.activation == CompletionActivation.EXPIRED -> "COMPLETION_CONCURRENCY_LIMIT" to 1L
            failure.activation == CompletionActivation.UNAVAILABLE -> "COMPLETION_COORDINATION_UNAVAILABLE" to 5L
            failure.overloaded -> "COMPLETION_OVERLOADED" to 1L
            else -> return
        }
        val detail = if (failure.activation == CompletionActivation.UNAVAILABLE) {
            "Completion service is temporarily unavailable. Try again later."
        } else {
            "Completion capacity is currently exhausted. Try again later."
        }
        throw ServiceUnavailableException(detail, code, retry)
    }

    private fun executionFailure(cause: Throwable, startNanos: Long): Resolved.Failure {
        val code = when (cause) {
            is ProviderUnavailableException -> CompletionErrorCode.PROVIDER_UNAVAILABLE
            is InvalidProviderResponseException -> CompletionErrorCode.INVALID_PROVIDER_RESPONSE
            else -> CompletionErrorCode.INTERNAL_COMPLETION_ERROR
        }
        return Resolved.Failure(code, cause.message, cause, elapsedMs(startNanos))
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

        data class Failure(
            val code: CompletionErrorCode,
            val providerDetail: String?,
            val cause: Throwable?,
            val latencyMs: Int?,
            val overloaded: Boolean = false,
            val interrupted: Boolean = false,
            val activation: CompletionActivation? = null,
        ) : Resolved
    }

    private companion object {
        val log = LoggerFactory.getLogger(CompletionService::class.java)

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
