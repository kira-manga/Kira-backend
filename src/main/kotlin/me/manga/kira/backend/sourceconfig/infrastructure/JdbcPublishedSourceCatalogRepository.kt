package me.manga.kira.backend.sourceconfig.infrastructure

import me.manga.kira.backend.sourceconfig.domain.NewPublishedSourceCatalog
import me.manga.kira.backend.sourceconfig.domain.PublishedSourceArtifact
import me.manga.kira.backend.sourceconfig.domain.PublishedSourceCatalog
import me.manga.kira.backend.sourceconfig.domain.PublishedSourceCatalogRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JdbcPublishedSourceCatalogRepository(private val jdbc: JdbcTemplate) : PublishedSourceCatalogRepository {
    override fun insert(spec: NewPublishedSourceCatalog): PublishedSourceCatalog {
        val id = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO published_source_catalogs (
              id, catalog_revision, schema_version, source_schema_version, manifest_json, checksum,
              canon_version, source_count, created_by, created_at, signature_format,
              signature_algorithm, signing_key_id, signature_base64, previous_catalog_revision,
              previous_catalog_checksum
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            id,
            spec.catalogRevision,
            spec.schemaVersion,
            spec.sourceSchemaVersion,
            spec.manifestJson,
            spec.checksum,
            spec.canonVersion,
            spec.entries.size,
            spec.createdBy,
            OffsetDateTime.ofInstant(spec.createdAt, ZoneOffset.UTC),
            spec.signatureFormat,
            spec.signatureAlgorithm,
            spec.signingKeyId,
            spec.signatureBase64,
            spec.previousCatalogRevision,
            spec.previousCatalogChecksum,
        )
        spec.entries.forEach { entry ->
            jdbc.update(
                """
                INSERT INTO published_source_catalog_entries (
                  catalog_id, source_config_id, source_revision_id, api, source_revision, checksum,
                  source_order, lifecycle, engine, source_signing_key_id, source_signature
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                id,
                entry.sourceConfigId,
                entry.sourceRevisionId,
                entry.api,
                entry.sourceRevision,
                entry.checksum,
                entry.order,
                entry.lifecycle,
                entry.engine,
                entry.sourceSigningKeyId,
                entry.sourceSignature,
            )
        }
        spec.removedApis.forEach { api ->
            jdbc.update(
                "INSERT INTO published_source_catalog_removed (catalog_id, api, lifecycle) VALUES (?, ?, 'removed')",
                id,
                api,
            )
        }
        return requireNotNull(findByRevision(spec.catalogRevision))
    }

    override fun findByRevision(revision: Long): PublishedSourceCatalog? = jdbc
        .query(
            "SELECT * FROM published_source_catalogs WHERE catalog_revision = ?",
            CATALOG_MAPPER,
            revision,
        ).firstOrNull()

    override fun previouslyPublishedApis(): Set<String> =
        jdbc.queryForList("SELECT DISTINCT api FROM published_source_catalog_entries", String::class.java).toSet()

    override fun removedApis(revision: Long): Set<String> = jdbc
        .queryForList(
            """
            SELECT r.api FROM published_source_catalog_removed r
            JOIN published_source_catalogs c ON c.id = r.catalog_id
            WHERE c.catalog_revision = ?
            """.trimIndent(),
            String::class.java,
            revision,
        ).toSet()

    override fun findPublishedArtifact(api: String, revision: Int): PublishedSourceArtifact? = jdbc
        .query(
            """
            SELECT e.api, e.source_revision, r.config_canonical_json, r.checksum, r.canon_version
            FROM published_source_catalog_entries e
            JOIN source_config_revisions r ON r.id = e.source_revision_id
            WHERE e.api = ? AND e.source_revision = ? AND e.engine = 'generic'
            ORDER BY e.catalog_id
            LIMIT 1
            """.trimIndent(),
            { rs, _ ->
                PublishedSourceArtifact(
                    api = rs.getString("api"),
                    sourceRevision = rs.getInt("source_revision"),
                    canonicalJson = rs.getString("config_canonical_json"),
                    checksum = rs.getString("checksum"),
                    canonVersion = rs.getString("canon_version"),
                )
            },
            api,
            revision,
        ).firstOrNull()

    private companion object {
        val CATALOG_MAPPER = { rs: ResultSet, _: Int ->
            PublishedSourceCatalog(
                id = rs.getObject("id", UUID::class.java),
                catalogRevision = rs.getLong("catalog_revision"),
                schemaVersion = rs.getInt("schema_version"),
                sourceSchemaVersion = rs.getInt("source_schema_version"),
                manifestJson = rs.getString("manifest_json"),
                checksum = rs.getString("checksum"),
                canonVersion = rs.getString("canon_version"),
                sourceCount = rs.getInt("source_count"),
                createdBy = rs.getObject("created_by", UUID::class.java),
                createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                signatureFormat = requireNotNull(rs.getString("signature_format")),
                signatureAlgorithm = requireNotNull(rs.getString("signature_algorithm")),
                signingKeyId = requireNotNull(rs.getString("signing_key_id")),
                signatureBase64 = requireNotNull(rs.getString("signature_base64")),
                previousCatalogRevision = rs.getObject("previous_catalog_revision", java.lang.Long::class.java)?.toLong(),
                previousCatalogChecksum = rs.getString("previous_catalog_checksum"),
            )
        }
    }
}
