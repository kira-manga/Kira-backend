package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceComplaintMaintenanceGateV1
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceJdbcParticipantRole
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.security.ComplaintJournalDeletionKindV1
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminDeleteApplyOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminDeletePhaseOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeleteAllApplyOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeleteApplyOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeletePhaseOperation
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintAdminDeleteApplyStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintAdminDeleteStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintAdminDeleteVerificationStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteAllApplyStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteAllStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteAllVerificationStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteApplyStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteVerificationStore
import me.manga.kira.backend.complaint.infrastructure.OwnerDeleteAllApplyInputV1
import me.manga.kira.backend.complaint.infrastructure.TestAdminDeleteApplyInputV1
import me.manga.kira.backend.complaint.infrastructure.TestOwnerDeleteApplyInputV1
import me.manga.kira.backend.complaint.infrastructure.TestOwnerDeleteLocalGraphV1
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.infrastructure.journal.TestOrdinaryInventoryReadbackV1
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor
import java.time.Instant

/**
 * One exact pending recurrent native observation, not a queue/terminal original or a new attempt.
 * Its three typed stores admit only an existing verified primary and retained N/P/L. The existing
 * scan independently rereads permanent coverage AFTER this real APPLY commits and physically retires.
 * Deferred aliases, missing bookkeeping and receiptless retirement are not implemented here.
 */
internal class TestActiveRecurrentApplyV1 private constructor(
    internal val original: TestActiveRecurrentV1,
    internal val input: TestActiveRecurrentScanRecoveryInputV1,
    private val deletionOwner: PersistencePhaseOwnership,
    private val deletionJdbc: JdbcTemplate,
    audit: AuditService,
) : AutoCloseable {
    internal val registration = original.registration
    internal val budget = original.budget
    private val caller = Thread.currentThread()
    // Capture once while connection-free, including actual native exchange/key/client cleanup.
    // No seam getter or native ownership callback is invoked from the deletion holder.
    private val allowance = input.phaseBudget()
    private val native = input.readback()
    internal val entry = original.captureApplyEntry(input)
    private val kind = native.event.comparison.eventKind
    private val targetCount = native.event.complaintIds().size
    private val objectCreatedAt = native.lastModified
    internal val path = when (kind) {
        ComplaintJournalDeletionKindV1.OWNER_DELETE -> PersistencePhasePath.COMPLAINT_OWNER_DELETE_APPLY
        ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL -> PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_APPLY
        ComplaintJournalDeletionKindV1.ADMIN_DELETE, ComplaintJournalDeletionKindV1.ADMIN_BATCH_DELETE -> PersistencePhasePath.COMPLAINT_ADMIN_DELETE_APPLY
        else -> throw TestActiveRecurrentExceptionV1()
    }
    private val graph: TestOwnerDeleteLocalGraphV1
    private val ownerApply: JdbcComplaintOwnerDeleteApplyStore
    private val allApply: JdbcComplaintOwnerDeleteAllApplyStore
    private val adminApply: JdbcComplaintAdminDeleteApplyStore
    private var ownerInput: TestOwnerDeleteApplyInputV1? = null
    private var allInput: OwnerDeleteAllApplyInputV1? = null
    private var adminInput: TestAdminDeleteApplyInputV1? = null
    private var ownerOperation: ComplaintOwnerDeleteApplyOperation? = null
    private var allOperation: ComplaintOwnerDeleteAllApplyOperation? = null
    private var adminOperation: ComplaintAdminDeleteApplyOperation? = null
    private var capturing = false
    private var started = false
    private var completed = false
    private var controls = false
    private var run = false
    private var lastDatabaseTime: Instant? = null
    // Admission is one-shot, but does not itself retain a context. Its synchronous call must
    // unwind before an unused entry can release custody; actual phases still need owned cleanup.
    private var phaseEntryClaimed = false
    private var phaseEntryInFlight = false
    private var phaseEntered = false
    private var phase: PersistencePhaseContext? = null
    private var cleanupUncertain = false

    init {
        requireConnectionFree()
        input.requireOriginal(original); input.requireCurrentUse(registration, original.assembly, original.routing)
        val process = original.process
        deletionOwner.requireBoundComplaintDeletion(process.pools)
        requireRecurrent(deletionJdbc.dataSource === input.resources.deletion && process.pools.deletion.businessReady())
        graph = TestOwnerDeleteLocalGraphV1(JdbcTemplate(process.pools.ordinary), deletionJdbc, process.consumers.ingressAdmission,
            original.routing, process.consumers.capacityPolicy, process.publicationLanes, process.desiredGeneration, registration)
        val capacity = JdbcComplaintCapacityStore(deletionJdbc, process.consumers.capacityPolicy.digestBytes())
        val owner = JdbcComplaintOwnerDeleteStore(deletionJdbc, capacity, audit, graph, codec = null)
        ownerApply = JdbcComplaintOwnerDeleteApplyStore(deletionJdbc, capacity, audit, graph, owner,
            JdbcComplaintOwnerDeleteVerificationStore(deletionJdbc, graph, owner))
        val all = JdbcComplaintOwnerDeleteAllStore(deletionJdbc, capacity, audit, graph, codec = null)
        allApply = JdbcComplaintOwnerDeleteAllApplyStore(deletionJdbc, capacity, audit, graph,
            JdbcComplaintOwnerDeleteAllVerificationStore(deletionJdbc, graph, all))
        val admin = JdbcComplaintAdminDeleteStore(deletionJdbc, capacity, audit, graph, codec = null)
        adminApply = JdbcComplaintAdminDeleteApplyStore(deletionJdbc, capacity, audit, graph, admin,
            JdbcComplaintAdminDeleteVerificationStore(deletionJdbc, graph, admin))
    }

    internal fun apply() {
        requireConnectionFree(); requireRunning(); requireRecurrent(!started && !capturing)
        capturing = true
        try {
            when (kind) {
                ComplaintJournalDeletionKindV1.OWNER_DELETE -> ownerInput = ownerApply.captureRegisteredRecurrentRecovery(this)
                ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL -> allInput = allApply.captureRegisteredRecurrentRecovery(this)
                ComplaintJournalDeletionKindV1.ADMIN_DELETE, ComplaintJournalDeletionKindV1.ADMIN_BATCH_DELETE -> adminInput = adminApply.captureRegisteredRecurrentRecovery(this)
                else -> throw TestActiveRecurrentExceptionV1()
            }
        } finally { capturing = false }
        input.requireOriginal(original)
        started = true
        phaseEntryInFlight = true
        val selected = try {
            when (kind) {
                ComplaintJournalDeletionKindV1.OWNER_DELETE -> deletionOwner.enterTestActiveRecurrentOwnerDeleteRecovery(this)
                ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL -> deletionOwner.enterTestActiveRecurrentOwnerDeleteAllRecovery(this)
                ComplaintJournalDeletionKindV1.ADMIN_DELETE, ComplaintJournalDeletionKindV1.ADMIN_BATCH_DELETE -> deletionOwner.enterTestActiveRecurrentAdminDeleteRecovery(this)
                else -> throw TestActiveRecurrentExceptionV1()
            }
        } finally { phaseEntryInFlight = false }
        try {
            selected.begin()
            when (kind) {
                ComplaintJournalDeletionKindV1.OWNER_DELETE -> requireRecurrent(ownerApply.apply(checkNotNull(ownerInput)) === ownerOperation)
                ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL -> requireRecurrent(allApply.apply(checkNotNull(allInput)) === allOperation)
                ComplaintJournalDeletionKindV1.ADMIN_DELETE, ComplaintJournalDeletionKindV1.ADMIN_BATCH_DELETE -> requireRecurrent(adminApply.apply(checkNotNull(adminInput)) === adminOperation)
                else -> throw TestActiveRecurrentExceptionV1()
            }
            requireRunning(); requireRecurrent(controls && run)
            selected.commit()
        } catch (problem: Throwable) { observeFailure(problem); selected.recordFailure(problem) }
        finally {
            try { runCatching(selected::finish).exceptionOrNull()?.let(::observeFailure) }
            finally { observePhaseCleanup(selected) }
        }
        requireRunning(); requirePhysicalReleased(); requireRecoveredOperation()
        completed = true
    }

    internal fun requireReleased() {
        requireConnectionFree(); original.requireApply(this)
        requireRecurrent(completed); requirePhysicalReleased(); requireRecoveredOperation()
    }
    private fun requireRecoveredOperation() {
        requireRecurrent(listOfNotNull(ownerOperation, allOperation, adminOperation).size == 1)
        when (kind) {
            ComplaintJournalDeletionKindV1.OWNER_DELETE -> checkNotNull(ownerOperation).requireRecovered()
            ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL -> checkNotNull(allOperation).requireRecovered()
            ComplaintJournalDeletionKindV1.ADMIN_DELETE, ComplaintJournalDeletionKindV1.ADMIN_BATCH_DELETE -> checkNotNull(adminOperation).requireRecovered()
            else -> throw TestActiveRecurrentExceptionV1()
        }
    }
    internal fun ownedRecoveryReadback(store: JdbcComplaintOwnerDeleteApplyStore, selectedGraph: TestOwnerDeleteLocalGraphV1, jdbc: JdbcTemplate): TestOrdinaryInventoryReadbackV1 {
        requireCapture(selectedGraph, jdbc); requireRecurrent(store === ownerApply && kind === ComplaintJournalDeletionKindV1.OWNER_DELETE)
        return native
    }
    internal fun ownedRecoveryReadback(store: JdbcComplaintOwnerDeleteAllApplyStore, selectedGraph: TestOwnerDeleteLocalGraphV1, jdbc: JdbcTemplate): TestOrdinaryInventoryReadbackV1 {
        requireCapture(selectedGraph, jdbc); requireRecurrent(store === allApply && kind === ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL)
        return native
    }
    internal fun ownedRecoveryReadback(store: JdbcComplaintAdminDeleteApplyStore, selectedGraph: TestOwnerDeleteLocalGraphV1, jdbc: JdbcTemplate): TestOrdinaryInventoryReadbackV1 {
        requireCapture(selectedGraph, jdbc); requireRecurrent(store === adminApply && kind in setOf(ComplaintJournalDeletionKindV1.ADMIN_DELETE, ComplaintJournalDeletionKindV1.ADMIN_BATCH_DELETE))
        return native
    }
    private fun requireCapture(selectedGraph: TestOwnerDeleteLocalGraphV1, jdbc: JdbcTemplate) {
        requireConnectionFree(); requireRecoveryPersistence(deletionOwner, jdbc, selectedGraph)
        requireRecurrent(capturing && !started && listOfNotNull(ownerInput, allInput, adminInput).isEmpty())
        requireRecurrent(input.readback() === native)
    }
    internal fun requireRecoveryInput(selected: TestOwnerDeleteApplyInputV1) {
        requireRunning(); requireRecurrent(started && !capturing && selected === ownerInput && allInput == null && adminInput == null && kind === ComplaintJournalDeletionKindV1.OWNER_DELETE)
    }
    internal fun requireRecoveryInput(selected: OwnerDeleteAllApplyInputV1) {
        requireRunning(); requireRecurrent(started && !capturing && selected === allInput && ownerInput == null && adminInput == null && kind === ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL)
    }
    internal fun requireRecoveryInput(selected: TestAdminDeleteApplyInputV1) {
        requireRunning(); requireRecurrent(started && !capturing && selected === adminInput && ownerInput == null && allInput == null &&
            kind in setOf(ComplaintJournalDeletionKindV1.ADMIN_DELETE, ComplaintJournalDeletionKindV1.ADMIN_BATCH_DELETE))
    }
    internal fun requireRecoveryPersistence(ownership: PersistencePhaseOwnership, jdbc: JdbcTemplate, selectedGraph: TestOwnerDeleteLocalGraphV1) {
        requireRunning(); requireRecurrent(ownership === deletionOwner && jdbc === deletionJdbc && selectedGraph === graph && jdbc.dataSource === original.process.pools.deletion)
        deletionOwner.requireBoundComplaintDeletion(original.process.pools); graph.requireDeletion(jdbc)
    }
    internal fun authenticateRecoveryAndControls(ownership: PersistencePhaseOwnership, jdbc: JdbcTemplate, operation: ComplaintOwnerDeletePhaseOperation) {
        requireAuthentication(ownership, jdbc)
        val selected = operation as? ComplaintOwnerDeleteApplyOperation ?: throw TestActiveRecurrentExceptionV1()
        selected.requireRegisteredRecurrentRecovery(this); ownerOperation = selected
        authenticateAndLock(jdbc)
    }
    internal fun authenticateRecoveryAndControls(ownership: PersistencePhaseOwnership, jdbc: JdbcTemplate, operation: ComplaintOwnerDeleteAllApplyOperation) {
        requireAuthentication(ownership, jdbc)
        operation.requireRegisteredRecurrentRecovery(this); allOperation = operation
        authenticateAndLock(jdbc)
    }
    internal fun authenticateRecoveryAndControls(ownership: PersistencePhaseOwnership, jdbc: JdbcTemplate, operation: ComplaintAdminDeletePhaseOperation) {
        requireAuthentication(ownership, jdbc)
        val selected = operation as? ComplaintAdminDeleteApplyOperation ?: throw TestActiveRecurrentExceptionV1()
        selected.requireRegisteredRecurrentRecovery(this); adminOperation = selected
        authenticateAndLock(jdbc)
    }
    private fun requireAuthentication(ownership: PersistencePhaseOwnership, jdbc: JdbcTemplate) {
        requireRecoveryPersistence(ownership, jdbc, graph)
        requireRecurrent(started && !controls && listOfNotNull(ownerOperation, allOperation, adminOperation).isEmpty())
    }
    private fun authenticateAndLock(jdbc: JdbcTemplate) {
        requireRecoveryHolder(jdbc)
        val properties = original.process.pools.descriptors().single { it.role === PersistenceJdbcParticipantRole.DELETION }.openings().map { it.publicDriverProperties() }
        val user = properties.map { it["user"] }.distinct().single(); val database = properties.map { it["PGDBNAME"] }.distinct().single()
        requireRecurrent(jdbc.query(TestActiveRecurrentSqlV1.authenticate,
            ResultSetExtractor { rows -> rows.next() && rows.getBoolean("valid") && !rows.wasNull() && !rows.next() }, user, user, database) == true)
        TestActiveRecurrentOperationV1.lockRecoveryControls(jdbc, this); controls = true
    }
    internal fun requireRecoveryHolder(jdbc: JdbcTemplate) {
        requireRecoveryPersistence(deletionOwner, jdbc, graph)
        requireRecurrent(started && phaseEntered && phase != null && phase === PersistencePhaseOwnership.current() &&
            listOfNotNull(ownerOperation, allOperation, adminOperation).size == 1)
    }
    internal fun requireRecoveryControls(jdbc: JdbcTemplate): Long {
        requireRecoveryHolder(jdbc); requireRecurrent(controls)
        return TestActiveRecurrentOperationV1.requireRecoveryCurrent(jdbc, this)
    }
    internal fun requireRecoveryRun(jdbc: JdbcTemplate) {
        requireRecoveryHolder(jdbc); requireRecurrent(controls && !run)
        TestActiveRecurrentOperationV1.lockRecoveryRun(jdbc, this); run = true
    }
    internal fun requireAppliedCurrent(operation: ComplaintOwnerDeleteApplyOperation, jdbc: JdbcTemplate) {
        requireRecurrent(operation === ownerOperation); requireAppliedCurrent(jdbc)
    }
    internal fun requireAppliedCurrent(operation: ComplaintOwnerDeleteAllApplyOperation, jdbc: JdbcTemplate) {
        requireRecurrent(operation === allOperation); requireAppliedCurrent(jdbc)
    }
    internal fun requireAppliedCurrent(operation: ComplaintAdminDeleteApplyOperation, jdbc: JdbcTemplate) {
        requireRecurrent(operation === adminOperation); requireAppliedCurrent(jdbc)
    }
    private fun requireAppliedCurrent(jdbc: JdbcTemplate) {
        requireRecoveryHolder(jdbc); requireRecurrent(controls && run)
        TestActiveRecurrentOperationV1.requireRecoveryCurrent(jdbc, this)
    }
    internal fun requireLockedEntry(jdbc: JdbcTemplate, row: TestActiveRecurrentScanV1.Entry) {
        requireRecoveryHolder(jdbc); entry.requireSame(row)
        requireRecurrent(row.replay == "PENDING")
        if (row.appliedPresent) requireRecurrent(row.appliedValid && row.targets == targetCount && checkNotNull(row.appliedAt) >= objectCreatedAt)
    }
    internal fun requireDatabaseTime(jdbc: JdbcTemplate, current: TestActiveRecurrentCurrentV1) {
        requireRecoveryHolder(jdbc); original.requireApplyCurrent(this, current)
        requireRecurrent(lastDatabaseTime?.isAfter(current.sampledAt) != true); lastDatabaseTime = current.sampledAt
    }
    internal fun phaseBudget(): PersistenceTimeBudget { requireRunning(); return allowance }
    internal fun requirePhaseEntry(ownership: PersistencePhaseOwnership, selected: PersistencePhasePath) {
        requireConnectionFree(); requireRecoveryPersistence(ownership, deletionJdbc, graph)
        requireRecurrent(started && !capturing && phaseEntryInFlight && !phaseEntryClaimed && selected === path && phase == null && !phaseEntered)
        phaseEntryClaimed = true
    }
    internal fun retainPhase(selected: PersistencePhaseContext) {
        requireRunning(); requireRecurrent(phaseEntryInFlight && phaseEntryClaimed && !phaseEntered && phase == null)
        phase = selected; phaseEntered = true
    }
    internal fun observePhaseCleanup(selected: PersistencePhaseContext) {
        try {
            if (caller !== Thread.currentThread() || phase !== selected || !selected.testActiveRecurrentApplyCleanupProven(this)) {
                cleanupUncertain = true; observeFailure(TestActiveRecurrentExceptionV1())
            } else {
                phase = null; phaseEntered = false
                if (selected.databaseOutcome() === PersistenceDatabaseOutcome.UNKNOWN) observeFailure(TestActiveRecurrentExceptionV1())
            }
        } catch (problem: Throwable) { cleanupUncertain = true; observeFailure(problem) }
    }
    internal fun requireMaintenanceGate(ownership: PersistencePhaseOwnership, selected: PersistencePhasePath, gate: PersistenceComplaintMaintenanceGateV1) {
        requireRunning(); requireRecurrent(ownership === deletionOwner && selected === path && phaseEntered && phase != null)
        registration.requireActiveDeletionGate(gate)
    }
    private fun requireRunning() {
        original.requireApply(this); requireRecurrent(caller === Thread.currentThread() && !completed && !cleanupUncertain); allowance.remainingMillis(1)
    }
    internal fun observeFailure(problem: Throwable) = original.observeFailure(problem)
    internal fun throwIfSignalled() = original.throwIfSignalled()
    internal fun requirePhysicalReleased() {
        requireConnectionFree(); requireRecurrent(caller === Thread.currentThread() && !phaseEntryInFlight && !cleanupUncertain && phase == null && !phaseEntered)
    }
    override fun close() {
        requireRecurrent(caller === Thread.currentThread())
        phase?.let { selected ->
            selected.recordFailure(TestActiveRecurrentExceptionV1())
            try { runCatching(selected::finish).exceptionOrNull()?.let(::observeFailure) }
            finally { observePhaseCleanup(selected) }
        }
        requirePhysicalReleased()
    }
    override fun toString(): String = "RecurrentApply(original-bound,retained-primary-only,no-checkpoint-authority)"
    companion object {
        internal fun prepare(original: TestActiveRecurrentV1, input: TestActiveRecurrentScanRecoveryInputV1,
            deletionOwner: PersistencePhaseOwnership, deletionJdbc: JdbcTemplate, audit: AuditService): TestActiveRecurrentApplyV1 =
            TestActiveRecurrentApplyV1(original, input, deletionOwner, deletionJdbc, audit)
    }
}
