package me.manga.kira.backend.sourceconfig.domain

import java.time.Instant
import java.util.UUID

data class PublishedSourceCatalog(
    val id: UUID,
    val catalogRevision: Long,
    val schemaVersion: Int,
    val sourceSchemaVersion: Int,
    val manifestJson: String,
    val checksum: String,
    val canonVersion: String,
    val sourceCount: Int,
    val createdBy: UUID,
    val createdAt: Instant,
    val signatureFormat: String,
    val signatureAlgorithm: String,
    val signingKeyId: String,
    val signatureBase64: String,
    val previousCatalogRevision: Long?,
    val previousCatalogChecksum: String?,
)

data class PublishedCatalogEntry(
    val sourceConfigId: UUID,
    val sourceRevisionId: UUID,
    val api: String,
    val sourceRevision: Int,
    val checksum: String,
    val order: Int,
    val lifecycle: String,
    val engine: String,
    val sourceSigningKeyId: String,
    val sourceSignature: String,
)

data class NewPublishedSourceCatalog(
    val catalogRevision: Long,
    val schemaVersion: Int,
    val sourceSchemaVersion: Int,
    val manifestJson: String,
    val checksum: String,
    val canonVersion: String,
    val createdBy: UUID,
    val createdAt: Instant,
    val signatureFormat: String,
    val signatureAlgorithm: String,
    val signingKeyId: String,
    val signatureBase64: String,
    val previousCatalogRevision: Long?,
    val previousCatalogChecksum: String?,
    val entries: List<PublishedCatalogEntry>,
    val removedApis: List<String>,
)

data class PublishedSourceArtifact(val api: String, val sourceRevision: Int, val canonicalJson: String, val checksum: String, val canonVersion: String)
