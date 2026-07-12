package me.manga.kira.backend.completion.infrastructure

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import me.manga.kira.backend.completion.domain.CompletionStatus
import java.time.Instant
import java.util.UUID

/**
 * JPA entity for `completion_requests` (PLAN §5 V5). Kept in `infrastructure` — never leaks into
 * controllers or the domain (PLAN §2). The id is generated client-side by Hibernate
 * ([GenerationType.UUID]) so a `save` is a plain INSERT with the id available immediately (the column's
 * `DEFAULT gen_random_uuid()` is a belt-and-braces fallback for manual inserts).
 *
 * `created_at`/`updated_at` are set explicitly by the adapter from the injected Clock (NO
 * `@PrePersist`/`@PreUpdate`), matching the audit/publication Clock discipline; [status] transitions
 * are applied via a bulk update so a `RUNNING`/terminal flip needs no prior load.
 */
@Entity
@Table(name = "completion_requests")
class CompletionRequestEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID? = null,
    @Column(name = "user_id", nullable = false, updatable = false)
    var userId: UUID? = null,
    @Column(name = "provider", nullable = false, updatable = false)
    var provider: String = "",
    @Column(name = "model", nullable = false, updatable = false)
    var model: String = "",
    @Column(name = "prompt", nullable = false, updatable = false)
    var prompt: String = "",
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: CompletionStatus = CompletionStatus.PENDING,
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.EPOCH,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.EPOCH,
)
