package me.manga.kira.backend.tutorial.infrastructure

import me.manga.kira.backend.tutorial.domain.StoredCategory
import me.manga.kira.backend.tutorial.domain.StoredMedia
import me.manga.kira.backend.tutorial.domain.StoredRevision
import me.manga.kira.backend.tutorial.domain.StoredTutorial
import me.manga.kira.backend.tutorial.domain.TutorialLifecycle
import me.manga.kira.backend.tutorial.domain.TutorialRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JdbcTutorialRepository(private val jdbc: JdbcTemplate) : TutorialRepository {
    override fun tutorialCount(): Int = jdbc.queryForObject("SELECT count(*) FROM tutorials", Int::class.java) ?: 0
    override fun categoryCount(): Int = jdbc.queryForObject("SELECT count(*) FROM tutorial_categories", Int::class.java) ?: 0

    override fun createCategory(id: UUID, slug: String, position: Int, now: Instant): StoredCategory {
        jdbc.execute("LOCK TABLE tutorial_categories IN SHARE ROW EXCLUSIVE MODE")
        jdbc.update(
            "INSERT INTO tutorial_categories (id, slug, status, position, created_at, updated_at) VALUES (?, ?, 'DRAFT', ?, ?, ?)",
            id,
            slug,
            position,
            at(now),
            at(now),
        )
        return requireNotNull(findCategory(id))
    }

    override fun findCategory(id: UUID, lock: Boolean): StoredCategory? = jdbc.query(
        "SELECT * FROM tutorial_categories WHERE id = ?" + if (lock) " FOR UPDATE" else "",
        CATEGORY_MAPPER,
        id,
    ).firstOrNull()

    override fun listCategories(publicOnly: Boolean): List<StoredCategory> {
        val where = if (publicOnly) " WHERE status = 'PUBLISHED' AND published_revision_id IS NOT NULL" else ""
        return jdbc.query("SELECT * FROM tutorial_categories$where ORDER BY position, slug", CATEGORY_MAPPER)
    }

    override fun nextCategoryPosition(): Int {
        lockCategoryOrder()
        return jdbc.queryForObject("SELECT COALESCE(MAX(position) + 1, 0) FROM tutorial_categories", Int::class.java) ?: 0
    }

    override fun lockCategoryOrder() {
        jdbc.execute("LOCK TABLE tutorial_categories IN SHARE ROW EXCLUSIVE MODE")
    }

    override fun createCategoryRevision(id: UUID, categoryId: UUID, contentJson: String, createdBy: UUID?, now: Instant): StoredRevision {
        val number = jdbc.queryForObject(
            "SELECT COALESCE(MAX(revision_number) + 1, 1) FROM tutorial_category_revisions WHERE category_id = ?",
            Int::class.java,
            categoryId,
        ) ?: 1
        jdbc.update(
            "INSERT INTO tutorial_category_revisions (id, category_id, revision_number, content, created_by, created_at) VALUES (?, ?, ?, ?::jsonb, ?, ?)",
            id,
            categoryId,
            number,
            contentJson,
            createdBy,
            at(now),
        )
        return StoredRevision(id, categoryId, number, null, contentJson, createdBy, now)
    }

    override fun findCategoryRevision(categoryId: UUID, revisionNumber: Int): StoredRevision? = jdbc.query(
        "SELECT * FROM tutorial_category_revisions WHERE category_id = ? AND revision_number = ?",
        CATEGORY_REVISION_MAPPER,
        categoryId,
        revisionNumber,
    ).firstOrNull()

    override fun listCategoryRevisions(categoryId: UUID): List<StoredRevision> = jdbc.query(
        "SELECT * FROM tutorial_category_revisions WHERE category_id = ? ORDER BY revision_number DESC",
        CATEGORY_REVISION_MAPPER,
        categoryId,
    )

    override fun publishCategory(categoryId: UUID, revisionId: UUID, now: Instant) {
        jdbc.update(
            "UPDATE tutorial_categories SET published_revision_id = ?, status = 'PUBLISHED', updated_at = ? WHERE id = ?",
            revisionId,
            at(now),
            categoryId,
        )
    }

    override fun updateCategoryStatus(categoryId: UUID, status: TutorialLifecycle, now: Instant) {
        jdbc.update("UPDATE tutorial_categories SET status = ?, updated_at = ? WHERE id = ?", status.name, at(now), categoryId)
    }

    override fun reorderCategories(items: List<Pair<UUID, Int>>, now: Instant) {
        jdbc.execute("LOCK TABLE tutorial_categories IN SHARE ROW EXCLUSIVE MODE")
        jdbc.update("UPDATE tutorial_categories SET position = position + 1000000")
        items.forEach { (id, position) ->
            jdbc.update("UPDATE tutorial_categories SET position = ?, updated_at = ? WHERE id = ?", position, at(now), id)
        }
    }

    override fun createTutorial(id: UUID, slug: String, position: Int, featuredPosition: Int?, now: Instant): StoredTutorial {
        jdbc.execute("LOCK TABLE tutorials IN SHARE ROW EXCLUSIVE MODE")
        jdbc.update(
            "INSERT INTO tutorials (id, slug, status, position, featured_position, created_at, updated_at) VALUES (?, ?, 'DRAFT', ?, ?, ?, ?)",
            id,
            slug,
            position,
            featuredPosition,
            at(now),
            at(now),
        )
        return requireNotNull(findTutorial(id))
    }

    override fun findTutorial(id: UUID, lock: Boolean): StoredTutorial? = jdbc.query(
        "SELECT * FROM tutorials WHERE id = ?" + if (lock) " FOR UPDATE" else "",
        TUTORIAL_MAPPER,
        id,
    ).firstOrNull()

    override fun findPublicTutorialBySlug(slug: String): StoredTutorial? = jdbc.query(
        "SELECT t.* FROM tutorials t JOIN tutorial_revisions r ON r.id = t.published_revision_id " +
            "JOIN tutorial_categories c ON c.id = r.category_id " +
            "WHERE t.slug = ? AND t.status = 'PUBLISHED' AND c.status = 'PUBLISHED' AND c.published_revision_id IS NOT NULL",
        TUTORIAL_MAPPER,
        slug,
    ).firstOrNull()

    override fun listTutorials(publicOnly: Boolean, categorySlug: String?, featured: Boolean?): List<StoredTutorial> {
        val conditions = mutableListOf<String>()
        val args = mutableListOf<Any>()
        if (publicOnly) {
            conditions += "t.status = 'PUBLISHED'"
            conditions += "c.status = 'PUBLISHED'"
            conditions += "c.published_revision_id IS NOT NULL"
        }
        categorySlug?.let {
            conditions += "c.slug = ?"
            args += it
        }
        featured?.let { conditions += if (it) "t.featured_position IS NOT NULL" else "t.featured_position IS NULL" }
        val where = if (conditions.isEmpty()) "" else " WHERE " + conditions.joinToString(" AND ")
        val order = if (featured == true) " ORDER BY t.featured_position, t.position, t.slug" else " ORDER BY t.position, t.slug"
        return jdbc.query(
            "SELECT DISTINCT t.* FROM tutorials t LEFT JOIN tutorial_revisions r ON r.id = t.published_revision_id " +
                "LEFT JOIN tutorial_categories c ON c.id = r.category_id$where$order",
            TUTORIAL_MAPPER,
            *args.toTypedArray(),
        )
    }

    override fun nextTutorialPosition(): Int {
        lockTutorialOrder()
        return jdbc.queryForObject("SELECT COALESCE(MAX(position) + 1, 0) FROM tutorials", Int::class.java) ?: 0
    }

    override fun lockTutorialOrder() {
        jdbc.execute("LOCK TABLE tutorials IN SHARE ROW EXCLUSIVE MODE")
    }

    override fun createTutorialRevision(
        id: UUID,
        tutorialId: UUID,
        categoryId: UUID,
        contentJson: String,
        media: Map<String, UUID>,
        createdBy: UUID?,
        now: Instant,
    ): StoredRevision {
        val number = jdbc.queryForObject(
            "SELECT COALESCE(MAX(revision_number) + 1, 1) FROM tutorial_revisions WHERE tutorial_id = ?",
            Int::class.java,
            tutorialId,
        ) ?: 1
        jdbc.update(
            "INSERT INTO tutorial_revisions (id, tutorial_id, revision_number, category_id, content, created_by, created_at) " +
                "VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)",
            id,
            tutorialId,
            number,
            categoryId,
            contentJson,
            createdBy,
            at(now),
        )
        media.forEach { (slot, mediaId) ->
            jdbc.update("INSERT INTO tutorial_revision_media (revision_id, media_id, slot_key) VALUES (?, ?, ?)", id, mediaId, slot)
        }
        return StoredRevision(id, tutorialId, number, categoryId, contentJson, createdBy, now)
    }

    override fun findTutorialRevision(tutorialId: UUID, revisionNumber: Int): StoredRevision? = jdbc.query(
        "SELECT * FROM tutorial_revisions WHERE tutorial_id = ? AND revision_number = ?",
        TUTORIAL_REVISION_MAPPER,
        tutorialId,
        revisionNumber,
    ).firstOrNull()

    override fun findTutorialRevisionById(id: UUID): StoredRevision? = jdbc.query(
        "SELECT * FROM tutorial_revisions WHERE id = ?",
        TUTORIAL_REVISION_MAPPER,
        id,
    ).firstOrNull()

    override fun listTutorialRevisions(tutorialId: UUID): List<StoredRevision> = jdbc.query(
        "SELECT * FROM tutorial_revisions WHERE tutorial_id = ? ORDER BY revision_number DESC",
        TUTORIAL_REVISION_MAPPER,
        tutorialId,
    )

    override fun publishTutorial(tutorialId: UUID, revisionId: UUID, mediaIds: Set<UUID>, now: Instant) {
        jdbc.update(
            "UPDATE tutorials SET published_revision_id = ?, status = 'PUBLISHED', updated_at = ? WHERE id = ?",
            revisionId,
            at(now),
            tutorialId,
        )
        mediaIds.forEach { jdbc.update("UPDATE tutorial_media SET published = true WHERE id = ?", it) }
    }

    override fun updateTutorialStatus(tutorialId: UUID, status: TutorialLifecycle, now: Instant) {
        jdbc.update("UPDATE tutorials SET status = ?, updated_at = ? WHERE id = ?", status.name, at(now), tutorialId)
    }

    override fun reorderTutorials(items: List<Triple<UUID, Int, Int?>>, now: Instant) {
        jdbc.execute("LOCK TABLE tutorials IN SHARE ROW EXCLUSIVE MODE")
        jdbc.update("UPDATE tutorials SET position = position + 1000000, featured_position = NULL")
        items.forEach { (id, position, featuredPosition) ->
            jdbc.update(
                "UPDATE tutorials SET position = ?, featured_position = ?, updated_at = ? WHERE id = ?",
                position,
                featuredPosition,
                at(now),
                id,
            )
        }
    }

    override fun createMedia(media: StoredMedia): StoredMedia {
        jdbc.update(
            "INSERT INTO tutorial_media (id, storage_filename, content_type, byte_size, width, height, sha256, published, created_by, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            media.id, media.storageFilename, media.contentType, media.byteSize, media.width, media.height,
            media.sha256, media.published, media.createdBy, at(media.createdAt),
        )
        return media
    }

    override fun findMedia(id: UUID): StoredMedia? = jdbc.query("SELECT * FROM tutorial_media WHERE id = ?", MEDIA_MAPPER, id).firstOrNull()

    override fun findMedia(ids: Set<UUID>): List<StoredMedia> {
        if (ids.isEmpty()) return emptyList()
        val placeholders = ids.joinToString(",") { "?" }
        return jdbc.query("SELECT * FROM tutorial_media WHERE id IN ($placeholders)", MEDIA_MAPPER, *ids.toTypedArray())
    }

    override fun listMedia(): List<StoredMedia> = jdbc.query("SELECT * FROM tutorial_media ORDER BY created_at DESC, id", MEDIA_MAPPER)

    override fun mediaReferenceCount(id: UUID): Int = jdbc.queryForObject(
        "SELECT count(*) FROM tutorial_revision_media WHERE media_id = ?",
        Int::class.java,
        id,
    ) ?: 0

    override fun deleteMedia(id: UUID): Boolean = jdbc.update("DELETE FROM tutorial_media WHERE id = ?", id) == 1

    private fun at(value: Instant): OffsetDateTime = OffsetDateTime.ofInstant(value, ZoneOffset.UTC)

    companion object {
        private val CATEGORY_MAPPER = RowMapper { rs: ResultSet, _: Int ->
            StoredCategory(
                rs.uuid("id"),
                rs.getString("slug"),
                TutorialLifecycle.valueOf(rs.getString("status")),
                rs.getInt("position"),
                rs.uuidOrNull("published_revision_id"),
                rs.instant("created_at"),
                rs.instant("updated_at"),
            )
        }
        private val TUTORIAL_MAPPER = RowMapper { rs: ResultSet, _: Int ->
            StoredTutorial(
                rs.uuid("id"),
                rs.getString("slug"),
                TutorialLifecycle.valueOf(rs.getString("status")),
                rs.getInt("position"),
                rs.getObject("featured_position", Integer::class.java)?.toInt(),
                rs.uuidOrNull("published_revision_id"),
                rs.instant("created_at"),
                rs.instant("updated_at"),
            )
        }
        private val CATEGORY_REVISION_MAPPER = RowMapper { rs: ResultSet, _: Int ->
            StoredRevision(
                rs.uuid("id"),
                rs.uuid("category_id"),
                rs.getInt("revision_number"),
                null,
                rs.getString("content"),
                rs.uuidOrNull("created_by"),
                rs.instant("created_at"),
            )
        }
        private val TUTORIAL_REVISION_MAPPER = RowMapper { rs: ResultSet, _: Int ->
            StoredRevision(
                rs.uuid("id"),
                rs.uuid("tutorial_id"),
                rs.getInt("revision_number"),
                rs.uuid("category_id"),
                rs.getString("content"),
                rs.uuidOrNull("created_by"),
                rs.instant("created_at"),
            )
        }
        private val MEDIA_MAPPER = RowMapper { rs: ResultSet, _: Int ->
            StoredMedia(
                rs.uuid("id"), rs.getString("storage_filename"), rs.getString("content_type"), rs.getLong("byte_size"),
                rs.getInt("width"), rs.getInt("height"), rs.getString("sha256"), rs.getBoolean("published"),
                rs.uuidOrNull("created_by"), rs.instant("created_at"),
            )
        }

        private fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)
        private fun ResultSet.uuidOrNull(column: String): UUID? = getObject(column, UUID::class.java)
        private fun ResultSet.instant(column: String): Instant = getObject(column, OffsetDateTime::class.java).toInstant()
    }
}
