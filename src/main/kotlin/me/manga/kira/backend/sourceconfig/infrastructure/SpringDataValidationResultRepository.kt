package me.manga.kira.backend.sourceconfig.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

/**
 * Spring Data JPA repository for [SourceValidationResultEntity] (PLAN §2 infrastructure). Wrapped by
 * [JpaValidationResultRepositoryAdapter]. The derived `findFirst…OrderByValidatedAtDesc` uses the
 * `idx_validation_revision (revision_id, validated_at DESC)` index (PLAN §5).
 */
interface SpringDataValidationResultRepository : JpaRepository<SourceValidationResultEntity, UUID> {

    fun findFirstByRevisionIdOrderByValidatedAtDesc(revisionId: UUID): SourceValidationResultEntity?
}
