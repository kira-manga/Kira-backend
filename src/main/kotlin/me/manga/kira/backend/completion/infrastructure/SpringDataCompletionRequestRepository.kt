package me.manga.kira.backend.completion.infrastructure

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

/**
 * Spring Data JPA repository for [CompletionRequestEntity] (PLAN §2 infrastructure). Wrapped by
 * [JpaCompletionRequestRepositoryAdapter]. Status claims are native `@Modifying` updates
 * (`clearAutomatically` evicts the stale managed copy) so a status flip needs no prior load; the
 * page query pairs a derived finder with an explicit newest-first `Sort` from the adapter.
 */
interface SpringDataCompletionRequestRepository : JpaRepository<CompletionRequestEntity, UUID> {
    @Modifying(clearAutomatically = true)
    @Query(
        value = "UPDATE completion_requests SET status = 'RUNNING', updated_at = :at WHERE id = :id AND status = 'PENDING'",
        nativeQuery = true,
    )
    fun tryMarkRunning(@Param("id") id: UUID, @Param("at") at: Instant): Int

    @Modifying(clearAutomatically = true)
    @Query(
        value = """
            UPDATE completion_requests SET status = :status, updated_at = :at
            WHERE id = :id AND (
              (:status = 'SUCCEEDED' AND status = 'RUNNING') OR
              (:status = 'FAILED' AND status IN ('PENDING', 'RUNNING'))
            )
        """,
        nativeQuery = true,
    )
    fun tryFinish(@Param("id") id: UUID, @Param("status") status: String, @Param("at") at: Instant): Int

    fun findByUserId(userId: UUID, pageable: Pageable): Page<CompletionRequestEntity>
}
