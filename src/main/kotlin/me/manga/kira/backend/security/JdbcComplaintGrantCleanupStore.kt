package me.manga.kira.backend.security

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCharges
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** Fixed, dormant composition. Grant locks precede all counter locks; neither adapter opens a transaction. */
internal class JdbcComplaintGrantCleanupStore(private val jdbc: JdbcTemplate, private val capacity: JdbcComplaintCapacityStore) : ComplaintGrantCleanup {
    override fun deleteEligibleComplaintGrantsAndRefund(cutoff: Instant): Int = ComplaintGrantCleanupBatch.lock(jdbc, cutoff).deleteAndRefund(capacity)

    override fun toString(): String = "JdbcComplaintGrantCleanupStore(COMPLAINT_GRANT_CLEANUP)"
}

/**
 * A retained, SQL-created batch, not a value receipt. Its constructor, identifiers, deletion count
 * and write cursor are private. Only the fixed selection can create it; no caller count or UUID
 * collection authorizes deletion/refund. The original phase retains it through its finalizer.
 */
internal class ComplaintGrantCleanupBatch private constructor(
    private val phase: PersistencePhaseContext,
    private val jdbc: JdbcTemplate,
    private val cutoff: Instant,
    private val grants: List<UUID>,
) {
    private var stage = Stage.GRANTS_LOCKED
    private var deletedCount = 0
    private var counters: JdbcComplaintCapacityStore.LockedCleanupCounters? = null

    internal fun belongsTo(candidate: PersistencePhaseContext): Boolean = phase === candidate

    internal fun completedCount(candidate: PersistencePhaseContext): Int? = if (phase === candidate && stage === Stage.COMPLETE) deletedCount else null

    // Any caught adapter or late failure must poison this owner (and a current owner attempting stale use).
    @Suppress("TooGenericExceptionCaught")
    internal fun deleteAndRefund(capacity: JdbcComplaintCapacityStore): Int {
        try {
            requireAt(Stage.GRANTS_LOCKED, jdbc)
            if (grants.isEmpty()) {
                stage = Stage.COMPLETE // A genuine empty selection neither needs policy authority nor changes closed seeds.
                return 0
            }
            stage = Stage.COUNTERS_REQUESTED
            val locked = capacity.lockForComplaintGrantCleanup(this)
            requireAt(Stage.COUNTERS_LOCKING, jdbc)
            check(locked.belongsTo(this))
            counters = locked
            stage = Stage.DELETING
            deleteLockedRows()
            stage = Stage.REFUNDING
            locked.refundDeletedBatch(this)
            requireAt(Stage.REFUNDING, jdbc)
            stage = Stage.COMPLETE // Reachable only after every real delete and both exact counter updates returned.
            return deletedCount
        } catch (failure: Throwable) {
            failed(failure)
        }
    }

    internal fun beginCounterLock(selected: JdbcTemplate) {
        requireAt(Stage.COUNTERS_REQUESTED, selected)
        stage = Stage.COUNTERS_LOCKING // Consumed before counter SQL, so failed or reentrant locking cannot be retried.
    }

    internal fun selectedCharge(selected: JdbcTemplate): ComplaintCapacityVector {
        requireAt(Stage.COUNTERS_LOCKING, selected)
        return ComplaintCapacityCharges.MODERATION_GRANT.scaled(grants.size.toLong())
    }

    internal fun requireCounterRefund(locked: JdbcComplaintCapacityStore.LockedCleanupCounters, selected: JdbcTemplate) {
        requireAt(Stage.REFUNDING, selected)
        if (counters !== locked || deletedCount != grants.size) failed(PersistencePhaseException(PersistencePhaseFailureCode.WORK_FAILED))
    }

    internal fun failed(problem: Throwable): Nothing {
        phase.recordFailure(problem)
        PersistencePhaseOwnership.current()?.takeIf { it !== phase }?.recordFailure(problem)
        throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
    }

    private fun requireAt(expected: Stage, selected: JdbcTemplate) {
        phase.requireComplaintGrantBatch(this, selected)
        if (stage !== expected) failed(PersistencePhaseException(PersistencePhaseFailureCode.WORK_FAILED))
    }

    private fun deleteLockedRows() {
        val cutoffTimestamp = Timestamp.from(cutoff)
        for (id in grants) {
            requireAt(Stage.DELETING, jdbc)
            val deleted = jdbc.update(DELETE_LOCKED_GRANT, id, COMPLAINT_SCOPE, cutoffTimestamp)
            check(deleted == 1) // A missing/reclassified retained row aborts the whole batch; no invented smaller refund.
            deletedCount += deleted
        }
        requireAt(Stage.DELETING, jdbc)
        check(deletedCount == grants.size)
    }

    override fun toString(): String = "ComplaintGrantCleanupBatch(redacted)"

    private enum class Stage { GRANTS_LOCKED, COUNTERS_REQUESTED, COUNTERS_LOCKING, DELETING, REFUNDING, COMPLETE }

    companion object {
        private const val BATCH_LIMIT = 50
        private const val COMPLAINT_SCOPE = "complaint-moderation-mutation"
        private val LOCK_ELIGIBLE_GRANTS = """
            SELECT id, scope, expires_at, used_at
            FROM admin_step_up_grants
            WHERE scope = ? AND (expires_at <= ? OR used_at IS NOT NULL)
            ORDER BY id
            LIMIT $BATCH_LIMIT
            FOR UPDATE SKIP LOCKED
        """.trimIndent()
        private val DELETE_LOCKED_GRANT = """
            DELETE FROM admin_step_up_grants
            WHERE id = ? AND scope = ? AND (expires_at <= ? OR used_at IS NOT NULL)
        """.trimIndent()

        // The only issuer performs the actual selection on the selected holder. SQL or decoding failure remains phase-owned.
        @Suppress("TooGenericExceptionCaught")
        internal fun lock(jdbc: JdbcTemplate, cutoff: Instant): ComplaintGrantCleanupBatch {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            try {
                phase.requireComplaintGrantCleanup(jdbc)
                val grants = jdbc.query(
                    LOCK_ELIGIBLE_GRANTS,
                    { result, _ ->
                        val id = requireNotNull(result.getObject("id", UUID::class.java))
                        val expiresAt = requireNotNull(result.getTimestamp("expires_at")).toInstant()
                        check(result.getString("scope") == COMPLAINT_SCOPE)
                        check(!expiresAt.isAfter(cutoff) || result.getTimestamp("used_at") != null)
                        id
                    },
                    COMPLAINT_SCOPE,
                    Timestamp.from(cutoff),
                )
                check(grants.size <= BATCH_LIMIT && grants.distinct().size == grants.size)
                val batch = ComplaintGrantCleanupBatch(phase, jdbc, cutoff, grants.toList())
                phase.retainComplaintGrantBatch(batch, jdbc)
                return batch
            } catch (failure: Throwable) {
                phase.recordFailure(failure)
                throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
            }
        }
    }
}
