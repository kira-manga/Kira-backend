package me.manga.kira.backend.completion.domain

/**
 * The Spring-free provider-exception vocabulary (PLAN §10) for transport-level failures a future real
 * [CompletionProvider] throws. The orchestrator maps them to stable catalog codes:
 *  - [ProviderUnavailableException] → `PROVIDER_UNAVAILABLE` (connect/transport failure)
 *  - [InvalidProviderResponseException] → `INVALID_PROVIDER_RESPONSE` (unparseable/contract-violating output)
 *
 * Any OTHER unexpected `Throwable` maps to `INTERNAL_COMPLETION_ERROR` and is never leaked to a client.
 * A deliberate *refusal* is NOT an exception — it is a [CompletionOutcome.Failure] (→ `PROVIDER_REJECTED`).
 */
class ProviderUnavailableException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class InvalidProviderResponseException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
