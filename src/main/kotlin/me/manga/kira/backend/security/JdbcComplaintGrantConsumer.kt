package me.manga.kira.backend.security

import me.manga.kira.backend.audit.domain.ComplaintAuditActorKind
import me.manga.kira.backend.audit.domain.ComplaintAuditMutation
import me.manga.kira.backend.audit.infrastructure.ComplaintAuditSelectedHolder
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.user.domain.Role
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID

/** Non-bean, existing-phase consumer. No nested transaction, source-proof wrapper or request-admission surrogate. */
internal class JdbcComplaintGrantConsumer(private val jdbc: JdbcTemplate, private val clock: Clock) {
    internal fun consume(userId: UUID, token: String?): ComplaintGrantConsumption = ComplaintGrantConsumption.consume(jdbc, clock, userId, token)

    override fun toString(): String = "JdbcComplaintGrantConsumer(redacted)"
}

/**
 * Grant -> real audit counters -> locked current Admin -> shared audit. This is infrastructure, not
 * a receipt/domain writer or proof of subject membership, abuse admission or a protected mutation.
 * Receipt/protected-state SQL belongs only to explicitly synthetic fixtures, never this consumer.
 */
internal class ComplaintGrantConsumption private constructor(
    private val phase: PersistencePhaseContext,
    private val jdbc: JdbcTemplate,
    private val userId: UUID,
) {
    private var stage = Stage.CONSUMING
    private var audit: JdbcComplaintCapacityStore.ChargedComplaintAudit? = null

    internal fun belongsTo(candidate: PersistencePhaseContext): Boolean = phase === candidate

    internal fun completedFor(candidate: PersistencePhaseContext): Boolean = belongsTo(candidate) && stage === Stage.ADMIN_CHECKED &&
        audit?.completedFor(this) == true

    internal fun requireCommitted() = phase.checkComplaintAuditResult(this)

    /** Real charge and current-Admin read are inseparable; callers cannot substitute a role or a success bit. */
    @Suppress("TooGenericExceptionCaught")
    internal fun allocateAudit(capacity: JdbcComplaintCapacityStore, mutation: ComplaintAuditMutation): JdbcComplaintCapacityStore.ChargedComplaintAudit {
        try {
            requireAt(Stage.CONSUMED, jdbc)
            check(mutation.actor.kind === ComplaintAuditActorKind.ADMIN && mutation.actor.adminUserId == userId)
            stage = Stage.COUNTERS_REQUESTED
            val charged = capacity.chargeComplaintAudit(this, mutation)
            requireAt(Stage.COUNTERS_CHARGING, jdbc)
            check(audit === charged && charged.chargedFor(this))
            stage = Stage.ADMIN_LOCKING
            lockCurrentAdmin()
            stage = Stage.ADMIN_CHECKED
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

    internal fun auditHolder(charged: JdbcComplaintCapacityStore.ChargedComplaintAudit): ComplaintAuditSelectedHolder {
        requireAt(Stage.ADMIN_CHECKED, jdbc)
        if (audit !== charged || !charged.chargedFor(this)) failed(PersistencePhaseException(PersistencePhaseFailureCode.WORK_FAILED))
        return phase.complaintAuditHolder(this, jdbc)
    }

    internal fun failed(problem: Throwable): Nothing {
        phase.recordFailure(problem)
        PersistencePhaseOwnership.current()?.takeIf { it !== phase }?.recordFailure(problem)
        throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
    }

    private fun requireAt(expected: Stage, selected: JdbcTemplate) {
        phase.requireComplaintGrantConsumption(this, selected)
        if (stage !== expected) failed(PersistencePhaseException(PersistencePhaseFailureCode.WORK_FAILED))
    }

    private fun lockCurrentAdmin() {
        requireAt(Stage.ADMIN_LOCKING, jdbc)
        val matches = jdbc.query(
            "SELECT id, enabled, role FROM users WHERE id = ? FOR UPDATE",
            { result, _ ->
                val id = result.getObject("id", UUID::class.java)
                val enabled = result.getBoolean("enabled").also { check(!result.wasNull()) }
                id == userId && enabled && result.getString("role") == Role.ADMIN.name
            },
            userId,
        )
        check(matches.size == 1 && matches.single())
        requireAt(Stage.ADMIN_LOCKING, jdbc)
    }

    override fun toString(): String = "ComplaintGrantConsumption(redacted)"

    private enum class Stage { CONSUMING, CONSUMED, COUNTERS_REQUESTED, COUNTERS_LOCKING, COUNTERS_CHARGING, ADMIN_LOCKING, ADMIN_CHECKED }

    companion object {
        private const val MAX_TOKEN_CHARS = 128
        private val LOCK_GRANT = """
            SELECT id FROM admin_step_up_grants
            WHERE user_id = ? AND token_hash = ? AND scope = 'complaint-moderation-mutation' AND used_at IS NULL
            FOR UPDATE
        """.trimIndent()
        private val CONSUME = """
            UPDATE admin_step_up_grants SET used_at = ?
            WHERE id = ? AND user_id = ? AND token_hash = ? AND scope = 'complaint-moderation-mutation'
                AND used_at IS NULL AND expires_at > ?
            RETURNING id
        """.trimIndent()

        @Suppress("TooGenericExceptionCaught")
        internal fun consume(jdbc: JdbcTemplate, clock: Clock, userId: UUID, token: String?): ComplaintGrantConsumption {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            try {
                phase.requireComplaintGrantConsumption(jdbc)
                val supplied = checkNotNull(token)
                check(supplied.isNotBlank() && supplied.length <= MAX_TOKEN_CHARS)
                val consumption = ComplaintGrantConsumption(phase, jdbc, userId)
                phase.retainComplaintGrantConsumption(consumption, jdbc)
                consumption.requireAt(Stage.CONSUMING, jdbc)
                val hash = Sha256.hexUtf8(supplied)
                val locked = jdbc.query(LOCK_GRANT, { result, _ -> result.getObject("id", UUID::class.java) }, userId, hash)
                check(locked.size == 1 && locked.single() != null)
                consumption.requireAt(Stage.CONSUMING, jdbc)
                val now = Timestamp.from(clock.instant()) // Recheck expiry after the exact row lock, never with a pre-wait timestamp.
                val ids = jdbc.query(CONSUME, { result, _ -> result.getObject("id", UUID::class.java) }, now, locked.single(), userId, hash, now)
                check(ids == locked)
                consumption.requireAt(Stage.CONSUMING, jdbc)
                consumption.stage = Stage.CONSUMED
                return consumption
            } catch (problem: Throwable) {
                phase.recordFailure(problem)
                throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
            }
        }
    }
}
