package me.manga.kira.backend.audit.infrastructure

import me.manga.kira.backend.audit.domain.ComplaintAuditAllocation
import me.manga.kira.backend.audit.domain.CountedAdminDeleteAuditEntry
import me.manga.kira.backend.audit.domain.AdminDeleteAuditOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import java.sql.Timestamp

/** Qualified same-holder JDBC only; the allocation checks the exact internally measured outcome. */
internal class ComplaintAdminDeleteAuditInsertion private constructor(private val allocation: JdbcComplaintCapacityStore.AdminDeleteAuditAllocation) {
    private var written = false
    fun belongsTo(candidate: JdbcComplaintCapacityStore.AdminDeleteAuditAllocation): Boolean = allocation === candidate
    fun completedFor(candidate: JdbcComplaintCapacityStore.AdminDeleteAuditAllocation): Boolean = belongsTo(candidate) && written

    companion object {
        @Suppress("TooGenericExceptionCaught")
        fun insert(entry: CountedAdminDeleteAuditEntry, allocation: ComplaintAuditAllocation) {
            val charged = allocation as? JdbcComplaintCapacityStore.AdminDeleteAuditAllocation ?: run {
                val failure = PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
                PersistencePhaseOwnership.current()?.recordFailure(failure)
                throw failure
            }
            try {
                val insertion = ComplaintAdminDeleteAuditInsertion(charged)
                val connection = charged.beginAuditInsert(insertion, entry)
                connection.prepareStatement(INSERT).use { statement ->
                    statement.setString(1, when (entry.outcome) {
                        is AdminDeleteAuditOutcome.Authorized -> "COMPLAINT_DELETE_AUTHORIZED"
                        is AdminDeleteAuditOutcome.Removed -> "COMPLAINT_DELETED"
                        is AdminDeleteAuditOutcome.RecoveryApplied, is AdminDeleteAuditOutcome.BatchRecoveryApplied -> "COMPLAINT_RECOVERY_APPLIED"
                    })
                    when (val outcome = entry.outcome) {
                        is AdminDeleteAuditOutcome.Resource -> {
                            statement.setString(2, "complaint"); statement.setString(3, outcome.resourceId.toString())
                        }
                        is AdminDeleteAuditOutcome.BatchRecoveryApplied -> {
                            statement.setString(2, "complaint_scope"); statement.setString(3, outcome.scope.id.toString())
                        }
                    }
                    statement.setString(4, entry.detailJson)
                    statement.setTimestamp(5, Timestamp.from(entry.createdAt))
                    statement.setObject(6, entry.outcome.scope.id)
                    statement.setString(7, if (entry.outcome.actorId == null) "SYSTEM" else "ADMIN")
                    statement.setObject(8, entry.outcome.actorId)
                    statement.executeQuery().use { row -> check(row.next() && row.getLong(1) > 0 && !row.wasNull() && !row.next()) }
                }
                charged.requireAuditInsert(insertion)
                insertion.written = true
            } catch (problem: Throwable) {
                charged.failed(problem)
            }
        }

        private const val INSERT = "INSERT INTO audit_log " +
            "(action, entity_type, entity_id, detail, created_at, complaint_data_scope_id, complaint_actor_kind, actor_user_id) " +
            "VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?) RETURNING id"
    }
}
