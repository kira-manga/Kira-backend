package me.manga.kira.backend.completion.domain

/**
 * The completion port (PLAN §10) — a pure domain abstraction with **no Spring types**. A future real
 * provider (e.g. an LLM API client) is one new implementation; its API key stays server-side in its
 * own env var and never appears in any API response (PLAN §10). Controllers know only
 * `CompletionService`; the service knows only this port.
 *
 * [complete] runs OUTSIDE any DB transaction, wrapped in a timeout by the orchestrator (PLAN §10), so
 * an implementation may block on I/O without pinning a connection-pool slot. A transport-level failure
 * a real provider hits should be signalled by throwing [ProviderUnavailableException] /
 * [InvalidProviderResponseException]; a deliberate *refusal* of the request is a [CompletionOutcome.Failure].
 */
interface CompletionProvider {
    /** Stable provider identifier; `kira.completion.provider` selects a bean by this name (PLAN §10). */
    val name: String

    /** Produce a completion for [prompt] using [model], or refuse via [CompletionOutcome.Failure]. */
    fun complete(prompt: String, model: String): CompletionOutcome
}

/**
 * The provider's result (PLAN §10): [Success] with the text + self-reported latency, or [Failure] — a
 * deliberate refusal that maps to `PROVIDER_REJECTED`. A [Failure.error] is the provider's own reason;
 * it is logged server-side only and is NEVER the client-visible message (that is always the sanitized
 * generic one — PLAN §10).
 */
sealed interface CompletionOutcome {
    data class Success(val result: String, val latencyMs: Int) : CompletionOutcome

    data class Failure(val error: String) : CompletionOutcome
}
