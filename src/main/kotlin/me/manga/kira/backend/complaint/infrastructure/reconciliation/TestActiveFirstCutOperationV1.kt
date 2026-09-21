package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.admission.TestNamespaceRecoveryRegistrationSqlV1
import me.manga.kira.backend.complaint.infrastructure.admission.TestNamespaceRecoveryRegistrationTailV1
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationHistoryV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationSqlV1
import me.manga.kira.backend.complaint.infrastructure.catalog.TRY_CATALOG_LOCK
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor
import java.util.UUID

/** Exact pooled READ/LEASE/REQUEST. All holders end before the separate NONPOOLED M/shared -> E/exclusive capture. */
internal class TestActiveFirstCutOperationV1 private constructor(
    private val phase: PersistencePhaseContext,
    private val jdbc: JdbcTemplate,
    internal val original: TestActiveFirstCutV1,
) {
    internal val path = original.path
    private val identity = original.identity
    private var controlsLocked = false
    private var historyLocked = false
    private var countersClaimed = false
    private var runLocked = false
    private var slotLocked = false
    private var counters: JdbcComplaintCapacityStore.LockedTestActiveFirstCut? = null
    private var completed = false
    private var observed: TestActiveFirstCutStateV1? = null
    private var capturedTail: TestNamespaceRecoveryRegistrationTailV1? = null
    private var capturedHistory: CatalogTestRunActivationHistoryV1? = null

    internal fun belongsTo(selected: PersistencePhaseContext): Boolean = phase === selected
    internal fun completedFor(selected: PersistencePhaseContext): Boolean = belongsTo(selected) && completed
    internal fun requireReleased() { phase.testActiveFirstCut.requireCommitted(this); requireConnectionFree() }
    internal fun releasedState(): TestActiveFirstCutStateV1 { requireReleased(); return checkNotNull(observed) }
    internal fun releasedTail(): TestNamespaceRecoveryRegistrationTailV1 { requireReleased(); return checkNotNull(capturedTail) }
    internal fun releasedHistory(): CatalogTestRunActivationHistoryV1 { requireReleased(); return checkNotNull(capturedHistory) }

    /** Pure original known-commit/cleanup check; safe while the following native holder exists. */
    internal fun requireSealedHandoff(): TestActiveFirstCutStateV1 {
        phase.testActiveFirstCut.requireCommitted(this)
        requireFirstCut(path === PersistencePhasePath.COMPLAINT_TEST_ACTIVE_FIRST_CUT_REQUEST)
        return checkNotNull(observed).also { requireFirstCut(it.state == "REQUESTED" && it.id == original.nonce) }
    }

    private fun run() {
        lockOne(TestActiveFirstCutSqlV1.lockGlobal, emptyArray(), UUID(0L, 0L))
        lockOne(TestActiveFirstCutSqlV1.lockScope, arrayOf(identity.scope), identity.scope)
        controlsLocked = true
        requireRetained()
        requireFirstCut(jdbc.query(TRY_CATALOG_LOCK, ResultSetExtractor { rows ->
            rows.next() && rows.getBoolean("locked") && !rows.wasNull() && !rows.next()
        }) == true)
        requireRetained()
        val history = checkNotNull(jdbc.query(CatalogTestRunActivationSqlV1.lockRecoveryRegistrationHistory, ResultSetExtractor { rows ->
            CatalogTestRunActivationHistoryV1.readActiveCurrent(rows, identity.generation, original.process.catalogReadback.chainPolicy.limits.maximumGenerations)
        }, *original.historyArguments()))
        requireRetained()
        // Shared pure tail comparator only; no closed SEALED binding/gate/recovery issuer is used.
        val tail = jdbc.query(TestNamespaceRecoveryRegistrationSqlV1.tail, { row, _ -> TestNamespaceRecoveryRegistrationTailV1(row, identity.scope) },
            *identity.tailArguments()).single()
        original.requireRawComparisons(tail, history)
        historyLocked = true
        if (path === PersistencePhasePath.COMPLAINT_TEST_ACTIVE_FIRST_CUT_REQUEST) {
            counters = JdbcComplaintCapacityStore(jdbc, original.process.consumers.capacityPolicy.digestBytes()).lockForTestActiveFirstCut(this)
        }
        lockOne(TestActiveFirstCutSqlV1.lockRun, arrayOf(identity.scope), identity.scope)
        runLocked = true
        requireRetained()
        val slots = jdbc.query(TestActiveFirstCutSqlV1.lockSlot, { row, _ -> row.getObject("operation_token", UUID::class.java) }, identity.scope)
        requireFirstCut(slots.isEmpty()) // An existing/spent/unknown-original row is never adopted by this first-only producer.
        slotLocked = true
        val before = read()
        before.requireNoSlot()
        original.requirePrior(before)
        val expected = when (path) {
            PersistencePhasePath.COMPLAINT_TEST_ACTIVE_FIRST_CUT_READ -> before
            PersistencePhasePath.COMPLAINT_TEST_ACTIVE_FIRST_CUT_LEASE -> acquire(before)
            PersistencePhasePath.COMPLAINT_TEST_ACTIVE_FIRST_CUT_REQUEST -> request(before)
            else -> throw TestActiveFirstCutExceptionV1()
        }
        val after = read()
        expected.requireSamePhysical(after)
        if (path === PersistencePhasePath.COMPLAINT_TEST_ACTIVE_FIRST_CUT_REQUEST) {
            after.requireCurrent(original.selectedLease())
            requireFirstCut(checkNotNull(counters).completedFor(this))
        }
        capturedTail = tail
        capturedHistory = history
        observed = after
        completed = true
    }

    private fun acquire(before: TestActiveFirstCutStateV1): TestActiveFirstCutStateV1 {
        requireRetained()
        requireFirstCut(before.leaseToken < Long.MAX_VALUE && (before.lease == null || !before.lease.expiresAt.isAfter(before.sampledAt)))
        requireFirstCut(jdbc.update(TestActiveFirstCutSqlV1.acquireLease, original.owner, identity.scope, before.controlFingerprint()) == 1)
        val after = read()
        before.requireSameGlobal(after)
        before.requireSameRun(after)
        after.requireNoSlot()
        val lease = checkNotNull(after.lease)
        requireFirstCut(after.scanRequested == before.scanRequested && lease.owner == original.owner &&
            lease.token == Math.addExact(before.leaseToken, 1L) && lease.expiresAt.isAfter(after.sampledAt))
        return after
    }

    private fun request(before: TestActiveFirstCutStateV1): TestActiveFirstCutStateV1 {
        requireRetained()
        val lease = original.selectedLease()
        before.requireCurrent(lease)
        checkNotNull(counters).charge(this) // Entire independent 2MiB price, ordinary free -> actual, before INSERT in the SAME transaction.
        requireRetained()
        requireFirstCut(jdbc.update(TestActiveFirstCutSqlV1.request, original.nonce, identity.scope, *lease.arguments(), before.controlFingerprint()) == 1)
        requireRetained()
        requireFirstCut(jdbc.update(TestActiveFirstCutSqlV1.insertSlot, *identity.arguments(), original.nonce) == 1)
        val after = read()
        before.requireSameGlobal(after)
        before.requireSameRun(after) // In particular, unused_reserve/enrollment/birth never change for this ordinary charge.
        after.requireCurrent(lease)
        requireFirstCut(after.epoch == 1L && after.sequence == 1L && after.id == original.nonce && after.state == "REQUESTED" && after.scanRequested &&
            after.epochBefore == 1L && after.requestOwner == lease.owner && after.requestToken == lease.token &&
            checkNotNull(after.requestedAt) >= before.sampledAt && after.captureOwner == null && after.captureToken == null && after.epochAfter == null)
        after.slotFingerprint()
        return after
    }

    private fun lockOne(sql: String, arguments: Array<Any?>, expected: UUID) {
        requireRetained()
        requireFirstCut(jdbc.query(sql, { row, _ -> row.getObject("data_scope_id", UUID::class.java) }, *arguments).single() == expected)
        requireRetained()
    }

    private fun read(): TestActiveFirstCutStateV1 {
        requireRetained()
        return jdbc.query(TestActiveFirstCutSqlV1.read, { row, _ -> TestActiveFirstCutStateV1.copy(row) }, *identity.arguments()).single().also { requireRetained() }
    }

    internal fun beginCounterLock(selected: JdbcTemplate) {
        requireRetained()
        requireFirstCut(selected === jdbc && path === PersistencePhasePath.COMPLAINT_TEST_ACTIVE_FIRST_CUT_REQUEST &&
            controlsLocked && historyLocked && !countersClaimed && !runLocked && !slotLocked && !completed)
        countersClaimed = true
    }
    internal fun requireCounterRead(selected: JdbcTemplate) {
        requireRetained()
        requireFirstCut(selected === jdbc && controlsLocked && historyLocked && countersClaimed && !runLocked && !completed)
    }
    internal fun requireCounterCharge(selected: JdbcComplaintCapacityStore.LockedTestActiveFirstCut, selectedJdbc: JdbcTemplate) {
        requireRetained()
        requireFirstCut(selectedJdbc === jdbc && counters === selected && countersClaimed && runLocked && slotLocked && !completed &&
            path === PersistencePhasePath.COMPLAINT_TEST_ACTIVE_FIRST_CUT_REQUEST)
    }
    internal fun failed(problem: Throwable): Nothing {
        original.observeFailure(problem)
        phase.recordFailure(problem)
        original.throwIfSignalled()
        throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
    }
    private fun requireRetained() = phase.testActiveFirstCut.requireRetained(this, jdbc)
    override fun toString(): String = "TestActiveFirstCutOperationV1(fixed-pooled-read-lease-paid-request,no-seal-or-content-authority)"

    companion object {
        internal fun execute(jdbc: JdbcTemplate, original: TestActiveFirstCutV1): TestActiveFirstCutOperationV1 {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            try {
                phase.testActiveFirstCut.requireOperation(original, jdbc)
                val operation = TestActiveFirstCutOperationV1(phase, jdbc, original)
                phase.testActiveFirstCut.retain(operation, jdbc)
                original.retain(operation)
                operation.run()
                return operation
            } catch (problem: Throwable) {
                original.observeFailure(problem)
                phase.recordFailure(problem)
                original.throwIfSignalled()
                throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
            }
        }
    }
}
