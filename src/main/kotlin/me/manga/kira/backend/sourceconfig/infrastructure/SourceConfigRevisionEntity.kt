package me.manga.kira.backend.sourceconfig.infrastructure

import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.PrePersist
import jakarta.persistence.Table
import me.manga.kira.backend.sourceconfig.domain.RevisionStatus
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * JPA entity for `source_config_revisions` (PLAN §5 V2) — immutable per-source history. [status] maps
 * via [RevisionStatusConverter]. [sourceConfigId]/[createdBy] are plain UUID FK columns (no JPA
 * relationships — explicit fetch discipline, PLAN §5). [configCanonicalJson] holds the canonical
 * (kcj-1) bytes verbatim in a `text` column so the checksum is reproducible from storage.
 *
 * `created_at` is stamped by [onCreate]; `published_at` is set on publish (Phase 6). The row is
 * insert-only (immutable history), so there is no `@PreUpdate`.
 */
@Entity
@Table(name = "source_config_revisions")
class SourceConfigRevisionEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID? = null,
    @Column(name = "source_config_id", nullable = false, updatable = false)
    var sourceConfigId: UUID? = null,
    @Column(name = "revision_number", nullable = false, updatable = false)
    var revisionNumber: Int = 0,
    @Column(name = "config_canonical_json", nullable = false, updatable = false)
    var configCanonicalJson: String = "",
    // char(64) (PLAN §5) — mapped as fixed-width CHAR so ddl-auto=validate matches `bpchar`, not varchar.
    // A SHA-256 hex is always exactly 64 chars, so there is no space-padding surprise.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "checksum", nullable = false, updatable = false, length = 64)
    var checksum: String = "",
    @Column(name = "canon_version", nullable = false, updatable = false)
    var canonVersion: String = "",
    @Convert(converter = RevisionStatusConverter::class)
    @Column(name = "status", nullable = false)
    var status: RevisionStatus = RevisionStatus.DRAFT,
    @Column(name = "created_by", nullable = false, updatable = false)
    var createdBy: UUID? = null,
    @Column(name = "notes")
    var notes: String? = null,
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.EPOCH,
    @Column(name = "published_at")
    var publishedAt: Instant? = null,
) {
    @PrePersist
    fun onCreate() {
        createdAt = Instant.now()
    }
}
