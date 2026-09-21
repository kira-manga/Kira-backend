package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.admission.TestNamespaceRecoveryRegistrationSqlV1
import me.manga.kira.backend.complaint.infrastructure.admission.TestNamespaceRecoveryRegistrationTailV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationHistoryV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationSqlV1
import me.manga.kira.backend.complaint.infrastructure.catalog.TRY_CATALOG_LOCK
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor
import java.util.UUID

/** Fixed pooled READ/LEASE/captured-RELEASE. No new request/charge; all holders end before native exclusive E. */
internal class TestActiveFirstCutSuccessorOperationV1 private constructor(
    private val phase: PersistencePhaseContext,
    private val jdbc: JdbcTemplate,
    internal val original: TestActiveFirstCutSuccessorV1,
) {
    internal val path = original.path
    private val identity = original.identity
    private var completed = false
    private var observed: TestActiveFirstCutStateV1? = null
    private var capturedTail: TestNamespaceRecoveryRegistrationTailV1? = null
    private var capturedHistory: CatalogTestRunActivationHistoryV1? = null
    private var accounting: TestActiveFirstCutSuccessorAccountingV1? = null

    internal fun belongsTo(selected: PersistencePhaseContext): Boolean = phase === selected
    internal fun completedFor(selected: PersistencePhaseContext): Boolean = belongsTo(selected) && completed
    internal fun requireReleased() { phase.testActiveFirstCutSuccessor.requireCommitted(this); requireConnectionFree() }
    internal fun releasedState(): TestActiveFirstCutStateV1 { requireReleased(); return checkNotNull(observed) }
    internal fun releasedTail(): TestNamespaceRecoveryRegistrationTailV1 { requireReleased(); return checkNotNull(capturedTail) }
    internal fun releasedHistory(): CatalogTestRunActivationHistoryV1 { requireReleased(); return checkNotNull(capturedHistory) }
    internal fun releasedAccounting(): TestActiveFirstCutSuccessorAccountingV1 { requireReleased(); return checkNotNull(accounting) }

    /** Pure original known-commit/cleanup check; safe while the following native holder exists. */
    internal fun requireCaptureHandoff(): TestActiveFirstCutStateV1 {
        phase.testActiveFirstCutSuccessor.requireCommitted(this)
        requireFirstCutSuccessor(path === PersistencePhasePath.COMPLAINT_TEST_ACTIVE_FIRST_CUT_SUCCESSOR_LEASE)
        return checkNotNull(observed).also {
            requireFirstCutReserved(it)
            requireFirstCutSuccessor(it.state == "REQUESTED" && it.id == original.selectedSlot())
            it.requireCurrent(original.selectedLease())
        }
    }

    private fun run() {
        lockOne(TestActiveFirstCutSuccessorSqlV1.lockGlobal, emptyArray(), UUID(0L, 0L))
        lockOne(TestActiveFirstCutSuccessorSqlV1.lockScope, arrayOf(identity.scope), identity.scope)
        requireRetained()
        requireFirstCutSuccessor(jdbc.query(TRY_CATALOG_LOCK, ResultSetExtractor { rows ->
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
        // Exactly P's full bounded22 catalogue is locked before the run. This owner has no counter writer.
        val counters = checkNotNull(jdbc.query(TestActiveFirstCutSuccessorSqlV1.lockCounters, ResultSetExtractor { rows ->
            TestActiveFirstCutSuccessorAccountingV1.readCounters(rows, original.process.consumers.capacityPolicy)
        }))
        requireRetained()
        lockOne(TestActiveFirstCutSuccessorSqlV1.lockRun, arrayOf(identity.scope), identity.scope)
        requireRetained()
        val slots = jdbc.query(TestActiveFirstCutSuccessorSqlV1.lockSlot, { row, _ -> row.getObject("operation_token", UUID::class.java) }, identity.scope)
        requireFirstCutSuccessor(slots.size == 1) // Discovery only: only the fixed state machine may continue this paid RESERVED slot.
        val before = read()
        requireFirstCutReserved(before)
        requireFirstCutSuccessor(before.id == slots.single())
        original.requirePrior(before)
        val beforeAccounting = readAccounting(counters)
        original.requireAccounting(beforeAccounting)
        val expected = when (path) {
            PersistencePhasePath.COMPLAINT_TEST_ACTIVE_FIRST_CUT_SUCCESSOR_READ -> before
            PersistencePhasePath.COMPLAINT_TEST_ACTIVE_FIRST_CUT_SUCCESSOR_LEASE -> acquire(before)
            PersistencePhasePath.COMPLAINT_TEST_ACTIVE_FIRST_CUT_SUCCESSOR_RELEASE -> release(before)
            else -> throw TestActiveFirstCutSuccessorExceptionV1()
        }
        val after = read()
        expected.requireSamePhysical(after)
        requireFirstCutReserved(after)
        val afterCounters = checkNotNull(jdbc.query(TestActiveFirstCutSuccessorSqlV1.readCounters, ResultSetExtractor { rows ->
            TestActiveFirstCutSuccessorAccountingV1.readCounters(rows, original.process.consumers.capacityPolicy)
        }))
        val afterAccounting = readAccounting(afterCounters)
        beforeAccounting.requireSame(afterAccounting) // No accounting/identity/reserve row, including xmin, may change.
        capturedTail = tail
        capturedHistory = history
        observed = after
        accounting = afterAccounting
        completed = true
    }

    private fun acquire(before: TestActiveFirstCutStateV1): TestActiveFirstCutStateV1 {
        requireRetained()
        requireFirstCutSuccessor(before.leaseToken < Long.MAX_VALUE && (before.lease == null || !before.lease.expiresAt.isAfter(before.sampledAt)))
        requireFirstCutSuccessor(jdbc.update(TestActiveFirstCutSuccessorSqlV1.acquireLease, original.owner, identity.scope, before.controlFingerprint()) == 1)
        val after = read()
        requireFirstCutReserved(after)
        requireSameFirstCutReserved(before, after)
        val lease = checkNotNull(after.lease)
        requireFirstCutSuccessor(lease.owner == original.owner && lease.token == Math.addExact(before.leaseToken, 1L) &&
            lease.token > checkNotNull(before.requestToken) && (before.captureToken == null || lease.token > before.captureToken) &&
            lease.expiresAt.isAfter(after.sampledAt))
        return after
    }

    private fun release(before: TestActiveFirstCutStateV1): TestActiveFirstCutStateV1 {
        requireRetained()
        requireFirstCutSuccessor(before.state == "CAPTURED")
        val lease = original.selectedLease()
        before.requireCurrent(lease)
        requireFirstCutSuccessor(lease.owner == original.owner && lease.token > checkNotNull(before.requestToken) &&
            lease.token > checkNotNull(before.captureToken))
        requireFirstCutSuccessor(jdbc.update(TestActiveFirstCutSuccessorSqlV1.releaseCapturedLease,
            identity.scope, checkNotNull(before.id), *lease.arguments(), before.controlFingerprint()) == 1)
        requireRetained()
        val after = read()
        requireFirstCutReserved(after)
        requireSameFirstCutReserved(before, after)
        requireFirstCutSuccessor(after.lease == null && after.leaseToken == lease.token)
        return after
    }

    private fun readAccounting(counters: me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationProjectionCountersV1): TestActiveFirstCutSuccessorAccountingV1 {
        requireRetained()
        val run = jdbc.query(TestActiveFirstCutSuccessorSqlV1.runAccounting, { row, _ ->
            TestActiveFirstCutSuccessorRunV1(row, identity, original.process.consumers.journalConfiguration.declaration().limits.capacity.maximumRetainedVersions)
        }, identity.scope).single()
        requireRetained()
        val counts = jdbc.query(TestActiveFirstCutSuccessorSqlV1.installationCounts, { row, _ ->
            val ids = row.getLong("ids").also { requireFirstCutSuccessor(!row.wasNull()) }
            val credentials = row.getLong("credentials").also { requireFirstCutSuccessor(!row.wasNull()) }
            ids to credentials
        }, identity.scope, identity.scope).single()
        requireRetained()
        return TestActiveFirstCutSuccessorAccountingV1.bind(counters, run, counts.first, counts.second)
    }

    private fun lockOne(sql: String, arguments: Array<Any?>, expected: UUID) {
        requireRetained()
        requireFirstCutSuccessor(jdbc.query(sql, { row, _ -> row.getObject("data_scope_id", UUID::class.java) }, *arguments).single() == expected)
        requireRetained()
    }

    private fun read(): TestActiveFirstCutStateV1 {
        requireRetained()
        return jdbc.query(TestActiveFirstCutSuccessorSqlV1.read, { row, _ -> TestActiveFirstCutStateV1.copy(row) }, *identity.arguments()).single().also { requireRetained() }
    }

    internal fun failed(problem: Throwable): Nothing {
        original.observeFailure(problem)
        phase.recordFailure(problem)
        original.throwIfSignalled()
        throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
    }
    private fun requireRetained() = phase.testActiveFirstCutSuccessor.requireRetained(this, jdbc)
    override fun toString(): String = "TestActiveFirstCutSuccessorOperationV1(fixed-pooled-read-lease-release,no-request-charge-or-content-authority)"

    companion object {
        internal fun execute(jdbc: JdbcTemplate, original: TestActiveFirstCutSuccessorV1): TestActiveFirstCutSuccessorOperationV1 {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            try {
                phase.testActiveFirstCutSuccessor.requireOperation(original, jdbc)
                val operation = TestActiveFirstCutSuccessorOperationV1(phase, jdbc, original)
                phase.testActiveFirstCutSuccessor.retain(operation, jdbc)
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
