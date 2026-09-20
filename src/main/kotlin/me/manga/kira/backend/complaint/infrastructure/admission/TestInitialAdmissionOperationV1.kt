package me.manga.kira.backend.complaint.infrastructure.admission

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationControlV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationHistoryV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationProjectionSqlV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationSqlV1
import me.manga.kira.backend.complaint.infrastructure.catalog.TRY_CATALOG_LOCK
import me.manga.kira.backend.complaint.infrastructure.catalog.requiredTestActivationBoolean
import me.manga.kira.backend.complaint.infrastructure.catalog.requiredTestActivationLong
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor
import java.util.UUID

/** Fixed original CAPTURE/RELEASE holder. No PROJECT, lease, counter, run, scan or checkpoint writer. */
internal class TestInitialAdmissionOperationV1 private constructor(
    private val phase: PersistencePhaseContext,
    private val jdbc: JdbcTemplate,
    internal val original: ComplaintTestInitialAdmissionV1,
) {
    internal val path = original.path
    private val facts = original.facts
    private var historyLocked = false
    private var countersClaimed = false
    private var completed = false
    private var observedControls: TestInitialAdmissionControlsV1? = null
    private var observedHistory: CatalogTestRunActivationHistoryV1? = null

    internal fun belongsTo(selected: PersistencePhaseContext): Boolean = phase === selected
    internal fun completedFor(selected: PersistencePhaseContext): Boolean = belongsTo(selected) && completed
    internal fun requireReleased() { phase.testInitialAdmission.requireCommitted(this); requireConnectionFree() }
    internal val controls: TestInitialAdmissionControlsV1 get() { requireReleased(); return checkNotNull(observedControls) }
    internal val history: CatalogTestRunActivationHistoryV1 get() { requireReleased(); return checkNotNull(observedHistory) }

    private fun run() {
        requireRetained()
        val global = readGlobal(lock = true)
        facts.requireControl(global)
        requireRegistration(jdbc.query(CatalogTestRunActivationProjectionSqlV1.lockScopeControl,
            { row, _ -> row.getObject("data_scope_id", UUID::class.java) }, facts.scope).single() == facts.scope)
        requireRetained()
        val controls = readControls()
        requireRegistration(jdbc.query(TRY_CATALOG_LOCK, { row, _ -> row.requiredTestActivationBoolean("locked") }).single())
        requireRetained()
        val history = checkNotNull(jdbc.query(CatalogTestRunActivationSqlV1.lockProjectionHistory, ResultSetExtractor { rows ->
            CatalogTestRunActivationHistoryV1.readInitialAdmission(rows, facts.generation, facts.maximumGenerations)
        }, *facts.historyArguments()))
        history.requireSame(facts.history)
        requireRetained()
        historyLocked = true
        val counters = JdbcComplaintCapacityStore(jdbc, original.process.consumers.capacityPolicy.digestBytes()).lockForTestInitialAdmission(this)
        counters.requireSame(facts.projection.counters)
        requireRetained()
        requireRegistration(jdbc.query(CatalogTestRunActivationProjectionSqlV1.lockRun,
            { row, _ -> row.getObject("data_scope_id", UUID::class.java) }, facts.scope).single() == facts.scope)
        for (sql in listOf(CatalogTestRunActivationProjectionSqlV1.lockResources, CatalogTestRunActivationProjectionSqlV1.lockNotices)) {
            requireRetained()
            requireRegistration(jdbc.query(sql, { row, _ -> row.getObject("id", UUID::class.java) }, facts.scope)
                .let { it.size == 2 && it.toSet() == facts.noticeIds })
        }
        requireRetained()
        requireRegistration(jdbc.query(CatalogTestRunActivationProjectionSqlV1.lockAudits, { row, _ -> row.requiredTestActivationLong("id") }, facts.scope)
            .let { it.size == 4 && it.all { id -> id > 0L } })
        requireRetained()
        val fingerprint = jdbc.query(CatalogTestRunActivationProjectionSqlV1.readEffect, { row, _ ->
            requireRegistration(row.requiredTestActivationBoolean("valid"))
            checkNotNull(row.getBytes("effect_fingerprint")).also { requireRegistration(it.size == 32) }
        }, *facts.effectArguments()).single()
        facts.projection.requireInitialAdmission(counters, facts.projectedAt, fingerprint)
        readGlobal(lock = false).requireSame(global, closed = false)
        readControls().requireSame(controls) // Already-held controls only; never a backward lock acquisition.
        original.requireCapturedControls(controls)
        requireRetained()
        if (path === PersistencePhasePath.COMPLAINT_TEST_INITIAL_ADMISSION_RELEASE) {
            // This phase acquired M-exclusive directly, before E/global/scope/catalog/counters/run.
            val opened = jdbc.query(TestInitialAdmissionSqlV1.release, { row, _ -> row.getObject("data_scope_id", UUID::class.java) },
                *controls.releaseArguments(facts.scope))
            requireRegistration(opened.size == 2 && opened.toSet() == setOf(UUID(0L, 0L), facts.scope))
            requireRetained()
        }
        observedControls = controls
        observedHistory = history
        completed = true
    }

    private fun readGlobal(lock: Boolean): CatalogTestRunActivationControlV1 {
        requireRetained()
        return jdbc.query(if (lock) CatalogTestRunActivationSqlV1.lockProjectionControl else CatalogTestRunActivationSqlV1.readProjectionControl,
            { row, _ -> CatalogTestRunActivationControlV1.copy(row) }, *facts.controlArguments()).single().also { requireRetained() }
    }

    private fun readControls(): TestInitialAdmissionControlsV1 {
        requireRetained()
        val rows = jdbc.query(TestInitialAdmissionSqlV1.controls, { row, _ -> TestInitialAdmissionControlV1(row) }, facts.scope)
        requireRetained()
        return TestInitialAdmissionControlsV1(rows, facts.scope)
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
    private fun requireRetained() = phase.testInitialAdmission.requireRetained(this, jdbc)
    override fun toString(): String = "TestInitialAdmissionOperationV1(original-holder,initial-identity-only,redacted)"

    companion object {
        internal fun execute(jdbc: JdbcTemplate, original: ComplaintTestInitialAdmissionV1): TestInitialAdmissionOperationV1 {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            try {
                phase.testInitialAdmission.requireOperation(original, jdbc)
                val operation = TestInitialAdmissionOperationV1(phase, jdbc, original)
                phase.testInitialAdmission.retain(operation, jdbc)
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
