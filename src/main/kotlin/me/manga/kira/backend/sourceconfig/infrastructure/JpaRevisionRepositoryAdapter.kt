package me.manga.kira.backend.sourceconfig.infrastructure

import me.manga.kira.backend.sourceconfig.domain.HistoryWindow
import me.manga.kira.backend.sourceconfig.domain.NewRevision
import me.manga.kira.backend.sourceconfig.domain.RevisionRepository
import me.manga.kira.backend.sourceconfig.domain.RevisionStatus
import me.manga.kira.backend.sourceconfig.domain.SourceRevision
import me.manga.kira.backend.sourceconfig.domain.SourceRevisionSummary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Adapts [SpringDataRevisionRepository] to the pure-Kotlin [RevisionRepository] port (PLAN §2).
 * Detail reads return immutable content; history reads project only bounded metadata. The adapter
 * flips only the status column (never rewrites content). [nextRevisionNumber] is `max+1` — correct only because the
 * caller holds the source head `FOR UPDATE` lock, which serializes concurrent creations (PLAN §5).
 */
@Repository
class JpaRevisionRepositoryAdapter(private val jpa: SpringDataRevisionRepository, private val jdbcTemplate: JdbcTemplate) : RevisionRepository {

    override fun findById(id: UUID): SourceRevision? = jpa.findById(id).map { it.toDomain() }.orElse(null)

    override fun findBySourceAndNumber(sourceConfigId: UUID, revisionNumber: Int): SourceRevision? =
        jpa.findBySourceConfigIdAndRevisionNumber(sourceConfigId, revisionNumber)?.toDomain()

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

    override fun nextRevisionNumber(sourceConfigId: UUID): Int = (jpa.maxRevisionNumber(sourceConfigId) ?: 0) + 1

    override fun latestRevisionNumber(sourceConfigId: UUID): Int? = jpa.maxRevisionNumber(sourceConfigId)

    override fun findSummaryWindow(sourceConfigId: UUID, beforeRevision: Int?, limit: Int): List<SourceRevisionSummary> {
        require(limit in 1..HistoryWindow.MAX_SIZE + 1)
        val sql =
            "SELECT page.revision_number, page.status, page.checksum, page.created_by, page.created_at, page.published_at, " +
                "(SELECT v.valid FROM source_validation_results v WHERE v.revision_id = page.id " +
                "ORDER BY v.validated_at DESC LIMIT 1) AS valid " +
                "FROM (SELECT id, revision_number, status, checksum, created_by, created_at, published_at " +
                "FROM source_config_revisions WHERE source_config_id = ? " +
                (if (beforeRevision == null) "" else "AND revision_number < ? ") +
                "ORDER BY revision_number DESC LIMIT ?) page ORDER BY page.revision_number DESC"
        return if (beforeRevision == null) {
            jdbcTemplate.query(sql, SUMMARY_MAPPER, sourceConfigId, limit)
        } else {
            jdbcTemplate.query(sql, SUMMARY_MAPPER, sourceConfigId, beforeRevision, limit)
        }
    }

    override fun markSuperseded(revisionId: UUID) = jpa.markSuperseded(revisionId)

    override fun markPublished(revisionId: UUID, publishedAt: Instant) = jpa.markPublished(revisionId, publishedAt)

    private fun SourceConfigRevisionEntity.toDomain(): SourceRevision = SourceRevision(
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

    private companion object {
        val SUMMARY_MAPPER =
            RowMapper { rs, _ ->
                SourceRevisionSummary(
                    revisionNumber = rs.getInt("revision_number"),
                    status = RevisionStatus.fromWire(rs.getString("status")),
                    checksum = rs.getString("checksum"),
                    createdBy = rs.getObject("created_by", UUID::class.java),
                    createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                    publishedAt = rs.getObject("published_at", OffsetDateTime::class.java)?.toInstant(),
                    valid = rs.getObject("valid", Boolean::class.javaObjectType),
                )
            }
    }
}
