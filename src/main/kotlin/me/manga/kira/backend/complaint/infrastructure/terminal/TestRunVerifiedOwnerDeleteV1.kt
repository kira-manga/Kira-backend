package me.manga.kira.backend.complaint.infrastructure.terminal

import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceComplaintMaintenanceGateV1
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceJdbcParticipantRole
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteReceipt
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeleteApplyOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeletePhaseOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeleteVerifiedReloadOperation
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteApplyStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteVerificationStore
import me.manga.kira.backend.complaint.infrastructure.TestOwnerDeleteApplyInputV1
import me.manga.kira.backend.complaint.infrastructure.TestOwnerDeleteLocalGraphV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationV1
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor
import java.io.InterruptedIOException
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference

/**
 * One original caller and one existing primary receipt. Scope is registration-owned; actor/key are
 * only SQL locators, never work/proof authority. A positively released VERIFIED reload precedes the
 * existing exact APPLY. No provider, authorization, PREPARED recovery, drain or reserve-release right.
 */
internal class TestRunVerifiedOwnerDeleteV1 private constructor(
    private val registration: ComplaintTestNamespaceRegistrationV1,
    private val ownership: PersistencePhaseOwnership,
    private val jdbc: JdbcTemplate,
    audit: AuditService,
    internal val actorId: UUID,
    internal val operationKey: UUID,
) {
    private val caller = Thread.currentThread()
    internal val budget = PersistenceTimeBudget.start(registration.process.catalogReadback.totalAttemptMillis, ownership.nanoClock)
    private val failure = AtomicReference<Throwable?>()
    private var started = false
    private var finished = false
    private var stage = Stage.NEW
    private var phase: PersistencePhaseContext? = null
    private var phaseEntered = false
    private var cleanupUncertain = false
    private var controlsChecked = false
    private var retainedOperation: ComplaintOwnerDeletePhaseOperation? = null
    private var sealedAt: Instant? = null
    private var applyInput: TestOwnerDeleteApplyInputV1? = null

    init {
        registration.requireUsable()
        registration.requireVerifiedOwnerDeleteResources(ownership, jdbc)
        requireContinuation(listOf(actorId, operationKey).all { it.version() == 4 && it.variant() == 2 })
    }

    private val process = registration.process
    private val graph = TestOwnerDeleteLocalGraphV1(JdbcTemplate(process.pools.ordinary), jdbc,
        process.consumers.ingressAdmission, process.consumers.journalRouting, process.consumers.capacityPolicy,
        process.publicationLanes, process.desiredGeneration, registration)
    private val capacity = JdbcComplaintCapacityStore(jdbc, process.consumers.capacityPolicy.digestBytes())
    // Canonical restoration is static. This store has no codec/provider and cannot authorize anything.
    private val store = JdbcComplaintOwnerDeleteStore(jdbc, capacity, audit, graph, codec = null)
    private val verification = JdbcComplaintOwnerDeleteVerificationStore(jdbc, graph, store)
    private val apply = JdbcComplaintOwnerDeleteApplyStore(jdbc, capacity, audit, graph, store, verification)

    fun complete(): ComplaintOwnerDeleteReceipt {
        requireContinuation(caller === Thread.currentThread())
        throwIfSignalled()
        requireContinuation(!started)
        started = true
        stage = Stage.RELOAD
        try {
            requireConnectionFree()
            requireRunning()
            val reloaded = reload()
            val work = store.releasedVerified(reloaded)
            requireContinuation(phase == null && !cleanupUncertain && sealedAt != null)
            val proof = verification.resume(work)
            applyInput = apply.capture(work, proof)
            stage = Stage.APPLY
            val completed = applyExisting()
            val result = completed.result
            requireConnectionFree()
            requireRunning()
            requireContinuation(phase == null && !cleanupUncertain && result === ComplaintOwnerDeleteReceipt.Applied)
            return result
        } catch (problem: Throwable) {
            observeFailure(problem)
            throwIfSignalled()
            throw TestRunVerifiedOwnerDeleteExceptionV1()
        } finally {
            finished = true
        }
    }

    private fun reload(): ComplaintOwnerDeleteVerifiedReloadOperation {
        val selected = ownership.enterTestRunVerifiedOwnerDeleteReload(this)
        var operation: ComplaintOwnerDeleteVerifiedReloadOperation? = null
        try { selected.begin(); operation = store.reloadVerified(this); selected.commit() }
        catch (problem: Throwable) { selected.recordFailure(problem) }
        finally { try { selected.finish() } finally { observePhaseCleanup(selected) } }
        return operation ?: throw selected.failureException(PersistencePhaseFailureCode.WORK_FAILED)
    }

    private fun applyExisting(): ComplaintOwnerDeleteApplyOperation {
        val selected = ownership.enterTestRunVerifiedOwnerDeleteApply(this)
        var operation: ComplaintOwnerDeleteApplyOperation? = null
        try { selected.begin(); operation = apply.apply(checkNotNull(applyInput)); selected.commit() }
        catch (problem: Throwable) { selected.recordFailure(problem) }
        finally { try { selected.finish() } finally { observePhaseCleanup(selected) } }
        return operation ?: throw selected.failureException(PersistencePhaseFailureCode.WORK_FAILED)
    }

    private val path: PersistencePhasePath get() = when (stage) {
        Stage.RELOAD -> PersistencePhasePath.COMPLAINT_OWNER_DELETE_RELOAD
        Stage.APPLY -> PersistencePhasePath.COMPLAINT_OWNER_DELETE_APPLY
        Stage.NEW -> throw TestRunVerifiedOwnerDeleteExceptionV1()
    }

    internal fun requirePersistence(selected: PersistencePhaseOwnership, selectedJdbc: JdbcTemplate, selectedGraph: TestOwnerDeleteLocalGraphV1) {
        requireRunning()
        requireContinuation(selected === ownership && selectedJdbc === jdbc && selectedGraph === graph)
        registration.requireVerifiedOwnerDeleteResources(selected, selectedJdbc)
        graph.requireDeletion(selectedJdbc)
    }

    internal fun requirePhaseEntry(selected: PersistencePhaseOwnership, selectedPath: PersistencePhasePath) {
        requireConnectionFree()
        requireRunning()
        requireContinuation(selected === ownership && selectedPath === path && !phaseEntered && phase == null &&
            (stage === Stage.RELOAD && applyInput == null && sealedAt == null || stage === Stage.APPLY && applyInput != null && sealedAt != null))
        phaseEntered = true
    }

    internal fun retainPhase(selected: PersistencePhaseContext) {
        requireRunning()
        requireContinuation(phaseEntered && phase == null)
        phase = selected
    }

    internal fun observePhaseCleanup(selected: PersistencePhaseContext) {
        try {
            if (caller !== Thread.currentThread() || phase !== selected || !selected.testVerifiedOwnerDeleteCleanupProven(this) ||
                selected.databaseOutcome() === PersistenceDatabaseOutcome.UNKNOWN) cleanupUncertain = true
            else { phase = null; phaseEntered = false; controlsChecked = false; retainedOperation = null }
        } catch (problem: Throwable) { cleanupUncertain = true; observeFailure(problem) }
    }

    internal fun requireMaintenanceGate(selected: PersistencePhaseOwnership, selectedPath: PersistencePhasePath, gate: PersistenceComplaintMaintenanceGateV1) {
        requireRunning()
        requireContinuation(selected === ownership && selectedPath === path && phaseEntered && phase != null)
        registration.requireVerifiedOwnerDeleteGate(gate)
    }

    internal fun authenticateAndControls(selected: PersistencePhaseOwnership, selectedJdbc: JdbcTemplate, operation: ComplaintOwnerDeletePhaseOperation) {
        requirePersistence(selected, selectedJdbc, graph)
        requireContinuation(retainedOperation == null && !controlsChecked && phase != null)
        when (stage) {
            Stage.RELOAD -> requireContinuation(operation is ComplaintOwnerDeleteVerifiedReloadOperation && operation.original === this)
            Stage.APPLY -> (operation as? ComplaintOwnerDeleteApplyOperation ?: throw TestRunVerifiedOwnerDeleteExceptionV1()).requireVerifiedContinuation(this)
            Stage.NEW -> throw TestRunVerifiedOwnerDeleteExceptionV1()
        }
        retainedOperation = operation
        val openings = process.pools.descriptors().single { it.role === PersistenceJdbcParticipantRole.DELETION }.openings().map { it.publicDriverProperties() }
        val user = openings.map { it["user"] }.distinct().single()
        val database = openings.map { it["PGDBNAME"] }.distinct().single()
        requireContinuation(jdbc.query("SELECT session_user = ? AND current_user = ? AND current_database() = ? AS authenticated",
            ResultSetExtractor { rows -> rows.next() && rows.getBoolean("authenticated") && !rows.wasNull() && !rows.next() }, user, user, database) == true)
        for (sql in listOf(TestRunSealingSqlV1.lockGlobalControl, TestRunSealingSqlV1.lockScopeControl)) {
            requireContinuation(jdbc.query(sql, { row, _ -> requiredBoolean(row, "valid") }, *registration.sealingControlArguments()).single())
            requirePersistence(selected, selectedJdbc, graph)
        }
        controlsChecked = true
    }

    /** Called by the existing control binding AFTER receipt/publication/reservation/counters, before domain locks. */
    internal fun requireSealedRun(selectedJdbc: JdbcTemplate) {
        requirePersistence(ownership, selectedJdbc, graph)
        requireContinuation(controlsChecked && retainedOperation != null)
        val at = jdbc.query(TestRunSealingSqlV1.lockRun, { row, _ ->
            requireContinuation(requiredBoolean(row, "valid") && row.getString("state") == "SEALED")
            checkNotNull(row.getTimestamp("sealed_at")).toInstant()
        }, *registration.sealingRunArguments()).single()
        if (stage === Stage.RELOAD) { requireContinuation(sealedAt == null); sealedAt = at } else requireContinuation(sealedAt == at)
        // Read only: taking an audit lock before APPLY's domain locks would invert the existing lock order.
        requireContinuation(jdbc.query(TestRunSealingSqlV1.readAudit, { row, _ -> requiredBoolean(row, "valid") },
            *registration.sealingAuditArguments(at)).single())
        requireRunning()
    }

    internal fun requireEarlierAuthorization(at: Instant) {
        requireRunning()
        requireContinuation(stage === Stage.RELOAD && !at.isAfter(checkNotNull(sealedAt)))
    }

    internal fun requireReleasedReload() {
        requireConnectionFree()
        requireRunning() // Sticky original failure/cancellation and uncertain cleanup veto even private work issuance.
        requireContinuation(stage === Stage.RELOAD && phase == null && !phaseEntered && !controlsChecked &&
            retainedOperation == null && sealedAt != null && applyInput == null)
    }

    internal fun requireApplyInput(input: TestOwnerDeleteApplyInputV1) {
        requireRunning()
        requireContinuation(stage === Stage.APPLY && applyInput === input)
    }

    private fun requireRunning() {
        throwIfSignalled()
        if (Thread.currentThread().isInterrupted) throw InterruptedException("TEST verified continuation interrupted.")
        requireContinuation(caller === Thread.currentThread() && !finished && !cleanupUncertain)
        budget.remainingMillis(1)
        registration.requireVerifiedOwnerDeleteResources(ownership, jdbc)
    }

    internal fun observeFailure(problem: Throwable) {
        val retained = when {
            problem is Error -> problem
            problem is CancellationException -> CancellationException("TEST verified continuation cancelled.")
            problem is InterruptedException || problem is InterruptedIOException ||
                (problem is PersistencePhaseException && problem.code === PersistencePhaseFailureCode.INTERRUPTED) -> InterruptedException("TEST verified continuation interrupted.")
            else -> TestRunVerifiedOwnerDeleteExceptionV1()
        }
        while (true) {
            val previous = failure.get()
            if (previous is Error || (previous is CancellationException && retained !is Error) ||
                (previous is InterruptedException && retained !is Error && retained !is CancellationException) ||
                (previous != null && retained is TestRunVerifiedOwnerDeleteExceptionV1)) return
            if (failure.compareAndSet(previous, retained)) return
        }
    }

    internal fun throwIfSignalled() {
        failure.get()?.let { if (it is InterruptedException) Thread.currentThread().interrupt(); throw it }
    }

    override fun toString(): String = "TestRunVerifiedOwnerDeleteV1(same-registration,existing-primary-only,redacted)"
    private enum class Stage { NEW, RELOAD, APPLY }

    companion object {
        fun begin(registration: ComplaintTestNamespaceRegistrationV1, ownership: PersistencePhaseOwnership, jdbc: JdbcTemplate,
            audit: AuditService, actorId: UUID, operationKey: UUID): TestRunVerifiedOwnerDeleteV1 =
            TestRunVerifiedOwnerDeleteV1(registration, ownership, jdbc, audit, actorId, operationKey)
    }
}

internal class TestRunVerifiedOwnerDeleteExceptionV1 : RuntimeException("TEST verified continuation refused.", null, false, false)
private fun requireContinuation(allowed: Boolean) { if (!allowed) throw TestRunVerifiedOwnerDeleteExceptionV1() }
private fun requiredBoolean(row: ResultSet, column: String): Boolean = row.getBoolean(column).also { requireContinuation(!row.wasNull()) }
