package me.manga.kira.backend.completion.infrastructure

import me.manga.kira.backend.completion.domain.CompletionOutcome
import me.manga.kira.backend.completion.domain.CompletionProvider
import me.manga.kira.backend.completion.domain.CompletionProviderLifetime
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * Audited synchronous dev/test provider (PLAN §10). `name = "echo"`; returns `"echo: $prompt"` and
 * never fails except on blank input. It carries no state or I/O, and every exit ends all its work.
 * It still uses the service's activation, timeout, truncation, sanitization and error-mapping path;
 * it is not a fallback for the unsupported generic production HTTPS provider.
 *
 * The submitted model is recorded on the `completion_requests` row by the orchestrator (it is a column,
 * not part of the echoed text — the echoed result is exactly `"echo: $prompt"`).
 */
@Component
@Profile("dev", "test")
class EchoCompletionProvider : CompletionProvider {
    override val name: String = "echo"
    override val lifetime = CompletionProviderLifetime.SYNCHRONOUS

    override fun complete(prompt: String, model: String): CompletionOutcome {
        require(prompt.isNotBlank()) { "prompt must not be blank" }
        val start = System.nanoTime()
        val result = "echo: $prompt"
        val latencyMs = ((System.nanoTime() - start) / NANOS_PER_MILLI).toInt()
        return CompletionOutcome.Success(result = result, latencyMs = latencyMs)
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
