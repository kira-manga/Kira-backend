package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.audit.infrastructure.ComplaintTestRunTerminalCatalogAuditInsertionV1
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityLedger
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.ComplaintDailyAdmission
import me.manga.kira.backend.complaint.infrastructure.OwnerDeleteRows
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunPurgeOperationV1
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

internal class JdbcCatalogTestRunTerminalStoreV1(private val jdbc: JdbcTemplate) {
    fun execute(input: CatalogTestRunTerminalPhaseV1, capacity: JdbcComplaintCapacityStore): CatalogTestRunTerminalOperationV1 =
        CatalogTestRunTerminalOperationV1.execute(jdbc, input, capacity)
}

/**
 * Actual original-retained catalog holder. M/RC -> shared E -> global -> exact scope -> catalog
 * -> validated name-order counters -> run. Only PREPARE and PROJECT lock/update that later run.
 * ACQUIRE/RELEASE are separate control-only transactions. No P/L or provider enters this holder.
 */
internal class CatalogTestRunTerminalOperationV1 private constructor(
    private val phase: PersistencePhaseContext,
    private val jdbc: JdbcTemplate,
    internal val input: CatalogTestRunTerminalPhaseV1,
) {
    private var stage = Stage.RETAINED
    private var before: CatalogTestRunTerminalSnapshotV1? = null
    private var observed: CatalogTestRunTerminalSnapshotV1? = null
    private var acquired: CatalogTestRunActivationLeaseV1? = null
    private var counters: JdbcComplaintCapacityStore.LockedCatalogTestRunTerminal? = null
    private var charge = ComplaintCapacityVector.ZERO
    private var projectedAt: Instant? = null
    private var audit: ComplaintTestRunTerminalCatalogAuditInsertionV1? = null
    private var auditDispatched = false
    internal val path get() = input.path
    internal fun belongsTo(selected: PersistencePhaseContext): Boolean = phase === selected
    internal fun completedFor(selected: PersistencePhaseContext): Boolean = phase === selected && stage === Stage.COMPLETE
    internal fun requireReleased() { phase.catalogTestRunTerminal.requireCommitted(this); requireConnectionFree() }
    internal fun snapshot(): CatalogTestRunTerminalSnapshotV1 { requireReleased(); return checkNotNull(observed) }
    internal fun lease(): CatalogTestRunActivationLeaseV1 { requireReleased(); return checkNotNull(acquired) }

    private fun run(capacity: JdbcComplaintCapacityStore) {
        at(Stage.RETAINED)
        stage = Stage.CONTROLS
        val global = readControl(GLOBAL, lock = true)
        val scoped = readControl(input.scope, lock = true)
        if (input.kind === CatalogTestRunTerminalKindV1.ACQUIRE || input.kind === CatalogTestRunTerminalKindV1.RELEASE) {
            val expected = checkNotNull(input.expected)
            global.requireSame(expected.global); scoped.requireSame(expected.scoped)
            expected.activeHistory.requirePhysical(jdbc, input.original)
            if (input.kind === CatalogTestRunTerminalKindV1.ACQUIRE) {
                acquired = jdbc.query(CatalogTestRunTerminalSqlV1.acquireLease, { row, _ -> CatalogTestRunActivationLeaseV1.copy(row) },
                    checkNotNull(input.leaseOwner)).single()
                requireTestTerminalCatalog(checkNotNull(acquired).owner == input.leaseOwner && checkNotNull(acquired).token > global.leaseToken)
                requireLease(checkNotNull(acquired))
            } else {
                requireLease()
                requireTestTerminalCatalog(jdbc.update(CatalogTestRunTerminalSqlV1.releaseLease, *checkNotNull(input.lease).arguments()) == 1)
            }
            at(Stage.CONTROLS)
            readControl(GLOBAL, lock = false).requireSame(global); readControl(input.scope, lock = false).requireSame(scoped)
            expected.activeHistory.requirePhysical(jdbc, input.original)
            stage = Stage.COMPLETE
            return
        }
        input.lease?.let(::requireLease)
        requireTestTerminalCatalog(jdbc.query(TRY_CATALOG_LOCK, { row, _ -> row.requiredTestActivationBoolean("locked") }).single())
        at(Stage.CONTROLS); stage = Stage.CATALOG
        val prefix = readPrefix(global, scoped, lock = true)
        stage = Stage.COUNTERS_REQUESTED
        val locked = capacity.lockForCatalogTestRunTerminal(this)
        at(Stage.COUNTERS_LOCKING); requireTestTerminalCatalog(locked.belongsTo(this)); counters = locked
        val facts = locked.before(this)
        stage = Stage.RUN
        val run = readRun(prefix.activation, lock = input.kind === CatalogTestRunTerminalKindV1.PREPARE || input.kind === CatalogTestRunTerminalKindV1.PROJECT)
        val selected = prefix.snapshot(run, facts, CatalogTestRunTerminalActiveHistoryV1.read(jdbc, input.original, prefix.activation))
        requireSnapshot(selected)
        input.expected?.requireSame(selected)
        before = selected
        requireCurrentTime()
        input.original.requireCurrentCatalogSnapshot(this, selected)
        charge = when (input.kind) {
            CatalogTestRunTerminalKindV1.PREPARE -> checkNotNull(input.frozen).prepareCharge.also { requireTestTerminalCatalog(selected.terminal == null) }
            CatalogTestRunTerminalKindV1.PROJECT -> if (selected.terminal?.projectedAt == null) checkNotNull(input.frozen).projectionCharge else ComplaintCapacityVector.ZERO
            else -> ComplaintCapacityVector.ZERO
        }
        if (input.kind === CatalogTestRunTerminalKindV1.PROJECT) requireTestTerminalCatalog(selected.terminal?.completed != null)
        stage = Stage.SETTLING
        locked.settle(this)
        at(Stage.SETTLING); requireTestTerminalCatalog(locked.settledFor(this))
        stage = Stage.WRITING
        when (input.kind) {
            CatalogTestRunTerminalKindV1.PREPARE -> prepare(selected)
            CatalogTestRunTerminalKindV1.SIGNATURE -> persistSignature(selected)
            CatalogTestRunTerminalKindV1.COMPLETE -> complete(selected)
            CatalogTestRunTerminalKindV1.PROJECT -> project(selected)
            CatalogTestRunTerminalKindV1.CAPTURE, CatalogTestRunTerminalKindV1.RELOAD -> Unit
            else -> throw CatalogTestRunTerminalExceptionV1()
        }
        at(Stage.WRITING); stage = Stage.REREADING
        // Rereads never reacquire an earlier lock class after the run.
        val repeatedPrefix = readPrefix(readControl(GLOBAL, lock = false), readControl(input.scope, lock = false), lock = false)
        val after = repeatedPrefix.snapshot(readRun(repeatedPrefix.activation, lock = false), locked.reread(this),
            CatalogTestRunTerminalActiveHistoryV1.read(jdbc, input.original, repeatedPrefix.activation))
        selected.requireCore(after); requireSnapshot(after)
        requireTransition(selected, after)
        requireCurrentTime()
        input.lease?.let(::requireLease)
        at(Stage.REREADING); observed = after; stage = Stage.COMPLETE
    }

    private fun prepare(snapshot: CatalogTestRunTerminalSnapshotV1) {
        input.original.requireCatalogDispatch(this); requireLease()
        val frozen = checkNotNull(input.frozen)
        requireTestTerminalCatalog(snapshot.terminal == null && snapshot.run.state == "SEALED")
        requireTestTerminalCatalog(jdbc.update(CatalogTestRunTerminalSqlV1.insertPrepared, *frozen.preparedArguments()) == 1)
        at(Stage.WRITING); requireLease()
        requireTestTerminalCatalog(jdbc.update(CatalogTestRunTerminalProjectionSqlV1.spendPrepared, *input.runArguments(snapshot.activation),
            OwnerDeleteRows.array(snapshot.run.unused - charge), OwnerDeleteRows.array(snapshot.run.unused), frozen.progressBytes(), frozen.sealSetBytes()) == 1)
    }
    private fun persistSignature(snapshot: CatalogTestRunTerminalSnapshotV1) {
        input.original.requireCatalogDispatch(this); requireLease()
        val row = checkNotNull(snapshot.terminal); val signed = checkNotNull(input.signed)
        if (row.signed) row.requireSigned(signed)
        else requireTestTerminalCatalog(jdbc.update(CatalogTestRunTerminalSqlV1.persistSignature,
            *signed.signatureArguments(), *checkNotNull(input.frozen).preparedArguments()) == 1)
    }
    private fun complete(snapshot: CatalogTestRunTerminalSnapshotV1) {
        input.original.requireCatalogDispatch(this); requireLease()
        val frozen = checkNotNull(input.frozen); val signed = checkNotNull(input.signed); val proof = checkNotNull(input.proof)
        val row = checkNotNull(snapshot.terminal)
        row.requireSigned(signed); requireTestTerminalCatalog(proof.state === CatalogTestRunTerminalDeliveryReadbackV1.State.DUAL_COPY)
        proof.requireSource(checkNotNull(input.expected), frozen, signed)
        if (row.completed != null) row.requireDual(proof)
        else {
            requireTestTerminalCatalog(jdbc.update(CatalogTestRunTerminalSqlV1.complete,
                *proof.completionArguments(), *frozen.preparedArguments(), *signed.signatureArguments()) == 1)
            at(Stage.WRITING); requireLease()
            requireTestTerminalCatalog(jdbc.update(CatalogTestRunTerminalSqlV1.markPending, frozen.generation - 1L,
                terminalCatalogHex(frozen.predecessorHash), frozen.generation, signed.envelopeHash(), frozen.token,
                *checkNotNull(input.lease).arguments()) == 1)
        }
    }
    private fun project(snapshot: CatalogTestRunTerminalSnapshotV1) {
        input.original.requireCatalogDispatch(this); requireLease()
        val frozen = checkNotNull(input.frozen); val signed = checkNotNull(input.signed); val row = checkNotNull(snapshot.terminal)
        val proof = checkNotNull(input.proof)
        row.requireSigned(signed); row.requireDual(proof)
        // The proof's row may have advanced only by this original's exact COMPLETE, never by replacing its bytes.
        input.original.requireProjectionProof(this, proof)
        val prior = row.projectedAt
        if (prior != null) {
            snapshot.run.requireProjected(frozen, signed, prior); requireAudit(snapshot, prior)
            return // No new time sample, audit, counter/run/catalog update, timestamp or xmin churn.
        }
        requireTestTerminalCatalog(snapshot.run.state == "SEALED")
        requireAuditAbsent(snapshot)
        projectedAt = jdbc.query(CatalogTestRunTerminalProjectionSqlV1.sampleTime,
            { value, _ -> checkNotNull(value.getTimestamp("sampled_at")).toInstant() }, Timestamp.from(checkNotNull(row.completed).completedAt)).single()
        val timestamp = Timestamp.from(checkNotNull(projectedAt)); val record = frozen.manifest().terminalRecord
        val purge = record.purge; val installations = record.installationManifest.summary
        requireTestTerminalCatalog(jdbc.update(CatalogTestRunTerminalProjectionSqlV1.projectRun, *input.runArguments(snapshot.activation),
            timestamp, OwnerDeleteRows.array(snapshot.run.unused - charge), purge.document.preTerminalInventory.count, terminalCatalogHex(purge.document.preTerminalInventory.sha256),
            installations.installationCount, terminalCatalogHex(installations.installationsSha256), installations.chunkCount, installations.retiredCount, installations.deletedCount,
            purge.document.eventId, purge.objectRef.objectKey, purge.objectRef.objectVersion, terminalCatalogHex(purge.objectRef.ciphertextSha256),
            frozen.generation, signed.envelopeHash(), OwnerDeleteRows.array(snapshot.run.unused), frozen.progressBytes(), frozen.sealSetBytes()) == 1)
        at(Stage.WRITING); stage = Stage.AUDIT
        val inserted = ComplaintTestRunTerminalCatalogAuditInsertionV1.insert(this)
        at(Stage.AUDIT); requireTestTerminalCatalog(inserted === audit && inserted.completedFor(this)); stage = Stage.WRITING
        requireLease()
        requireTestTerminalCatalog(jdbc.update(CatalogTestRunTerminalProjectionSqlV1.markProjected,
            timestamp, frozen.token, frozen.scope, frozen.generation, signed.envelopeHash(), timestamp) == 1)
        at(Stage.WRITING)
        requireTestTerminalCatalog(jdbc.update(CatalogTestRunTerminalProjectionSqlV1.clearPending,
            timestamp, frozen.token, frozen.generation, signed.envelopeHash(), *checkNotNull(input.lease).arguments()) == 1)
    }

    private fun requireSnapshot(snapshot: CatalogTestRunTerminalSnapshotV1) {
        snapshot.requireHead()
        snapshot.activeHistory.requireRun(snapshot.run)
        requireTestTerminalCatalog(snapshot.activation.scope == input.scope && snapshot.activation.generation == snapshot.scoped.head.generation)
        val terminal = snapshot.terminal
        requireTestTerminalCatalog(terminal == null || terminal.operation == "TEST_RUN_TERMINAL" && terminal.token == input.token &&
            terminal.scope == input.scope && terminal.generation == snapshot.activation.generation + 1L)
        snapshot.run.requirePayment(terminal != null, terminal?.projectedAt != null)
        input.frozen?.let {
            requireTestTerminalCatalog(it.generation == snapshot.activation.generation + 1L && it.predecessorHash == snapshot.scoped.head.envelopeSha256)
            snapshot.run.requireFrozen(it); snapshot.activeHistory.requireFrozen(it); terminal?.requireFrozen(it)
        }
        if (terminal?.signed == true && input.signed != null) terminal.requireSigned(input.signed)
        if (terminal?.projectedAt != null && input.frozen != null && input.signed != null) {
            val time = checkNotNull(terminal.projectedAt)
            snapshot.run.requireProjected(input.frozen, input.signed, time)
            requireAudit(snapshot, time)
        } else if (terminal?.projectedAt == null) requireAuditAbsent(snapshot)
    }
    private fun requireTransition(prior: CatalogTestRunTerminalSnapshotV1, after: CatalogTestRunTerminalSnapshotV1) {
        requireTestTerminalCatalog(after.run.unused == prior.run.unused - charge)
        when (input.kind) {
            CatalogTestRunTerminalKindV1.PREPARE -> {
                checkNotNull(after.terminal).requireFrozen(checkNotNull(input.frozen))
                requireTestTerminalCatalog(!after.terminal.signed && after.terminal.completed == null)
                after.global.requireSame(prior.global)
            }
            CatalogTestRunTerminalKindV1.SIGNATURE -> {
                checkNotNull(after.terminal).requireSigned(checkNotNull(input.signed)); after.global.requireSame(prior.global)
                after.terminal.requireSignatureTransition(checkNotNull(prior.terminal))
                after.run.requireSame(prior.run)
            }
            CatalogTestRunTerminalKindV1.COMPLETE -> {
                checkNotNull(after.terminal).requireSigned(checkNotNull(input.signed)); after.terminal.requireDual(checkNotNull(input.proof))
                after.terminal.requireCompletionTransition(checkNotNull(prior.terminal))
                after.run.requireSame(prior.run)
            }
            CatalogTestRunTerminalKindV1.PROJECT -> {
                val time = checkNotNull(after.terminal?.projectedAt)
                requireTestTerminalCatalog(time == (prior.terminal?.projectedAt ?: projectedAt))
                after.run.requireProjected(checkNotNull(input.frozen), checkNotNull(input.signed), time); requireAudit(after, time)
                checkNotNull(after.terminal).requireSigned(checkNotNull(input.signed)); after.terminal.requireDual(checkNotNull(input.proof))
                after.terminal.requireProjectionTransition(checkNotNull(prior.terminal))
                if (prior.terminal?.projectedAt != null) after.requireSame(prior)
            }
            else -> after.requireSame(prior)
        }
    }

    private class Prefix(val global: CatalogTestRunTerminalControlV1, val scoped: CatalogTestRunTerminalControlV1,
        val activation: CatalogTestRunTerminalMutationV1, val history: CatalogTestRunActivationHistoryV1, val terminal: CatalogTestRunTerminalMutationV1?) {
        fun snapshot(run: CatalogTestRunTerminalRunV1, counters: CatalogTestRunActivationProjectionCountersV1,
            activeHistory: CatalogTestRunTerminalActiveHistoryV1) =
            CatalogTestRunTerminalSnapshotV1(global, scoped, activation, history, terminal, run, counters, activeHistory)
    }
    private fun readPrefix(global: CatalogTestRunTerminalControlV1, scoped: CatalogTestRunTerminalControlV1, lock: Boolean): Prefix {
        retained()
        val activation = jdbc.query(if (lock) CatalogTestRunTerminalSqlV1.lockActivation else CatalogTestRunTerminalSqlV1.readActivation,
            { row, _ -> CatalogTestRunTerminalMutationV1(row) }, scoped.head.generation).single()
        requireTestTerminalCatalog(activation.operation == "TEST_RUN_ACTIVATION" && activation.head == scoped.head && activation.scope == input.scope)
        val history = checkNotNull(jdbc.query(if (lock) CatalogTestRunTerminalSqlV1.lockActivationHistory else CatalogTestRunTerminalSqlV1.readActivationHistory,
            ResultSetExtractor { rows -> CatalogTestRunActivationHistoryV1.readRecoveryRegistration(rows, activation.generation, input.maximumGenerations) },
            *input.historyArguments(activation)))
        history.requireExpected(activation.generation, prepared = true, signed = true, completed = true)
        retained()
        val suffix = jdbc.query(if (lock) CatalogTestRunTerminalSqlV1.lockSuffix else CatalogTestRunTerminalSqlV1.readSuffix,
            { row, _ -> CatalogTestRunTerminalMutationV1(row) }, activation.generation)
        requireTestTerminalCatalog(suffix.size <= 1)
        retained(); return Prefix(global, scoped, activation, history, suffix.singleOrNull())
    }
    private fun readRun(activation: CatalogTestRunTerminalMutationV1, lock: Boolean): CatalogTestRunTerminalRunV1 {
        retained()
        return jdbc.query(if (lock) CatalogTestRunTerminalProjectionSqlV1.lockRun else CatalogTestRunTerminalProjectionSqlV1.readRun,
            { row, _ -> CatalogTestRunTerminalRunV1(row, input.maximumVersions) }, *input.runArguments(activation)).single().also { retained() }
    }
    private fun readControl(scope: UUID, lock: Boolean): CatalogTestRunTerminalControlV1 {
        retained()
        return jdbc.query(if (lock) CatalogTestRunTerminalSqlV1.lockControl else CatalogTestRunTerminalSqlV1.readControl,
            { row, _ -> CatalogTestRunTerminalControlV1(row) }, *input.controlArguments(scope)).single().also { retained() }
    }
    private fun requireLease(lease: CatalogTestRunActivationLeaseV1 = checkNotNull(input.lease)) {
        retained(); requireTestTerminalCatalog(jdbc.query(CatalogTestRunTerminalSqlV1.currentLease,
            { row, _ -> row.requiredTestActivationBoolean("current") }, *lease.arguments()).single()); retained()
    }
    private fun requireCurrentTime() {
        retained()
        val at = jdbc.query("SELECT clock_timestamp() AS at", { row, _ -> checkNotNull(row.getTimestamp("at")).toInstant() }).single()
        input.original.requireCatalogSqlTime(this, at); retained()
    }
    private fun auditArguments(snapshot: CatalogTestRunTerminalSnapshotV1, time: Instant): Array<Any?> = arrayOf(input.scope, input.token,
        snapshot.activation.generation + 1L, snapshot.terminal?.head?.envelopeSha256 ?: "", Timestamp.from(time))
    private fun requireAuditAbsent(snapshot: CatalogTestRunTerminalSnapshotV1) {
        retained(); requireTestTerminalCatalog(jdbc.query(CatalogTestRunTerminalProjectionSqlV1.readAudit,
            { row, _ -> row.requiredTestActivationBoolean("valid") }, *auditArguments(snapshot, Instant.EPOCH)).isEmpty()); retained()
    }
    private fun requireAudit(snapshot: CatalogTestRunTerminalSnapshotV1, time: Instant) {
        retained(); requireTestTerminalCatalog(jdbc.query(CatalogTestRunTerminalProjectionSqlV1.readAudit,
            { row, _ -> row.requiredTestActivationBoolean("valid") }, *auditArguments(snapshot, time)).single()); retained()
    }
    internal fun beginProjectionAudit(value: ComplaintTestRunTerminalCatalogAuditInsertionV1): JdbcTemplate {
        at(Stage.AUDIT); requireTestTerminalCatalog(value.belongsTo(this) && audit == null && !auditDispatched && counters?.settledFor(this) == true)
        audit = value; return jdbc
    }
    internal fun projectionAuditArguments(value: ComplaintTestRunTerminalCatalogAuditInsertionV1, selected: JdbcTemplate): Array<Any?> {
        requireProjectionAudit(value, selected); requireTestTerminalCatalog(!auditDispatched); auditDispatched = true
        return auditArguments(checkNotNull(before), checkNotNull(projectedAt))
    }
    internal fun requireProjectionAudit(value: ComplaintTestRunTerminalCatalogAuditInsertionV1, selected: JdbcTemplate) {
        at(Stage.AUDIT); requireTestTerminalCatalog(audit === value && selected === jdbc && value.belongsTo(this))
    }
    internal fun beginCounterLock(selected: JdbcTemplate) {
        at(Stage.COUNTERS_REQUESTED); requireTestTerminalCatalog(selected === jdbc); stage = Stage.COUNTERS_LOCKING
    }
    internal fun requireCounterRead(value: JdbcComplaintCapacityStore.LockedCatalogTestRunTerminal, selected: JdbcTemplate) {
        retained(); requireTestTerminalCatalog(counters === value && selected === jdbc && stage in setOf(Stage.COUNTERS_LOCKING, Stage.SETTLING, Stage.REREADING))
    }
    internal fun requireCounterTransfer(value: JdbcComplaintCapacityStore.LockedCatalogTestRunTerminal, selected: JdbcTemplate) {
        at(Stage.SETTLING); requireTestTerminalCatalog(counters === value && selected === jdbc)
    }
    internal fun settleLockedLedger(selected: JdbcTemplate, ledger: ComplaintCapacityLedger, daily: ComplaintDailyAdmission, digest: ByteArray): ComplaintCapacityLedger {
        at(Stage.SETTLING); requireTestTerminalCatalog(selected === jdbc)
        val snapshot = checkNotNull(before); val policy = input.process.consumers.capacityPolicy
        ledger.configuration.requireMatching(digest)
        requireTestTerminalCatalog(digest.contentEquals(policy.digestBytes()) && ledger.balance.hardLimit == policy.hardLimit &&
            ledger.balance.creationLimit == policy.creationLimit && daily.dailyLimit == policy.dailyEnrollmentLimit &&
            ledger.balance.actual[ComplaintCapacityCounter.CATALOG_MUTATIONS] == snapshot.activation.generation + (if (snapshot.terminal == null) 0L else 1L) &&
            ledger.balance.actual[ComplaintCapacityCounter.TEST_RUNS] == 1L && snapshot.run.unused.fitsWithin(ledger.balance.testReserved) && charge.fitsWithin(snapshot.run.unused) &&
            (snapshot.run.reserve - snapshot.run.unused - TestRunPurgeOperationV1.FUTURE + snapshot.activeHistory.ordinaryPaid).fitsWithin(ledger.balance.actual) &&
            TestRunPurgeOperationV1.FUTURE.fitsWithin(ledger.balance.recoveryReserved))
        return if (charge.isZero()) ledger else ledger.spendTestReserve(digest, charge, ComplaintCapacityVector.ZERO)
    }
    internal fun failed(problem: Throwable): Nothing {
        stage = Stage.FAILED; input.original.observeFailure(problem); phase.recordFailure(problem)
        input.original.throwIfSignalled(); throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
    }
    private fun retained() = phase.catalogTestRunTerminal.requireRetained(this, jdbc)
    private fun at(expected: Stage) { retained(); requireTestTerminalCatalog(stage === expected) }
    private enum class Stage { RETAINED, CONTROLS, CATALOG, COUNTERS_REQUESTED, COUNTERS_LOCKING, RUN, SETTLING, WRITING, AUDIT, REREADING, COMPLETE, FAILED }
    override fun toString(): String = "CatalogTestRunTerminalOperationV1(original-holder,SEALED-to-PURGING-only,no-native-authority)"
    companion object {
        private val GLOBAL = UUID(0L, 0L)
        internal fun execute(jdbc: JdbcTemplate, input: CatalogTestRunTerminalPhaseV1, capacity: JdbcComplaintCapacityStore): CatalogTestRunTerminalOperationV1 {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            try {
                phase.catalogTestRunTerminal.requireOperation(input, jdbc)
                val operation = CatalogTestRunTerminalOperationV1(phase, jdbc, input)
                phase.catalogTestRunTerminal.retain(operation, jdbc); operation.run(capacity); return operation
            } catch (problem: Throwable) {
                input.original.observeFailure(problem); phase.recordFailure(problem); input.original.throwIfSignalled()
                throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
            }
        }
    }
}
