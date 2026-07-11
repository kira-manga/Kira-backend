package me.manga.kira.backend.config

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
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
    /** Selects the `CompletionProvider` bean by name (PLAN §10). Default fake/echo provider. */
    @field:NotBlank
    val provider: String = "echo",
    /** Provider-call timeout; the call runs outside any DB transaction (PLAN §10). */
    @field:NotNull
    val timeout: Duration = Duration.ofSeconds(30),
    /** Max stored result length before truncation (truncation is recorded) (PLAN §10). */
    @field:Positive
    val maxResultLength: Int = 100_000,
    /** Max accepted prompt length; over the limit → 413 (PLAN §4.6 / §4.5). */
    @field:Positive
    val promptMaxLength: Int = 8_000,
)
