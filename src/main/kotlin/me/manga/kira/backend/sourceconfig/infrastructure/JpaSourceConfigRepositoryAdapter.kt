package me.manga.kira.backend.sourceconfig.infrastructure

import me.manga.kira.backend.sourceconfig.domain.AdminSourceListing
import me.manga.kira.backend.sourceconfig.domain.AssemblySource
import me.manga.kira.backend.sourceconfig.domain.NewSourceConfig
import me.manga.kira.backend.sourceconfig.domain.PublishedRevisionMetadata
import me.manga.kira.backend.sourceconfig.domain.SourceConfigHead
import me.manga.kira.backend.sourceconfig.domain.SourceConfigRepository
import me.manga.kira.backend.sourceconfig.domain.SourceLifecycleStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Adapts [SpringDataSourceConfigRepository] to the pure-Kotlin [SourceConfigRepository] port (PLAN §2).
 * Entity↔domain mapping is explicit here so entities never escape this layer.
 *
 * The `FOR UPDATE` source-row lock ([lockByApiForUpdate]) and the candidate-assembly join
 * ([findSourcesForAssembly]) are issued via [JdbcTemplate] native SQL: the lock must hold on the exact
 * transaction connection without leaving a managed entity that would later shadow the native
 * publish-path updates, and the assembly join reads the published revision's canonical content in one
 * query, ordered by `(position ASC, api ASC)` (PLAN §5/§9).
 */
@Repository
class JpaSourceConfigRepositoryAdapter(private val jpa: SpringDataSourceConfigRepository, private val jdbcTemplate: JdbcTemplate) : SourceConfigRepository {

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
        return jpa.saveAndFlush(entity).toDomain()
    }

    override fun lockByApiForUpdate(api: String): SourceConfigHead? = jdbcTemplate
        .query("SELECT * FROM source_configs WHERE api = ? FOR UPDATE", HEAD_MAPPER, api)
        .firstOrNull()

    override fun nextPosition(): Int = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(position) + 1, 0) FROM source_configs", Int::class.java) ?: 0

    override fun findAll(status: SourceLifecycleStatus?): List<SourceConfigHead> = if (status == null) {
        jpa.findAllByOrderByPositionAscApiAsc()
    } else {
        jpa.findAllByStatusOrderByPositionAscApiAsc(status)
    }.map { it.toDomain() }

    override fun findAllWithRevisionNumbers(status: SourceLifecycleStatus?): List<AdminSourceListing> {
        val sql =
            "SELECT s.*, current_revision.revision_number AS current_revision_number, " +
                "(SELECT MAX(r.revision_number) FROM source_config_revisions r WHERE r.source_config_id = s.id) AS latest_revision_number " +
                "FROM source_configs s " +
                "LEFT JOIN source_config_revisions current_revision ON current_revision.id = s.current_published_revision_id " +
                (if (status == null) "" else "WHERE s.status = ? ") +
                "ORDER BY s.position ASC, s.api ASC"
        val mapper =
            RowMapper { rs: ResultSet, rowNumber: Int ->
                AdminSourceListing(
                    head = requireNotNull(HEAD_MAPPER.mapRow(rs, rowNumber)),
                    currentPublishedRevisionNumber = rs.nullableInt("current_revision_number"),
                    latestRevisionNumber = rs.nullableInt("latest_revision_number"),
                )
            }
        return if (status == null) jdbcTemplate.query(sql, mapper) else jdbcTemplate.query(sql, mapper, status.wire)
    }

    override fun findSourcesForAssembly(): List<AssemblySource> = jdbcTemplate.query(
        "SELECT s.api, s.position, s.engine, s.status, r.config_canonical_json " +
            "FROM source_configs s " +
            "LEFT JOIN source_config_revisions r ON r.id = s.current_published_revision_id " +
            "WHERE s.status IN ('active', 'disabled', 'retired') " +
            "ORDER BY s.position ASC, s.api ASC",
        ASSEMBLY_MAPPER,
    )

    override fun findPublishedRevisionMetadata(): List<PublishedRevisionMetadata> = jdbcTemplate.query(
        "SELECT s.api, r.revision_number, r.published_at " +
            "FROM source_configs s " +
            "JOIN source_config_revisions r ON r.id = s.current_published_revision_id " +
            "WHERE s.status IN ('active', 'disabled', 'retired')",
        METADATA_MAPPER,
    )

    override fun applyPublishedRevision(
        id: UUID,
        currentPublishedRevisionId: UUID,
        status: SourceLifecycleStatus,
        publishedAt: Instant,
        displayName: String,
        language: String,
        engine: String,
        baseUrl: String,
        adult: Boolean,
        updatedAt: Instant,
    ) = jpa.applyPublishedRevision(
        id = id,
        revId = currentPublishedRevisionId,
        status = status.wire,
        publishedAt = publishedAt,
        displayName = displayName,
        language = language,
        engine = engine,
        baseUrl = baseUrl,
        adult = adult,
        updatedAt = updatedAt,
    )

    override fun updateStatus(id: UUID, status: SourceLifecycleStatus, updatedAt: Instant) = jpa.updateStatusNative(id, status.wire, updatedAt)

    override fun updatePosition(id: UUID, position: Int, updatedAt: Instant) {
        jdbcTemplate.update(
            "UPDATE source_configs SET position = ?, updated_at = ? WHERE id = ?",
            position,
            OffsetDateTime.ofInstant(updatedAt, java.time.ZoneOffset.UTC),
            id,
        )
    }

    private fun SourceConfigEntity.toDomain(): SourceConfigHead = SourceConfigHead(
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

    private fun ResultSet.nullableInt(column: String): Int? = getObject(column, Integer::class.java)?.toInt()

    private companion object {
        val HEAD_MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                SourceConfigHead(
                    id = rs.getObject("id", UUID::class.java),
                    api = rs.getString("api"),
                    displayName = rs.getString("display_name"),
                    language = rs.getString("language"),
                    engine = rs.getString("engine"),
                    status = SourceLifecycleStatus.fromWire(rs.getString("status")),
                    position = rs.getInt("position"),
                    baseUrl = rs.getString("base_url"),
                    adult = rs.getBoolean("adult"),
                    currentPublishedRevisionId = rs.getObject("current_published_revision_id", UUID::class.java),
                    createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                    updatedAt = rs.getObject("updated_at", OffsetDateTime::class.java).toInstant(),
                    publishedAt = rs.getObject("published_at", OffsetDateTime::class.java)?.toInstant(),
                )
            }

        val ASSEMBLY_MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                AssemblySource(
                    api = rs.getString("api"),
                    position = rs.getInt("position"),
                    engine = rs.getString("engine"),
                    status = SourceLifecycleStatus.fromWire(rs.getString("status")),
                    canonicalContent =
                    requireNotNull(rs.getString("config_canonical_json")) {
                        "published source '${rs.getString("api")}' has no current revision; refusing partial document assembly"
                    },
                )
            }

        val METADATA_MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                PublishedRevisionMetadata(
                    api = rs.getString("api"),
                    revisionNumber = rs.getInt("revision_number"),
                    publishedAt = rs.getObject("published_at", OffsetDateTime::class.java).toInstant(),
                )
            }
    }
}
