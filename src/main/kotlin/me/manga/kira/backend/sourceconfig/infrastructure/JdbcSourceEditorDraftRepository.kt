package me.manga.kira.backend.sourceconfig.infrastructure

import me.manga.kira.backend.sourceconfig.domain.NewSourceEditorDraft
import me.manga.kira.backend.sourceconfig.domain.SourceEditorDraft
import me.manga.kira.backend.sourceconfig.domain.SourceEditorDraftRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JdbcSourceEditorDraftRepository(private val jdbc: JdbcTemplate) : SourceEditorDraftRepository {
    override fun findBySourceConfigId(sourceConfigId: UUID): SourceEditorDraft? = jdbc.query(
        "SELECT * FROM source_editor_drafts WHERE source_config_id = ?",
        MAPPER,
        sourceConfigId,
    ).firstOrNull()

    override fun create(spec: NewSourceEditorDraft): SourceEditorDraft {
        val id = UUID.randomUUID()
        val at = OffsetDateTime.ofInstant(spec.at, ZoneOffset.UTC)
        jdbc.update(
            """
            INSERT INTO source_editor_drafts
                (id, source_config_id, based_on_revision_number, content_json, version,
                 created_by, updated_by, created_at, updated_at)
            VALUES (?, ?, ?, ?, 1, ?, ?, ?, ?)
            """.trimIndent(),
            id,
            spec.sourceConfigId,
            spec.basedOnRevisionNumber,
            spec.contentJson,
            spec.actorId,
            spec.actorId,
            at,
            at,
        )
        return requireNotNull(findBySourceConfigId(spec.sourceConfigId))
    }

    override fun updateContent(id: UUID, expectedVersion: Long, contentJson: String, actorId: UUID, updatedAt: Instant): SourceEditorDraft? = updateAndRead(
        id = id,
        sql =
        """
            UPDATE source_editor_drafts
            SET content_json = ?, updated_by = ?, updated_at = ?, version = version + 1
            WHERE id = ? AND version = ?
        """.trimIndent(),
        contentJson,
        actorId,
        OffsetDateTime.ofInstant(updatedAt, ZoneOffset.UTC),
        id,
        expectedVersion,
    )

    override fun updateBaseline(
        id: UUID,
        expectedVersion: Long,
        basedOnRevisionNumber: Int,
        contentJson: String,
        actorId: UUID,
        updatedAt: Instant,
    ): SourceEditorDraft? = updateAndRead(
        id = id,
        sql =
        """
            UPDATE source_editor_drafts
            SET based_on_revision_number = ?, content_json = ?, updated_by = ?, updated_at = ?,
                version = version + 1
            WHERE id = ? AND version = ?
        """.trimIndent(),
        basedOnRevisionNumber,
        contentJson,
        actorId,
        OffsetDateTime.ofInstant(updatedAt, ZoneOffset.UTC),
        id,
        expectedVersion,
    )

    override fun delete(id: UUID, expectedVersion: Long): Boolean = jdbc.update(
        "DELETE FROM source_editor_drafts WHERE id = ? AND version = ?",
        id,
        expectedVersion,
    ) == 1

    private fun updateAndRead(id: UUID, sql: String, vararg args: Any): SourceEditorDraft? {
        if (jdbc.update(sql, *args) != 1) return null
        return jdbc.query("SELECT * FROM source_editor_drafts WHERE id = ?", MAPPER, id).firstOrNull()
            ?: error("updated source editor draft disappeared")
    }

    private companion object {
        val MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                SourceEditorDraft(
                    id = rs.getObject("id", UUID::class.java),
                    sourceConfigId = rs.getObject("source_config_id", UUID::class.java),
                    basedOnRevisionNumber = rs.getInt("based_on_revision_number"),
                    contentJson = rs.getString("content_json"),
                    version = rs.getLong("version"),
                    createdBy = rs.getObject("created_by", UUID::class.java),
                    updatedBy = rs.getObject("updated_by", UUID::class.java),
                    createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                    updatedAt = rs.getObject("updated_at", OffsetDateTime::class.java).toInstant(),
                )
            }
    }
}
