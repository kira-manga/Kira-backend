package me.manga.kira.backend.sourceconfig.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

/**
 * Spring Data JPA repository for [SourceConfigRevisionEntity] (PLAN §2 infrastructure). Wrapped by
 * [JpaRevisionRepositoryAdapter].
 *
 * [markSuperseded]/[markPublished] are native `@Modifying` updates executed in that ORDER inside the
 * publish transaction so the `uq_one_published_per_source` partial unique index is never momentarily
 * violated (PLAN §9 supersede-then-publish); `flushAutomatically` flushes a just-inserted rollback
 * revision first, `clearAutomatically` evicts stale managed copies.
 */
interface SpringDataRevisionRepository : JpaRepository<SourceConfigRevisionEntity, UUID> {

    fun findBySourceConfigIdAndRevisionNumber(
        sourceConfigId: UUID,
        revisionNumber: Int,
    ): SourceConfigRevisionEntity?

    fun findAllBySourceConfigIdOrderByRevisionNumberAsc(sourceConfigId: UUID): List<SourceConfigRevisionEntity>

    @Query("SELECT max(r.revisionNumber) FROM SourceConfigRevisionEntity r WHERE r.sourceConfigId = :sourceId")
    fun maxRevisionNumber(
        @Param("sourceId") sourceConfigId: UUID,
    ): Int?

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        value = "UPDATE source_config_revisions SET status = 'superseded' WHERE id = :id",
        nativeQuery = true,
    )
    fun markSuperseded(
        @Param("id") id: UUID,
    )

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        value = "UPDATE source_config_revisions SET status = 'published', published_at = :publishedAt WHERE id = :id",
        nativeQuery = true,
    )
    fun markPublished(
        @Param("id") id: UUID,
        @Param("publishedAt") publishedAt: Instant,
    )
}
