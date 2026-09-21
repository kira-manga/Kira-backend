package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.admission.TestNamespaceRecoveryRegistrationSqlV1
import me.manga.kira.backend.complaint.infrastructure.admission.TestNamespaceRecoveryRegistrationTailV1
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationHistoryV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationSqlV1
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor
import java.sql.Timestamp

/** One fixed current-control/observation phase. A detached row is never a successful phase result. */
internal class TestActiveOwnerDeleteQueueOperationV1 private constructor(
    private val phase: PersistencePhaseContext, private val jdbc: JdbcTemplate, internal val original: TestActiveOwnerDeleteQueueV1,
) {
    internal val step = original.step
    internal val path = original.path
    private var completed = false
    private var controlsLocked = false
    private var countersClaimed = false
    private var runLocked = false
    private var observationRead = false
    private var charge: Boolean? = null
    private var counters: JdbcComplaintCapacityStore.LockedTestActiveOwnerDeleteQueue? = null
    internal lateinit var current: TestActiveOwnerDeleteQueueRowsV1.Current
        private set
    internal lateinit var tail: TestNamespaceRecoveryRegistrationTailV1
        private set
    internal lateinit var history: CatalogTestRunActivationHistoryV1
        private set
    internal var observation: TestActiveOwnerDeleteQueueRowsV1.Observation? = null
        private set

    internal fun belongsTo(selected: PersistencePhaseContext) = phase === selected
    internal fun completedFor(selected: PersistencePhaseContext) = belongsTo(selected) && completed
    internal fun requireReleased() { requireCommittedComparison(); requireConnectionFree() }
    internal fun requireCommittedComparison() = phase.testActiveOwnerDeleteQueue.requireCommitted(this)
    private fun retained() { phase.testActiveOwnerDeleteQueue.requireRetained(this, jdbc); requireQueue(!completed) }

    private fun run() {
        retained(); lockControls(jdbc, original); controlsLocked = true
        val admission = readAdmission(jdbc, original); tail = admission.first; history = admission.second
        if (step === TestActiveOwnerDeleteQueueStepV1.ACQUIRE) counters = JdbcComplaintCapacityStore(jdbc, original.process.consumers.capacityPolicy.digestBytes())
            .lockForTestActiveOwnerDeleteQueue(this)
        requireQueue(jdbc.query(TestActiveOwnerDeleteQueueSqlV1.lockRun, { _, _ -> true }, original.scope).single()); runLocked = true
        observation = readObservation(jdbc, original); observationRead = true
        current = readCurrent(jdbc, original); original.requireState(current)
        observation?.let { requireQueue(it.token <= current.token && !it.startedAt.isAfter(current.sampledAt) && it.settledAt?.isAfter(current.sampledAt) != true) }
        when (step) {
            TestActiveOwnerDeleteQueueStepV1.READ -> Unit
            TestActiveOwnerDeleteQueueStepV1.ACQUIRE -> acquire()
            TestActiveOwnerDeleteQueueStepV1.RECHECK -> {
                current.requireLease(original); checkNotNull(observation).requirePolling(original); original.requireObservation(checkNotNull(observation))
            }
            TestActiveOwnerDeleteQueueStepV1.SETTLE -> settle()
            else -> throw TestActiveOwnerDeleteQueueExceptionV1()
        }
        retained()
        val after = readCurrent(jdbc, original); current.requireSame(after); original.requireState(after); current = after
        if (step === TestActiveOwnerDeleteQueueStepV1.SETTLE) {
            requireQueue(after.owner == null && after.expiresAt == null && after.token == original.leaseToken)
        } else if (step !== TestActiveOwnerDeleteQueueStepV1.READ) current.requireLease(original)
        if (step === TestActiveOwnerDeleteQueueStepV1.ACQUIRE) requireQueue(checkNotNull(counters).completedFor(this))
        retained(); completed = true
    }

    private fun acquire() {
        original.requireRawReleased()
        val before = current.token
        requireQueue(before < Long.MAX_VALUE && (current.owner == null || !checkNotNull(current.expiresAt).isAfter(current.sampledAt)))
        requireQueue(jdbc.query(TestActiveOwnerDeleteQueueSqlV1.acquire, { row, _ -> TestActiveOwnerDeleteQueueRowsV1.long(row, "lease_token") },
            original.attemptId, original.scope, before).single() == before + 1)
        val after = readCurrent(jdbc, original); current.requireSame(after); original.requireState(after)
        original.retainLease(this, before, after); current = after; current.requireLease(original)
        charge = observation == null
        checkNotNull(counters).settle(this)
        val started = Timestamp.from(current.sampledAt)
        val old = observation
        if (old == null) {
            val identity = original.observationIdentityArguments()
            try { requireQueue(jdbc.update(TestActiveOwnerDeleteQueueSqlV1.insertObservation, *identity, original.attemptId, original.leaseToken, started) == 1) }
            finally { identity.filterIsInstance<ByteArray>().forEach { it.fill(0) } }
        } else {
            requireQueue(old.token < original.leaseToken && !old.startedAt.isAfter(current.sampledAt))
            requireQueue(jdbc.update(TestActiveOwnerDeleteQueueSqlV1.restartObservation, original.attemptId, original.leaseToken, started,
                original.scope, old.token, original.leaseToken, started) == 1)
        }
        observation = checkNotNull(readObservation(jdbc, original))
        original.retainPolling(this, checkNotNull(observation)); checkNotNull(observation).requirePolling(original)
    }

    private fun settle() {
        current.requireLease(original); checkNotNull(observation).requirePolling(original); original.requireObservation(checkNotNull(observation))
        original.requireSettlementReady()
        val at = Timestamp.from(current.sampledAt); val started = Timestamp.from(original.pollingStartedAt)
        requireQueue(jdbc.update(TestActiveOwnerDeleteQueueSqlV1.settle, at, original.primaryAcked, original.dlqAcked,
            original.scope, original.attemptId, original.leaseToken, started, at) == 1)
        requireQueue(jdbc.update(TestActiveOwnerDeleteQueueSqlV1.relinquish, original.scope, original.attemptId, original.leaseToken,
            Timestamp.from(checkNotNull(original.leaseExpiresAt))) == 1)
        val value = checkNotNull(readObservation(jdbc, original))
        requireQueue(value.owner == original.attemptId && value.token == original.leaseToken && value.startedAt == original.pollingStartedAt &&
            value.state == "SETTLED" && value.settledAt == current.sampledAt && value.primaryAcked == original.primaryAcked.toLong() && value.dlqAcked == original.dlqAcked.toLong())
        observation = value // Historical bookkeeping, not proof of COMMIT, cleanup or whole-queue health.
    }

    internal fun beginCounterLock(selected: JdbcTemplate) {
        retained(); requireQueue(selected === jdbc && step === TestActiveOwnerDeleteQueueStepV1.ACQUIRE && controlsLocked && !countersClaimed && !runLocked && !observationRead)
        countersClaimed = true
    }
    internal fun requireCounterRead(selected: JdbcTemplate) {
        retained(); requireQueue(selected === jdbc && countersClaimed && controlsLocked && !runLocked && !observationRead)
    }
    internal fun chargeRequired(selected: JdbcComplaintCapacityStore.LockedTestActiveOwnerDeleteQueue, selectedJdbc: JdbcTemplate): Boolean {
        retained(); requireQueue(selectedJdbc === jdbc && selected === counters && countersClaimed && controlsLocked && runLocked && observationRead &&
            step === TestActiveOwnerDeleteQueueStepV1.ACQUIRE)
        current.requireLease(original)
        return checkNotNull(charge)
    }
    internal fun failed(problem: Throwable): Nothing {
        original.observeFailure(problem); phase.recordFailure(problem); original.throwIfSignalled()
        throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
    }
    override fun toString() = "ActiveQueueOperation(fixed,redacted,no-health-authority)"

    companion object {
        internal fun execute(jdbc: JdbcTemplate, original: TestActiveOwnerDeleteQueueV1): TestActiveOwnerDeleteQueueOperationV1 {
            val phase = checkNotNull(PersistencePhaseOwnership.current())
            phase.testActiveOwnerDeleteQueue.requireOperation(original, jdbc)
            val operation = TestActiveOwnerDeleteQueueOperationV1(phase, jdbc, original)
            phase.testActiveOwnerDeleteQueue.retain(operation, jdbc)
            operation.run(); return operation
        }
        private fun lockControls(jdbc: JdbcTemplate, original: TestActiveOwnerDeleteQueueV1) {
            requireQueue(jdbc.query(TestActiveOwnerDeleteQueueSqlV1.lockGlobal, { _, _ -> true }).single())
            requireQueue(jdbc.query(TestActiveOwnerDeleteQueueSqlV1.lockScope, { _, _ -> true }, original.scope).single())
        }
        private fun readCurrent(jdbc: JdbcTemplate, original: TestActiveOwnerDeleteQueueV1): TestActiveOwnerDeleteQueueRowsV1.Current {
            val args = original.identity.arguments()
            return try { jdbc.query(TestActiveOwnerDeleteQueueSqlV1.current, { row, _ -> TestActiveOwnerDeleteQueueRowsV1.Current(row) }, *args).single() }
            finally { args.filterIsInstance<ByteArray>().forEach { it.fill(0) } }
        }
        private fun readObservation(jdbc: JdbcTemplate, original: TestActiveOwnerDeleteQueueV1): TestActiveOwnerDeleteQueueRowsV1.Observation? {
            val args = original.identity.arguments()
            return try { jdbc.query(TestActiveOwnerDeleteQueueSqlV1.observation, { row, _ -> TestActiveOwnerDeleteQueueRowsV1.Observation(row) }, *args).singleOrNull() }
            finally { args.filterIsInstance<ByteArray>().forEach { it.fill(0) } }
        }
        private fun readAdmission(jdbc: JdbcTemplate, original: TestActiveOwnerDeleteQueueV1): Pair<TestNamespaceRecoveryRegistrationTailV1, CatalogTestRunActivationHistoryV1> {
            val args = original.identity.tailArguments()
            val tail = try { jdbc.query(TestNamespaceRecoveryRegistrationSqlV1.tail, { row, _ -> TestNamespaceRecoveryRegistrationTailV1(row, original.scope) }, *args).single() }
            finally { args.filterIsInstance<ByteArray>().forEach { it.fill(0) } }
            val historyArgs = original.historyArguments()
            val history = try { checkNotNull(jdbc.query(CatalogTestRunActivationSqlV1.lockRecoveryRegistrationHistory.removeSuffix("\nFOR UPDATE"),
                ResultSetExtractor { rows -> CatalogTestRunActivationHistoryV1.readActiveCurrent(rows, original.identity.generation,
                    original.process.catalogReadback.chainPolicy.limits.maximumGenerations) }, *historyArgs)) }
            finally { historyArgs.filterIsInstance<ByteArray>().forEach { it.fill(0) } }
            original.requireAdmissionComparisons(tail, history)
            return tail to history
        }

        /** Called only by the exact retained registered deletion APPLY boundary; no terminal/SEALED bypass. */
        internal fun lockRecoveryControls(jdbc: JdbcTemplate, original: TestActiveOwnerDeleteQueueV1) {
            original.requireRecoveryHolder(jdbc)
            lockControls(jdbc, original); readAdmission(jdbc, original)
            val current = readCurrent(jdbc, original); original.requireState(current); current.requireLease(original)
        }
        /** The ordinary APPLY counter lock precedes this run/observation lock and every erasure. */
        internal fun lockRecoveryRun(jdbc: JdbcTemplate, original: TestActiveOwnerDeleteQueueV1) {
            original.requireRecoveryHolder(jdbc)
            requireQueue(jdbc.query(TestActiveOwnerDeleteQueueSqlV1.lockRun, { _, _ -> true }, original.scope).single())
            original.requireObservation(checkNotNull(readObservation(jdbc, original)))
            requireRecoveryCurrent(jdbc, original)
        }
        internal fun requireRecoveryCurrent(jdbc: JdbcTemplate, original: TestActiveOwnerDeleteQueueV1) {
            original.requireRecoveryHolder(jdbc)
            val current = readCurrent(jdbc, original); original.requireState(current); current.requireLease(original)
        }
    }
}

internal enum class TestActiveOwnerDeleteQueueStepV1 { READ, ACQUIRE, PRINCIPAL, RECEIVE, JOURNAL, APPLY, RECHECK, ACK, SETTLE }
