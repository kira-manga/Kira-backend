package me.manga.kira.backend.config

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

/**
 * `kira.completion.*` — completion-foundation configuration (PLAN §3 config/, §4.6, §10). Phase 2
 * defines and validates the typed binding; `CompletionService` and provider selection wire it in
 * Phase 9. All fields have safe non-secret defaults, so the context loads with zero external
 * config. A real provider's API key stays server-side in its own env var — never a property echoed
 * anywhere client-visible (PLAN §10).
 */
@Validated
@ConfigurationProperties(prefix = "kira.completion")
data class KiraCompletionProperties(
    /** Disabled by default; enabling requires a valid provider configuration. */
    val enabled: Boolean = false,
    /** Selects the `CompletionProvider` bean by name. Echo exists only in dev/test. */
    @field:NotBlank
    val provider: String = "http",
    /** HTTPS endpoint for the production HTTP provider. */
    val endpoint: String? = null,
    /** Bearer credential for the production HTTP provider; environment/secret manager only. */
    val apiKey: String? = null,
    /** Provider-call timeout; the call runs outside any DB transaction (PLAN §10). */
    @field:NotNull
    val timeout: Duration = Duration.ofSeconds(30),
    /** Maximum time work may wait for a provider worker before overload rejection. */
    @field:NotNull
    val queueTimeout: Duration = Duration.ofSeconds(2),
    /** Max stored result length before truncation (truncation is recorded) (PLAN §10). */
    @field:Positive
    val maxResultLength: Int = 100_000,
    /** Max accepted prompt length; over the limit → 413 (PLAN §4.6 / §4.5). */
    @field:Positive
    val promptMaxLength: Int = 8_000,
    /** Provider worker count. Calls above this run from the bounded queue. */
    @field:Positive
    val executorThreads: Int = 8,
    /** Maximum queued provider calls before new work fails fast as provider-unavailable. */
    @field:Positive
    val queueCapacity: Int = 64,
    /** Admission backend. Memory supports one instance; Redis coordinates multiple instances. */
    val coordinationBackend: String = "memory",
    @field:Positive
    val instanceCount: Int = 1,
    /** Per-user rolling-minute request cap. Zero disables this specific cap. */
    @field:PositiveOrZero
    val perUserPerMinute: Int = 10,
    /** Service-wide rolling-minute request cap. Zero disables this specific cap. */
    @field:PositiveOrZero
    val globalPerMinute: Int = 100,
    /** Per-user daily request cap. Zero disables this specific cap. */
    @field:PositiveOrZero
    val perUserDailyQuota: Int = 100,
    /** Global provider calls allowed concurrently across the configured topology. */
    @field:Positive
    val globalConcurrency: Int = 8,
    /** Prompt/result retention. Expired rows are deleted by the scheduled cleanup. */
    @field:NotNull
    val retention: Duration = Duration.ofDays(7),
    /** Cleanup cadence. */
    @field:NotNull
    val cleanupInterval: Duration = Duration.ofHours(1),
    /** Rows deleted per cleanup transaction to bound locks and WAL bursts. */
    @field:Positive
    val cleanupBatchSize: Int = 1_000,
) {
    init {
        require(!timeout.isZero && !timeout.isNegative) { "kira.completion.timeout must be positive" }
        require(!queueTimeout.isZero && !queueTimeout.isNegative) { "kira.completion.queue-timeout must be positive" }
        require(coordinationBackend in setOf("memory", "redis")) {
            "kira.completion.coordination-backend must be memory or redis"
        }
        require(!retention.isZero && !retention.isNegative) { "kira.completion.retention must be positive" }
        require(!cleanupInterval.isZero && !cleanupInterval.isNegative) { "kira.completion.cleanup-interval must be positive" }
    }
}
