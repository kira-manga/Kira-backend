package me.manga.kira.backend.sourceconfig.domain

import java.time.Instant
import java.util.UUID

/**
 * Immutable domain view of a `published_documents` row (PLAN §5) — a materialized served snapshot.
 * [documentJson] is the EXACT canonical bytes served; [checksum] (SHA-256 of those bytes) is the
 * strong ETag. [createdAt] is the same application-controlled instant serialized as the document's
 * `generatedAt` (PLAN §9). [documentRevision] comes from `seq_document_revision` (monotonic, gaps
 * allowed).
 */
data class PublishedDocument(
    val id: UUID,
    val documentRevision: Long,
    val schemaVersion: Int,
    val documentJson: String,
    val checksum: String,
    val canonVersion: String,
    val sourceCount: Int,
    val createdBy: UUID,
    val createdAt: Instant,
    val notes: String?,
    val signatureFormat: String?,
    val signatureAlgorithm: String?,
    val signingKeyId: String?,
    val signatureBase64: String?,
    val previousDocumentRevision: Long?,
    val previousDocumentChecksum: String?,
)
