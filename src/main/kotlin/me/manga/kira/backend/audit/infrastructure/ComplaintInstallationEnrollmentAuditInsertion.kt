package me.manga.kira.backend.audit.infrastructure

import me.manga.kira.backend.audit.domain.AuditAction
import me.manga.kira.backend.audit.domain.ComplaintAuditActorKind
import me.manga.kira.backend.audit.domain.ComplaintAuditAllocation
import me.manga.kira.backend.audit.domain.CountedInstallationEnrollmentAuditEntry
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore

/** Shared JPA audit adapter, not a second audit subsystem or an uncounted enrollment side effect. */
internal class ComplaintInstallationEnrollmentAuditInsertion private constructor(
    private val allocation: JdbcComplaintCapacityStore.LockedInstallationEnrollment,
) {
    private var written = false

    internal fun belongsTo(candidate: JdbcComplaintCapacityStore.LockedInstallationEnrollment): Boolean = allocation === candidate

    internal fun completedFor(candidate: JdbcComplaintCapacityStore.LockedInstallationEnrollment): Boolean = belongsTo(candidate) && written

    override fun toString(): String = "ComplaintInstallationEnrollmentAuditInsertion(redacted)"

    companion object {
        @Suppress("TooGenericExceptionCaught")
        internal fun insert(entry: CountedInstallationEnrollmentAuditEntry, allocation: ComplaintAuditAllocation) {
            val charged = allocation as? JdbcComplaintCapacityStore.LockedInstallationEnrollment ?: run {
                val failure = PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
                PersistencePhaseOwnership.current()?.recordFailure(failure)
                throw failure
            }
            try {
                val insertion = ComplaintInstallationEnrollmentAuditInsertion(charged)
                val entityManager = charged.beginAuditInsert(insertion, entry)
                val entity = AuditLogEntity(
                    actorUserId = null,
                    action = AuditAction.COMPLAINT_INSTALLATION_ENROLLED.wire,
                    entityType = "complaint_scope",
                    entityId = entry.scope.id.toString(),
                    detail = entry.detailJson,
                    createdAt = entry.createdAt,
                    complaintDataScopeId = entry.scope.id,
                    complaintActorKind = ComplaintAuditActorKind.INSTALLATION.name,
                )
                entityManager.persist(entity)
                entityManager.flush()
                check(checkNotNull(entity.id) > 0)
                charged.requireAuditInsert(insertion)
                insertion.written = true
            } catch (problem: Throwable) {
                charged.failed(problem)
            }
        }
    }
}
