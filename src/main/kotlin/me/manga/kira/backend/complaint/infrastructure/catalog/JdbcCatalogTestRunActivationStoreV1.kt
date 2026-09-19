package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor

/** Named TEST snapshot/row-only lease/atomic PREPARE/exact reload only. No publication or projection transition. */
internal class JdbcCatalogTestRunActivationStoreV1(private val jdbc: JdbcTemplate) {
    fun execute(input: CatalogTestRunActivationInputV1, capacity: JdbcComplaintCapacityStore): CatalogTestRunActivationOperationV1 =
        CatalogTestRunActivationOperationV1.execute(jdbc, input, capacity)
}

/** One actual retained holder. No observation, caller tuple or enum alone can create or settle this operation. */
internal class CatalogTestRunActivationOperationV1 private constructor(
    private val phase: PersistencePhaseContext,
    private val jdbc: JdbcTemplate,
    internal val input: CatalogTestRunActivationInputV1,
) {
    private var stage = Stage.RETAINED
    private var lockedSnapshot: CatalogTestRunActivationSnapshotV1? = null
    private var captured: CatalogTestRunActivationSnapshotV1? = null
    private var acquired: CatalogTestRunActivationLeaseV1? = null
    private var counters: JdbcComplaintCapacityStore.LockedCatalogTestRunActivation? = null

    internal fun belongsTo(selected: PersistencePhaseContext, path: PersistencePhasePath): Boolean = phase === selected && input.path === path
    internal fun completedFor(selected: PersistencePhaseContext): Boolean = phase === selected && stage === Stage.COMPLETE

    internal fun requireReleased() {
        phase.catalogTestRunActivation.requireCommitted(this)
        requireConnectionFree()
    }

    internal val snapshot: CatalogTestRunActivationSnapshotV1
        get() {
            requireReleased()
            return checkNotNull(captured)
        }

    internal val lease: CatalogTestRunActivationLeaseV1
        get() {
            requireReleased()
            check(input.kind === CatalogTestRunActivationKindV1.LEASE_ACQUIRE)
            return checkNotNull(acquired)
        }

    @Suppress("TooGenericExceptionCaught")
    private fun run(capacity: JdbcComplaintCapacityStore) {
        try {
            requireAt(Stage.RETAINED)
            when (input.kind) {
                CatalogTestRunActivationKindV1.SNAPSHOT -> observeSnapshot()
                CatalogTestRunActivationKindV1.LEASE_ACQUIRE -> acquireLease()
                CatalogTestRunActivationKindV1.PREPARE, CatalogTestRunActivationKindV1.PREPARED_RELOAD -> prepareOrReload(capacity)
            }
            requireRetained()
            stage = Stage.COMPLETE
        } catch (problem: Throwable) {
            failed(problem)
        }
    }

    /** Released observation only: a later locked exact preimage check is mandatory before any effect. */
    private fun observeSnapshot() {
        check(input.expected == null && input.lease == null && input.leaseOwner == null)
        val control = readControl(lock = false)
        val history = readHistory(lock = false)
        val value = CatalogTestRunActivationSnapshotV1(control, history)
        value.requireExpected(input.frozen, prepared = input.recovering)
        readControl(lock = false).requireSame(control, closed = false)
        requireAt(Stage.RETAINED)
        captured = value
    }

    /** Shared M then ONLY the permanent global row: no E/catalog/history/counter lock on this acquisition. */
    private fun acquireLease() {
        check(input.lease == null)
        val expected = checkNotNull(input.expected)
        val owner = checkNotNull(input.leaseOwner)
        val control = readControl(lock = true)
        control.requireSame(expected.control, closed = false)
        control.requirePredecessor(input.frozen)
        stage = Stage.CONTROL_LOCKED
        val lease = jdbc.query(CatalogTestRunActivationSqlV1.acquireLease, { row, _ -> CatalogTestRunActivationLeaseV1.copy(row) }, owner).single()
        requireAt(Stage.CONTROL_LOCKED)
        check(lease.owner == owner && control.leaseToken < Long.MAX_VALUE && lease.token == control.leaseToken + 1L)
        requireCurrentLease(lease)
        readControl(lock = false).requireSame(control, closed = false)
        acquired = lease
    }

    /** Initial direct exclusive M or authorized shared M -> shared E -> control -> history -> ascending counters. */
    private fun prepareOrReload(capacity: JdbcComplaintCapacityStore) {
        val creating = input.kind === CatalogTestRunActivationKindV1.PREPARE
        check(!creating || !input.recovering)
        val control = readControl(lock = true)
        control.requireSame(checkNotNull(input.expected).control, closed = false)
        control.requirePredecessor(input.frozen)
        stage = Stage.CONTROL_LOCKED
        requireCurrentLease() // A NEW SQL sample after the actual row lock, not transaction-start time or the lease receipt.
        check(jdbc.query(TRY_CATALOG_LOCK, { row, _ -> row.requiredTestActivationBoolean("locked") }).single())
        requireAt(Stage.CONTROL_LOCKED)
        stage = Stage.CATALOG_LOCKED
        val before = CatalogTestRunActivationSnapshotV1(control, readHistory(lock = true))
        before.requireExpected(input.frozen, prepared = !creating)
        before.requireSame(checkNotNull(input.expected), closed = false)
        lockedSnapshot = before
        stage = Stage.HISTORY_LOCKED
        requireProjectionPreflight()
        val locked = capacity.lockForCatalogTestRunActivation(this)
        requireAt(Stage.COUNTERS_LOCKING)
        check(locked.belongsTo(this))
        counters = locked
        stage = Stage.SETTLING_COUNTERS
        locked.settle(this) // Fit the full future projection/reserve; persist ONLY initial preparation charge.
        requireAt(Stage.SETTLING_COUNTERS)
        check(locked.settledFor(this))
        stage = Stage.WRITING
        requireCurrentLease()
        if (creating) {
            check(jdbc.update(CatalogTestRunActivationSqlV1.closeControl, *checkNotNull(input.lease).arguments()) == 1)
            requireAt(Stage.WRITING)
            check(jdbc.update(CatalogTestRunActivationSqlV1.insertPrepared, *input.frozen.preparedArguments()) == 1)
        }
        requireAt(Stage.WRITING)
        stage = Stage.REREADING
        val after = CatalogTestRunActivationSnapshotV1(readControl(lock = false), readHistory(lock = false))
        after.requireExpected(input.frozen, prepared = true)
        after.control.requireSame(before.control, closed = true)
        after.history.requireUnchangedPrefix(before.history, appended = creating)
        requireProjectionPreflight()
        requireCurrentLease()
        requireAt(Stage.REREADING)
        captured = after
    }

    private fun requireProjectionPreflight() {
        requireRetained()
        check(jdbc.query(CatalogTestRunActivationSqlV1.preflight, { row, _ -> row.requiredTestActivationBoolean("allowed") }, *input.frozen.preflightArguments()).single())
        requireRetained()
    }

    private fun readControl(lock: Boolean): CatalogTestRunActivationControlV1 {
        requireRetained()
        val value = jdbc.query(
            if (lock) CatalogTestRunActivationSqlV1.lockControl else CatalogTestRunActivationSqlV1.readControl,
            { row, _ -> CatalogTestRunActivationControlV1.copy(row) },
        ).single()
        requireRetained()
        return value
    }

    private fun requireCurrentLease(lease: CatalogTestRunActivationLeaseV1 = checkNotNull(input.lease)) {
        requireRetained()
        check(jdbc.query(CatalogTestRunActivationSqlV1.currentLease, { row, _ -> row.requiredTestActivationBoolean("current") }, *lease.arguments()).single())
        requireRetained()
    }

    private fun readHistory(lock: Boolean): CatalogTestRunActivationHistoryV1 {
        requireRetained()
        check(jdbc.fetchSize == CatalogTestRunActivationHistoryV1.FETCH_ROWS)
        val rows = checkNotNull(
            jdbc.query(
                if (lock) CatalogTestRunActivationSqlV1.lockHistory else CatalogTestRunActivationSqlV1.readHistory,
                ResultSetExtractor { result ->
                    CatalogTestRunActivationHistoryV1.read(result, input.frozen, retainRaw = input.kind === CatalogTestRunActivationKindV1.SNAPSHOT)
                },
                *input.frozen.preparedArguments(), input.frozen.maximumGenerations + 1,
            ),
        )
        requireRetained()
        return rows
    }

    internal fun beginCounterLock(selected: JdbcTemplate) {
        requireAt(Stage.HISTORY_LOCKED)
        check(selected === jdbc)
        stage = Stage.COUNTERS_LOCKING
    }

    internal fun requireCounterSettlement(locked: JdbcComplaintCapacityStore.LockedCatalogTestRunActivation, selected: JdbcTemplate): Pair<Int, Boolean> {
        requireAt(Stage.SETTLING_COUNTERS)
        check(counters === locked && selected === jdbc)
        return checkNotNull(lockedSnapshot).history.size to (input.kind === CatalogTestRunActivationKindV1.PREPARE)
    }

    internal fun failed(problem: Throwable): Nothing {
        stage = Stage.FAILED
        input.original.observeFailure(problem)
        phase.recordFailure(problem)
        PersistencePhaseOwnership.current()?.takeIf { it !== phase }?.recordFailure(problem)
        input.original.throwIfSignalled()
        throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
    }

    private fun requireRetained() = phase.catalogTestRunActivation.requireRetained(this, jdbc)

    private fun requireAt(expected: Stage) {
        requireRetained()
        check(stage === expected)
    }

    override fun toString(): String = "CatalogTestRunActivationOperationV1(original-PREPARED-only,no-publication-or-run-authority)"

    private enum class Stage { RETAINED, CONTROL_LOCKED, CATALOG_LOCKED, HISTORY_LOCKED, COUNTERS_LOCKING, SETTLING_COUNTERS, WRITING, REREADING, COMPLETE, FAILED }

    companion object {
        @Suppress("TooGenericExceptionCaught")
        internal fun execute(jdbc: JdbcTemplate, input: CatalogTestRunActivationInputV1, capacity: JdbcComplaintCapacityStore): CatalogTestRunActivationOperationV1 {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            try {
                phase.catalogTestRunActivation.requireOperation(input, jdbc)
                val operation = CatalogTestRunActivationOperationV1(phase, jdbc, input)
                phase.catalogTestRunActivation.retain(operation, jdbc)
                operation.run(capacity)
                return operation
            } catch (problem: Throwable) {
                input.original.observeFailure(problem)
                phase.recordFailure(problem)
                input.original.throwIfSignalled()
                throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
            }
        }
    }
}
