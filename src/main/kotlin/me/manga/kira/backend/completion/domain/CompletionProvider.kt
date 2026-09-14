package me.manga.kira.backend.completion.domain

/**
 * The completion port (PLAN §10) — a pure domain abstraction with **no Spring types**. Provider
 * credentials stay server-side in their own env vars and never appear in API responses. The generic
 * HTTPS adapter has UNKNOWN lifetime and cannot execute through the service. Controllers know only
 * `CompletionService`; the service knows only this port.
 *
 * [complete] runs OUTSIDE any DB transaction, wrapped in a timeout by the orchestrator (PLAN §10), so
 * an implementation may block on I/O without pinning a connection-pool slot. A transport-level failure
 * a supported provider hits should be signalled by throwing [ProviderUnavailableException] /
 * [InvalidProviderResponseException]; a deliberate *refusal* of the request is a [CompletionOutcome.Failure].
 */
interface CompletionProvider {
    /** Stable provider identifier; `kira.completion.provider` selects a bean by this name (PLAN §10). */
    val name: String

    /** Code-owned capability, never an operator assertion; UNKNOWN providers cannot execute. */
    val lifetime: CompletionProviderLifetime get() = CompletionProviderLifetime.UNKNOWN

    /** Produce a completion for [prompt] using [model], or refuse via [CompletionOutcome.Failure]. */
    fun complete(prompt: String, model: String): CompletionOutcome
}

enum class CompletionProviderLifetime {
    UNKNOWN,

    /** Every return or throw ends all work, including failure/cancellation paths; no detached or uncertain remote work. */
    SYNCHRONOUS,
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
