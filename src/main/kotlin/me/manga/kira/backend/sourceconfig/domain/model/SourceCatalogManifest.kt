package me.manga.kira.backend.sourceconfig.domain.model

import kotlinx.serialization.Serializable

/** Exact schema-v1 wire model for the lightweight signed source catalog. */
@Serializable
data class SourceCatalogManifest(
    val schemaVersion: Int,
    val sourceSchemaVersion: Int,
    val catalogRevision: Long,
    val generatedAt: String,
    val sources: List<SourceCatalogEntry>,
    val removedSources: List<RemovedSourceEntry> = emptyList(),
)

@Serializable
data class SourceCatalogEntry(
    val api: String,
    val sourceRevision: Int,
    val checksum: String,
    val order: Int,
    val lifecycle: String,
    val engine: String,
    val sourceSigningKeyId: String,
    val sourceSignature: String,
)

@Serializable
data class RemovedSourceEntry(val api: String, val lifecycle: String = "removed")
