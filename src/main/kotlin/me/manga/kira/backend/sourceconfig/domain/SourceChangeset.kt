package me.manga.kira.backend.sourceconfig.domain

import java.time.Instant
import java.util.UUID

data class SourceChangeset(
    val id: UUID,
    val name: String,
    val description: String?,
    val operationsJson: String,
    val status: SourceChangesetStatus,
    val version: Long,
    val appliedDocumentRevision: Long?,
    val createdBy: UUID,
    val updatedBy: UUID,
    val createdAt: Instant,
    val updatedAt: Instant,
    val appliedAt: Instant?,
)

enum class SourceChangesetStatus(val wire: String) {
    OPEN("open"),
    APPLIED("applied"),
    DISCARDED("discarded"),
}

data class NewSourceChangeset(val id: UUID, val name: String, val description: String?, val operationsJson: String, val actorId: UUID, val at: Instant)

interface SourceChangesetRepository {
    fun create(spec: NewSourceChangeset): SourceChangeset

    fun findById(id: UUID): SourceChangeset?

    fun findAll(): List<SourceChangeset>

    fun update(id: UUID, expectedVersion: Long, name: String, description: String?, operationsJson: String, actorId: UUID, at: Instant): SourceChangeset?

    fun lockOpen(id: UUID, expectedVersion: Long): SourceChangeset?

    fun markApplied(id: UUID, expectedVersion: Long, documentRevision: Long, actorId: UUID, at: Instant): SourceChangeset?

    fun discard(id: UUID, expectedVersion: Long, actorId: UUID, at: Instant): SourceChangeset?
}
