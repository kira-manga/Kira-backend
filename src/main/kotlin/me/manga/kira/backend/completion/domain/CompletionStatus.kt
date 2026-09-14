package me.manga.kira.backend.completion.domain

/**
 * The lifecycle of a completion request (PLAN §5/§10 — the `completion_requests.status` CHECK values).
 *
 * Normal execution walks `PENDING → RUNNING → (SUCCEEDED | FAILED)`; failed/canceled startup may
 * instead move `PENDING → FAILED`. Conditional writes never leave a terminal state. A process crash
 * can leave a nonterminal row, which the bounded retention job eventually expires (PLAN §10).
 */
enum class CompletionStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
}
