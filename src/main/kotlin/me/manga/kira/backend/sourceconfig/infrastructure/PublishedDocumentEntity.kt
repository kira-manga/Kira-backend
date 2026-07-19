package me.manga.kira.backend.sourceconfig.infrastructure

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * JPA entity for `published_documents` (PLAN §5 V3) — immutable served snapshots. [documentJson] is the
 * exact canonical bytes served (`text`, not jsonb). There is deliberately **no** lifecycle callback:
 * [createdAt] carries NO DB default and is set by the application (Phase 6) to the same injected-Clock
 * instant serialized as the document's `generatedAt` (PLAN §9). [documentRevision] is assigned by the
 * app from `seq_document_revision` (Phase 6), so no JPA sequence generator is declared.
 */
@Entity
@Table(name = "published_documents")
class PublishedDocumentEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID? = null,
    @Column(name = "document_revision", nullable = false, updatable = false)
    var documentRevision: Long = 0,
    @Column(name = "schema_version", nullable = false)
    var schemaVersion: Int = 1,
    @Column(name = "document_json", nullable = false, updatable = false)
    var documentJson: String = "",
    // char(64) (PLAN §5) — mapped as fixed-width CHAR so ddl-auto=validate matches `bpchar`, not varchar.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "checksum", nullable = false, updatable = false, length = 64)
    var checksum: String = "",
    @Column(name = "canon_version", nullable = false, updatable = false)
    var canonVersion: String = "",
    @Column(name = "source_count", nullable = false)
    var sourceCount: Int = 0,
    @Column(name = "created_by", nullable = false, updatable = false)
    var createdBy: UUID? = null,
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.EPOCH,
    @Column(name = "notes")
    var notes: String? = null,
) {
    @Embedded
    var signature: PublishedDocumentSignatureEmbeddable = PublishedDocumentSignatureEmbeddable()
}

@Embeddable
class PublishedDocumentSignatureEmbeddable(
    @Column(name = "signature_format", length = 64)
    var signatureFormat: String? = null,
    @Column(name = "signature_algorithm", length = 16)
    var signatureAlgorithm: String? = null,
    @Column(name = "signing_key_id", length = 64)
    var signingKeyId: String? = null,
    @Column(name = "signature_base64", length = 128)
    var signatureBase64: String? = null,
    @Column(name = "previous_document_revision")
    var previousDocumentRevision: Long? = null,
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "previous_document_checksum", length = 64)
    var previousDocumentChecksum: String? = null,
)
