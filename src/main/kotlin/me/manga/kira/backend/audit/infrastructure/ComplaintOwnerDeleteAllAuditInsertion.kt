package me.manga.kira.backend.audit.infrastructure

import me.manga.kira.backend.audit.domain.ComplaintAuditAllocation
import me.manga.kira.backend.audit.domain.CountedOwnerDeleteAllAuditEntry
import me.manga.kira.backend.audit.domain.OwnerDeleteAllAuditOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import java.sql.Timestamp

/** Same shared audit adapter and original APPLY JDBC holder; never JPA, ordinary recordAt, or another owner. */
internal class ComplaintOwnerDeleteAllAuditInsertion private constructor(private val allocation: JdbcComplaintCapacityStore.LockedOwnerDeleteAllApply) {
    private var written = false
    fun belongsTo(candidate: JdbcComplaintCapacityStore.LockedOwnerDeleteAllApply): Boolean = allocation === candidate
    fun completedFor(candidate: JdbcComplaintCapacityStore.LockedOwnerDeleteAllApply): Boolean = belongsTo(candidate) && written
    override fun toString(): String = "ComplaintOwnerDeleteAllAuditInsertion(redacted)"

    companion object {
        @Suppress("TooGenericExceptionCaught")
        fun insert(entry: CountedOwnerDeleteAllAuditEntry, allocation: ComplaintAuditAllocation) {
            val charged = allocation as? JdbcComplaintCapacityStore.LockedOwnerDeleteAllApply ?: run {
                val failure = PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
                PersistencePhaseOwnership.current()?.recordFailure(failure)
                throw failure
            }
            try {
                val insertion = ComplaintOwnerDeleteAllAuditInsertion(charged)
                val connection = charged.beginAuditInsert(insertion, entry)
                val scope = charged.auditScope(insertion)
                connection.prepareStatement(INSERT).use { statement ->
                    when (val outcome = entry.outcome) {
                        is OwnerDeleteAllAuditOutcome.Removed -> {
                            statement.setString(1, "COMPLAINT_DELETED")
                            statement.setString(2, "complaint")
                            statement.setString(3, outcome.resourceId.toString())
                        }

                        is OwnerDeleteAllAuditOutcome.InstallationCompleted -> {
                            statement.setString(1, "COMPLAINT_INSTALLATION_DELETED")
                            statement.setString(2, "complaint_scope")
                            statement.setString(3, scope.id.toString())
                        }
                    }
                    statement.setString(4, entry.detailJson)
                    statement.setTimestamp(5, Timestamp.from(entry.createdAt))
                    statement.setObject(6, scope.id)
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
            "VALUES (NULL, ?, ?, ?, ?::jsonb, ?, ?, 'INSTALLATION') RETURNING id"
    }
}
