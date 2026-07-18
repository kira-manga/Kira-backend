package me.manga.kira.backend.sourceconfig.domain

import java.time.Instant
import java.util.UUID

/**
 * Persistence **port** for `published_documents` + the `document_publication_state` pointer (PLAN
 * §2/§3/§5). Pure Kotlin.
 *
 * [latestPointer] is THE authoritative latest-document read path (PLAN §5): the single-row pointer,
 * never `MAX(document_revision)`. [maxDocumentRevision] exists solely for the **startup consistency
 * comparison** and must never be a runtime read path. [sequenceNextValue] reports the sequence's next
 * value **without consuming it** (`pg_sequences`).
 *
 * Phase 6 adds the mutation path: the global publication `FOR UPDATE` lock ([lockPublicationState] —
 * step 1 of the §9 sequence), the monotonic revision allocator ([nextDocumentRevision] — `nextval`),
 * the snapshot insert ([insertSnapshot] — step 8), and the pointer move ([updatePointer] — step 9),
 * plus the snapshot reads for the admin document surface.
 */
interface PublishedDocumentRepository {

    /** The authoritative latest published document revision (pointer), or null before the first publish. */
    fun latestPointer(): Long?

    /** Number of published snapshots. */
    fun snapshotCount(): Long

    /** `MAX(document_revision)`, or null when no snapshot exists. **Startup consistency check ONLY** (PLAN §5). */
    fun maxDocumentRevision(): Long?

    /** The sequence's next value without consuming it (`pg_sequences`; NULL last_value ⇒ START value). */
    fun sequenceNextValue(): Long

    /**
     * Lock the singleton `document_publication_state` row `FOR UPDATE` (PLAN §9 step 1 — the GLOBAL
     * publication lock every state-visible document mutation takes first, before any source-row lock, so
     * mutations serialize with no lost updates and no deadlock). Returns the current pointer under the
     * lock. Must run inside a transaction; the lock releases at commit.
     */
    fun lockPublicationState(): Long?

    /** Consume the next monotonic `document_revision` from `seq_document_revision` (PLAN §5/§9 step 8). */
    fun nextDocumentRevision(): Long

    /** Insert a materialized snapshot and return the stored model (PLAN §9 step 8). */
    fun insertSnapshot(spec: NewPublishedDocument): PublishedDocument

    /** Move the authoritative latest-document pointer to [revision] (PLAN §9 step 9). */
    fun updatePointer(revision: Long, at: Instant)

    /** A stored snapshot by its document revision, or null (admin `GET /documents/{revision}`). */
    fun findByRevision(revision: Long): PublishedDocument?

    /** All snapshots ordered by `document_revision` ascending (admin `GET /documents` list). */
    fun findAllOrderedByRevision(): List<PublishedDocument>
}

/**
 * The fields needed to insert a `published_documents` row (PLAN §5/§9). [createdAt] is the same injected
 * Clock instant serialized as the document's `generatedAt` (there is NO DB default — application time
 * and DB time cannot diverge). [documentRevision] is the value already consumed via [PublishedDocumentRepository.nextDocumentRevision].
 */
data class NewPublishedDocument(
    val documentRevision: Long,
    val schemaVersion: Int,
    val documentJson: String,
    val checksum: String,
    val canonVersion: String,
    val sourceCount: Int,
    val createdBy: UUID,
    val createdAt: Instant,
    val notes: String?,
)
