package me.manga.kira.backend.audit.domain

import java.time.Instant
import java.util.UUID

/**
 * Persistence **port** for `audit_log` (PLAN §2/§5). Pure Kotlin — the infrastructure adapter maps
 * [NewAuditEntry] to the JPA entity. Insert-only (audit rows are immutable evidence, never mutated or
 * deleted — every FK is `ON DELETE RESTRICT`).
 */
interface AuditRepository {

    /** Append one audit row. [NewAuditEntry.detailJson] is a pre-encoded jsonb object string. */
    fun record(entry: NewAuditEntry)

    fun findPage(page: Int, size: Int): AuditPage
}

/**
 * The fields needed to insert an `audit_log` row (PLAN §5). [detailJson] holds identifiers, revision
 * numbers, and checksums ONLY (PLAN §6 log-hygiene) — never config bodies, header values, prompts, or
 * passwords; the [me.manga.kira.backend.audit.application.AuditService] owns building it safely.
 * [actorUserId] is null for a system actor.
 */
data class NewAuditEntry(
    val actorUserId: UUID?,
    val action: String,
    val entityType: String,
    val entityId: String,
    val detailJson: String,
    val createdAt: Instant,
)

data class AuditEntry(
    val id: Long,
    val actorUserId: UUID?,
    val action: String,
    val entityType: String,
    val entityId: String,
    val detailJson: String,
    val createdAt: Instant,
)

data class AuditPage(val items: List<AuditEntry>, val total: Long)
