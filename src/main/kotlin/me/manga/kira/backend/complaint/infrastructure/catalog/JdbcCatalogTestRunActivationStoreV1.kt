package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.audit.infrastructure.ComplaintTestRunActivationAuditInsertionV1
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** Named TEST snapshot/lease/PREPARE/signature/COMPLETE/atomic first PROJECT SQL only. No provider transition. */
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
    private var projectionAt: Instant? = null
    private var projectionAudit: ComplaintTestRunActivationAuditInsertionV1? = null
    private var projectionAuditOrdinal = 0

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
                CatalogTestRunActivationKindV1.PREPARE, CatalogTestRunActivationKindV1.PREPARED_RELOAD,
                CatalogTestRunActivationKindV1.SIGNATURE, CatalogTestRunActivationKindV1.SIGNED_RELOAD -> prepareOrReload(capacity)
                CatalogTestRunActivationKindV1.DELIVERY_RELOAD, CatalogTestRunActivationKindV1.COMPLETE,
                CatalogTestRunActivationKindV1.PENDING_RELOAD -> completeOrReload(capacity)
                CatalogTestRunActivationKindV1.PROJECT_RELOAD, CatalogTestRunActivationKindV1.PROJECT,
                CatalogTestRunActivationKindV1.PROJECTED_RELOAD -> projectOrReload(capacity)
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
        val value = readSnapshot(control, lock = false)
        if (input.projecting) value.requireProjection(input.frozen, checkNotNull(input.signed))
        else if (input.delivering) value.requireDelivery(input.frozen, checkNotNull(input.signed))
        else value.requireExpected(input.frozen, prepared = input.recovering, signed = expectedSignature(value))
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
        if (input.projecting) control.requireProjection(input.frozen, checkNotNull(input.signed), checkNotNull(expected.completedTail).projectedAt != null)
        else if (input.delivering) control.requireDelivery(input.frozen, checkNotNull(input.signed), expected.completedTail != null)
        else control.requirePredecessor(input.frozen)
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
        check(!input.projecting)
        val creating = input.kind === CatalogTestRunActivationKindV1.PREPARE
        val signature = input.kind === CatalogTestRunActivationKindV1.SIGNATURE
        val signedReload = input.kind === CatalogTestRunActivationKindV1.SIGNED_RELOAD
        check(!creating || !input.recovering)
        if (signature || signedReload) check(input.signed != null)
        val control = readControl(lock = true)
        control.requireSame(checkNotNull(input.expected).control, closed = false)
        control.requirePredecessor(input.frozen)
        stage = Stage.CONTROL_LOCKED
        requireCurrentLease() // A NEW SQL sample after the actual row lock, not transaction-start time or the lease receipt.
        check(jdbc.query(TRY_CATALOG_LOCK, { row, _ -> row.requiredTestActivationBoolean("locked") }).single())
        requireAt(Stage.CONTROL_LOCKED)
        stage = Stage.CATALOG_LOCKED
        val before = readSnapshot(control, lock = true)
        before.requireExpected(input.frozen, prepared = !creating, signed = expectedSignature(before))
        if (creating || input.kind === CatalogTestRunActivationKindV1.PREPARED_RELOAD) check(before.signedTail == null)
        if (signedReload) check(before.signedTail != null)
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
        } else if (signature && before.signedTail == null) {
            check(jdbc.update(CatalogTestRunActivationSqlV1.persistSignature,
                *checkNotNull(input.signed).signatureArguments(), *input.frozen.preparedArguments()) == 1)
        }
        // An already exact signed row takes NO UPDATE at all; replay cannot churn xmin or recharge counters.
        requireAt(Stage.WRITING)
        stage = Stage.REREADING
        val after = readSnapshot(readControl(lock = false), lock = false)
        after.requireExpected(input.frozen, prepared = true, signed = if (signature || signedReload) checkNotNull(input.signed) else null)
        after.control.requireSame(before.control, closed = true)
        if (signature) after.history.requireSignatureTransition(before.history)
        else after.history.requireUnchangedPrefix(before.history, appended = creating)
        requireProjectionPreflight()
        requireCurrentLease()
        requireAt(Stage.REREADING)
        captured = after
    }

    /** Existing exact signed TEST only: shared M -> E -> global control -> fresh lease -> catalog/history -> counters. */
    private fun completeOrReload(capacity: JdbcComplaintCapacityStore) {
        check(input.delivering && input.recovering && !input.projecting)
        val signed = checkNotNull(input.signed)
        val proof = checkNotNull(input.deliveryProof)
        val completing = input.kind === CatalogTestRunActivationKindV1.COMPLETE
        val pending = input.kind === CatalogTestRunActivationKindV1.PENDING_RELOAD
        if (completing || pending) check(proof.state === CatalogTestRunActivationDeliveryReadbackV1.State.DUAL_COPY)
        val expected = checkNotNull(input.expected)
        val control = readControl(lock = true)
        control.requireSame(expected.control, closed = false)
        control.requireDelivery(input.frozen, signed, expected.completedTail != null)
        stage = Stage.CONTROL_LOCKED
        requireCurrentLease()
        check(jdbc.query(TRY_CATALOG_LOCK, { row, _ -> row.requiredTestActivationBoolean("locked") }).single())
        requireAt(Stage.CONTROL_LOCKED)
        stage = Stage.CATALOG_LOCKED
        val before = readSnapshot(control, lock = true)
        before.requireDelivery(input.frozen, signed)
        before.requireSame(expected, closed = false)
        before.completedTail?.requireExact(proof)
        if (pending) check(before.completedTail != null)
        lockedSnapshot = before
        stage = Stage.HISTORY_LOCKED
        requireProjectionPreflight()
        val locked = capacity.lockForCatalogTestRunActivation(this)
        requireAt(Stage.COUNTERS_LOCKING)
        check(locked.belongsTo(this))
        counters = locked
        stage = Stage.SETTLING_COUNTERS
        locked.settle(this) // Existing charge/fit checks only. No PREPARE, reserve, refund, daily update or projection charge.
        requireAt(Stage.SETTLING_COUNTERS)
        check(locked.settledFor(this))
        stage = Stage.WRITING
        requireCurrentLease()
        if (completing && before.completedTail == null) {
            check(jdbc.update(CatalogTestRunActivationSqlV1.complete,
                *proof.completionArguments(), *input.frozen.preparedArguments(), *signed.signatureArguments()) == 1)
            requireAt(Stage.WRITING)
            requireCurrentLease()
            check(jdbc.update(CatalogTestRunActivationSqlV1.markPending,
                *signed.deliveryControlArguments(), *checkNotNull(input.lease).arguments()) == 1)
        }
        // Exact already-completed replay performs NO UPDATE, preserving timestamp, row bytes and xmin.
        requireAt(Stage.WRITING)
        stage = Stage.REREADING
        val after = readSnapshot(readControl(lock = false), lock = false)
        after.requireDelivery(input.frozen, signed)
        if (completing) {
            checkNotNull(after.completedTail).requireExact(proof)
            after.control.requireCompletionTransition(before.control, input.frozen, signed)
            after.history.requireCompletionTransition(before.history)
            checkNotNull(after.signedTail).requireSame(checkNotNull(before.signedTail))
            before.completedTail?.let { checkNotNull(after.completedTail).requireSame(it) }
        } else after.requireSame(before, closed = false)
        requireProjectionPreflight()
        requireCurrentLease()
        requireAt(Stage.REREADING)
        captured = after
    }

    /** Shared M/E, global control/current lease, existing scope control if any, catalog/history, counters, fixed effect. */
    @Suppress("LongMethod")
    private fun projectOrReload(capacity: JdbcComplaintCapacityStore) {
        check(input.projecting && input.delivering && input.recovering)
        val signed = checkNotNull(input.signed)
        val proof = checkNotNull(input.deliveryProof)
        check(proof.state === CatalogTestRunActivationDeliveryReadbackV1.State.DUAL_COPY)
        val expected = checkNotNull(input.expected)
        val writing = input.kind === CatalogTestRunActivationKindV1.PROJECT
        val wasProjected = checkNotNull(expected.completedTail).projectedAt != null
        if (writing) {
            check(!wasProjected && expected.projectionRows != null)
            input.original.requireProjectionDispatch(this)
        }
        if (input.kind === CatalogTestRunActivationKindV1.PROJECTED_RELOAD) check(wasProjected && expected.projectionRows != null)
        val control = readControl(lock = true)
        control.requireSame(expected.control, closed = false)
        control.requireProjection(input.frozen, signed, wasProjected)
        stage = Stage.CONTROL_LOCKED
        requireCurrentLease()
        if (wasProjected) {
            check(jdbc.query(CatalogTestRunActivationProjectionSqlV1.lockScopeControl,
                { row, _ -> row.getObject("data_scope_id", UUID::class.java) }, input.frozen.scope).single() == input.frozen.scope)
            requireAt(Stage.CONTROL_LOCKED)
        } // Never lock a not-yet-created scope, and never acquire this earlier-class lock after counters.
        check(jdbc.query(TRY_CATALOG_LOCK, { row, _ -> row.requiredTestActivationBoolean("locked") }).single())
        requireAt(Stage.CONTROL_LOCKED)
        stage = Stage.CATALOG_LOCKED
        var before = readSnapshot(control, lock = true)
        before.requireProjection(input.frozen, signed)
        before.requireSame(expected, closed = false, compareProjection = false)
        checkNotNull(before.completedTail).requireExact(proof)
        lockedSnapshot = before
        stage = Stage.HISTORY_LOCKED
        if (!wasProjected) requireProjectionPreflight()
        val locked = capacity.lockForCatalogTestRunActivation(this)
        requireAt(Stage.COUNTERS_LOCKING)
        check(locked.belongsTo(this))
        counters = locked
        val beforeCounters = locked.projectionBefore(this)
        if (!wasProjected) {
            before = withProjectionEffect(before, beforeCounters)
            expected.projectionRows?.let { checkNotNull(before.projectionRows).requireSame(it) }
            lockedSnapshot = before
        }
        stage = Stage.SETTLING_COUNTERS
        locked.settle(this)
        requireAt(Stage.SETTLING_COUNTERS)
        check(locked.settledFor(this))
        stage = Stage.WRITING
        requireCurrentLease()
        if (wasProjected) {
            lockProjectedDomainEffect()
            before = withProjectionEffect(before, beforeCounters)
            expected.projectionRows?.let { checkNotNull(before.projectionRows).requireSame(it) }
            lockedSnapshot = before
        } else if (writing) writeProjection(before)
        // Read-only captures/rechecks NEVER touch domain/catalog/counter timestamps, audit IDs or xmin.
        requireRetained()
        stage = Stage.REREADING
        var after = readSnapshot(readControl(lock = false), lock = false)
        after.requireProjection(input.frozen, signed)
        after = withProjectionEffect(after, locked.rereadProjection(this))
        if (writing) {
            check(checkNotNull(after.completedTail).projectedAt == projectionAt)
            after.control.requireProjectionTransition(before.control, input.frozen, signed)
            after.history.requireProjectionTransition(before.history)
            checkNotNull(after.signedTail).requireSame(checkNotNull(before.signedTail))
            checkNotNull(after.completedTail).requireProjectionTransition(checkNotNull(before.completedTail))
            checkNotNull(after.projectionRows).requireProjectionTransition(checkNotNull(before.projectionRows), input.frozen)
            check(checkNotNull(projectionAudit).completedFor(this))
        } else after.requireSame(before, closed = false)
        if (after.completedTail?.projectedAt == null) requireProjectionPreflight()
        requireCurrentLease()
        requireAt(Stage.REREADING)
        captured = after
    }

    private fun writeProjection(before: CatalogTestRunActivationSnapshotV1) {
        requireAt(Stage.WRITING)
        check(input.kind === CatalogTestRunActivationKindV1.PROJECT && projectionAt == null && before.completedTail?.projectedAt == null)
        projectionAt = jdbc.query(CatalogTestRunActivationProjectionSqlV1.sampleTime,
            { row, _ -> checkNotNull(row.getTimestamp("sampled_at")).toInstant() }, Timestamp.from(checkNotNull(before.completedTail).completedAt)).single()
        requireAt(Stage.WRITING)
        stage = Stage.WRITING_RUN
        check(jdbc.update(CatalogTestRunActivationProjectionSqlV1.insertRun, *projectionArguments()) == 1)
        requireAt(Stage.WRITING_RUN)
        stage = Stage.WRITING_CONTROL
        check(jdbc.update(CatalogTestRunActivationProjectionSqlV1.insertControl, *projectionArguments()) == 1)
        requireAt(Stage.WRITING_CONTROL)
        stage = Stage.WRITING_RESOURCES
        check(jdbc.update(CatalogTestRunActivationProjectionSqlV1.insertResources, *projectionArguments()) == 2)
        requireAt(Stage.WRITING_RESOURCES)
        stage = Stage.WRITING_NOTICES
        check(jdbc.update(CatalogTestRunActivationProjectionSqlV1.insertNotices, *projectionArguments()) == 2)
        requireAt(Stage.WRITING_NOTICES)
        stage = Stage.WRITING_AUDITS
        val audits = ComplaintTestRunActivationAuditInsertionV1.insert(this)
        requireAt(Stage.WRITING_AUDITS)
        check(audits === projectionAudit && audits.completedFor(this) && projectionAuditOrdinal == 4)
        stage = Stage.WRITING_CATALOG
        requireCurrentLease()
        check(jdbc.update(CatalogTestRunActivationProjectionSqlV1.project, *projectionArguments()) == 1)
        requireAt(Stage.WRITING_CATALOG)
        check(jdbc.update(CatalogTestRunActivationProjectionSqlV1.clearPending,
            *projectionArguments(), *checkNotNull(input.lease).arguments()) == 1)
        requireAt(Stage.WRITING_CATALOG)
    }

    private fun lockProjectedDomainEffect() {
        requireAt(Stage.WRITING)
        check(jdbc.query(CatalogTestRunActivationProjectionSqlV1.lockRun,
            { row, _ -> row.getObject("data_scope_id", UUID::class.java) }, input.frozen.scope).single() == input.frozen.scope)
        requireAt(Stage.WRITING)
        val ids = input.frozen.projectionNoticeIds().toSet()
        check(jdbc.query(CatalogTestRunActivationProjectionSqlV1.lockResources,
            { row, _ -> row.getObject("id", UUID::class.java) }, input.frozen.scope).let { it.size == 2 && it.toSet() == ids })
        requireAt(Stage.WRITING)
        check(jdbc.query(CatalogTestRunActivationProjectionSqlV1.lockNotices,
            { row, _ -> row.getObject("id", UUID::class.java) }, input.frozen.scope).let { it.size == 2 && it.toSet() == ids })
        requireAt(Stage.WRITING)
        check(jdbc.query(CatalogTestRunActivationProjectionSqlV1.lockAudits,
            { row, _ -> row.requiredTestActivationLong("id") }, input.frozen.scope).let { it.size == 4 && it.all { id -> id > 0L } })
        requireAt(Stage.WRITING)
    }

    private fun withProjectionEffect(snapshot: CatalogTestRunActivationSnapshotV1, counters: CatalogTestRunActivationProjectionCountersV1): CatalogTestRunActivationSnapshotV1 {
        requireRetained()
        val projectedAt = checkNotNull(snapshot.completedTail).projectedAt
        val effect = projectedAt?.let { at ->
            jdbc.query(CatalogTestRunActivationProjectionSqlV1.readEffect, { row, _ ->
                check(row.requiredTestActivationBoolean("valid"))
                checkNotNull(row.getBytes("effect_fingerprint")).also { check(it.size == 32) }
            }, *input.frozen.projectionArguments(checkNotNull(input.signed), at)).single()
        }
        requireRetained()
        return snapshot.withProjection(CatalogTestRunActivationProjectionRowsV1(counters, projectedAt, effect, input.frozen))
    }

    private fun projectionArguments(): Array<Any?> = input.frozen.projectionArguments(checkNotNull(input.signed), checkNotNull(projectionAt))

    internal fun beginProjectionAudit(insertion: ComplaintTestRunActivationAuditInsertionV1): JdbcTemplate {
        requireAt(Stage.WRITING_AUDITS)
        check(input.projecting && input.kind === CatalogTestRunActivationKindV1.PROJECT && counters?.settledFor(this) == true &&
            projectionAt != null && projectionAudit == null && projectionAuditOrdinal == 0 && insertion.belongsTo(this))
        projectionAudit = insertion
        return jdbc
    }

    internal fun projectionAuditArguments(insertion: ComplaintTestRunActivationAuditInsertionV1, selected: JdbcTemplate, ordinal: Int): Array<Any?> {
        requireProjectionAudit(insertion, selected)
        check(ordinal in 0..3 && ordinal == projectionAuditOrdinal)
        projectionAuditOrdinal++ // One fixed paid stage, spent before dispatch; no second insertion after a thrown/unknown call.
        return projectionArguments()
    }

    internal fun requireProjectionAudit(insertion: ComplaintTestRunActivationAuditInsertionV1, selected: JdbcTemplate) {
        requireAt(Stage.WRITING_AUDITS)
        check(projectionAudit === insertion && selected === jdbc && insertion.belongsTo(this) && counters?.settledFor(this) == true)
    }

    private fun requireProjectionPreflight() {
        requireRetained()
        check(jdbc.query(CatalogTestRunActivationSqlV1.preflight, { row, _ -> row.requiredTestActivationBoolean("allowed") }, *input.frozen.preflightArguments()).single())
        requireRetained()
    }

    private fun readControl(lock: Boolean): CatalogTestRunActivationControlV1 {
        requireRetained()
        val sql = if (input.projecting) {
            if (lock) CatalogTestRunActivationSqlV1.lockProjectionControl else CatalogTestRunActivationSqlV1.readProjectionControl
        } else if (input.delivering) {
            if (lock) CatalogTestRunActivationSqlV1.lockDeliveryControl else CatalogTestRunActivationSqlV1.readDeliveryControl
        } else if (lock) CatalogTestRunActivationSqlV1.lockControl else CatalogTestRunActivationSqlV1.readControl
        val value = jdbc.query(
            sql,
            { row, _ -> CatalogTestRunActivationControlV1.copy(row) },
            *(if (input.delivering) checkNotNull(input.signed).deliveryControlArguments() else emptyArray<Any?>()),
        ).single()
        requireRetained()
        return value
    }

    private fun requireCurrentLease(lease: CatalogTestRunActivationLeaseV1 = checkNotNull(input.lease)) {
        requireRetained()
        check(jdbc.query(CatalogTestRunActivationSqlV1.currentLease, { row, _ -> row.requiredTestActivationBoolean("current") }, *lease.arguments()).single())
        requireRetained()
    }

    private fun readSnapshot(control: CatalogTestRunActivationControlV1, lock: Boolean): CatalogTestRunActivationSnapshotV1 {
        if (input.delivering) {
            val signed = checkNotNull(input.signed)
            requireRetained()
            val tail = jdbc.query(
                if (input.projecting) CatalogTestRunActivationSqlV1.readProjectionTail else CatalogTestRunActivationSqlV1.readDeliveryTail,
                { row, _ -> CatalogTestRunActivationDeliveryTailV1.copy(row, signed, projection = input.projecting) },
                *input.frozen.preparedArguments(), *signed.signatureArguments(), input.frozen.generation,
            ).single()
            requireRetained()
            return CatalogTestRunActivationSnapshotV1(control, readHistory(lock, signed = true, completed = tail.completed), tail.signed, tail.completed)
        }
        val tail = input.signed?.let { signed ->
            requireRetained()
            jdbc.query(
                CatalogTestRunActivationSqlV1.readPreparedTail, { row, _ -> CatalogTestRunActivationSignedTailV1.copy(row, signed) },
                *input.frozen.preparedArguments(), *signed.signatureArguments(), input.frozen.generation,
            ).single().also { requireRetained() }
        }
        return CatalogTestRunActivationSnapshotV1(control, readHistory(lock, tail != null), tail)
    }

    private fun expectedSignature(snapshot: CatalogTestRunActivationSnapshotV1): CatalogTestRunActivationSignedV1? =
        if (snapshot.signedTail == null) null else checkNotNull(input.signed)

    private fun readHistory(lock: Boolean, signed: Boolean, completed: CatalogTestRunActivationCompletedTailV1? = null): CatalogTestRunActivationHistoryV1 {
        requireRetained()
        check(jdbc.fetchSize == CatalogTestRunActivationHistoryV1.FETCH_ROWS)
        val sql = if (input.projecting) {
            check(completed != null && input.delivering && signed)
            if (lock) CatalogTestRunActivationSqlV1.lockProjectionHistory else CatalogTestRunActivationSqlV1.readProjectionHistory
        } else if (completed != null) {
            check(input.delivering && signed)
            if (lock) CatalogTestRunActivationSqlV1.lockCompletedHistory else CatalogTestRunActivationSqlV1.readCompletedHistory
        } else if (signed) {
            if (lock) CatalogTestRunActivationSqlV1.lockSignedHistory else CatalogTestRunActivationSqlV1.readSignedHistory
        } else {
            if (lock) CatalogTestRunActivationSqlV1.lockHistory else CatalogTestRunActivationSqlV1.readHistory
        }
        val arguments = input.frozen.preparedArguments()
            .plus(elements = if (signed) checkNotNull(input.signed).signatureArguments() else emptyArray<Any?>())
            .plus(elements = if (input.projecting) checkNotNull(completed).projectionArguments() else completed?.arguments() ?: emptyArray<Any?>())
            .plus(elements = arrayOf<Any?>(input.frozen.maximumGenerations + 1))
        val rows = checkNotNull(
            jdbc.query(
                sql,
                ResultSetExtractor { result ->
                    CatalogTestRunActivationHistoryV1.read(result, input.frozen, retainRaw = input.kind === CatalogTestRunActivationKindV1.SNAPSHOT,
                        signed = signed, completed = completed != null)
                },
                *arguments,
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

    internal fun requireProjectionCounterRead(locked: JdbcComplaintCapacityStore.LockedCatalogTestRunActivation, selected: JdbcTemplate) {
        requireRetained()
        check(input.projecting && counters === locked && selected === jdbc &&
            stage in setOf(Stage.COUNTERS_LOCKING, Stage.SETTLING_COUNTERS, Stage.REREADING))
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

    override fun toString(): String = "CatalogTestRunActivationOperationV1(original-TEST-atomic-effect,no-provider-or-admission-authority)"

    private enum class Stage {
        RETAINED, CONTROL_LOCKED, CATALOG_LOCKED, HISTORY_LOCKED, COUNTERS_LOCKING, SETTLING_COUNTERS, WRITING,
        WRITING_RUN, WRITING_CONTROL, WRITING_RESOURCES, WRITING_NOTICES, WRITING_AUDITS, WRITING_CATALOG, REREADING, COMPLETE, FAILED,
    }

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
