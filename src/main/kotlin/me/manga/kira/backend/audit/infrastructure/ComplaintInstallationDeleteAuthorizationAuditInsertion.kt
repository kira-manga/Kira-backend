package me.manga.kira.backend.audit.infrastructure

import me.manga.kira.backend.audit.domain.ComplaintAuditAllocation
import me.manga.kira.backend.audit.domain.CountedInstallationDeleteAuthorizationAuditEntry
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import java.sql.Timestamp

/** The existing shared audit adapter consumes the exact prepaid owner and its original deletion JDBC holder. */
internal class ComplaintInstallationDeleteAuthorizationAuditInsertion private constructor(
    private val allocation: JdbcComplaintCapacityStore.LockedOwnerDeleteAll,
) {
    private var written = false

    internal fun belongsTo(candidate: JdbcComplaintCapacityStore.LockedOwnerDeleteAll): Boolean = allocation === candidate
    internal fun completedFor(candidate: JdbcComplaintCapacityStore.LockedOwnerDeleteAll): Boolean = belongsTo(candidate) && written

    override fun toString(): String = "ComplaintInstallationDeleteAuthorizationAuditInsertion(redacted)"

    companion object {
        @Suppress("TooGenericExceptionCaught")
        internal fun insert(entry: CountedInstallationDeleteAuthorizationAuditEntry, allocation: ComplaintAuditAllocation) {
            val charged = allocation as? JdbcComplaintCapacityStore.LockedOwnerDeleteAll ?: run {
                val failure = PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
                PersistencePhaseOwnership.current()?.recordFailure(failure)
                throw failure
            }
            try {
                val insertion = ComplaintInstallationDeleteAuthorizationAuditInsertion(charged)
                val connection = charged.beginAuditInsert(insertion, entry)
                val scope = charged.auditScope(insertion)
                connection.prepareStatement(INSERT).use { statement ->
                    statement.setString(1, scope.id.toString())
                    statement.setString(2, entry.detailJson)
                    statement.setTimestamp(3, Timestamp.from(entry.createdAt))
                    statement.setObject(4, scope.id)
                    statement.executeQuery().use { rows ->
                        check(rows.next() && rows.getLong(1) > 0 && !rows.wasNull() && !rows.next())
                    }
                }
                charged.requireAuditInsert(insertion)
                insertion.written = true
            } catch (problem: Throwable) {
                charged.failed(problem)
            }
        }

        private const val INSERT = "INSERT INTO audit_log " +
            "(actor_user_id, action, entity_type, entity_id, detail, created_at, complaint_data_scope_id, complaint_actor_kind) " +
            "VALUES (NULL, 'COMPLAINT_INSTALLATION_DELETE_AUTHORIZED', 'complaint_scope', ?, ?::jsonb, ?, ?, 'INSTALLATION') RETURNING id"
    }
}
