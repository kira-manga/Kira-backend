package me.manga.kira.backend.audit.infrastructure

import me.manga.kira.backend.audit.domain.ComplaintAuditAllocation
import me.manga.kira.backend.audit.domain.CountedOwnerDeleteAuditEntry
import me.manga.kira.backend.audit.domain.OwnerDeleteAuditOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import java.sql.Timestamp

/** Qualified same-holder JDBC only; the allocation checks the exact internally measured outcome. */
internal class ComplaintOwnerDeleteAuditInsertion private constructor(private val allocation: JdbcComplaintCapacityStore.OwnerDeleteAuditAllocation) {
    private var written = false
    fun belongsTo(candidate: JdbcComplaintCapacityStore.OwnerDeleteAuditAllocation): Boolean = allocation === candidate
    fun completedFor(candidate: JdbcComplaintCapacityStore.OwnerDeleteAuditAllocation): Boolean = belongsTo(candidate) && written

    companion object {
        @Suppress("TooGenericExceptionCaught")
        fun insert(entry: CountedOwnerDeleteAuditEntry, allocation: ComplaintAuditAllocation) {
            val charged = allocation as? JdbcComplaintCapacityStore.OwnerDeleteAuditAllocation ?: run {
                val failure = PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
                PersistencePhaseOwnership.current()?.recordFailure(failure)
                throw failure
            }
            try {
                val insertion = ComplaintOwnerDeleteAuditInsertion(charged)
                val connection = charged.beginAuditInsert(insertion, entry)
                connection.prepareStatement(INSERT).use { statement ->
                    statement.setString(1, when (entry.outcome) {
                        is OwnerDeleteAuditOutcome.Authorized -> "COMPLAINT_DELETE_AUTHORIZED"
                        is OwnerDeleteAuditOutcome.Removed -> "COMPLAINT_DELETED"
                        is OwnerDeleteAuditOutcome.RecoveryApplied -> "COMPLAINT_RECOVERY_APPLIED"
                    })
                    statement.setString(2, entry.outcome.resourceId.toString())
                    statement.setString(3, entry.detailJson)
                    statement.setTimestamp(4, Timestamp.from(entry.createdAt))
                    statement.setObject(5, entry.outcome.scope.id)
                    statement.setString(6, if (entry.outcome is OwnerDeleteAuditOutcome.RecoveryApplied) "SYSTEM" else "INSTALLATION")
                    statement.executeQuery().use { row -> check(row.next() && row.getLong(1) > 0 && !row.wasNull() && !row.next()) }
                }
                charged.requireAuditInsert(insertion)
                insertion.written = true
            } catch (problem: Throwable) {
                charged.failed(problem)
            }
        }

        private const val INSERT = "INSERT INTO audit_log " +
            "(actor_user_id, action, entity_type, entity_id, detail, created_at, complaint_data_scope_id, complaint_actor_kind) " +
            "VALUES (NULL, ?, 'complaint', ?, ?::jsonb, ?, ?, ?) RETURNING id"
    }
}
