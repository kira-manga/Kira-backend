package me.manga.kira.backend.sourceconfig.infrastructure

import me.manga.kira.backend.sourceconfig.domain.NewSourceConfig
import me.manga.kira.backend.sourceconfig.domain.SourceConfigHead
import me.manga.kira.backend.sourceconfig.domain.SourceConfigRepository
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * Adapts [SpringDataSourceConfigRepository] to the pure-Kotlin [SourceConfigRepository] port (PLAN §2).
 * Entity↔domain mapping is explicit here so entities never escape this layer.
 */
@Repository
class JpaSourceConfigRepositoryAdapter(
    private val jpa: SpringDataSourceConfigRepository,
) : SourceConfigRepository {

    override fun findByApi(api: String): SourceConfigHead? = jpa.findByApi(api)?.toDomain()

    override fun findById(id: UUID): SourceConfigHead? = jpa.findById(id).map { it.toDomain() }.orElse(null)

    override fun existsByApi(api: String): Boolean = jpa.existsByApi(api)

    override fun create(spec: NewSourceConfig): SourceConfigHead {
        val entity =
            SourceConfigEntity(
                api = spec.api,
                displayName = spec.displayName,
                language = spec.language,
                engine = spec.engine,
                status = spec.status,
                position = spec.position,
                baseUrl = spec.baseUrl,
                adult = spec.adult,
            )
        return jpa.save(entity).toDomain()
    }

    private fun SourceConfigEntity.toDomain(): SourceConfigHead =
        SourceConfigHead(
            id = requireNotNull(id) { "persisted SourceConfigEntity must have an id" },
            api = api,
            displayName = displayName,
            language = language,
            engine = engine,
            status = status,
            position = position,
            baseUrl = baseUrl,
            adult = adult,
            currentPublishedRevisionId = currentPublishedRevisionId,
            createdAt = createdAt,
            updatedAt = updatedAt,
            publishedAt = publishedAt,
        )
}
