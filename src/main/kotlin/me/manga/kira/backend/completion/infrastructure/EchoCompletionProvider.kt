package me.manga.kira.backend.completion.infrastructure

import me.manga.kira.backend.completion.domain.CompletionOutcome
import me.manga.kira.backend.completion.domain.CompletionProvider
import org.springframework.stereotype.Component

/**
 * The default runtime provider and the provider used by tests (PLAN §10). `name = "echo"`; returns
 * `"echo: $prompt"` and never fails except on blank input. It carries no state and no I/O, but still
 * goes through the SAME orchestration path (timeout, truncation, sanitization, error mapping) as a
 * future real provider — so swapping in a real one changes zero orchestration code.
 *
 * The submitted model is recorded on the `completion_requests` row by the orchestrator (it is a column,
 * not part of the echoed text — the echoed result is exactly `"echo: $prompt"`).
 */
@Component
class EchoCompletionProvider : CompletionProvider {
    override val name: String = "echo"

    override fun complete(
        prompt: String,
        model: String,
    ): CompletionOutcome {
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
