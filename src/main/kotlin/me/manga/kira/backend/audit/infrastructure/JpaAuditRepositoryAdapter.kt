package me.manga.kira.backend.audit.infrastructure

import me.manga.kira.backend.audit.domain.AuditEntry
import me.manga.kira.backend.audit.domain.AuditPage
import me.manga.kira.backend.audit.domain.AuditRepository
import me.manga.kira.backend.audit.domain.ComplaintAuditActorKind
import me.manga.kira.backend.audit.domain.ComplaintAuditAllocation
import me.manga.kira.backend.audit.domain.CountedComplaintAuditEntry
import me.manga.kira.backend.audit.domain.CountedComplaintAuditRepository
import me.manga.kira.backend.audit.domain.CountedInstallationEnrollmentAuditEntry
import me.manga.kira.backend.audit.domain.CountedInstallationDeleteAuthorizationAuditEntry
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
internal class JpaAuditRepositoryAdapter(private val jpa: SpringDataAuditLogRepository) :
    AuditRepository,
    CountedComplaintAuditRepository {

    override fun recordComplaint(entry: CountedComplaintAuditEntry, allocation: ComplaintAuditAllocation) = ComplaintAuditInsertion.insert(entry, allocation)

    override fun recordInstallationEnrollment(entry: CountedInstallationEnrollmentAuditEntry, allocation: ComplaintAuditAllocation) =
        ComplaintInstallationEnrollmentAuditInsertion.insert(entry, allocation)

    override fun recordInstallationDeleteAuthorization(entry: CountedInstallationDeleteAuthorizationAuditEntry, allocation: ComplaintAuditAllocation) =
        ComplaintInstallationDeleteAuthorizationAuditInsertion.insert(entry, allocation)

    override fun record(entry: NewAuditEntry) {
        // Fail closed for the entire raw namespace, including unknown and W06-excluded identities.
        // This ordinary route has neither complaint metadata nor a phase-bound capacity allocation.
        require(!entry.action.startsWith("COMPLAINT_")) {
            "Complaint audit writes are not available through the ordinary route."
        }
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
                    complaintDataScopeId = it.complaintDataScopeId,
                    complaintActorKind = it.complaintActorKind?.let { kind -> ComplaintAuditActorKind.valueOf(kind) },
                )
            },
            total = result.totalElements,
        )
    }
}
