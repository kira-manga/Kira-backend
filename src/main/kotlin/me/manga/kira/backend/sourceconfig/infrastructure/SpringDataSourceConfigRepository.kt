package me.manga.kira.backend.sourceconfig.infrastructure

import me.manga.kira.backend.sourceconfig.domain.SourceLifecycleStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

/**
 * Spring Data JPA repository for [SourceConfigEntity] (PLAN §2 infrastructure). Wrapped by
 * [JpaSourceConfigRepositoryAdapter], which exposes the pure-Kotlin domain port.
 *
 * The publish/lifecycle mutations are **native `@Modifying` updates** (not entity dirty-checking) so
 * they execute against the transaction's connection immediately and are visible to the subsequent
 * same-transaction candidate-assembly read (a `JdbcTemplate` native SELECT); `flushAutomatically`
 * flushes any pending inserts first, `clearAutomatically` evicts now-stale managed entities (PLAN §9).
 */
interface SpringDataSourceConfigRepository : JpaRepository<SourceConfigEntity, UUID> {

    fun findByApi(api: String): SourceConfigEntity?

    fun existsByApi(api: String): Boolean

    fun findAllByOrderByPositionAscApiAsc(): List<SourceConfigEntity>

    fun findAllByStatusOrderByPositionAscApiAsc(status: SourceLifecycleStatus): List<SourceConfigEntity>

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        value =
            "UPDATE source_configs SET status = :status, " +
                "current_published_revision_id = :revId, " +
                "published_at = COALESCE(published_at, :publishedAt), " +
                "display_name = :displayName, language = :language, engine = :engine, " +
                "base_url = :baseUrl, adult = :adult, updated_at = :updatedAt WHERE id = :id",
        nativeQuery = true,
    )
    @Suppress("LongParameterList")
    fun applyPublishedRevision(
        @Param("id") id: UUID,
        @Param("revId") revId: UUID,
        @Param("status") status: String,
        @Param("publishedAt") publishedAt: Instant,
        @Param("displayName") displayName: String,
        @Param("language") language: String,
        @Param("engine") engine: String,
        @Param("baseUrl") baseUrl: String,
        @Param("adult") adult: Boolean,
        @Param("updatedAt") updatedAt: Instant,
    )

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        value = "UPDATE source_configs SET status = :status, updated_at = :updatedAt WHERE id = :id",
        nativeQuery = true,
    )
    fun updateStatusNative(
        @Param("id") id: UUID,
        @Param("status") status: String,
        @Param("updatedAt") updatedAt: Instant,
    )
}
