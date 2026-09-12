package me.manga.kira.backend.sourceconfig.infrastructure

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * JPA entity for the `document_publication_state` singleton row (id = 1), seeded by V3 (PLAN §5/§9). It
 * is the GLOBAL publication-serialization lock (locked `FOR UPDATE` by Phase 6's publish sequence) AND
 * the one authoritative latest-document pointer ([latestDocumentRevision], NULL until first publish).
 * No `@GeneratedValue` — the id is the fixed singleton key.
 */
@Entity
@Table(name = "document_publication_state")
class DocumentPublicationStateEntity(
    @Id
    @Column(name = "id", nullable = false)
    var id: Int = 1,
    @Column(name = "latest_document_revision")
    var latestDocumentRevision: Long? = null,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.EPOCH,
    @Column(name = "bootstrap_phase", nullable = false, length = 32)
    var bootstrapPhase: String = "pending",
    @Column(name = "bootstrap_policy_id", length = 128)
    var bootstrapPolicyId: String? = null,
    @Column(name = "bootstrap_reference_sha256", columnDefinition = "char(64)")
    var bootstrapReferenceSha256: String? = null,
    @Column(name = "bootstrap_payload_sha256", columnDefinition = "char(64)")
    var bootstrapPayloadSha256: String? = null,
    @Column(name = "bootstrap_document_revision")
    var bootstrapDocumentRevision: Long? = null,
    @Column(name = "bootstrap_document_checksum", columnDefinition = "char(64)")
    var bootstrapDocumentChecksum: String? = null,
    @Column(name = "bootstrap_catalog_revision")
    var bootstrapCatalogRevision: Long? = null,
    @Column(name = "bootstrap_catalog_checksum", columnDefinition = "char(64)")
    var bootstrapCatalogChecksum: String? = null,
    @Column(name = "bootstrap_completed_at")
    var bootstrapCompletedAt: Instant? = null,
    @Column(name = "bootstrap_actor_id")
    var bootstrapActorId: UUID? = null,
)
