package me.manga.kira.backend.completion.infrastructure

import me.manga.kira.backend.completion.domain.CompletionRequestPage
import me.manga.kira.backend.completion.domain.CompletionRequestRecord
import me.manga.kira.backend.completion.domain.CompletionRequestRepository
import me.manga.kira.backend.completion.domain.CompletionStatus
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/**
 * Adapts [SpringDataCompletionRequestRepository] to the pure-Kotlin [CompletionRequestRepository] port
 * (PLAN §2). The list page is sorted newest-first (`created_at DESC`) to match `idx_completions_user`
 * (PLAN §4.6). Timestamps are passed in from the injected Clock — the adapter does not read wall time.
 */
@Repository
class JpaCompletionRequestRepositoryAdapter(
    private val jpa: SpringDataCompletionRequestRepository,
) : CompletionRequestRepository {
    override fun insertPending(
        userId: UUID,
        provider: String,
        model: String,
        prompt: String,
        now: Instant,
    ): UUID {
        val entity =
            CompletionRequestEntity(
                userId = userId,
                provider = provider,
                model = model,
                prompt = prompt,
                status = CompletionStatus.PENDING,
                createdAt = now,
                updatedAt = now,
            )
        return requireNotNull(jpa.save(entity).id) { "persisted CompletionRequestEntity must have an id" }
    }

    override fun updateStatus(
        id: UUID,
        status: CompletionStatus,
        now: Instant,
    ) = jpa.updateStatus(id, status.name, now)

    override fun findById(id: UUID): CompletionRequestRecord? = jpa.findById(id).map { it.toDomain() }.orElse(null)

    override fun findPageByUser(
        userId: UUID,
        page: Int,
        size: Int,
    ): CompletionRequestPage {
        val pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))
        val result = jpa.findByUserId(userId, pageable)
        return CompletionRequestPage(items = result.content.map { it.toDomain() }, total = result.totalElements)
    }

    private fun CompletionRequestEntity.toDomain(): CompletionRequestRecord =
        CompletionRequestRecord(
            id = requireNotNull(id) { "persisted CompletionRequestEntity must have an id" },
            userId = requireNotNull(userId),
            provider = provider,
            model = model,
            prompt = prompt,
            status = status,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
}
