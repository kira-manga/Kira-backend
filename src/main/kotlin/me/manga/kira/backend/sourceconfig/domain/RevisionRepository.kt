package me.manga.kira.backend.sourceconfig.domain

import java.util.UUID

/**
 * Persistence **port** for `source_config_revisions` (PLAN §2/§3) — the immutable per-source history.
 * Pure Kotlin. Phase 5 exposes create + reads; Phase 6 adds the publish-path mutations
 * (status flips, max+1 allocation under the source-row lock).
 */
interface RevisionRepository {

    /** Find a revision by primary key, or null. */
    fun findById(id: UUID): SourceRevision?

    /** Find a source's revision by its per-source number, or null. */
    fun findBySourceAndNumber(
        sourceConfigId: UUID,
        revisionNumber: Int,
    ): SourceRevision?

    /**
     * Insert a new immutable revision row and return the stored model (id + `created_at` assigned by
     * the adapter). Content is expected pre-canonicalized (kcj-1) and lifecycle-neutral (PLAN §5/§9);
     * the caller supplies the matching [NewRevision.checksum] computed from the same parsed model.
     */
    fun create(spec: NewRevision): SourceRevision
}

/** The fields needed to insert a `source_config_revisions` row (PLAN §5). */
data class NewRevision(
    val sourceConfigId: UUID,
    val revisionNumber: Int,
    val configCanonicalJson: String,
    val checksum: String,
    val canonVersion: String,
    val status: RevisionStatus,
    val createdBy: UUID,
    val notes: String?,
)
