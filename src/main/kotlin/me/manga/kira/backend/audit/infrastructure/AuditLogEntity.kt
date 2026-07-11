package me.manga.kira.backend.audit.infrastructure

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * JPA entity for `audit_log` (PLAN §5 V4). Insert-only (no `@PreUpdate`, no lifecycle callback):
 * `created_at` is set by the application from the injected Clock so a publication audit row can carry
 * the SAME instant as the document's `generatedAt` (PLAN §9 steps 7–8). [id] is `GENERATED ALWAYS AS
 * IDENTITY`, so it is DB-assigned ([GenerationType.IDENTITY]). [detail] maps the `jsonb` column as a
 * canonical JSON-object **string** via `@JdbcTypeCode(SqlTypes.JSON)` (Hibernate 6) — the adapter/service
 * owns encoding, keeping identifiers-only hygiene (PLAN §6) in one place.
 */
@Entity
@Table(name = "audit_log")
class AuditLogEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    var id: Long? = null,
    @Column(name = "actor_user_id", updatable = false)
    var actorUserId: UUID? = null,
    @Column(name = "action", nullable = false, updatable = false)
    var action: String = "",
    @Column(name = "entity_type", nullable = false, updatable = false)
    var entityType: String = "",
    @Column(name = "entity_id", nullable = false, updatable = false)
    var entityId: String = "",
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail", nullable = false, updatable = false)
    var detail: String = "{}",
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.EPOCH,
)
