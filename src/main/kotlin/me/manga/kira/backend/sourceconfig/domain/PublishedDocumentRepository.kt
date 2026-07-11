package me.manga.kira.backend.sourceconfig.domain

/**
 * Persistence **port** for `published_documents` + the `document_publication_state` pointer (PLAN
 * §2/§3/§5). Pure Kotlin.
 *
 * [latestPointer] is THE authoritative latest-document read path (PLAN §5): the single-row pointer,
 * never `MAX(document_revision)`. [maxDocumentRevision] exists solely for the **startup consistency
 * comparison** (pointer must equal MAX) and must never be used as a runtime read path. [sequenceNextValue]
 * reports the `seq_document_revision` next value **without consuming it** (read from `pg_sequences`;
 * a NULL `last_value` ⇒ the sequence's START value).
 *
 * Phase 5 exposes only the reads the startup validators need; Phase 6 adds the snapshot insert, the
 * `FOR UPDATE` global publication lock, and the pointer move.
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
}
