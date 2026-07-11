package me.manga.kira.backend.sourceconfig.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

/**
 * Spring Data JPA repository for [SourceConfigRevisionEntity] (PLAN §2 infrastructure). Wrapped by
 * [JpaRevisionRepositoryAdapter].
 */
interface SpringDataRevisionRepository : JpaRepository<SourceConfigRevisionEntity, UUID> {

    fun findBySourceConfigIdAndRevisionNumber(
        sourceConfigId: UUID,
        revisionNumber: Int,
    ): SourceConfigRevisionEntity?
}
