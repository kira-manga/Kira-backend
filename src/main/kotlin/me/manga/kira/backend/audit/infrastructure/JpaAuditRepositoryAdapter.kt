package me.manga.kira.backend.audit.infrastructure

import me.manga.kira.backend.audit.domain.AuditEntry
import me.manga.kira.backend.audit.domain.AuditPage
import me.manga.kira.backend.audit.domain.AuditRepository
import me.manga.kira.backend.audit.domain.NewAuditEntry
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Repository

/**
 * Adapts [SpringDataAuditLogRepository] to the pure-Kotlin [AuditRepository] port (PLAN §2). The
 * `detailJson` string is stored verbatim into the `jsonb` column (the service pre-encoded it, keeping
 * log-hygiene — identifiers/numbers/checksums only — in one place, PLAN §6).
 */
@Repository
class JpaAuditRepositoryAdapter(private val jpa: SpringDataAuditLogRepository) : AuditRepository {

    override fun record(entry: NewAuditEntry) {
        jpa.save(
            AuditLogEntity(
                actorUserId = entry.actorUserId,
                action = entry.action,
                entityType = entry.entityType,
                entityId = entry.entityId,
                detail = entry.detailJson,
                createdAt = entry.createdAt,
            ),
        )
    }

    override fun findPage(page: Int, size: Int): AuditPage {
        val result = jpa.findAll(PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id")))
        return AuditPage(
            items =
            result.content.map {
                AuditEntry(
                    id = requireNotNull(it.id),
                    actorUserId = it.actorUserId,
                    action = it.action,
                    entityType = it.entityType,
                    entityId = it.entityId,
                    detailJson = it.detail,
                    createdAt = it.createdAt,
                )
            },
            total = result.totalElements,
        )
    }
}
