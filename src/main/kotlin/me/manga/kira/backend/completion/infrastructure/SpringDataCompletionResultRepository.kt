package me.manga.kira.backend.completion.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

/**
 * Spring Data JPA repository for [CompletionResultEntity] (PLAN §2 infrastructure). Wrapped by
 * [JpaCompletionResultRepositoryAdapter].
 */
interface SpringDataCompletionResultRepository : JpaRepository<CompletionResultEntity, UUID> {
    fun findByRequestId(requestId: UUID): CompletionResultEntity?

    fun findByRequestIdIn(requestIds: Collection<UUID>): List<CompletionResultEntity>
}
