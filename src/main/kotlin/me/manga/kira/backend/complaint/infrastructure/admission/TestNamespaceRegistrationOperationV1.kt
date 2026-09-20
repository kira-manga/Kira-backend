package me.manga.kira.backend.complaint.infrastructure.admission

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationControlV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationDeliveryTailV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationHistoryV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationProjectionRowsV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationProjectionSqlV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationSnapshotV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationSqlV1
import me.manga.kira.backend.complaint.infrastructure.catalog.requiredTestActivationBoolean
import me.manga.kira.backend.complaint.infrastructure.catalog.TRY_CATALOG_LOCK
import me.manga.kira.backend.complaint.infrastructure.catalog.requiredTestActivationLong
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor
import java.util.UUID

/** Fixed read/lock-only fresh effect; never invokes PROJECT settlement, audit insertion or lease mutation. */
internal class TestNamespaceRegistrationOperationV1 private constructor(
    private val phase: PersistencePhaseContext,
    private val jdbc: JdbcTemplate,
    internal val original: ComplaintTestNamespaceRegistrationAttemptV1,
) {
    private val completion = original.completion
    private val frozen = completion.frozen
    private val signed = completion.signed
    private var historyLocked = false
    private var countersClaimed = false
    private var completed = false
    private var result: CatalogTestRunActivationSnapshotV1? = null

    internal fun belongsTo(selected: PersistencePhaseContext): Boolean = phase === selected
    internal fun completedFor(selected: PersistencePhaseContext): Boolean = belongsTo(selected) && completed
    internal fun requireReleased() { phase.testNamespaceRegistration.requireCommitted(this); requireConnectionFree() }
    internal val snapshot: CatalogTestRunActivationSnapshotV1 get() { requireReleased(); return checkNotNull(result) }

    private fun run() {
        requireRetained()
        val control = readControl(lock = true)
        control.requireSame(completion.snapshot.control, closed = false)
        requireRegistration(control.leaseToken == completion.snapshot.control.leaseToken)
        control.requireProjection(frozen, signed, projected = true)
        requireRegistration(jdbc.query(CatalogTestRunActivationProjectionSqlV1.lockScopeControl,
            { row, _ -> row.getObject("data_scope_id", UUID::class.java) }, frozen.scope).single() == frozen.scope)
        requireRetained()
        requireRegistration(jdbc.query(TRY_CATALOG_LOCK,
            { row, _ -> row.requiredTestActivationBoolean("locked") }).single())
        requireRetained()
        val tail = jdbc.query(CatalogTestRunActivationSqlV1.readProjectionTail,
            { row, _ -> CatalogTestRunActivationDeliveryTailV1.copy(row, signed, projection = true) },
            *frozen.preparedArguments(), *signed.signatureArguments(), frozen.generation).single()
        val current = checkNotNull(tail.completed)
        requireRetained()
        val history = checkNotNull(jdbc.query(CatalogTestRunActivationSqlV1.lockProjectionHistory,
            ResultSetExtractor { rows -> CatalogTestRunActivationHistoryV1.read(rows, frozen, retainRaw = false, signed = true, completed = true) },
            *frozen.preparedArguments(), *signed.signatureArguments(), *current.projectionArguments(), frozen.maximumGenerations + 1))
        requireRetained()
        val snapshot = CatalogTestRunActivationSnapshotV1(control, history, tail.signed, current)
        snapshot.requireProjection(frozen, signed)
        snapshot.requireSame(completion.snapshot, closed = false, compareProjection = false)
        historyLocked = true
        val counters = JdbcComplaintCapacityStore(jdbc, original.process.consumers.capacityPolicy.digestBytes()).lockForTestNamespaceRegistration(this)
        counters.requireSame(checkNotNull(completion.snapshot.projectionRows).counters)
        requireRetained()
        requireRegistration(jdbc.query(CatalogTestRunActivationProjectionSqlV1.lockRun,
            { row, _ -> row.getObject("data_scope_id", UUID::class.java) }, frozen.scope).single() == frozen.scope)
        val ids = frozen.projectionNoticeIds().toSet()
        for (sql in listOf(CatalogTestRunActivationProjectionSqlV1.lockResources, CatalogTestRunActivationProjectionSqlV1.lockNotices)) {
            requireRetained()
            requireRegistration(jdbc.query(sql, { row, _ -> row.getObject("id", UUID::class.java) }, frozen.scope).let { it.size == 2 && it.toSet() == ids })
        }
        requireRetained()
        requireRegistration(jdbc.query(CatalogTestRunActivationProjectionSqlV1.lockAudits,
            { row, _ -> row.requiredTestActivationLong("id") }, frozen.scope).let { it.size == 4 && it.all { id -> id > 0L } })
        requireRetained()
        val at = checkNotNull(current.projectedAt)
        val fingerprint = jdbc.query(CatalogTestRunActivationProjectionSqlV1.readEffect, { row, _ ->
            requireRegistration(row.requiredTestActivationBoolean("valid"))
            checkNotNull(row.getBytes("effect_fingerprint")).also { requireRegistration(it.size == 32) }
        }, *frozen.projectionArguments(signed, at)).single()
        requireRetained()
        val observed = snapshot.withProjection(CatalogTestRunActivationProjectionRowsV1(counters, at, fingerprint, frozen))
        observed.requireSame(completion.snapshot, closed = false)
        readControl(lock = false).requireSame(control, closed = false)
        requireRetained()
        result = observed
        completed = true
    }

    private fun readControl(lock: Boolean): CatalogTestRunActivationControlV1 {
        requireRetained()
        return jdbc.query(if (lock) CatalogTestRunActivationSqlV1.lockProjectionControl else CatalogTestRunActivationSqlV1.readProjectionControl,
            { row, _ -> CatalogTestRunActivationControlV1.copy(row) }, *signed.deliveryControlArguments()).single().also { requireRetained() }
    }

    internal fun beginCounterLock(selected: JdbcTemplate) {
        requireRetained()
        requireRegistration(selected === jdbc && historyLocked && !countersClaimed && !completed)
        countersClaimed = true
    }

    internal fun requireCounterRead(selected: JdbcTemplate) {
        requireRetained()
        requireRegistration(selected === jdbc && historyLocked && countersClaimed && !completed)
    }

    private fun requireRetained() = phase.testNamespaceRegistration.requireRetained(this, jdbc)
    override fun toString(): String = "TestNamespaceRegistrationOperationV1(original-runtime-holder,read-only-fresh-effect,redacted)"

    companion object {
        internal fun execute(jdbc: JdbcTemplate, original: ComplaintTestNamespaceRegistrationAttemptV1): TestNamespaceRegistrationOperationV1 {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            try {
                phase.testNamespaceRegistration.requireOperation(original, jdbc)
                val operation = TestNamespaceRegistrationOperationV1(phase, jdbc, original)
                phase.testNamespaceRegistration.retain(operation, jdbc)
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
