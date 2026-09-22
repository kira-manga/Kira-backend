package me.manga.kira.backend.common.infrastructure.persistence

import jakarta.persistence.EntityManager
import me.manga.kira.backend.audit.infrastructure.ComplaintAuditSelectedHolder
import me.manga.kira.backend.complaint.domain.ComplaintAdminContentTuple
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusTuple
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchStatusTuple
import me.manga.kira.backend.complaint.domain.ComplaintCapacityLedger
import me.manga.kira.backend.complaint.domain.ComplaintDailyAdmission
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintOwnerCreationOperation
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteTuple
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminDeletePhaseOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminDeleteReadOperation
import me.manga.kira.backend.security.ComplaintAdmittedAdminErasure
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteTuple
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeletePhaseOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeleteReadOperation
import me.manga.kira.backend.complaint.infrastructure.TestOwnerDeleteApplyInputV1
import me.manga.kira.backend.complaint.infrastructure.TestOwnerDeleteControlBindingV1
import me.manga.kira.backend.complaint.infrastructure.TestOwnerDeleteVerificationInputV1
import me.manga.kira.backend.complaint.infrastructure.TestOwnerDeleteLocalGraphV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationAttemptV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestInitialAdmissionV1
import me.manga.kira.backend.complaint.infrastructure.admission.TestInitialAdmissionOperationV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveFirstCutV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveFirstCutSuccessorV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveFirstCutOperationV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveFirstCutSuccessorOperationV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRecoveryRegistrationAttemptV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceActiveRegistrationAttemptV1
import me.manga.kira.backend.complaint.infrastructure.admission.TestNamespaceRegistrationOperationV1
import me.manga.kira.backend.complaint.infrastructure.admission.TestNamespaceRecoveryRegistrationOperationV1
import me.manga.kira.backend.complaint.infrastructure.admission.TestNamespaceActiveRegistrationOperationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunSealingOperationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunSealingV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveOrdinarySealV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveInitialCheckpointV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveRecurrentV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveRecurrentApplyV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveOwnerDeleteQueueV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveOrdinarySealOperationV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveInitialCheckpointOperationV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveRecurrentOperationV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveOwnerDeleteQueueOperationV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveOrdinarySealRecoveryV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveOrdinarySealRecoveryOperationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOrdinarySealV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinarySealOperationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestInstallationManifestOperationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestInstallationManifestPublicationOperationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestTerminalQuiescenceOperationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunTerminalQuiescenceV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunErasureV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunErasureOperationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunPurgeOperationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestTerminalEpochSealOperationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunInstallationManifestV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunInstallationManifestPublicationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunPurgePublicationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunTerminalEpochSealV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOrdinaryDrainV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainOperationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOwnerDeleteContinuationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOwnerDeleteAllContinuationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunAdminDeleteContinuationV1
import me.manga.kira.backend.complaint.infrastructure.TestAdminDeleteApplyInputV1
import me.manga.kira.backend.complaint.infrastructure.TestAdminDeleteVerificationInputV1
import me.manga.kira.backend.complaint.infrastructure.OwnerDeleteAllVerificationInputV1
import me.manga.kira.backend.complaint.infrastructure.OwnerDeleteAllApplyInputV1
import me.manga.kira.backend.security.ComplaintAdmittedOwnerDelete
import me.manga.kira.backend.complaint.domain.ComplaintOwnerEditTuple
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
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminContentOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminStatusMutation
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminBatchStatusMutation
import me.manga.kira.backend.complaint.infrastructure.ComplaintInstallationDeletionPreflightOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintInstallationSessionOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerCreateOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeleteAllApplyOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeleteAllOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeleteAllVerificationOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDetailReadOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerEditOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerHistoryReadOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminReadOperation
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintCatalogGenesisPublishRecheckOperationV1
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
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisFinalizeAttemptV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisFreezeAttemptV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisInitialLiveBinding
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisMutationInput
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisMutationOperation
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisPublishAttemptV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogProjectedHeadInputV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogProjectedHeadReadOperationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogReadbackRefreshCustodyV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationActivationInputV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationActivationOperationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationActivationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalPhaseV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalOperationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalPreflightOperationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationInputV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationOperationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationDeliveryV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationFinalizationInputV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationFinalizationOperationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationFreezeAttemptV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationInitialAuthorV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationOperationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationPreparedRecoveryV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationSqlInputV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSnapshotReadOperation
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintDeletionOperation
import me.manga.kira.backend.security.ComplaintAdmittedAdminContent
import me.manga.kira.backend.security.ComplaintAdmittedAdminStatus
import me.manga.kira.backend.security.ComplaintAdmittedAdminBatchStatus
import me.manga.kira.backend.security.ComplaintAdmittedEnrollmentWrite
import me.manga.kira.backend.security.ComplaintAdmittedOwnerCreate
import me.manga.kira.backend.security.ComplaintAdmittedOwnerDeleteAll
import me.manga.kira.backend.security.ComplaintAdmittedOwnerEdit
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
    private val catalogAuthorAttempt: CatalogGenesisFreezeAttemptV1? = null,
    private val catalogAuthorWork: PersistenceTimeBudget? = null,
    private val catalogFinalizerAttempt: CatalogGenesisFinalizeAttemptV1? = null,
    private val catalogFinalizerWork: PersistenceTimeBudget? = null,
    private val catalogPublisherAttempt: CatalogGenesisPublishAttemptV1? = null,
    private val catalogPublisherWork: PersistenceTimeBudget? = null,
    private val catalogSignerRotationAttempt: CatalogSignerRotationFreezeAttemptV1? = null,
    private val catalogSignerRotationWork: PersistenceTimeBudget? = null,
    private val signerRotationRecovery: CatalogSignerRotationPreparedRecoveryV1? = null,
    private val signerRotationRecoveryWork: PersistenceTimeBudget? = null,
    private val signerRotationAuthor: CatalogSignerRotationInitialAuthorV1? = null,
    private val signerRotationAuthorWork: PersistenceTimeBudget? = null,
    private val signerRotationDelivery: CatalogSignerRotationDeliveryV1? = null,
    private val signerRotationDeliveryWork: PersistenceTimeBudget? = null,
    private val signerRotationActivation: CatalogSignerRotationActivationV1? = null,
    private val signerRotationActivationWork: PersistenceTimeBudget? = null,
    private val testRunActivation: CatalogTestRunActivationV1? = null,
    private val testRunActivationWork: PersistenceTimeBudget? = null,
    private val testRegistration: ComplaintTestNamespaceRegistrationAttemptV1? = null,
    private val testRegistrationWork: PersistenceTimeBudget? = null,
    private val initialAdmission: ComplaintTestInitialAdmissionV1? = null,
    private val testInitialAdmissionWork: PersistenceTimeBudget? = null,
    private val activeFirstCut: TestActiveFirstCutV1? = null,
    private val testActiveFirstCutWork: PersistenceTimeBudget? = null,
    private val activeFirstCutSuccessor: TestActiveFirstCutSuccessorV1? = null,
    private val testActiveFirstCutSuccessorWork: PersistenceTimeBudget? = null,
    private val testRecoveryRegistration: ComplaintTestNamespaceRecoveryRegistrationAttemptV1? = null,
    private val testRecoveryRegistrationWork: PersistenceTimeBudget? = null,
    private val testActiveRegistration: ComplaintTestNamespaceActiveRegistrationAttemptV1? = null,
    private val testActiveRegistrationWork: PersistenceTimeBudget? = null,
    private val testRunSealer: TestRunSealingV1? = null,
    private val testRunSealingWork: PersistenceTimeBudget? = null,
    private val testOrdinarySealer: TestRunOrdinarySealV1? = null,
    private val testOrdinarySealWork: PersistenceTimeBudget? = null,
    private val testRunOwnerDelete: TestRunOwnerDeleteContinuationV1? = null,
    private val testRunOwnerDeleteWork: PersistenceTimeBudget? = null,
    private val testRunOwnerDeleteAll: TestRunOwnerDeleteAllContinuationV1? = null,
    private val testRunOwnerDeleteAllWork: PersistenceTimeBudget? = null,
    private val testRunAdminDelete: TestRunAdminDeleteContinuationV1? = null,
    private val testRunAdminDeleteWork: PersistenceTimeBudget? = null,
    private val testOrdinaryDrain: TestRunOrdinaryDrainV1? = null,
    private val testOrdinaryDrainWork: PersistenceTimeBudget? = null,
    private val testInstallationManifest: TestRunInstallationManifestV1? = null,
    private val testInstallationManifestWork: PersistenceTimeBudget? = null,
    private val testInstallationManifestPublication: TestRunInstallationManifestPublicationV1? = null,
    private val testInstallationManifestPublicationWork: PersistenceTimeBudget? = null,
    private val testRunPurge: TestRunPurgePublicationV1? = null,
    private val testRunPurgeWork: PersistenceTimeBudget? = null,
    private val testActiveSealer: TestActiveOrdinarySealV1? = null,
    private val testActiveOrdinarySealWork: PersistenceTimeBudget? = null,
    private val testTerminalEpochSeal: TestRunTerminalEpochSealV1? = null,
    private val testTerminalEpochSealWork: PersistenceTimeBudget? = null,
    private val testInitialCheckpoint: TestActiveInitialCheckpointV1? = null,
    private val testRecurrent: TestActiveRecurrentV1? = null,
    private val testRecurrentApply: TestActiveRecurrentApplyV1? = null,
    private val testActiveRecurrentApplyWork: PersistenceTimeBudget? = null,
    private val testActiveInitialCheckpointWork: PersistenceTimeBudget? = null,
    private val testActiveRecurrentWork: PersistenceTimeBudget? = null,
    private val testActiveQueue: TestActiveOwnerDeleteQueueV1? = null,
    private val testActiveOwnerDeleteQueueWork: PersistenceTimeBudget? = null,
    private val testActiveSealRecovery: TestActiveOrdinarySealRecoveryV1? = null,
    private val testActiveOrdinarySealRecoveryWork: PersistenceTimeBudget? = null,
    private val testTerminalQuiescence: TestRunTerminalQuiescenceV1? = null,
    private val testTerminalQuiescenceWork: PersistenceTimeBudget? = null,
    private val testRunErasure: TestRunErasureV1? = null,
    private val testRunErasureWork: PersistenceTimeBudget? = null,
    private val testRunTerminalCatalog: CatalogTestRunTerminalV1? = null,
    private val testRunTerminalCatalogWork: PersistenceTimeBudget? = null,
) {
    private val manager = ownership.manager
    private val dataSource = ownership.dataSource

    // Capture this phase's original allowance; a later session stage must never replace its cleanup budget.
    private val signerRotationAuthorAllowance = signerRotationAuthor?.phaseBudget
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
    internal val ownerDetail: PersistenceOwnerDetail = OwnerDetailBoundary()
    internal val adminRead: PersistenceComplaintAdminRead = AdminReadBoundary()
    internal val adminContent: PersistenceComplaintAdminContent = AdminContentBoundary()
    internal val adminStatus: PersistenceComplaintAdminStatus = AdminStatusBoundary()
    internal val adminBatchStatus: PersistenceComplaintAdminBatchStatus = AdminBatchStatusBoundary()
    private var registeredInitialCheckpointCreate: me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredInitialCheckpointCreateV1? = null
    private var registeredInitialCheckpointEdit: me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredInitialCheckpointEditV1? = null
    private var registeredAdminContent: me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredAdminContentV1? = null
    private var registeredAdminStatus: me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredAdminContentV1? = null
    private var registeredAdminStepUp: me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredComplaintStepUpV1? = null
    private var preparingRegisteredAdminStepUp = false
    private var registeredAdminStepUpCurrentReady = false
    private var registeredInitialDeletion: me.manga.kira.backend.complaint.infrastructure.TestOwnerDeleteProcessBindingV1? = null
    private var initialDeletionControls = false
    private var initialOwnerDeleteStore: me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteStore? = null
    private var initialOwnerDeleteLane: me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationLanesV1.TestOwnerDeleteReservation? = null
    private var initialAllDeleteStore: me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteAllStore? = null
    private var initialAllDeleteLane: me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationLanesV1.TestOwnerDeleteAllReservation? = null
    private var initialAdminDeleteStore: me.manga.kira.backend.complaint.infrastructure.JdbcComplaintAdminDeleteStore? = null
    private var initialAdminDeleteLane: me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationLanesV1.TestAdminDeleteReservation? = null
    private var initialOwnerDeleteVerificationInput: me.manga.kira.backend.complaint.infrastructure.TestOwnerDeleteVerificationInputV1? = null
    private var initialAllDeleteVerificationInput: me.manga.kira.backend.complaint.infrastructure.OwnerDeleteAllVerificationInputV1? = null
    private var initialAdminDeleteVerificationInput: me.manga.kira.backend.complaint.infrastructure.TestAdminDeleteVerificationInputV1? = null
    internal val ownerOperation: PersistenceOwnerOperation = OwnerOperationBoundary()
    internal val ownerEdit: PersistenceOwnerEdit = OwnerEditBoundary()
    internal val adminDelete: PersistenceAdminDelete = AdminDeleteBoundary()
    internal val adminDeleteRead: PersistenceAdminDeleteRead = AdminDeleteReadBoundary()
    internal val ownerDelete: PersistenceOwnerDelete = OwnerDeleteBoundary()
    internal val ownerDeleteRead: PersistenceOwnerDeleteRead = OwnerDeleteReadBoundary()
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
    internal val catalogGenesisPublishRecheck: PersistenceCatalogGenesisPublishRecheck = CatalogGenesisPublishRecheckBoundary()
    internal val catalogSignerRotation: PersistenceCatalogSignerRotationV1 = CatalogSignerRotationBoundary()
    internal val catalogSignerRotationFinalization: PersistenceCatalogSignerRotationFinalizationV1 = CatalogSignerRotationFinalizationBoundary()
    internal val catalogSignerRotationActivation: PersistenceCatalogSignerRotationActivationV1 = CatalogSignerRotationActivationBoundary()
    internal val catalogTestRunActivation: PersistenceCatalogTestRunActivationV1 = CatalogTestRunActivationBoundary()
    internal val testNamespaceRegistration: PersistenceTestNamespaceRegistrationV1 = TestNamespaceRegistrationBoundary()
    internal val testInitialAdmission: PersistenceTestInitialAdmissionV1 = TestInitialAdmissionBoundary()
    internal val testActiveFirstCut: PersistenceTestActiveFirstCutV1 = TestActiveFirstCutBoundary()
    internal val testActiveFirstCutSuccessor: PersistenceTestActiveFirstCutSuccessorV1 = TestActiveFirstCutSuccessorBoundary()
    internal val testNamespaceRecoveryRegistration: PersistenceTestNamespaceRecoveryRegistrationV1 = TestNamespaceRecoveryRegistrationBoundary()
    internal val testNamespaceActiveRegistration: PersistenceTestNamespaceActiveRegistrationV1 = TestNamespaceActiveRegistrationBoundary()
    internal val testRunSealing: PersistenceTestRunSealingV1 = TestRunSealingBoundary()
    internal val testOrdinarySeal: PersistenceTestOrdinarySealV1 = TestOrdinarySealBoundary()
    internal val testActiveOrdinarySeal: PersistenceTestActiveOrdinarySealV1 = TestActiveOrdinarySealBoundary()
    internal val testActiveInitialCheckpoint: PersistenceTestActiveInitialCheckpointV1 = TestActiveInitialCheckpointBoundary()
    internal val testActiveRecurrent: PersistenceTestActiveRecurrentV1 = TestActiveRecurrentBoundary()
    internal val testActiveOwnerDeleteQueue: PersistenceTestActiveOwnerDeleteQueueV1 = TestActiveOwnerDeleteQueueBoundary()
    internal val testActiveOrdinarySealRecovery: PersistenceTestActiveOrdinarySealRecoveryV1 = TestActiveOrdinarySealRecoveryBoundary()
    internal val testInstallationManifestBoundary: PersistenceTestInstallationManifestV1 = TestInstallationManifestBoundary()
    internal val testInstallationManifestPublicationBoundary: PersistenceTestInstallationManifestPublicationV1 = TestInstallationManifestPublicationBoundary()
    internal val testTerminalQuiescenceBoundary: PersistenceTestTerminalQuiescenceV1 = TestTerminalQuiescenceBoundary()
    internal val testRunErasureBoundary: PersistenceTestRunErasureV1 = TestRunErasureBoundary()
    internal val catalogTestRunTerminal: PersistenceCatalogTestRunTerminalV1 = CatalogTestRunTerminalBoundary()
    internal val testRunTerminalCatalogPreflight: PersistenceTestRunTerminalCatalogPreflightV1 = TestRunTerminalCatalogPreflightBoundary()
    internal val testRunPurgeBoundary: PersistenceTestRunPurgeV1 = TestRunPurgeBoundary()
    internal val testTerminalEpochSealBoundary: PersistenceTestTerminalEpochSealV1 = TestTerminalEpochSealBoundary()
    internal val testOrdinaryDrainBoundary: PersistenceTestOrdinaryDrainV1 = TestOrdinaryDrainBoundary()

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

    // Keep transaction-status custody, exact resource validation and fence acquisition in their original order.
    @Suppress("CyclomaticComplexMethod")
    internal fun begin() {
        requireCaller()
        if (stage !== Stage.PREPARED) refuse(PersistencePhaseFailureCode.MANAGER_REFUSED)
        stage = Stage.STARTING
        val rechecksReadCommitted = firstDesiredAttempt != null || catalogPublisherAttempt != null ||
            catalogSignerRotationAttempt != null || signerRotationRecovery != null || signerRotationDelivery != null || signerRotationActivation != null ||
                testRunActivation != null || testRunOwnerDelete != null || testRunOwnerDeleteAll != null || testRunAdminDelete != null || testOrdinaryDrain != null || path.complaintMaintenanceWriter
        val adminReadCommitted = path === PersistencePhasePath.COMPLAINT_ADMIN_DELETE_PREFLIGHT || path === PersistencePhasePath.COMPLAINT_ADMIN_READ_AUTHENTICATION ||
            path === PersistencePhasePath.COMPLAINT_ADMIN_SEARCH || path === PersistencePhasePath.COMPLAINT_ADMIN_DETAIL ||
                path === PersistencePhasePath.COMPLAINT_ADMIN_STATS ||
                path === PersistencePhasePath.COMPLAINT_ADMIN_EDIT_PREFLIGHT ||
                path === PersistencePhasePath.COMPLAINT_ADMIN_STATUS_PREFLIGHT ||
                path === PersistencePhasePath.COMPLAINT_ADMIN_BATCH_STATUS_PREFLIGHT
        val definition = DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRED).apply {
            setName(path.name)
            timeout = 2
            isReadOnly = path.readOnly
            // Durable maintenance checks need a fresh statement snapshot AFTER M; selected desired/catalog
            // owners also recheck AFTER control. Set isolation from begin, never after a snapshot exists.
            if (desiredAttempt != null || rechecksReadCommitted || adminReadCommitted) {
                isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
            }
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
        if ((rechecksReadCommitted || adminReadCommitted) && selected.transactionIsolation != Connection.TRANSACTION_READ_COMMITTED) {
            refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
        selectedHolder.acquireMaintenanceFence(selected)
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
            rotationWork ?: cutoffWork ?: catalogRefreshWork ?: desiredWork ?: firstDesiredWork ?: catalogAuthorWork ?: catalogFinalizerWork
                ?: catalogPublisherWork ?: catalogSignerRotationWork ?: signerRotationRecoveryWork ?: signerRotationAuthorWork ?: signerRotationDeliveryWork
                ?: signerRotationActivationWork ?: testRunActivationWork ?: testRegistrationWork ?: testInitialAdmissionWork ?: testActiveFirstCutWork ?: testActiveFirstCutSuccessorWork ?: testRecoveryRegistrationWork ?: testActiveRegistrationWork ?: testRunSealingWork ?: testOrdinarySealWork ?: testActiveOrdinarySealWork ?: testActiveOrdinarySealRecoveryWork ?: testActiveInitialCheckpointWork ?: testActiveRecurrentWork ?: testActiveRecurrentApplyWork ?: testActiveOwnerDeleteQueueWork ?: testRunOwnerDeleteWork ?: testRunAdminDeleteWork ?: testRunOwnerDeleteAllWork ?: testOrdinaryDrainWork ?: testInstallationManifestWork ?: testInstallationManifestPublicationWork ?: testRunPurgeWork ?: testTerminalEpochSealWork ?: testRunErasureWork ?: testTerminalQuiescenceWork ?: testRunTerminalCatalogWork
                ?: PersistenceTimeBudget.start(WORK_MILLIS, ownership.nanoClock)
    }

    internal fun retainedPhaseCheckoutBudget(ceilingMillis: Long): PersistenceTimeBudget? {
        requireCaller()
        val retained = rotationWork ?: cutoffWork ?: catalogRefreshWork ?: desiredWork ?: firstDesiredWork ?: catalogAuthorWork ?: catalogFinalizerWork
            ?: catalogPublisherWork ?: catalogSignerRotationWork ?: signerRotationRecoveryWork ?: signerRotationAuthorWork ?: signerRotationDeliveryWork
            ?: signerRotationActivationWork ?: testRunActivationWork ?: testRegistrationWork ?: testInitialAdmissionWork ?: testActiveFirstCutWork ?: testActiveFirstCutSuccessorWork ?: testRecoveryRegistrationWork ?: testActiveRegistrationWork ?: testRunSealingWork ?: testOrdinarySealWork ?: testActiveOrdinarySealWork ?: testActiveOrdinarySealRecoveryWork ?: testActiveInitialCheckpointWork ?: testActiveRecurrentWork ?: testActiveRecurrentApplyWork ?: testActiveOwnerDeleteQueueWork ?: testRunOwnerDeleteWork ?: testRunAdminDeleteWork ?: testRunOwnerDeleteAllWork ?: testOrdinaryDrainWork ?: testInstallationManifestWork ?: testInstallationManifestPublicationWork ?: testRunPurgeWork ?: testTerminalEpochSealWork ?: testRunErasureWork ?: testTerminalQuiescenceWork ?: testRunTerminalCatalogWork
        return retained?.systemCappedSnapshot(ceilingMillis)
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
        if (expected === PersistencePhasePath.COMPLAINT_STEP_UP_SNAPSHOT || expected === PersistencePhasePath.COMPLAINT_STEP_UP_ISSUANCE) {
            // A desired-only issuer cannot borrow the protected registered ordinary pool.
            ownership.dataSource.requireTestInitialCheckpointCreate(registeredAdminStepUp?.policy)
            registeredAdminStepUp?.let {
                requireRegisteredAdminStepUpOwner(it, jdbc, ownership)
                if (expected === PersistencePhasePath.COMPLAINT_STEP_UP_ISSUANCE && !registeredAdminStepUpCurrentReady) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
        }
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
        registeredAdminStepUp?.let {
            requireRegisteredAdminStepUpOwner(it, jdbc, ownership)
            if (!registeredAdminStepUpCurrentReady) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            // The existing issuer invokes this before accounting/SQL and after each real wait.
            // Reuse the original typed current reader, not a cached readiness bit or provider callback.
            it.owner.checkCurrent(it, this)
        }
    }

    internal fun bindRegisteredAdminStepUp(original: me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredComplaintStepUpV1) {
        requireCaller()
        if (stage !== Stage.PREPARED || registeredAdminStepUp != null) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        original.owner.requirePhaseOwner(ownership)
        original.bind(this, path)
        registeredAdminStepUp = original
    }

    internal fun requireRegisteredAdminStepUpOwner(original: me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredComplaintStepUpV1,
        jdbc: JdbcTemplate, selected: PersistencePhaseOwnership) {
        if (path !in setOf(PersistencePhasePath.COMPLAINT_STEP_UP_SNAPSHOT, PersistencePhasePath.COMPLAINT_STEP_UP_ISSUANCE)) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        requireStepUpResource(jdbc, path)
        if (registeredAdminStepUp !== original || selected !== ownership) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        original.owner.requirePhaseOwner(ownership)
        original.requireBound(this)
    }

    internal fun registeredAdminStepUpConnection(original: me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredComplaintStepUpV1,
        jdbc: JdbcTemplate, selected: PersistencePhaseOwnership): Connection {
        requireRegisteredAdminStepUpOwner(original, jdbc, selected)
        if (path !== PersistencePhasePath.COMPLAINT_STEP_UP_ISSUANCE || (!preparingRegisteredAdminStepUp && !registeredAdminStepUpCurrentReady)) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        original.requireCurrentRead(this)
        return connection ?: refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
    }

    /** Fixed pre-counter control locking on the exact issuance holder, not a generic before-work callback. */
    internal fun prepareRegisteredAdminStepUp(original: me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredComplaintStepUpV1) {
        requireParticipation()
        if (registeredAdminStepUp !== original || path !== PersistencePhasePath.COMPLAINT_STEP_UP_ISSUANCE ||
            stepUpOperationIssued || preparingRegisteredAdminStepUp || registeredAdminStepUpCurrentReady) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        installLimits(); requireWork()
        original.beginCurrentRead(this)
        preparingRegisteredAdminStepUp = true
        try {
            original.owner.lockAndCheck(original, this)
            registeredAdminStepUpCurrentReady = true
        } finally {
            preparingRegisteredAdminStepUp = false
        }
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

        PersistencePhasePath.COMPLAINT_TEST_NAMESPACE_REGISTRATION -> testNamespaceRegistration.completed()
        PersistencePhasePath.COMPLAINT_TEST_INITIAL_ADMISSION_CAPTURE,
        PersistencePhasePath.COMPLAINT_TEST_INITIAL_ADMISSION_RELEASE,
        -> testInitialAdmission.completed()

        PersistencePhasePath.COMPLAINT_TEST_ACTIVE_FIRST_CUT_READ,
        PersistencePhasePath.COMPLAINT_TEST_ACTIVE_FIRST_CUT_LEASE,
        PersistencePhasePath.COMPLAINT_TEST_ACTIVE_FIRST_CUT_REQUEST,
        -> testActiveFirstCut.completed()

        PersistencePhasePath.COMPLAINT_TEST_ACTIVE_FIRST_CUT_SUCCESSOR_READ,
        PersistencePhasePath.COMPLAINT_TEST_ACTIVE_FIRST_CUT_SUCCESSOR_LEASE,
        PersistencePhasePath.COMPLAINT_TEST_ACTIVE_FIRST_CUT_SUCCESSOR_RELEASE,
        -> testActiveFirstCutSuccessor.completed()
        PersistencePhasePath.COMPLAINT_TEST_NAMESPACE_RECOVERY_REGISTRATION -> testNamespaceRecoveryRegistration.completed()
        PersistencePhasePath.COMPLAINT_TEST_NAMESPACE_ACTIVE_REGISTRATION -> testNamespaceActiveRegistration.completed()

        PersistencePhasePath.COMPLAINT_TEST_RUN_SEAL,
        PersistencePhasePath.COMPLAINT_TEST_RUN_SEALED_AUDIT,
        -> testRunSealing.completed()
        PersistencePhasePath.COMPLAINT_TEST_ORDINARY_SEAL -> testOrdinarySeal.completed()
        PersistencePhasePath.COMPLAINT_TEST_ACTIVE_ORDINARY_SEAL,
        PersistencePhasePath.COMPLAINT_TEST_ACTIVE_CUTOFF_EVIDENCE,
        -> testActiveOrdinarySeal.completed()
        PersistencePhasePath.COMPLAINT_TEST_ACTIVE_INITIAL_CHECKPOINT -> testActiveInitialCheckpoint.completed()
        PersistencePhasePath.COMPLAINT_TEST_ACTIVE_RECURRENT -> testActiveRecurrent.completed()
        PersistencePhasePath.COMPLAINT_TEST_ACTIVE_OWNER_DELETE_QUEUE -> testActiveOwnerDeleteQueue.completed()
        PersistencePhasePath.COMPLAINT_TEST_ACTIVE_ORDINARY_SEAL_RECOVERY -> testActiveOrdinarySealRecovery.completed()
        PersistencePhasePath.COMPLAINT_TEST_INSTALLATION_MANIFEST_PREPARE -> testInstallationManifestBoundary.completed()
        PersistencePhasePath.COMPLAINT_TEST_INSTALLATION_MANIFEST_PUBLICATION,
        PersistencePhasePath.COMPLAINT_TEST_INSTALLATION_MANIFEST_VERIFY,
        -> testInstallationManifestPublicationBoundary.completed()
        PersistencePhasePath.COMPLAINT_TEST_RUN_PURGE_PUBLICATION,
        PersistencePhasePath.COMPLAINT_TEST_RUN_PURGE_VERIFY,
        -> testRunPurgeBoundary.completed()
        PersistencePhasePath.COMPLAINT_TEST_TERMINAL_EPOCH_SEAL -> testTerminalEpochSealBoundary.completed()
        PersistencePhasePath.COMPLAINT_TEST_TERMINAL_QUIESCENCE -> testTerminalQuiescenceBoundary.completed()
        PersistencePhasePath.COMPLAINT_TEST_RUN_ERASURE_READ,
        PersistencePhasePath.COMPLAINT_TEST_RUN_ERASURE_BATCH,
        PersistencePhasePath.COMPLAINT_TEST_RUN_ERASURE_FINAL -> testRunErasureBoundary.completed()
        PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_TERMINAL_CAPTURE,
        PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_TERMINAL_ACQUIRE,
        PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_TERMINAL_PREPARE,
        PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_TERMINAL_RELOAD,
        PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_TERMINAL_SIGNATURE,
        PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_TERMINAL_COMPLETE,
        PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_TERMINAL_PROJECT,
        PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_TERMINAL_RELEASE,
        -> catalogTestRunTerminal.completed()
        PersistencePhasePath.COMPLAINT_TEST_RUN_TERMINAL_CATALOG_PREFLIGHT -> testRunTerminalCatalogPreflight.completed()
        PersistencePhasePath.COMPLAINT_TEST_ORDINARY_DRAIN -> testOrdinaryDrainBoundary.completed()

        PersistencePhasePath.COMPLAINT_INSTALLATION_ENROLLMENT -> installationEnrollment.completed()

        PersistencePhasePath.COMPLAINT_INSTALLATION_SESSION_PREFLIGHT,
        PersistencePhasePath.COMPLAINT_INSTALLATION_SESSION_REFRESH,
        -> installationSession.completed()

        PersistencePhasePath.COMPLAINT_INSTALLATION_DELETION_PREFLIGHT -> installationDeletionPreflight.completed()

        PersistencePhasePath.COMPLAINT_ADMIN_DELETE_AUTHORIZE,
        PersistencePhasePath.COMPLAINT_ADMIN_DELETE_RELOAD,
        PersistencePhasePath.COMPLAINT_ADMIN_DELETE_VERIFY,
        PersistencePhasePath.COMPLAINT_ADMIN_DELETE_APPLY,
        -> adminDelete.completed()
        PersistencePhasePath.COMPLAINT_ADMIN_DELETE_PREFLIGHT -> adminDeleteRead.completed()

        PersistencePhasePath.COMPLAINT_OWNER_DELETE_AUTHORIZE,
        PersistencePhasePath.COMPLAINT_OWNER_DELETE_RELOAD,
        PersistencePhasePath.COMPLAINT_OWNER_DELETE_VERIFY,
        PersistencePhasePath.COMPLAINT_OWNER_DELETE_APPLY,
        -> ownerDelete.completed()

        PersistencePhasePath.COMPLAINT_OWNER_DELETE_AUTHENTICATION,
        PersistencePhasePath.COMPLAINT_OWNER_DELETE_PREFLIGHT,
        PersistencePhasePath.COMPLAINT_OWNER_DELETE_STATUS,
        -> ownerDeleteRead.completed()

        PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_AUTHORIZE,
        PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_RELOAD,
        -> ownerDeleteAll.completed()

        PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_VERIFY -> ownerDeleteAllVerification.completed()

        PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_APPLY -> ownerDeleteAllApply.completed()

        PersistencePhasePath.COMPLAINT_INSTALLATION_CURRENT_STATE -> installationCurrentState.completed()

        PersistencePhasePath.COMPLAINT_OWNER_HISTORY_AUTHENTICATION,
        PersistencePhasePath.COMPLAINT_OWNER_HISTORY_PAGE,
        -> ownerHistory.completed()

        PersistencePhasePath.COMPLAINT_OWNER_DETAIL -> ownerDetail.completed()

        PersistencePhasePath.COMPLAINT_ADMIN_READ_AUTHENTICATION,
        PersistencePhasePath.COMPLAINT_ADMIN_SEARCH,
        PersistencePhasePath.COMPLAINT_ADMIN_DETAIL,
        PersistencePhasePath.COMPLAINT_ADMIN_STATS,
        -> adminRead.completed()

        PersistencePhasePath.COMPLAINT_ADMIN_EDIT_PREFLIGHT,
        PersistencePhasePath.COMPLAINT_ADMIN_EDIT,
        -> adminContent.completed()

        PersistencePhasePath.COMPLAINT_ADMIN_STATUS_PREFLIGHT,
        PersistencePhasePath.COMPLAINT_ADMIN_STATUS,
        -> adminStatus.completed()

        PersistencePhasePath.COMPLAINT_ADMIN_BATCH_STATUS_PREFLIGHT,
        PersistencePhasePath.COMPLAINT_ADMIN_BATCH_STATUS,
        -> adminBatchStatus.completed()

        PersistencePhasePath.COMPLAINT_OWNER_OPERATION_AUTHENTICATION,
        PersistencePhasePath.COMPLAINT_OWNER_CREATE_PREFLIGHT,
        PersistencePhasePath.COMPLAINT_OWNER_CREATE,
        PersistencePhasePath.COMPLAINT_OWNER_REPLY_PREFLIGHT,
        PersistencePhasePath.COMPLAINT_OWNER_REPLY,
        PersistencePhasePath.COMPLAINT_OWNER_OPERATION_STATUS,
        -> ownerOperation.completed()

        PersistencePhasePath.COMPLAINT_OWNER_EDIT_AUTHENTICATION,
        PersistencePhasePath.COMPLAINT_OWNER_EDIT_PREFLIGHT,
        PersistencePhasePath.COMPLAINT_OWNER_EDIT_STATUS,
        PersistencePhasePath.COMPLAINT_OWNER_EDIT,
        -> ownerEdit.completed()

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

        PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_READ,
        PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_PREPARE,
        PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_SIGNATURE,
        -> catalogSignerRotation.completed()

        PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_FINAL_READ,
        PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_COMPLETE,
        PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_PROJECT,
        -> catalogSignerRotationFinalization.completed()

        PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_READ,
        PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_PREPARE,
        PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_SIGNATURE,
        PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_COMPLETE,
        PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_PROJECT,
        -> catalogSignerRotationActivation.completed()

        PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_SNAPSHOT,
        PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_LEASE_ACQUIRE,
        PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_PREPARE,
        PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_PREPARED_RELOAD,
        PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_SIGNATURE,
        PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_SIGNED_RELOAD,
        PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_DELIVERY_RELOAD,
        PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_COMPLETE,
        PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_PENDING_RELOAD,
        PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_PROJECT_RELOAD,
        PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_PROJECT,
        PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_PROJECTED_RELOAD,
        -> catalogTestRunActivation.completed()

        PersistencePhasePath.COMPLAINT_DESIRED_SIGNED_GENESIS_FIRST -> signedGenesisFirstDesired.completed()

        PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PUBLISH_RECHECK -> catalogGenesisPublishRecheck.completed()

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
        catalogSignerRotationAttempt?.observeFailure(problem)
        signerRotationRecovery?.observeFailure(problem)
        signerRotationAuthor?.observeFailure(problem)
        signerRotationDelivery?.observeFailure(problem)
        signerRotationActivation?.observeFailure(problem)
        testRunActivation?.observeFailure(problem)
        testRegistration?.observeFailure(problem)
        initialAdmission?.observeFailure(problem)
        activeFirstCut?.observeFailure(problem)
        activeFirstCutSuccessor?.observeFailure(problem)
        testRecoveryRegistration?.observeFailure(problem)
        testActiveRegistration?.observeFailure(problem)
        testRunSealer?.observeFailure(problem)
        testOrdinarySealer?.observeFailure(problem)
        testActiveSealer?.observeFailure(problem)
        testInitialCheckpoint?.observeFailure(problem)
        testRecurrent?.observeFailure(problem)
        testRecurrentApply?.observeFailure(problem)
        testActiveQueue?.observeFailure(problem)
        testActiveSealRecovery?.observeFailure(problem)
        testInstallationManifest?.observeFailure(problem)
        testInstallationManifestPublication?.observeFailure(problem)
        testRunPurge?.observeFailure(problem)
        testTerminalEpochSeal?.observeFailure(problem)
        testTerminalQuiescence?.observeFailure(problem)
        testRunErasure?.observeFailure(problem)
        testRunTerminalCatalog?.observeFailure(problem)
        testRunOwnerDelete?.observeFailure(problem)
        testRunOwnerDeleteAll?.observeFailure(problem)
        testRunAdminDelete?.observeFailure(problem)
        testOrdinaryDrain?.observeFailure(problem)
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

    /** Exact original snapshot/acquire context facts, including entry failures before enter returns. */
    internal fun signerRotationRecoveryCleanupProven(original: CatalogSignerRotationPreparedRecoveryV1): Boolean =
        caller.isCurrent() && signerRotationRecovery === original &&
            path in setOf(PersistencePhasePath.COMPLAINT_CATALOG_SNAPSHOT, PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_ACQUIRE) &&
            stage === Stage.CLOSED && finalizerEnded && springSettled && refunded.get() &&
            acquisition?.quiescent() != false && !completionActive && (!beginDispatched || beginEnded) &&
            (rootStatus?.hasReturnedStatus() != true || completionEnded) && failure.get() !== PersistencePhaseFailureCode.CLEANUP_UNRESOLVED

    /** Exact original G1 bootstrap/acquire cleanup, never an exception flag or another phase's empty ThreadLocal. */
    internal fun signerRotationAuthorCleanupProven(original: CatalogSignerRotationInitialAuthorV1): Boolean =
        caller.isCurrent() && signerRotationAuthor === original &&
            path in setOf(
                PersistencePhasePath.COMPLAINT_CATALOG_SNAPSHOT,
                PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_COMPLETE,
                PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PROJECT,
                PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_ACQUIRE,
            ) && stage === Stage.CLOSED && finalizerEnded && springSettled && refunded.get() &&
            acquisition?.quiescent() != false && !completionActive && (!beginDispatched || beginEnded) &&
            (rootStatus?.hasReturnedStatus() != true || completionEnded) && failure.get() !== PersistencePhaseFailureCode.CLEANUP_UNRESOLVED

    /** All fixed delivery phases retain this exact owner, original refund and actual manager/lease cleanup. */
    internal fun signerRotationDeliveryCleanupProven(original: CatalogSignerRotationDeliveryV1): Boolean =
        caller.isCurrent() && signerRotationDelivery === original &&
            path in setOf(
                PersistencePhasePath.COMPLAINT_CATALOG_SNAPSHOT,
                PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_ACQUIRE,
                PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_FINAL_READ,
                PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_COMPLETE,
                PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_PROJECT,
            ) && stage === Stage.CLOSED && finalizerEnded && springSettled && refunded.get() &&
            acquisition?.quiescent() != false && !completionActive && (!beginDispatched || beginEnded) &&
            (rootStatus?.hasReturnedStatus() != true || completionEnded) && failure.get() !== PersistencePhaseFailureCode.CLEANUP_UNRESOLVED

    internal fun signerRotationActivationCleanupProven(original: CatalogSignerRotationActivationV1): Boolean =
        caller.isCurrent() && signerRotationActivation === original &&
            path in setOf(
                PersistencePhasePath.COMPLAINT_CATALOG_SNAPSHOT,
                PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_ACQUIRE,
                PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_READ,
                PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_PREPARE,
                PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_SIGNATURE,
                PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_COMPLETE,
                PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_PROJECT,
            ) && stage === Stage.CLOSED && finalizerEnded && springSettled && refunded.get() &&
            acquisition?.quiescent() != false && !completionActive && (!beginDispatched || beginEnded) &&
            (rootStatus?.hasReturnedStatus() != true || completionEnded) && failure.get() !== PersistencePhaseFailureCode.CLEANUP_UNRESOLVED

    internal fun testRunActivationCleanupProven(original: CatalogTestRunActivationV1): Boolean =
        caller.isCurrent() && testRunActivation === original && path.catalogTestRunActivation &&
            stage === Stage.CLOSED && finalizerEnded && springSettled && refunded.get() &&
            acquisition?.quiescent() != false && !completionActive && (!beginDispatched || beginEnded) &&
            (rootStatus?.hasReturnedStatus() != true || completionEnded) && failure.get() !== PersistencePhaseFailureCode.CLEANUP_UNRESOLVED

    internal fun testRegistrationCleanupProven(original: ComplaintTestNamespaceRegistrationAttemptV1): Boolean =
        caller.isCurrent() && testRegistration === original && path === PersistencePhasePath.COMPLAINT_TEST_NAMESPACE_REGISTRATION &&
            stage === Stage.CLOSED && finalizerEnded && springSettled && refunded.get() &&
            acquisition?.quiescent() != false && !completionActive && (!beginDispatched || beginEnded) &&
            (rootStatus?.hasReturnedStatus() != true || completionEnded) && failure.get() !== PersistencePhaseFailureCode.CLEANUP_UNRESOLVED

    internal fun testInitialAdmissionCleanupProven(original: ComplaintTestInitialAdmissionV1): Boolean =
        caller.isCurrent() && initialAdmission === original && path.testInitialAdmission &&
            stage === Stage.CLOSED && finalizerEnded && springSettled && refunded.get() &&
            acquisition?.quiescent() != false && !completionActive && (!beginDispatched || beginEnded) &&
            (rootStatus?.hasReturnedStatus() != true || completionEnded) && failure.get() !== PersistencePhaseFailureCode.CLEANUP_UNRESOLVED

    internal fun testActiveFirstCutCleanupProven(original: TestActiveFirstCutV1): Boolean =
        caller.isCurrent() && activeFirstCut === original && path.testActiveFirstCut &&
            stage === Stage.CLOSED && finalizerEnded && springSettled && refunded.get() &&
            acquisition?.quiescent() != false && !completionActive && (!beginDispatched || beginEnded) &&
            (rootStatus?.hasReturnedStatus() != true || completionEnded) && failure.get() !== PersistencePhaseFailureCode.CLEANUP_UNRESOLVED

    internal fun testActiveFirstCutSuccessorCleanupProven(original: TestActiveFirstCutSuccessorV1): Boolean =
        caller.isCurrent() && activeFirstCutSuccessor === original && path.testActiveFirstCutSuccessor &&
            stage === Stage.CLOSED && finalizerEnded && springSettled && refunded.get() &&
            acquisition?.quiescent() != false && !completionActive && (!beginDispatched || beginEnded) &&
            (rootStatus?.hasReturnedStatus() != true || completionEnded) && failure.get() !== PersistencePhaseFailureCode.CLEANUP_UNRESOLVED

    internal fun testRecoveryRegistrationCleanupProven(original: ComplaintTestNamespaceRecoveryRegistrationAttemptV1): Boolean =
        caller.isCurrent() && testRecoveryRegistration === original && path === PersistencePhasePath.COMPLAINT_TEST_NAMESPACE_RECOVERY_REGISTRATION &&
            stage === Stage.CLOSED && finalizerEnded && springSettled && refunded.get() &&
            acquisition?.quiescent() != false && !completionActive && (!beginDispatched || beginEnded) &&
            (rootStatus?.hasReturnedStatus() != true || completionEnded) && failure.get() !== PersistencePhaseFailureCode.CLEANUP_UNRESOLVED

    internal fun testActiveRegistrationCleanupProven(original: ComplaintTestNamespaceActiveRegistrationAttemptV1): Boolean =
        caller.isCurrent() && testActiveRegistration === original && path === PersistencePhasePath.COMPLAINT_TEST_NAMESPACE_ACTIVE_REGISTRATION &&
            stage === Stage.CLOSED && finalizerEnded && springSettled && refunded.get() &&
            acquisition?.quiescent() != false && !completionActive && (!beginDispatched || beginEnded) &&
            (rootStatus?.hasReturnedStatus() != true || completionEnded) && failure.get() !== PersistencePhaseFailureCode.CLEANUP_UNRESOLVED

    internal fun testRunSealingCleanupProven(original: TestRunSealingV1): Boolean =
        caller.isCurrent() && testRunSealer === original && path.testRunSealing &&
            stage === Stage.CLOSED && finalizerEnded && springSettled && refunded.get() &&
            acquisition?.quiescent() != false && !completionActive && (!beginDispatched || beginEnded) &&
            (rootStatus?.hasReturnedStatus() != true || completionEnded) && failure.get() !== PersistencePhaseFailureCode.CLEANUP_UNRESOLVED

    internal fun testOrdinarySealCleanupProven(original: TestRunOrdinarySealV1): Boolean =
        caller.isCurrent() && testOrdinarySealer === original && path === PersistencePhasePath.COMPLAINT_TEST_ORDINARY_SEAL &&
            stage === Stage.CLOSED && finalizerEnded && springSettled && refunded.get() &&
            acquisition?.quiescent() != false && !completionActive && (!beginDispatched || beginEnded) &&
            (rootStatus?.hasReturnedStatus() != true || completionEnded) && failure.get() !== PersistencePhaseFailureCode.CLEANUP_UNRESOLVED

    internal fun testActiveOrdinarySealCleanupProven(original: TestActiveOrdinarySealV1): Boolean =
        caller.isCurrent() && testActiveSealer === original && path.testActiveOrdinarySeal &&
            stage === Stage.CLOSED && finalizerEnded && springSettled && refunded.get() &&
            acquisition?.quiescent() != false && !completionActive && (!beginDispatched || beginEnded) &&
            (rootStatus?.hasReturnedStatus() != true || completionEnded) && failure.get() !== PersistencePhaseFailureCode.CLEANUP_UNRESOLVED

    internal fun testActiveInitialCheckpointCleanupProven(original: TestActiveInitialCheckpointV1): Boolean =
        caller.isCurrent() && testInitialCheckpoint === original && path.testActiveInitialCheckpoint &&
            stage === Stage.CLOSED && finalizerEnded && springSettled && refunded.get() &&
            acquisition?.quiescent() != false && !completionActive && (!beginDispatched || beginEnded) &&
            (rootStatus?.hasReturnedStatus() != true || completionEnded) && failure.get() !== PersistencePhaseFailureCode.CLEANUP_UNRESOLVED

    internal fun testActiveRecurrentCleanupProven(original: TestActiveRecurrentV1): Boolean =
        caller.isCurrent() && testRecurrent === original && path.testActiveRecurrent &&
            stage === Stage.CLOSED && finalizerEnded && springSettled && refunded.get() &&
            acquisition?.quiescent() != false && !completionActive && (!beginDispatched || beginEnded) &&
            (rootStatus?.hasReturnedStatus() != true || completionEnded) && failure.get() !== PersistencePhaseFailureCode.CLEANUP_UNRESOLVED

    internal fun testActiveRecurrentApplyCleanupProven(original: TestActiveRecurrentApplyV1): Boolean =
        caller.isCurrent() && testRecurrentApply === original && path in setOf(PersistencePhasePath.COMPLAINT_OWNER_DELETE_APPLY,
            PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_APPLY, PersistencePhasePath.COMPLAINT_ADMIN_DELETE_APPLY) &&
            stage === Stage.CLOSED && finalizerEnded && springSettled && refunded.get() &&
            acquisition?.quiescent() != false && !completionActive && (!beginDispatched || beginEnded) &&
            (rootStatus?.hasReturnedStatus() != true || completionEnded) && failure.get() !== PersistencePhaseFailureCode.CLEANUP_UNRESOLVED

    internal fun testActiveOwnerDeleteQueueCleanupProven(original: TestActiveOwnerDeleteQueueV1): Boolean =
        caller.isCurrent() && testActiveQueue === original && path in setOf(PersistencePhasePath.COMPLAINT_TEST_ACTIVE_OWNER_DELETE_QUEUE,
            PersistencePhasePath.COMPLAINT_OWNER_DELETE_APPLY, PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_APPLY, PersistencePhasePath.COMPLAINT_ADMIN_DELETE_APPLY) &&
            stage === Stage.CLOSED && finalizerEnded && springSettled && refunded.get() &&
            acquisition?.quiescent() != false && !completionActive && (!beginDispatched || beginEnded) &&
            (rootStatus?.hasReturnedStatus() != true || completionEnded) && failure.get() !== PersistencePhaseFailureCode.CLEANUP_UNRESOLVED

    internal fun testActiveOrdinarySealRecoveryCleanupProven(original: TestActiveOrdinarySealRecoveryV1): Boolean =
        caller.isCurrent() && testActiveSealRecovery === original && path.testActiveOrdinarySealRecovery &&
            stage === Stage.CLOSED && finalizerEnded && springSettled && refunded.get() &&
            acquisition?.quiescent() != false && !completionActive && (!beginDispatched || beginEnded) &&
            (rootStatus?.hasReturnedStatus() != true || completionEnded) && failure.get() !== PersistencePhaseFailureCode.CLEANUP_UNRESOLVED

    internal fun testInstallationManifestCleanupProven(original: TestRunInstallationManifestV1): Boolean =
        caller.isCurrent() && testInstallationManifest === original && path === PersistencePhasePath.COMPLAINT_TEST_INSTALLATION_MANIFEST_PREPARE &&
            stage === Stage.CLOSED && finalizerEnded && springSettled && refunded.get() &&
            acquisition?.quiescent() != false && !completionActive && (!beginDispatched || beginEnded) &&
            (rootStatus?.hasReturnedStatus() != true || completionEnded) && failure.get() !== PersistencePhaseFailureCode.CLEANUP_UNRESOLVED

    internal fun testInstallationManifestPublicationCleanupProven(original: TestRunInstallationManifestPublicationV1): Boolean =
        testInstallationManifestPublicationResourcesRetired(original) && failure.get() !== PersistencePhaseFailureCode.CLEANUP_UNRESOLVED

    /** Physical retirement only. UNKNOWN and quarantined originals never gain a successful result from it. */
    internal fun testInstallationManifestPublicationResourcesRetired(original: TestRunInstallationManifestPublicationV1): Boolean =
        caller.isCurrent() && testInstallationManifestPublication === original && path in setOf(
            PersistencePhasePath.COMPLAINT_TEST_INSTALLATION_MANIFEST_PUBLICATION, PersistencePhasePath.COMPLAINT_TEST_INSTALLATION_MANIFEST_VERIFY) &&
            stage === Stage.CLOSED && finalizerEnded && springSettled && refunded.get() &&
            acquisition?.quiescent() != false && !completionActive && (!beginDispatched || beginEnded) &&
            (rootStatus?.hasReturnedStatus() != true || completionEnded)

    internal fun testRunTerminalCatalogCleanupProven(original: CatalogTestRunTerminalV1): Boolean =
        testRunTerminalCatalogResourcesRetired(original) && failure.get() !== PersistencePhaseFailureCode.CLEANUP_UNRESOLVED

    /** Positive original physical retirement only. UNKNOWN never turns into a successful capability. */
    internal fun testRunTerminalCatalogResourcesRetired(original: CatalogTestRunTerminalV1): Boolean =
        caller.isCurrent() && testRunTerminalCatalog === original &&
            (path.catalogTestRunTerminal || path === PersistencePhasePath.COMPLAINT_TEST_RUN_TERMINAL_CATALOG_PREFLIGHT) &&
            stage === Stage.CLOSED && finalizerEnded && springSettled && refunded.get() &&
            acquisition?.quiescent() != false && !completionActive && (!beginDispatched || beginEnded) &&
            (rootStatus?.hasReturnedStatus() != true || completionEnded)

    internal fun testRunErasureCleanupProven(original: TestRunErasureV1): Boolean =
        testRunErasureResourcesRetired(original) && failure.get() !== PersistencePhaseFailureCode.CLEANUP_UNRESOLVED

    /** Physical retirement only. Neither UNKNOWN nor a quarantined original becomes successful. */
    internal fun testRunErasureResourcesRetired(original: TestRunErasureV1): Boolean =
        caller.isCurrent() && testRunErasure === original && path.testRunErasure &&
            stage === Stage.CLOSED && finalizerEnded && springSettled && refunded.get() &&
            acquisition?.quiescent() != false && !completionActive && (!beginDispatched || beginEnded) &&
            (rootStatus?.hasReturnedStatus() != true || completionEnded)

    internal fun testTerminalQuiescenceCleanupProven(original: TestRunTerminalQuiescenceV1): Boolean =
        testTerminalQuiescenceResourcesRetired(original) && failure.get() !== PersistencePhaseFailureCode.CLEANUP_UNRESOLVED

    /** Physical retirement only. Neither UNKNOWN nor a quarantined original becomes successful. */
    internal fun testTerminalQuiescenceResourcesRetired(original: TestRunTerminalQuiescenceV1): Boolean =
        caller.isCurrent() && testTerminalQuiescence === original && path === PersistencePhasePath.COMPLAINT_TEST_TERMINAL_QUIESCENCE &&
            stage === Stage.CLOSED && finalizerEnded && springSettled && refunded.get() &&
            acquisition?.quiescent() != false && !completionActive && (!beginDispatched || beginEnded) &&
            (rootStatus?.hasReturnedStatus() != true || completionEnded)

    internal fun testRunPurgeCleanupProven(original: TestRunPurgePublicationV1): Boolean =
        testRunPurgeResourcesRetired(original) && failure.get() !== PersistencePhaseFailureCode.CLEANUP_UNRESOLVED

    /** Physical retirement only. UNKNOWN and quarantined originals never gain a successful result from it. */
    internal fun testRunPurgeResourcesRetired(original: TestRunPurgePublicationV1): Boolean =
        caller.isCurrent() && testRunPurge === original && path in setOf(
            PersistencePhasePath.COMPLAINT_TEST_RUN_PURGE_PUBLICATION, PersistencePhasePath.COMPLAINT_TEST_RUN_PURGE_VERIFY) &&
            stage === Stage.CLOSED && finalizerEnded && springSettled && refunded.get() &&
            acquisition?.quiescent() != false && !completionActive && (!beginDispatched || beginEnded) &&
            (rootStatus?.hasReturnedStatus() != true || completionEnded)

    internal fun testTerminalEpochSealCleanupProven(original: TestRunTerminalEpochSealV1): Boolean =
        testTerminalEpochSealResourcesRetired(original) && failure.get() !== PersistencePhaseFailureCode.CLEANUP_UNRESOLVED

    /** Physical retirement only. UNKNOWN and quarantined originals never gain a successful result from it. */
    internal fun testTerminalEpochSealResourcesRetired(original: TestRunTerminalEpochSealV1): Boolean =
        caller.isCurrent() && testTerminalEpochSeal === original && path === PersistencePhasePath.COMPLAINT_TEST_TERMINAL_EPOCH_SEAL &&
            stage === Stage.CLOSED && finalizerEnded && springSettled && refunded.get() &&
            acquisition?.quiescent() != false && !completionActive && (!beginDispatched || beginEnded) &&
            (rootStatus?.hasReturnedStatus() != true || completionEnded)

    internal fun testOrdinaryDrainCleanupProven(original: TestRunOrdinaryDrainV1): Boolean =
        caller.isCurrent() && testOrdinaryDrain === original &&
            path in setOf(PersistencePhasePath.COMPLAINT_TEST_ORDINARY_DRAIN, PersistencePhasePath.COMPLAINT_OWNER_DELETE_APPLY,
                PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_APPLY, PersistencePhasePath.COMPLAINT_ADMIN_DELETE_APPLY) &&
            stage === Stage.CLOSED && finalizerEnded && springSettled && refunded.get() &&
            acquisition?.quiescent() != false && !completionActive && (!beginDispatched || beginEnded) &&
            (rootStatus?.hasReturnedStatus() != true || completionEnded) && failure.get() !== PersistencePhaseFailureCode.CLEANUP_UNRESOLVED

    internal fun testRunOwnerDeleteCleanupProven(original: TestRunOwnerDeleteContinuationV1): Boolean =
        caller.isCurrent() && testRunOwnerDelete === original &&
            path in setOf(PersistencePhasePath.COMPLAINT_OWNER_DELETE_RELOAD, PersistencePhasePath.COMPLAINT_OWNER_DELETE_VERIFY, PersistencePhasePath.COMPLAINT_OWNER_DELETE_APPLY) &&
            stage === Stage.CLOSED && finalizerEnded && springSettled && refunded.get() &&
            acquisition?.quiescent() != false && !completionActive && (!beginDispatched || beginEnded) &&
            (rootStatus?.hasReturnedStatus() != true || completionEnded) && failure.get() !== PersistencePhaseFailureCode.CLEANUP_UNRESOLVED

    internal fun requireTestRunOwnerDeleteReload(original: TestRunOwnerDeleteContinuationV1, graph: TestOwnerDeleteLocalGraphV1, jdbc: JdbcTemplate) {
        if (testRunOwnerDelete !== original || path !== PersistencePhasePath.COMPLAINT_OWNER_DELETE_RELOAD) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        original.requirePersistence(ownership, jdbc, graph)
    }

    internal fun requireTestRunOwnerDeleteApply(graph: TestOwnerDeleteLocalGraphV1, jdbc: JdbcTemplate, input: TestOwnerDeleteApplyInputV1) {
        if (testRecurrentApply != null) {
            if (path !== PersistencePhasePath.COMPLAINT_OWNER_DELETE_APPLY || testActiveQueue != null || testOrdinaryDrain != null ||
                testRunOwnerDelete != null || testRunOwnerDeleteAll != null || testRunAdminDelete != null) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            testRecurrentApply.requireRecoveryPersistence(ownership, jdbc, graph)
            testRecurrentApply.requireRecoveryInput(input)
        } else if (testActiveQueue != null) {
            if (path !== PersistencePhasePath.COMPLAINT_OWNER_DELETE_APPLY || testRunOwnerDelete != null || testOrdinaryDrain != null) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            testActiveQueue.requireRecoveryPersistence(ownership, jdbc, graph)
            testActiveQueue.requireRecoveryInput(input)
        } else if (testOrdinaryDrain != null) {
            if (path !== PersistencePhasePath.COMPLAINT_OWNER_DELETE_APPLY || testRunOwnerDelete != null) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            testOrdinaryDrain.requireRecoveryPersistence(ownership, jdbc, graph)
            testOrdinaryDrain.requireRecoveryInput(input)
        } else if (testRunOwnerDelete == null) {
            if (graph.recoveryRegistration != null) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        } else {
            if (path !== PersistencePhasePath.COMPLAINT_OWNER_DELETE_APPLY) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            testRunOwnerDelete.requirePersistence(ownership, jdbc, graph)
            testRunOwnerDelete.requireApplyInput(input)
        }
    }

    internal fun requireTestRunOwnerDeleteVerify(graph: TestOwnerDeleteLocalGraphV1, jdbc: JdbcTemplate, input: TestOwnerDeleteVerificationInputV1) {
        if (testRunOwnerDelete == null) {
            if (graph.recoveryRegistration != null) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        } else {
            if (path !== PersistencePhasePath.COMPLAINT_OWNER_DELETE_VERIFY) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            testRunOwnerDelete.requirePersistence(ownership, jdbc, graph)
            testRunOwnerDelete.requireVerificationInput(input)
        }
    }

    internal fun requireTestRunOwnerDeleteVerificationRun(graph: TestOwnerDeleteLocalGraphV1, jdbc: JdbcTemplate, authorizedAt: java.time.Instant) {
        if (graph.recoveryRegistration != null) {
            if (path !== PersistencePhasePath.COMPLAINT_OWNER_DELETE_VERIFY) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            requireTestRunOwnerDeleteRun(graph, jdbc)
            checkNotNull(testRunOwnerDelete).requireEarlierAuthorization(authorizedAt)
        }
    }

    internal fun requireTestRunOwnerDeleteRun(graph: TestOwnerDeleteLocalGraphV1, jdbc: JdbcTemplate) {
        if (testRecurrentApply != null) {
            if (path !== PersistencePhasePath.COMPLAINT_OWNER_DELETE_APPLY) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            testRecurrentApply.requireRecoveryPersistence(ownership, jdbc, graph)
            testRecurrentApply.requireRecoveryRun(jdbc)
            return
        }
        if (testActiveQueue != null) {
            if (path !== PersistencePhasePath.COMPLAINT_OWNER_DELETE_APPLY) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            testActiveQueue.requireRecoveryPersistence(ownership, jdbc, graph)
            testActiveQueue.requireRecoveryRun(jdbc)
        } else if (testOrdinaryDrain != null) {
            testOrdinaryDrain.requireRecoveryPersistence(ownership, jdbc, graph)
            testOrdinaryDrain.requireRecoveryRun(jdbc)
        } else {
            val original = testRunOwnerDelete ?: refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            original.requirePersistence(ownership, jdbc, graph)
            original.requireSealedRun(jdbc)
        }
    }

    internal fun registeredActiveRecurrentControls(graph: TestOwnerDeleteLocalGraphV1, jdbc: JdbcTemplate): Long? {
        val original = testRecurrentApply ?: return null
        if (path !in setOf(PersistencePhasePath.COMPLAINT_OWNER_DELETE_APPLY, PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_APPLY,
                PersistencePhasePath.COMPLAINT_ADMIN_DELETE_APPLY) || graph.recoveryRegistration !== original.registration) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        original.requireRecoveryPersistence(ownership, jdbc, graph)
        return original.requireRecoveryControls(jdbc)
    }

    internal fun registeredActiveQueueControls(graph: TestOwnerDeleteLocalGraphV1, jdbc: JdbcTemplate): Long? {
        val original = testActiveQueue ?: return null
        if (path !in setOf(PersistencePhasePath.COMPLAINT_OWNER_DELETE_APPLY, PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_APPLY,
                PersistencePhasePath.COMPLAINT_ADMIN_DELETE_APPLY) || graph.recoveryRegistration !== original.registration) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        original.requireRecoveryPersistence(ownership, jdbc, graph)
        return original.requireRecoveryControls(jdbc)
    }

    internal fun registeredInventoryControls(graph: TestOwnerDeleteLocalGraphV1, jdbc: JdbcTemplate): Long? {
        val original = testOrdinaryDrain ?: return null
        if (path !in setOf(PersistencePhasePath.COMPLAINT_OWNER_DELETE_APPLY, PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_APPLY, PersistencePhasePath.COMPLAINT_ADMIN_DELETE_APPLY) ||
            graph.recoveryRegistration == null) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        original.requireRecoveryPersistence(ownership, jdbc, graph)
        return original.requireRecoveryControls(jdbc)
    }

    /** Existing primary RELOAD/APPLY only. A selected child keeps its actual owner and phase. */
    internal fun registeredRetainedDrainPrimaryControls(graph: TestOwnerDeleteLocalGraphV1, jdbc: JdbcTemplate): TestOwnerDeleteControlBindingV1.Locked? {
        val original = testRunOwnerDelete ?: return null
        if (path !in setOf(PersistencePhasePath.COMPLAINT_OWNER_DELETE_RELOAD, PersistencePhasePath.COMPLAINT_OWNER_DELETE_APPLY) ||
            testActiveQueue != null || testOrdinaryDrain != null || testRunOwnerDeleteAll != null || testRunAdminDelete != null)
            refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        original.requirePersistence(ownership, jdbc, graph)
        return original.retainedDrainControls(jdbc, graph)
    }

    internal fun testRunOwnerDeleteAllCleanupProven(original: TestRunOwnerDeleteAllContinuationV1): Boolean =
        caller.isCurrent() && testRunOwnerDeleteAll === original &&
            path in setOf(PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_RELOAD, PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_VERIFY, PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_APPLY) &&
            stage === Stage.CLOSED && finalizerEnded && springSettled && refunded.get() &&
            acquisition?.quiescent() != false && !completionActive && (!beginDispatched || beginEnded) &&
            (rootStatus?.hasReturnedStatus() != true || completionEnded) && failure.get() !== PersistencePhaseFailureCode.CLEANUP_UNRESOLVED

    internal fun requireTestRunOwnerDeleteAllReload(original: TestRunOwnerDeleteAllContinuationV1, graph: TestOwnerDeleteLocalGraphV1, jdbc: JdbcTemplate) {
        if (testRunOwnerDeleteAll !== original || path !== PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_RELOAD) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        original.requirePersistence(ownership, jdbc, graph)
    }

    internal fun requireTestRunOwnerDeleteAllApply(graph: TestOwnerDeleteLocalGraphV1, jdbc: JdbcTemplate, input: OwnerDeleteAllApplyInputV1) {
        if (testRecurrentApply != null) {
            if (path !== PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_APPLY || testActiveQueue != null || testOrdinaryDrain != null ||
                testRunOwnerDelete != null || testRunOwnerDeleteAll != null || testRunAdminDelete != null) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            testRecurrentApply.requireRecoveryPersistence(ownership, jdbc, graph)
            testRecurrentApply.requireRecoveryInput(input)
        } else if (testActiveQueue != null) {
            if (path !== PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_APPLY || testOrdinaryDrain != null || testRunOwnerDeleteAll != null ||
                testRunAdminDelete != null || testRunOwnerDelete != null) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            testActiveQueue.requireRecoveryPersistence(ownership, jdbc, graph)
            testActiveQueue.requireRecoveryInput(input)
        } else if (testOrdinaryDrain != null) {
            if (path !== PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_APPLY || testRunOwnerDeleteAll != null || testRunAdminDelete != null || testRunOwnerDelete != null) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            testOrdinaryDrain.requireRecoveryPersistence(ownership, jdbc, graph)
            testOrdinaryDrain.requireRecoveryInput(input)
        } else if (testRunOwnerDeleteAll == null) {
            if (graph.recoveryRegistration != null) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        } else {
            if (path !== PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_APPLY) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            testRunOwnerDeleteAll.requirePersistence(ownership, jdbc, graph)
            testRunOwnerDeleteAll.requireApplyInput(input)
        }
    }

    internal fun requireTestRunOwnerDeleteAllVerify(graph: TestOwnerDeleteLocalGraphV1, jdbc: JdbcTemplate, input: OwnerDeleteAllVerificationInputV1) {
        if (testRunOwnerDeleteAll == null) {
            if (graph.recoveryRegistration != null) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        } else {
            if (path !== PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_VERIFY) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            testRunOwnerDeleteAll.requirePersistence(ownership, jdbc, graph)
            testRunOwnerDeleteAll.requireVerificationInput(input)
        }
    }

    internal fun requireTestRunOwnerDeleteAllVerificationRun(graph: TestOwnerDeleteLocalGraphV1, jdbc: JdbcTemplate, authorizedAt: java.time.Instant) {
        if (graph.recoveryRegistration != null) {
            if (path !== PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_VERIFY) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            requireTestRunOwnerDeleteAllRun(graph, jdbc)
            checkNotNull(testRunOwnerDeleteAll).requireEarlierAuthorization(authorizedAt)
        }
    }

    internal fun requireTestRunOwnerDeleteAllRun(graph: TestOwnerDeleteLocalGraphV1, jdbc: JdbcTemplate) {
        if (testRecurrentApply != null) {
            if (path !== PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_APPLY) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            testRecurrentApply.requireRecoveryPersistence(ownership, jdbc, graph)
            testRecurrentApply.requireRecoveryRun(jdbc)
            return
        }
        if (testActiveQueue != null) {
            if (path !== PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_APPLY) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            testActiveQueue.requireRecoveryPersistence(ownership, jdbc, graph)
            testActiveQueue.requireRecoveryRun(jdbc)
            return
        }
        if (testOrdinaryDrain != null) {
            if (path !== PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_APPLY) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            testOrdinaryDrain.requireRecoveryPersistence(ownership, jdbc, graph)
            testOrdinaryDrain.requireRecoveryRun(jdbc)
            return
        }
        val original = testRunOwnerDeleteAll ?: refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        original.requirePersistence(ownership, jdbc, graph)
        original.requireSealedRun(jdbc)
    }

    internal fun testRunAdminDeleteCleanupProven(original: TestRunAdminDeleteContinuationV1): Boolean =
        caller.isCurrent() && testRunAdminDelete === original &&
            path in setOf(PersistencePhasePath.COMPLAINT_ADMIN_DELETE_RELOAD, PersistencePhasePath.COMPLAINT_ADMIN_DELETE_VERIFY, PersistencePhasePath.COMPLAINT_ADMIN_DELETE_APPLY) &&
            stage === Stage.CLOSED && finalizerEnded && springSettled && refunded.get() &&
            acquisition?.quiescent() != false && !completionActive && (!beginDispatched || beginEnded) &&
            (rootStatus?.hasReturnedStatus() != true || completionEnded) && failure.get() !== PersistencePhaseFailureCode.CLEANUP_UNRESOLVED

    internal fun requireTestRunAdminDeleteReload(original: TestRunAdminDeleteContinuationV1, graph: TestOwnerDeleteLocalGraphV1, jdbc: JdbcTemplate) {
        if (testRunAdminDelete !== original || path !== PersistencePhasePath.COMPLAINT_ADMIN_DELETE_RELOAD) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        original.requirePersistence(ownership, jdbc, graph)
    }

    internal fun requireTestRunAdminDeleteApply(graph: TestOwnerDeleteLocalGraphV1, jdbc: JdbcTemplate, input: TestAdminDeleteApplyInputV1) {
        if (testRecurrentApply != null) {
            if (path !== PersistencePhasePath.COMPLAINT_ADMIN_DELETE_APPLY || testActiveQueue != null || testOrdinaryDrain != null ||
                testRunOwnerDelete != null || testRunOwnerDeleteAll != null || testRunAdminDelete != null) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            testRecurrentApply.requireRecoveryPersistence(ownership, jdbc, graph)
            testRecurrentApply.requireRecoveryInput(input)
        } else if (testActiveQueue != null) {
            if (path !== PersistencePhasePath.COMPLAINT_ADMIN_DELETE_APPLY || testOrdinaryDrain != null || testRunAdminDelete != null ||
                testRunOwnerDeleteAll != null || testRunOwnerDelete != null) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            testActiveQueue.requireRecoveryPersistence(ownership, jdbc, graph)
            testActiveQueue.requireRecoveryInput(input)
        } else if (testOrdinaryDrain != null) {
            if (path !== PersistencePhasePath.COMPLAINT_ADMIN_DELETE_APPLY || testRunAdminDelete != null || testRunOwnerDeleteAll != null || testRunOwnerDelete != null) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            testOrdinaryDrain.requireRecoveryPersistence(ownership, jdbc, graph)
            testOrdinaryDrain.requireRecoveryInput(input)
        } else if (testRunAdminDelete == null) {
            if (graph.recoveryRegistration != null) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        } else {
            if (path !== PersistencePhasePath.COMPLAINT_ADMIN_DELETE_APPLY) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            testRunAdminDelete.requirePersistence(ownership, jdbc, graph)
            testRunAdminDelete.requireApplyInput(input)
        }
    }

    internal fun requireTestRunAdminDeleteVerify(graph: TestOwnerDeleteLocalGraphV1, jdbc: JdbcTemplate, input: TestAdminDeleteVerificationInputV1) {
        if (testRunAdminDelete == null) {
            if (graph.recoveryRegistration != null) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        } else {
            if (path !== PersistencePhasePath.COMPLAINT_ADMIN_DELETE_VERIFY) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            testRunAdminDelete.requirePersistence(ownership, jdbc, graph)
            testRunAdminDelete.requireVerificationInput(input)
        }
    }

    internal fun requireTestRunAdminDeleteVerificationRun(graph: TestOwnerDeleteLocalGraphV1, jdbc: JdbcTemplate, authorizedAt: java.time.Instant) {
        if (graph.recoveryRegistration != null) {
            if (path !== PersistencePhasePath.COMPLAINT_ADMIN_DELETE_VERIFY) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            requireTestRunAdminDeleteRun(graph, jdbc)
            checkNotNull(testRunAdminDelete).requireEarlierAuthorization(authorizedAt)
        }
    }

    internal fun requireTestRunAdminDeleteRun(graph: TestOwnerDeleteLocalGraphV1, jdbc: JdbcTemplate) {
        if (testRecurrentApply != null) {
            if (path !== PersistencePhasePath.COMPLAINT_ADMIN_DELETE_APPLY) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            testRecurrentApply.requireRecoveryPersistence(ownership, jdbc, graph)
            testRecurrentApply.requireRecoveryRun(jdbc)
            return
        }
        if (testActiveQueue != null) {
            if (path !== PersistencePhasePath.COMPLAINT_ADMIN_DELETE_APPLY) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            testActiveQueue.requireRecoveryPersistence(ownership, jdbc, graph)
            testActiveQueue.requireRecoveryRun(jdbc)
            return
        }
        if (testOrdinaryDrain != null) {
            if (path !== PersistencePhasePath.COMPLAINT_ADMIN_DELETE_APPLY) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            testOrdinaryDrain.requireRecoveryPersistence(ownership, jdbc, graph)
            testOrdinaryDrain.requireRecoveryRun(jdbc)
            return
        }
        val original = testRunAdminDelete ?: refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        original.requirePersistence(ownership, jdbc, graph)
        original.requireSealedRun(jdbc)
    }

    internal fun requireTestRunDeletionRun(graph: TestOwnerDeleteLocalGraphV1, jdbc: JdbcTemplate) {
        if (path in setOf(PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_RELOAD, PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_APPLY))
            requireTestRunOwnerDeleteAllRun(graph, jdbc)
        else if (path in setOf(PersistencePhasePath.COMPLAINT_ADMIN_DELETE_RELOAD, PersistencePhasePath.COMPLAINT_ADMIN_DELETE_APPLY))
            requireTestRunAdminDeleteRun(graph, jdbc)
        else requireTestRunOwnerDeleteRun(graph, jdbc)
    }

    internal fun initialTestActivationPrepare(fence: PersistenceComplaintMaintenanceFenceV1, selected: Connection): Boolean {
        selectedHolder.requireMaintenanceFence(fence, selected)
        if (path !== PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_PREPARE) return false
        val original = testRunActivation ?: refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        original.requireInitialMaintenancePrepare(ownership)
        return true
    }

    internal fun initialTestAdmissionRelease(fence: PersistenceComplaintMaintenanceFenceV1, selected: Connection): Boolean {
        selectedHolder.requireMaintenanceFence(fence, selected)
        if (path !== PersistencePhasePath.COMPLAINT_TEST_INITIAL_ADMISSION_RELEASE) return false
        val original = initialAdmission ?: refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        original.requireExclusiveRelease(ownership)
        return true
    }

    /** Existing PREPARED phases only: a protected pool cannot borrow a desired-only or recovery request route. */
    private fun bindRegisteredInitialDeletion(original: me.manga.kira.backend.complaint.infrastructure.TestOwnerDeleteProcessBindingV1) {
        requireCaller()
        if (stage !== Stage.PREPARED || registeredInitialDeletion != null) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        original.requirePhaseOwner(ownership, path)
        registeredInitialDeletion = original
    }

    internal fun bindInitialDeletionRead(original: me.manga.kira.backend.complaint.infrastructure.TestOwnerDeleteProcessBindingV1) {
        if (path !in setOf(PersistencePhasePath.COMPLAINT_OWNER_DELETE_AUTHENTICATION, PersistencePhasePath.COMPLAINT_OWNER_DELETE_PREFLIGHT,
                PersistencePhasePath.COMPLAINT_OWNER_DELETE_STATUS, PersistencePhasePath.COMPLAINT_INSTALLATION_DELETION_PREFLIGHT,
                PersistencePhasePath.COMPLAINT_ADMIN_READ_AUTHENTICATION, PersistencePhasePath.COMPLAINT_ADMIN_DELETE_PREFLIGHT,
                PersistencePhasePath.COMPLAINT_OWNER_DELETE_RELOAD, PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_RELOAD,
                PersistencePhasePath.COMPLAINT_ADMIN_DELETE_RELOAD)) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        original.requireReadAdmission()
        bindRegisteredInitialDeletion(original)
    }

    internal fun bindInitialOwnerDeleteAuthorize(original: me.manga.kira.backend.complaint.infrastructure.TestOwnerDeleteProcessBindingV1,
        store: me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteStore, handoff: ComplaintAdmittedOwnerDelete,
        lane: me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationLanesV1.TestOwnerDeleteReservation) {
        if (path !== PersistencePhasePath.COMPLAINT_OWNER_DELETE_AUTHORIZE || store.graph.initialDeletion !== original) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        original.requireAdmission(handoff); lane.requireAuthorizing(original, store)
        bindRegisteredInitialDeletion(original)
        initialOwnerDeleteStore = store; initialOwnerDeleteLane = lane
    }

    internal fun bindInitialAllDeleteAuthorize(original: me.manga.kira.backend.complaint.infrastructure.TestOwnerDeleteProcessBindingV1,
        store: me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteAllStore, handoff: ComplaintAdmittedOwnerDeleteAll,
        lane: me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationLanesV1.TestOwnerDeleteAllReservation) {
        if (path !== PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_AUTHORIZE || store.testGraph.initialDeletion !== original) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        original.requireAdmission(handoff); lane.requireAuthorizing(original, store)
        bindRegisteredInitialDeletion(original)
        initialAllDeleteStore = store; initialAllDeleteLane = lane
    }

    internal fun bindInitialAdminDeleteAuthorize(original: me.manga.kira.backend.complaint.infrastructure.TestOwnerDeleteProcessBindingV1,
        store: me.manga.kira.backend.complaint.infrastructure.JdbcComplaintAdminDeleteStore, handoff: ComplaintAdmittedAdminErasure,
        lane: me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationLanesV1.TestAdminDeleteReservation) {
        if (path !== PersistencePhasePath.COMPLAINT_ADMIN_DELETE_AUTHORIZE || store.graph.initialDeletion !== original) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        original.requireAdmission(handoff); lane.requireAuthorizing(original, store)
        bindRegisteredInitialDeletion(original)
        initialAdminDeleteStore = store; initialAdminDeleteLane = lane
    }

    internal fun bindInitialOwnerDeleteVerify(original: me.manga.kira.backend.complaint.infrastructure.TestOwnerDeleteProcessBindingV1,
        store: me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteVerificationStore,
        input: me.manga.kira.backend.complaint.infrastructure.TestOwnerDeleteVerificationInputV1) {
        if (path !== PersistencePhasePath.COMPLAINT_OWNER_DELETE_VERIFY) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        store.requireInitialInput(original, input)
        bindRegisteredInitialDeletion(original)
        initialOwnerDeleteVerificationInput = input
    }

    internal fun bindInitialAllDeleteVerify(original: me.manga.kira.backend.complaint.infrastructure.TestOwnerDeleteProcessBindingV1,
        store: me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteAllVerificationStore,
        input: me.manga.kira.backend.complaint.infrastructure.OwnerDeleteAllVerificationInputV1) {
        if (path !== PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_VERIFY) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        store.requireInitialInput(original, input)
        bindRegisteredInitialDeletion(original)
        initialAllDeleteVerificationInput = input
    }

    internal fun bindInitialAdminDeleteVerify(original: me.manga.kira.backend.complaint.infrastructure.TestOwnerDeleteProcessBindingV1,
        store: me.manga.kira.backend.complaint.infrastructure.JdbcComplaintAdminDeleteVerificationStore,
        input: me.manga.kira.backend.complaint.infrastructure.TestAdminDeleteVerificationInputV1) {
        if (path !== PersistencePhasePath.COMPLAINT_ADMIN_DELETE_VERIFY) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        store.requireInitialInput(original, input)
        bindRegisteredInitialDeletion(original)
        initialAdminDeleteVerificationInput = input
    }

    internal fun requireInitialOwnerDeleteVerificationInput(input: me.manga.kira.backend.complaint.infrastructure.TestOwnerDeleteVerificationInputV1) {
        if (registeredInitialDeletion != null && initialOwnerDeleteVerificationInput !== input) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
    }
    internal fun requireInitialAllDeleteVerificationInput(input: me.manga.kira.backend.complaint.infrastructure.OwnerDeleteAllVerificationInputV1) {
        if (registeredInitialDeletion != null && initialAllDeleteVerificationInput !== input) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
    }
    internal fun requireInitialAdminDeleteVerificationInput(input: me.manga.kira.backend.complaint.infrastructure.TestAdminDeleteVerificationInputV1) {
        if (registeredInitialDeletion != null && initialAdminDeleteVerificationInput !== input) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
    }

    /** Caller-selected graphs must be the privately bound graph, even when pool identities happen to match. */
    internal fun requireRegisteredInitialDeletion(graph: TestOwnerDeleteLocalGraphV1?, jdbc: JdbcTemplate) {
        requireStepUpResource(jdbc, path)
        if (graph?.initialDeletion !== registeredInitialDeletion) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        requireInitialDeletionPoolPolicy()
        registeredInitialDeletion?.let { original ->
            original.requirePhaseOwner(ownership, path)
            when (path) {
                PersistencePhasePath.COMPLAINT_OWNER_DELETE_AUTHORIZE -> checkNotNull(initialOwnerDeleteLane).requireAuthorizing(original, checkNotNull(initialOwnerDeleteStore))
                PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_AUTHORIZE -> checkNotNull(initialAllDeleteLane).requireAuthorizing(original, checkNotNull(initialAllDeleteStore))
                PersistencePhasePath.COMPLAINT_ADMIN_DELETE_AUTHORIZE -> checkNotNull(initialAdminDeleteLane).requireAuthorizing(original, checkNotNull(initialAdminDeleteStore))
                else -> Unit
            }
        }
    }

    internal fun requireInitialOwnerDeleteStore(store: me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteStore) {
        if (registeredInitialDeletion != null && path === PersistencePhasePath.COMPLAINT_OWNER_DELETE_AUTHORIZE && initialOwnerDeleteStore !== store)
            refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
    }
    internal fun requireInitialAllDeleteStore(store: me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteAllStore) {
        if (registeredInitialDeletion != null && path === PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_AUTHORIZE && initialAllDeleteStore !== store)
            refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
    }
    internal fun requireInitialAdminDeleteStore(store: me.manga.kira.backend.complaint.infrastructure.JdbcComplaintAdminDeleteStore) {
        if (registeredInitialDeletion != null && path === PersistencePhasePath.COMPLAINT_ADMIN_DELETE_AUTHORIZE && initialAdminDeleteStore !== store)
            refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
    }

    private fun requireInitialDeletionPoolPolicy() {
        // Existing privately bound recovery/queue owners keep their closed continuation protocol.
        // They cannot bind an AUTHORIZE path and must still authenticate their graph when retaining work.
        if (testRunOwnerDelete != null || testRunOwnerDeleteAll != null || testRunAdminDelete != null || testOrdinaryDrain != null || testActiveQueue != null || testRecurrentApply != null) {
            if (registeredInitialDeletion != null) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        } else ownership.dataSource.requireTestInitialCheckpointDeletion(registeredInitialDeletion?.policy)
    }

    internal fun beginInitialDeletionControls(original: me.manga.kira.backend.complaint.infrastructure.TestOwnerDeleteProcessBindingV1) {
        if (registeredInitialDeletion !== original || initialDeletionControls || !selectedHolder.fenceReady()) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        requireWork(); initialDeletionControls = true
    }
    internal fun requireInitialDeletionControls(original: me.manga.kira.backend.complaint.infrastructure.TestOwnerDeleteProcessBindingV1) {
        if (registeredInitialDeletion !== original || !initialDeletionControls || !selectedHolder.fenceReady()) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        requireWork()
    }
    internal fun initialDeletionGate(original: me.manga.kira.backend.complaint.infrastructure.TestOwnerDeleteProcessBindingV1): PersistenceComplaintMaintenanceGateV1 {
        requireInitialDeletionControls(original)
        return PersistenceComplaintMaintenanceGateV1.read(connection ?: refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED))
    }

    /** Fixed terminal-catalog read choice; keeps path private and validates the original fence/holder. */
    internal fun terminalCatalogMaintenanceRead(fence: PersistenceComplaintMaintenanceFenceV1, selected: Connection): Boolean {
        selectedHolder.requireMaintenanceFence(fence, selected)
        return path.catalogTestRunTerminal || path === PersistencePhasePath.COMPLAINT_TEST_RUN_TERMINAL_CATALOG_PREFLIGHT
    }

    /** Separate fixed eraser read choice, not a caller-selectable path or open-gate bypass. */
    internal fun testRunErasureMaintenanceRead(fence: PersistenceComplaintMaintenanceFenceV1, selected: Connection): Boolean {
        selectedHolder.requireMaintenanceFence(fence, selected)
        return path.testRunErasure
    }

    internal fun requireComplaintMaintenanceGate(
        fence: PersistenceComplaintMaintenanceFenceV1,
        selected: Connection,
        gate: PersistenceComplaintMaintenanceGateV1,
    ) {
        selectedHolder.requireMaintenanceFence(fence, selected)
        when {
            // M/shared is still held. Closed/pending/current eligibility is checked only AFTER a new
            // exact claim; a losing receipt claim must remain replayable without a freshness gate.
            // AUTH/receipt SQL separately compares current desired identity in its same row snapshot.
            registeredInitialCheckpointCreate != null -> checkNotNull(registeredInitialCheckpointCreate).requirePhaseOwner(ownership)
            registeredInitialCheckpointEdit != null -> checkNotNull(registeredInitialCheckpointEdit).requirePhaseOwner(ownership)
            registeredAdminContent != null -> checkNotNull(registeredAdminContent).requirePhaseOwner(ownership)
            registeredAdminStatus != null -> checkNotNull(registeredAdminStatus).requireStatusPhaseOwner(ownership)
            registeredAdminStepUp != null -> checkNotNull(registeredAdminStepUp).let { it.owner.requireStepUpGate(it, this, gate) }
            registeredInitialDeletion != null -> checkNotNull(registeredInitialDeletion).requirePhaseOwner(ownership, path)
            testOrdinaryDrain != null -> testOrdinaryDrain.requireMaintenanceGate(ownership, path, gate)
            testRunOwnerDelete != null -> testRunOwnerDelete.requireMaintenanceGate(ownership, path, gate)
            testRunOwnerDeleteAll != null -> testRunOwnerDeleteAll.requireMaintenanceGate(ownership, path, gate)
            testRunAdminDelete != null -> testRunAdminDelete.requireMaintenanceGate(ownership, path, gate)
            testRunSealer != null -> testRunSealer.requireMaintenanceGate(ownership, path, gate)
            testOrdinarySealer != null -> testOrdinarySealer.requireMaintenanceGate(ownership, path, gate)
            testActiveSealer != null -> testActiveSealer.requireMaintenanceGate(ownership, path, gate)
            testInitialCheckpoint != null -> testInitialCheckpoint.requireMaintenanceGate(ownership, path, gate)
            testRecurrent != null -> testRecurrent.requireMaintenanceGate(ownership, path, gate)
            testRecurrentApply != null -> testRecurrentApply.requireMaintenanceGate(ownership, path, gate)
            testActiveQueue != null -> testActiveQueue.requireMaintenanceGate(ownership, path, gate)
            testActiveSealRecovery != null -> testActiveSealRecovery.requireMaintenanceGate(ownership, path, gate)
            testInstallationManifest != null -> testInstallationManifest.requireMaintenanceGate(ownership, path, gate)
            testInstallationManifestPublication != null -> testInstallationManifestPublication.requireMaintenanceGate(ownership, path, gate)
            testRunPurge != null -> testRunPurge.requireMaintenanceGate(ownership, path, gate)
            testTerminalEpochSeal != null -> testTerminalEpochSeal.requireMaintenanceGate(ownership, path, gate)
            testRunErasure != null -> testRunErasure.requireMaintenanceGate(ownership, path, gate)
            testTerminalQuiescence != null -> testTerminalQuiescence.requireMaintenanceGate(ownership, path, gate)
            testRunTerminalCatalog != null -> testRunTerminalCatalog.requireMaintenanceGate(ownership, path, gate)
            testRegistration != null -> testRegistration.requireMaintenanceGate(ownership, path, gate)
            initialAdmission != null -> initialAdmission.requireMaintenanceGate(ownership, path, gate)
            activeFirstCut != null -> activeFirstCut.requireMaintenanceGate(ownership, path, gate)
            activeFirstCutSuccessor != null -> activeFirstCutSuccessor.requireMaintenanceGate(ownership, path, gate)
            testRecoveryRegistration != null -> testRecoveryRegistration.requireMaintenanceGate(ownership, path, gate)
            testActiveRegistration != null -> testActiveRegistration.requireMaintenanceGate(ownership, path, gate)
            testRunActivation != null -> testRunActivation.requireMaintenanceGate(ownership, path, gate)
            else -> gate.requireUnownedOpen()
        }
    }

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

    /** Acceptance only; original cleanup admission/execution always keeps cleanupBudget(). */
    internal fun maintenanceCleanupBudget(): PersistenceTimeBudget? = selectedHolder.maintenanceCleanupBudget()

    /** The original admitted guard retains its own acceptance cap through native/core finalizers. */
    internal fun maintenanceDispatchReturned(kind: PersistenceJdbcGuardCallKind, admitted: PersistenceTimeBudget?) {
        if (kind !== PersistenceJdbcGuardCallKind.CANCELLATION) selectedHolder.maintenanceDispatchReturned(kind, admitted)
    }

    internal fun connectionKind(method: Method, arguments: Array<out Any?>?): PersistenceJdbcGuardCallKind = jdbcCapabilities.classify(method, arguments)

    internal fun requireDeletionFence(fence: PersistenceDeletionFence, selected: Connection): Boolean {
        selectedHolder.requireFence(fence, selected)
        return path === PersistencePhasePath.COMPLAINT_TEST_NAMESPACE_RECOVERY_REGISTRATION ||
            path === PersistencePhasePath.COMPLAINT_TEST_NAMESPACE_ACTIVE_REGISTRATION ||
            path === PersistencePhasePath.COMPLAINT_TEST_INSTALLATION_MANIFEST_PUBLICATION || path === PersistencePhasePath.COMPLAINT_TEST_RUN_PURGE_PUBLICATION || path === PersistencePhasePath.COMPLAINT_TEST_TERMINAL_QUIESCENCE || path === PersistencePhasePath.COMPLAINT_TEST_TERMINAL_EPOCH_SEAL || path === PersistencePhasePath.COMPLAINT_TEST_INSTALLATION_MANIFEST_PREPARE || path === PersistencePhasePath.COMPLAINT_TEST_ORDINARY_SEAL || path === PersistencePhasePath.COMPLAINT_TEST_ORDINARY_DRAIN || testOrdinaryDrain != null
    }

    internal fun requireComplaintMaintenanceFence(fence: PersistenceComplaintMaintenanceFenceV1, selected: Connection) =
        selectedHolder.requireMaintenanceFence(fence, selected)

    internal fun requireDeletionControlSnapshot(snapshot: PersistenceDeletionControlSnapshot, selected: Connection) =
        selectedHolder.requireControlSnapshot(snapshot, selected)

    /** Reclips the actual JDBC read cap before every upper call, without recursion or a new time budget. */
    internal fun beforeJdbcCall(kind: PersistenceJdbcGuardCallKind) {
        if (kind === PersistenceJdbcGuardCallKind.CANCELLATION || changingReadCap || restoringReadCap) return
        readCapKind = kind
        changingReadCap = true
        try {
            val budget = callBudget(kind)
            val selected = connection ?: return
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
            } catch (problem: Throwable) {
                catalogSignerRotationAttempt?.observeFailure(problem)
                signerRotationRecovery?.observeFailure(problem)
                signerRotationAuthor?.observeFailure(problem)
                signerRotationDelivery?.observeFailure(problem)
                signerRotationActivation?.observeFailure(problem)
                testRunActivation?.observeFailure(problem)
                testRegistration?.observeFailure(problem)
                initialAdmission?.observeFailure(problem)
                activeFirstCut?.observeFailure(problem)
                activeFirstCutSuccessor?.observeFailure(problem)
                testRecoveryRegistration?.observeFailure(problem)
                testActiveRegistration?.observeFailure(problem)
                testRunSealer?.observeFailure(problem)
                testOrdinarySealer?.observeFailure(problem)
                testActiveSealer?.observeFailure(problem)
                testInitialCheckpoint?.observeFailure(problem)
                testRecurrent?.observeFailure(problem)
                testRecurrentApply?.observeFailure(problem)
                testActiveQueue?.observeFailure(problem)
                testActiveSealRecovery?.observeFailure(problem)
                testInstallationManifest?.observeFailure(problem)
                testInstallationManifestPublication?.observeFailure(problem)
                testRunPurge?.observeFailure(problem)
                testTerminalEpochSeal?.observeFailure(problem)
                testTerminalQuiescence?.observeFailure(problem)
                testRunErasure?.observeFailure(problem)
                testRunTerminalCatalog?.observeFailure(problem)
                testRunOwnerDelete?.observeFailure(problem)
                testRunOwnerDeleteAll?.observeFailure(problem)
                testRunAdminDelete?.observeFailure(problem)
                testOrdinaryDrain?.observeFailure(problem)
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
        val selected = work ?: rotationWork ?: cutoffWork ?: catalogRefreshWork ?: desiredWork ?: firstDesiredWork ?: catalogAuthorWork
            ?: catalogFinalizerWork ?: catalogPublisherWork ?: catalogSignerRotationWork ?: signerRotationRecoveryWork ?: signerRotationAuthorWork
            ?: signerRotationDeliveryWork ?: signerRotationActivationWork ?: testRunActivationWork ?: testRegistrationWork ?: testInitialAdmissionWork ?: testActiveFirstCutWork ?: testActiveFirstCutSuccessorWork ?: testRecoveryRegistrationWork ?: testActiveRegistrationWork ?: testRunSealingWork ?: testOrdinarySealWork ?: testActiveOrdinarySealWork ?: testActiveOrdinarySealRecoveryWork ?: testActiveInitialCheckpointWork ?: testActiveRecurrentWork ?: testActiveRecurrentApplyWork ?: testActiveOwnerDeleteQueueWork ?: testRunOwnerDeleteWork ?: testRunAdminDeleteWork ?: testRunOwnerDeleteAllWork ?: testOrdinaryDrainWork ?: testInstallationManifestWork ?: testInstallationManifestPublicationWork ?: testRunPurgeWork ?: testTerminalEpochSealWork ?: testRunErasureWork ?: testTerminalQuiescenceWork ?: testRunTerminalCatalogWork
            ?: return false
        val expired = persistenceFactoryRemainingMillis(selected) == 0L
        if (expired) failure.compareAndSet(null, PersistencePhaseFailureCode.TIME_BUDGET_EXHAUSTED)
        return expired
    }

    private fun requireWork() {
        testActiveQueue?.throwIfSignalled()
        testRecurrentApply?.throwIfSignalled()
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
        if (usesCatalogLifecycleCleanup()) {
            return budget.systemCleanupSnapshot(WORK_MILLIS)
        }
        val ordinaryBudget = rotationAttempt == null && cutoffAttempt == null && catalogRefresh == null
        return if (ordinaryBudget && desiredAttempt == null && firstDesiredAttempt == null) {
            budget
        } else {
            budget.systemCappedSnapshot(WORK_MILLIS)
        }
    }

    private fun usesCatalogLifecycleCleanup(): Boolean = catalogAuthorAttempt != null || catalogFinalizerAttempt != null || catalogPublisherAttempt != null ||
        catalogSignerRotationAttempt != null || signerRotationRecovery != null || signerRotationAuthor != null || signerRotationDelivery != null ||
        signerRotationActivation != null || testRunActivation != null || testRegistration != null || initialAdmission != null || activeFirstCut != null || activeFirstCutSuccessor != null || testRecoveryRegistration != null || testActiveRegistration != null || testRunSealer != null || testOrdinarySealer != null || testActiveSealer != null || testInstallationManifest != null || testInstallationManifestPublication != null || testRunPurge != null || testRunOwnerDelete != null || testRunOwnerDeleteAll != null || testRunAdminDelete != null || testOrdinaryDrain != null || testTerminalEpochSeal != null || testInitialCheckpoint != null || testRecurrent != null || testRecurrentApply != null || testActiveQueue != null || testActiveSealRecovery != null || testRunErasure != null || testTerminalQuiescence != null || testRunTerminalCatalog != null

    private fun emergencyBudget(): PersistenceTimeBudget {
        emergency?.let { return it }
        requireCaller()
        val catalogBudget =
            testInstallationManifest?.budget ?: testInstallationManifestPublication?.budget ?: testRunPurge?.budget ?: testRunErasure?.budget ?: testTerminalQuiescence?.budget ?: testRunTerminalCatalog?.budget ?: testTerminalEpochSeal?.budget ?: testRunAdminDelete?.budget ?: testRunOwnerDeleteAll?.budget ?: testOrdinaryDrain?.budget ?: testRunOwnerDelete?.budget ?: testRunSealer?.budget ?: testOrdinarySealer?.budget ?: testActiveSealer?.budget ?: testActiveSealRecovery?.budget ?: testInitialCheckpoint?.budget ?: testRecurrent?.budget ?: testRecurrentApply?.budget ?: testActiveQueue?.budget ?: testRegistration?.budget ?: initialAdmission?.budget ?: activeFirstCut?.budget ?: activeFirstCutSuccessor?.budget ?: testRecoveryRegistration?.budget ?: testActiveRegistration?.budget ?: testRunActivation?.budget ?: signerRotationActivation?.budget ?: signerRotationDelivery?.budget ?: signerRotationAuthorAllowance ?: signerRotationRecovery?.budget
                ?: catalogSignerRotationAttempt?.budget
                ?: catalogPublisherAttempt?.budget
                ?: catalogFinalizerAttempt?.phaseBudget ?: catalogAuthorAttempt?.budget
        catalogBudget?.let {
            return it.systemCleanupSnapshot(EMERGENCY_MILLIS).also { selected -> emergency = selected }
        }
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
            } catch (problem: Throwable) {
                catalogSignerRotationAttempt?.observeFailure(problem)
                signerRotationRecovery?.observeFailure(problem)
                signerRotationAuthor?.observeFailure(problem)
                signerRotationDelivery?.observeFailure(problem)
                signerRotationActivation?.observeFailure(problem)
                testRunActivation?.observeFailure(problem)
                testRegistration?.observeFailure(problem)
                initialAdmission?.observeFailure(problem)
                activeFirstCut?.observeFailure(problem)
                activeFirstCutSuccessor?.observeFailure(problem)
                testRecoveryRegistration?.observeFailure(problem)
                testActiveRegistration?.observeFailure(problem)
                testRunSealer?.observeFailure(problem)
                testOrdinarySealer?.observeFailure(problem)
                testActiveSealer?.observeFailure(problem)
                testInitialCheckpoint?.observeFailure(problem)
                testRecurrent?.observeFailure(problem)
                testRecurrentApply?.observeFailure(problem)
                testActiveQueue?.observeFailure(problem)
                testActiveSealRecovery?.observeFailure(problem)
                testInstallationManifest?.observeFailure(problem)
                testInstallationManifestPublication?.observeFailure(problem)
                testRunPurge?.observeFailure(problem)
                testTerminalEpochSeal?.observeFailure(problem)
                testTerminalQuiescence?.observeFailure(problem)
                testRunErasure?.observeFailure(problem)
                testRunTerminalCatalog?.observeFailure(problem)
                testRunOwnerDelete?.observeFailure(problem)
                testRunOwnerDeleteAll?.observeFailure(problem)
                testRunAdminDelete?.observeFailure(problem)
                testOrdinaryDrain?.observeFailure(problem)
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
        private val maintenanceFence = if (path.complaintMaintenanceWriter || testRunOwnerDelete != null || testRunOwnerDeleteAll != null || testRunAdminDelete != null || testOrdinaryDrain != null) PersistenceComplaintMaintenanceFenceV1(this@PersistencePhaseContext) else null
        private var maintenanceLimitsRestored = false
        private val deletionFence = when (path) {
            PersistencePhasePath.COMPLAINT_DELETION_FENCE_PREFIX,
            PersistencePhasePath.COMPLAINT_DELETION_CONTROL_SNAPSHOT,
            PersistencePhasePath.COMPLAINT_OWNER_DELETE_AUTHORIZE,
            PersistencePhasePath.COMPLAINT_ADMIN_DELETE_AUTHORIZE,
            PersistencePhasePath.COMPLAINT_OWNER_DELETE_RELOAD,
            PersistencePhasePath.COMPLAINT_ADMIN_DELETE_RELOAD,
            PersistencePhasePath.COMPLAINT_OWNER_DELETE_APPLY,
            PersistencePhasePath.COMPLAINT_ADMIN_DELETE_APPLY,
            PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_AUTHORIZE,
            PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_RELOAD,
            PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_APPLY,
            PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PREPARE,
            PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_SIGNATURE,
            PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_COMPLETE,
            PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PROJECT,
            PersistencePhasePath.COMPLAINT_CATALOG_PROJECTED_HEAD,
            PersistencePhasePath.COMPLAINT_TEST_NAMESPACE_REGISTRATION,
            PersistencePhasePath.COMPLAINT_TEST_INITIAL_ADMISSION_CAPTURE,
            PersistencePhasePath.COMPLAINT_TEST_INITIAL_ADMISSION_RELEASE,
            PersistencePhasePath.COMPLAINT_TEST_ACTIVE_FIRST_CUT_READ,
            PersistencePhasePath.COMPLAINT_TEST_ACTIVE_FIRST_CUT_LEASE,
            PersistencePhasePath.COMPLAINT_TEST_ACTIVE_FIRST_CUT_REQUEST,
            PersistencePhasePath.COMPLAINT_TEST_ACTIVE_FIRST_CUT_SUCCESSOR_READ,
            PersistencePhasePath.COMPLAINT_TEST_ACTIVE_FIRST_CUT_SUCCESSOR_LEASE,
            PersistencePhasePath.COMPLAINT_TEST_ACTIVE_FIRST_CUT_SUCCESSOR_RELEASE,
            PersistencePhasePath.COMPLAINT_TEST_NAMESPACE_RECOVERY_REGISTRATION,
            PersistencePhasePath.COMPLAINT_TEST_NAMESPACE_ACTIVE_REGISTRATION,
            PersistencePhasePath.COMPLAINT_TEST_ORDINARY_SEAL,
            PersistencePhasePath.COMPLAINT_TEST_INSTALLATION_MANIFEST_PREPARE,
            PersistencePhasePath.COMPLAINT_TEST_INSTALLATION_MANIFEST_PUBLICATION,
            PersistencePhasePath.COMPLAINT_TEST_RUN_PURGE_PUBLICATION,
            PersistencePhasePath.COMPLAINT_TEST_TERMINAL_EPOCH_SEAL,
            PersistencePhasePath.COMPLAINT_TEST_TERMINAL_QUIESCENCE,
            PersistencePhasePath.COMPLAINT_TEST_RUN_ERASURE_READ,
            PersistencePhasePath.COMPLAINT_TEST_RUN_ERASURE_BATCH,
            PersistencePhasePath.COMPLAINT_TEST_RUN_ERASURE_FINAL,
            PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_TERMINAL_CAPTURE,
            PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_TERMINAL_PREPARE,
            PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_TERMINAL_RELOAD,
            PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_TERMINAL_SIGNATURE,
            PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_TERMINAL_COMPLETE,
            PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_TERMINAL_PROJECT,
            PersistencePhasePath.COMPLAINT_TEST_RUN_TERMINAL_CATALOG_PREFLIGHT,
            PersistencePhasePath.COMPLAINT_TEST_ORDINARY_DRAIN,
            PersistencePhasePath.COMPLAINT_TEST_RUN_SEALED_AUDIT, // The run-only seal must commit/release BEFORE E or any control/counter lock.
            PersistencePhasePath.COMPLAINT_DESIRED_SIGNED_GENESIS_FIRST,
            PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PUBLISH_RECHECK,
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_READ,
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_PREPARE,
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_SIGNATURE,
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_FINAL_READ,
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_COMPLETE,
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_PROJECT,
            PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_PREPARE,
            PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_PREPARED_RELOAD,
            PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_SIGNATURE,
            PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_SIGNED_RELOAD,
            PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_DELIVERY_RELOAD,
            PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_COMPLETE,
            PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_PENDING_RELOAD,
            PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_PROJECT_RELOAD,
            PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_PROJECT,
            PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_PROJECTED_RELOAD,
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_READ,
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_PREPARE,
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_SIGNATURE,
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_COMPLETE,
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_PROJECT,
            -> PersistenceDeletionFence(this@PersistencePhaseContext, ownership.nanoClock)

            else -> null
        }
        private val deletionControlSnapshot = if (path === PersistencePhasePath.COMPLAINT_DELETION_CONTROL_SNAPSHOT) {
            PersistenceDeletionControlSnapshot(this@PersistencePhaseContext, checkNotNull(deletionScope))
        } else {
            null
        }
        private var fenceLimitsRestored = false

        fun acquireMaintenanceFence(selected: Connection) {
            maintenanceFence?.let {
                it.acquire(selected, requireNotNull(work))
                installLimits() // Restore only the original remaining allowance, before E or any path-specific lock.
                maintenanceLimitsRestored = true
            }
        }

        fun requireMaintenanceFence(fence: PersistenceComplaintMaintenanceFenceV1, selected: Connection) {
            requireCaller()
            if (stage !== Stage.SETTING_UP || maintenanceFence !== fence || connection !== selected) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            requireCurrent()
            requireWork()
        }

        fun acquireFence(selected: Connection) {
            if (maintenanceFence != null && (!maintenanceFence.accepted() || !maintenanceLimitsRestored)) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
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

        // C already committed and physically released E at CAPTURE. These closed ACTIVE paths
        // retain the real M/shared prefix and current gate, never reacquire terminal E authority.
        fun activeOrdinarySealReady(): Boolean = path.testActiveOrdinarySeal && testActiveSealer != null &&
            maintenanceFence?.accepted() == true && maintenanceLimitsRestored &&
            deletionFence == null && deletionControlSnapshot == null

        fun activeInitialCheckpointReady(): Boolean = path.testActiveInitialCheckpoint && testInitialCheckpoint != null &&
            maintenanceFence?.accepted() == true && maintenanceLimitsRestored &&
            deletionFence == null && deletionControlSnapshot == null

        fun activeRecurrentReady(): Boolean = path.testActiveRecurrent && testRecurrent != null &&
            maintenanceFence?.accepted() == true && maintenanceLimitsRestored &&
            deletionFence == null && deletionControlSnapshot == null

        fun activeOwnerDeleteQueueReady(): Boolean = path.testActiveOwnerDeleteQueue && testActiveQueue != null &&
            maintenanceFence?.accepted() == true && maintenanceLimitsRestored &&
            deletionFence == null && deletionControlSnapshot == null

        fun activeOrdinarySealRecoveryReady(): Boolean = path.testActiveOrdinarySealRecovery && testActiveSealRecovery != null &&
            maintenanceFence?.accepted() == true && maintenanceLimitsRestored &&
            deletionFence == null && deletionControlSnapshot == null

        fun receiptlessManifestReady(): Boolean = path === PersistencePhasePath.COMPLAINT_TEST_INSTALLATION_MANIFEST_VERIFY &&
            maintenanceFence == null && deletionFence == null && deletionControlSnapshot == null

        fun receiptlessPurgeReady(): Boolean = path === PersistencePhasePath.COMPLAINT_TEST_RUN_PURGE_VERIFY &&
            maintenanceFence == null && deletionFence == null && deletionControlSnapshot == null

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
            if (maintenanceFence?.active() == true || deletionFence?.active() == true || deletionControlSnapshot?.active() == true) requireCurrent()
            val normal = deletionFence?.callBudget(requireNotNull(work)) ?: requireNotNull(work)
            return maintenanceFence?.callBudget(normal) ?: normal
        }

        fun maintenanceCleanupBudget(): PersistenceTimeBudget? {
            if (!healthyMaintenancePrefix()) return null
            requireCurrent()
            return try {
                checkNotNull(maintenanceFence).callBudget(checkNotNull(work))
            } catch (problem: PersistencePhaseException) {
                if (problem.code !== PersistencePhaseFailureCode.TIME_BUDGET_EXHAUSTED) throw problem
                // This SAME original close first observed expiry. Failure is already sticky, but its
                // separate original cleanup allowance still owns every lower admission/first close.
                null
            }
        }

        fun maintenanceDispatchReturned(kind: PersistenceJdbcGuardCallKind, admitted: PersistenceTimeBudget?) {
            if (!healthyMaintenancePrefix()) return
            try {
                checkNotNull(admitted).remainingMillis(PersistenceComplaintMaintenanceFenceV1.DISPATCH_MILLIS)
            } catch (_: PersistenceBoundaryException) {
                // Nested lower cleanup must return its genuine successful-close fact to the upper
                // guard. Throwing here would counterfeit an upper CLEANUP_FAILURE. The mandatory
                // owner/work check before ACCEPTED sees this sticky TIME refusal instead.
                if (kind !== PersistenceJdbcGuardCallKind.CLEANUP) refuse(PersistencePhaseFailureCode.TIME_BUDGET_EXHAUSTED)
                recordFailure(PersistencePhaseException(PersistencePhaseFailureCode.TIME_BUDGET_EXHAUSTED))
            }
        }

        private fun healthyMaintenancePrefix(): Boolean = stage === Stage.SETTING_UP && failure.get() == null && maintenanceFence?.active() == true

        fun readCeiling(): Long {
            val normal = deletionFence?.readCeiling(NORMAL_READ_MILLIS) ?: NORMAL_READ_MILLIS
            return maintenanceFence?.readCeiling(normal) ?: normal
        }

        fun afterBusinessCall() {
            requireWork()
            maintenanceFence?.requireRemaining()
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

        override fun requireOperation(jdbc: JdbcTemplate, path: PersistencePhasePath, input: CatalogGenesisMutationInput?) {
            if (input?.finalization?.finalizer !== catalogFinalizerAttempt) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            catalogFinalizerAttempt?.requirePersistence(ownership, jdbc)
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

    /** Fixed first-overlap operation, with original phase identity and actual cleanup proof retained beyond ThreadLocal removal. */
    private inner class CatalogSignerRotationBoundary : PersistenceCatalogSignerRotationV1 {
        private var selectedInput: CatalogSignerRotationSqlInputV1? = null
        private var retained: CatalogSignerRotationOperationV1? = null

        override fun requireOperation(input: CatalogSignerRotationSqlInputV1, jdbc: JdbcTemplate) {
            if (input.attempt !== catalogSignerRotationAttempt || input.path !== path || !signerRotationPath()) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            requireStepUpResource(jdbc, path)
            if (selectedInput != null || !selectedHolder.fenceReady()) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            input.requirePersistence(ownership, jdbc)
            selectedInput = input
            installLimits()
            requireWork()
        }

        override fun retain(operation: CatalogSignerRotationOperationV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            val matchingInput = operation.input === selectedInput && operation.input.attempt === catalogSignerRotationAttempt
            if (retained != null || !matchingInput || !operation.belongsTo(this@PersistencePhaseContext, path)) {
                refuse(PersistencePhaseFailureCode.WORK_FAILED)
            }
            operation.input.requirePersistence(ownership, jdbc)
            retained = operation
        }

        override fun requireRetained(operation: CatalogSignerRotationOperationV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            val matchingInput = operation.input === selectedInput && operation.input.attempt === catalogSignerRotationAttempt
            if (retained !== operation || !matchingInput || !selectedHolder.fenceReady()) {
                refuse(PersistencePhaseFailureCode.WORK_FAILED)
            }
            operation.input.requirePersistence(ownership, jdbc)
        }

        override fun requireCommitted(operation: CatalogSignerRotationOperationV1) {
            val completedOriginal = retained === operation && operation.input === selectedInput && completed()
            if (!completedOriginal || !cleanupProven(operation.input.attempt)) {
                failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            }
            requireSuccessfulResult()
        }

        override fun cleanupProven(attempt: CatalogSignerRotationFreezeAttemptV1): Boolean =
            caller.isCurrent() && catalogSignerRotationAttempt === attempt && signerRotationPath() &&
                stage === Stage.CLOSED && finalizerEnded && springSettled && refunded.get() &&
                acquisition?.quiescent() != false && !completionActive && (!beginDispatched || beginEnded) &&
                (rootStatus?.hasReturnedStatus() != true || completionEnded) && failure.get() !== PersistencePhaseFailureCode.CLEANUP_UNRESOLVED

        override fun completed(): Boolean = retained?.let {
            it.input === selectedInput && it.input.attempt === catalogSignerRotationAttempt && it.completedFor(this@PersistencePhaseContext)
        } == true

        private fun signerRotationPath(): Boolean = when (path) {
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_READ,
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_PREPARE,
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_SIGNATURE,
            -> true

            else -> false
        }
    }

    /** Separate fixed delivery boundary. A freeze/recovery input can never select COMPLETE or PROJECT. */
    @Suppress("ComplexCondition") // Keep this closed boundary's exact original input, owner, phase and cleanup checks explicit.
    private inner class CatalogSignerRotationFinalizationBoundary : PersistenceCatalogSignerRotationFinalizationV1 {
        private var selectedInput: CatalogSignerRotationFinalizationInputV1? = null
        private var retained: CatalogSignerRotationFinalizationOperationV1? = null

        override fun requireOperation(input: CatalogSignerRotationFinalizationInputV1, jdbc: JdbcTemplate) {
            if (input.original !== signerRotationDelivery || input.path !== path || !finalizationPath()) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            requireStepUpResource(jdbc, path)
            if (selectedInput != null || !selectedHolder.fenceReady()) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            input.requirePersistence(ownership, jdbc)
            selectedInput = input
            installLimits()
            requireWork()
        }

        override fun retain(operation: CatalogSignerRotationFinalizationOperationV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (retained != null || operation.input !== selectedInput || operation.input.original !== signerRotationDelivery ||
                !operation.belongsTo(this@PersistencePhaseContext, path)
            ) {
                refuse(PersistencePhaseFailureCode.WORK_FAILED)
            }
            operation.input.requirePersistence(ownership, jdbc)
            retained = operation
        }

        override fun requireRetained(operation: CatalogSignerRotationFinalizationOperationV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (retained !== operation || operation.input !== selectedInput || operation.input.original !== signerRotationDelivery ||
                !selectedHolder.fenceReady()
            ) {
                refuse(PersistencePhaseFailureCode.WORK_FAILED)
            }
            operation.input.requirePersistence(ownership, jdbc)
        }

        override fun requireCommitted(operation: CatalogSignerRotationFinalizationOperationV1) {
            if (retained !== operation || operation.input !== selectedInput || !completed() ||
                !signerRotationDeliveryCleanupProven(operation.input.original)
            ) {
                failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            }
            requireSuccessfulResult()
        }

        override fun completed(): Boolean = retained?.let {
            it.input === selectedInput && it.input.original === signerRotationDelivery && it.completedFor(this@PersistencePhaseContext)
        } == true

        private fun finalizationPath(): Boolean = when (path) {
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_FINAL_READ,
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_COMPLETE,
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_PROJECT,
            -> true

            else -> false
        }
    }

    private inner class CatalogSignerRotationActivationBoundary : PersistenceCatalogSignerRotationActivationV1 {
        private var selectedInput: CatalogSignerRotationActivationInputV1? = null
        private var retained: CatalogSignerRotationActivationOperationV1? = null

        override fun requireOperation(input: CatalogSignerRotationActivationInputV1, jdbc: JdbcTemplate) {
            if (input.original !== signerRotationActivation || input.path !== path || !finalizationPath()) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            requireStepUpResource(jdbc, path)
            if (selectedInput != null || !selectedHolder.fenceReady()) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            input.requirePersistence(ownership, jdbc)
            selectedInput = input
            installLimits()
            requireWork()
        }

        // The operation, input, owner and phase identities form one indivisible admission guard.
        @Suppress("ComplexCondition")
        override fun retain(operation: CatalogSignerRotationActivationOperationV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (retained != null || operation.input !== selectedInput || operation.input.original !== signerRotationActivation ||
                !operation.belongsTo(this@PersistencePhaseContext, path)
            ) {
                refuse(PersistencePhaseFailureCode.WORK_FAILED)
            }
            operation.input.requirePersistence(ownership, jdbc)
            retained = operation
        }

        // Keep exact retained identities and the original fence check visible at the same boundary.
        @Suppress("ComplexCondition")
        override fun requireRetained(operation: CatalogSignerRotationActivationOperationV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (retained !== operation || operation.input !== selectedInput || operation.input.original !== signerRotationActivation ||
                !selectedHolder.fenceReady()
            ) {
                refuse(PersistencePhaseFailureCode.WORK_FAILED)
            }
            operation.input.requirePersistence(ownership, jdbc)
        }

        // A result requires this exact retained operation, completion and positively proven original cleanup.
        @Suppress("ComplexCondition")
        override fun requireCommitted(operation: CatalogSignerRotationActivationOperationV1) {
            if (retained !== operation || operation.input !== selectedInput || !completed() ||
                !signerRotationActivationCleanupProven(operation.input.original)
            ) {
                failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            }
            requireSuccessfulResult()
        }

        override fun completed(): Boolean = retained?.let {
            it.input === selectedInput && it.input.original === signerRotationActivation && it.completedFor(this@PersistencePhaseContext)
        } == true

        private fun finalizationPath(): Boolean = when (path) {
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_READ,
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_PREPARE,
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_SIGNATURE,
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_COMPLETE,
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_PROJECT,
            -> true

            else -> false
        }
    }

    /** Two fixed normal-root operations; an original failed release is never a successful barrier/audit receipt. */
    private inner class TestRunSealingBoundary : PersistenceTestRunSealingV1 {
        private var selected = false
        private var retained: TestRunSealingOperationV1? = null

        override fun requireOperation(original: TestRunSealingV1, jdbc: JdbcTemplate) {
            if (!path.testRunSealing || original !== testRunSealer || original.path !== path) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            requireStepUpResource(jdbc, path)
            if (selected || (path === PersistencePhasePath.COMPLAINT_TEST_RUN_SEALED_AUDIT && !selectedHolder.fenceReady())) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            original.requirePersistence(ownership, jdbc)
            selected = true
            installLimits()
            requireWork()
        }

        override fun retain(operation: TestRunSealingOperationV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (!selected || retained != null || operation.original !== testRunSealer || operation.path !== path || !operation.belongsTo(this@PersistencePhaseContext)) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            operation.original.requirePersistence(ownership, jdbc)
            retained = operation
        }

        override fun requireRetained(operation: TestRunSealingOperationV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (retained !== operation || operation.original !== testRunSealer || operation.path !== path ||
                (path === PersistencePhasePath.COMPLAINT_TEST_RUN_SEALED_AUDIT && !selectedHolder.fenceReady())) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            operation.original.requirePersistence(ownership, jdbc)
        }

        override fun requireCommitted(operation: TestRunSealingOperationV1) {
            if (retained !== operation || !completed() || !testRunSealingCleanupProven(operation.original)) {
                failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            }
            requireSuccessfulResult()
        }

        override fun completed(): Boolean = retained?.let { it.original === testRunSealer && it.path === path && it.completedFor(this@PersistencePhaseContext) } == true
    }

    private inner class TestOrdinarySealBoundary : PersistenceTestOrdinarySealV1 {
        private var selected = false
        private var retained: TestOrdinarySealOperationV1? = null

        override fun requireOperation(original: TestRunOrdinarySealV1, jdbc: JdbcTemplate) {
            if (path !== PersistencePhasePath.COMPLAINT_TEST_ORDINARY_SEAL || original !== testOrdinarySealer || original.path !== path) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            requireStepUpResource(jdbc, path)
            if (selected || !selectedHolder.fenceReady()) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            original.requirePersistence(ownership, jdbc)
            selected = true
            installLimits()
            requireWork()
        }

        override fun retain(operation: TestOrdinarySealOperationV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (!selected || retained != null || operation.original !== testOrdinarySealer || operation.path !== path || !operation.belongsTo(this@PersistencePhaseContext)) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            operation.original.requirePersistence(ownership, jdbc)
            retained = operation
        }

        override fun requireRetained(operation: TestOrdinarySealOperationV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (retained !== operation || operation.original !== testOrdinarySealer || operation.path !== path ||
                !selectedHolder.fenceReady()) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            operation.original.requirePersistence(ownership, jdbc)
        }

        override fun requireCommitted(operation: TestOrdinarySealOperationV1) {
            if (retained !== operation || !completed() || !testOrdinarySealCleanupProven(operation.original)) {
                failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            }
            requireSuccessfulResult()
        }

        override fun completed(): Boolean = retained?.let { it.original === testOrdinarySealer && it.path === path && it.completedFor(this@PersistencePhaseContext) } == true
    }

    private inner class TestActiveOrdinarySealBoundary : PersistenceTestActiveOrdinarySealV1 {
        private var selected = false
        private var retained: TestActiveOrdinarySealOperationV1? = null

        override fun requireOperation(original: TestActiveOrdinarySealV1, jdbc: JdbcTemplate) {
            if (!path.testActiveOrdinarySeal || original !== testActiveSealer || original.path !== path) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            requireStepUpResource(jdbc, path)
            if (selected || !selectedHolder.activeOrdinarySealReady()) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            original.requirePersistence(ownership, jdbc)
            selected = true
            installLimits()
            requireWork()
        }

        override fun retain(operation: TestActiveOrdinarySealOperationV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (!selected || retained != null || operation.original !== testActiveSealer || operation.path !== path || !operation.belongsTo(this@PersistencePhaseContext)) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            operation.original.requirePersistence(ownership, jdbc)
            retained = operation
        }

        override fun requireRetained(operation: TestActiveOrdinarySealOperationV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (retained !== operation || operation.original !== testActiveSealer || operation.path !== path ||
                !selectedHolder.activeOrdinarySealReady()) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            operation.original.requirePersistence(ownership, jdbc)
        }

        override fun requireCommitted(operation: TestActiveOrdinarySealOperationV1) {
            if (retained !== operation || !completed() || !testActiveOrdinarySealCleanupProven(operation.original)) {
                failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            }
            requireSuccessfulResult()
        }

        override fun completed(): Boolean = retained?.let { it.original === testActiveSealer && it.path === path && it.completedFor(this@PersistencePhaseContext) } == true
    }

    private inner class TestActiveInitialCheckpointBoundary : PersistenceTestActiveInitialCheckpointV1 {
        private var selected = false
        private var retained: TestActiveInitialCheckpointOperationV1? = null

        override fun requireOperation(original: TestActiveInitialCheckpointV1, jdbc: JdbcTemplate) {
            if (!path.testActiveInitialCheckpoint || original !== testInitialCheckpoint || original.path !== path) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            requireStepUpResource(jdbc, path)
            if (selected || !selectedHolder.activeInitialCheckpointReady()) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            original.requirePersistence(ownership, jdbc)
            selected = true
            installLimits()
            requireWork()
        }

        override fun retain(operation: TestActiveInitialCheckpointOperationV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (!selected || retained != null || operation.original !== testInitialCheckpoint || operation.path !== path || !operation.belongsTo(this@PersistencePhaseContext)) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            operation.original.requirePersistence(ownership, jdbc)
            retained = operation
        }

        override fun requireRetained(operation: TestActiveInitialCheckpointOperationV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (retained !== operation || operation.original !== testInitialCheckpoint || operation.path !== path ||
                !selectedHolder.activeInitialCheckpointReady()) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            operation.original.requirePersistence(ownership, jdbc)
        }

        override fun requireCommitted(operation: TestActiveInitialCheckpointOperationV1) {
            if (retained !== operation || !completed() || !testActiveInitialCheckpointCleanupProven(operation.original)) {
                failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            }
            requireSuccessfulResult()
        }

        override fun completed(): Boolean = retained?.let { it.original === testInitialCheckpoint && it.path === path && it.completedFor(this@PersistencePhaseContext) } == true
    }

    private inner class TestActiveRecurrentBoundary : PersistenceTestActiveRecurrentV1 {
        private var selected = false
        private var retained: TestActiveRecurrentOperationV1? = null

        override fun requireOperation(original: TestActiveRecurrentV1, jdbc: JdbcTemplate) {
            if (!path.testActiveRecurrent || original !== testRecurrent || original.path !== path) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            requireStepUpResource(jdbc, path)
            if (selected || !selectedHolder.activeRecurrentReady()) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            original.requirePersistence(ownership, jdbc)
            selected = true
            installLimits()
            requireWork()
        }

        override fun retain(operation: TestActiveRecurrentOperationV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (!selected || retained != null || operation.original !== testRecurrent || operation.path !== path || !operation.belongsTo(this@PersistencePhaseContext)) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            operation.original.requirePersistence(ownership, jdbc)
            retained = operation
        }

        override fun requireRetained(operation: TestActiveRecurrentOperationV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (retained !== operation || operation.original !== testRecurrent || operation.path !== path ||
                !selectedHolder.activeRecurrentReady()) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            operation.original.requirePersistence(ownership, jdbc)
        }

        override fun requireCommitted(operation: TestActiveRecurrentOperationV1) {
            if (retained !== operation || !completed() || !testActiveRecurrentCleanupProven(operation.original)) {
                failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            }
            requireSuccessfulResult()
        }

        override fun completed(): Boolean = retained?.let { it.original === testRecurrent && it.path === path && it.completedFor(this@PersistencePhaseContext) } == true
    }

    private inner class TestActiveOwnerDeleteQueueBoundary : PersistenceTestActiveOwnerDeleteQueueV1 {
        private var selected = false
        private var retained: TestActiveOwnerDeleteQueueOperationV1? = null

        override fun requireOperation(original: TestActiveOwnerDeleteQueueV1, jdbc: JdbcTemplate) {
            if (!path.testActiveOwnerDeleteQueue || original !== testActiveQueue || original.path !== path) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            requireStepUpResource(jdbc, path)
            if (selected || !selectedHolder.activeOwnerDeleteQueueReady()) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            original.requirePersistence(ownership, jdbc)
            selected = true
            installLimits()
            requireWork()
        }

        override fun retain(operation: TestActiveOwnerDeleteQueueOperationV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (!selected || retained != null || operation.original !== testActiveQueue || operation.path !== path || !operation.belongsTo(this@PersistencePhaseContext)) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            operation.original.requirePersistence(ownership, jdbc)
            retained = operation
        }

        override fun requireRetained(operation: TestActiveOwnerDeleteQueueOperationV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (retained !== operation || operation.original !== testActiveQueue || operation.path !== path ||
                !selectedHolder.activeOwnerDeleteQueueReady()) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            operation.original.requirePersistence(ownership, jdbc)
        }

        override fun requireCommitted(operation: TestActiveOwnerDeleteQueueOperationV1) {
            if (retained !== operation || !completed() || !testActiveOwnerDeleteQueueCleanupProven(operation.original)) {
                failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            }
            requireSuccessfulResult()
        }

        override fun completed(): Boolean = retained?.let { it.original === testActiveQueue && it.path === path && it.completedFor(this@PersistencePhaseContext) } == true
    }

    private inner class TestActiveOrdinarySealRecoveryBoundary : PersistenceTestActiveOrdinarySealRecoveryV1 {
        private var selected = false
        private var retained: TestActiveOrdinarySealRecoveryOperationV1? = null

        override fun requireOperation(original: TestActiveOrdinarySealRecoveryV1, jdbc: JdbcTemplate) {
            if (!path.testActiveOrdinarySealRecovery || original !== testActiveSealRecovery || original.path !== path) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            requireStepUpResource(jdbc, path)
            if (selected || !selectedHolder.activeOrdinarySealRecoveryReady()) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            original.requirePersistence(ownership, jdbc)
            selected = true
            installLimits()
            requireWork()
        }

        override fun retain(operation: TestActiveOrdinarySealRecoveryOperationV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (!selected || retained != null || operation.original !== testActiveSealRecovery || operation.path !== path || !operation.belongsTo(this@PersistencePhaseContext)) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            operation.original.requirePersistence(ownership, jdbc)
            retained = operation
        }

        override fun requireRetained(operation: TestActiveOrdinarySealRecoveryOperationV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (retained !== operation || operation.original !== testActiveSealRecovery || operation.path !== path ||
                !selectedHolder.activeOrdinarySealRecoveryReady()) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            operation.original.requirePersistence(ownership, jdbc)
        }

        override fun requireCommitted(operation: TestActiveOrdinarySealRecoveryOperationV1) {
            if (retained !== operation || !completed() || !testActiveOrdinarySealRecoveryCleanupProven(operation.original)) {
                failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            }
            requireSuccessfulResult()
        }

        override fun completed(): Boolean = retained?.let { it.original === testActiveSealRecovery && it.path === path && it.completedFor(this@PersistencePhaseContext) } == true
    }

    private inner class TestInstallationManifestBoundary : PersistenceTestInstallationManifestV1 {
        private var selected = false
        private var retained: TestInstallationManifestOperationV1? = null

        override fun requireOperation(original: TestRunInstallationManifestV1, jdbc: JdbcTemplate) {
            if (path !== PersistencePhasePath.COMPLAINT_TEST_INSTALLATION_MANIFEST_PREPARE || original !== testInstallationManifest || original.path !== path) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            requireStepUpResource(jdbc, path)
            if (selected || !selectedHolder.fenceReady()) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            original.requirePersistence(ownership, jdbc)
            selected = true
            installLimits()
            requireWork()
        }

        override fun retain(operation: TestInstallationManifestOperationV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (!selected || retained != null || operation.original !== testInstallationManifest || operation.path !== path || !operation.belongsTo(this@PersistencePhaseContext)) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            operation.original.requirePersistence(ownership, jdbc)
            retained = operation
        }

        override fun requireRetained(operation: TestInstallationManifestOperationV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (retained !== operation || operation.original !== testInstallationManifest || operation.path !== path ||
                !selectedHolder.fenceReady()) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            operation.original.requirePersistence(ownership, jdbc)
        }

        override fun requireCommitted(operation: TestInstallationManifestOperationV1) {
            if (retained !== operation || !completed() || !testInstallationManifestCleanupProven(operation.original)) {
                failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            }
            requireSuccessfulResult()
        }

        override fun completed(): Boolean = retained?.let { it.original === testInstallationManifest && it.path === path && it.completedFor(this@PersistencePhaseContext) } == true
    }

    private inner class TestInstallationManifestPublicationBoundary : PersistenceTestInstallationManifestPublicationV1 {
        private var selected = false
        private var retained: TestInstallationManifestPublicationOperationV1? = null

        override fun requireOperation(original: TestRunInstallationManifestPublicationV1, jdbc: JdbcTemplate) {
            if (path !in setOf(PersistencePhasePath.COMPLAINT_TEST_INSTALLATION_MANIFEST_PUBLICATION, PersistencePhasePath.COMPLAINT_TEST_INSTALLATION_MANIFEST_VERIFY) || original !== testInstallationManifestPublication || original.path !== path) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            requireStepUpResource(jdbc, path)
            if (selected || !ready()) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            original.requirePersistence(ownership, jdbc)
            selected = true
            installLimits()
            requireWork()
        }

        override fun retain(operation: TestInstallationManifestPublicationOperationV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (!selected || retained != null || operation.original !== testInstallationManifestPublication || operation.path !== path || !operation.belongsTo(this@PersistencePhaseContext)) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            operation.original.requirePersistence(ownership, jdbc)
            retained = operation
        }

        override fun requireRetained(operation: TestInstallationManifestPublicationOperationV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (retained !== operation || operation.original !== testInstallationManifestPublication || operation.path !== path ||
                !ready()) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            operation.original.requirePersistence(ownership, jdbc)
        }

        override fun requireCommitted(operation: TestInstallationManifestPublicationOperationV1) {
            if (retained !== operation || !completed() || !testInstallationManifestPublicationCleanupProven(operation.original)) {
                failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            }
            requireSuccessfulResult()
        }

        private fun ready(): Boolean = if (path === PersistencePhasePath.COMPLAINT_TEST_INSTALLATION_MANIFEST_VERIFY)
            selectedHolder.receiptlessManifestReady() else selectedHolder.fenceReady()

        override fun completed(): Boolean = retained?.let { it.original === testInstallationManifestPublication && it.path === path && it.completedFor(this@PersistencePhaseContext) } == true
    }

    /** The new catalog holder cannot select the predecessor's P/L preflight operation. */
    private inner class CatalogTestRunTerminalBoundary : PersistenceCatalogTestRunTerminalV1 {
        private var selected: CatalogTestRunTerminalPhaseV1? = null
        private var retained: CatalogTestRunTerminalOperationV1? = null
        override fun requireOperation(input: CatalogTestRunTerminalPhaseV1, jdbc: JdbcTemplate) {
            if (!path.catalogTestRunTerminal || input.original !== testRunTerminalCatalog || input.path !== path || selected != null) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            requireStepUpResource(jdbc, path)
            if (input.requiresEpochFence && !selectedHolder.fenceReady()) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            input.requirePersistence(ownership, jdbc); selected = input; installLimits(); requireWork()
        }
        override fun retain(operation: CatalogTestRunTerminalOperationV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (retained != null || selected !== operation.input || operation.input.original !== testRunTerminalCatalog ||
                operation.path !== path || !operation.belongsTo(this@PersistencePhaseContext)) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            operation.input.requirePersistence(ownership, jdbc); retained = operation
        }
        override fun requireRetained(operation: CatalogTestRunTerminalOperationV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (retained !== operation || selected !== operation.input || operation.input.original !== testRunTerminalCatalog ||
                operation.path !== path || operation.input.requiresEpochFence && !selectedHolder.fenceReady()) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            operation.input.requirePersistence(ownership, jdbc)
        }
        override fun requireCommitted(operation: CatalogTestRunTerminalOperationV1) {
            if (retained !== operation || !completed() || !testRunTerminalCatalogCleanupProven(operation.input.original)) {
                failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            }
            requireSuccessfulResult()
        }
        override fun completed(): Boolean = retained?.let { selected === it.input && it.input.original === testRunTerminalCatalog &&
            it.path === path && it.completedFor(this@PersistencePhaseContext) } == true
    }

    /** This independently released holder never obtains a catalog-boundary capability. */
    private inner class TestRunTerminalCatalogPreflightBoundary : PersistenceTestRunTerminalCatalogPreflightV1 {
        private var selected = false
        private var retained: CatalogTestRunTerminalPreflightOperationV1? = null
        override fun requireOperation(original: CatalogTestRunTerminalV1, jdbc: JdbcTemplate) {
            if (path !== PersistencePhasePath.COMPLAINT_TEST_RUN_TERMINAL_CATALOG_PREFLIGHT || original !== testRunTerminalCatalog || selected) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            requireStepUpResource(jdbc, path)
            if (!selectedHolder.fenceReady()) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            original.requirePreflightPersistence(ownership, jdbc); selected = true; installLimits(); requireWork()
        }
        override fun retain(operation: CatalogTestRunTerminalPreflightOperationV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (!selected || retained != null || operation.original !== testRunTerminalCatalog || operation.path !== path ||
                !operation.belongsTo(this@PersistencePhaseContext)) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            operation.original.requirePreflightPersistence(ownership, jdbc); retained = operation
        }
        override fun requireRetained(operation: CatalogTestRunTerminalPreflightOperationV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (retained !== operation || operation.original !== testRunTerminalCatalog || operation.path !== path || !selectedHolder.fenceReady()) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            operation.original.requirePreflightPersistence(ownership, jdbc)
        }
        override fun requireCommitted(operation: CatalogTestRunTerminalPreflightOperationV1) {
            if (retained !== operation || !completed() || !testRunTerminalCatalogCleanupProven(operation.original)) {
                failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            }
            requireSuccessfulResult()
        }
        override fun completed(): Boolean = retained?.let { it.original === testRunTerminalCatalog && it.path === path &&
            it.completedFor(this@PersistencePhaseContext) } == true
    }

    private inner class TestRunErasureBoundary : PersistenceTestRunErasureV1 {
        private var selected = false
        private var retained: TestRunErasureOperationV1? = null

        override fun requireOperation(original: TestRunErasureV1, jdbc: JdbcTemplate) {
            if (!path.testRunErasure || original !== testRunErasure || original.path !== path) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            requireStepUpResource(jdbc, path)
            if (selected || !ready()) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            original.requirePersistence(ownership, jdbc)
            selected = true
            installLimits()
            requireWork()
        }

        override fun retain(operation: TestRunErasureOperationV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (!selected || retained != null || operation.original !== testRunErasure || operation.path !== path || !operation.belongsTo(this@PersistencePhaseContext)) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            operation.original.requirePersistence(ownership, jdbc)
            retained = operation
        }

        override fun requireRetained(operation: TestRunErasureOperationV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (retained !== operation || operation.original !== testRunErasure || operation.path !== path ||
                !ready()) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            operation.original.requirePersistence(ownership, jdbc)
        }

        override fun requireCommitted(operation: TestRunErasureOperationV1) {
            if (retained !== operation || !completed() || !testRunErasureCleanupProven(operation.original)) {
                failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            }
            requireSuccessfulResult()
        }

        private fun ready(): Boolean = selectedHolder.fenceReady()

        override fun completed(): Boolean = retained?.let { it.original === testRunErasure && it.path === path && it.completedFor(this@PersistencePhaseContext) } == true
    }

    private inner class TestTerminalQuiescenceBoundary : PersistenceTestTerminalQuiescenceV1 {
        private var selected = false
        private var retained: TestTerminalQuiescenceOperationV1? = null

        override fun requireOperation(original: TestRunTerminalQuiescenceV1, jdbc: JdbcTemplate) {
            if (path !== PersistencePhasePath.COMPLAINT_TEST_TERMINAL_QUIESCENCE || original !== testTerminalQuiescence || original.path !== path) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            requireStepUpResource(jdbc, path)
            if (selected || !ready()) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            original.requirePersistence(ownership, jdbc)
            selected = true
            installLimits()
            requireWork()
        }

        override fun retain(operation: TestTerminalQuiescenceOperationV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (!selected || retained != null || operation.original !== testTerminalQuiescence || operation.path !== path || !operation.belongsTo(this@PersistencePhaseContext)) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            operation.original.requirePersistence(ownership, jdbc)
            retained = operation
        }

        override fun requireRetained(operation: TestTerminalQuiescenceOperationV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (retained !== operation || operation.original !== testTerminalQuiescence || operation.path !== path ||
                !ready()) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            operation.original.requirePersistence(ownership, jdbc)
        }

        override fun requireCommitted(operation: TestTerminalQuiescenceOperationV1) {
            if (retained !== operation || !completed() || !testTerminalQuiescenceCleanupProven(operation.original)) {
                failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            }
            requireSuccessfulResult()
        }

        private fun ready(): Boolean = selectedHolder.fenceReady()

        override fun completed(): Boolean = retained?.let { it.original === testTerminalQuiescence && it.path === path && it.completedFor(this@PersistencePhaseContext) } == true
    }

    private inner class TestRunPurgeBoundary : PersistenceTestRunPurgeV1 {
        private var selected = false
        private var retained: TestRunPurgeOperationV1? = null

        override fun requireOperation(original: TestRunPurgePublicationV1, jdbc: JdbcTemplate) {
            if (path !in setOf(PersistencePhasePath.COMPLAINT_TEST_RUN_PURGE_PUBLICATION, PersistencePhasePath.COMPLAINT_TEST_RUN_PURGE_VERIFY) || original !== testRunPurge || original.path !== path) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            requireStepUpResource(jdbc, path)
            if (selected || !ready()) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            original.requirePersistence(ownership, jdbc)
            selected = true
            installLimits()
            requireWork()
        }

        override fun retain(operation: TestRunPurgeOperationV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (!selected || retained != null || operation.original !== testRunPurge || operation.path !== path || !operation.belongsTo(this@PersistencePhaseContext)) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            operation.original.requirePersistence(ownership, jdbc)
            retained = operation
        }

        override fun requireRetained(operation: TestRunPurgeOperationV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (retained !== operation || operation.original !== testRunPurge || operation.path !== path ||
                !ready()) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            operation.original.requirePersistence(ownership, jdbc)
        }

        override fun requireCommitted(operation: TestRunPurgeOperationV1) {
            if (retained !== operation || !completed() || !testRunPurgeCleanupProven(operation.original)) {
                failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            }
            requireSuccessfulResult()
        }

        private fun ready(): Boolean = if (path === PersistencePhasePath.COMPLAINT_TEST_RUN_PURGE_VERIFY)
            selectedHolder.receiptlessPurgeReady() else selectedHolder.fenceReady()

        override fun completed(): Boolean = retained?.let { it.original === testRunPurge && it.path === path && it.completedFor(this@PersistencePhaseContext) } == true
    }

    private inner class TestTerminalEpochSealBoundary : PersistenceTestTerminalEpochSealV1 {
        private var selected = false
        private var retained: TestTerminalEpochSealOperationV1? = null

        override fun requireOperation(original: TestRunTerminalEpochSealV1, jdbc: JdbcTemplate) {
            if (path !== PersistencePhasePath.COMPLAINT_TEST_TERMINAL_EPOCH_SEAL || original !== testTerminalEpochSeal || original.path !== path) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            requireStepUpResource(jdbc, path)
            if (selected || !ready()) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            original.requirePersistence(ownership, jdbc)
            selected = true
            installLimits()
            requireWork()
        }

        override fun retain(operation: TestTerminalEpochSealOperationV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (!selected || retained != null || operation.original !== testTerminalEpochSeal || operation.path !== path || !operation.belongsTo(this@PersistencePhaseContext)) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            operation.original.requirePersistence(ownership, jdbc)
            retained = operation
        }

        override fun requireRetained(operation: TestTerminalEpochSealOperationV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (retained !== operation || operation.original !== testTerminalEpochSeal || operation.path !== path ||
                !ready()) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            operation.original.requirePersistence(ownership, jdbc)
        }

        override fun requireCommitted(operation: TestTerminalEpochSealOperationV1) {
            if (retained !== operation || !completed() || !testTerminalEpochSealCleanupProven(operation.original)) {
                failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            }
            requireSuccessfulResult()
        }

        private fun ready(): Boolean = selectedHolder.fenceReady()

        override fun completed(): Boolean = retained?.let { it.original === testTerminalEpochSeal && it.path === path && it.completedFor(this@PersistencePhaseContext) } == true
    }

    private inner class TestOrdinaryDrainBoundary : PersistenceTestOrdinaryDrainV1 {
        private var selected = false
        private var retained: TestOrdinaryDrainOperationV1? = null

        override fun requireOperation(original: TestRunOrdinaryDrainV1, jdbc: JdbcTemplate) {
            if (path !== PersistencePhasePath.COMPLAINT_TEST_ORDINARY_DRAIN || original !== testOrdinaryDrain || original.path !== path) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            requireStepUpResource(jdbc, path)
            if (selected || !selectedHolder.fenceReady()) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            original.requirePersistence(ownership, jdbc)
            selected = true
            installLimits()
            requireWork()
        }

        override fun retain(operation: TestOrdinaryDrainOperationV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (!selected || retained != null || operation.original !== testOrdinaryDrain || operation.path !== path || !operation.belongsTo(this@PersistencePhaseContext)) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            operation.original.requirePersistence(ownership, jdbc)
            retained = operation
        }

        override fun requireRetained(operation: TestOrdinaryDrainOperationV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (retained !== operation || operation.original !== testOrdinaryDrain || operation.path !== path ||
                !selectedHolder.fenceReady()) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            operation.original.requirePersistence(ownership, jdbc)
        }

        override fun requireCommitted(operation: TestOrdinaryDrainOperationV1) {
            if (retained !== operation || !completed() || !testOrdinaryDrainCleanupProven(operation.original)) {
                failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            }
            requireSuccessfulResult()
        }

        override fun completed(): Boolean = retained?.let { it.original === testOrdinaryDrain && it.path === path && it.completedFor(this@PersistencePhaseContext) } == true
    }

    /** Only the claimed initial original may capture or release its exact closed gate. */
    private inner class TestInitialAdmissionBoundary : PersistenceTestInitialAdmissionV1 {
        private var selected = false
        private var retained: TestInitialAdmissionOperationV1? = null

        override fun requireOperation(original: ComplaintTestInitialAdmissionV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (!path.testInitialAdmission || selected || original !== initialAdmission || original.path !== path || !selectedHolder.fenceReady()) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            original.requirePersistence(ownership, jdbc)
            selected = true
            installLimits()
            requireWork()
        }

        override fun retain(operation: TestInitialAdmissionOperationV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (!selected || retained != null || operation.original !== initialAdmission || operation.path !== path || !operation.belongsTo(this@PersistencePhaseContext)) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            operation.original.requirePersistence(ownership, jdbc)
            retained = operation
        }

        override fun requireRetained(operation: TestInitialAdmissionOperationV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (retained !== operation || operation.original !== initialAdmission || operation.path !== path || !selectedHolder.fenceReady()) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            operation.original.requirePersistence(ownership, jdbc)
        }

        override fun requireCommitted(operation: TestInitialAdmissionOperationV1) {
            if (retained !== operation || !completed() || !testInitialAdmissionCleanupProven(operation.original)) {
                failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            }
            requireSuccessfulResult()
        }

        override fun completed(): Boolean = retained?.let { it.original === initialAdmission && it.path === path && it.completedFor(this@PersistencePhaseContext) } == true
    }

    /** Concrete ACTIVE current-read/lease/paid REQUEST only; no prior-initial or closed-run authority. */
    private inner class TestActiveFirstCutBoundary : PersistenceTestActiveFirstCutV1 {
        private var selected = false
        private var retained: TestActiveFirstCutOperationV1? = null

        override fun requireOperation(original: TestActiveFirstCutV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (!path.testActiveFirstCut || selected || original !== activeFirstCut || original.path !== path || !selectedHolder.fenceReady()) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            original.requirePersistence(ownership, jdbc)
            selected = true
            installLimits()
            requireWork()
        }

        override fun retain(operation: TestActiveFirstCutOperationV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (!selected || retained != null || operation.original !== activeFirstCut || operation.path !== path || !operation.belongsTo(this@PersistencePhaseContext)) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            operation.original.requirePersistence(ownership, jdbc)
            retained = operation
        }

        override fun requireRetained(operation: TestActiveFirstCutOperationV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (retained !== operation || operation.original !== activeFirstCut || operation.path !== path || !selectedHolder.fenceReady()) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            operation.original.requirePersistence(ownership, jdbc)
        }

        override fun requireCommitted(operation: TestActiveFirstCutOperationV1) {
            if (retained !== operation || !completed() || !testActiveFirstCutCleanupProven(operation.original)) {
                failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            }
            requireSuccessfulResult()
        }

        override fun completed(): Boolean = retained?.let { it.original === activeFirstCut && it.path === path && it.completedFor(this@PersistencePhaseContext) } == true
    }

    /** Distinct paid-RESERVED successor read/lease/release only; no prior-initial or closed-run authority. */
    private inner class TestActiveFirstCutSuccessorBoundary : PersistenceTestActiveFirstCutSuccessorV1 {
        private var selected = false
        private var retained: TestActiveFirstCutSuccessorOperationV1? = null

        override fun requireOperation(original: TestActiveFirstCutSuccessorV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (!path.testActiveFirstCutSuccessor || selected || original !== activeFirstCutSuccessor || original.path !== path || !selectedHolder.fenceReady()) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            original.requirePersistence(ownership, jdbc)
            selected = true
            installLimits()
            requireWork()
        }

        override fun retain(operation: TestActiveFirstCutSuccessorOperationV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (!selected || retained != null || operation.original !== activeFirstCutSuccessor || operation.path !== path || !operation.belongsTo(this@PersistencePhaseContext)) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            operation.original.requirePersistence(ownership, jdbc)
            retained = operation
        }

        override fun requireRetained(operation: TestActiveFirstCutSuccessorOperationV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (retained !== operation || operation.original !== activeFirstCutSuccessor || operation.path !== path || !selectedHolder.fenceReady()) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            operation.original.requirePersistence(ownership, jdbc)
        }

        override fun requireCommitted(operation: TestActiveFirstCutSuccessorOperationV1) {
            if (retained !== operation || !completed() || !testActiveFirstCutSuccessorCleanupProven(operation.original)) {
                failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            }
            requireSuccessfulResult()
        }

        override fun completed(): Boolean = retained?.let { it.original === activeFirstCutSuccessor && it.path === path && it.completedFor(this@PersistencePhaseContext) } == true
    }

    /** Exact runtime-root read/lock-only owner. No unowned closed-gate entry or supplied success can satisfy this boundary. */
    private inner class TestNamespaceRegistrationBoundary : PersistenceTestNamespaceRegistrationV1 {
        private var selected = false
        private var retained: TestNamespaceRegistrationOperationV1? = null

        override fun requireOperation(original: ComplaintTestNamespaceRegistrationAttemptV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, PersistencePhasePath.COMPLAINT_TEST_NAMESPACE_REGISTRATION)
            if (selected || original !== testRegistration || !selectedHolder.fenceReady()) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            original.requirePersistence(ownership, jdbc)
            selected = true
            installLimits()
            requireWork()
        }

        override fun retain(operation: TestNamespaceRegistrationOperationV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, PersistencePhasePath.COMPLAINT_TEST_NAMESPACE_REGISTRATION)
            if (!selected || retained != null || operation.original !== testRegistration || !operation.belongsTo(this@PersistencePhaseContext)) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            operation.original.requirePersistence(ownership, jdbc)
            retained = operation
        }

        override fun requireRetained(operation: TestNamespaceRegistrationOperationV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, PersistencePhasePath.COMPLAINT_TEST_NAMESPACE_REGISTRATION)
            if (retained !== operation || operation.original !== testRegistration || !selectedHolder.fenceReady()) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            operation.original.requirePersistence(ownership, jdbc)
        }

        override fun requireCommitted(operation: TestNamespaceRegistrationOperationV1) {
            if (retained !== operation || !completed() || !testRegistrationCleanupProven(operation.original)) {
                failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            }
            requireSuccessfulResult()
        }

        override fun completed(): Boolean = retained?.let { it.original === testRegistration && it.completedFor(this@PersistencePhaseContext) } == true
    }

    /** Exact runtime-root read/lock-only owner. No unowned closed-gate entry or supplied success can satisfy this boundary. */
    private inner class TestNamespaceRecoveryRegistrationBoundary : PersistenceTestNamespaceRecoveryRegistrationV1 {
        private var selected = false
        private var retained: TestNamespaceRecoveryRegistrationOperationV1? = null

        override fun requireOperation(original: ComplaintTestNamespaceRecoveryRegistrationAttemptV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, PersistencePhasePath.COMPLAINT_TEST_NAMESPACE_RECOVERY_REGISTRATION)
            if (selected || original !== testRecoveryRegistration || !selectedHolder.fenceReady()) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            original.requirePersistence(ownership, jdbc)
            selected = true
            installLimits()
            requireWork()
        }

        override fun retain(operation: TestNamespaceRecoveryRegistrationOperationV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, PersistencePhasePath.COMPLAINT_TEST_NAMESPACE_RECOVERY_REGISTRATION)
            if (!selected || retained != null || operation.original !== testRecoveryRegistration || !operation.belongsTo(this@PersistencePhaseContext)) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            operation.original.requirePersistence(ownership, jdbc)
            retained = operation
        }

        override fun requireRetained(operation: TestNamespaceRecoveryRegistrationOperationV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, PersistencePhasePath.COMPLAINT_TEST_NAMESPACE_RECOVERY_REGISTRATION)
            if (retained !== operation || operation.original !== testRecoveryRegistration || !selectedHolder.fenceReady()) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            operation.original.requirePersistence(ownership, jdbc)
        }

        override fun requireCommitted(operation: TestNamespaceRecoveryRegistrationOperationV1) {
            if (retained !== operation || !completed() || !testRecoveryRegistrationCleanupProven(operation.original)) {
                failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            }
            requireSuccessfulResult()
        }

        override fun completed(): Boolean = retained?.let { it.original === testRecoveryRegistration && it.completedFor(this@PersistencePhaseContext) } == true
    }

    /** Exact runtime-root read/lock-only owner. No unowned identity entry or supplied success can satisfy this boundary. */
    private inner class TestNamespaceActiveRegistrationBoundary : PersistenceTestNamespaceActiveRegistrationV1 {
        private var selected = false
        private var retained: TestNamespaceActiveRegistrationOperationV1? = null

        override fun requireOperation(original: ComplaintTestNamespaceActiveRegistrationAttemptV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, PersistencePhasePath.COMPLAINT_TEST_NAMESPACE_ACTIVE_REGISTRATION)
            if (selected || original !== testActiveRegistration || !selectedHolder.fenceReady()) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            original.requirePersistence(ownership, jdbc)
            selected = true
            installLimits()
            requireWork()
        }

        override fun retain(operation: TestNamespaceActiveRegistrationOperationV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, PersistencePhasePath.COMPLAINT_TEST_NAMESPACE_ACTIVE_REGISTRATION)
            if (!selected || retained != null || operation.original !== testActiveRegistration || !operation.belongsTo(this@PersistencePhaseContext)) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            operation.original.requirePersistence(ownership, jdbc)
            retained = operation
        }

        override fun requireRetained(operation: TestNamespaceActiveRegistrationOperationV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, PersistencePhasePath.COMPLAINT_TEST_NAMESPACE_ACTIVE_REGISTRATION)
            if (retained !== operation || operation.original !== testActiveRegistration || !selectedHolder.fenceReady()) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            operation.original.requirePersistence(ownership, jdbc)
        }

        override fun requireCommitted(operation: TestNamespaceActiveRegistrationOperationV1) {
            if (retained !== operation || !completed() || !testActiveRegistrationCleanupProven(operation.original)) {
                failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            }
            requireSuccessfulResult()
        }

        override fun completed(): Boolean = retained?.let { it.original === testActiveRegistration && it.completedFor(this@PersistencePhaseContext) } == true
    }

    private inner class CatalogTestRunActivationBoundary : PersistenceCatalogTestRunActivationV1 {
        private var selectedInput: CatalogTestRunActivationInputV1? = null
        private var retained: CatalogTestRunActivationOperationV1? = null

        override fun requireOperation(input: CatalogTestRunActivationInputV1, jdbc: JdbcTemplate) {
            if (input.original !== testRunActivation || input.path !== path || !testPath()) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            requireStepUpResource(jdbc, path)
            if (selectedInput != null || (input.requiresEpochFence && !selectedHolder.fenceReady())) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            input.requirePersistence(ownership, jdbc)
            selectedInput = input
            installLimits()
            requireWork()
        }

        // The operation, input, owner and phase identities form one indivisible admission guard.
        @Suppress("ComplexCondition")
        override fun retain(operation: CatalogTestRunActivationOperationV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (retained != null || operation.input !== selectedInput || operation.input.original !== testRunActivation ||
                !operation.belongsTo(this@PersistencePhaseContext, path)
            ) {
                refuse(PersistencePhaseFailureCode.WORK_FAILED)
            }
            operation.input.requirePersistence(ownership, jdbc)
            retained = operation
        }

        // Keep exact retained identities and the original fence check visible at the same boundary.
        @Suppress("ComplexCondition")
        override fun requireRetained(operation: CatalogTestRunActivationOperationV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (retained !== operation || operation.input !== selectedInput || operation.input.original !== testRunActivation ||
                (operation.input.requiresEpochFence && !selectedHolder.fenceReady())
            ) {
                refuse(PersistencePhaseFailureCode.WORK_FAILED)
            }
            operation.input.requirePersistence(ownership, jdbc)
        }

        // A result requires this exact retained operation, completion and positively proven original cleanup.
        @Suppress("ComplexCondition")
        override fun requireCommitted(operation: CatalogTestRunActivationOperationV1) {
            if (retained !== operation || operation.input !== selectedInput || !completed() ||
                !testRunActivationCleanupProven(operation.input.original)
            ) {
                failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            }
            requireSuccessfulResult()
        }

        override fun completed(): Boolean = retained?.let {
            it.input === selectedInput && it.input.original === testRunActivation && it.completedFor(this@PersistencePhaseContext)
        } == true

        private fun testPath(): Boolean = path.catalogTestRunActivation
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

    /** Publisher-only current recheck, sharing no operation/receipt with the D-mutating first-D path. */
    private inner class CatalogGenesisPublishRecheckBoundary : PersistenceCatalogGenesisPublishRecheck {
        private var issued = false
        private var retained: ComplaintCatalogGenesisPublishRecheckOperationV1? = null

        override fun requireOperation(jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PUBLISH_RECHECK)
            val attempt = catalogPublisherAttempt ?: refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            attempt.requirePersistence(ownership, jdbc)
            if (issued || !selectedHolder.fenceReady()) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            issued = true
            installLimits()
            requireWork()
        }

        override fun retain(operation: ComplaintCatalogGenesisPublishRecheckOperationV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PUBLISH_RECHECK)
            if (!issued || retained != null || operation.attempt !== catalogPublisherAttempt) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            if (!operation.belongsTo(this@PersistencePhaseContext)) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            retained = operation
        }

        override fun requireRetained(operation: ComplaintCatalogGenesisPublishRecheckOperationV1, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PUBLISH_RECHECK)
            if (retained !== operation || operation.attempt !== catalogPublisherAttempt || !selectedHolder.fenceReady()) {
                refuse(PersistencePhaseFailureCode.WORK_FAILED)
            }
            operation.attempt.requirePersistence(ownership, jdbc)
        }

        override fun requireCommitted(operation: ComplaintCatalogGenesisPublishRecheckOperationV1) {
            val retainedByCaller = caller.isCurrent() && retained === operation && operation.attempt === catalogPublisherAttempt
            if (!retainedByCaller || !operation.completedFor(this@PersistencePhaseContext)) {
                failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            }
            requireSuccessfulResult() // Actual known COMMITTED and released original holder/permit, before any publisher I/O.
        }

        override fun completed(): Boolean = retained?.let { it.attempt === catalogPublisherAttempt && it.completedFor(this@PersistencePhaseContext) } == true
    }

    /** Six fixed operations; both creation writes require an exact-operation one-use handoff, never a raw bypass. */
    private inner class OwnerOperationBoundary : PersistenceOwnerOperation {
        private var issued = false
        private var retained: ComplaintOwnerCreateOperation? = null
        private val admissionIdentity = Any()
        private var admission: ComplaintAdmittedOwnerCreate? = null
        private var claimed = false
        private var boundsChecked = false

        override fun bindRegisteredInitialCheckpointCreate(original: me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredInitialCheckpointCreateV1,
            context: me.manga.kira.backend.security.ComplaintIngressContext?) {
            requireCaller()
            if (stage !== Stage.PREPARED || registeredInitialCheckpointCreate != null) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            original.requirePhaseOwner(ownership) // Entry already happened; this PREPARED phase now owns the caller.
            original.requirePath(path)
            if (writeOperation != null) original.requireAdmission(admission ?: refuse(PersistencePhaseFailureCode.WORK_FAILED))
            else original.requireReadAdmission(context ?: refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED))
            registeredInitialCheckpointCreate = original
        }

        private val writeOperation: ComplaintOwnerCreationOperation?
            get() = when (path) {
                PersistencePhasePath.COMPLAINT_OWNER_CREATE -> ComplaintOwnerCreationOperation.OWNER_CREATE
                PersistencePhasePath.COMPLAINT_OWNER_REPLY -> ComplaintOwnerCreationOperation.OWNER_REPLY
                else -> null
            }

        override fun bindCreate(handoff: ComplaintAdmittedOwnerCreate) {
            requireCaller()
            if (stage !== Stage.PREPARED || writeOperation == null ||
                admission != null
            ) {
                refuse(PersistencePhaseFailureCode.WORK_FAILED)
            }
            admission = handoff
            ComplaintIngressAdmission.bindOwnerCreate(handoff, admissionIdentity, checkNotNull(writeOperation))
        }

        override fun requireOperation(jdbc: JdbcTemplate, expected: PersistencePhasePath) {
            if (expected !in setOf(
                    PersistencePhasePath.COMPLAINT_OWNER_OPERATION_AUTHENTICATION,
                    PersistencePhasePath.COMPLAINT_OWNER_CREATE_PREFLIGHT,
                    PersistencePhasePath.COMPLAINT_OWNER_CREATE,
                    PersistencePhasePath.COMPLAINT_OWNER_REPLY_PREFLIGHT,
                    PersistencePhasePath.COMPLAINT_OWNER_REPLY,
                    PersistencePhasePath.COMPLAINT_OWNER_OPERATION_STATUS,
                )
            ) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            requireStepUpResource(jdbc, expected)
            ownership.dataSource.requireTestInitialCheckpointCreate(registeredInitialCheckpointCreate?.policy)
            if (issued || (writeOperation != null && admission == null)) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            issued = true
            installLimits()
            requireWork()
        }

        override fun retain(operation: ComplaintOwnerCreateOperation, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (!issued || retained != null || !operation.belongsTo(this@PersistencePhaseContext, path) ||
                !operation.registeredWith(registeredInitialCheckpointCreate)) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            retained = operation
        }

        override fun requireRetained(operation: ComplaintOwnerCreateOperation, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (retained !== operation) refuse(PersistencePhaseFailureCode.WORK_FAILED)
        }

        override fun requireOwner(operation: ComplaintOwnerCreateOperation, jdbc: JdbcTemplate, selected: PersistencePhaseOwnership) {
            requireRetained(operation, jdbc)
            if (selected !== ownership) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }

        override fun connection(operation: ComplaintOwnerCreateOperation, jdbc: JdbcTemplate): Connection {
            requireRetained(operation, jdbc)
            return connection ?: refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }

        override fun claimCreate(operation: ComplaintOwnerCreateOperation, jdbc: JdbcTemplate, tuple: ComplaintOwnerOperationTuple) {
            requireRetained(operation, jdbc)
            if (writeOperation !== tuple.operation || claimed) refuse(PersistencePhaseFailureCode.WORK_FAILED)
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
            (writeOperation == null || claimed)
    }

    /** Closed single-target TEST phases; no callback, caller operation implementation or alternative holder. */
    private inner class OwnerDeleteBoundary : PersistenceOwnerDelete {
        private var issued = false
        private var retained: ComplaintOwnerDeletePhaseOperation? = null
        private var admission: ComplaintAdmittedOwnerDelete? = null
        private val admissionIdentity = Any()
        private var claimed = false
        private var boundsChecked = false

        override fun bindAuthorize(handoff: ComplaintAdmittedOwnerDelete) {
            requireCaller()
            if (stage !== Stage.PREPARED || path !== PersistencePhasePath.COMPLAINT_OWNER_DELETE_AUTHORIZE || admission != null) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            ComplaintIngressAdmission.bindOwnerDelete(handoff, admissionIdentity)
            admission = handoff
        }

        override fun requireOperation(jdbc: JdbcTemplate, expected: PersistencePhasePath) {
            if (expected !in setOf(PersistencePhasePath.COMPLAINT_OWNER_DELETE_AUTHORIZE, PersistencePhasePath.COMPLAINT_OWNER_DELETE_RELOAD,
                    PersistencePhasePath.COMPLAINT_OWNER_DELETE_VERIFY, PersistencePhasePath.COMPLAINT_OWNER_DELETE_APPLY)) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            requireStepUpResource(jdbc, expected)
            requireInitialDeletionPoolPolicy()
            if (issued || entityManagerFactory != null || (expected !== PersistencePhasePath.COMPLAINT_OWNER_DELETE_VERIFY && !selectedHolder.fenceReady())) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            if (expected === PersistencePhasePath.COMPLAINT_OWNER_DELETE_AUTHORIZE && admission == null) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            issued = true
            installLimits()
            requireWork()
        }

        override fun retain(operation: ComplaintOwnerDeletePhaseOperation, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (!issued || retained != null || !operation.belongsTo(this@PersistencePhaseContext, path)) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            retained = operation
            testRunOwnerDelete?.authenticateAndControls(ownership, jdbc, operation)
            testOrdinaryDrain?.authenticateRecoveryAndControls(ownership, jdbc, operation)
            testActiveQueue?.authenticateRecoveryAndControls(ownership, jdbc, operation)
            testRecurrentApply?.authenticateRecoveryAndControls(ownership, jdbc, operation)
        }

        override fun requireRetained(operation: ComplaintOwnerDeletePhaseOperation, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (retained !== operation || (path !== PersistencePhasePath.COMPLAINT_OWNER_DELETE_VERIFY && !selectedHolder.fenceReady())) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            testActiveQueue?.requireRecoveryHolder(jdbc)
            testRecurrentApply?.requireRecoveryHolder(jdbc)
        }

        override fun claim(operation: ComplaintOwnerDeletePhaseOperation, jdbc: JdbcTemplate, tuple: ComplaintOwnerDeleteTuple) {
            requireRetained(operation, jdbc)
            if (path !== PersistencePhasePath.COMPLAINT_OWNER_DELETE_AUTHORIZE || claimed) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            ComplaintIngressAdmission.claimOwnerDelete(checkNotNull(admission), admissionIdentity, tuple)
            claimed = true
        }

        override fun checkReceiptWrite(operation: ComplaintOwnerDeletePhaseOperation, jdbc: JdbcTemplate) {
            requireRetained(operation, jdbc)
            if (!claimed) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            ComplaintIngressAdmission.checkOwnerDeleteReceipt(checkNotNull(admission), admissionIdentity)
        }

        override fun checkCapacity(operation: ComplaintOwnerDeletePhaseOperation, jdbc: JdbcTemplate, ledger: ComplaintCapacityLedger) {
            requireRetained(operation, jdbc)
            if (boundsChecked) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            if (path === PersistencePhasePath.COMPLAINT_OWNER_DELETE_AUTHORIZE) {
                if (!claimed) refuse(PersistencePhaseFailureCode.WORK_FAILED)
                ComplaintIngressAdmission.checkOwnerDeleteBounds(checkNotNull(admission), admissionIdentity, ledger)
            }
            boundsChecked = true
        }

        override fun checkWrite(operation: ComplaintOwnerDeletePhaseOperation, jdbc: JdbcTemplate) {
            requireRetained(operation, jdbc)
            when (path) {
                PersistencePhasePath.COMPLAINT_OWNER_DELETE_AUTHORIZE -> {
                    if (!claimed || !boundsChecked) refuse(PersistencePhaseFailureCode.WORK_FAILED)
                    ComplaintIngressAdmission.checkOwnerDeleteWrite(checkNotNull(admission), admissionIdentity)
                }
                PersistencePhasePath.COMPLAINT_OWNER_DELETE_APPLY -> if (!boundsChecked) refuse(PersistencePhaseFailureCode.WORK_FAILED)
                PersistencePhasePath.COMPLAINT_OWNER_DELETE_VERIFY -> Unit
                else -> refuse(PersistencePhaseFailureCode.WORK_FAILED)
            }
        }

        override fun connection(operation: ComplaintOwnerDeletePhaseOperation, jdbc: JdbcTemplate): Connection {
            checkWrite(operation, jdbc)
            return this@PersistencePhaseContext.connection ?: refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }

        override fun requireCommitted(operation: ComplaintOwnerDeletePhaseOperation) {
            if (!caller.isCurrent() || retained !== operation || !completed()) failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            if (testActiveQueue != null && !testActiveOwnerDeleteQueueCleanupProven(testActiveQueue)) failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            if (testRecurrentApply != null && !testActiveRecurrentApplyCleanupProven(testRecurrentApply)) failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            requireSuccessfulResult()
        }

        override fun completed(): Boolean = retained?.completedFor(this@PersistencePhaseContext, path) == true &&
            (path !== PersistencePhasePath.COMPLAINT_OWNER_DELETE_AUTHORIZE || claimed)
    }

    private inner class AdminDeleteBoundary : PersistenceAdminDelete {
        private var issued = false
        private var retained: ComplaintAdminDeletePhaseOperation? = null
        private var admission: ComplaintAdmittedAdminErasure? = null
        private val admissionIdentity = Any()
        private var claimed = false
        private var boundsChecked = false

        override fun bindAuthorize(handoff: ComplaintAdmittedAdminErasure) {
            requireCaller()
            if (stage !== Stage.PREPARED || path !== PersistencePhasePath.COMPLAINT_ADMIN_DELETE_AUTHORIZE || admission != null) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            ComplaintIngressAdmission.bindAdminErasure(handoff, admissionIdentity)
            admission = handoff
        }

        override fun requireOperation(jdbc: JdbcTemplate, expected: PersistencePhasePath) {
            if (expected !in setOf(PersistencePhasePath.COMPLAINT_ADMIN_DELETE_AUTHORIZE, PersistencePhasePath.COMPLAINT_ADMIN_DELETE_RELOAD,
                    PersistencePhasePath.COMPLAINT_ADMIN_DELETE_VERIFY, PersistencePhasePath.COMPLAINT_ADMIN_DELETE_APPLY)) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            requireStepUpResource(jdbc, expected)
            requireInitialDeletionPoolPolicy()
            if (issued || entityManagerFactory != null || (expected !== PersistencePhasePath.COMPLAINT_ADMIN_DELETE_VERIFY && !selectedHolder.fenceReady())) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            if (expected === PersistencePhasePath.COMPLAINT_ADMIN_DELETE_AUTHORIZE && admission == null) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            issued = true
            installLimits()
            requireWork()
        }

        override fun retain(operation: ComplaintAdminDeletePhaseOperation, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (!issued || retained != null || !operation.belongsTo(this@PersistencePhaseContext, path)) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            retained = operation
            testRunAdminDelete?.authenticateAndControls(ownership, jdbc, operation)
            testOrdinaryDrain?.authenticateRecoveryAndControls(ownership, jdbc, operation)
            testActiveQueue?.authenticateRecoveryAndControls(ownership, jdbc, operation)
            testRecurrentApply?.authenticateRecoveryAndControls(ownership, jdbc, operation)
        }

        override fun requireRetained(operation: ComplaintAdminDeletePhaseOperation, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (retained !== operation || (path !== PersistencePhasePath.COMPLAINT_ADMIN_DELETE_VERIFY && !selectedHolder.fenceReady())) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            testActiveQueue?.requireRecoveryHolder(jdbc)
            testRecurrentApply?.requireRecoveryHolder(jdbc)
        }

        override fun claim(operation: ComplaintAdminDeletePhaseOperation, jdbc: JdbcTemplate, tuple: ComplaintAdminDeleteTuple) {
            requireRetained(operation, jdbc)
            if (path !== PersistencePhasePath.COMPLAINT_ADMIN_DELETE_AUTHORIZE || claimed) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            ComplaintIngressAdmission.claimAdminErasure(checkNotNull(admission), admissionIdentity, tuple)
            claimed = true
        }

        override fun checkReceiptWrite(operation: ComplaintAdminDeletePhaseOperation, jdbc: JdbcTemplate) {
            requireRetained(operation, jdbc)
            if (path === PersistencePhasePath.COMPLAINT_ADMIN_DELETE_APPLY) {
                val recovery = operation as? me.manga.kira.backend.complaint.infrastructure.ComplaintAdminDeleteApplyOperation
                    ?: refuse(PersistencePhaseFailureCode.WORK_FAILED)
                recovery.requireRecoveryReceiptClaim(jdbc)
                return
            }
            if (!claimed) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            ComplaintIngressAdmission.checkAdminErasureReceipt(checkNotNull(admission), admissionIdentity)
        }

        override fun checkCapacity(operation: ComplaintAdminDeletePhaseOperation, jdbc: JdbcTemplate, ledger: ComplaintCapacityLedger) {
            requireRetained(operation, jdbc)
            if (boundsChecked) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            if (path === PersistencePhasePath.COMPLAINT_ADMIN_DELETE_AUTHORIZE) {
                if (!claimed) refuse(PersistencePhaseFailureCode.WORK_FAILED)
                ComplaintIngressAdmission.checkAdminErasureBounds(checkNotNull(admission), admissionIdentity, ledger)
            }
            boundsChecked = true
        }

        override fun checkWrite(operation: ComplaintAdminDeletePhaseOperation, jdbc: JdbcTemplate) {
            requireRetained(operation, jdbc)
            when (path) {
                PersistencePhasePath.COMPLAINT_ADMIN_DELETE_AUTHORIZE -> {
                    if (!claimed || !boundsChecked) refuse(PersistencePhaseFailureCode.WORK_FAILED)
                    ComplaintIngressAdmission.checkAdminErasureWrite(checkNotNull(admission), admissionIdentity)
                }
                PersistencePhasePath.COMPLAINT_ADMIN_DELETE_APPLY -> if (!boundsChecked) refuse(PersistencePhaseFailureCode.WORK_FAILED)
                PersistencePhasePath.COMPLAINT_ADMIN_DELETE_VERIFY -> Unit
                else -> refuse(PersistencePhaseFailureCode.WORK_FAILED)
            }
        }

        override fun connection(operation: ComplaintAdminDeletePhaseOperation, jdbc: JdbcTemplate): Connection {
            checkWrite(operation, jdbc)
            return this@PersistencePhaseContext.connection ?: refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }

        override fun requireCommitted(operation: ComplaintAdminDeletePhaseOperation) {
            if (!caller.isCurrent() || retained !== operation || !completed()) failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            if (testActiveQueue != null && !testActiveOwnerDeleteQueueCleanupProven(testActiveQueue)) failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            if (testRecurrentApply != null && !testActiveRecurrentApplyCleanupProven(testRecurrentApply)) failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            requireSuccessfulResult()
        }

        override fun completed(): Boolean = retained?.completedFor(this@PersistencePhaseContext, path) == true &&
            (path !== PersistencePhasePath.COMPLAINT_ADMIN_DELETE_AUTHORIZE || claimed)
    }

    private inner class OwnerDeleteReadBoundary : PersistenceOwnerDeleteRead {
        private var issued = false
        private var retained: ComplaintOwnerDeleteReadOperation? = null

        override fun requireOperation(jdbc: JdbcTemplate, expected: PersistencePhasePath) {
            if (expected !in setOf(PersistencePhasePath.COMPLAINT_OWNER_DELETE_AUTHENTICATION, PersistencePhasePath.COMPLAINT_OWNER_DELETE_PREFLIGHT,
                    PersistencePhasePath.COMPLAINT_OWNER_DELETE_STATUS)) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            requireStepUpResource(jdbc, expected)
            requireInitialDeletionPoolPolicy()
            if (issued) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            issued = true
            installLimits()
            requireWork()
        }

        override fun retain(operation: ComplaintOwnerDeleteReadOperation, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (!issued || retained != null || !operation.belongsTo(this@PersistencePhaseContext, path)) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            retained = operation
        }

        override fun requireRetained(operation: ComplaintOwnerDeleteReadOperation, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (retained !== operation) refuse(PersistencePhaseFailureCode.WORK_FAILED)
        }

        override fun requireCommitted(operation: ComplaintOwnerDeleteReadOperation) {
            if (!caller.isCurrent() || retained !== operation || !completed()) failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            requireSuccessfulResult()
        }

        override fun completed(): Boolean = retained?.completedFor(this@PersistencePhaseContext, path) == true
    }

    private inner class AdminDeleteReadBoundary : PersistenceAdminDeleteRead {
        private var issued = false
        private var retained: ComplaintAdminDeleteReadOperation? = null

        override fun requireOperation(jdbc: JdbcTemplate, expected: PersistencePhasePath) {
            if (expected !== PersistencePhasePath.COMPLAINT_ADMIN_DELETE_PREFLIGHT) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            requireStepUpResource(jdbc, expected)
            requireInitialDeletionPoolPolicy()
            if (issued) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            issued = true
            installLimits()
            requireWork()
        }

        override fun retain(operation: ComplaintAdminDeleteReadOperation, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (!issued || retained != null || !operation.belongsTo(this@PersistencePhaseContext, path)) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            retained = operation
        }

        override fun requireRetained(operation: ComplaintAdminDeleteReadOperation, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (retained !== operation) refuse(PersistencePhaseFailureCode.WORK_FAILED)
        }

        override fun requireCommitted(operation: ComplaintAdminDeleteReadOperation) {
            if (!caller.isCurrent() || retained !== operation || !completed()) failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            requireSuccessfulResult()
        }

        override fun completed(): Boolean = retained?.completedFor(this@PersistencePhaseContext, path) == true
    }

    /** Fixed ADMIN_EDIT only; neither owner edit nor read authentication can substitute its capability. */
    private inner class AdminContentBoundary : PersistenceComplaintAdminContent {
        private var issued = false
        private var retained: ComplaintAdminContentOperation? = null
        private val admissionIdentity = Any()
        private var admission: ComplaintAdmittedAdminContent? = null
        private var claimed = false
        private var boundsChecked = false
        private val write: Boolean get() = path === PersistencePhasePath.COMPLAINT_ADMIN_EDIT

        override fun bindRegistered(original: me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredAdminContentV1) {
            requireCaller()
            if (stage !== Stage.PREPARED || registeredAdminContent != null || registeredAdminStatus != null) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            original.requirePhaseOwner(ownership)
            original.requirePath(path)
            original.requireIngress()
            if (write) original.requireAdmission(admission ?: refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED))
            registeredAdminContent = original
        }

        override fun bindEdit(handoff: ComplaintAdmittedAdminContent) {
            requireCaller()
            if (stage !== Stage.PREPARED || !write || admission != null) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            admission = handoff
            ComplaintIngressAdmission.bindAdminContent(handoff, admissionIdentity)
        }

        override fun requireOperation(jdbc: JdbcTemplate, expected: PersistencePhasePath) {
            if (expected !in setOf(
                    PersistencePhasePath.COMPLAINT_ADMIN_EDIT_PREFLIGHT,
                    PersistencePhasePath.COMPLAINT_ADMIN_EDIT,
                )
            ) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            requireStepUpResource(jdbc, expected)
            ownership.dataSource.requireTestInitialCheckpointCreate(registeredAdminContent?.policy)
            if (issued || (write && admission == null)) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            issued = true
            installLimits()
            requireWork()
        }

        override fun retain(operation: ComplaintAdminContentOperation, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (!issued || retained != null || !operation.belongsTo(this@PersistencePhaseContext, path) ||
                !operation.registeredWith(registeredAdminContent)) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            retained = operation
        }

        override fun requireRetained(operation: ComplaintAdminContentOperation, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (retained !== operation) refuse(PersistencePhaseFailureCode.WORK_FAILED)
        }

        override fun requireRegisteredOwner(original: me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredAdminContentV1,
            jdbc: JdbcTemplate, ownership: PersistencePhaseOwnership) {
            requireStepUpResource(jdbc, PersistencePhasePath.COMPLAINT_ADMIN_READ_AUTHENTICATION)
            if (registeredAdminContent !== original || ownership !== this@PersistencePhaseContext.ownership) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            original.requirePath(path)
            original.requireIngress()
        }

        override fun requireOwner(operation: ComplaintAdminContentOperation, jdbc: JdbcTemplate, ownership: PersistencePhaseOwnership) {
            requireRetained(operation, jdbc)
            if (ownership !== this@PersistencePhaseContext.ownership) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }

        override fun connection(operation: ComplaintAdminContentOperation, jdbc: JdbcTemplate): Connection {
            requireRetained(operation, jdbc)
            return connection ?: refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }

        override fun claimEdit(operation: ComplaintAdminContentOperation, jdbc: JdbcTemplate, tuple: ComplaintAdminContentTuple) {
            requireRetained(operation, jdbc)
            if (!write || claimed) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            ComplaintIngressAdmission.claimAdminContent(admission ?: refuse(PersistencePhaseFailureCode.WORK_FAILED), admissionIdentity, tuple)
            claimed = true
        }

        override fun checkGrantWrite(operation: ComplaintAdminContentOperation, jdbc: JdbcTemplate) {
            requireRetained(operation, jdbc)
            if (!write || !claimed || boundsChecked) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            ComplaintIngressAdmission.checkAdminContentClaim(admission ?: refuse(PersistencePhaseFailureCode.WORK_FAILED), admissionIdentity)
        }

        override fun checkEditBounds(operation: ComplaintAdminContentOperation, jdbc: JdbcTemplate, ledger: ComplaintCapacityLedger) {
            requireRetained(operation, jdbc)
            if (!claimed || boundsChecked) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            ComplaintIngressAdmission.checkAdminContentBounds(admission ?: refuse(PersistencePhaseFailureCode.WORK_FAILED), admissionIdentity, ledger)
            boundsChecked = true
        }

        override fun checkEditWrite(operation: ComplaintAdminContentOperation, jdbc: JdbcTemplate) {
            requireRetained(operation, jdbc)
            if (!claimed || !boundsChecked) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            ComplaintIngressAdmission.checkAdminContentWrite(admission ?: refuse(PersistencePhaseFailureCode.WORK_FAILED), admissionIdentity)
        }

        override fun entityManager(operation: ComplaintAdminContentOperation, jdbc: JdbcTemplate): EntityManager {
            checkEditWrite(operation, jdbc)
            return createdEntityManager ?: refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }

        override fun requireCommitted(operation: ComplaintAdminContentOperation) {
            if (!caller.isCurrent() || retained !== operation || !completed()) failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            requireSuccessfulResult()
        }

        override fun completed(): Boolean = retained?.completedFor(this@PersistencePhaseContext, path) == true && (!write || claimed)
    }

    /** Fixed ADMIN_STATUS/ADMIN_CLOSURE only; content/read capabilities cannot substitute this producer. */
    private inner class AdminStatusBoundary : PersistenceComplaintAdminStatus {
        private var issued = false
        private var retained: ComplaintAdminStatusMutation? = null
        private val admissionIdentity = Any()
        private var admission: ComplaintAdmittedAdminStatus? = null
        private var claimed = false
        private var boundsChecked = false
        private val write: Boolean get() = path === PersistencePhasePath.COMPLAINT_ADMIN_STATUS

        override fun bindRegistered(original: me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredAdminContentV1) {
            requireCaller()
            if (stage !== Stage.PREPARED || registeredAdminStatus != null || registeredAdminContent != null) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            original.requireStatusPhaseOwner(ownership)
            original.requireStatusPath(path)
            original.requireStatusIngress()
            if (write) original.requireAdmission(admission ?: refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED))
            registeredAdminStatus = original
        }

        override fun bindStatus(handoff: ComplaintAdmittedAdminStatus) {
            requireCaller()
            if (stage !== Stage.PREPARED || !write || admission != null) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            admission = handoff
            ComplaintIngressAdmission.bindAdminStatus(handoff, admissionIdentity)
        }

        override fun requireOperation(jdbc: JdbcTemplate, expected: PersistencePhasePath) {
            if (expected !in setOf(
                    PersistencePhasePath.COMPLAINT_ADMIN_STATUS_PREFLIGHT,
                    PersistencePhasePath.COMPLAINT_ADMIN_STATUS,
                )
            ) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            requireStepUpResource(jdbc, expected)
            ownership.dataSource.requireTestInitialCheckpointCreate(registeredAdminStatus?.policy)
            if (issued || (write && admission == null)) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            issued = true
            installLimits()
            requireWork()
        }

        override fun retain(operation: ComplaintAdminStatusMutation, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (!issued || retained != null || !operation.belongsTo(this@PersistencePhaseContext, path) ||
                !operation.registeredWith(registeredAdminStatus)) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            retained = operation
        }

        override fun requireRetained(operation: ComplaintAdminStatusMutation, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (retained !== operation) refuse(PersistencePhaseFailureCode.WORK_FAILED)
        }

        override fun requireRegisteredOwner(original: me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredAdminContentV1,
            jdbc: JdbcTemplate, ownership: PersistencePhaseOwnership) {
            requireStepUpResource(jdbc, PersistencePhasePath.COMPLAINT_ADMIN_READ_AUTHENTICATION)
            if (registeredAdminStatus !== original || ownership !== this@PersistencePhaseContext.ownership) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            original.requireStatusPath(path)
            original.requireStatusIngress()
        }

        override fun requireOwner(operation: ComplaintAdminStatusMutation, jdbc: JdbcTemplate, ownership: PersistencePhaseOwnership) {
            requireRetained(operation, jdbc)
            if (ownership !== this@PersistencePhaseContext.ownership) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }

        override fun connection(operation: ComplaintAdminStatusMutation, jdbc: JdbcTemplate): Connection {
            requireRetained(operation, jdbc)
            return connection ?: refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }

        override fun claimStatus(operation: ComplaintAdminStatusMutation, jdbc: JdbcTemplate, tuple: ComplaintAdminStatusTuple) {
            requireRetained(operation, jdbc)
            if (!write || claimed) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            ComplaintIngressAdmission.claimAdminStatus(admission ?: refuse(PersistencePhaseFailureCode.WORK_FAILED), admissionIdentity, tuple)
            claimed = true
        }

        override fun checkGrantWrite(operation: ComplaintAdminStatusMutation, jdbc: JdbcTemplate) {
            requireRetained(operation, jdbc)
            if (!write || !claimed || boundsChecked) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            ComplaintIngressAdmission.checkAdminStatusClaim(admission ?: refuse(PersistencePhaseFailureCode.WORK_FAILED), admissionIdentity)
        }

        override fun checkStatusBounds(operation: ComplaintAdminStatusMutation, jdbc: JdbcTemplate, ledger: ComplaintCapacityLedger) {
            requireRetained(operation, jdbc)
            if (!claimed || boundsChecked) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            ComplaintIngressAdmission.checkAdminStatusBounds(admission ?: refuse(PersistencePhaseFailureCode.WORK_FAILED), admissionIdentity, ledger)
            boundsChecked = true
        }

        override fun checkStatusWrite(operation: ComplaintAdminStatusMutation, jdbc: JdbcTemplate) {
            requireRetained(operation, jdbc)
            if (!claimed || !boundsChecked) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            ComplaintIngressAdmission.checkAdminStatusWrite(admission ?: refuse(PersistencePhaseFailureCode.WORK_FAILED), admissionIdentity)
        }

        override fun entityManager(operation: ComplaintAdminStatusMutation, jdbc: JdbcTemplate): EntityManager {
            checkStatusWrite(operation, jdbc)
            return createdEntityManager ?: refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }

        override fun requireCommitted(operation: ComplaintAdminStatusMutation) {
            if (!caller.isCurrent() || retained !== operation || !completed()) failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            requireSuccessfulResult()
        }

        override fun completed(): Boolean = retained?.completedFor(this@PersistencePhaseContext, path) == true && (!write || claimed)
    }

    /** Fixed atomic ADMIN_BATCH_STATUS only; content/read capabilities cannot substitute this producer. */
    private inner class AdminBatchStatusBoundary : PersistenceComplaintAdminBatchStatus {
        private var issued = false
        private var retained: ComplaintAdminBatchStatusMutation? = null
        private val admissionIdentity = Any()
        private var admission: ComplaintAdmittedAdminBatchStatus? = null
        private var claimed = false
        private var boundsChecked = false
        private val write: Boolean get() = path === PersistencePhasePath.COMPLAINT_ADMIN_BATCH_STATUS

        override fun bindStatus(handoff: ComplaintAdmittedAdminBatchStatus) {
            requireCaller()
            if (stage !== Stage.PREPARED || !write || admission != null) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            admission = handoff
            ComplaintIngressAdmission.bindAdminBatchStatus(handoff, admissionIdentity)
        }

        override fun requireOperation(jdbc: JdbcTemplate, expected: PersistencePhasePath) {
            if (expected !in setOf(
                    PersistencePhasePath.COMPLAINT_ADMIN_BATCH_STATUS_PREFLIGHT,
                    PersistencePhasePath.COMPLAINT_ADMIN_BATCH_STATUS,
                )
            ) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            requireStepUpResource(jdbc, expected)
            if (issued || (write && admission == null)) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            issued = true
            installLimits()
            requireWork()
        }

        override fun retain(operation: ComplaintAdminBatchStatusMutation, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (!issued || retained != null || !operation.belongsTo(this@PersistencePhaseContext, path)) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            retained = operation
        }

        override fun requireRetained(operation: ComplaintAdminBatchStatusMutation, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (retained !== operation) refuse(PersistencePhaseFailureCode.WORK_FAILED)
        }

        override fun claimStatus(operation: ComplaintAdminBatchStatusMutation, jdbc: JdbcTemplate, tuple: ComplaintAdminBatchStatusTuple) {
            requireRetained(operation, jdbc)
            if (!write || claimed) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            ComplaintIngressAdmission.claimAdminBatchStatus(admission ?: refuse(PersistencePhaseFailureCode.WORK_FAILED), admissionIdentity, tuple)
            claimed = true
        }

        override fun checkGrantWrite(operation: ComplaintAdminBatchStatusMutation, jdbc: JdbcTemplate) {
            requireRetained(operation, jdbc)
            if (!write || !claimed || boundsChecked) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            ComplaintIngressAdmission.checkAdminBatchStatusClaim(admission ?: refuse(PersistencePhaseFailureCode.WORK_FAILED), admissionIdentity)
        }

        override fun checkStatusBounds(operation: ComplaintAdminBatchStatusMutation, jdbc: JdbcTemplate, ledger: ComplaintCapacityLedger) {
            requireRetained(operation, jdbc)
            if (!claimed || boundsChecked) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            ComplaintIngressAdmission.checkAdminBatchStatusBounds(admission ?: refuse(PersistencePhaseFailureCode.WORK_FAILED), admissionIdentity, ledger)
            boundsChecked = true
        }

        override fun checkStatusWrite(operation: ComplaintAdminBatchStatusMutation, jdbc: JdbcTemplate) {
            requireRetained(operation, jdbc)
            if (!claimed || !boundsChecked) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            ComplaintIngressAdmission.checkAdminBatchStatusWrite(admission ?: refuse(PersistencePhaseFailureCode.WORK_FAILED), admissionIdentity)
        }

        override fun entityManager(operation: ComplaintAdminBatchStatusMutation, jdbc: JdbcTemplate): EntityManager {
            checkStatusWrite(operation, jdbc)
            return createdEntityManager ?: refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }

        override fun requireCommitted(operation: ComplaintAdminBatchStatusMutation) {
            if (!caller.isCurrent() || retained !== operation || !completed()) failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            requireSuccessfulResult()
        }

        override fun completed(): Boolean = retained?.completedFor(this@PersistencePhaseContext, path) == true && (!write || claimed)
    }

    /** A separate fixed edit capability. Creation's tuple, handoff and retained operation cannot enter it. */
    private inner class OwnerEditBoundary : PersistenceOwnerEdit {
        private var issued = false
        private var retained: ComplaintOwnerEditOperation? = null
        private val admissionIdentity = Any()
        private var admission: ComplaintAdmittedOwnerEdit? = null
        private var claimed = false
        private var boundsChecked = false
        private val write: Boolean get() = path === PersistencePhasePath.COMPLAINT_OWNER_EDIT

        override fun bindRegisteredInitialCheckpointEdit(original: me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredInitialCheckpointEditV1,
            context: me.manga.kira.backend.security.ComplaintIngressContext?) {
            requireCaller()
            if (stage !== Stage.PREPARED || registeredInitialCheckpointEdit != null) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            original.requirePhaseOwner(ownership)
            original.requirePath(path)
            if (write) original.requireAdmission(admission ?: refuse(PersistencePhaseFailureCode.WORK_FAILED))
            else original.requireReadAdmission(context ?: refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED))
            registeredInitialCheckpointEdit = original
        }

        override fun bindEdit(handoff: ComplaintAdmittedOwnerEdit) {
            requireCaller()
            if (stage !== Stage.PREPARED || !write || admission != null) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            admission = handoff
            ComplaintIngressAdmission.bindOwnerEdit(handoff, admissionIdentity)
        }

        override fun requireOperation(jdbc: JdbcTemplate, expected: PersistencePhasePath) {
            if (expected !in setOf(
                    PersistencePhasePath.COMPLAINT_OWNER_EDIT_AUTHENTICATION,
                    PersistencePhasePath.COMPLAINT_OWNER_EDIT_PREFLIGHT,
                    PersistencePhasePath.COMPLAINT_OWNER_EDIT_STATUS,
                    PersistencePhasePath.COMPLAINT_OWNER_EDIT,
                )
            ) {
                refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            requireStepUpResource(jdbc, expected)
            ownership.dataSource.requireTestInitialCheckpointCreate(registeredInitialCheckpointEdit?.policy)
            if (issued || (write && admission == null)) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            issued = true
            installLimits()
            requireWork()
        }

        override fun retain(operation: ComplaintOwnerEditOperation, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (!issued || retained != null || !operation.belongsTo(this@PersistencePhaseContext, path) ||
                !operation.registeredWith(registeredInitialCheckpointEdit)) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            retained = operation
        }

        override fun requireRetained(operation: ComplaintOwnerEditOperation, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (retained !== operation) refuse(PersistencePhaseFailureCode.WORK_FAILED)
        }

        override fun requireOwner(operation: ComplaintOwnerEditOperation, jdbc: JdbcTemplate, selected: PersistencePhaseOwnership) {
            requireRetained(operation, jdbc)
            if (selected !== ownership) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }

        override fun connection(operation: ComplaintOwnerEditOperation, jdbc: JdbcTemplate): Connection {
            requireRetained(operation, jdbc)
            return connection ?: refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }

        override fun claimEdit(operation: ComplaintOwnerEditOperation, jdbc: JdbcTemplate, tuple: ComplaintOwnerEditTuple) {
            requireRetained(operation, jdbc)
            if (!write || claimed) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            ComplaintIngressAdmission.claimOwnerEdit(admission ?: refuse(PersistencePhaseFailureCode.WORK_FAILED), admissionIdentity, tuple)
            claimed = true
        }

        override fun checkEditBounds(operation: ComplaintOwnerEditOperation, jdbc: JdbcTemplate, ledger: ComplaintCapacityLedger) {
            requireRetained(operation, jdbc)
            if (!claimed || boundsChecked) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            ComplaintIngressAdmission.checkOwnerEditBounds(admission ?: refuse(PersistencePhaseFailureCode.WORK_FAILED), admissionIdentity, ledger)
            boundsChecked = true
        }

        override fun checkEditWrite(operation: ComplaintOwnerEditOperation, jdbc: JdbcTemplate) {
            requireRetained(operation, jdbc)
            if (!claimed || !boundsChecked) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            ComplaintIngressAdmission.checkOwnerEditWrite(admission ?: refuse(PersistencePhaseFailureCode.WORK_FAILED), admissionIdentity)
        }

        override fun entityManager(operation: ComplaintOwnerEditOperation, jdbc: JdbcTemplate): EntityManager {
            checkEditWrite(operation, jdbc)
            return createdEntityManager ?: refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }

        override fun requireCommitted(operation: ComplaintOwnerEditOperation) {
            if (!caller.isCurrent() || retained !== operation || !completed()) failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            requireSuccessfulResult()
        }

        override fun completed(): Boolean = retained?.completedFor(this@PersistencePhaseContext, path) == true && (!write || claimed)
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
            requireInitialDeletionPoolPolicy()
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
            testRunOwnerDeleteAll?.authenticateAndControls(ownership, jdbc, operation)
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
            requireInitialDeletionPoolPolicy()
            if (issued || entityManagerFactory != null || !selectedHolder.fenceReady()) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            issued = true
            installLimits()
            requireWork()
        }

        override fun retain(operation: ComplaintOwnerDeleteAllApplyOperation, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_APPLY)
            if (!issued || retained != null || !operation.belongsTo(this@PersistencePhaseContext)) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            retained = operation
            testRunOwnerDeleteAll?.authenticateAndControls(ownership, jdbc, operation)
            testOrdinaryDrain?.authenticateRecoveryAndControls(ownership, jdbc, operation)
            testActiveQueue?.authenticateRecoveryAndControls(ownership, jdbc, operation)
            testRecurrentApply?.authenticateRecoveryAndControls(ownership, jdbc, operation)
        }

        override fun requireRetained(operation: ComplaintOwnerDeleteAllApplyOperation, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_APPLY)
            if (retained !== operation || !selectedHolder.fenceReady()) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            testActiveQueue?.requireRecoveryHolder(jdbc)
            testRecurrentApply?.requireRecoveryHolder(jdbc)
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
            if (testActiveQueue != null && !testActiveOwnerDeleteQueueCleanupProven(testActiveQueue)) failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            if (testRecurrentApply != null && !testActiveRecurrentApplyCleanupProven(testRecurrentApply)) failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
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
            requireInitialDeletionPoolPolicy()
            if (issued || entityManagerFactory != null) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            issued = true
            installLimits()
            requireWork()
        }

        override fun retain(operation: ComplaintOwnerDeleteAllVerificationOperation, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_VERIFY)
            if (!issued || retained != null || !operation.belongsTo(this@PersistencePhaseContext)) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            retained = operation
            testRunOwnerDeleteAll?.authenticateAndControls(ownership, jdbc, operation)
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

    /** Exact observation paths; only their original concrete operation can expose a physically released result. */
    private inner class AdminReadBoundary : PersistenceComplaintAdminRead {
        private var issued = false
        private var retained: ComplaintAdminReadOperation? = null

        override fun requireAuthentication(jdbc: JdbcTemplate) = requireOperation(jdbc, PersistencePhasePath.COMPLAINT_ADMIN_READ_AUTHENTICATION)
        override fun requireSearch(jdbc: JdbcTemplate) = requireOperation(jdbc, PersistencePhasePath.COMPLAINT_ADMIN_SEARCH)
        override fun requireDetail(jdbc: JdbcTemplate) = requireOperation(jdbc, PersistencePhasePath.COMPLAINT_ADMIN_DETAIL)
        override fun requireStats(jdbc: JdbcTemplate) = requireOperation(jdbc, PersistencePhasePath.COMPLAINT_ADMIN_STATS)

        private fun requireOperation(jdbc: JdbcTemplate, expected: PersistencePhasePath) {
            requireStepUpResource(jdbc, expected)
            if (issued) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            issued = true
            installLimits()
            requireWork()
        }

        override fun retain(operation: ComplaintAdminReadOperation, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (!issued || retained != null || !operation.belongsTo(this@PersistencePhaseContext, path)) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            retained = operation
        }

        override fun requireRetained(operation: ComplaintAdminReadOperation, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, path)
            if (retained !== operation) refuse(PersistencePhaseFailureCode.WORK_FAILED)
        }

        override fun connection(operation: ComplaintAdminReadOperation, jdbc: JdbcTemplate): Connection {
            requireRetained(operation, jdbc)
            return connection ?: refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }

        override fun requireCommitted(operation: ComplaintAdminReadOperation) {
            if (!caller.isCurrent() || retained !== operation || !operation.completedFor(this@PersistencePhaseContext, path)) {
                failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            }
            requireSuccessfulResult()
        }

        override fun completed(): Boolean = retained?.completedFor(this@PersistencePhaseContext, path) == true
    }

    /** One exact-ID read recognizes only this phase's retained concrete operation and actual released result. */
    private inner class OwnerDetailBoundary : PersistenceOwnerDetail {
        private var issued = false
        private var retained: ComplaintOwnerDetailReadOperation? = null

        override fun requireOperation(jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, PersistencePhasePath.COMPLAINT_OWNER_DETAIL)
            if (issued) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            issued = true
            installLimits()
            requireWork()
        }

        override fun retain(operation: ComplaintOwnerDetailReadOperation, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, PersistencePhasePath.COMPLAINT_OWNER_DETAIL)
            if (!issued || retained != null || !operation.belongsTo(this@PersistencePhaseContext)) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            retained = operation
        }

        override fun requireRetained(operation: ComplaintOwnerDetailReadOperation, jdbc: JdbcTemplate) {
            requireStepUpResource(jdbc, PersistencePhasePath.COMPLAINT_OWNER_DETAIL)
            if (retained !== operation) refuse(PersistencePhaseFailureCode.WORK_FAILED)
        }

        override fun connection(operation: ComplaintOwnerDetailReadOperation, jdbc: JdbcTemplate): Connection {
            requireRetained(operation, jdbc)
            return connection ?: refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }

        override fun requireCommitted(operation: ComplaintOwnerDetailReadOperation) {
            if (!caller.isCurrent() || retained !== operation || !operation.completedFor(this@PersistencePhaseContext)) {
                failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
            }
            requireSuccessfulResult()
        }

        override fun completed(): Boolean = retained?.completedFor(this@PersistencePhaseContext) == true
    }

    /** A concrete read, not a caller-supplied diagnostic enum, owns completion and the result-release seal. */
    private inner class InstallationCurrentStateBoundary : PersistenceInstallationCurrentState {
        private var issued = false
        private var retained: InstallationCurrentStateReadOperation? = null

        override fun requireOwner(selected: PersistencePhaseOwnership) {
            if (ownership !== selected) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }

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

        override fun requireRegistered(operation: ComplaintInstallationEnrollmentOperation, jdbc: JdbcTemplate, registration: ComplaintTestNamespaceRegistrationV1) {
            requireRetained(operation, jdbc)
            registration.requireIdentityAdmissionPhaseResources(ownership, jdbc)
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
            requireInitialDeletionPoolPolicy()
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

        override fun requireRegistered(operation: ComplaintInstallationSessionOperation, jdbc: JdbcTemplate, registration: ComplaintTestNamespaceRegistrationV1) {
            requireRetained(operation, jdbc)
            registration.requireIdentityAdmissionPhaseResources(ownership, jdbc)
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
    fun requireOwner(selected: PersistencePhaseOwnership)
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
    fun requireRegistered(operation: ComplaintInstallationEnrollmentOperation, jdbc: JdbcTemplate, registration: ComplaintTestNamespaceRegistrationV1)
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
    fun requireRegistered(operation: ComplaintInstallationSessionOperation, jdbc: JdbcTemplate, registration: ComplaintTestNamespaceRegistrationV1)
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

/** Same-phase retention and physical-release checks, never replaceable by a caller's result or callback. */
internal interface PersistenceOwnerDetail {
    fun requireOperation(jdbc: JdbcTemplate)
    fun retain(operation: ComplaintOwnerDetailReadOperation, jdbc: JdbcTemplate)
    fun requireRetained(operation: ComplaintOwnerDetailReadOperation, jdbc: JdbcTemplate)
    fun connection(operation: ComplaintOwnerDetailReadOperation, jdbc: JdbcTemplate): Connection
    fun requireCommitted(operation: ComplaintOwnerDetailReadOperation)
    fun completed(): Boolean
}

/** The exact phase owns this view and accepts only its own concrete retained SQL operation. */
internal interface PersistenceOwnerOperation {
    fun bindCreate(handoff: ComplaintAdmittedOwnerCreate)
    fun bindRegisteredInitialCheckpointCreate(original: me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredInitialCheckpointCreateV1,
        context: me.manga.kira.backend.security.ComplaintIngressContext?)
    fun requireOperation(jdbc: JdbcTemplate, expected: PersistencePhasePath)
    fun retain(operation: ComplaintOwnerCreateOperation, jdbc: JdbcTemplate)
    fun requireRetained(operation: ComplaintOwnerCreateOperation, jdbc: JdbcTemplate)
    fun requireOwner(operation: ComplaintOwnerCreateOperation, jdbc: JdbcTemplate, selected: PersistencePhaseOwnership)
    fun connection(operation: ComplaintOwnerCreateOperation, jdbc: JdbcTemplate): Connection
    fun claimCreate(operation: ComplaintOwnerCreateOperation, jdbc: JdbcTemplate, tuple: ComplaintOwnerOperationTuple)
    fun checkCreateBounds(operation: ComplaintOwnerCreateOperation, jdbc: JdbcTemplate, ledger: ComplaintCapacityLedger)
    fun checkCreateWrite(operation: ComplaintOwnerCreateOperation, jdbc: JdbcTemplate)
    fun entityManager(operation: ComplaintOwnerCreateOperation, jdbc: JdbcTemplate): EntityManager
    fun requireCommitted(operation: ComplaintOwnerCreateOperation)
    fun completed(): Boolean
}

/** This view cannot grant resources without its own concrete retained edit operation and exact one-use handoff. */
internal interface PersistenceOwnerEdit {
    fun bindRegisteredInitialCheckpointEdit(original: me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredInitialCheckpointEditV1,
        context: me.manga.kira.backend.security.ComplaintIngressContext?)
    fun requireOwner(operation: ComplaintOwnerEditOperation, jdbc: JdbcTemplate, selected: PersistencePhaseOwnership)
    fun connection(operation: ComplaintOwnerEditOperation, jdbc: JdbcTemplate): Connection
    fun bindEdit(handoff: ComplaintAdmittedOwnerEdit)
    fun requireOperation(jdbc: JdbcTemplate, expected: PersistencePhasePath)
    fun retain(operation: ComplaintOwnerEditOperation, jdbc: JdbcTemplate)
    fun requireRetained(operation: ComplaintOwnerEditOperation, jdbc: JdbcTemplate)
    fun claimEdit(operation: ComplaintOwnerEditOperation, jdbc: JdbcTemplate, tuple: ComplaintOwnerEditTuple)
    fun checkEditBounds(operation: ComplaintOwnerEditOperation, jdbc: JdbcTemplate, ledger: ComplaintCapacityLedger)
    fun checkEditWrite(operation: ComplaintOwnerEditOperation, jdbc: JdbcTemplate)
    fun entityManager(operation: ComplaintOwnerEditOperation, jdbc: JdbcTemplate): EntityManager
    fun requireCommitted(operation: ComplaintOwnerEditOperation)
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

/** Only the fixed private single-delete producers implement the retained operation family. */
internal interface PersistenceOwnerDelete {
    fun bindAuthorize(handoff: ComplaintAdmittedOwnerDelete)
    fun requireOperation(jdbc: JdbcTemplate, expected: PersistencePhasePath)
    fun retain(operation: ComplaintOwnerDeletePhaseOperation, jdbc: JdbcTemplate)
    fun requireRetained(operation: ComplaintOwnerDeletePhaseOperation, jdbc: JdbcTemplate)
    fun claim(operation: ComplaintOwnerDeletePhaseOperation, jdbc: JdbcTemplate, tuple: ComplaintOwnerDeleteTuple)
    fun checkReceiptWrite(operation: ComplaintOwnerDeletePhaseOperation, jdbc: JdbcTemplate)
    fun checkCapacity(operation: ComplaintOwnerDeletePhaseOperation, jdbc: JdbcTemplate, ledger: ComplaintCapacityLedger)
    fun checkWrite(operation: ComplaintOwnerDeletePhaseOperation, jdbc: JdbcTemplate)
    fun connection(operation: ComplaintOwnerDeletePhaseOperation, jdbc: JdbcTemplate): Connection
    fun requireCommitted(operation: ComplaintOwnerDeletePhaseOperation)
    fun completed(): Boolean
}

internal interface PersistenceOwnerDeleteRead {
    fun requireOperation(jdbc: JdbcTemplate, expected: PersistencePhasePath)
    fun retain(operation: ComplaintOwnerDeleteReadOperation, jdbc: JdbcTemplate)
    fun requireRetained(operation: ComplaintOwnerDeleteReadOperation, jdbc: JdbcTemplate)
    fun requireCommitted(operation: ComplaintOwnerDeleteReadOperation)
    fun completed(): Boolean
}

internal interface PersistenceAdminDelete {
    fun bindAuthorize(handoff: ComplaintAdmittedAdminErasure)
    fun requireOperation(jdbc: JdbcTemplate, expected: PersistencePhasePath)
    fun retain(operation: ComplaintAdminDeletePhaseOperation, jdbc: JdbcTemplate)
    fun requireRetained(operation: ComplaintAdminDeletePhaseOperation, jdbc: JdbcTemplate)
    fun claim(operation: ComplaintAdminDeletePhaseOperation, jdbc: JdbcTemplate, tuple: ComplaintAdminDeleteTuple)
    fun checkReceiptWrite(operation: ComplaintAdminDeletePhaseOperation, jdbc: JdbcTemplate)
    fun checkCapacity(operation: ComplaintAdminDeletePhaseOperation, jdbc: JdbcTemplate, ledger: ComplaintCapacityLedger)
    fun checkWrite(operation: ComplaintAdminDeletePhaseOperation, jdbc: JdbcTemplate)
    fun connection(operation: ComplaintAdminDeletePhaseOperation, jdbc: JdbcTemplate): Connection
    fun requireCommitted(operation: ComplaintAdminDeletePhaseOperation)
    fun completed(): Boolean
}


internal interface PersistenceAdminDeleteRead {
    fun requireOperation(jdbc: JdbcTemplate, expected: PersistencePhasePath)
    fun retain(operation: ComplaintAdminDeleteReadOperation, jdbc: JdbcTemplate)
    fun requireRetained(operation: ComplaintAdminDeleteReadOperation, jdbc: JdbcTemplate)
    fun requireCommitted(operation: ComplaintAdminDeleteReadOperation)
    fun completed(): Boolean
}
