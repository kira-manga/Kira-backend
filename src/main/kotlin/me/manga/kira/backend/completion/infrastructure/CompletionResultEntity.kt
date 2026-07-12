package me.manga.kira.backend.completion.infrastructure

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * JPA entity for `completion_results` (PLAN §5 V5) — the sanitized outcome, one row per finished
 * request (unique `request_id`). [result] XOR [error] is enforced by the DB CHECK `chk_result_xor_error`,
 * and [error]/[errorCode] pair together via `chk_completion_error_code_pairing`. [error] is ALWAYS the
 * sanitized client-visible message — never a stack trace or raw provider exception (PLAN §10). Insert-only
 * (no `@PreUpdate`); `created_at` is set by the adapter from the injected Clock.
 */
@Entity
@Table(name = "completion_results")
class CompletionResultEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID? = null,
    @Column(name = "request_id", nullable = false, updatable = false)
    var requestId: UUID? = null,
    @Column(name = "result", updatable = false)
    var result: String? = null,
    @Column(name = "error", updatable = false)
    var error: String? = null,
    @Column(name = "error_code", updatable = false)
    var errorCode: String? = null,
    @Column(name = "latency_ms", updatable = false)
    var latencyMs: Int? = null,
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.EPOCH,
)
