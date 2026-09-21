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
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteReceipt
import me.manga.kira.backend.complaint.infrastructure.CommittedTestAdminDeleteVerificationV1
import me.manga.kira.backend.complaint.infrastructure.CommittedTestAdminDeleteWork
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminDeleteApplyOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminDeleteRegisteredReloadOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminDeleteVerificationOperation
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintAdminDeleteApplyStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintAdminDeleteStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintAdminDeleteVerificationStore
import me.manga.kira.backend.complaint.infrastructure.TestAdminDeleteApplyInputV1
import me.manga.kira.backend.complaint.infrastructure.TestOwnerDeleteLocalGraphV1
import me.manga.kira.backend.complaint.infrastructure.TestAdminDeleteVerificationInputV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationV1
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.infrastructure.journal.TestAdminDeleteJournalPublisherFactoryV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestOwnerDeleteJournalReadbackV1
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor
import java.io.InterruptedIOException
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference

/**
 * One original caller for one existing primary. Scope is registration-owned; actor/key remain
 * locators, never work/proof authority. The primary requires its own released RELOAD and uses the
 * SAME outer budget and live custody through native publication, VERIFY and APPLY.
 * No new authorization, user/grant/credential creation, ordinary-range drain proof or reserve-release right.
 * The existing registered primary is paid and retains its original actor/grant/resolved owner.
 */
internal class TestRunAdminDeleteContinuationV1 internal constructor(
    private val registration: ComplaintTestNamespaceRegistrationV1,
    private val ownership: PersistencePhaseOwnership,
    private val jdbc: JdbcTemplate,
    private val audit: AuditService,
    actor: UUID,
    key: UUID,
    private val publication: TestRunPreparedAdminDeleteV1.Publication,
    private val drainBy: TestRunOrdinaryDrainV1? = null,
) {
    private val caller = Thread.currentThread()
    internal val budget: PersistenceTimeBudget = drainBy?.budget ?: PersistenceTimeBudget.start(registration.process.catalogReadback.totalAttemptMillis, ownership.nanoClock)
    private val locator = actor to key
    internal val actorId: UUID get() = locator.first
    internal val operationKey: UUID get() = locator.second
    private val failure = AtomicReference<Throwable?>()
    private var started = false
    private var finished = false
    private var successful = false
    private var stage = Stage.NEW
    private var phase: PersistencePhaseContext? = null
    private var phaseEntered = false
    private var cleanupUncertain = false
    private var controlsChecked = false
    private var retainedOperation: Any? = null
    private var sealedAt: Instant? = null
    private var applyInput: TestAdminDeleteApplyInputV1? = null
    private var preparedWork: CommittedTestAdminDeleteWork.Prepared? = null
    private var publisher: TestAdminDeleteJournalPublisherFactoryV1? = null
    private var publishedReadback: TestOwnerDeleteJournalReadbackV1? = null
    private var verificationInput: TestAdminDeleteVerificationInputV1? = null


    init {
        registration.requireUsable()
        registration.requireOwnerDeleteContinuationResources(ownership, jdbc)
        requireContinuation(listOf(actor, key).all { it.version() == 4 && it.variant() == 2 })
        requireContinuation(registration.process.consumers.journalConfiguration.registeredAdminDelete)
        drainBy?.retainPrimaryContinuation(this, registration, ownership, jdbc)
    }

    private val process = registration.process
    private val graph = TestOwnerDeleteLocalGraphV1(JdbcTemplate(process.pools.ordinary), jdbc,
        process.consumers.ingressAdmission, process.consumers.journalRouting, process.consumers.capacityPolicy,
        process.publicationLanes, process.desiredGeneration, registration)
    private val capacity = JdbcComplaintCapacityStore(jdbc, process.consumers.capacityPolicy.digestBytes())
    // Canonical restoration is static. Neither entry receives a request-authorizing codec.
    private val store = JdbcComplaintAdminDeleteStore(jdbc, capacity, audit, graph, codec = null)
    private val verification = JdbcComplaintAdminDeleteVerificationStore(jdbc, graph, store)
    private val apply = JdbcComplaintAdminDeleteApplyStore(jdbc, capacity, audit, graph, store, verification)

    fun complete(): ComplaintAdminDeleteReceipt {
        requireContinuation(caller === Thread.currentThread())
        throwIfSignalled()
        requireContinuation(!started)
        started = true
        stage = Stage.RELOAD
        try {
            requireConnectionFree()
            requireRunning()
            val reloaded = reload()
            val work = store.releasedRegistered(reloaded)
            requireContinuation(phase == null && !cleanupUncertain && sealedAt != null)
            val proof = when (work) {
                is CommittedTestAdminDeleteWork.RecordedVerified -> verification.resume(work)
                is CommittedTestAdminDeleteWork.Prepared -> publishAndVerify(work)
                else -> throw refusal()
            }
            requireRunning()
            applyInput = apply.capture(work, proof)
            stage = Stage.APPLY
            val completed = applyExisting()
            val result = completed.result
            requireConnectionFree()
            requireRunning()
            requireContinuation(phase == null && !cleanupUncertain && result.consumedGrantId == work.consumedGrantId)
            me.manga.kira.backend.complaint.infrastructure.AdminDeleteRows.requireApplied(result, store.ownedEvent(work))
            successful = true
            return result
        } catch (problem: Throwable) {
            observeFailure(problem)
            throwIfSignalled()
            throw refusal()
        } finally {
            finished = true
        }
    }

    private fun publishAndVerify(work: CommittedTestAdminDeleteWork.Prepared): CommittedTestAdminDeleteVerificationV1 {
        requirePreparedReload()
        requireReleasedReload()
        preparedWork = work
        stage = Stage.PUBLISH
        // The fixed factory shares J, consumes private work and owns all provider/native cleanup.
        // Neither a returned body nor a failed/retained lane can become a VERIFY input.
        val readback = publication.open(this, store).use { selected ->
            selected.reserve().use { it.publish(work) }
        }
        requireRunning()
        publishedReadback = readback
        verificationInput = verification.captureRegistered(this, readback)
        stage = Stage.VERIFY
        val selected = ownership.enterTestRunAdminDeleteVerify(this)
        var operation: ComplaintAdminDeleteVerificationOperation? = null
        try { selected.begin(); operation = verification.verify(checkNotNull(verificationInput)); selected.commit() }
        catch (problem: Throwable) { selected.recordFailure(problem) }
        finally { try { selected.finish() } finally { observePhaseCleanup(selected) } }
        val completed = operation ?: throw selected.failureException(PersistencePhaseFailureCode.WORK_FAILED)
        requireRunning()
        requireContinuation(phase == null && !phaseEntered && !controlsChecked && retainedOperation == null)
        return completed.result
    }

    private fun reload(): ComplaintAdminDeleteRegisteredReloadOperation {
        val selected = ownership.enterTestRunAdminDeleteReload(this)
        var operation: ComplaintAdminDeleteRegisteredReloadOperation? = null
        try { selected.begin(); operation = store.reloadRegistered(this); selected.commit() }
        catch (problem: Throwable) { selected.recordFailure(problem) }
        finally { try { selected.finish() } finally { observePhaseCleanup(selected) } }
        return operation ?: throw selected.failureException(PersistencePhaseFailureCode.WORK_FAILED)
    }

    private fun applyExisting(): ComplaintAdminDeleteApplyOperation {
        val selected = ownership.enterTestRunAdminDeleteApply(this)
        var operation: ComplaintAdminDeleteApplyOperation? = null
        try { selected.begin(); operation = apply.apply(checkNotNull(applyInput)); selected.commit() }
        catch (problem: Throwable) { selected.recordFailure(problem) }
        finally { try { selected.finish() } finally { observePhaseCleanup(selected) } }
        return operation ?: throw selected.failureException(PersistencePhaseFailureCode.WORK_FAILED)
    }

    private val path: PersistencePhasePath get() = when (stage) {
        Stage.RELOAD -> PersistencePhasePath.COMPLAINT_ADMIN_DELETE_RELOAD
        Stage.VERIFY -> PersistencePhasePath.COMPLAINT_ADMIN_DELETE_VERIFY
        Stage.APPLY -> PersistencePhasePath.COMPLAINT_ADMIN_DELETE_APPLY
        Stage.NEW, Stage.PUBLISH -> throw refusal()
    }

    internal fun requirePersistence(selected: PersistencePhaseOwnership, selectedJdbc: JdbcTemplate, selectedGraph: TestOwnerDeleteLocalGraphV1) {
        requireRunning()
        requireContinuation(selected === ownership && selectedJdbc === jdbc && selectedGraph === graph)
        registration.requireOwnerDeleteContinuationResources(selected, selectedJdbc)
        graph.requireDeletion(selectedJdbc)
    }

    internal fun requirePersistenceForStore(selectedGraph: TestOwnerDeleteLocalGraphV1, selectedJdbc: JdbcTemplate, operation: ComplaintAdminDeleteRegisteredReloadOperation) {
        requirePersistence(ownership, selectedJdbc, selectedGraph)
        requireContinuation(stage === Stage.RELOAD && retainedOperation === operation && operation.original === this && controlsChecked)
    }

    internal fun requirePhaseEntry(selected: PersistencePhaseOwnership, selectedPath: PersistencePhasePath) {
        requireConnectionFree()
        requireRunning()
        requireContinuation(selected === ownership && selectedPath === path && !phaseEntered && phase == null &&
            (stage === Stage.RELOAD && applyInput == null && sealedAt == null && preparedWork == null ||
                stage === Stage.VERIFY && preparedWork != null && publishedReadback != null && verificationInput != null && applyInput == null && sealedAt != null ||
                stage === Stage.APPLY && applyInput != null && sealedAt != null))
        phaseEntered = true
    }

    internal fun retainPhase(selected: PersistencePhaseContext) {
        requireRunning()
        requireContinuation(phaseEntered && phase == null)
        phase = selected
    }

    internal fun observePhaseCleanup(selected: PersistencePhaseContext) {
        try {
            if (caller !== Thread.currentThread() || phase !== selected || !selected.testRunAdminDeleteCleanupProven(this) ||
                selected.databaseOutcome() === PersistenceDatabaseOutcome.UNKNOWN) cleanupUncertain = true
            else { phase = null; phaseEntered = false; controlsChecked = false; retainedOperation = null }
        } catch (problem: Throwable) { cleanupUncertain = true; observeFailure(problem) }
    }

    internal fun requireMaintenanceGate(selected: PersistencePhaseOwnership, selectedPath: PersistencePhasePath, gate: PersistenceComplaintMaintenanceGateV1) {
        requireRunning()
        requireContinuation(selected === ownership && selectedPath === path && phaseEntered && phase != null)
        registration.requireOwnerDeleteContinuationGate(gate)
    }

    internal fun authenticateAndControls(selected: PersistencePhaseOwnership, selectedJdbc: JdbcTemplate, operation: Any) {
        requirePersistence(selected, selectedJdbc, graph)
        requireContinuation(retainedOperation == null && !controlsChecked && phase != null)
        when (stage) {
            Stage.RELOAD -> requireContinuation(operation is ComplaintAdminDeleteRegisteredReloadOperation && operation.original === this)
            Stage.VERIFY -> (operation as? ComplaintAdminDeleteVerificationOperation ?: throw refusal()).requireRegisteredContinuation(this)
            Stage.APPLY -> (operation as? ComplaintAdminDeleteApplyOperation ?: throw refusal()).requireRegisteredContinuation(this)
            Stage.NEW, Stage.PUBLISH -> throw refusal()
        }
        retainedOperation = operation
        val openings = process.pools.descriptors().single { it.role === PersistenceJdbcParticipantRole.DELETION }.openings().map { it.publicDriverProperties() }
        val user = openings.map { it["user"] }.distinct().single()
        val database = openings.map { it["PGDBNAME"] }.distinct().single()
        requireContinuation(jdbc.query("SELECT session_user = ? AND current_user = ? AND current_database() = ? AS authenticated",
            ResultSetExtractor { rows -> rows.next() && rows.getBoolean("authenticated") && !rows.wasNull() && !rows.next() }, user, user, database) == true)
        val controls = if (stage === Stage.VERIFY) listOf(TestRunSealingSqlV1.readGlobalControl, TestRunSealingSqlV1.readScopeControl)
            else listOf(TestRunSealingSqlV1.lockGlobalControl, TestRunSealingSqlV1.lockScopeControl)
        for (sql in controls) {
            requireContinuation(jdbc.query(sql, { row, _ -> requiredBoolean(row, "valid") }, *registration.sealingControlArguments()).single())
            requirePersistence(selected, selectedJdbc, graph)
        }
        controlsChecked = true
    }

    /** RELOAD/APPLY lock after counters; short VERIFY observes without a run lock. */
    internal fun requireSealedRun(selectedJdbc: JdbcTemplate) {
        requirePersistence(ownership, selectedJdbc, graph)
        requireContinuation(controlsChecked && retainedOperation != null)
        val at = jdbc.query(if (stage === Stage.VERIFY) TestRunSealingSqlV1.readRun else TestRunSealingSqlV1.lockRun, { row, _ ->
            requireContinuation(requiredBoolean(row, "valid") && row.getString("state") == "SEALED")
            checkNotNull(row.getTimestamp("sealed_at")).toInstant()
        }, *registration.sealingRunArguments()).single()
        if (stage === Stage.RELOAD) {
            requireContinuation(sealedAt == null)
            sealedAt = at
        } else requireContinuation(sealedAt == at)
        // Read only: taking an audit lock before APPLY's domain locks would invert the existing lock order.
        requireContinuation(jdbc.query(TestRunSealingSqlV1.readAudit, { row, _ -> requiredBoolean(row, "valid") },
            *registration.sealingAuditArguments(at)).single())
        requireRunning()
    }

    internal fun requireEarlierAuthorization(at: Instant) {
        requireRunning()
        requireContinuation(stage in setOf(Stage.RELOAD, Stage.VERIFY) && !at.isAfter(checkNotNull(sealedAt)))
    }

    internal fun requireReleasedReload() {
        requireConnectionFree()
        requireRunning() // Sticky original failure/cancellation and uncertain cleanup veto even private work issuance.
        requireContinuation(stage === Stage.RELOAD && phase == null && !phaseEntered && !controlsChecked &&
            retainedOperation == null && sealedAt != null && applyInput == null)
    }

    internal fun requirePreparedReload() {
        requireRunning()
        requireContinuation(stage === Stage.RELOAD && preparedWork == null)
    }

    internal fun retainPublisher(selected: TestAdminDeleteJournalPublisherFactoryV1, selectedStore: JdbcComplaintAdminDeleteStore) {
        requireConnectionFree()
        requireRunning()
        requireContinuation(stage === Stage.PUBLISH && selectedStore === store && preparedWork != null && publisher == null && phase == null)
        publisher = selected
    }

    /** Fixed factory admission/construction checks only; no permission callback is hidden in a clock. */
    internal fun requirePublisher(selected: TestAdminDeleteJournalPublisherFactoryV1, selectedStore: JdbcComplaintAdminDeleteStore,
        work: CommittedTestAdminDeleteWork.Prepared? = null) {
        requireConnectionFree()
        requireRunning()
        requireContinuation(stage === Stage.PUBLISH && publisher === selected && !selected.isClosed() && selectedStore === store &&
            preparedWork != null && (work == null || preparedWork === work) && phase == null && !phaseEntered)
    }

    internal fun requirePublishedReadback(selectedStore: JdbcComplaintAdminDeleteStore, selectedGraph: TestOwnerDeleteLocalGraphV1,
        readback: TestOwnerDeleteJournalReadbackV1) {
        requireConnectionFree()
        requirePersistence(ownership, jdbc, selectedGraph)
        requireContinuation(stage === Stage.PUBLISH && selectedStore === store && publishedReadback === readback &&
            publisher?.isClosed() == true && phase == null && !phaseEntered && verificationInput == null)
        val event = store.preparedEvent(checkNotNull(preparedWork))
        requireContinuation(event.route.objectKey == readback.event.route.objectKey && event.route.eventId == readback.event.route.eventId &&
            event.canonicalBytes().contentEquals(readback.event.canonicalBytes()))
    }

    internal fun requireVerificationInput(input: TestAdminDeleteVerificationInputV1) {
        requireRunning()
        requireContinuation(stage === Stage.VERIFY && verificationInput === input && publishedReadback != null)
    }

    internal fun requireApplyInput(input: TestAdminDeleteApplyInputV1) {
        requireRunning()
        requireContinuation(stage === Stage.APPLY && applyInput === input)
    }

    private fun requireRunning() {
        throwIfSignalled()
        if (Thread.currentThread().isInterrupted) throw InterruptedException("TEST admin-delete continuation interrupted.")
        requireContinuation(caller === Thread.currentThread() && !finished && !cleanupUncertain)
        budget.remainingMillis(1)
        registration.requireOwnerDeleteContinuationResources(ownership, jdbc)
        drainBy?.requirePrimaryContinuation(this)
    }

    internal fun requireCompletedForDrain(original: TestRunOrdinaryDrainV1) {
        requireConnectionFree(); throwIfSignalled()
        requireContinuation(drainBy === original && successful && finished && phase == null && !phaseEntered && !cleanupUncertain)
        original.requirePrimaryContinuation(this)
    }

    internal fun observeFailure(problem: Throwable) {
        drainBy?.observeFailure(problem)
        val retained = when {
            problem is Error -> problem
            problem is CancellationException -> CancellationException("TEST admin-delete continuation cancelled.")
            problem is InterruptedException || problem is InterruptedIOException ||
                (problem is PersistencePhaseException && problem.code === PersistencePhaseFailureCode.INTERRUPTED) -> InterruptedException("TEST admin-delete continuation interrupted.")
            else -> refusal()
        }
        while (true) {
            val previous = failure.get()
            if (previous is Error || (previous is CancellationException && retained !is Error) ||
                (previous is InterruptedException && retained !is Error && retained !is CancellationException) ||
                (previous != null && retained is TestRunAdminDeleteExceptionV1)) return
            if (failure.compareAndSet(previous, retained)) { return }
        }
    }

    internal fun throwIfSignalled() {
        failure.get()?.let { if (it is InterruptedException) Thread.currentThread().interrupt(); throw it }
    }

    private fun refusal(): TestRunAdminDeleteExceptionV1 = TestRunAdminDeleteExceptionV1()
    private fun requireContinuation(allowed: Boolean) { if (!allowed) throw refusal() }
    private fun requiredBoolean(row: ResultSet, column: String): Boolean = row.getBoolean(column).also { requireContinuation(!row.wasNull()) }

    override fun toString(): String = "TestRunAdminDeleteContinuationV1(same-registration,existing-primary-only,redacted)"
    private enum class Stage { NEW, RELOAD, PUBLISH, VERIFY, APPLY }
}

internal class TestRunAdminDeleteExceptionV1 : RuntimeException("TEST admin-delete continuation refused.", null, false, false)
