package me.manga.kira.backend.sourceconfig.infrastructure

import me.manga.kira.backend.sourceconfig.domain.NewSourceChangeset
import me.manga.kira.backend.sourceconfig.domain.SourceChangeset
import me.manga.kira.backend.sourceconfig.domain.SourceChangesetRepository
import me.manga.kira.backend.sourceconfig.domain.SourceChangesetStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JdbcSourceChangesetRepository(private val jdbc: JdbcTemplate) : SourceChangesetRepository {
    override fun create(spec: NewSourceChangeset): SourceChangeset {
        val at = OffsetDateTime.ofInstant(spec.at, ZoneOffset.UTC)
        jdbc.update(
            """
            INSERT INTO source_changesets
                (id, name, description, operations_json, status, version,
                 created_by, updated_by, created_at, updated_at)
            VALUES (?, ?, ?, ?, 'open', 1, ?, ?, ?, ?)
            """.trimIndent(),
            spec.id,
            spec.name,
            spec.description,
            spec.operationsJson,
            spec.actorId,
            spec.actorId,
            at,
            at,
        )
        return requireNotNull(findById(spec.id))
    }

    override fun findById(id: UUID): SourceChangeset? = jdbc.query("SELECT * FROM source_changesets WHERE id = ?", mapper, id).firstOrNull()

    override fun findAll(): List<SourceChangeset> = jdbc.query("SELECT * FROM source_changesets ORDER BY updated_at DESC, id", mapper)

    override fun update(
        id: UUID,
        expectedVersion: Long,
        name: String,
        description: String?,
        operationsJson: String,
        actorId: UUID,
        at: Instant,
    ): SourceChangeset? {
        val changed =
            jdbc.update(
                """
                UPDATE source_changesets
                   SET name = ?, description = ?, operations_json = ?,
                       updated_by = ?, updated_at = ?, version = version + 1
                 WHERE id = ? AND version = ? AND status = 'open'
                """.trimIndent(),
                name,
                description,
                operationsJson,
                actorId,
                OffsetDateTime.ofInstant(at, ZoneOffset.UTC),
                id,
                expectedVersion,
            )
        return if (changed == 1) findById(id) else null
    }

    override fun lockOpen(id: UUID, expectedVersion: Long): SourceChangeset? = jdbc.query(
        "SELECT * FROM source_changesets WHERE id = ? AND version = ? AND status = 'open' FOR UPDATE",
        mapper,
        id,
        expectedVersion,
    ).firstOrNull()

    override fun markApplied(id: UUID, expectedVersion: Long, documentRevision: Long, actorId: UUID, at: Instant): SourceChangeset? {
        val changed =
            jdbc.update(
                """
                UPDATE source_changesets
                   SET status = 'applied', applied_document_revision = ?, applied_at = ?,
                       updated_by = ?, updated_at = ?, version = version + 1
                 WHERE id = ? AND version = ? AND status = 'open'
                """.trimIndent(),
                documentRevision,
                OffsetDateTime.ofInstant(at, ZoneOffset.UTC),
                actorId,
                OffsetDateTime.ofInstant(at, ZoneOffset.UTC),
                id,
                expectedVersion,
            )
        return if (changed == 1) findById(id) else null
    }

    override fun discard(id: UUID, expectedVersion: Long, actorId: UUID, at: Instant): SourceChangeset? {
        val changed =
            jdbc.update(
                """
                UPDATE source_changesets
                   SET status = 'discarded', updated_by = ?, updated_at = ?, version = version + 1
                 WHERE id = ? AND version = ? AND status = 'open'
                """.trimIndent(),
                actorId,
                OffsetDateTime.ofInstant(at, ZoneOffset.UTC),
                id,
                expectedVersion,
            )
        return if (changed == 1) findById(id) else null
    }

    private companion object {
        val mapper =
            RowMapper { rs: ResultSet, _: Int ->
                SourceChangeset(
                    id = rs.getObject("id", UUID::class.java),
                    name = rs.getString("name"),
                    description = rs.getString("description"),
                    operationsJson = rs.getString("operations_json"),
                    status = SourceChangesetStatus.entries.single { it.wire == rs.getString("status") },
                    version = rs.getLong("version"),
                    appliedDocumentRevision = rs.getLong("applied_document_revision").takeUnless { rs.wasNull() },
                    createdBy = rs.getObject("created_by", UUID::class.java),
                    updatedBy = rs.getObject("updated_by", UUID::class.java),
                    createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                    updatedAt = rs.getObject("updated_at", OffsetDateTime::class.java).toInstant(),
                    appliedAt = rs.getObject("applied_at", OffsetDateTime::class.java)?.toInstant(),
                )
            }
    }
}
