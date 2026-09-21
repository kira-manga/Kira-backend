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
import me.manga.kira.backend.complaint.infrastructure.CommittedTestOwnerDeleteVerificationV1
import me.manga.kira.backend.complaint.infrastructure.CommittedTestOwnerDeleteWork
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeleteApplyOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeletePhaseOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeleteRegisteredReloadOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeleteRegisteredSelectionOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeleteVerificationOperation
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteApplyStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteVerificationStore
import me.manga.kira.backend.complaint.infrastructure.OwnerDeletePersistenceSql
import me.manga.kira.backend.complaint.infrastructure.TestOwnerDeleteApplyInputV1
import me.manga.kira.backend.complaint.infrastructure.TestOwnerDeleteLocalGraphV1
import me.manga.kira.backend.complaint.infrastructure.TestOwnerDeleteVerificationInputV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationV1
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.infrastructure.journal.TestOwnerDeleteJournalPublisherFactoryV1
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
 * One original caller for an existing primary, or one bounded page of SQL-discovered primaries.
 * Scope is registration-owned; actor/key remain locators, never work/proof authority. Each selected
 * primary still requires its own released RELOAD and uses the SAME outer budget and live custody.
 * No new authorization, reconstruction, ordinary-range drain proof or reserve-release right.
 */
internal sealed class TestRunOwnerDeleteContinuationV1 protected constructor(
    private val registration: ComplaintTestNamespaceRegistrationV1,
    private val ownership: PersistencePhaseOwnership,
    private val jdbc: JdbcTemplate,
    private val audit: AuditService,
    actor: UUID?,
    key: UUID?,
    private val publication: TestRunPreparedOwnerDeleteV1.Publication?,
    private val selectedBy: TestRunOwnerDeleteContinuationV1? = null,
    private val drainBy: TestRunOrdinaryDrainV1? = null,
) {
    private val caller = Thread.currentThread()
    internal val budget: PersistenceTimeBudget = selectedBy?.budget ?: drainBy?.budget ?: PersistenceTimeBudget.start(registration.process.catalogReadback.totalAttemptMillis, ownership.nanoClock)
    private val locator = actor?.let { it to checkNotNull(key) }
    internal val actorId: UUID get() = checkNotNull(locator).first
    internal val operationKey: UUID get() = checkNotNull(locator).second
    private val failure = AtomicReference<Throwable?>()
    private var started = false
    private var finished = false
    private var successful = false
    private var stage = Stage.NEW
    private var phase: PersistencePhaseContext? = null
    private var phaseEntered = false
    private var cleanupUncertain = false
    private var controlsChecked = false
    private var retainedOperation: ComplaintOwnerDeletePhaseOperation? = null
    private var sealedAt: Instant? = null
    private var applyInput: TestOwnerDeleteApplyInputV1? = null
    private var preparedWork: CommittedTestOwnerDeleteWork.Prepared? = null
    private var publisher: TestOwnerDeleteJournalPublisherFactoryV1? = null
    private var publishedReadback: TestOwnerDeleteJournalReadbackV1? = null
    private var verificationInput: TestOwnerDeleteVerificationInputV1? = null
    private var selectedLocator: Pair<UUID, UUID>? = null
    private var selectedChild: TestRunOwnerDeleteContinuationV1? = null

    init {
        registration.requireUsable()
        registration.requireOwnerDeleteContinuationResources(ownership, jdbc)
        requireContinuation((actor == null) == (key == null))
        if (locator == null) requireContinuation(publication != null && selectedBy == null)
        else requireContinuation(listOf(locator.first, locator.second).all { it.version() == 4 && it.variant() == 2 })
        selectedBy?.retainSelected(this)
        drainBy?.retainPrimaryContinuation(this, registration, ownership, jdbc)
    }

    private val process = registration.process
    private val graph = TestOwnerDeleteLocalGraphV1(JdbcTemplate(process.pools.ordinary), jdbc,
        process.consumers.ingressAdmission, process.consumers.journalRouting, process.consumers.capacityPolicy,
        process.publicationLanes, process.desiredGeneration, registration)
    private val capacity = JdbcComplaintCapacityStore(jdbc, process.consumers.capacityPolicy.digestBytes())
    // Canonical restoration is static. Neither entry receives a request-authorizing codec.
    private val store = JdbcComplaintOwnerDeleteStore(jdbc, capacity, audit, graph, codec = null)
    private val verification = JdbcComplaintOwnerDeleteVerificationStore(jdbc, graph, store)
    private val apply = JdbcComplaintOwnerDeleteApplyStore(jdbc, capacity, audit, graph, store, verification)

    fun complete(): ComplaintOwnerDeleteReceipt {
        requireContinuation(caller === Thread.currentThread())
        throwIfSignalled()
        requireContinuation(!started && locator != null)
        started = true
        stage = Stage.RELOAD
        try {
            requireConnectionFree()
            requireRunning()
            val reloaded = reload()
            val work = store.releasedRegistered(reloaded)
            requireContinuation(phase == null && !cleanupUncertain && sealedAt != null)
            val proof = when (work) {
                is CommittedTestOwnerDeleteWork.RecordedVerified -> verification.resume(work)
                is CommittedTestOwnerDeleteWork.Prepared -> publishAndVerify(work)
                else -> throw refusal()
            }
            requireRunning()
            applyInput = apply.capture(work, proof)
            stage = Stage.APPLY
            val completed = applyExisting()
            val result = completed.result
            requireConnectionFree()
            requireRunning()
            requireContinuation(phase == null && !cleanupUncertain && result === ComplaintOwnerDeleteReceipt.Applied)
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

    /** Exactly one page, no automatic pagination/retry. A zero count or absent peek is not quiescence. */
    protected fun completeSelectedPage(): TestRunPreparedOwnerDeleteV1.PageProgress {
        requireContinuation(caller === Thread.currentThread())
        throwIfSignalled()
        requireContinuation(!started && locator == null && selectedBy == null && publication != null)
        started = true
        stage = Stage.SELECT
        try {
            requireConnectionFree()
            requireRunning()
            val page = select().released(store)
            stage = Stage.PAGE
            var completed = 0
            for (candidate in page.take(OwnerDeletePersistenceSql.REGISTERED_PRIMARY_PAGE_LIMIT)) {
                requireRunning()
                requireContinuation(selectedChild == null && selectedLocator == null)
                selectedLocator = candidate
                val child: TestRunOwnerDeleteContinuationV1 = TestRunPreparedOwnerDeleteV1.selected(this, registration, ownership, jdbc, audit,
                    candidate.first, candidate.second, checkNotNull(publication))
                child.complete()
                requireRunning()
                requireContinuation(selectedChild === child && child.finished && child.successful && child.failure.get() == null &&
                    child.phase == null && !child.cleanupUncertain)
                selectedChild = null
                selectedLocator = null
                completed++
            }
            requireConnectionFree()
            requireRunning()
            successful = true
            return TestRunPreparedOwnerDeleteV1.PageProgress(completed, page.size > OwnerDeletePersistenceSql.REGISTERED_PRIMARY_PAGE_LIMIT)
        } catch (problem: Throwable) {
            observeFailure(problem)
            throwIfSignalled()
            throw refusal()
        } finally {
            finished = true
        }
    }

    private fun select(): ComplaintOwnerDeleteRegisteredSelectionOperation {
        val selected = ownership.enterTestRunOwnerDeleteReload(this)
        var operation: ComplaintOwnerDeleteRegisteredSelectionOperation? = null
        try { selected.begin(); operation = store.selectRegistered(this); selected.commit() }
        catch (problem: Throwable) { selected.recordFailure(problem) }
        finally { try { selected.finish() } finally { observePhaseCleanup(selected) } }
        return operation ?: throw selected.failureException(PersistencePhaseFailureCode.WORK_FAILED)
    }

    private fun retainSelected(child: TestRunOwnerDeleteContinuationV1) {
        requireConnectionFree()
        requireRunning()
        requireContinuation(stage === Stage.PAGE && locator == null && selectedBy == null && selectedChild == null && selectedLocator != null &&
            child.selectedBy === this && child.locator == selectedLocator && child.registration === registration && child.ownership === ownership &&
            child.jdbc === jdbc && child.audit === audit && child.publication === publication && child.budget === budget)
        selectedChild = child
    }

    private fun requireSelectedRunning(child: TestRunOwnerDeleteContinuationV1) {
        requireRunning()
        requireContinuation(stage === Stage.PAGE && selectedChild === child && phase == null && !phaseEntered && !controlsChecked)
    }

    internal fun requireReleasedSelection() {
        requireConnectionFree()
        requireRunning()
        requireContinuation(stage === Stage.SELECT && locator == null && phase == null && !phaseEntered && !controlsChecked &&
            retainedOperation == null && sealedAt != null && selectedChild == null && selectedLocator == null)
    }

    /** Current original-owned SELECT comparison only; never request/RELOAD/APPLY admission. */
    internal fun freshDrainSelectionControls(operation: ComplaintOwnerDeleteRegisteredSelectionOperation,
        selectedJdbc: JdbcTemplate, selectedGraph: TestOwnerDeleteLocalGraphV1): Boolean {
        requirePersistence(ownership, selectedJdbc, selectedGraph)
        requireContinuation(stage === Stage.SELECT && locator == null && selectedBy == null &&
            retainedOperation === operation && operation.original === this && controlsChecked && phaseEntered &&
            phase === PersistencePhaseOwnership.current() && selectedChild == null && selectedLocator == null)
        val original = drainBy ?: return false
        return original.freshPrimarySelectionControls(this, selectedJdbc)
    }

    private fun publishAndVerify(work: CommittedTestOwnerDeleteWork.Prepared): CommittedTestOwnerDeleteVerificationV1 {
        requirePreparedReload()
        requireReleasedReload()
        preparedWork = work
        stage = Stage.PUBLISH
        // The fixed factory shares J, consumes private work and owns all provider/native cleanup.
        // Neither a returned body nor a failed/retained lane can become a VERIFY input.
        val readback = checkNotNull(publication).open(this, store).use { selected ->
            selected.reserve().use { it.publish(work) }
        }
        requireRunning()
        publishedReadback = readback
        verificationInput = verification.captureRegistered(this, readback)
        stage = Stage.VERIFY
        val selected = ownership.enterTestRunOwnerDeleteVerify(this)
        var operation: ComplaintOwnerDeleteVerificationOperation? = null
        try { selected.begin(); operation = verification.verify(checkNotNull(verificationInput)); selected.commit() }
        catch (problem: Throwable) { selected.recordFailure(problem) }
        finally { try { selected.finish() } finally { observePhaseCleanup(selected) } }
        val completed = operation ?: throw selected.failureException(PersistencePhaseFailureCode.WORK_FAILED)
        requireRunning()
        requireContinuation(phase == null && !phaseEntered && !controlsChecked && retainedOperation == null)
        return completed.result
    }

    private fun reload(): ComplaintOwnerDeleteRegisteredReloadOperation {
        val selected = ownership.enterTestRunOwnerDeleteReload(this)
        var operation: ComplaintOwnerDeleteRegisteredReloadOperation? = null
        try { selected.begin(); operation = store.reloadRegistered(this); selected.commit() }
        catch (problem: Throwable) { selected.recordFailure(problem) }
        finally { try { selected.finish() } finally { observePhaseCleanup(selected) } }
        return operation ?: throw selected.failureException(PersistencePhaseFailureCode.WORK_FAILED)
    }

    private fun applyExisting(): ComplaintOwnerDeleteApplyOperation {
        val selected = ownership.enterTestRunOwnerDeleteApply(this)
        var operation: ComplaintOwnerDeleteApplyOperation? = null
        try { selected.begin(); operation = apply.apply(checkNotNull(applyInput)); selected.commit() }
        catch (problem: Throwable) { selected.recordFailure(problem) }
        finally { try { selected.finish() } finally { observePhaseCleanup(selected) } }
        return operation ?: throw selected.failureException(PersistencePhaseFailureCode.WORK_FAILED)
    }

    private val path: PersistencePhasePath get() = when (stage) {
        Stage.SELECT, Stage.RELOAD -> PersistencePhasePath.COMPLAINT_OWNER_DELETE_RELOAD
        Stage.VERIFY -> PersistencePhasePath.COMPLAINT_OWNER_DELETE_VERIFY
        Stage.APPLY -> PersistencePhasePath.COMPLAINT_OWNER_DELETE_APPLY
        Stage.NEW, Stage.PAGE, Stage.PUBLISH -> throw refusal()
    }

    internal fun requirePersistence(selected: PersistencePhaseOwnership, selectedJdbc: JdbcTemplate, selectedGraph: TestOwnerDeleteLocalGraphV1) {
        requireRunning()
        requireContinuation(selected === ownership && selectedJdbc === jdbc && selectedGraph === graph)
        registration.requireOwnerDeleteContinuationResources(selected, selectedJdbc)
        graph.requireDeletion(selectedJdbc)
    }

    internal fun requirePhaseEntry(selected: PersistencePhaseOwnership, selectedPath: PersistencePhasePath) {
        requireConnectionFree()
        requireRunning()
        requireContinuation(selected === ownership && selectedPath === path && !phaseEntered && phase == null &&
            (stage === Stage.SELECT && locator == null && sealedAt == null && selectedChild == null ||
                stage === Stage.RELOAD && locator != null && applyInput == null && sealedAt == null && preparedWork == null ||
                stage === Stage.VERIFY && publication != null && preparedWork != null && publishedReadback != null && verificationInput != null && applyInput == null && sealedAt != null ||
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
            if (caller !== Thread.currentThread() || phase !== selected || !selected.testRunOwnerDeleteCleanupProven(this) ||
                selected.databaseOutcome() === PersistenceDatabaseOutcome.UNKNOWN) cleanupUncertain = true
            else { phase = null; phaseEntered = false; controlsChecked = false; retainedOperation = null }
        } catch (problem: Throwable) { cleanupUncertain = true; observeFailure(problem) }
    }

    internal fun requireMaintenanceGate(selected: PersistencePhaseOwnership, selectedPath: PersistencePhasePath, gate: PersistenceComplaintMaintenanceGateV1) {
        requireRunning()
        requireContinuation(selected === ownership && selectedPath === path && phaseEntered && phase != null)
        registration.requireOwnerDeleteContinuationGate(gate)
    }

    internal fun authenticateAndControls(selected: PersistencePhaseOwnership, selectedJdbc: JdbcTemplate, operation: ComplaintOwnerDeletePhaseOperation) {
        requirePersistence(selected, selectedJdbc, graph)
        requireContinuation(retainedOperation == null && !controlsChecked && phase != null)
        when (stage) {
            Stage.SELECT -> requireContinuation(operation is ComplaintOwnerDeleteRegisteredSelectionOperation && operation.original === this)
            Stage.RELOAD -> requireContinuation(operation is ComplaintOwnerDeleteRegisteredReloadOperation && operation.original === this)
            Stage.VERIFY -> (operation as? ComplaintOwnerDeleteVerificationOperation ?: throw refusal()).requireRegisteredContinuation(this)
            Stage.APPLY -> (operation as? ComplaintOwnerDeleteApplyOperation ?: throw refusal()).requireRegisteredContinuation(this)
            Stage.NEW, Stage.PAGE, Stage.PUBLISH -> throw refusal()
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

    /** Selection/RELOAD/APPLY lock after counters; short VERIFY observes without a run lock. */
    internal fun requireSealedRun(selectedJdbc: JdbcTemplate) {
        requirePersistence(ownership, selectedJdbc, graph)
        requireContinuation(controlsChecked && retainedOperation != null)
        val at = jdbc.query(if (stage === Stage.VERIFY) TestRunSealingSqlV1.readRun else TestRunSealingSqlV1.lockRun, { row, _ ->
            requireContinuation(requiredBoolean(row, "valid") && row.getString("state") == "SEALED")
            checkNotNull(row.getTimestamp("sealed_at")).toInstant()
        }, *registration.sealingRunArguments()).single()
        if (stage === Stage.SELECT || stage === Stage.RELOAD) {
            requireContinuation(sealedAt == null && (selectedBy == null || selectedBy.sealedAt == at))
            sealedAt = at
        } else requireContinuation(sealedAt == at)
        // Read only: taking an audit lock before APPLY's domain locks would invert the existing lock order.
        requireContinuation(jdbc.query(TestRunSealingSqlV1.readAudit, { row, _ -> requiredBoolean(row, "valid") },
            *registration.sealingAuditArguments(at)).single())
        requireRunning()
    }

    internal fun requireEarlierAuthorization(at: Instant) {
        requireRunning()
        requireContinuation(stage in setOf(Stage.SELECT, Stage.RELOAD, Stage.VERIFY) && !at.isAfter(checkNotNull(sealedAt)))
    }

    internal fun requireReleasedReload() {
        requireConnectionFree()
        requireRunning() // Sticky original failure/cancellation and uncertain cleanup veto even private work issuance.
        requireContinuation(stage === Stage.RELOAD && phase == null && !phaseEntered && !controlsChecked &&
            retainedOperation == null && sealedAt != null && applyInput == null)
    }

    internal fun requirePreparedReload() {
        requireRunning()
        requireContinuation(publication != null && stage === Stage.RELOAD && preparedWork == null)
    }

    internal fun retainPublisher(selected: TestOwnerDeleteJournalPublisherFactoryV1, selectedStore: JdbcComplaintOwnerDeleteStore) {
        requireConnectionFree()
        requireRunning()
        requireContinuation(publication != null && stage === Stage.PUBLISH && selectedStore === store && preparedWork != null && publisher == null && phase == null)
        publisher = selected
    }

    /** Fixed factory admission/construction checks only; no permission callback is hidden in a clock. */
    internal fun requirePublisher(selected: TestOwnerDeleteJournalPublisherFactoryV1, selectedStore: JdbcComplaintOwnerDeleteStore,
        work: CommittedTestOwnerDeleteWork.Prepared? = null) {
        requireConnectionFree()
        requireRunning()
        requireContinuation(publication != null && stage === Stage.PUBLISH && publisher === selected && !selected.isClosed() && selectedStore === store &&
            preparedWork != null && (work == null || preparedWork === work) && phase == null && !phaseEntered)
    }

    internal fun requirePublishedReadback(selectedStore: JdbcComplaintOwnerDeleteStore, selectedGraph: TestOwnerDeleteLocalGraphV1,
        readback: TestOwnerDeleteJournalReadbackV1) {
        requireConnectionFree()
        requirePersistence(ownership, jdbc, selectedGraph)
        requireContinuation(publication != null && stage === Stage.PUBLISH && selectedStore === store && publishedReadback === readback &&
            publisher?.isClosed() == true && phase == null && !phaseEntered && verificationInput == null)
        val event = store.preparedEvent(checkNotNull(preparedWork))
        requireContinuation(event.route == readback.event.route && event.canonicalBytes().contentEquals(readback.event.canonicalBytes()))
    }

    internal fun requireVerificationInput(input: TestOwnerDeleteVerificationInputV1) {
        requireRunning()
        requireContinuation(publication != null && stage === Stage.VERIFY && verificationInput === input && publishedReadback != null)
    }

    internal fun requireApplyInput(input: TestOwnerDeleteApplyInputV1) {
        requireRunning()
        requireContinuation(stage === Stage.APPLY && applyInput === input)
    }

    private fun requireRunning() {
        throwIfSignalled()
        if (Thread.currentThread().isInterrupted) throw InterruptedException("TEST owner-delete continuation interrupted.")
        requireContinuation(caller === Thread.currentThread() && !finished && !cleanupUncertain)
        budget.remainingMillis(1)
        registration.requireOwnerDeleteContinuationResources(ownership, jdbc)
        selectedBy?.requireSelectedRunning(this)
        drainBy?.requirePrimaryContinuation(this)
    }

    internal fun observeFailure(problem: Throwable) {
        val retained = when {
            problem is Error -> problem
            problem is CancellationException -> CancellationException("TEST owner-delete continuation cancelled.")
            problem is InterruptedException || problem is InterruptedIOException ||
                (problem is PersistencePhaseException && problem.code === PersistencePhaseFailureCode.INTERRUPTED) -> InterruptedException("TEST owner-delete continuation interrupted.")
            else -> refusal()
        }
        while (true) {
            val previous = failure.get()
            if (previous is Error || (previous is CancellationException && retained !is Error) ||
                (previous is InterruptedException && retained !is Error && retained !is CancellationException) ||
                (previous != null && retained is TestRunOwnerDeleteExceptionV1)) return
            if (failure.compareAndSet(previous, retained)) { selectedBy?.observeFailure(retained); drainBy?.observeFailure(retained); return }
        }
    }

    internal fun throwIfSignalled() {
        failure.get()?.let { if (it is InterruptedException) Thread.currentThread().interrupt(); throw it }
    }

    private fun refusal(): TestRunOwnerDeleteExceptionV1 = if (publication == null) TestRunVerifiedOwnerDeleteExceptionV1() else TestRunPreparedOwnerDeleteExceptionV1()
    private fun requireContinuation(allowed: Boolean) { if (!allowed) throw refusal() }
    private fun requiredBoolean(row: ResultSet, column: String): Boolean = row.getBoolean(column).also { requireContinuation(!row.wasNull()) }

    override fun toString(): String = "TestRunOwnerDeleteContinuationV1(same-registration,existing-primary-only,redacted)"
    private enum class Stage { NEW, SELECT, PAGE, RELOAD, PUBLISH, VERIFY, APPLY }
}

internal sealed class TestRunOwnerDeleteExceptionV1(message: String) : RuntimeException(message, null, false, false)
