package me.manga.kira.backend.complaint.infrastructure.terminal

import me.manga.kira.backend.audit.infrastructure.ComplaintTestRunSealedAuditInsertionV1
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintCapacityEncoding
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityLedger
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.ComplaintDailyAdmission
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalCapacityChargesV1
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant

/** One fixed operation on the retained holder. Neither observed rows nor an audit ID can construct it. */
internal class TestRunSealingOperationV1 private constructor(
    private val phase: PersistencePhaseContext,
    private val jdbc: JdbcTemplate,
    internal val original: TestRunSealingV1,
) {
    internal val path = original.path
    private var stage = Stage.NEW
    private var run: Run? = null
    private var sealedAt: Instant? = null
    private var counters: JdbcComplaintCapacityStore.LockedTestRunSealedAudit? = null
    private var existingAudit = false
    private var insertion: ComplaintTestRunSealedAuditInsertionV1? = null
    private var auditDispatched = false

    internal fun belongsTo(selected: PersistencePhaseContext): Boolean = phase === selected
    internal fun completedFor(selected: PersistencePhaseContext): Boolean = belongsTo(selected) && stage === Stage.COMPLETE
    internal fun releasedSealedAt(): Instant {
        phase.testRunSealing.requireCommitted(this)
        requireConnectionFree()
        return checkNotNull(sealedAt)
    }

    private fun execute() {
        requireAt(Stage.NEW)
        if (path === PersistencePhasePath.COMPLAINT_TEST_RUN_SEAL) {
            stage = Stage.RUN
            val current = lockRun()
            // Exact SEALED replay never rewrites the original barrier timestamp or touches an audit/counter.
            sealedAt = if (current.state == "SEALED") checkNotNull(current.sealedAt) else {
                jdbc.query(TestRunSealingSqlV1.sealRun, { row, _ -> checkNotNull(row.getTimestamp("sealed_at")).toInstant() },
                    *original.registration.sealingRunArguments()).single().also { requireAt(Stage.RUN) }
            }
            stage = Stage.COMPLETE
            return
        }

        stage = Stage.CONTROLS
        for (sql in listOf(TestRunSealingSqlV1.lockGlobalControl, TestRunSealingSqlV1.lockScopeControl)) {
            requireSealing(jdbc.query(sql, { row, _ -> requiredBoolean(row, "valid") },
                *original.registration.sealingControlArguments()).single())
            requireAt(Stage.CONTROLS)
        }
        stage = Stage.COUNTERS_REQUESTED
        counters = JdbcComplaintCapacityStore(jdbc, original.registration.process.consumers.capacityPolicy.digestBytes()).lockForTestRunSealedAudit(this)
        requireAt(Stage.COUNTERS_LOCKING)
        stage = Stage.RUN
        val current = lockRun()
        requireSealing(current.state == "SEALED" && current.sealedAt == original.priorSealedAt())
        sealedAt = current.sealedAt
        stage = Stage.AUDIT_CHECK
        val audits = jdbc.query(TestRunSealingSqlV1.lockAudit, { row, _ -> requiredBoolean(row, "valid") },
            *original.registration.sealingAuditArguments(checkNotNull(sealedAt)))
        requireAt(Stage.AUDIT_CHECK)
        requireSealing(audits.size <= 1 && audits.all { it }) // Bounded second row is a duplicate sentinel, never LIMIT1 promotion.
        existingAudit = audits.isNotEmpty()
        // First terminal audit only. Ordinary enrollment's audit is charged separately, not from this prepaid component.
        val auditRows = ComplaintCapacityCounter.AUDIT_ROWS
        val paidAuditRows = if (existingAudit) TestTerminalCapacityChargesV1.AUDIT[auditRows] else 0L
        requireSealing(current.unused[auditRows] == current.original[auditRows] - paidAuditRows)
        if (existingAudit) requireSealing(TestTerminalCapacityChargesV1.AUDIT.fitsWithin(current.original - current.unused))
        else requireSealing(TestTerminalCapacityChargesV1.AUDIT.fitsWithin(current.unused))

        stage = Stage.TRANSFER
        checkNotNull(counters).settle(this)
        requireAt(Stage.TRANSFER)
        requireSealing(checkNotNull(counters).completedFor(this))
        if (!existingAudit) {
            stage = Stage.RUN_SPEND
            requireSealing(jdbc.update(TestRunSealingSqlV1.spendRun, *original.registration.sealingRunArguments(),
                (current.unused - TestTerminalCapacityChargesV1.AUDIT).sqlArray(), Timestamp.from(checkNotNull(sealedAt)), current.unused.sqlArray()) == 1)
            requireAt(Stage.RUN_SPEND)
            stage = Stage.AUDIT_WRITE
            val written = ComplaintTestRunSealedAuditInsertionV1.insert(this)
            requireAt(Stage.AUDIT_WRITE)
            requireSealing(insertion === written && written.completedFor(this))
        }
        stage = Stage.COMPLETE
    }

    private fun lockRun(): Run {
        requireAt(Stage.RUN)
        val value = jdbc.query(TestRunSealingSqlV1.lockRun, { row, _ ->
            requireSealing(requiredBoolean(row, "valid"))
            Run(checkNotNull(row.getString("state")), row.getTimestamp("sealed_at")?.toInstant(),
                vector(row, "original_reserve"), vector(row, "unused_reserve"))
        }, *original.registration.sealingRunArguments()).single()
        requireAt(Stage.RUN)
        run = value
        return value
    }

    internal fun beginCounterLock(selected: JdbcTemplate) {
        requireAt(Stage.COUNTERS_REQUESTED, selected)
        requireSealing(path === PersistencePhasePath.COMPLAINT_TEST_RUN_SEALED_AUDIT && counters == null)
        stage = Stage.COUNTERS_LOCKING
    }

    internal fun requireCounterTransfer(locked: JdbcComplaintCapacityStore.LockedTestRunSealedAudit, selected: JdbcTemplate) {
        requireAt(Stage.TRANSFER, selected)
        requireSealing(counters === locked)
    }

    internal fun settleLockedLedger(selected: JdbcTemplate, ledger: ComplaintCapacityLedger, daily: ComplaintDailyAdmission, expectedDigest: ByteArray): ComplaintCapacityLedger {
        requireAt(Stage.TRANSFER, selected)
        val current = checkNotNull(run)
        ledger.configuration.requireMatching(expectedDigest)
        val policy = original.registration.process.consumers.capacityPolicy
        requireSealing(ledger.balance.hardLimit == policy.hardLimit && ledger.balance.creationLimit == policy.creationLimit && daily.dailyLimit == policy.dailyEnrollmentLimit)
        requireSealing(current.unused.fitsWithin(ledger.balance.testReserved))
        // The locked run's remainder, not an initial global snapshot, pays this one same-transaction audit.
        return if (existingAudit) ledger else ledger.spendTestReserve(expectedDigest, TestTerminalCapacityChargesV1.AUDIT, ComplaintCapacityVector.ZERO)
    }

    internal fun beginSealAudit(selected: ComplaintTestRunSealedAuditInsertionV1): JdbcTemplate {
        requireAt(Stage.AUDIT_WRITE)
        requireSealing(!existingAudit && insertion == null && selected.belongsTo(this) && checkNotNull(counters).completedFor(this))
        insertion = selected // Retain before even argument construction or the first JDBC dispatch.
        return jdbc
    }

    internal fun sealAuditArguments(selected: ComplaintTestRunSealedAuditInsertionV1, template: JdbcTemplate): Array<Any?> {
        requireAt(Stage.AUDIT_WRITE, template)
        requireSealing(insertion === selected && !auditDispatched)
        auditDispatched = true
        return original.registration.sealingAuditArguments(checkNotNull(sealedAt))
    }

    internal fun requireSealAudit(selected: ComplaintTestRunSealedAuditInsertionV1, template: JdbcTemplate) {
        requireAt(Stage.AUDIT_WRITE, template)
        requireSealing(insertion === selected && auditDispatched)
    }

    internal fun failed(problem: Throwable): Nothing {
        original.observeFailure(problem)
        phase.recordFailure(problem)
        PersistencePhaseOwnership.current()?.takeIf { it !== phase }?.recordFailure(problem)
        original.throwIfSignalled()
        throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
    }

    private fun requireAt(expected: Stage, selected: JdbcTemplate = jdbc) {
        phase.testRunSealing.requireRetained(this, selected)
        requireSealing(selected === jdbc && stage === expected)
    }

    override fun toString(): String = "TestRunSealingOperationV1(original-holder,barrier-or-paid-audit,redacted)"
    private class Run(val state: String, val sealedAt: Instant?, val original: ComplaintCapacityVector, val unused: ComplaintCapacityVector)
    private enum class Stage { NEW, CONTROLS, COUNTERS_REQUESTED, COUNTERS_LOCKING, RUN, AUDIT_CHECK, TRANSFER, RUN_SPEND, AUDIT_WRITE, COMPLETE }

    companion object {
        internal fun execute(jdbc: JdbcTemplate, original: TestRunSealingV1): TestRunSealingOperationV1 {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            try {
                phase.testRunSealing.requireOperation(original, jdbc)
                val operation = TestRunSealingOperationV1(phase, jdbc, original)
                phase.testRunSealing.retain(operation, jdbc)
                operation.execute()
                return operation
            } catch (problem: Throwable) {
                original.observeFailure(problem)
                phase.recordFailure(problem)
                original.throwIfSignalled()
                throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
            }
        }

        private fun requiredBoolean(row: ResultSet, column: String): Boolean = row.getBoolean(column).also { requireSealing(!row.wasNull()) }

        private fun vector(row: ResultSet, column: String): ComplaintCapacityVector {
            // SQL checked dimensions, lower bound, all22 nonnegative non-NULL int64 values before returning either array.
            val array = checkNotNull(row.getArray(column))
            try {
                requireSealing(array.baseType == Types.BIGINT)
                val values = array.array as? Array<*> ?: throw TestRunSealingExceptionV1()
                requireSealing(values.size == ComplaintCapacityEncoding.WIDTH)
                return ComplaintCapacityVector.of(LongArray(values.size) { checkNotNull(values[it] as? Long) })
            } finally { array.free() }
        }

        private fun ComplaintCapacityVector.sqlArray(): String = toLongArray().joinToString(",", "{", "}")
    }
}
