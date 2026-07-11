package me.manga.kira.backend.sourceconfig.infrastructure

import me.manga.kira.backend.sourceconfig.domain.NewRevision
import me.manga.kira.backend.sourceconfig.domain.RevisionRepository
import me.manga.kira.backend.sourceconfig.domain.SourceRevision
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * Adapts [SpringDataRevisionRepository] to the pure-Kotlin [RevisionRepository] port (PLAN §2).
 * Revisions are immutable, so this adapter only inserts + reads whole rows (no partial updates).
 */
@Repository
class JpaRevisionRepositoryAdapter(
    private val jpa: SpringDataRevisionRepository,
) : RevisionRepository {

    override fun findById(id: UUID): SourceRevision? = jpa.findById(id).map { it.toDomain() }.orElse(null)

    override fun findBySourceAndNumber(
        sourceConfigId: UUID,
        revisionNumber: Int,
    ): SourceRevision? = jpa.findBySourceConfigIdAndRevisionNumber(sourceConfigId, revisionNumber)?.toDomain()

    override fun create(spec: NewRevision): SourceRevision {
        val entity =
            SourceConfigRevisionEntity(
                sourceConfigId = spec.sourceConfigId,
                revisionNumber = spec.revisionNumber,
                configCanonicalJson = spec.configCanonicalJson,
                checksum = spec.checksum,
                canonVersion = spec.canonVersion,
                status = spec.status,
                createdBy = spec.createdBy,
                notes = spec.notes,
            )
        return jpa.save(entity).toDomain()
    }

    private fun SourceConfigRevisionEntity.toDomain(): SourceRevision =
        SourceRevision(
            id = requireNotNull(id) { "persisted SourceConfigRevisionEntity must have an id" },
            sourceConfigId = requireNotNull(sourceConfigId),
            revisionNumber = revisionNumber,
            configCanonicalJson = configCanonicalJson,
            checksum = checksum,
            canonVersion = canonVersion,
            status = status,
            createdBy = requireNotNull(createdBy),
            notes = notes,
            createdAt = createdAt,
            publishedAt = publishedAt,
        )
}
