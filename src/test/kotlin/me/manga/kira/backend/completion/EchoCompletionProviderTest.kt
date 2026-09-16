package me.manga.kira.backend.completion

import me.manga.kira.backend.completion.domain.CompletionOutcome
import me.manga.kira.backend.completion.infrastructure.EchoCompletionProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * PLAN §11 test 8 — `EchoCompletionProviderTest` (pure unit, no Spring). The echo provider echoes the
 * prompt as `"echo: $prompt"`, accepts the model, reports a non-negative latency, and fails only on
 * blank input. The model is *recorded* on the `completion_requests` row by the orchestrator (a column,
 * not part of the echoed text) — that end-to-end recording is asserted in `CompletionIT`.
 */
class EchoCompletionProviderTest {
    private val provider = EchoCompletionProvider()

    @Test
    fun `name is echo`() {
        assertEquals("echo", provider.name)
    }

    @Test
    fun `echoes the prompt and reports non-negative latency`() {
        val outcome = provider.complete("hello world", "echo-1")

        assertTrue(outcome is CompletionOutcome.Success)
        outcome as CompletionOutcome.Success
        assertEquals("echo: hello world", outcome.result)
        assertTrue(outcome.latencyMs >= 0)
    }

    @Test
    fun `accepts the model parameter without altering the echoed prefix`() {
        val a = provider.complete("x", "model-a")
        val b = provider.complete("x", "model-b")

        assertTrue(a is CompletionOutcome.Success)
        assertTrue(b is CompletionOutcome.Success)
        assertEquals("echo: x", (a as CompletionOutcome.Success).result)
        assertEquals("echo: x", (b as CompletionOutcome.Success).result)
    }

    @Test
    fun `blank prompt fails`() {
        assertThrows<IllegalArgumentException> { provider.complete("   ", "echo-1") }
        assertThrows<IllegalArgumentException> { provider.complete("", "echo-1") }
    }
}
