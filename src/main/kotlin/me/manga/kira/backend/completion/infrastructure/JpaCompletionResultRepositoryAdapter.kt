package me.manga.kira.backend.completion.infrastructure

import me.manga.kira.backend.completion.domain.CompletionErrorCode
import me.manga.kira.backend.completion.domain.CompletionResultRecord
import me.manga.kira.backend.completion.domain.CompletionResultRepository
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * Adapts [SpringDataCompletionResultRepository] to the pure-Kotlin [CompletionResultRepository] port
 * (PLAN §2). `error_code` is stored as its stable enum name and mapped back to [CompletionErrorCode].
 */
@Repository
class JpaCompletionResultRepositoryAdapter(
    private val jpa: SpringDataCompletionResultRepository,
) : CompletionResultRepository {
    override fun insert(result: CompletionResultRecord) {
        jpa.save(
            CompletionResultEntity(
                requestId = result.requestId,
                result = result.result,
                error = result.error,
                errorCode = result.errorCode?.name,
                latencyMs = result.latencyMs,
                createdAt = result.createdAt,
            ),
        )
    }

    override fun findByRequestId(requestId: UUID): CompletionResultRecord? = jpa.findByRequestId(requestId)?.toDomain()

    override fun findByRequestIds(requestIds: List<UUID>): Map<UUID, CompletionResultRecord> =
        if (requestIds.isEmpty()) {
            emptyMap()
        } else {
            jpa.findByRequestIdIn(requestIds).associate { requireNotNull(it.requestId) to it.toDomain() }
        }

    private fun CompletionResultEntity.toDomain(): CompletionResultRecord =
        CompletionResultRecord(
            requestId = requireNotNull(requestId),
            result = result,
            error = error,
            errorCode = errorCode?.let { CompletionErrorCode.valueOf(it) },
            latencyMs = latencyMs,
            createdAt = createdAt,
        )
}
