package me.manga.kira.backend.sourceconfig.infrastructure

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
 * JPA entity for `source_validation_results` (PLAN §5 V2). [errors]/[warnings] map the `jsonb` columns
 * as canonical JSON-array **strings** via `@JdbcTypeCode(SqlTypes.JSON)` (Hibernate 6) — the adapter
 * owns the `{code, path, message}` (de)serialization, so no framework serialization leaks into the pure
 * validation types. The row is insert-only (history is kept), so there is no `@PreUpdate`.
 */
@Entity
@Table(name = "source_validation_results")
class SourceValidationResultEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID? = null,
    @Column(name = "revision_id", nullable = false, updatable = false)
    var revisionId: UUID? = null,
    @Column(name = "valid", nullable = false)
    var valid: Boolean = false,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "errors", nullable = false)
    var errors: String = "[]",
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "warnings", nullable = false)
    var warnings: String = "[]",
    @Column(name = "rules_version", nullable = false)
    var rulesVersion: String = "",
    @Column(name = "validated_at", nullable = false)
    var validatedAt: Instant = Instant.EPOCH,
)
