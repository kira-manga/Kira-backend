package me.manga.kira.backend.sourceconfig.infrastructure

import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import me.manga.kira.backend.sourceconfig.domain.SourceLifecycleStatus
import java.time.Instant
import java.util.UUID

/**
 * JPA entity for `source_configs` (PLAN §5 V2). Kept in `infrastructure` (never leaks to controllers or
 * domain — PLAN §2). `kotlin-jpa` supplies the no-arg ctor; `allOpen` makes it `open`. The id is
 * generated client-side ([GenerationType.UUID]). [status] uses [SourceLifecycleStatusConverter] to map
 * the enum to its lowercase wire value (`chk_source_configs_status`).
 *
 * [currentPublishedRevisionId] is a plain UUID FK column (no JPA relationship) — the composite FK is a
 * DB constraint (PLAN §5); the persistence discipline (PLAN §5) forbids lazy graphs across service
 * boundaries, so we fetch explicitly. `created_at`/`updated_at` are stamped by the lifecycle callbacks.
 */
@Entity
@Table(name = "source_configs")
class SourceConfigEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID? = null,
    @Column(name = "api", nullable = false, updatable = false)
    var api: String = "",
    @Column(name = "display_name", nullable = false)
    var displayName: String = "",
    @Column(name = "language", nullable = false)
    var language: String = "",
    @Column(name = "engine", nullable = false)
    var engine: String = "",
    @Convert(converter = SourceLifecycleStatusConverter::class)
    @Column(name = "status", nullable = false)
    var status: SourceLifecycleStatus = SourceLifecycleStatus.DRAFT,
    @Column(name = "position", nullable = false)
    var position: Int = 0,
    @Column(name = "base_url", nullable = false)
    var baseUrl: String = "",
    @Column(name = "adult", nullable = false)
    var adult: Boolean = false,
    @Column(name = "current_published_revision_id")
    var currentPublishedRevisionId: UUID? = null,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.EPOCH,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.EPOCH,
    @Column(name = "published_at")
    var publishedAt: Instant? = null,
) {
    @PrePersist
    fun onCreate() {
        val now = Instant.now()
        createdAt = now
        updatedAt = now
    }

    @PreUpdate
    fun onUpdate() {
        updatedAt = Instant.now()
    }
}
