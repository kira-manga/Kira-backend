package me.manga.kira.backend.sourceconfig.domain

import java.time.Instant
import java.util.UUID

/**
 * Persistence **port** for `source_config_revisions` (PLAN §2/§3) — the immutable per-source history.
 * Pure Kotlin. Phase 5 exposed create + reads; Phase 6 adds the publish-path mutations: the max+1
 * number allocation ([nextRevisionNumber], called under the source-row lock, PLAN §5) and the
 * supersede-then-publish status flips ([markSuperseded]/[markPublished], ordered so the
 * `uq_one_published_per_source` partial unique index holds at every statement — PLAN §9).
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

    /**
     * The next per-source revision number (`max(revision_number)+1`, or 1 for the first). Must be called
     * while holding the source head `FOR UPDATE` lock so concurrent creations serialize (PLAN §5).
     */
    fun nextRevisionNumber(sourceConfigId: UUID): Int

    /** The highest per-source revision number, or null if the source has no revisions. */
    fun latestRevisionNumber(sourceConfigId: UUID): Int?

    /** All of a source's revisions, ordered by `revision_number` ascending (the admin revision list). */
    fun findAllForSource(sourceConfigId: UUID): List<SourceRevision>

    /**
     * Flip a `published` revision to `superseded` (PLAN §9). Called FIRST in the publish transaction so
     * the partial unique index sees zero published rows before the new one is marked published.
     */
    fun markSuperseded(revisionId: UUID)

    /**
     * Flip a `draft` revision to `published`, stamping `published_at` (PLAN §9). Called AFTER
     * [markSuperseded] so `uq_one_published_per_source` is never momentarily violated.
     */
    fun markPublished(
        revisionId: UUID,
        publishedAt: Instant,
    )
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
