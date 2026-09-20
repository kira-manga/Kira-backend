package me.manga.kira.backend.complaint.infrastructure.transaction

import me.manga.kira.backend.audit.domain.ComplaintAuditMutation
import me.manga.kira.backend.audit.domain.CountedComplaintAuditEntry
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Connection
import java.time.Instant

/**
 * Dormant lower mutation cursor, NOT deletion authorization or a production writer. The synthetic
 * W03 fixture supplies protected SQL; W04 must supply fence/control/publication, scope membership,
 * actor provenance and the real domain writer. No executor, callback or audit-only entry is exposed.
 * The same retained phase owns counters -> protected work -> late shared audit and its finalizer.
 */
internal class ComplaintDeletionOperation private constructor(
    private val phase: PersistencePhaseContext,
    private val jdbc: JdbcTemplate,
    internal val mutation: ComplaintAuditMutation,
    private val originalAuditTime: Instant,
) {
    private var stage = Stage.PREPARED
    private var audit: JdbcComplaintCapacityStore.ChargedComplaintAudit? = null

    internal fun belongsTo(candidate: PersistencePhaseContext): Boolean = phase === candidate

    internal fun completedFor(candidate: PersistencePhaseContext): Boolean = belongsTo(candidate) && stage === Stage.PROTECTED_RETURNED &&
        audit?.completedFor(this) == true

    internal fun requireCommitted() = phase.complaintDeletion.requireCommitted(this)

    @Suppress("TooGenericExceptionCaught")
    internal fun allocateAudit(capacity: JdbcComplaintCapacityStore): JdbcComplaintCapacityStore.ChargedComplaintAudit {
        try {
            requireAt(Stage.PREPARED, jdbc)
            stage = Stage.COUNTERS_REQUESTED
            val charged = capacity.chargeDeletionAudit(this)
            requireAt(Stage.COUNTERS_CHARGING, jdbc)
            check(audit === charged && charged.chargedFor(this))
            stage = Stage.CHARGED
            return charged
        } catch (problem: Throwable) {
            failed(problem)
        }
    }

    internal fun beginAuditCharge(selected: JdbcTemplate) {
        requireAt(Stage.COUNTERS_REQUESTED, selected)
        stage = Stage.COUNTERS_LOCKING
    }

    internal fun retainAuditCharge(charged: JdbcComplaintCapacityStore.ChargedComplaintAudit, selected: JdbcTemplate) {
        requireAt(Stage.COUNTERS_LOCKING, selected)
        if (audit != null || !charged.belongsTo(this)) failed(PersistencePhaseException(PersistencePhaseFailureCode.WORK_FAILED))
        audit = charged
        stage = Stage.COUNTERS_CHARGING
    }

    internal fun requireAuditCharge(charged: JdbcComplaintCapacityStore.ChargedComplaintAudit, selected: JdbcTemplate) {
        requireAt(Stage.COUNTERS_CHARGING, selected)
        if (audit !== charged) failed(PersistencePhaseException(PersistencePhaseFailureCode.WORK_FAILED))
    }

    /** Lock-class cursor only, never a caller assertion that SQL happened or that deletion is authorized. */
    internal fun beginProtectedWork(selected: JdbcTemplate) {
        requireAt(Stage.CHARGED, selected)
        stage = Stage.PROTECTED_WORK
    }

    internal fun protectedWorkReturned(selected: JdbcTemplate) {
        requireAt(Stage.PROTECTED_WORK, selected)
        stage = Stage.PROTECTED_RETURNED
    }

    internal fun auditConnection(charged: JdbcComplaintCapacityStore.ChargedComplaintAudit): Connection {
        requireAt(Stage.PROTECTED_RETURNED, jdbc)
        if (audit !== charged || !charged.chargedFor(this)) failed(PersistencePhaseException(PersistencePhaseFailureCode.WORK_FAILED))
        return phase.complaintDeletion.connection(this, jdbc)
    }

    internal fun requireAuditEntry(entry: CountedComplaintAuditEntry) {
        requireAt(Stage.PROTECTED_RETURNED, jdbc)
        if (entry.mutation !== mutation || entry.createdAt != originalAuditTime) failed(PersistencePhaseException(PersistencePhaseFailureCode.WORK_FAILED))
    }

    internal fun failed(problem: Throwable): Nothing {
        phase.recordFailure(problem)
        PersistencePhaseOwnership.current()?.takeIf { it !== phase }?.recordFailure(problem)
        throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
    }

    private fun requireAt(expected: Stage, selected: JdbcTemplate) {
        phase.complaintDeletion.requireRetained(this, selected)
        if (stage !== expected) failed(PersistencePhaseException(PersistencePhaseFailureCode.WORK_FAILED))
    }

    override fun toString(): String = "ComplaintDeletionOperation(redacted)"

    private enum class Stage { PREPARED, COUNTERS_REQUESTED, COUNTERS_LOCKING, COUNTERS_CHARGING, CHARGED, PROTECTED_WORK, PROTECTED_RETURNED }

    companion object {
        @Suppress("TooGenericExceptionCaught")
        internal fun prepare(jdbc: JdbcTemplate, mutation: ComplaintAuditMutation, originalAuditTime: Instant): ComplaintDeletionOperation {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            try {
                phase.complaintDeletion.requireOperation(jdbc)
                return ComplaintDeletionOperation(phase, jdbc, mutation, originalAuditTime).also { phase.complaintDeletion.retain(it, jdbc) }
            } catch (problem: Throwable) {
                phase.recordFailure(problem)
                throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
            }
        }
    }
}
