package me.manga.kira.backend.audit.infrastructure

import jakarta.persistence.EntityManager
import me.manga.kira.backend.audit.domain.ComplaintAuditAllocation
import me.manga.kira.backend.audit.domain.CountedComplaintAuditEntry
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import java.sql.Connection
import java.sql.Timestamp

/** Shared-adapter implementation detail. Only the selected ordinary EM or original deletion holder can complete this handle. */
internal class ComplaintAuditInsertion private constructor(private val allocation: JdbcComplaintCapacityStore.ChargedComplaintAudit) {
    private var written = false

    internal fun belongsTo(candidate: JdbcComplaintCapacityStore.ChargedComplaintAudit): Boolean = allocation === candidate

    internal fun completedFor(candidate: JdbcComplaintCapacityStore.ChargedComplaintAudit): Boolean = belongsTo(candidate) && written

    override fun toString(): String = "ComplaintAuditInsertion(redacted)"

    companion object {
        @Suppress("TooGenericExceptionCaught")
        internal fun insert(entry: CountedComplaintAuditEntry, allocation: ComplaintAuditAllocation) {
            val charged = allocation as? JdbcComplaintCapacityStore.ChargedComplaintAudit ?: run {
                val failure = PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
                PersistencePhaseOwnership.current()?.recordFailure(failure)
                throw failure
            }
            try {
                val insertion = ComplaintAuditInsertion(charged)
                when (val holder = charged.beginInsert(insertion, entry)) {
                    is ComplaintAuditSelectedHolder.Ordinary -> insertOrdinary(holder.entityManager, entry)
                    is ComplaintAuditSelectedHolder.Deletion -> insertDeletion(holder.connection, entry)
                }
                charged.requireInsert(insertion)
                insertion.written = true
            } catch (problem: Throwable) {
                charged.failed(problem)
            }
        }

        private fun insertOrdinary(entityManager: EntityManager, entry: CountedComplaintAuditEntry) {
            val mutation = entry.mutation
            val entity = AuditLogEntity(
                actorUserId = mutation.actor.adminUserId,
                action = mutation.action.wire,
                entityType = "complaint",
                entityId = mutation.subject.resourceId.toString(),
                detail = entry.detailJson,
                createdAt = entry.createdAt,
                complaintDataScopeId = mutation.subject.scope.id,
                complaintActorKind = mutation.actor.kind.name,
            )
            // Exact already-held ordinary EM, not a repository proxy that might start/select another transaction.
            entityManager.persist(entity)
            entityManager.flush()
            check(checkNotNull(entity.id) > 0)
        }

        private fun insertDeletion(connection: Connection, entry: CountedComplaintAuditEntry) {
            val mutation = entry.mutation
            connection.prepareStatement(INSERT_DELETION).use { statement ->
                statement.setObject(1, mutation.actor.adminUserId)
                statement.setString(2, mutation.action.wire)
                statement.setString(3, mutation.subject.resourceId.toString())
                statement.setString(4, entry.detailJson)
                statement.setTimestamp(5, Timestamp.from(entry.createdAt))
                statement.setObject(6, mutation.subject.scope.id)
                statement.setString(7, mutation.actor.kind.name)
                statement.executeQuery().use { result ->
                    check(result.next())
                    check(result.getLong(1) > 0 && !result.wasNull() && !result.next())
                }
            }
        }

        private const val INSERT_DELETION = "INSERT INTO audit_log " +
            "(actor_user_id, action, entity_type, entity_id, detail, created_at, complaint_data_scope_id, complaint_actor_kind) " +
            "VALUES (?, ?, 'complaint', ?, ?::jsonb, ?, ?, ?) RETURNING id"
    }
}

/** Internal selected resources only, never a public phase result or a DataSource/fallback factory. */
internal sealed interface ComplaintAuditSelectedHolder {
    class Ordinary(val entityManager: EntityManager) : ComplaintAuditSelectedHolder
    class Deletion(val connection: Connection) : ComplaintAuditSelectedHolder
}
