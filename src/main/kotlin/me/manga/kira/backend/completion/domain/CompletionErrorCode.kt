package me.manga.kira.backend.completion.domain

/**
 * The stable, bounded completion error-code catalog (PLAN §10) — persisted in
 * `completion_results.error_code` and exposed as `errorCode` in the API. It is the machine-actionable
 * part of a failure; the accompanying `error` message is always a sanitized, generic, client-safe
 * string. Raw provider exceptions/stack traces are NEVER stored or returned (secured server logs only).
 */
enum class CompletionErrorCode {
    /** The §10 provider-call timeout elapsed. */
    PROVIDER_TIMEOUT,

    /** Connect/transport failure reaching the provider ([ProviderUnavailableException]). */
    PROVIDER_UNAVAILABLE,

    /** The provider deliberately refused the request (a [CompletionOutcome.Failure]). */
    PROVIDER_REJECTED,

    /** Unparseable/contract-violating provider output ([InvalidProviderResponseException]). */
    INVALID_PROVIDER_RESPONSE,

    /**
     * Result over the maximum where truncation is disallowed. The v1 default policy TRUNCATES to
     * `kira.completion.max-result-length` (recording it in the server log) and keeps the row a success,
     * so this code is currently unused — it is retained in the catalog for a future no-truncate policy.
     */
    RESULT_TOO_LARGE,

    /** Every unknown/unexpected exception maps here — never a leaked message (PLAN §10). */
    INTERNAL_COMPLETION_ERROR,
}
