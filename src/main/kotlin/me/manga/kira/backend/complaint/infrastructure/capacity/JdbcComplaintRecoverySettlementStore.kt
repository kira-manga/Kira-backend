package me.manga.kira.backend.complaint.infrastructure.capacity

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.complaint.domain.ComplaintCapacityEncoding
import me.manga.kira.backend.complaint.domain.ComplaintCapacityLedger
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.ComplaintRecoverySettlement
import me.manga.kira.backend.complaint.domain.ComplaintRecoverySettlementExpectation
import me.manga.kira.backend.complaint.domain.ComplaintRecoverySettlementResult
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.sql.Types
import java.util.UUID

/**
 * Dormant/non-bean lower SQL composition, NOT a production recovery writer or authenticated proof
 * producer. Only synthetic fixtures bind it. Publication -> original reservation -> all counters
 * share the existing ordinary phase; no insertion, terminal/catalog transition or partial release.
 */
internal class JdbcComplaintRecoverySettlementStore(private val jdbc: JdbcTemplate, private val capacity: JdbcComplaintCapacityStore) :
    ComplaintRecoverySettlement {
    override fun settle(expectation: ComplaintRecoverySettlementExpectation): ComplaintRecoverySettlementResult =
        ComplaintRecoverySettlementOperation.lock(jdbc, expectation).settle(capacity)

    override fun toString(): String = "JdbcComplaintRecoverySettlementStore(redacted)"
}

/** Private cursor retained before SQL. A locked row proves comparison/ownership, never erasure authority. */
internal class ComplaintRecoverySettlementOperation private constructor(
    private val phase: PersistencePhaseContext,
    private val jdbc: JdbcTemplate,
    private val expectation: ComplaintRecoverySettlementExpectation,
) {
    private var stage = Stage.PREPARED
    private var reservation: Reservation? = null
    private var counters: JdbcComplaintCapacityStore.LockedRecoveryTransfer? = null
    private var result: ComplaintRecoverySettlementResult? = null

    internal fun belongsTo(candidate: PersistencePhaseContext): Boolean = phase === candidate

    internal fun completedResult(candidate: PersistencePhaseContext): ComplaintRecoverySettlementResult? =
        if (phase === candidate && stage === Stage.COMPLETE) result else null

    @Suppress("TooGenericExceptionCaught")
    internal fun settle(capacity: JdbcComplaintCapacityStore): ComplaintRecoverySettlementResult {
        try {
            requireAt(Stage.RESERVATION_LOCKED, jdbc)
            stage = Stage.COUNTERS_REQUESTED
            val locked = capacity.lockForRecoverySettlement(this)
            requireAt(Stage.COUNTERS_LOCKING, jdbc)
            check(locked.belongsTo(this))
            counters = locked
            stage = Stage.TRANSFERRING
            locked.transfer(this)
            requireAt(Stage.TRANSFERRING, jdbc)
            check(locked.completedFor(this))
            stage = Stage.SETTLING
            val replay = checkNotNull(reservation).replay
            if (!replay) transitionReservation()
            requireAt(Stage.SETTLING, jdbc)
            result = if (replay) ComplaintRecoverySettlementResult.REPLAYED else ComplaintRecoverySettlementResult.SETTLED
            stage = Stage.COMPLETE
            return checkNotNull(result)
        } catch (problem: Throwable) {
            failed(problem)
        }
    }

    internal fun beginCounterLock(selected: JdbcTemplate) {
        requireAt(Stage.COUNTERS_REQUESTED, selected)
        stage = Stage.COUNTERS_LOCKING
    }

    internal fun convertLockedLedger(selected: JdbcTemplate, ledger: ComplaintCapacityLedger, expectedDigest: ByteArray): ComplaintCapacityLedger {
        requireAt(Stage.COUNTERS_LOCKING, selected)
        val original = checkNotNull(reservation)
        return if (original.replay) ledger else ledger.convertRecovery(expectedDigest, original.promise, expectation.actualUse)
    }

    internal fun requireCounterTransfer(locked: JdbcComplaintCapacityStore.LockedRecoveryTransfer, selected: JdbcTemplate) {
        requireAt(Stage.TRANSFERRING, selected)
        if (counters !== locked) failed(PersistencePhaseException(PersistencePhaseFailureCode.WORK_FAILED))
    }

    internal fun failed(problem: Throwable): Nothing {
        phase.recordFailure(problem)
        PersistencePhaseOwnership.current()?.takeIf { it !== phase }?.recordFailure(problem)
        throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
    }

    private fun requireAt(expected: Stage, selected: JdbcTemplate) {
        phase.requireComplaintRecoverySettlement(this, selected)
        if (stage !== expected) failed(PersistencePhaseException(PersistencePhaseFailureCode.WORK_FAILED))
    }

    private fun lockRows() {
        requireAt(Stage.PREPARED, jdbc)
        stage = Stage.PUBLICATION_LOCKING
        val publications = jdbc.query(LOCK_PUBLICATION, { row, _ -> readPublication(row, expectation) }, expectation.eventId)
        requireAt(Stage.PUBLICATION_LOCKING, jdbc)
        check(publications.size == 1)
        stage = Stage.RESERVATION_LOCKING
        reservation = jdbc.query(
            LOCK_RESERVATION,
            { row, _ -> readReservation(row, expectation) },
            expectation.eventId,
            expectation.scope.id,
        ).single()
        requireAt(Stage.RESERVATION_LOCKING, jdbc)
        stage = Stage.RESERVATION_LOCKED
    }

    private fun transitionReservation() {
        requireAt(Stage.SETTLING, jdbc)
        val updated = jdbc.update(
            SETTLE_RESERVATION,
            expectation.actualUse.sqlArray(),
            expectation.eventId,
            expectation.scope.id,
            expectation.scope.testOnly,
            expectation.accountingVersion,
            checkNotNull(reservation).promise.sqlArray(),
        )
        check(updated == 1)
    }

    override fun toString(): String = "ComplaintRecoverySettlementOperation(redacted)"

    private class Reservation(val promise: ComplaintCapacityVector, val replay: Boolean)

    private enum class Stage {
        PREPARED,
        PUBLICATION_LOCKING,
        RESERVATION_LOCKING,
        RESERVATION_LOCKED,
        COUNTERS_REQUESTED,
        COUNTERS_LOCKING,
        TRANSFERRING,
        SETTLING,
        COMPLETE,
    }

    companion object {
        private val LOCK_PUBLICATION = """
            SELECT event_id, data_scope_id, test_only, event_kind, state,
                complaint_event_count_valid(event_kind, target_count, test_only) AS valid_kind,
                isfinite(created_at) AND complaint_finite_times(object_created_at, retain_until, verified_at, applied_at) AS finite_times
            FROM complaint_journal_publications
            WHERE event_id = ?
            FOR UPDATE
        """.trimIndent()
        private val LOCK_RESERVATION = """
            SELECT event_id, data_scope_id, test_only, publication_ref, state, accounting_version,
                reserved_amounts, array_ndims(reserved_amounts) AS reserved_dimensions,
                array_lower(reserved_amounts, 1) AS reserved_lower, cardinality(reserved_amounts) AS reserved_width,
                converted_amounts, array_ndims(converted_amounts) AS converted_dimensions,
                array_lower(converted_amounts, 1) AS converted_lower, cardinality(converted_amounts) AS converted_width,
                converted_amounts IS NOT NULL AS converted_present, converted_at IS NOT NULL AS converted_at_present,
                isfinite(created_at) AND (converted_at IS NULL OR isfinite(converted_at)) AS finite_times
            FROM complaint_recovery_capacity_reservations
            WHERE event_id = ? AND data_scope_id = ?
            FOR UPDATE
        """.trimIndent()
        private val SETTLE_RESERVATION = """
            UPDATE complaint_recovery_capacity_reservations
            SET state = 'CONVERTED', converted_amounts = ?::bigint[], converted_at = now()
            WHERE event_id = ? AND data_scope_id = ? AND test_only = ? AND accounting_version = ?
                AND publication_ref = event_id AND state = 'RESERVED' AND reserved_amounts = ?::bigint[]
                AND converted_amounts IS NULL AND converted_at IS NULL
        """.trimIndent()
        private val NONTERMINAL_KINDS = setOf("OWNER_DELETE", "ADMIN_DELETE", "ADMIN_BATCH_DELETE", "OWNER_DELETE_ALL", "RETENTION", "INSTALLATION_RETIREMENT")

        @Suppress("TooGenericExceptionCaught")
        internal fun lock(jdbc: JdbcTemplate, expectation: ComplaintRecoverySettlementExpectation): ComplaintRecoverySettlementOperation {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            try {
                phase.requireComplaintRecoverySettlement(jdbc)
                val operation = ComplaintRecoverySettlementOperation(phase, jdbc, expectation)
                phase.retainComplaintRecoverySettlement(operation, jdbc) // Before the first publication SQL, including decoder failure.
                operation.lockRows()
                return operation
            } catch (problem: Throwable) {
                phase.recordFailure(problem)
                throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
            }
        }

        private fun readPublication(row: ResultSet, expected: ComplaintRecoverySettlementExpectation) {
            requireIdentity(row, expected)
            check(requiredBoolean(row, "valid_kind") && requiredBoolean(row, "finite_times"))
            check(row.getString("event_kind") in NONTERMINAL_KINDS)
            check(row.getString("state") in setOf("PREPARED", "VERIFIED", "APPLIED"))
            // State/bytes are NOT proof of actual or unused work. Terminal event/catalog authority is deliberately absent.
        }

        private fun readReservation(row: ResultSet, expected: ComplaintRecoverySettlementExpectation): Reservation {
            requireIdentity(row, expected)
            val version = requiredInt(row, "accounting_version")
            ComplaintCapacityEncoding.requireVersion(version)
            check(version == expected.accountingVersion && requiredBoolean(row, "finite_times"))
            // Compacted/null-reference replay is closed until W04 can authenticate the missing event kind.
            check(row.getString("publication_ref") == expected.eventId)
            val original = readVector(row, "reserved", version)
            check(original == expected.originalPromise)
            val converted = requiredBoolean(row, "converted_present")
            val convertedAt = requiredBoolean(row, "converted_at_present")
            val replay = when (row.getString("state")) {
                "RESERVED" -> {
                    check(!converted && !convertedAt)
                    false
                }

                "CONVERTED" -> {
                    check(converted && convertedAt)
                    val used = readVector(row, "converted", version)
                    check(used.fitsWithin(original) && used == expected.actualUse)
                    true
                }

                else -> error("Invalid recovery settlement state.")
            }
            return Reservation(original, replay)
        }

        private fun requireIdentity(row: ResultSet, expected: ComplaintRecoverySettlementExpectation) {
            check(row.getString("event_id") == expected.eventId)
            check(row.getObject("data_scope_id", UUID::class.java) == expected.scope.id)
            check(requiredBoolean(row, "test_only") == expected.scope.testOnly)
        }

        private fun readVector(row: ResultSet, prefix: String, version: Int): ComplaintCapacityVector {
            check(requiredInt(row, "${prefix}_dimensions") == 1 && requiredInt(row, "${prefix}_lower") == 1)
            check(requiredInt(row, "${prefix}_width") == ComplaintCapacityEncoding.WIDTH)
            val array = checkNotNull(row.getArray("${prefix}_amounts"))
            try {
                check(array.baseType == Types.BIGINT)
                val values = array.array as? Array<*> ?: error("Invalid recovery vector representation.")
                check(values.size == ComplaintCapacityEncoding.WIDTH)
                return ComplaintCapacityVector.of(LongArray(values.size) { checkNotNull(values[it] as? Long) }, version)
            } finally {
                array.free()
            }
        }

        private fun ComplaintCapacityVector.sqlArray(): String = toLongArray().joinToString(separator = ",", prefix = "{", postfix = "}")

        private fun requiredInt(row: ResultSet, name: String): Int = row.getInt(name).also { check(!row.wasNull()) }

        private fun requiredBoolean(row: ResultSet, name: String): Boolean = row.getBoolean(name).also { check(!row.wasNull()) }
    }
}
