package me.manga.kira.backend.common.infrastructure.persistence

import jakarta.persistence.EntityManager
import me.manga.kira.backend.audit.infrastructure.ComplaintAuditSelectedHolder
import me.manga.kira.backend.complaint.domain.ComplaintCapacityLedger
import me.manga.kira.backend.complaint.domain.ComplaintDailyAdmission
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationTuple
import me.manga.kira.backend.complaint.domain.ComplaintRecoverySettlementResult
import me.manga.kira.backend.complaint.domain.ComplaintTestReserveSpendResult
import me.manga.kira.backend.complaint.domain.InstallationDeletionPreflightTuple
import me.manga.kira.backend.complaint.domain.InstallationEnrollmentCandidate
import me.manga.kira.backend.complaint.domain.InstallationEnrollmentResult
import me.manga.kira.backend.complaint.domain.InstallationSessionPreflight
import me.manga.kira.backend.complaint.domain.InstallationSessionResult
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import me.manga.kira.backend.complaint.domain.SessionPreflightResult
import me.manga.kira.backend.complaint.domain.SessionRefreshResult
import me.manga.kira.backend.complaint.infrastructure.ComplaintInstallationDeletionPreflightOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintInstallationSessionOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerCreateOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeleteAllApplyOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeleteAllOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeleteAllVerificationOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerHistoryReadOperation
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintDesiredInstallAttemptV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintDesiredInstallOperationV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintSignedGenesisFirstDAttemptV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintSignedGenesisFirstDOperationV1
import me.manga.kira.backend.complaint.infrastructure.admission.InstallationCurrentStateReadOperation
import me.manga.kira.backend.complaint.infrastructure.capacity.ComplaintInstallationEnrollmentOperation
import me.manga.kira.backend.complaint.infrastructure.capacity.ComplaintRecoverySettlementOperation
import me.manga.kira.backend.complaint.infrastructure.capacity.ComplaintTestReserveSpendOperation
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogCoordinatorLeaseBindingV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogCoordinatorLeaseOperation
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogCutoffAttemptV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogCutoffPersistenceOperationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogEpochRotationAttemptV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogEpochRotationControlOperation
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisInitialLiveBinding
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisMutationOperation
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogProjectedHeadInputV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogProjectedHeadReadOperationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogReadbackRefreshCustodyV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSnapshotReadOperation
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintDeletionOperation
import me.manga.kira.backend.security.ComplaintAdmittedEnrollmentWrite
import me.manga.kira.backend.security.ComplaintAdmittedOwnerCreate
import me.manga.kira.backend.security.ComplaintAdmittedOwnerDeleteAll
import me.manga.kira.backend.security.ComplaintAdmittedSessionRefresh
import me.manga.kira.backend.security.ComplaintGrantCleanupBatch
import me.manga.kira.backend.security.ComplaintGrantConsumption
import me.manga.kira.backend.security.ComplaintIngressAdmission
import me.manga.kira.backend.security.ScopedAdminStepUpScope
import me.manga.kira.backend.security.StepUpGrantIssuance
import me.manga.kira.backend.security.StepUpUserSnapshot
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.orm.jpa.EntityManagerHolder
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.DefaultTransactionDefinition
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.lang.reflect.Method
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/**
 * One synchronous named owner. No application lambda, foreign thread or resource switch.
 * All guarded transitions share this retained phase/permit/lease rather than splitting their custody.
 * Named operation boundaries keep commit/release/quarantine proof on this one original context.
 * The constructor keeps each named attempt/work pair explicit instead of introducing a generic authority carrier.
 */
@Suppress("TooManyFunctions", "LargeClass")
internal class PersistencePhaseContext
@Suppress("LongParameterList")
constructor(
    private val ownership: PersistencePhaseOwnership,
    private val slot: Int,
    private val caller: PersistenceOwnedFactoryCaller,
    private val path: PersistencePhasePath,
    private val deletionScope: ComplaintDataScope? = null,
    private val enrollmentOwnerReference: UUID? = null,
    private val rotationAttempt: CatalogEpochRotationAttemptV1? = null,
    private val rotationWork: PersistenceTimeBudget? = null,
    private val cutoffAttempt: CatalogCutoffAttemptV1? = null,
    private val cutoffWork: PersistenceTimeBudget? = null,
    private val catalogRefresh: CatalogReadbackRefreshCustodyV1.Attempt? = null,
    private val catalogRefreshWork: PersistenceTimeBudget? = null,
    private val desiredAttempt: ComplaintDesiredInstallAttemptV1? = null,
    private val desiredWork: PersistenceTimeBudget? = null,
    private val firstDesiredAttempt: ComplaintSignedGenesisFirstDAttemptV1? = null,
    private val firstDesiredWork: PersistenceTimeBudget? = null,
) {
    private val manager = ownership.manager
    private val dataSource = ownership.dataSource
    private val entityManagerFactory = ownership.entityManagerFactory
    private val failure = AtomicReference<PersistencePhaseFailureCode?>()
    private val jdbcFailureObserved = AtomicBoolean()
    private val refunded = AtomicBoolean()
    private var permit: LocalPersistencePermit? = null
    private val entrySettlement = EntrySettlementBoundary()

    @Volatile private var stage = Stage.PREPARED

    @Volatile private var work: PersistenceTimeBudget? = null

    @Volatile private var emergency: PersistenceTimeBudget? = null
    private var acquisition: PersistenceLeaseCompletion? = null
    private var lease: PersistenceJdbcLease? = null
    private var connection: LeaseJdbcFacade? = null
    private var holder: ConnectionHolder? = null
    private var entityHolder: EntityManagerHolder? = null
    private var createdEntityManager: EntityManager? = null
    private var rootStatus: PersistenceManagedStatus? = null
    private var beginDispatched = false
    private var beginEnded = false
    private var acquisitionAuthority = false
    private var acquisitionSpent = false
    private var completionActive = false
    private var completionEnded = false
    private var springSettled = false
    private var sourceOperationIssued = false
    private var complaintOperationIssued = false
    private var stepUpOperationIssued = false
    private var stepUpSnapshot: StepUpUserSnapshot? = null
    private var stepUpIssuance: StepUpGrantIssuance? = null
    private var complaintConsumptionIssued = false
    private var complaintConsumption: ComplaintGrantConsumption? = null
    private val recoverySettlement = RecoverySettlementBoundary()
    private val testReserveSpend = TestReserveSpendBoundary()
    private val selectedHolder = SelectedHolderBoundary()
    private val jdbcCapabilities = JdbcCapabilityBoundary()
    internal val installationEnrollment: PersistenceInstallationEnrollment = InstallationEnrollmentBoundary()
    internal val installationSession: PersistenceInstallationSession = InstallationSessionBoundary()
    internal val installationDeletionPreflight: PersistenceInstallationDeletionPreflight = InstallationDeletionPreflightBoundary()
    internal val installationCurrentState: PersistenceInstallationCurrentState = InstallationCurrentStateBoundary()
    internal val ownerHistory: PersistenceOwnerHistory = OwnerHistoryBoundary()
    internal val ownerOperation: PersistenceOwnerOperation = OwnerOperationBoundary()
    internal val ownerDeleteAll: PersistenceOwnerDeleteAll = OwnerDeleteAllBoundary()
    internal val ownerDeleteAllVerification: PersistenceOwnerDeleteAllVerification = OwnerDeleteAllVerificationBoundary()
    private val ownerDeleteAllApplyBoundary = OwnerDeleteAllApplyBoundary()
    internal val ownerDeleteAllApply: PersistenceOwnerDeleteAllApply = ownerDeleteAllApplyBoundary
    internal val complaintDeletion: PersistenceComplaintDeletion = DeletionBoundary()
    internal val catalogSnapshot: PersistenceCatalogSnapshot = CatalogSnapshotBoundary()
    internal val catalogGenesis: PersistenceCatalogGenesisMutation = CatalogGenesisBoundary()
    internal val catalogProjectedHead: PersistenceCatalogProjectedHead = CatalogProjectedHeadBoundary()
    internal val coordinatorLease: PersistenceCoordinatorLease = CoordinatorLeaseBoundary()
    internal val epochRotation: PersistenceEpochRotationControl = EpochRotationBoundary()
    internal val cutoffPublications: PersistenceCutoffPublications = CutoffPublicationsBoundary()
    internal val desiredInstallation: PersistenceDesiredInstall = DesiredInstallationBoundary()
    internal val signedGenesisFirstDesired: PersistenceSignedGenesisFirstDesired = SignedGenesisFirstDesiredBoundary()

    // The SQL-created batch retains the private grant -> counters -> delete -> refund cursor, never a caller count or UUID.
    private var complaintBatch: ComplaintGrantCleanupBatch? = null
    private var changingReadCap = false
    private var readCapKind: PersistenceJdbcGuardCallKind? = null
    private var restoringReadCap = false
    private var originalReadCap: Int? = null
    private var restoreInterrupt = false
    private var finalizerEnded = false

    internal fun reserveComplaintClaim() = entrySettlement.reserveComplaintClaim()

    internal fun retainEntryPermit(selected: LocalPersistencePermit) = entrySettlement.retainEntryPermit(selected)

    internal fun publishEntry() = entrySettlement.publishEntry()

    internal fun begin() {
        requireCaller()
        if (stage !== Stage.PREPARED) refuse(PersistencePhaseFailureCode.MANAGER_REFUSED)
        stage = Stage.STARTING
        val definition = DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRED).apply {
            setName(path.name)
            timeout = 2
            isReadOnly = path.readOnly
            // Desired-state pending/pristine checks deliberately take a fresh statement snapshot
            // AFTER the LIVE control lock. Never inherit a role/database REPEATABLE READ default.
            if (desiredAttempt != null || firstDesiredAttempt != null) isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
        }
        manager.getTransaction(definition)
        // The wrapper retained TransactionStatus before this validation. A failure here still has rollback custody.
        val status = rootStatus ?: refuse(PersistencePhaseFailureCode.MANAGER_REFUSED)
        if (!status.hasReturnedStatus() || !status.isNewTransaction || !beginEnded) refuse(PersistencePhaseFailureCode.MANAGER_REFUSED)
        if (!TransactionSynchronizationManager.isActualTransactionActive() || !TransactionSynchronizationManager.isSynchronizationActive()) {
            refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
        holder = TransactionSynchronizationManager.getResource(dataSource) as? ConnectionHolder
            ?: refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        entityManagerFactory?.let { factory ->
            entityHolder = TransactionSynchronizationManager.getResource(factory) as? EntityManagerHolder
                ?: refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
        // Do not use DataSourceUtils or bind a replacement. JPA's existing handle may acquire lazily here.
        val selected = requireNotNull(holder).connection
        val accepted = lease ?: refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        if (selected !is LeaseJdbcFacade || !selected.matches(accepted) || acquisition?.matches(accepted) != true) {
            refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
        connection = selected
        acquisitionAuthority = false // Spent is never reset, even if Hibernate later releases its handle.
        requireWork()
        stage = Stage.SETTING_UP
        installLimits()
        if (firstDesiredAttempt != null && selected.transactionIsolation != Connection.TRANSACTION_READ_COMMITTED) {
            refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
        selectedHolder.acquireFence(selected)
        requireWork()
        stage = Stage.WORK
    }

    internal fun beforeGetTransaction(candidate: PlatformTransactionManager, definition: TransactionDefinition?): Boolean {
        requireCaller()
        if (candidate !== manager || definition?.propagationBehavior?.let { it != TransactionDefinition.PROPAGATION_REQUIRED } == true) {
            refuse(PersistencePhaseFailureCode.MANAGER_REFUSED)
        }
        if (stage === Stage.STARTING && !beginDispatched) {
            beginDispatched = true
            acquisitionAuthority = true
            return true
        }
        requireParticipation() // Before even Spring's synchronization-suspension callbacks.
        return false
    }

    internal fun prepareStatus(status: PersistenceManagedStatus) {
        requireCaller()
        if (status.root) {
            if (rootStatus != null) refuse(PersistencePhaseFailureCode.MANAGER_REFUSED)
            rootStatus = status
        }
    }

    internal fun retainEntityManager(candidate: GuardedJpaTransactionManager, entityManager: EntityManager) {
        requireCaller()
        check(candidate === manager && stage === Stage.STARTING && createdEntityManager == null)
        createdEntityManager = entityManager // Before the real dialect/transaction begin, including the no-status path.
    }

    /** The initializer frame still owns this exact EM; Spring has not received a holder or status. */
    internal fun entityManagerInitializationFailed(candidate: GuardedJpaTransactionManager, entityManager: EntityManager, problem: Throwable) {
        requireCaller()
        check(candidate === manager && createdEntityManager === entityManager && stage === Stage.STARTING && beginDispatched && !beginEnded)
        acquisitionAuthority = false // Failure cleanup must not start a fresh checkout.
        recordFailure(problem)
    }

    internal fun statusReturned(status: PersistenceManagedStatus) {
        requireCaller()
        if (status.root) {
            if (rootStatus !== status) refuse(PersistencePhaseFailureCode.MANAGER_REFUSED)
        } else if (status.isNewTransaction || status.hasSavepoint()) {
            refuse(PersistencePhaseFailureCode.MANAGER_REFUSED)
        }
    }

    internal fun getTransactionEnded(status: PersistenceManagedStatus) {
        if (status === rootStatus) beginEnded = true
    }

    // Selected resource/start stage and unspent single-borrow authority are one pre-checkout gate.
    @Suppress("ComplexCondition")
    internal fun authorizeAcquisition(dataSource: GuardedDataSource) {
        requireCaller()
        if (dataSource !== this.dataSource || stage !== Stage.STARTING || !acquisitionAuthority || acquisitionSpent) {
            refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
        acquisitionSpent = true // Consumed before either DataSource overload reaches Hikari.
    }

    internal fun retainAcquisition(completion: PersistenceLeaseCompletion) {
        requireCaller()
        check(acquisition == null && completion.phase === this)
        acquisition = completion
    }

    internal fun bindLease(completion: PersistenceLeaseCompletion, prepared: PersistenceJdbcLease) {
        requireCaller()
        check(acquisition === completion && lease == null)
        lease = prepared
        prepared.state.epoch.attachPhase(this)
        prepared.state.context.attachPhase(this)
    }

    internal fun bindFacade(prepared: PersistenceJdbcLease, facade: LeaseJdbcFacade) {
        requireCaller()
        check(lease === prepared && connection == null)
        connection = facade
    }

    internal fun leaseAccepted(completion: PersistenceLeaseCompletion) {
        requireCaller()
        check(acquisition === completion && work == null)
        // Rotation retains its pre-admission cap; ordinary phases begin after the real CHECKOUT consent, before its remaining tail.
        work =
            rotationWork ?: cutoffWork ?: catalogRefreshWork ?: desiredWork ?: firstDesiredWork ?: PersistenceTimeBudget.start(WORK_MILLIS, ownership.nanoClock)
    }

    internal fun retainedPhaseCheckoutBudget(ceilingMillis: Long): PersistenceTimeBudget? {
        requireCaller()
        return (rotationWork ?: cutoffWork ?: catalogRefreshWork ?: desiredWork ?: firstDesiredWork)?.systemCappedSnapshot(ceilingMillis)
    }

    internal fun requireAcceptedLease() = requireWork()

    internal fun requireParticipation() {
        requireCaller()
        if (stage !== Stage.WORK || !beginEnded || completionActive) refuse(PersistencePhaseFailureCode.MANAGER_REFUSED)
        requireWork()
        selectedHolder.requireCurrent()
    }

    internal fun requireSourceCleanup(jdbc: JdbcTemplate) {
        requireParticipation()
        if (path !== PersistencePhasePath.SOURCE_GRANT_CLEANUP || jdbc.dataSource !== dataSource || sourceOperationIssued) {
            refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
        sourceOperationIssued = true // One fixed batch, not repeated cleanup until exhaustion.
        installLimits()
        requireWork()
    }

    internal fun requireComplaintGrantCleanup(jdbc: JdbcTemplate) {
        if (path !== PersistencePhasePath.COMPLAINT_GRANT_CLEANUP) {
            recordFailure(PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED))
            refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
        requireParticipation()
        if (jdbc.dataSource !== dataSource || complaintOperationIssued) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        complaintOperationIssued = true
        installLimits()
        requireWork()
    }

    @Suppress("ComplexCondition")
    internal fun retainComplaintGrantBatch(batch: ComplaintGrantCleanupBatch, jdbc: JdbcTemplate) {
        requireParticipation()
        if (path !== PersistencePhasePath.COMPLAINT_GRANT_CLEANUP || !complaintOperationIssued || complaintBatch != null ||
            !batch.belongsTo(this) || jdbc.dataSource !== dataSource
        ) {
            recordFailure(PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED))
            refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
        complaintBatch = batch
    }

    internal fun requireComplaintGrantBatch(batch: ComplaintGrantCleanupBatch, jdbc: JdbcTemplate) {
        requireParticipation()
        if (path !== PersistencePhasePath.COMPLAINT_GRANT_CLEANUP || complaintBatch !== batch || jdbc.dataSource !== dataSource) {
            recordFailure(PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED))
            refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
    }

    internal fun requireStepUpSnapshot(jdbc: JdbcTemplate, scope: ScopedAdminStepUpScope) = requireStepUpOperation(jdbc, scope.snapshotPath)

    internal fun requireStepUpIssuance(jdbc: JdbcTemplate, scope: ScopedAdminStepUpScope) = requireStepUpOperation(jdbc, scope.issuancePath)

    private fun requireStepUpOperation(jdbc: JdbcTemplate, expected: PersistencePhasePath) {
        requireStepUpResource(jdbc, expected)
        if (stepUpOperationIssued) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        stepUpOperationIssued = true
        installLimits()
        requireWork()
    }

    internal fun retainStepUpSnapshot(snapshot: StepUpUserSnapshot, jdbc: JdbcTemplate) {
        requireStepUpResource(jdbc, snapshot.scope.snapshotPath)
        if (!stepUpOperationIssued || stepUpSnapshot != null) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        stepUpSnapshot = snapshot
    }

    internal fun retainStepUpIssuance(issuance: StepUpGrantIssuance, jdbc: JdbcTemplate) {
        requireStepUpResource(jdbc, issuance.scope.issuancePath)
        if (!stepUpOperationIssued || stepUpIssuance != null || !issuance.belongsTo(this)) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        stepUpIssuance = issuance
    }

    internal fun requireStepUpIssuance(issuance: StepUpGrantIssuance, jdbc: JdbcTemplate) {
        requireStepUpResource(jdbc, issuance.scope.issuancePath)
        if (stepUpIssuance !== issuance) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
    }

    internal fun requireComplaintGrantConsumption(jdbc: JdbcTemplate) {
        requireComplaintAdminResource(jdbc)
        if (complaintConsumptionIssued) refuse(PersistencePhaseFailureCode.WORK_FAILED)
        complaintConsumptionIssued = true
        installLimits()
        requireWork()
    }

    internal fun retainComplaintGrantConsumption(consumption: ComplaintGrantConsumption, jdbc: JdbcTemplate) {
        requireComplaintAdminResource(jdbc)
        if (!complaintConsumptionIssued || complaintConsumption != null || !consumption.belongsTo(this)) {
            refuse(PersistencePhaseFailureCode.WORK_FAILED)
        }
        complaintConsumption = consumption
    }

    internal fun requireComplaintGrantConsumption(consumption: ComplaintGrantConsumption, jdbc: JdbcTemplate) {
        requireComplaintAdminResource(jdbc)
        if (complaintConsumption !== consumption) refuse(PersistencePhaseFailureCode.WORK_FAILED)
    }

    private fun requireComplaintAdminResource(jdbc: JdbcTemplate) = selectedHolder.requireComplaintAdminResource(jdbc)

    /** Only this phase selects its original holder. No caller resource flag, lookup, checkout or fallback. */
    internal fun complaintAuditHolder(consumption: ComplaintGrantConsumption, jdbc: JdbcTemplate): ComplaintAuditSelectedHolder {
        requireComplaintGrantConsumption(consumption, jdbc)
        return selectedHolder.complaintAuditHolder()
    }

    internal fun requireComplaintRecoverySettlement(jdbc: JdbcTemplate) = recoverySettlement.requireOperation(jdbc)

    internal fun retainComplaintRecoverySettlement(operation: ComplaintRecoverySettlementOperation, jdbc: JdbcTemplate) =
        recoverySettlement.retain(operation, jdbc)

    internal fun requireComplaintRecoverySettlement(operation: ComplaintRecoverySettlementOperation, jdbc: JdbcTemplate) =
        recoverySettlement.requireRetained(operation, jdbc)

    internal fun checkComplaintRecoverySettlementWork(result: ComplaintRecoverySettlementResult) = recoverySettlement.checkWork(result)

    internal fun complaintRecoverySettlementResult(result: ComplaintRecoverySettlementResult?): ComplaintRecoverySettlementResult =
        recoverySettlement.result(result)

    internal fun requireComplaintTestReserveSpend(jdbc: JdbcTemplate) = testReserveSpend.requireOperation(jdbc)

    internal fun retainComplaintTestReserveSpend(operation: ComplaintTestReserveSpendOperation, jdbc: JdbcTemplate) = testReserveSpend.retain(operation, jdbc)

    internal fun requireComplaintTestReserveSpend(operation: ComplaintTestReserveSpendOperation, jdbc: JdbcTemplate) =
        testReserveSpend.requireRetained(operation, jdbc)

    internal fun checkComplaintTestReserveSpendWork(result: ComplaintTestReserveSpendResult) = testReserveSpend.checkWork(result)

    internal fun complaintTestReserveSpendResult(result: ComplaintTestReserveSpendResult?): ComplaintTestReserveSpendResult = testReserveSpend.result(result)

    private fun requireStepUpResource(jdbc: JdbcTemplate, expected: PersistencePhasePath) {
        if (path !== expected) {
            recordFailure(PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED))
            refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
        requireParticipation()
        if (jdbc.dataSource !== dataSource) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
    }

    internal fun checkStepUpSnapshotResult(snapshot: StepUpUserSnapshot) {
        if (!caller.isCurrent() || path !== snapshot.scope.snapshotPath || stepUpSnapshot !== snapshot) {
            failure.compareAndSet(null, PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
        requireSuccessfulResult()
    }

    internal fun checkStepUpIssuanceResult(issuance: StepUpGrantIssuance) {
        if (!caller.isCurrent() || stepUpIssuance !== issuance || !issuance.completedFor(this)) {
            failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
        }
        requireSuccessfulResult()
    }

    internal fun checkComplaintAuditResult(consumption: ComplaintGrantConsumption) {
        if (!caller.isCurrent() || complaintConsumption !== consumption || !consumption.completedFor(this)) {
            failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
        }
        requireSuccessfulResult()
    }

    internal fun recordNestedEntryFailure(requested: PersistencePhasePath, problem: Throwable) {
        if (path !== PersistencePhasePath.SOURCE_GRANT_CLEANUP || requested !== PersistencePhasePath.SOURCE_GRANT_CLEANUP) recordFailure(problem)
    }

    internal fun checkWorkReturned(count: Int) {
        requireParticipation()
        val completed = when (path) {
            PersistencePhasePath.SOURCE_GRANT_CLEANUP -> sourceOperationIssued
            PersistencePhasePath.COMPLAINT_GRANT_CLEANUP -> complaintBatch?.completedCount(this) == count
            else -> false // Snapshot/grant completion is bound to its exact object, never a caller count.
        }
        if (!completed || count !in 0..50) refuse(PersistencePhaseFailureCode.WORK_FAILED)
    }

    internal fun commit() {
        requireParticipation()
        // Preserve the existing source-only participation contract.
        if (!completedOperation()) refuse(PersistencePhaseFailureCode.WORK_FAILED) // No skipped check, unspent charge or incomplete insert can commit.
        stage = Stage.COMMITTING
        manager.commit(requireNotNull(rootStatus))
        if (databaseOutcome() !== PersistenceDatabaseOutcome.COMMITTED) refuse(PersistencePhaseFailureCode.COMPLETION_FAILED)
        requireWork()
    }

    // Preserve the exhaustive named-phase completion matrix; a generic callback must not replace retained operation checks.
    @Suppress("CyclomaticComplexMethod")
    private fun completedOperation(): Boolean = when (path) {
        PersistencePhasePath.SOURCE_GRANT_CLEANUP -> true

        PersistencePhasePath.COMPLAINT_GRANT_CLEANUP -> complaintBatch?.completedCount(this) != null

        PersistencePhasePath.SOURCE_STEP_UP_SNAPSHOT, PersistencePhasePath.COMPLAINT_STEP_UP_SNAPSHOT -> stepUpSnapshot != null

        PersistencePhasePath.SOURCE_STEP_UP_ISSUANCE, PersistencePhasePath.COMPLAINT_STEP_UP_ISSUANCE -> stepUpIssuance?.completedFor(this) == true

        PersistencePhasePath.COMPLAINT_ADMIN_AUDIT,
        PersistencePhasePath.COMPLAINT_DELETION_ADMIN_AUDIT,
        -> complaintConsumption?.completedFor(this) == true

        PersistencePhasePath.COMPLAINT_RECOVERY_SETTLEMENT -> recoverySettlement.completed()

        PersistencePhasePath.COMPLAINT_TEST_RESERVE_SPEND -> testReserveSpend.completed()

        PersistencePhasePath.COMPLAINT_INSTALLATION_ENROLLMENT -> installationEnrollment.completed()

        PersistencePhasePath.COMPLAINT_INSTALLATION_SESSION_PREFLIGHT,
        PersistencePhasePath.COMPLAINT_INSTALLATION_SESSION_REFRESH,
        -> installationSession.completed()

        PersistencePhasePath.COMPLAINT_INSTALLATION_DELETION_PREFLIGHT -> installationDeletionPreflight.completed()

        PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_AUTHORIZE,
        PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_RELOAD,
        -> ownerDeleteAll.completed()

        PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_VERIFY -> ownerDeleteAllVerification.completed()

        PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_APPLY -> ownerDeleteAllApply.completed()

        PersistencePhasePath.COMPLAINT_INSTALLATION_CURRENT_STATE -> installationCurrentState.completed()

        PersistencePhasePath.COMPLAINT_OWNER_HISTORY_AUTHENTICATION,
        PersistencePhasePath.COMPLAINT_OWNER_HISTORY_PAGE,
        -> ownerHistory.completed()

        PersistencePhasePath.COMPLAINT_OWNER_OPERATION_AUTHENTICATION,
        PersistencePhasePath.COMPLAINT_OWNER_CREATE_PREFLIGHT,
        PersistencePhasePath.COMPLAINT_OWNER_CREATE,
        PersistencePhasePath.COMPLAINT_OWNER_OPERATION_STATUS,
        -> ownerOperation.completed()

        PersistencePhasePath.COMPLAINT_DELETION_MUTATION -> complaintDeletion.completed()

        PersistencePhasePath.COMPLAINT_DELETION_FENCE_PREFIX -> selectedHolder.fenceAccepted()

        PersistencePhasePath.COMPLAINT_DELETION_CONTROL_SNAPSHOT -> selectedHolder.controlSnapshotCaptured()

        PersistencePhasePath.COMPLAINT_CATALOG_SNAPSHOT,
        PersistencePhasePath.COMPLAINT_CATALOG_PROJECTED_HEAD,
        -> completedCatalogRead()

        PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PREPARE,
        PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_SIGNATURE,
        PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_COMPLETE,
        PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PROJECT,
        -> catalogGenesis.completed()

        PersistencePhasePath.COMPLAINT_DESIRED_SIGNED_GENESIS_FIRST -> signedGenesisFirstDesired.completed()

        PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_ACQUIRE,
        PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_RENEW,
        PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_RELINQUISH,
        PersistencePhasePath.COMPLAINT_DESIRED_BOOTSTRAP,
        PersistencePhasePath.COMPLAINT_DESIRED_CLOSE,
        PersistencePhasePath.COMPLAINT_DESIRED_SUPERSEDE,
        -> completedControlOperation()

        PersistencePhasePath.COMPLAINT_EPOCH_ROTATION_REQUEST,
        PersistencePhasePath.COMPLAINT_EPOCH_ROTATION_RESUME,
        PersistencePhasePath.COMPLAINT_CUTOFF_CONTROL,
        PersistencePhasePath.COMPLAINT_CUTOFF_PAGE,
        PersistencePhasePath.COMPLAINT_CUTOFF_VERIFY,
        PersistencePhasePath.COMPLAINT_SEAL_PREPARE,
        -> completedRotationOrCutoff()
    }

    private fun completedControlOperation(): Boolean = when (path) {
        PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_ACQUIRE,
        PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_RENEW,
        PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_RELINQUISH,
        -> coordinatorLease.completed()

        PersistencePhasePath.COMPLAINT_DESIRED_BOOTSTRAP,
        PersistencePhasePath.COMPLAINT_DESIRED_CLOSE,
        PersistencePhasePath.COMPLAINT_DESIRED_SUPERSEDE,
        -> desiredInstallation.completed()

        else -> false
    }

    private fun completedCatalogRead(): Boolean = when (path) {
        PersistencePhasePath.COMPLAINT_CATALOG_SNAPSHOT -> catalogSnapshot.completed()
        PersistencePhasePath.COMPLAINT_CATALOG_PROJECTED_HEAD -> catalogProjectedHead.completed()
        else -> false
    }

    private fun completedRotationOrCutoff(): Boolean = when (path) {
        PersistencePhasePath.COMPLAINT_EPOCH_ROTATION_REQUEST,
        PersistencePhasePath.COMPLAINT_EPOCH_ROTATION_RESUME,
        -> epochRotation.completed()

        PersistencePhasePath.COMPLAINT_CUTOFF_CONTROL,
        PersistencePhasePath.COMPLAINT_CUTOFF_PAGE,
        PersistencePhasePath.COMPLAINT_CUTOFF_VERIFY,
        PersistencePhasePath.COMPLAINT_SEAL_PREPARE,
        -> cutoffPublications.completed()

        else -> false
    }

    // Root completion must match its commit/rollback stage with no overlapping completion dispatch.
    @Suppress("ComplexCondition")
    internal fun beforeCompletion(candidate: PlatformTransactionManager, status: PersistenceManagedStatus, commit: Boolean) {
        requireCaller()
        if (candidate !== manager || !status.hasReturnedStatus()) refuse(PersistencePhaseFailureCode.MANAGER_REFUSED)
        if (status !== rootStatus) {
            requireParticipation()
            return
        }
        if (completionActive || (commit && stage !== Stage.COMMITTING) || (!commit && stage !== Stage.ROLLING_BACK)) {
            refuse(PersistencePhaseFailureCode.MANAGER_REFUSED)
        }
        if (commit) {
            requireWork()
            selectedHolder.requireCurrent()
        } else {
            cleanupBudget()
        }
        completionActive = true
        if (commit) ownerDeleteAllApplyBoundary.commitDispatched()
    }

    internal fun completionDispatchEnded(status: PersistenceManagedStatus) {
        if (status === rootStatus) {
            completionActive = false
            completionEnded = true
        }
    }

    internal fun managerFailure(problem: Throwable) = recordFailure(problem)

    internal fun recordFailure(problem: Throwable) {
        ownerDeleteAllApply.observeFailure(problem)
        if (problem is InterruptedException) restoreInterrupt = true
        val reason = when (problem) {
            is InterruptedException -> PersistencePhaseFailureCode.INTERRUPTED
            is PersistencePhaseException -> problem.code
            else -> PersistencePhaseFailureCode.WORK_FAILED
        }
        failure.compareAndSet(null, reason) // Preserve our refusal code; DB/cleanup facts still come from this exact owner.
    }

    internal fun jdbcFailure() {
        jdbcFailureObserved.set(true)
        failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
    }

    internal fun isOriginalCaller(): Boolean = caller.isCurrent()

    /** Lower/upper dispatch uses this budget; no business worker or copied Spring context exists. */
    internal fun callBudget(kind: PersistenceJdbcGuardCallKind): PersistenceTimeBudget {
        if (kind === PersistenceJdbcGuardCallKind.CANCELLATION) {
            return emergency ?: work ?: refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
        if (kind === PersistenceJdbcGuardCallKind.BUSINESS) {
            requireWork()
            if (stage !in BUSINESS_STAGES || databaseOutcome() !== PersistenceDatabaseOutcome.NONE) {
                refuse(PersistencePhaseFailureCode.COMPLETION_FAILED)
            }
            return selectedHolder.businessBudget()
        }
        return cleanupBudget()
    }

    internal fun connectionKind(method: Method, arguments: Array<out Any?>?): PersistenceJdbcGuardCallKind = jdbcCapabilities.classify(method, arguments)

    internal fun requireDeletionFence(fence: PersistenceDeletionFence, selected: Connection) = selectedHolder.requireFence(fence, selected)

    internal fun requireDeletionControlSnapshot(snapshot: PersistenceDeletionControlSnapshot, selected: Connection) =
        selectedHolder.requireControlSnapshot(snapshot, selected)

    /** Reclips the actual JDBC read cap before every upper call, without recursion or a new time budget. */
    internal fun beforeJdbcCall(kind: PersistenceJdbcGuardCallKind) {
        if (kind === PersistenceJdbcGuardCallKind.CANCELLATION || changingReadCap || restoringReadCap) return
        val budget = callBudget(kind)
        val selected = connection ?: return
        readCapKind = kind
        changingReadCap = true
        try {
            if (originalReadCap == null) originalReadCap = selected.networkTimeout
            val ceiling = if (budget === emergency) EMERGENCY_READ_MILLIS else selectedHolder.readCeiling()
            selected.setNetworkTimeout(INLINE, budget.remainingMillis(ceiling).toInt())
        } finally {
            changingReadCap = false
            readCapKind = null
        }
    }

    internal fun afterJdbcCall(kind: PersistenceJdbcGuardCallKind) {
        if (kind === PersistenceJdbcGuardCallKind.BUSINESS) selectedHolder.afterBusinessCall()
    }

    private fun installLimits() {
        requireWork()
        val selected = connection ?: refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        selected.prepareStatement(LOCAL_LIMITS).use { statement ->
            statement.setString(1, requireNotNull(work).remainingMillis(WORK_MILLIS).toString() + "ms")
            statement.setString(2, requireNotNull(work).remainingMillis(1_000).toString() + "ms")
            statement.setString(3, requireNotNull(work).remainingMillis(1_000).toString() + "ms")
            statement.setString(4, requireNotNull(work).remainingMillis(100).toString() + "ms")
            statement.executeQuery().use { result ->
                if (!result.next() || result.next()) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
        }
        requireWork()
    }

    internal fun logicalRelease(completion: PersistenceLeaseCompletion) {
        requireCaller()
        if (completion !== acquisition) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        if (stage === Stage.WORK || stage === Stage.SETTING_UP) failure.compareAndSet(null, PersistencePhaseFailureCode.COMPLETION_FAILED)
    }

    internal fun requireFinalizer(candidate: PersistenceJdbcLease) {
        requireCaller()
        if (stage !== Stage.FINALIZING || candidate !== lease || !springSettled) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
    }

    /**
     * Caller finally owns rollback, restoration, the real return, and proof observation in that order.
     * Broad catches retain bounded failure/interrupt state through cleanup; nested restoration always resets its read-cap flag.
     */
    @Suppress("TooGenericExceptionCaught", "NestedBlockDepth", "ComplexCondition")
    internal fun finish() {
        requireCaller()
        acquisitionAuthority = false
        try {
            val status = rootStatus
            // Roll back only a returned, incomplete status after begin ends and outside any completion dispatch.
            if (status?.hasReturnedStatus() == true && !status.isCompleted && beginEnded && !completionActive) {
                stage = Stage.ROLLING_BACK
                try {
                    manager.rollback(status)
                } catch (problem: Throwable) {
                    recordFailure(problem)
                }
            }
            springSettled = springCompletionProven()
            stage = Stage.FINALIZING
            val selected = lease
            if (selected != null) {
                if (springSettled && databaseOutcome() in KNOWN_OUTCOMES && !jdbcFailureObserved.get()) {
                    try {
                        // Manager reset may have swallowed failure; lower poison/transaction evidence still gates return.
                        restoringReadCap = true
                        try {
                            originalReadCap?.let { requireNotNull(connection).setNetworkTimeout(INLINE, it) }
                        } finally {
                            restoringReadCap = false
                        }
                        selected.finishScoped(this, transferCleanupBudget())
                    } catch (problem: Throwable) {
                        recordFailure(problem)
                        selected.retireScoped(this)
                    }
                } else {
                    selected.retireScoped(this)
                }
            }
            if (acquisition?.quiescent() == false) awaitCleanup()
            deadlineExpired() // A successful but late return is still an overrun, not an on-time commit.
        } catch (problem: Throwable) {
            recordFailure(problem)
            lease?.retireScoped(this)
        } finally {
            try {
                caller.restoreAfterFailure()
                if (restoreInterrupt) Thread.currentThread().interrupt()
            } catch (_: Throwable) {
                // Discard raw restoration details, but retain unresolved custody instead of claiming settlement/refund.
                failure.set(PersistencePhaseFailureCode.CLEANUP_UNRESOLVED)
                springSettled = false
            }
            ownerDeleteAllApplyBoundary.finalizerObserved()
            finalizerEnded = true
            if (!releaseIfProven()) quarantine()
        }
    }

    // Empty Spring state alone cannot settle an unfinished dispatch or a no-status begin without exact EM custody.
    @Suppress("ComplexCondition")
    private fun springCompletionProven(): Boolean {
        if (!PersistencePhaseOwnership.springConnectionFree() || completionActive || (beginDispatched && !beginEnded)) return false
        val status = rootStatus
        if (status?.hasReturnedStatus() == true && (!status.isCompleted || !completionEnded)) return false
        if (entityManagerFactory == null) return selectedHolder.jdbcCompletionProven()
        // The private delegate retained the exact created EM before doBegin. Only actual close,
        // ended dispatch and empty Spring state settle a no-status begin; no rollback is invented.
        if (status != null && !status.hasReturnedStatus() && acquisition != null && createdEntityManager == null) return false
        return createdEntityManager?.isOpen != true && entityHolder?.entityManager?.isOpen != true
    }

    private fun awaitCleanup() {
        val budget = emergencyBudget() // One measured allowance for all failed return/terminal observation, never per close.
        while (acquisition?.quiescent() == false && persistenceFactoryRemainingMillis(budget) > 0L) {
            if (Thread.interrupted()) restoreInterrupt = true // Cleanup only; never restores business authority.
            LockSupport.parkNanos(1_000_000)
        }
    }

    internal fun reconcileQuarantine() {
        requireCaller()
        if (stage !== Stage.QUARANTINED || !finalizerEnded) return
        springSettled = springCompletionProven()
        if (!releaseIfProven()) quarantine()
    }

    private fun releaseIfProven(): Boolean = entrySettlement.releaseIfProven()

    private fun quarantine() = entrySettlement.quarantine()

    internal fun entryPublicationFailed() = entrySettlement.entryPublicationFailed()

    internal fun quarantined(): Boolean = stage === Stage.QUARANTINED

    // Complaint incidents seal only complaint admission. Source-origin local health and the shared permit budget remain authoritative.
    internal fun blocksEntry(candidate: PersistencePhasePath): Boolean = quarantined() && (!candidate.source || path.source)

    internal fun result(count: Int): Int {
        if (path === PersistencePhasePath.COMPLAINT_GRANT_CLEANUP && complaintBatch?.completedCount(this) != count) {
            failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
        }
        if (path !== PersistencePhasePath.SOURCE_GRANT_CLEANUP && path !== PersistencePhasePath.COMPLAINT_GRANT_CLEANUP) {
            failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
        }
        requireSuccessfulResult()
        return count
    }

    private fun requireSuccessfulResult() {
        val reason = failure.get()
        if (reason != null || !refunded.get() || databaseOutcome() !== PersistenceDatabaseOutcome.COMMITTED) {
            throw failureException(reason ?: PersistencePhaseFailureCode.COMPLETION_FAILED)
        }
    }

    internal fun failureException(default: PersistencePhaseFailureCode): PersistencePhaseException =
        PersistencePhaseException(failure.get() ?: default, databaseOutcome(), refunded.get())

    internal fun databaseOutcome(): PersistenceDatabaseOutcome = acquisition?.databaseOutcome() ?: PersistenceDatabaseOutcome.NONE

    /** Called outside F/G/T by the existing scanner; a later exact-epoch cut performs the retirement. */
    internal fun deadlineExpired(): Boolean {
        val selected = work ?: rotationWork ?: cutoffWork ?: catalogRefreshWork ?: desiredWork ?: firstDesiredWork ?: return false
        val expired = persistenceFactoryRemainingMillis(selected) == 0L
        if (expired) failure.compareAndSet(null, PersistencePhaseFailureCode.TIME_BUDGET_EXHAUSTED)
        return expired
    }

    private fun requireWork() {
        requireCaller()
        if (caller.sampleOutsideLocks() != null) {
            failure.compareAndSet(null, PersistencePhaseFailureCode.INTERRUPTED)
            refuse(PersistencePhaseFailureCode.INTERRUPTED)
        }
        if (work == null || deadlineExpired()) refuse(PersistencePhaseFailureCode.TIME_BUDGET_EXHAUSTED)
        if (failure.get() != null) refuse(failure.get()!!)
    }

    internal fun cleanupBudget(): PersistenceTimeBudget {
        requireCaller()
        emergency?.let { return it }
        val normal = work
        return if (normal != null && persistenceFactoryRemainingMillis(normal) > 0L) normal else emergencyBudget()
    }

    /** A rotation RETURN's protected G predicates may not invoke the original coordinator's supplied clock. */
    internal fun transferCleanupBudget(): PersistenceTimeBudget {
        val budget = cleanupBudget()
        val ordinaryBudget = rotationAttempt == null && cutoffAttempt == null && catalogRefresh == null
        return if (ordinaryBudget && desiredAttempt == null && firstDesiredAttempt == null) budget else budget.systemCappedSnapshot(WORK_MILLIS)
    }

    private fun emergencyBudget(): PersistenceTimeBudget {
        emergency?.let { return it }
        requireCaller()
        val selected =
            (rotationAttempt?.budget ?: cutoffAttempt?.budget ?: catalogRefresh?.projectedBudget ?: desiredAttempt?.budget ?: firstDesiredAttempt?.budget)
                ?.capped(EMERGENCY_MILLIS)
                ?: PersistenceTimeBudget.start(EMERGENCY_MILLIS, ownership.nanoClock)
        emergency = selected
        return selected
    }

    private fun requireCaller() {
        if (!caller.isCurrent() || PersistencePhaseOwnership.current() !== this) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
    }

    private fun refuse(code: PersistencePhaseFailureCode): Nothing {
        // New named operations are sticky even when an internal caller catches them. Preserve existing source-cleanup refusal semantics.
        if (path !== PersistencePhasePath.SOURCE_GRANT_CLEANUP) {
            failure.compareAndSet(null, code)
            PersistencePhaseOwnership.current()?.takeIf { it !== this }?.recordFailure(PersistencePhaseException(code))
        }
        throw failureException(code)
    }

    override fun toString(): String = "PersistencePhaseContext(${path.name})"

    /** Entry and settlement bookkeeping over this exact phase, with no copied cleanup state or independent owner. */
    private inner class EntrySettlementBoundary {
        private var complaintClaim: PersistenceComplaintContainment.Claim? = null
        private var ownershipSlotDetached = false

        fun reserveComplaintClaim() {
            requireCaller()
            check(stage === Stage.PREPARED && complaintClaim == null && permit == null)
            complaintClaim = dataSource.complaintContainment.reserve(this@PersistencePhaseContext, caller)
        }

        fun retainEntryPermit(selected: LocalPersistencePermit) {
            requireCaller()
            check(stage === Stage.PREPARED && permit == null)
            permit = selected // Retain before any following entry bookkeeping can fail.
            complaintClaim?.bind(this@PersistencePhaseContext, selected)
        }

        fun publishEntry() {
            requireCaller()
            check(permit != null)
            complaintClaim?.publish(this@PersistencePhaseContext)
        }

        @Suppress("TooGenericExceptionCaught")
        fun releaseIfProven(): Boolean {
            if (refunded.get()) return true
            if (!finalizerEnded || !springSettled || acquisition?.quiescent() == false) return false
            return try {
                permit?.let { selected ->
                    if (!selected.releaseCompleted()) check(selected.releaseAfterQuiescence())
                    check(selected.releaseCompleted()) // Initial release CAS and owner-count decrement are not this receipt.
                }
                PersistencePhaseOwnership.reconcileLoans()
                if (!ownershipSlotDetached) {
                    ownership.detach(this@PersistencePhaseContext, slot)
                    ownershipSlotDetached = true // Exact completed detach receipt, never inferred from an empty/reused slot.
                }
                ownership.clearCaller(this@PersistencePhaseContext)
                // Last fallible bookkeeping; no callback follows root clear.
                complaintClaim?.let { check(it.clearAfterCleanup(this@PersistencePhaseContext)) }
                refunded.set(true)
                stage = Stage.CLOSED
                true
            } catch (_: Throwable) {
                // The release callback is never retried if claimed but unfinished/failed. Keep the original recovery path.
                ownership.retainCallerForRecovery(this@PersistencePhaseContext)
                false
            }
        }

        fun quarantine() {
            failure.set(PersistencePhaseFailureCode.CLEANUP_UNRESOLVED)
            complaintClaim?.seal(this@PersistencePhaseContext)
            stage = Stage.QUARANTINED // Retain exact phase/permit/holder/lease; an incident, not a lifetime PASS.
        }

        fun entryPublicationFailed() {
            // No manager/checkout was entered. This is genuinely unused, not an invented lease receipt.
            springSettled = springCompletionProven()
            finalizerEnded = true
            if (!releaseIfProven()) {
                quarantine()
                throw failureException(PersistencePhaseFailureCode.CLEANUP_UNRESOLVED)
            }
        }
    }

    /** Closed JDBC capability selection; reads the same enclosing phase without a copied state snapshot. */
    private inner class JdbcCapabilityBoundary {
        // Keep the closed JDBC capability table and exact rollback-stage conjunction together; no fallback grants cleanup authority.
        @Suppress("CyclomaticComplexMethod", "ComplexCondition")
        fun classify(method: Method, arguments: Array<out Any?>?): PersistenceJdbcGuardCallKind {
            requireCaller()
            if (changingReadCap && method.name in READ_CAP_METHODS) return requireNotNull(readCapKind)
            val completion = stage === Stage.ROLLING_BACK || stage === Stage.FINALIZING || stage === Stage.COMMITTING ||
                (stage === Stage.STARTING && databaseOutcome() in KNOWN_OUTCOMES)
            return when (method.name) {
                "commit" -> {
                    if (stage !== Stage.COMMITTING || !completionActive) refuse(PersistencePhaseFailureCode.MANAGER_REFUSED)
                    PersistenceJdbcGuardCallKind.BUSINESS
                }

                "rollback" -> {
                    if (arguments?.isNotEmpty() == true ||
                        !((completionActive && stage in setOf(Stage.COMMITTING, Stage.ROLLING_BACK)) || (stage === Stage.STARTING && !beginEnded))
                    ) {
                        refuse(PersistencePhaseFailureCode.MANAGER_REFUSED)
                    }
                    PersistenceJdbcGuardCallKind.CLEANUP
                }

                "setSavepoint", "releaseSavepoint" -> refuse(PersistencePhaseFailureCode.MANAGER_REFUSED)

                "setNetworkTimeout" -> {
                    if (!changingReadCap && !restoringReadCap) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
                    if (completion) PersistenceJdbcGuardCallKind.CLEANUP else PersistenceJdbcGuardCallKind.BUSINESS
                }

                "setAutoCommit" -> {
                    if (arguments?.singleOrNull() == false) {
                        if (stage !== Stage.STARTING || !acquisitionAuthority) refuse(PersistencePhaseFailureCode.MANAGER_REFUSED)
                        PersistenceJdbcGuardCallKind.BUSINESS
                    } else {
                        if (!completion || databaseOutcome() !in KNOWN_OUTCOMES) refuse(PersistencePhaseFailureCode.COMPLETION_FAILED)
                        PersistenceJdbcGuardCallKind.CLEANUP
                    }
                }

                in COMPLETION_LOCAL_METHODS -> if (completion) PersistenceJdbcGuardCallKind.CLEANUP else PersistenceJdbcGuardCallKind.BUSINESS

                else -> PersistenceJdbcGuardCallKind.BUSINESS
            }
        }
    }

    /** Read-only guard view of this phase's retained resources, not another owner or finalizer. */
    private inner class SelectedHolderBoundary {
        private val deletionFence = when (path) {
            PersistencePhasePath.COMPLAINT_DELETION_FENCE_PREFIX,
            PersistencePhasePath.COMPLAINT_DELETION_CONTROL_SNAPSHOT,
            PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_AUTHORIZE,
            PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_RELOAD,
            PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_APPLY,
            PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PREPARE,
            PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_SIGNATURE,
            PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_COMPLETE,
            PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PROJECT,
            PersistencePhasePath.COMPLAINT_CATALOG_PROJECTED_HEAD,
            PersistencePhasePath.COMPLAINT_DESIRED_SIGNED_GENESIS_FIRST,
            -> PersistenceDeletionFence(this@PersistencePhaseContext, ownership.nanoClock)

            else -> null
        }
        private val deletionControlSnapshot = if (path === PersistencePhasePath.COMPLAINT_DELETION_CONTROL_SNAPSHOT) {
            PersistenceDeletionControlSnapshot(this@PersistencePhaseContext, checkNotNull(deletionScope))
        } else {
            null
        }
        private var fenceLimitsRestored = false

        fun acquireFence(selected: Connection) {
            deletionFence?.let {
                it.acquire(selected, requireNotNull(work))
                installLimits() // Same remaining phase budget, only after an on-time true; never a new two-second allowance.
                fenceLimitsRestored = true
                deletionControlSnapshot?.capture(selected)
            }
        }

        fun requireFence(fence: PersistenceDeletionFence, selected: Connection) {
            requireCaller()
            if (stage !== Stage.SETTING_UP || deletionFence !== fence || connection !== selected) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            requireCurrent()
            requireWork()
        }

        fun fenceAccepted(): Boolean = deletionFence?.accepted() == true

        fun fenceReady(): Boolean = fenceAccepted() && fenceLimitsRestored

        fun requireControlSnapshot(snapshot: PersistenceDeletionControlSnapshot, selected: Connection) {
            requireCaller()
            if (stage !== Stage.SETTING_UP || deletionControlSnapshot !== snapshot || connection !== selected) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            if (!fenceAccepted() || !fenceLimitsRestored) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            requireCurrent()
            requireWork()
        }

        fun controlSnapshotCaptured(): Boolean = fenceAccepted() && fenceLimitsRestored && deletionControlSnapshot?.captured() == true

        fun businessBudget(): PersistenceTimeBudget {
            if (deletionFence?.active() == true || deletionControlSnapshot?.active() == true) requireCurrent()
            return deletionFence?.callBudget(requireNotNull(work)) ?: requireNotNull(work)
        }

        fun readCeiling(): Long = deletionFence?.readCeiling(NORMAL_READ_MILLIS) ?: NORMAL_READ_MILLIS

        fun afterBusinessCall() {
            requireWork()
            deletionFence?.requireRemaining()
        }

        // Short-circuit Spring-state/holder identity checks before consulting the retained holder's connection.
        fun requireCurrent() {
            if (!TransactionSynchronizationManager.isActualTransactionActive() || !TransactionSynchronizationManager.isSynchronizationActive() ||
                TransactionSynchronizationManager.getResource(dataSource) !== holder
            ) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            if (entityManagerFactory == null) {
                if (entityHolder != null || createdEntityManager != null || TransactionSynchronizationManager.getResourceMap().size != 1) {
                    refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
                }
            } else {
                if (entityHolder == null || TransactionSynchronizationManager.getResource(entityManagerFactory) !== entityHolder ||
                    entityHolder?.entityManager !== createdEntityManager
                ) {
                    refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
                }
            }
            if (holder == null || holder?.connection !== connection) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            val selected = lease ?: refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            if (connection?.matches(selected) != true || acquisition?.matches(selected) != true) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            selected.requireBusiness()
        }

        fun requireComplaintAdminResource(jdbc: JdbcTemplate) {
            if (path !== PersistencePhasePath.COMPLAINT_ADMIN_AUDIT && path !== PersistencePhasePath.COMPLAINT_DELETION_ADMIN_AUDIT) {
                recordFailure(PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED))
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            requireStepUpResource(jdbc, path)
        }

        fun complaintAuditHolder(): ComplaintAuditSelectedHolder = when (path) {
            PersistencePhasePath.COMPLAINT_ADMIN_AUDIT ->
                ComplaintAuditSelectedHolder.Ordinary(createdEntityManager ?: refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED))

            PersistencePhasePath.COMPLAINT_DELETION_ADMIN_AUDIT -> {
                if (entityManagerFactory != null) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
                ComplaintAuditSelectedHolder.Deletion(connection ?: refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED))
            }

            else -> refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }

        fun jdbcCompletionProven(): Boolean {
            if (createdEntityManager != null || entityHolder != null) return false
            // A JDBC no-status begin needs its actual close or terminal custody. No absent-EM shortcut.
            val retained = acquisition ?: return true
            return retained.logicallyReleased() || retained.quiescent()
        }
    }

    /** One fixed G1 operation; the same actual phase retains its fence, slot, holder, commit and cleanup. */
    private inner class CatalogGenesisBoundary : PersistenceCatalogGenesisMutation {
        private var issued = false
        private var retained: CatalogGenesisMutationOperation? = null

        override fun requireOperation(jdbc: JdbcTemplate, path: PersistencePhasePath) {
            if (path !in setOf(
                    PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PREPARE,
                    PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_SIGNATURE,
                    PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_COMPLETE,
                    PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PROJECT,
                )
            ) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            requireStepUpResource(jdbc, path)
            if (issued || !selectedHolder.fenceReady()) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            issued = true
            installLimits()
            requireWork()
        }

        override fun retain(operation: CatalogGenesisMutationOperation, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (!issued || retained != null || !operation.belongsTo(this@PersistencePhaseContext, path)) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            retained = operation
        }

        override fun requireRetained(operation: CatalogGenesisMutationOperation, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (retained !== operation) refuse(PersistencePhaseFailureCode.WORK_FAILED)
        }

        override fun requireCommitted(operation: CatalogGenesisMutationOperation) {
            if (!caller.isCurrent() || retained !== operation || !operation.completedFor(this@PersistencePhaseContext)) {
                failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            }
            requireSuccessfulResult()
        }

        /** Resource identity only, usable after the separate committed/released proof as well as during work. */
        override fun requireProcessBinding(binding: CatalogGenesisInitialLiveBinding, jdbc: JdbcTemplate) = binding.requirePersistence(ownership, jdbc)

        override fun completed(): Boolean = retained?.completedFor(this@PersistencePhaseContext) == true
    }

    /** Fixed projected-head history read: same original refresh, shared fence, phase and released transaction product. */
    private inner class CatalogProjectedHeadBoundary : PersistenceCatalogProjectedHead {
        private var issued = false
        private var retained: CatalogProjectedHeadReadOperationV1? = null

        override fun requireOperation(input: CatalogProjectedHeadInputV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, PersistencePhasePath.COMPLAINT_CATALOG_PROJECTED_HEAD)
            if (issued || input.attempt !== catalogRefresh || !selectedHolder.fenceReady()) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            input.requirePersistence(ownership, jdbc)
            issued = true
            installLimits()
            requireWork()
        }

        override fun retain(operation: CatalogProjectedHeadReadOperationV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, PersistencePhasePath.COMPLAINT_CATALOG_PROJECTED_HEAD)
            if (!issued || retained != null) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            if (operation.input.attempt !== catalogRefresh || !operation.belongsTo(this@PersistencePhaseContext)) {
                refuse(PersistencePhaseFailureCode.WORK_FAILED)
            }
            retained = operation
        }

        override fun requireRetained(operation: CatalogProjectedHeadReadOperationV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, PersistencePhasePath.COMPLAINT_CATALOG_PROJECTED_HEAD)
            if (retained !== operation || operation.input.attempt !== catalogRefresh) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            operation.input.requirePersistence(ownership, jdbc)
        }

        override fun requireCommitted(operation: CatalogProjectedHeadReadOperationV1) {
            if (!caller.isCurrent() || retained !== operation || !operation.completedFor(this@PersistencePhaseContext)) {
                failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            }
            requireSuccessfulResult()
        }

        override fun completed(): Boolean = retained?.let { it.input.attempt === catalogRefresh && it.completedFor(this@PersistencePhaseContext) } == true
    }

    /** Row-only lease phases: no fence is acquired or required, so renewal remains independent of epoch rotation. */
    private inner class CoordinatorLeaseBoundary : PersistenceCoordinatorLease {
        private var issued = false
        private var retained: CatalogCoordinatorLeaseOperation? = null

        override fun requireOperation(jdbc: JdbcTemplate, path: PersistencePhasePath) {
            if (path !in setOf(
                    PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_ACQUIRE,
                    PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_RENEW,
                    PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_RELINQUISH,
                )
            ) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            requireStepUpResource(jdbc, path)
            if (issued) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            issued = true
            installLimits()
            requireWork()
        }

        override fun retain(operation: CatalogCoordinatorLeaseOperation, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (!issued || retained != null || !operation.belongsTo(this@PersistencePhaseContext, path)) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            retained = operation
        }

        override fun requireRetained(operation: CatalogCoordinatorLeaseOperation, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (retained !== operation) refuse(PersistencePhaseFailureCode.WORK_FAILED)
        }

        override fun requireCommitted(operation: CatalogCoordinatorLeaseOperation) {
            if (!caller.isCurrent() || retained !== operation || !operation.completedFor(this@PersistencePhaseContext)) {
                failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            }
            requireSuccessfulResult() // Includes actual released permit/holder proof, not merely COMMITTED.
        }

        override fun requireProcessBinding(binding: CatalogCoordinatorLeaseBindingV1, jdbc: JdbcTemplate) = binding.requirePersistence(ownership, jdbc)

        override fun completed(): Boolean = retained?.completedFor(this@PersistencePhaseContext) == true
    }

    /** Control-only request/discovery: commit and release this holder before the distinct exclusive session may start. */
    private inner class EpochRotationBoundary : PersistenceEpochRotationControl {
        private var issued = false
        private var retained: CatalogEpochRotationControlOperation? = null

        override fun requireOperation(jdbc: JdbcTemplate, path: PersistencePhasePath) {
            if (path !== PersistencePhasePath.COMPLAINT_EPOCH_ROTATION_REQUEST && path !== PersistencePhasePath.COMPLAINT_EPOCH_ROTATION_RESUME) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            requireStepUpResource(jdbc, path)
            val attempt = rotationAttempt ?: refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            attempt.requirePersistence(ownership, jdbc)
            if (issued) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            issued = true
            installLimits()
            requireWork()
        }

        override fun retain(operation: CatalogEpochRotationControlOperation, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (!issued || retained != null || !belongsToAttempt(operation)) {
                refuse(PersistencePhaseFailureCode.WORK_FAILED)
            }
            retained = operation
        }

        override fun requireRetained(operation: CatalogEpochRotationControlOperation, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (!retainsOperation(operation)) refuse(PersistencePhaseFailureCode.WORK_FAILED)
        }

        override fun requireCommitted(operation: CatalogEpochRotationControlOperation) {
            if (!caller.isCurrent() || !retainsOperation(operation) || !operation.completedFor(this@PersistencePhaseContext)) {
                failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            }
            requireSuccessfulResult() // Actual same-phase commit, holder release and completed permit refund.
        }

        override fun requireProcessBinding(attempt: CatalogEpochRotationAttemptV1, jdbc: JdbcTemplate) {
            if (rotationAttempt !== attempt) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            attempt.requirePersistence(ownership, jdbc)
        }

        private fun belongsToAttempt(operation: CatalogEpochRotationControlOperation): Boolean =
            operation.attempt === rotationAttempt && operation.belongsTo(this@PersistencePhaseContext, path)

        private fun retainsOperation(operation: CatalogEpochRotationControlOperation): Boolean = retained === operation && operation.attempt === rotationAttempt

        override fun completed(): Boolean = retained?.let { it.attempt === rotationAttempt && it.completedFor(this@PersistencePhaseContext) } == true
    }

    /** Closed control/canonical-prepare/page/immutable-evidence operations; no supplied SQL, callback or authority flag. */
    private inner class CutoffPublicationsBoundary : PersistenceCutoffPublications {
        private var issued = false
        private var retained: CatalogCutoffPersistenceOperationV1? = null

        override fun requireOperation(jdbc: JdbcTemplate, selectedPath: PersistencePhasePath) {
            if (!CatalogCutoffPersistenceOperationV1.supports(selectedPath)) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            requireStepUpResource(jdbc, selectedPath)
            val attempt = cutoffAttempt ?: refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            attempt.requirePersistence(ownership, jdbc, selectedPath)
            if (issued) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            issued = true
            installLimits()
            requireWork()
        }

        override fun retain(operation: CatalogCutoffPersistenceOperationV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (!issued || retained != null || !belongsToAttempt(operation)) {
                refuse(PersistencePhaseFailureCode.WORK_FAILED)
            }
            retained = operation
        }

        override fun requireRetained(operation: CatalogCutoffPersistenceOperationV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (retained !== operation || operation.attempt !== cutoffAttempt) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            operation.attempt.requirePersistence(ownership, jdbc, path)
        }

        override fun requireCommitted(operation: CatalogCutoffPersistenceOperationV1) {
            if (!caller.isCurrent() || retained !== operation || !operation.completedFor(this@PersistencePhaseContext)) {
                failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            }
            requireSuccessfulResult() // BOTH actual COMMITTED and released/refunded original holder, never a supplied boolean.
        }

        private fun belongsToAttempt(operation: CatalogCutoffPersistenceOperationV1): Boolean =
            operation.attempt === cutoffAttempt && operation.belongsTo(this@PersistencePhaseContext, path)

        override fun completed(): Boolean = retained?.completedFor(this@PersistencePhaseContext) == true
    }

    /** Fixed operator/control-only work. The original attempt, exact operation and released phase cannot be replaced. */
    private inner class DesiredInstallationBoundary : PersistenceDesiredInstall {
        private var issued = false
        private var retained: ComplaintDesiredInstallOperationV1? = null

        override fun requireOperation(jdbc: JdbcTemplate, path: PersistencePhasePath) {
            if (path !in setOf(
                    PersistencePhasePath.COMPLAINT_DESIRED_BOOTSTRAP,
                    PersistencePhasePath.COMPLAINT_DESIRED_CLOSE,
                    PersistencePhasePath.COMPLAINT_DESIRED_SUPERSEDE,
                )
            ) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            requireStepUpResource(jdbc, path)
            val attempt = desiredAttempt ?: refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            attempt.requirePersistence(ownership, jdbc)
            if (issued || attempt.path !== path) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            issued = true
            installLimits()
            requireWork()
        }

        override fun retain(operation: ComplaintDesiredInstallOperationV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (!issued || retained != null || operation.attempt !== desiredAttempt) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            if (!operation.belongsTo(this@PersistencePhaseContext, path)) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            retained = operation
        }

        override fun requireRetained(operation: ComplaintDesiredInstallOperationV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (retained !== operation || operation.attempt !== desiredAttempt) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            operation.attempt.requirePersistence(ownership, jdbc)
        }

        override fun requireCommitted(operation: ComplaintDesiredInstallOperationV1) {
            val retainedByCaller = caller.isCurrent() && retained === operation && operation.attempt === desiredAttempt
            if (!retainedByCaller || !operation.completedFor(this@PersistencePhaseContext)) {
                failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            }
            requireSuccessfulResult() // Actual known COMMITTED + released holder/permit; never a supplied success/cleanup flag.
        }

        override fun completed(): Boolean = retained?.let { it.attempt === desiredAttempt && it.completedFor(this@PersistencePhaseContext) } == true
    }

    /** Dedicated first-D: genuine fence + exact original attempt/operation + known commit and complete release. */
    private inner class SignedGenesisFirstDesiredBoundary : PersistenceSignedGenesisFirstDesired {
        private var issued = false
        private var retained: ComplaintSignedGenesisFirstDOperationV1? = null

        override fun requireOperation(jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, PersistencePhasePath.COMPLAINT_DESIRED_SIGNED_GENESIS_FIRST)
            val attempt = firstDesiredAttempt ?: refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            attempt.requirePersistence(ownership, jdbc)
            if (issued || !selectedHolder.fenceReady()) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            issued = true
            installLimits()
            requireWork()
        }

        override fun retain(operation: ComplaintSignedGenesisFirstDOperationV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, PersistencePhasePath.COMPLAINT_DESIRED_SIGNED_GENESIS_FIRST)
            if (!issued || retained != null || operation.attempt !== firstDesiredAttempt) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            if (!operation.belongsTo(this@PersistencePhaseContext)) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            retained = operation
        }

        override fun requireRetained(operation: ComplaintSignedGenesisFirstDOperationV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, PersistencePhasePath.COMPLAINT_DESIRED_SIGNED_GENESIS_FIRST)
            if (retained !== operation || operation.attempt !== firstDesiredAttempt || !selectedHolder.fenceReady()) {
                refuse(PersistencePhaseFailureCode.WORK_FAILED)
            }
            operation.attempt.requirePersistence(ownership, jdbc)
        }

        override fun requireCommitted(operation: ComplaintSignedGenesisFirstDOperationV1) {
            val retainedByCaller = caller.isCurrent() && retained === operation && operation.attempt === firstDesiredAttempt
            if (!retainedByCaller || !operation.completedFor(this@PersistencePhaseContext)) {
                failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            }
            requireSuccessfulResult()
        }

        override fun completed(): Boolean = retained?.let { it.attempt === firstDesiredAttempt && it.completedFor(this@PersistencePhaseContext) } == true
    }

    /** Four fixed operations; a write has a mandatory one-use ingress handoff, never a raw bypass. */
    private inner class OwnerOperationBoundary : PersistenceOwnerOperation {
        private var issued = false
        private var retained: ComplaintOwnerCreateOperation? = null
        private val admissionIdentity = Any()
        private var admission: ComplaintAdmittedOwnerCreate? = null
        private var claimed = false
        private var boundsChecked = false

        override fun bindCreate(handoff: ComplaintAdmittedOwnerCreate) {
            requireCaller()
            if (stage !== Stage.PREPARED || path !== PersistencePhasePath.COMPLAINT_OWNER_CREATE ||
                admission != null
            ) {
                refuse(PersistencePhaseFailureCode.WORK_FAILED)
            }
            admission = handoff
            ComplaintIngressAdmission.bindOwnerCreate(handoff, admissionIdentity)
        }

        override fun requireOperation(jdbc: JdbcTemplate, expected: PersistencePhasePath) {
            if (expected !in setOf(
                    PersistencePhasePath.COMPLAINT_OWNER_OPERATION_AUTHENTICATION,
                    PersistencePhasePath.COMPLAINT_OWNER_CREATE_PREFLIGHT,
                    PersistencePhasePath.COMPLAINT_OWNER_CREATE,
                    PersistencePhasePath.COMPLAINT_OWNER_OPERATION_STATUS,
                )
            ) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            requireStepUpResource(jdbc, expected)
            if (issued || (path === PersistencePhasePath.COMPLAINT_OWNER_CREATE && admission == null)) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            issued = true
            installLimits()
            requireWork()
        }

        override fun retain(operation: ComplaintOwnerCreateOperation, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (!issued || retained != null || !operation.belongsTo(this@PersistencePhaseContext, path)) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            retained = operation
        }

        override fun requireRetained(operation: ComplaintOwnerCreateOperation, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (retained !== operation) refuse(PersistencePhaseFailureCode.WORK_FAILED)
        }

        override fun claimCreate(operation: ComplaintOwnerCreateOperation, jdbc: JdbcTemplate, tuple: ComplaintOwnerOperationTuple) {
            requireRetained(operation, jdbc)
            if (path !== PersistencePhasePath.COMPLAINT_OWNER_CREATE || claimed) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            ComplaintIngressAdmission.claimOwnerCreate(admission ?: refuse(PersistencePhaseFailureCode.WORK_FAILED), admissionIdentity, tuple)
            claimed = true
        }

        override fun checkCreateBounds(operation: ComplaintOwnerCreateOperation, jdbc: JdbcTemplate, ledger: ComplaintCapacityLedger) {
            requireRetained(operation, jdbc)
            if (!claimed || boundsChecked) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            ComplaintIngressAdmission.checkOwnerCreateBounds(admission ?: refuse(PersistencePhaseFailureCode.WORK_FAILED), admissionIdentity, ledger)
            boundsChecked = true
        }

        override fun checkCreateWrite(operation: ComplaintOwnerCreateOperation, jdbc: JdbcTemplate) {
            requireRetained(operation, jdbc)
            if (!claimed || !boundsChecked) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            ComplaintIngressAdmission.checkOwnerCreateWrite(admission ?: refuse(PersistencePhaseFailureCode.WORK_FAILED), admissionIdentity)
        }

        override fun entityManager(operation: ComplaintOwnerCreateOperation, jdbc: JdbcTemplate): EntityManager {
            checkCreateWrite(operation, jdbc)
            return createdEntityManager ?: refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }

        override fun requireCommitted(operation: ComplaintOwnerCreateOperation) {
            if (!caller.isCurrent() || retained !== operation || !completed()) failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            requireSuccessfulResult()
        }

        override fun completed(): Boolean = retained?.completedFor(this@PersistencePhaseContext, path) == true &&
            (path !== PersistencePhasePath.COMPLAINT_OWNER_CREATE || claimed)
    }

    /** Fixed fenced LIVE authorizer/reloader; no callback or caller-minted result can complete it. */
    private inner class OwnerDeleteAllBoundary : PersistenceOwnerDeleteAll {
        private var issued = false
        private var retained: ComplaintOwnerDeleteAllOperation? = null
        private var admission: ComplaintAdmittedOwnerDeleteAll? = null
        private val admissionIdentity = Any()
        private var claimed = false
        private var boundsChecked = false

        override fun bindAuthorize(handoff: ComplaintAdmittedOwnerDeleteAll) {
            requireCaller()
            if (stage !== Stage.PREPARED || path !== PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_AUTHORIZE || admission != null) {
                refuse(PersistencePhaseFailureCode.WORK_FAILED)
            }
            admission = handoff
            ComplaintIngressAdmission.bindOwnerDeleteAll(handoff, admissionIdentity)
        }

        override fun requireOperation(jdbc: JdbcTemplate, expected: PersistencePhasePath) {
            if (expected !== PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_AUTHORIZE && expected !== PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_RELOAD) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            requireStepUpResource(jdbc, expected)
            if (issued || entityManagerFactory != null || !selectedHolder.fenceReady()) {
                refuse(PersistencePhaseFailureCode.WORK_FAILED)
            }
            if (path === PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_AUTHORIZE && admission == null) {
                refuse(PersistencePhaseFailureCode.WORK_FAILED)
            }
            issued = true
            installLimits()
            requireWork()
        }

        override fun retain(operation: ComplaintOwnerDeleteAllOperation, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (!issued || retained != null || !operation.belongsTo(this@PersistencePhaseContext, path)) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            retained = operation
        }

        override fun requireRetained(operation: ComplaintOwnerDeleteAllOperation, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (retained !== operation || !selectedHolder.fenceReady()) refuse(PersistencePhaseFailureCode.WORK_FAILED)
        }

        override fun claimAuthorize(operation: ComplaintOwnerDeleteAllOperation, jdbc: JdbcTemplate, tuple: InstallationDeletionPreflightTuple) {
            requireRetained(operation, jdbc)
            if (path !== PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_AUTHORIZE || claimed) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            ComplaintIngressAdmission.claimOwnerDeleteAll(admission ?: refuse(PersistencePhaseFailureCode.WORK_FAILED), admissionIdentity, tuple)
            claimed = true
        }

        override fun checkReceiptWrite(operation: ComplaintOwnerDeleteAllOperation, jdbc: JdbcTemplate) {
            requireRetained(operation, jdbc)
            if (!claimed) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            ComplaintIngressAdmission.checkOwnerDeleteAllReceipt(admission ?: refuse(PersistencePhaseFailureCode.WORK_FAILED), admissionIdentity)
        }

        override fun checkCapacity(operation: ComplaintOwnerDeleteAllOperation, jdbc: JdbcTemplate, ledger: ComplaintCapacityLedger) {
            requireRetained(operation, jdbc)
            if (boundsChecked) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            if (path === PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_AUTHORIZE) {
                if (!claimed) refuse(PersistencePhaseFailureCode.WORK_FAILED)
                ComplaintIngressAdmission.checkOwnerDeleteAllBounds(admission ?: refuse(PersistencePhaseFailureCode.WORK_FAILED), admissionIdentity, ledger)
            }
            boundsChecked = true
        }

        override fun checkWrite(operation: ComplaintOwnerDeleteAllOperation, jdbc: JdbcTemplate) {
            requireRetained(operation, jdbc)
            if (!claimed || !boundsChecked ||
                path !== PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_AUTHORIZE
            ) {
                refuse(PersistencePhaseFailureCode.WORK_FAILED)
            }
            ComplaintIngressAdmission.checkOwnerDeleteAllWrite(admission ?: refuse(PersistencePhaseFailureCode.WORK_FAILED), admissionIdentity)
        }

        override fun connection(operation: ComplaintOwnerDeleteAllOperation, jdbc: JdbcTemplate): Connection {
            requireRetained(operation, jdbc)
            return this@PersistencePhaseContext.connection ?: refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }

        override fun requireCommitted(operation: ComplaintOwnerDeleteAllOperation) {
            if (!caller.isCurrent() || retained !== operation || !completed()) failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            requireSuccessfulResult()
        }

        override fun completed(): Boolean = retained?.completedFor(this@PersistencePhaseContext, path) == true && boundsChecked &&
            (path === PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_RELOAD || claimed)
    }

    /** Fixed fenced privacy continuation; its operation must prove every mutation and remaining promise. */
    private inner class OwnerDeleteAllApplyBoundary : PersistenceOwnerDeleteAllApply {
        private var issued = false
        private var boundsChecked = false
        private var retained: ComplaintOwnerDeleteAllApplyOperation? = null
        private var commitDispatchObserved = false
        private var unconfirmedCompletion = false
        private val signalVeto = AtomicBoolean()

        fun commitDispatched() {
            if (path === PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_APPLY) commitDispatchObserved = true
        }

        override fun observeFailure(problem: Throwable) {
            if (path !== PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_APPLY) return
            if (problem is Error || problem is CancellationException || problem is InterruptedException) {
                signalVeto.set(true)
            }
        }

        fun finalizerObserved() {
            // Snapshot only the real attempt's failure, before refund/clear. A later diagnostic
            // or wrong-caller result access cannot turn a successfully finished APPLY into pending.
            unconfirmedCompletion = commitDispatchObserved && failure.get() != null
        }

        override fun requireOperation(jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_APPLY)
            if (issued || entityManagerFactory != null || !selectedHolder.fenceReady()) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            issued = true
            installLimits()
            requireWork()
        }

        override fun retain(operation: ComplaintOwnerDeleteAllApplyOperation, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_APPLY)
            if (!issued || retained != null || !operation.belongsTo(this@PersistencePhaseContext)) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            retained = operation
        }

        override fun requireRetained(operation: ComplaintOwnerDeleteAllApplyOperation, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_APPLY)
            if (retained !== operation || !selectedHolder.fenceReady()) refuse(PersistencePhaseFailureCode.WORK_FAILED)
        }

        override fun checkCapacity(operation: ComplaintOwnerDeleteAllApplyOperation, jdbc: JdbcTemplate) {
            requireRetained(operation, jdbc)
            if (boundsChecked) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            boundsChecked = true
        }

        override fun checkWrite(operation: ComplaintOwnerDeleteAllApplyOperation, jdbc: JdbcTemplate) {
            requireRetained(operation, jdbc)
            if (!boundsChecked) refuse(PersistencePhaseFailureCode.WORK_FAILED)
        }

        override fun connection(operation: ComplaintOwnerDeleteAllApplyOperation, jdbc: JdbcTemplate): Connection {
            checkWrite(operation, jdbc)
            return this@PersistencePhaseContext.connection ?: refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }

        override fun requireCommitted(operation: ComplaintOwnerDeleteAllApplyOperation) {
            if (!caller.isCurrent() || retained !== operation || !completed()) failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            requireSuccessfulResult()
        }

        override fun reconciliationPending(operation: ComplaintOwnerDeleteAllApplyOperation): Boolean {
            val exactCompleted = caller.isCurrent() && path === PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_APPLY &&
                retained === operation && operation.completedFor(this@PersistencePhaseContext) && boundsChecked
            if (!exactCompleted) {
                failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
                throw failureException(PersistencePhaseFailureCode.WORK_FAILED)
            }
            requireNoSignal()
            if (!unconfirmedCompletion) {
                requireSuccessfulResult()
                return false
            }
            // These are original owner facts, not PersistencePhaseException's caller-constructible
            // cleanup/outcome fields. A quarantined attempt stays a failure even if later reconciled.
            val originalCleanup = stage === Stage.CLOSED && finalizerEnded && springSettled && refunded.get() &&
                acquisition?.quiescent() == true && !completionActive && completionEnded
            if (!originalCleanup || failure.get() === PersistencePhaseFailureCode.CLEANUP_UNRESOLVED ||
                databaseOutcome() === PersistenceDatabaseOutcome.NONE
            ) {
                throw failureException(PersistencePhaseFailureCode.COMPLETION_FAILED)
            }
            observeFinalCallerSignal()
            requireNoSignal()
            return true
        }

        private fun requireNoSignal() {
            if (signalVeto.get() || restoreInterrupt || failure.get() === PersistencePhaseFailureCode.INTERRUPTED) {
                throw failureException(PersistencePhaseFailureCode.COMPLETION_FAILED)
            }
        }

        @Suppress("TooGenericExceptionCaught")
        private fun observeFinalCallerSignal() {
            try {
                if (caller.sampleOutsideLocks() != null) {
                    signalVeto.set(true)
                    failure.compareAndSet(null, PersistencePhaseFailureCode.INTERRUPTED)
                }
            } catch (problem: Throwable) {
                signalVeto.set(true)
                recordFailure(problem)
            } finally {
                try {
                    caller.restoreAfterFailure()
                } catch (problem: Throwable) {
                    signalVeto.set(true)
                    recordFailure(problem)
                }
            }
        }

        override fun completed(): Boolean = retained?.completedFor(this@PersistencePhaseContext) == true && boundsChecked
    }

    /** VERIFY is a distinct unfenced receipt -> publication path, not a weakened authorizer/apply. */
    private inner class OwnerDeleteAllVerificationBoundary : PersistenceOwnerDeleteAllVerification {
        private var issued = false
        private var retained: ComplaintOwnerDeleteAllVerificationOperation? = null

        override fun requireOperation(jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_VERIFY)
            if (issued || entityManagerFactory != null) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            issued = true
            installLimits()
            requireWork()
        }

        override fun retain(operation: ComplaintOwnerDeleteAllVerificationOperation, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_VERIFY)
            if (!issued || retained != null || !operation.belongsTo(this@PersistencePhaseContext)) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            retained = operation
        }

        override fun requireRetained(operation: ComplaintOwnerDeleteAllVerificationOperation, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_VERIFY)
            if (retained !== operation) refuse(PersistencePhaseFailureCode.WORK_FAILED)
        }

        override fun requireCommitted(operation: ComplaintOwnerDeleteAllVerificationOperation) {
            if (!caller.isCurrent() || retained !== operation || !completed()) failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            requireSuccessfulResult()
        }

        override fun completed(): Boolean = retained?.completedFor(this@PersistencePhaseContext) == true
    }

    /** Two named read paths, each recognizing only its own retained concrete SQL operation. */
    private inner class OwnerHistoryBoundary : PersistenceOwnerHistory {
        private var issued = false
        private var retained: ComplaintOwnerHistoryReadOperation? = null

        override fun requireAuthentication(jdbc: JdbcTemplate) = requireOperation(jdbc, PersistencePhasePath.COMPLAINT_OWNER_HISTORY_AUTHENTICATION)
        override fun requirePage(jdbc: JdbcTemplate) = requireOperation(jdbc, PersistencePhasePath.COMPLAINT_OWNER_HISTORY_PAGE)

        private fun requireOperation(jdbc: JdbcTemplate, expected: PersistencePhasePath) {
            requireStepUpResource(jdbc, expected)
            if (issued) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            issued = true
            installLimits()
            requireWork()
        }

        override fun retain(operation: ComplaintOwnerHistoryReadOperation, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (!issued || retained != null || !operation.belongsTo(this@PersistencePhaseContext, path)) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            retained = operation
        }

        override fun requireRetained(operation: ComplaintOwnerHistoryReadOperation, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (retained !== operation) refuse(PersistencePhaseFailureCode.WORK_FAILED)
        }

        override fun connection(operation: ComplaintOwnerHistoryReadOperation, jdbc: JdbcTemplate): Connection {
            requireRetained(operation, jdbc)
            return connection ?: refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }

        override fun requireCommitted(operation: ComplaintOwnerHistoryReadOperation) {
            if (!caller.isCurrent() || retained !== operation || !operation.completedFor(this@PersistencePhaseContext, path)) {
                failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            }
            requireSuccessfulResult()
        }

        override fun completed(): Boolean = retained?.completedFor(this@PersistencePhaseContext, path) == true
    }

    /** A concrete read, not a caller-supplied diagnostic enum, owns completion and the result-release seal. */
    private inner class InstallationCurrentStateBoundary : PersistenceInstallationCurrentState {
        private var issued = false
        private var retained: InstallationCurrentStateReadOperation? = null

        override fun requireOperation(jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, PersistencePhasePath.COMPLAINT_INSTALLATION_CURRENT_STATE)
            if (issued) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            issued = true
            installLimits()
            requireWork()
        }

        override fun retain(operation: InstallationCurrentStateReadOperation, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, PersistencePhasePath.COMPLAINT_INSTALLATION_CURRENT_STATE)
            if (!issued || retained != null || !operation.belongsTo(this@PersistencePhaseContext)) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            retained = operation
        }

        override fun requireRetained(operation: InstallationCurrentStateReadOperation, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, PersistencePhasePath.COMPLAINT_INSTALLATION_CURRENT_STATE)
            if (retained !== operation) refuse(PersistencePhaseFailureCode.WORK_FAILED)
        }

        override fun connection(operation: InstallationCurrentStateReadOperation, jdbc: JdbcTemplate): Connection {
            requireRetained(operation, jdbc)
            return connection ?: refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }

        override fun requireCommitted(operation: InstallationCurrentStateReadOperation) {
            if (!caller.isCurrent() || retained !== operation || !operation.completedFor(this@PersistencePhaseContext)) {
                failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            }
            requireSuccessfulResult()
        }

        override fun completed(): Boolean = retained?.completedFor(this@PersistencePhaseContext) == true
    }

    /** One exact read operation; completion is insufficient until the enclosing phase proves commit AND cleanup. */
    private inner class CatalogSnapshotBoundary : PersistenceCatalogSnapshot {
        private var issued = false
        private var retained: CatalogSnapshotReadOperation? = null

        override fun requireOperation(jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, PersistencePhasePath.COMPLAINT_CATALOG_SNAPSHOT)
            if (issued) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            issued = true
            installLimits()
            requireWork()
        }

        override fun retain(operation: CatalogSnapshotReadOperation, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, PersistencePhasePath.COMPLAINT_CATALOG_SNAPSHOT)
            if (!issued || retained != null || !operation.belongsTo(this@PersistencePhaseContext)) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            retained = operation
        }

        override fun requireRetained(operation: CatalogSnapshotReadOperation, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, PersistencePhasePath.COMPLAINT_CATALOG_SNAPSHOT)
            if (retained !== operation) refuse(PersistencePhaseFailureCode.WORK_FAILED)
        }

        override fun requireCommitted(operation: CatalogSnapshotReadOperation) {
            if (!caller.isCurrent() || retained !== operation || !operation.completedFor(this@PersistencePhaseContext)) {
                failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            }
            requireSuccessfulResult()
        }

        override fun completed(): Boolean = retained?.completedFor(this@PersistencePhaseContext) == true
    }

    /** One-operation cursor only; the enclosing phase retains the same permit, holder, lease and sticky failure. */
    private inner class DeletionBoundary : PersistenceComplaintDeletion {
        private var issued = false
        private var retained: ComplaintDeletionOperation? = null

        override fun requireOperation(jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, PersistencePhasePath.COMPLAINT_DELETION_MUTATION)
            if (issued) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            issued = true
            installLimits()
            requireWork()
        }

        override fun retain(operation: ComplaintDeletionOperation, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, PersistencePhasePath.COMPLAINT_DELETION_MUTATION)
            if (!issued || retained != null || !operation.belongsTo(this@PersistencePhaseContext)) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            retained = operation
        }

        override fun requireRetained(operation: ComplaintDeletionOperation, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, PersistencePhasePath.COMPLAINT_DELETION_MUTATION)
            if (retained !== operation) refuse(PersistencePhaseFailureCode.WORK_FAILED)
        }

        override fun connection(operation: ComplaintDeletionOperation, jdbc: JdbcTemplate): Connection {
            requireRetained(operation, jdbc)
            if (entityManagerFactory != null) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            return this@PersistencePhaseContext.connection ?: refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }

        override fun requireCommitted(operation: ComplaintDeletionOperation) {
            if (!caller.isCurrent() || retained !== operation || !operation.completedFor(this@PersistencePhaseContext)) {
                failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            }
            requireSuccessfulResult()
        }

        override fun completed(): Boolean = retained?.completedFor(this@PersistencePhaseContext) == true
    }

    /** K04's one-operation state only; phase/permit/lease ownership and sticky failure remain on the enclosing owner. */
    private inner class RecoverySettlementBoundary {
        private var issued = false
        private var retained: ComplaintRecoverySettlementOperation? = null

        fun requireOperation(jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, PersistencePhasePath.COMPLAINT_RECOVERY_SETTLEMENT)
            if (issued) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            issued = true
            installLimits()
            requireWork()
        }

        fun retain(operation: ComplaintRecoverySettlementOperation, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, PersistencePhasePath.COMPLAINT_RECOVERY_SETTLEMENT)
            if (!issued || retained != null || !operation.belongsTo(this@PersistencePhaseContext)) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            retained = operation
        }

        fun requireRetained(operation: ComplaintRecoverySettlementOperation, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, PersistencePhasePath.COMPLAINT_RECOVERY_SETTLEMENT)
            if (retained !== operation) refuse(PersistencePhaseFailureCode.WORK_FAILED)
        }

        fun checkWork(result: ComplaintRecoverySettlementResult) {
            requireParticipation()
            if (path !== PersistencePhasePath.COMPLAINT_RECOVERY_SETTLEMENT || retained?.completedResult(this@PersistencePhaseContext) !== result) {
                refuse(PersistencePhaseFailureCode.WORK_FAILED)
            }
        }

        fun result(result: ComplaintRecoverySettlementResult?): ComplaintRecoverySettlementResult {
            if (!caller.isCurrent() || path !== PersistencePhasePath.COMPLAINT_RECOVERY_SETTLEMENT) {
                failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            } else if (result == null || retained?.completedResult(this@PersistencePhaseContext) !== result) {
                failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            }
            requireSuccessfulResult()
            return checkNotNull(result)
        }

        fun completed(): Boolean = retained?.completedResult(this@PersistencePhaseContext) != null
    }

    /** K05 state only; all resource, permit, lease and sticky failure custody remains on the same enclosing phase. */
    private inner class TestReserveSpendBoundary {
        private var issued = false
        private var retained: ComplaintTestReserveSpendOperation? = null

        fun requireOperation(jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, PersistencePhasePath.COMPLAINT_TEST_RESERVE_SPEND)
            if (issued) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            issued = true
            installLimits()
            requireWork()
        }

        fun retain(operation: ComplaintTestReserveSpendOperation, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, PersistencePhasePath.COMPLAINT_TEST_RESERVE_SPEND)
            if (!issued || retained != null || !operation.belongsTo(this@PersistencePhaseContext)) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            retained = operation
        }

        fun requireRetained(operation: ComplaintTestReserveSpendOperation, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, PersistencePhasePath.COMPLAINT_TEST_RESERVE_SPEND)
            if (retained !== operation) refuse(PersistencePhaseFailureCode.WORK_FAILED)
        }

        fun checkWork(result: ComplaintTestReserveSpendResult) {
            requireParticipation()
            if (path !== PersistencePhasePath.COMPLAINT_TEST_RESERVE_SPEND || retained?.completedResult(this@PersistencePhaseContext) !== result) {
                refuse(PersistencePhaseFailureCode.WORK_FAILED)
            }
        }

        fun result(result: ComplaintTestReserveSpendResult?): ComplaintTestReserveSpendResult {
            if (!caller.isCurrent() || path !== PersistencePhasePath.COMPLAINT_TEST_RESERVE_SPEND) {
                failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            } else if (result == null || retained?.completedResult(this@PersistencePhaseContext) !== result) {
                failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            }
            requireSuccessfulResult()
            return checkNotNull(result)
        }

        fun completed(): Boolean = retained?.completedResult(this@PersistencePhaseContext) != null
    }

    /** Same exact result custody for paired creation, authenticated replay, or a no-write normal rejection. */
    private inner class InstallationEnrollmentBoundary : PersistenceInstallationEnrollment {
        private var issued = false
        private var retained: ComplaintInstallationEnrollmentOperation? = null
        private val enrollmentIdentity = Any()
        private var admission: ComplaintAdmittedEnrollmentWrite? = null
        private var admissionClaimed = false
        private var admissionBoundsChecked = false
        private var admissionWriteChecked = false

        override fun bindAdmitted(handoff: ComplaintAdmittedEnrollmentWrite) {
            requireCaller()
            if (stage !== Stage.PREPARED || path !== PersistencePhasePath.COMPLAINT_INSTALLATION_ENROLLMENT || admission != null) {
                refuse(PersistencePhaseFailureCode.WORK_FAILED)
            }
            admission = handoff // A caught failed bind cannot turn this into a raw unadmitted phase.
            ComplaintIngressAdmission.bindEnrollment(handoff, enrollmentIdentity)
        }

        override fun requireOperation(jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, PersistencePhasePath.COMPLAINT_INSTALLATION_ENROLLMENT)
            if (admission == null) ComplaintIngressAdmission.requireRawEnrollmentContext()
            if (issued) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            issued = true
            installLimits()
            requireWork()
        }

        override fun retain(operation: ComplaintInstallationEnrollmentOperation, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, PersistencePhasePath.COMPLAINT_INSTALLATION_ENROLLMENT)
            if (!issued || retained != null || !operation.belongsTo(this@PersistencePhaseContext)) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            retained = operation
        }

        override fun requireRetained(operation: ComplaintInstallationEnrollmentOperation, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, PersistencePhasePath.COMPLAINT_INSTALLATION_ENROLLMENT)
            if (retained !== operation) refuse(PersistencePhaseFailureCode.WORK_FAILED)
        }

        override fun ownerReference(operation: ComplaintInstallationEnrollmentOperation, jdbc: JdbcTemplate): UUID {
            requireRetained(operation, jdbc)
            val reference = enrollmentOwnerReference ?: refuse(PersistencePhaseFailureCode.WORK_FAILED)
            if (!operation.ownerReferenceIsDistinct(reference)) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            return reference
        }

        override fun claimAdmitted(operation: ComplaintInstallationEnrollmentOperation, jdbc: JdbcTemplate, candidate: InstallationEnrollmentCandidate) {
            requireRetained(operation, jdbc)
            val handoff = admission ?: return
            if (admissionClaimed) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            ComplaintIngressAdmission.claimEnrollment(handoff, enrollmentIdentity, candidate)
            admissionClaimed = true
        }

        override fun checkAdmittedBounds(
            operation: ComplaintInstallationEnrollmentOperation,
            jdbc: JdbcTemplate,
            ledger: ComplaintCapacityLedger,
            daily: ComplaintDailyAdmission,
        ) {
            requireRetained(operation, jdbc)
            val handoff = admission ?: return
            if (!admissionClaimed || admissionBoundsChecked) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            ComplaintIngressAdmission.checkEnrollmentBounds(handoff, enrollmentIdentity, ledger, daily)
            admissionBoundsChecked = true
        }

        override fun checkAdmittedWrite(operation: ComplaintInstallationEnrollmentOperation, jdbc: JdbcTemplate) {
            requireRetained(operation, jdbc)
            val handoff = admission ?: return
            if (!admissionClaimed || !admissionBoundsChecked) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            ComplaintIngressAdmission.checkEnrollmentWrite(handoff, enrollmentIdentity)
            admissionWriteChecked = true
        }

        override fun checkWork(result: InstallationEnrollmentResult) {
            requireParticipation()
            if (path !== PersistencePhasePath.COMPLAINT_INSTALLATION_ENROLLMENT || retained?.completedResult(this@PersistencePhaseContext) !== result ||
                !admissionCompleted(result)
            ) {
                refuse(PersistencePhaseFailureCode.WORK_FAILED)
            }
        }

        override fun result(result: InstallationEnrollmentResult?): InstallationEnrollmentResult {
            if (!caller.isCurrent() || path !== PersistencePhasePath.COMPLAINT_INSTALLATION_ENROLLMENT) {
                failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            } else if (result == null || retained?.completedResult(this@PersistencePhaseContext) !== result || !admissionCompleted(result)) {
                failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            }
            requireSuccessfulResult()
            return checkNotNull(result)
        }

        override fun entityManager(operation: ComplaintInstallationEnrollmentOperation, jdbc: JdbcTemplate): EntityManager {
            requireRetained(operation, jdbc)
            return createdEntityManager ?: refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }

        private fun admissionCompleted(result: InstallationEnrollmentResult): Boolean = admission == null ||
            (admissionClaimed && admissionBoundsChecked && (result !is InstallationEnrollmentResult.Enrolled || admissionWriteChecked))

        override fun completed(): Boolean {
            val result = retained?.completedResult(this@PersistencePhaseContext) ?: return false
            return admissionCompleted(result)
        }
    }

    /** Fixed delete-all snapshot only. Its result cannot be released by a caller flag or another retained operation. */
    private inner class InstallationDeletionPreflightBoundary : PersistenceInstallationDeletionPreflight {
        private var issued = false
        private var retained: ComplaintInstallationDeletionPreflightOperation? = null

        override fun requireOperation(jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, PersistencePhasePath.COMPLAINT_INSTALLATION_DELETION_PREFLIGHT)
            if (issued) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            issued = true
            installLimits()
            requireWork()
        }

        override fun retain(operation: ComplaintInstallationDeletionPreflightOperation, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, PersistencePhasePath.COMPLAINT_INSTALLATION_DELETION_PREFLIGHT)
            if (!issued || retained != null || !operation.belongsTo(this@PersistencePhaseContext)) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            retained = operation
        }

        override fun requireRetained(operation: ComplaintInstallationDeletionPreflightOperation, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, PersistencePhasePath.COMPLAINT_INSTALLATION_DELETION_PREFLIGHT)
            if (retained !== operation) refuse(PersistencePhaseFailureCode.WORK_FAILED)
        }

        override fun ownerIdentity(operation: ComplaintInstallationDeletionPreflightOperation, jdbc: JdbcTemplate): Any {
            requireRetained(operation, jdbc)
            return ownership.installationDeletionIdentity
        }

        override fun requireCommitted(operation: ComplaintInstallationDeletionPreflightOperation) {
            val callerAndPathMatch = caller.isCurrent() && path === PersistencePhasePath.COMPLAINT_INSTALLATION_DELETION_PREFLIGHT
            if (!callerAndPathMatch || retained !== operation || !operation.completedFor(this@PersistencePhaseContext)) {
                failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            }
            requireSuccessfulResult()
        }

        override fun completed(): Boolean = retained?.completedFor(this@PersistencePhaseContext) == true
    }

    /** Exact read/refresh operation and result custody; a normal rejection still needs real completion and release. */
    private inner class InstallationSessionBoundary : PersistenceInstallationSession {
        private var issued = false
        private var retained: ComplaintInstallationSessionOperation? = null
        private val refreshIdentity = Any()
        private var admittedRefresh: ComplaintAdmittedSessionRefresh? = null
        private var admissionClaimed = false
        private var admissionWriteChecked = false

        override fun bindAdmittedRefresh(handoff: ComplaintAdmittedSessionRefresh) {
            requireCaller()
            if (stage !== Stage.PREPARED || path !== PersistencePhasePath.COMPLAINT_INSTALLATION_SESSION_REFRESH || admittedRefresh != null) {
                refuse(PersistencePhaseFailureCode.WORK_FAILED)
            }
            admittedRefresh = handoff // Even a caught bind failure cannot turn this into an unadmitted phase.
            ComplaintIngressAdmission.bindSessionRefresh(handoff, refreshIdentity)
        }

        override fun requirePreflight(jdbc: JdbcTemplate) = requireOperation(jdbc, PersistencePhasePath.COMPLAINT_INSTALLATION_SESSION_PREFLIGHT)

        override fun requireRefresh(jdbc: JdbcTemplate) = requireOperation(jdbc, PersistencePhasePath.COMPLAINT_INSTALLATION_SESSION_REFRESH)

        private fun requireOperation(jdbc: JdbcTemplate, expected: PersistencePhasePath) {
            requireStepUpResource(jdbc, expected)
            if (issued) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            issued = true
            installLimits()
            requireWork()
        }

        override fun retain(operation: ComplaintInstallationSessionOperation, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (!issued || retained != null || !operation.belongsTo(this@PersistencePhaseContext, path)) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            retained = operation
        }

        override fun requireRetained(operation: ComplaintInstallationSessionOperation, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (retained !== operation) refuse(PersistencePhaseFailureCode.WORK_FAILED)
        }

        override fun ownerIdentity(operation: ComplaintInstallationSessionOperation, jdbc: JdbcTemplate): Any {
            requireRetained(operation, jdbc)
            return ownership.installationSessionIdentity
        }

        override fun claimAdmittedRefresh(
            operation: ComplaintInstallationSessionOperation,
            jdbc: JdbcTemplate,
            preflight: InstallationSessionPreflight,
            installation: ScopedInstallationId,
            credentialVersion: Long,
        ) {
            requireRetained(operation, jdbc)
            if (path !== PersistencePhasePath.COMPLAINT_INSTALLATION_SESSION_REFRESH) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            val handoff = admittedRefresh ?: return // Explicit dormant lower core only; a bound phase can never drop its handoff.
            if (admissionClaimed) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            ComplaintIngressAdmission.claimSessionRefresh(handoff, refreshIdentity, preflight, installation, credentialVersion)
            admissionClaimed = true
        }

        override fun checkAdmittedRefreshWrite(
            operation: ComplaintInstallationSessionOperation,
            jdbc: JdbcTemplate,
            installation: ScopedInstallationId,
            credentialVersion: Long,
        ) {
            requireRetained(operation, jdbc)
            if (path !== PersistencePhasePath.COMPLAINT_INSTALLATION_SESSION_REFRESH) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            val handoff = admittedRefresh ?: return
            if (!admissionClaimed || admissionWriteChecked) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            ComplaintIngressAdmission.checkSessionRefreshWrite(handoff, refreshIdentity, installation, credentialVersion)
            admissionWriteChecked = true
        }

        override fun checkWork(result: InstallationSessionResult) {
            requireParticipation()
            if (retained?.completedResult(this@PersistencePhaseContext) !== result || !admissionCompleted(result)) {
                refuse(PersistencePhaseFailureCode.WORK_FAILED)
            }
        }

        override fun preflightResult(result: SessionPreflightResult?): SessionPreflightResult {
            requireResult(result, PersistencePhasePath.COMPLAINT_INSTALLATION_SESSION_PREFLIGHT)
            return checkNotNull(result)
        }

        override fun refreshResult(result: SessionRefreshResult?): SessionRefreshResult {
            requireResult(result, PersistencePhasePath.COMPLAINT_INSTALLATION_SESSION_REFRESH)
            return checkNotNull(result)
        }

        private fun requireResult(result: InstallationSessionResult?, expected: PersistencePhasePath) {
            if (!caller.isCurrent() || path !== expected) {
                failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            } else if (result == null || retained?.completedResult(this@PersistencePhaseContext) !== result || !admissionCompleted(result)) {
                failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            }
            requireSuccessfulResult()
        }

        private fun admissionCompleted(result: InstallationSessionResult): Boolean =
            admittedRefresh == null || (admissionClaimed && (result !is SessionRefreshResult.Refreshed || admissionWriteChecked))

        override fun completed(): Boolean {
            val result = retained?.completedResult(this@PersistencePhaseContext) ?: return false
            return admissionCompleted(result)
        }
    }
}

private enum class Stage { PREPARED, STARTING, SETTING_UP, WORK, COMMITTING, ROLLING_BACK, FINALIZING, QUARANTINED, CLOSED }

private const val WORK_MILLIS = 2_000L
private const val EMERGENCY_MILLIS = 1_000L
private const val NORMAL_READ_MILLIS = 1_000L
private const val EMERGENCY_READ_MILLIS = 250L
private val INLINE = Executor { command -> command.run() }
private val BUSINESS_STAGES = setOf(Stage.STARTING, Stage.SETTING_UP, Stage.WORK, Stage.COMMITTING)
private val KNOWN_OUTCOMES = setOf(PersistenceDatabaseOutcome.COMMITTED, PersistenceDatabaseOutcome.ROLLED_BACK)
private val READ_CAP_METHODS = setOf("getNetworkTimeout", "setNetworkTimeout")
private val COMPLETION_LOCAL_METHODS = setOf(
    "getAutoCommit", "getNetworkTimeout", "setNetworkTimeout", "isClosed", "getWarnings", "clearWarnings",
    "getTransactionIsolation", "setTransactionIsolation", "isReadOnly", "setReadOnly", "getHoldability", "setHoldability",
    "getCatalog", "setCatalog", "getSchema", "setSchema", "getTypeMap", "setTypeMap",
)
private const val LOCAL_LIMITS = "SELECT set_config('transaction_timeout', ?, true), set_config('statement_timeout', ?, true), " +
    "set_config('idle_in_transaction_session_timeout', ?, true), set_config('lock_timeout', ?, true)"

/** Only this phase's private boundary is used; implementing a view cannot manufacture a retained read. */
internal interface PersistenceInstallationCurrentState {
    fun requireOperation(jdbc: JdbcTemplate)
    fun retain(operation: InstallationCurrentStateReadOperation, jdbc: JdbcTemplate)
    fun requireRetained(operation: InstallationCurrentStateReadOperation, jdbc: JdbcTemplate)
    fun connection(operation: InstallationCurrentStateReadOperation, jdbc: JdbcTemplate): Connection
    fun requireCommitted(operation: InstallationCurrentStateReadOperation)
    fun completed(): Boolean
}

/** Read-only K06 view of the exact phase's private boundary; no caller-supplied implementation is accepted. */
internal interface PersistenceInstallationEnrollment {
    fun bindAdmitted(handoff: ComplaintAdmittedEnrollmentWrite)
    fun requireOperation(jdbc: JdbcTemplate)
    fun retain(operation: ComplaintInstallationEnrollmentOperation, jdbc: JdbcTemplate)
    fun requireRetained(operation: ComplaintInstallationEnrollmentOperation, jdbc: JdbcTemplate)
    fun ownerReference(operation: ComplaintInstallationEnrollmentOperation, jdbc: JdbcTemplate): UUID
    fun claimAdmitted(operation: ComplaintInstallationEnrollmentOperation, jdbc: JdbcTemplate, candidate: InstallationEnrollmentCandidate)

    fun checkAdmittedBounds(
        operation: ComplaintInstallationEnrollmentOperation,
        jdbc: JdbcTemplate,
        ledger: ComplaintCapacityLedger,
        daily: ComplaintDailyAdmission,
    )

    fun checkAdmittedWrite(operation: ComplaintInstallationEnrollmentOperation, jdbc: JdbcTemplate)
    fun checkWork(result: InstallationEnrollmentResult)
    fun result(result: InstallationEnrollmentResult?): InstallationEnrollmentResult
    fun entityManager(operation: ComplaintInstallationEnrollmentOperation, jdbc: JdbcTemplate): EntityManager
    fun completed(): Boolean
}

/** Read-only view of this phase's private session boundary; callers cannot replace its owner or completion predicate. */
internal interface PersistenceInstallationSession {
    fun bindAdmittedRefresh(handoff: ComplaintAdmittedSessionRefresh)
    fun requirePreflight(jdbc: JdbcTemplate)
    fun requireRefresh(jdbc: JdbcTemplate)
    fun retain(operation: ComplaintInstallationSessionOperation, jdbc: JdbcTemplate)
    fun requireRetained(operation: ComplaintInstallationSessionOperation, jdbc: JdbcTemplate)
    fun ownerIdentity(operation: ComplaintInstallationSessionOperation, jdbc: JdbcTemplate): Any

    fun claimAdmittedRefresh(
        operation: ComplaintInstallationSessionOperation,
        jdbc: JdbcTemplate,
        preflight: InstallationSessionPreflight,
        installation: ScopedInstallationId,
        credentialVersion: Long,
    )

    fun checkAdmittedRefreshWrite(
        operation: ComplaintInstallationSessionOperation,
        jdbc: JdbcTemplate,
        installation: ScopedInstallationId,
        credentialVersion: Long,
    )

    fun checkWork(result: InstallationSessionResult)
    fun preflightResult(result: SessionPreflightResult?): SessionPreflightResult
    fun refreshResult(result: SessionRefreshResult?): SessionRefreshResult
    fun completed(): Boolean
}

/** Fixed snapshot operation on the existing owner; implementing this interface cannot replace its private boundary. */
internal interface PersistenceInstallationDeletionPreflight {
    fun requireOperation(jdbc: JdbcTemplate)
    fun retain(operation: ComplaintInstallationDeletionPreflightOperation, jdbc: JdbcTemplate)
    fun requireRetained(operation: ComplaintInstallationDeletionPreflightOperation, jdbc: JdbcTemplate)
    fun ownerIdentity(operation: ComplaintInstallationDeletionPreflightOperation, jdbc: JdbcTemplate): Any
    fun requireCommitted(operation: ComplaintInstallationDeletionPreflightOperation)
    fun completed(): Boolean
}

/** Read-only view of the phase's private deletion boundary; no caller implementation can replace it. */
internal interface PersistenceComplaintDeletion {
    fun requireOperation(jdbc: JdbcTemplate)
    fun retain(operation: ComplaintDeletionOperation, jdbc: JdbcTemplate)
    fun requireRetained(operation: ComplaintDeletionOperation, jdbc: JdbcTemplate)
    fun connection(operation: ComplaintDeletionOperation, jdbc: JdbcTemplate): Connection
    fun requireCommitted(operation: ComplaintDeletionOperation)
    fun completed(): Boolean
}

/** Exact phase/path/one-operation/resource/holder/lease guard, before the fixed store's SQL. */
internal fun requireSourceGrantCleanup(jdbc: JdbcTemplate) {
    val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
    phase.requireSourceCleanup(jdbc)
}

/** The phase's private boundary owns retention; a replacement implementation is never accepted. */
internal interface PersistenceOwnerHistory {
    fun requireAuthentication(jdbc: JdbcTemplate)
    fun requirePage(jdbc: JdbcTemplate)
    fun retain(operation: ComplaintOwnerHistoryReadOperation, jdbc: JdbcTemplate)
    fun requireRetained(operation: ComplaintOwnerHistoryReadOperation, jdbc: JdbcTemplate)
    fun connection(operation: ComplaintOwnerHistoryReadOperation, jdbc: JdbcTemplate): Connection
    fun requireCommitted(operation: ComplaintOwnerHistoryReadOperation)
    fun completed(): Boolean
}

/** The exact phase owns this view and accepts only its own concrete retained SQL operation. */
internal interface PersistenceOwnerOperation {
    fun bindCreate(handoff: ComplaintAdmittedOwnerCreate)
    fun requireOperation(jdbc: JdbcTemplate, expected: PersistencePhasePath)
    fun retain(operation: ComplaintOwnerCreateOperation, jdbc: JdbcTemplate)
    fun requireRetained(operation: ComplaintOwnerCreateOperation, jdbc: JdbcTemplate)
    fun claimCreate(operation: ComplaintOwnerCreateOperation, jdbc: JdbcTemplate, tuple: ComplaintOwnerOperationTuple)
    fun checkCreateBounds(operation: ComplaintOwnerCreateOperation, jdbc: JdbcTemplate, ledger: ComplaintCapacityLedger)
    fun checkCreateWrite(operation: ComplaintOwnerCreateOperation, jdbc: JdbcTemplate)
    fun entityManager(operation: ComplaintOwnerCreateOperation, jdbc: JdbcTemplate): EntityManager
    fun requireCommitted(operation: ComplaintOwnerCreateOperation)
    fun completed(): Boolean
}

/** Read-only view of this exact phase's private authorizer/reloader boundary, never a replaceable executor. */
internal interface PersistenceOwnerDeleteAll {
    fun bindAuthorize(handoff: ComplaintAdmittedOwnerDeleteAll)
    fun requireOperation(jdbc: JdbcTemplate, expected: PersistencePhasePath)
    fun retain(operation: ComplaintOwnerDeleteAllOperation, jdbc: JdbcTemplate)
    fun requireRetained(operation: ComplaintOwnerDeleteAllOperation, jdbc: JdbcTemplate)
    fun claimAuthorize(operation: ComplaintOwnerDeleteAllOperation, jdbc: JdbcTemplate, tuple: InstallationDeletionPreflightTuple)
    fun checkReceiptWrite(operation: ComplaintOwnerDeleteAllOperation, jdbc: JdbcTemplate)
    fun checkCapacity(operation: ComplaintOwnerDeleteAllOperation, jdbc: JdbcTemplate, ledger: ComplaintCapacityLedger)
    fun checkWrite(operation: ComplaintOwnerDeleteAllOperation, jdbc: JdbcTemplate)
    fun connection(operation: ComplaintOwnerDeleteAllOperation, jdbc: JdbcTemplate): Connection
    fun requireCommitted(operation: ComplaintOwnerDeleteAllOperation)
    fun completed(): Boolean
}

/** This named phase retains only its concrete VERIFY operation; it exposes no optional lock or callback. */
internal interface PersistenceOwnerDeleteAllVerification {
    fun requireOperation(jdbc: JdbcTemplate)
    fun retain(operation: ComplaintOwnerDeleteAllVerificationOperation, jdbc: JdbcTemplate)
    fun requireRetained(operation: ComplaintOwnerDeleteAllVerificationOperation, jdbc: JdbcTemplate)
    fun requireCommitted(operation: ComplaintOwnerDeleteAllVerificationOperation)
    fun completed(): Boolean
}

/** No generic callback, second holder, repeated authorization or caller-selected locking policy. */
internal interface PersistenceOwnerDeleteAllApply {
    /** Veto only, before raw signal types are discarded; never grants completion, cleanup or an outcome. */
    fun observeFailure(problem: Throwable)
    fun requireOperation(jdbc: JdbcTemplate)
    fun retain(operation: ComplaintOwnerDeleteAllApplyOperation, jdbc: JdbcTemplate)
    fun requireRetained(operation: ComplaintOwnerDeleteAllApplyOperation, jdbc: JdbcTemplate)
    fun checkCapacity(operation: ComplaintOwnerDeleteAllApplyOperation, jdbc: JdbcTemplate)
    fun checkWrite(operation: ComplaintOwnerDeleteAllApplyOperation, jdbc: JdbcTemplate)
    fun connection(operation: ComplaintOwnerDeleteAllApplyOperation, jdbc: JdbcTemplate): Connection
    fun requireCommitted(operation: ComplaintOwnerDeleteAllApplyOperation)
    fun reconciliationPending(operation: ComplaintOwnerDeleteAllApplyOperation): Boolean
    fun completed(): Boolean
}
