package me.manga.kira.backend.completion.domain

/**
 * The lifecycle of a completion request (PLAN §5/§10 — the `completion_requests.status` CHECK values).
 *
 * The three-transaction orchestration walks `PENDING → RUNNING → (SUCCEEDED | FAILED)`. A process crash
 * between marking `RUNNING` and storing the outcome leaves a `RUNNING` row — harmless, visible, and
 * exactly why the status exists (PLAN §10). The `RUNNING` state also enables async execution later with
 * no schema change.
 */
enum class CompletionStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
}
