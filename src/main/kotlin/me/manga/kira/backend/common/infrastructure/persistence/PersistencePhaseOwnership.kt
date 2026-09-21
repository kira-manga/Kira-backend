package me.manga.kira.backend.common.infrastructure.persistence

import jakarta.persistence.EntityManagerFactory
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.InstallationDeletionPreflightTuple
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintDesiredInstallAttemptV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintSignedGenesisFirstDAttemptV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationAttemptV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestInitialAdmissionV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRecoveryRegistrationAttemptV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunSealingV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOrdinarySealV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunInstallationManifestV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunInstallationManifestPublicationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunPurgePublicationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOrdinaryDrainV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOwnerDeleteContinuationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOwnerDeleteAllContinuationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunAdminDeleteContinuationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogCutoffAttemptV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogEpochRotationAttemptV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisFinalizeAttemptV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisFreezeAttemptV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisPublishAttemptV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogReadbackRefreshCustodyV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationActivationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationDeliveryV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationFreezeAttemptV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationInitialAuthorV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationPreparedRecoveryV1
import me.manga.kira.backend.complaint.infrastructure.transaction.DeletionPersistenceAdmission
import me.manga.kira.backend.security.ComplaintAdmittedAdminErasure
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteTuple
import me.manga.kira.backend.security.ComplaintAdmittedOwnerDelete
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteTuple
import me.manga.kira.backend.security.ComplaintAdmittedOwnerDeleteAll
import me.manga.kira.backend.security.ComplaintIngressAdmission
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID
import java.util.concurrent.atomic.AtomicReferenceArray
import java.util.concurrent.locks.ReentrantLock

/**
 * Dormant closed resource composition of existing permit budgets, never another pool/physical owner.
 * Explicit named entries intentionally share this one selection/permit owner, not a caller-selected path API.
 */
@Suppress("TooManyFunctions")
internal class PersistencePhaseOwnership private constructor(
    private val selection: Selection,
    internal val nanoClock: PersistenceNanoClock = SystemPersistenceNanoClock,
) {
    constructor(
        admission: OrdinaryPersistenceAdmission,
        manager: GuardedJpaTransactionManager,
        otherManager: GuardedJdbcTransactionManager? = null,
        nanoClock: PersistenceNanoClock = SystemPersistenceNanoClock,
    ) : this(Selection.Ordinary(admission, manager, otherManager), nanoClock)

    internal val manager: PlatformTransactionManager get() = selection.manager
    internal val dataSource: GuardedDataSource get() = selection.dataSource
    internal val entityManagerFactory: EntityManagerFactory? get() = (selection as? Selection.Ordinary)?.manager?.entityManagerFactory
    internal val otherManager: GuardedJdbcTransactionManager? get() = (selection as? Selection.Ordinary)?.otherManager
    internal val installationSessionIdentity = Any() // Bounded continuation identity, not a retained phase/resource or admission grant.
    internal val installationDeletionIdentity = Any() // Same-owner read-only comparisons, never deletion admission or writer authority.

    /** Fixed supported process profile only; the same pool cannot hide source-only or differently sized admission. */
    internal fun requireBoundComplaintComposition(pools: VersionBoundPersistencePools, deletion: PersistencePhaseOwnership) {
        requireBoundComplaintOrdinary(pools)
        if (deletion.selection !is Selection.Deletion || deletion.dataSource !== pools.deletion) {
            throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
    }

    internal fun requireBoundComplaintOrdinary(pools: VersionBoundPersistencePools) {
        val ordinary = selection as? Selection.Ordinary ?: throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        val size = pools.descriptors().single { it.role === PersistenceJdbcParticipantRole.ORDINARY }.hikari.sizing.maximumPoolSize
        if (dataSource !== pools.ordinary || !ordinary.matchesComplaintPool(size)) {
            throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
    }

    internal fun requireBoundComplaintDeletion(pools: VersionBoundPersistencePools) {
        if (selection !is Selection.Deletion || dataSource !== pools.deletion) {
            throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
        selection.requireResources()
    }
    private val admissionCut = ReentrantLock()

    // Exactly the existing bounded permits. Resolved slots are removed, never kept as a history.
    private val phases = AtomicReferenceArray<PersistencePhaseContext?>(selection.ownerLimit)

    init {
        selection.requireResources()
        selection.bind(this)
    }

    internal fun enterTestNamespaceRegistration(original: ComplaintTestNamespaceRegistrationAttemptV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_TEST_NAMESPACE_REGISTRATION, testRegistration = original)

    internal fun enterTestInitialAdmission(original: ComplaintTestInitialAdmissionV1): PersistencePhaseContext =
        enter(original.path, testInitialAdmission = original)

    internal fun enterTestNamespaceRecoveryRegistration(original: ComplaintTestNamespaceRecoveryRegistrationAttemptV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_TEST_NAMESPACE_RECOVERY_REGISTRATION, testRecoveryRegistration = original)

    internal fun enterTestRunSeal(original: TestRunSealingV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_TEST_RUN_SEAL, testRunSealer = original)

    internal fun enterTestRunSealedAudit(original: TestRunSealingV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_TEST_RUN_SEALED_AUDIT, testRunSealer = original)

    internal fun enterTestInstallationManifest(original: TestRunInstallationManifestV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_TEST_INSTALLATION_MANIFEST_PREPARE, testInstallationManifest = original)

    internal fun enterTestInstallationManifestPublication(original: TestRunInstallationManifestPublicationV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_TEST_INSTALLATION_MANIFEST_PUBLICATION, testInstallationManifestPublication = original)

    internal fun enterTestInstallationManifestVerify(original: TestRunInstallationManifestPublicationV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_TEST_INSTALLATION_MANIFEST_VERIFY, testInstallationManifestPublication = original)

    internal fun enterTestRunPurge(original: TestRunPurgePublicationV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_TEST_RUN_PURGE_PUBLICATION, testRunPurge = original)

    internal fun enterTestRunPurgeVerify(original: TestRunPurgePublicationV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_TEST_RUN_PURGE_VERIFY, testRunPurge = original)

    internal fun enterTestOrdinarySeal(original: TestRunOrdinarySealV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_TEST_ORDINARY_SEAL, testOrdinarySealer = original)

    internal fun enterTestOrdinaryDrain(original: TestRunOrdinaryDrainV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_TEST_ORDINARY_DRAIN, testOrdinaryDrain = original)

    internal fun enterTestOrdinaryInventoryRecovery(original: TestRunOrdinaryDrainV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_OWNER_DELETE_APPLY, testOrdinaryDrain = original)

    internal fun enterTestOrdinaryAllInventoryRecovery(original: TestRunOrdinaryDrainV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_APPLY, testOrdinaryDrain = original)

    internal fun enterTestRunOwnerDeleteReload(original: TestRunOwnerDeleteContinuationV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_OWNER_DELETE_RELOAD, testRunOwnerDelete = original)

    internal fun enterTestRunOwnerDeleteVerify(original: TestRunOwnerDeleteContinuationV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_OWNER_DELETE_VERIFY, testRunOwnerDelete = original)

    internal fun enterTestRunOwnerDeleteApply(original: TestRunOwnerDeleteContinuationV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_OWNER_DELETE_APPLY, testRunOwnerDelete = original)

    internal fun enterTestRunOwnerDeleteAllReload(original: TestRunOwnerDeleteAllContinuationV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_RELOAD, testRunOwnerDeleteAll = original)

    internal fun enterTestRunOwnerDeleteAllVerify(original: TestRunOwnerDeleteAllContinuationV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_VERIFY, testRunOwnerDeleteAll = original)

    internal fun enterTestRunOwnerDeleteAllApply(original: TestRunOwnerDeleteAllContinuationV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_APPLY, testRunOwnerDeleteAll = original)

    internal fun enterTestRunAdminDeleteReload(original: TestRunAdminDeleteContinuationV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_ADMIN_DELETE_RELOAD, testRunAdminDelete = original)

    internal fun enterTestRunAdminDeleteVerify(original: TestRunAdminDeleteContinuationV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_ADMIN_DELETE_VERIFY, testRunAdminDelete = original)

    internal fun enterTestRunAdminDeleteApply(original: TestRunAdminDeleteContinuationV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_ADMIN_DELETE_APPLY, testRunAdminDelete = original)

    internal fun enterTestOrdinaryAdminDeleteRecovery(original: TestRunOrdinaryDrainV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_ADMIN_DELETE_APPLY, testOrdinaryDrain = original)

    internal fun enterSourceGrantCleanup(): PersistencePhaseContext = enter(PersistencePhasePath.SOURCE_GRANT_CLEANUP)

    internal fun enterComplaintGrantCleanup(): PersistencePhaseContext = enter(PersistencePhasePath.COMPLAINT_GRANT_CLEANUP)

    internal fun enterSourceStepUpSnapshot(): PersistencePhaseContext = enter(PersistencePhasePath.SOURCE_STEP_UP_SNAPSHOT)

    internal fun enterComplaintStepUpSnapshot(): PersistencePhaseContext = enter(PersistencePhasePath.COMPLAINT_STEP_UP_SNAPSHOT)

    internal fun enterSourceStepUpIssuance(): PersistencePhaseContext = enter(PersistencePhasePath.SOURCE_STEP_UP_ISSUANCE)

    internal fun enterComplaintStepUpIssuance(): PersistencePhaseContext = enter(PersistencePhasePath.COMPLAINT_STEP_UP_ISSUANCE)

    /** Infrastructure composition only; no production receipt, abuse-admission or domain writer is provided here. */
    internal fun enterComplaintAdminAudit(): PersistencePhaseContext = enter(PersistencePhasePath.COMPLAINT_ADMIN_AUDIT)

    /** Existing deletion holder only; receipt-before-grant and W04 domain authority remain unavailable. */
    internal fun enterComplaintDeletionAdminAudit(): PersistencePhaseContext = enter(PersistencePhasePath.COMPLAINT_DELETION_ADMIN_AUDIT)

    /** Dormant accounting composition only; no production policy/event/unused-work authority. */
    internal fun enterComplaintRecoverySettlement(): PersistencePhaseContext = enter(PersistencePhasePath.COMPLAINT_RECOVERY_SETTLEMENT)

    /** Dormant ACTIVE-run spending only; no production allocation or terminal/catalog authority. */
    internal fun enterComplaintTestReserveSpend(): PersistencePhaseContext = enter(PersistencePhasePath.COMPLAINT_TEST_RESERVE_SPEND)

    /** Dormant LIVE-only paired enrollment; authenticated runtime mode/configuration remains separate. */
    internal fun enterComplaintInstallationEnrollment(): PersistencePhaseContext = enter(PersistencePhasePath.COMPLAINT_INSTALLATION_ENROLLMENT)

    /** Separate dormant comparison/refresh phases. No request admission is inferred between them. */
    internal fun enterComplaintInstallationSessionPreflight(): PersistencePhaseContext = enter(PersistencePhasePath.COMPLAINT_INSTALLATION_SESSION_PREFLIGHT)

    internal fun enterComplaintInstallationSessionRefresh(): PersistencePhaseContext = enter(PersistencePhasePath.COMPLAINT_INSTALLATION_SESSION_REFRESH)

    /** Receipt-first read only: no deletion bulkhead/fence and no authority to refresh or publish. */
    internal fun enterComplaintInstallationDeletionPreflight(): PersistencePhaseContext = enter(PersistencePhasePath.COMPLAINT_INSTALLATION_DELETION_PREFLIGHT)

    /** Actual semantic admission precedes even the privacy permit; this remains dormant and grants no runtime capability. */
    internal fun enterComplaintOwnerDeleteAllAuthorize(
        admission: ComplaintAdmittedOwnerDeleteAll,
        tuple: InstallationDeletionPreflightTuple,
    ): PersistencePhaseContext {
        ComplaintIngressAdmission.requireOwnerDeleteAllEntry(admission, tuple)
        return enter(PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_AUTHORIZE)
    }

    internal fun enterComplaintOwnerDeleteAllReload(): PersistencePhaseContext = enter(PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_RELOAD)

    /** Private publisher readback persistence only: receipt -> publication, deliberately no epoch fence. */
    internal fun enterComplaintOwnerDeleteAllVerify(): PersistencePhaseContext = enter(PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_VERIFY)

    /** Fixed erasure of a privately verified continuation, not another authorization/admission attempt. */
    internal fun enterComplaintOwnerDeleteAllApply(): PersistencePhaseContext = enter(PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_APPLY)

    /** Read-only diagnostics, never current-mode, catalog, restore or TEST admission authority. */
    internal fun enterComplaintInstallationCurrentState(): PersistencePhaseContext = enter(PersistencePhasePath.COMPLAINT_INSTALLATION_CURRENT_STATE)

    /** Dormant TEST-only token-state and owner-page reads; neither entry supplies enabled-mode authority. */
    internal fun enterComplaintOwnerHistoryAuthentication(): PersistencePhaseContext = enter(PersistencePhasePath.COMPLAINT_OWNER_HISTORY_AUTHENTICATION)

    internal fun enterComplaintOwnerHistoryPage(): PersistencePhaseContext = enter(PersistencePhasePath.COMPLAINT_OWNER_HISTORY_PAGE)

    /** Exact-ID owner detail, using the same ordinary read budget and original resource owner. */
    internal fun enterComplaintOwnerDetail(): PersistencePhaseContext = enter(PersistencePhasePath.COMPLAINT_OWNER_DETAIL)

    /** Current ADMIN and bounded TEST-only reads. Observations grant no mode, maintenance or projection authority. */
    internal fun enterComplaintAdminReadAuthentication(): PersistencePhaseContext = enter(PersistencePhasePath.COMPLAINT_ADMIN_READ_AUTHENTICATION)

    internal fun enterComplaintAdminSearch(): PersistencePhaseContext = enter(PersistencePhasePath.COMPLAINT_ADMIN_SEARCH)

    internal fun enterComplaintAdminDetail(): PersistencePhaseContext = enter(PersistencePhasePath.COMPLAINT_ADMIN_DETAIL)

    internal fun enterComplaintAdminStats(): PersistencePhaseContext = enter(PersistencePhasePath.COMPLAINT_ADMIN_STATS)

    internal fun enterComplaintAdminEditPreflight(): PersistencePhaseContext = enter(PersistencePhasePath.COMPLAINT_ADMIN_EDIT_PREFLIGHT)

    internal fun enterComplaintAdminEdit(): PersistencePhaseContext = enter(PersistencePhasePath.COMPLAINT_ADMIN_EDIT)

    internal fun enterComplaintAdminStatusPreflight(): PersistencePhaseContext = enter(PersistencePhasePath.COMPLAINT_ADMIN_STATUS_PREFLIGHT)

    internal fun enterComplaintAdminStatus(): PersistencePhaseContext = enter(PersistencePhasePath.COMPLAINT_ADMIN_STATUS)

    internal fun enterComplaintAdminBatchStatusPreflight(): PersistencePhaseContext = enter(PersistencePhasePath.COMPLAINT_ADMIN_BATCH_STATUS_PREFLIGHT)

    internal fun enterComplaintAdminBatchStatus(): PersistencePhaseContext = enter(PersistencePhasePath.COMPLAINT_ADMIN_BATCH_STATUS)

    /** TEST-dormant create/status share only the existing ordinary owner; no activation is inferred. */
    internal fun enterComplaintOwnerOperationAuthentication(): PersistencePhaseContext = enter(PersistencePhasePath.COMPLAINT_OWNER_OPERATION_AUTHENTICATION)

    internal fun enterComplaintOwnerCreatePreflight(): PersistencePhaseContext = enter(PersistencePhasePath.COMPLAINT_OWNER_CREATE_PREFLIGHT)

    internal fun enterComplaintOwnerCreate(): PersistencePhaseContext = enter(PersistencePhasePath.COMPLAINT_OWNER_CREATE)

    internal fun enterComplaintOwnerReplyPreflight(): PersistencePhaseContext = enter(PersistencePhasePath.COMPLAINT_OWNER_REPLY_PREFLIGHT)

    internal fun enterComplaintOwnerReply(): PersistencePhaseContext = enter(PersistencePhasePath.COMPLAINT_OWNER_REPLY)

    internal fun enterComplaintOwnerOperationStatus(): PersistencePhaseContext = enter(PersistencePhasePath.COMPLAINT_OWNER_OPERATION_STATUS)

    internal fun enterComplaintAdminDeletePreflight(): PersistencePhaseContext = enter(PersistencePhasePath.COMPLAINT_ADMIN_DELETE_PREFLIGHT)
    internal fun enterComplaintAdminDeleteAuthorize(admission: ComplaintAdmittedAdminErasure, tuple: ComplaintAdminDeleteTuple): PersistencePhaseContext {
        ComplaintIngressAdmission.requireAdminErasureEntry(admission, tuple)
        return enter(PersistencePhasePath.COMPLAINT_ADMIN_DELETE_AUTHORIZE)
    }
    internal fun enterComplaintAdminDeleteReload(): PersistencePhaseContext = enter(PersistencePhasePath.COMPLAINT_ADMIN_DELETE_RELOAD)
    internal fun enterComplaintAdminDeleteVerify(): PersistencePhaseContext = enter(PersistencePhasePath.COMPLAINT_ADMIN_DELETE_VERIFY)
    internal fun enterComplaintAdminDeleteApply(): PersistencePhaseContext = enter(PersistencePhasePath.COMPLAINT_ADMIN_DELETE_APPLY)

    internal fun enterComplaintOwnerDeleteAuthentication(): PersistencePhaseContext = enter(PersistencePhasePath.COMPLAINT_OWNER_DELETE_AUTHENTICATION)
    internal fun enterComplaintOwnerDeletePreflight(): PersistencePhaseContext = enter(PersistencePhasePath.COMPLAINT_OWNER_DELETE_PREFLIGHT)
    internal fun enterComplaintOwnerDeleteStatus(): PersistencePhaseContext = enter(PersistencePhasePath.COMPLAINT_OWNER_DELETE_STATUS)
    internal fun enterComplaintOwnerDeleteAuthorize(admission: ComplaintAdmittedOwnerDelete, tuple: ComplaintOwnerDeleteTuple): PersistencePhaseContext {
        ComplaintIngressAdmission.requireOwnerDeleteEntry(admission, tuple)
        return enter(PersistencePhasePath.COMPLAINT_OWNER_DELETE_AUTHORIZE)
    }
    internal fun enterComplaintOwnerDeleteReload(): PersistencePhaseContext = enter(PersistencePhasePath.COMPLAINT_OWNER_DELETE_RELOAD)
    internal fun enterComplaintOwnerDeleteVerify(): PersistencePhaseContext = enter(PersistencePhasePath.COMPLAINT_OWNER_DELETE_VERIFY)
    internal fun enterComplaintOwnerDeleteApply(): PersistencePhaseContext = enter(PersistencePhasePath.COMPLAINT_OWNER_DELETE_APPLY)

    internal fun enterComplaintOwnerEditAuthentication(): PersistencePhaseContext = enter(PersistencePhasePath.COMPLAINT_OWNER_EDIT_AUTHENTICATION)

    internal fun enterComplaintOwnerEditPreflight(): PersistencePhaseContext = enter(PersistencePhasePath.COMPLAINT_OWNER_EDIT_PREFLIGHT)

    internal fun enterComplaintOwnerEditStatus(): PersistencePhaseContext = enter(PersistencePhasePath.COMPLAINT_OWNER_EDIT_STATUS)

    internal fun enterComplaintOwnerEdit(): PersistencePhaseContext = enter(PersistencePhasePath.COMPLAINT_OWNER_EDIT)

    /** Lower dormant mutation composition only; W04 fence/control/publication and provenance authority are unavailable. */
    internal fun enterComplaintDeletionMutation(): PersistencePhaseContext = enter(PersistencePhasePath.COMPLAINT_DELETION_MUTATION)

    /** Fence-only dormant prefix; it supplies no controls, receipt, writer authority or domain operation. */
    internal fun enterComplaintDeletionFencePrefix(): PersistencePhaseContext = enter(PersistencePhasePath.COMPLAINT_DELETION_FENCE_PREFIX)

    /** Locked observations only. Syntactic scope is not run membership or authenticated writer/catalog authority. */
    internal fun enterComplaintDeletionControlSnapshot(scope: ComplaintDataScope): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_DELETION_CONTROL_SNAPSHOT, scope)

    /** One read-only coordinator phase. Its observations confer neither catalog mutation nor complaint admission. */
    internal fun enterComplaintCatalogSnapshot(): PersistencePhaseContext = enter(PersistencePhasePath.COMPLAINT_CATALOG_SNAPSHOT)

    internal fun enterComplaintCatalogSnapshot(attempt: CatalogReadbackRefreshCustodyV1.Attempt): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_CATALOG_SNAPSHOT, catalogRefresh = attempt)

    internal fun enterComplaintCatalogSnapshot(attempt: CatalogGenesisFreezeAttemptV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_CATALOG_SNAPSHOT, catalogAuthorAttempt = attempt)

    internal fun enterComplaintCatalogSnapshot(attempt: CatalogGenesisFinalizeAttemptV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_CATALOG_SNAPSHOT, catalogFinalizerAttempt = attempt)

    internal fun enterComplaintCatalogSnapshot(original: CatalogSignerRotationPreparedRecoveryV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_CATALOG_SNAPSHOT, signerRotationRecovery = original)

    internal fun enterComplaintCatalogSnapshot(original: CatalogSignerRotationInitialAuthorV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_CATALOG_SNAPSHOT, signerRotationAuthor = original)

    internal fun enterComplaintSignerRotationAuthorAcquire(original: CatalogSignerRotationInitialAuthorV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_ACQUIRE, signerRotationAuthor = original)

    internal fun enterComplaintCatalogGenesisComplete(original: CatalogSignerRotationInitialAuthorV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_COMPLETE, signerRotationAuthor = original)

    internal fun enterComplaintCatalogGenesisProject(original: CatalogSignerRotationInitialAuthorV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PROJECT, signerRotationAuthor = original)

    internal fun enterComplaintSignerRotationRecoveryAcquire(original: CatalogSignerRotationPreparedRecoveryV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_ACQUIRE, signerRotationRecovery = original)

    internal fun enterComplaintCatalogGenesisComplete(attempt: CatalogGenesisFinalizeAttemptV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_COMPLETE, catalogFinalizerAttempt = attempt)

    internal fun enterComplaintCatalogGenesisProject(attempt: CatalogGenesisFinalizeAttemptV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PROJECT, catalogFinalizerAttempt = attempt)

    internal fun enterComplaintCatalogProjectedHead(attempt: CatalogReadbackRefreshCustodyV1.Attempt): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_CATALOG_PROJECTED_HEAD, catalogRefresh = attempt)

    /** Dedicated fenced G1 write phases, sharing the snapshot coordinator's one existing slot. */
    internal fun enterComplaintCatalogGenesisPrepare(): PersistencePhaseContext = enter(PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PREPARE)

    internal fun enterComplaintCatalogGenesisSignature(): PersistencePhaseContext = enter(PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_SIGNATURE)

    internal fun enterComplaintCatalogGenesisPrepare(attempt: CatalogGenesisFreezeAttemptV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PREPARE, catalogAuthorAttempt = attempt)

    internal fun enterComplaintCatalogGenesisSignature(attempt: CatalogGenesisFreezeAttemptV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_SIGNATURE, catalogAuthorAttempt = attempt)

    internal fun enterComplaintCatalogGenesisComplete(): PersistencePhaseContext = enter(PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_COMPLETE)

    internal fun enterComplaintCatalogGenesisProject(): PersistencePhaseContext = enter(PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PROJECT)

    /** Fixed control-row lease transitions only, deliberately without the G1/deletion epoch fence. */
    internal fun enterComplaintCoordinatorLeaseAcquire(): PersistencePhaseContext = enter(PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_ACQUIRE)

    internal fun enterComplaintCoordinatorLeaseRenew(): PersistencePhaseContext = enter(PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_RENEW)

    internal fun enterComplaintCoordinatorLeaseRelinquish(): PersistencePhaseContext = enter(PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_RELINQUISH)

    internal fun enterComplaintEpochRotationRequest(attempt: CatalogEpochRotationAttemptV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_EPOCH_ROTATION_REQUEST, rotationAttempt = attempt)

    internal fun enterComplaintEpochRotationResume(attempt: CatalogEpochRotationAttemptV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_EPOCH_ROTATION_RESUME, rotationAttempt = attempt)

    internal fun enterComplaintCutoffControl(attempt: CatalogCutoffAttemptV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_CUTOFF_CONTROL, cutoffAttempt = attempt)

    internal fun enterComplaintCutoffPage(attempt: CatalogCutoffAttemptV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_CUTOFF_PAGE, cutoffAttempt = attempt)

    internal fun enterComplaintCutoffVerify(attempt: CatalogCutoffAttemptV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_CUTOFF_VERIFY, cutoffAttempt = attempt)

    /** Canonical-only seal bookkeeping on the original coordinator, with the original resolver attempt/budget. */
    internal fun enterComplaintSealPrepare(attempt: CatalogCutoffAttemptV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_SEAL_PREPARE, cutoffAttempt = attempt)

    /** Actual locked renewal on the SAME lease path, bounded by this original seal attempt. */
    internal fun enterComplaintCutoffRenew(attempt: CatalogCutoffAttemptV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_RENEW, cutoffAttempt = attempt)

    /** Fixed authenticated operator lane only. All three caps retain the same original command deadline. */
    internal fun enterComplaintDesiredBootstrap(attempt: ComplaintDesiredInstallAttemptV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_DESIRED_BOOTSTRAP, desiredAttempt = attempt)

    internal fun enterComplaintDesiredClose(attempt: ComplaintDesiredInstallAttemptV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_DESIRED_CLOSE, desiredAttempt = attempt)

    internal fun enterComplaintDesiredSupersede(attempt: ComplaintDesiredInstallAttemptV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_DESIRED_SUPERSEDE, desiredAttempt = attempt)

    /** Separately fenced signed-PREPARED first-D route; the pristine/control-only entries remain unchanged. */
    internal fun enterComplaintDesiredSignedGenesisFirst(attempt: ComplaintSignedGenesisFirstDAttemptV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_DESIRED_SIGNED_GENESIS_FIRST, firstDesiredAttempt = attempt)

    /** Current selected-G1 recheck on the fixed operator only; no NULL-D transition or portable earlier selection result. */
    internal fun enterComplaintCatalogGenesisPublishRecheck(attempt: CatalogGenesisPublishAttemptV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PUBLISH_RECHECK, catalogPublisherAttempt = attempt)

    /** Fixed first-overlap phases on the ordinary runtime coordinator, never on the G1 author/finalizer/operator. */
    internal fun enterComplaintCatalogSignerRotationRead(attempt: CatalogSignerRotationFreezeAttemptV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_READ, catalogSignerRotationAttempt = attempt)

    internal fun enterComplaintCatalogSignerRotationPrepare(attempt: CatalogSignerRotationFreezeAttemptV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_PREPARE, catalogSignerRotationAttempt = attempt)

    internal fun enterComplaintCatalogSignerRotationSignature(attempt: CatalogSignerRotationFreezeAttemptV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_SIGNATURE, catalogSignerRotationAttempt = attempt)

    /** Concrete fixed-overlap delivery only; no caller-selected path or FreezeAttempt substitution. */
    internal fun enterComplaintCatalogSnapshot(original: CatalogSignerRotationDeliveryV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_CATALOG_SNAPSHOT, signerRotationDelivery = original)

    internal fun enterComplaintSignerRotationDeliveryAcquire(original: CatalogSignerRotationDeliveryV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_ACQUIRE, signerRotationDelivery = original)

    internal fun enterComplaintSignerRotationFinalRead(original: CatalogSignerRotationDeliveryV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_FINAL_READ, signerRotationDelivery = original)

    internal fun enterComplaintSignerRotationComplete(original: CatalogSignerRotationDeliveryV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_COMPLETE, signerRotationDelivery = original)

    internal fun enterComplaintSignerRotationProject(original: CatalogSignerRotationDeliveryV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_PROJECT, signerRotationDelivery = original)

    /** Fixed activation3 owner only; no caller-selected phase or overlap-owner substitution. */
    internal fun enterComplaintCatalogSnapshot(original: CatalogSignerRotationActivationV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_CATALOG_SNAPSHOT, signerRotationActivation = original)

    internal fun enterComplaintSignerRotationActivationAcquire(original: CatalogSignerRotationActivationV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_ACQUIRE, signerRotationActivation = original)

    internal fun enterComplaintSignerRotationActivationRead(original: CatalogSignerRotationActivationV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_READ, signerRotationActivation = original)

    internal fun enterComplaintSignerRotationActivationPrepare(original: CatalogSignerRotationActivationV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_PREPARE, signerRotationActivation = original)

    internal fun enterComplaintSignerRotationActivationSignature(original: CatalogSignerRotationActivationV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_SIGNATURE, signerRotationActivation = original)

    internal fun enterComplaintSignerRotationActivationComplete(original: CatalogSignerRotationActivationV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_COMPLETE, signerRotationActivation = original)

    internal fun enterComplaintSignerRotationActivationProject(original: CatalogSignerRotationActivationV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_PROJECT, signerRotationActivation = original)

    /** Concrete cold TEST owner only; no caller-selected phase, ordinary root or LIVE substitution. */
    internal fun enterComplaintTestRunActivationSnapshot(original: CatalogTestRunActivationV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_SNAPSHOT, testRunActivation = original)

    internal fun enterComplaintTestRunActivationAcquire(original: CatalogTestRunActivationV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_LEASE_ACQUIRE, testRunActivation = original)

    internal fun enterComplaintTestRunActivationPrepare(original: CatalogTestRunActivationV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_PREPARE, testRunActivation = original)

    internal fun enterComplaintTestRunActivationReload(original: CatalogTestRunActivationV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_PREPARED_RELOAD, testRunActivation = original)

    internal fun enterComplaintTestRunActivationSignature(original: CatalogTestRunActivationV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_SIGNATURE, testRunActivation = original)

    internal fun enterComplaintTestRunActivationSignedReload(original: CatalogTestRunActivationV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_SIGNED_RELOAD, testRunActivation = original)

    internal fun enterComplaintTestRunActivationDeliveryReload(original: CatalogTestRunActivationV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_DELIVERY_RELOAD, testRunActivation = original)

    internal fun enterComplaintTestRunActivationComplete(original: CatalogTestRunActivationV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_COMPLETE, testRunActivation = original)

    internal fun enterComplaintTestRunActivationPendingReload(original: CatalogTestRunActivationV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_PENDING_RELOAD, testRunActivation = original)

    internal fun enterComplaintTestRunActivationProjectReload(original: CatalogTestRunActivationV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_PROJECT_RELOAD, testRunActivation = original)

    internal fun enterComplaintTestRunActivationProject(original: CatalogTestRunActivationV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_PROJECT, testRunActivation = original)

    internal fun enterComplaintTestRunActivationProjectedReload(original: CatalogTestRunActivationV1): PersistencePhaseContext =
        enter(PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_PROJECTED_RELOAD, testRunActivation = original)

    // Refusals precede their own side effects; catch every entry failure to settle only unused custody and retain bounded reasons.
    // Keep ordered admission/custody/publication in one entry; concrete owner parameters must not become an interchangeable capability bag.
    @Suppress("ThrowsCount", "TooGenericExceptionCaught", "LongMethod", "LongParameterList")
    private fun enter(
        path: PersistencePhasePath,
        deletionScope: ComplaintDataScope? = null,
        rotationAttempt: CatalogEpochRotationAttemptV1? = null,
        cutoffAttempt: CatalogCutoffAttemptV1? = null,
        catalogRefresh: CatalogReadbackRefreshCustodyV1.Attempt? = null,
        desiredAttempt: ComplaintDesiredInstallAttemptV1? = null,
        firstDesiredAttempt: ComplaintSignedGenesisFirstDAttemptV1? = null,
        catalogAuthorAttempt: CatalogGenesisFreezeAttemptV1? = null,
        catalogFinalizerAttempt: CatalogGenesisFinalizeAttemptV1? = null,
        catalogPublisherAttempt: CatalogGenesisPublishAttemptV1? = null,
        catalogSignerRotationAttempt: CatalogSignerRotationFreezeAttemptV1? = null,
        signerRotationRecovery: CatalogSignerRotationPreparedRecoveryV1? = null,
        signerRotationAuthor: CatalogSignerRotationInitialAuthorV1? = null,
        signerRotationDelivery: CatalogSignerRotationDeliveryV1? = null,
        signerRotationActivation: CatalogSignerRotationActivationV1? = null,
        testRunActivation: CatalogTestRunActivationV1? = null,
        testRegistration: ComplaintTestNamespaceRegistrationAttemptV1? = null,
        testInitialAdmission: ComplaintTestInitialAdmissionV1? = null,
        testRecoveryRegistration: ComplaintTestNamespaceRecoveryRegistrationAttemptV1? = null,
        testRunSealer: TestRunSealingV1? = null,
        testOrdinarySealer: TestRunOrdinarySealV1? = null,
        testRunOwnerDelete: TestRunOwnerDeleteContinuationV1? = null,
        testRunOwnerDeleteAll: TestRunOwnerDeleteAllContinuationV1? = null,
        testRunAdminDelete: TestRunAdminDeleteContinuationV1? = null,
        testOrdinaryDrain: TestRunOrdinaryDrainV1? = null,
        testInstallationManifest: TestRunInstallationManifestV1? = null,
        testInstallationManifestPublication: TestRunInstallationManifestPublicationV1? = null,
        testRunPurge: TestRunPurgePublicationV1? = null,
    ): PersistencePhaseContext {
        try {
            requireConnectionFree() // Before even a fail-fast permit attempt, including unbound loans.
        } catch (failure: Throwable) {
            current.get()?.recordNestedEntryFailure(path, failure)
            throw failure
        }
        selection.requireResources() // A changed/unprovable resource pair cannot spend a phase permit.
        requireCatalogGenesisEntry(catalogAuthorAttempt, catalogFinalizerAttempt)
        requireSignerRotationRecoveryEntry(path, catalogSignerRotationAttempt, signerRotationRecovery)
        requireSignerRotationAuthorEntry(path, catalogSignerRotationAttempt, signerRotationAuthor)
        requireSignerRotationDeliveryEntry(path, signerRotationDelivery)
        requireSignerRotationActivationEntry(path, signerRotationActivation)
        requireTestRunActivationEntry(path, testRunActivation)
        if ((path === PersistencePhasePath.COMPLAINT_TEST_NAMESPACE_REGISTRATION) != (testRegistration != null)) {
            throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
        if (path.testInitialAdmission != (testInitialAdmission != null)) {
            throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
        if ((path === PersistencePhasePath.COMPLAINT_TEST_NAMESPACE_RECOVERY_REGISTRATION) != (testRecoveryRegistration != null)) {
            throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
        if ((path === PersistencePhasePath.COMPLAINT_TEST_INSTALLATION_MANIFEST_PREPARE) != (testInstallationManifest != null)) {
            throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
        if ((path in setOf(PersistencePhasePath.COMPLAINT_TEST_INSTALLATION_MANIFEST_PUBLICATION,
                PersistencePhasePath.COMPLAINT_TEST_INSTALLATION_MANIFEST_VERIFY)) != (testInstallationManifestPublication != null)) {
            throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
        if ((path in setOf(PersistencePhasePath.COMPLAINT_TEST_RUN_PURGE_PUBLICATION,
                PersistencePhasePath.COMPLAINT_TEST_RUN_PURGE_VERIFY)) != (testRunPurge != null)) {
            throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
        if ((path === PersistencePhasePath.COMPLAINT_TEST_ORDINARY_SEAL) != (testOrdinarySealer != null)) {
            throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
        if ((path === PersistencePhasePath.COMPLAINT_TEST_ORDINARY_DRAIN && testOrdinaryDrain == null) ||
            (testOrdinaryDrain != null && (testRunOwnerDelete != null || testRunOwnerDeleteAll != null || testRunAdminDelete != null || path !in setOf(
                PersistencePhasePath.COMPLAINT_TEST_ORDINARY_DRAIN, PersistencePhasePath.COMPLAINT_OWNER_DELETE_APPLY,
                PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_APPLY, PersistencePhasePath.COMPLAINT_ADMIN_DELETE_APPLY)))) {
            throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
        if (path.testRunSealing != (testRunSealer != null)) {
            throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
        if (testRunOwnerDelete != null && path !in setOf(PersistencePhasePath.COMPLAINT_OWNER_DELETE_RELOAD,
                PersistencePhasePath.COMPLAINT_OWNER_DELETE_VERIFY, PersistencePhasePath.COMPLAINT_OWNER_DELETE_APPLY)) {
            throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
        if (testRunOwnerDeleteAll != null && path !in setOf(PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_RELOAD,
                PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_VERIFY, PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_APPLY)) {
            throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
        if (testRunAdminDelete != null && (testRunOwnerDelete != null || testRunOwnerDeleteAll != null || path !in setOf(
                PersistencePhasePath.COMPLAINT_ADMIN_DELETE_RELOAD, PersistencePhasePath.COMPLAINT_ADMIN_DELETE_VERIFY,
                PersistencePhasePath.COMPLAINT_ADMIN_DELETE_APPLY))) {
            throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
        catalogRefresh?.requireProjectedPersistence(this)
        desiredAttempt?.requirePhaseEntry(this, path)
        firstDesiredAttempt?.requirePhaseEntry(this, path)
        catalogAuthorAttempt?.requirePhaseEntry(this, path)
        catalogFinalizerAttempt?.requirePhaseEntry(this, path)
        catalogPublisherAttempt?.requirePhaseEntry(this, path)
        catalogSignerRotationAttempt?.requirePhaseEntry(this, path)
        signerRotationRecovery?.requirePhaseEntry(this, path)
        signerRotationAuthor?.requirePhaseEntry(this, path)
        signerRotationDelivery?.requirePhaseEntry(this, path)
        signerRotationActivation?.requirePhaseEntry(this, path)
        testRunActivation?.requirePhaseEntry(this, path)
        testRegistration?.requirePhaseEntry(this, path)
        testInitialAdmission?.requirePhaseEntry(this, path)
        testRecoveryRegistration?.requirePhaseEntry(this, path)
        testRunSealer?.requirePhaseEntry(this, path)
        testOrdinarySealer?.requirePhaseEntry(this, path)
        testInstallationManifest?.requirePhaseEntry(this, path)
        testInstallationManifestPublication?.requirePhaseEntry(this, path)
        testRunPurge?.requirePhaseEntry(this, path)
        testRunOwnerDelete?.requirePhaseEntry(this, path)
        testRunOwnerDeleteAll?.requirePhaseEntry(this, path)
        testRunAdminDelete?.requirePhaseEntry(this, path)
        testOrdinaryDrain?.requirePhaseEntry(this, path)
        // Request/discovery admission and checkout consume the same stage; neither may restart it after a wait.
        val rotationWork = rotationAttempt?.budget?.capped(EpochRotationLimits.REQUEST_PHASE_MILLIS)
        val cutoffWork = cutoffAttempt?.budget?.capped(2_000)
        val catalogRefreshWork = catalogRefresh?.let { checkNotNull(it.projectedBudget).capped(2_000) }
        val desiredWork = desiredAttempt?.budget?.capped(2_000)
        val firstDesiredWork = firstDesiredAttempt?.budget?.capped(2_000)
        val catalogAuthorWork = catalogAuthorAttempt?.budget?.capped(2_000)
        val catalogFinalizerWork = catalogFinalizerAttempt?.phaseBudget?.capped(2_000)
        val catalogPublisherWork = catalogPublisherAttempt?.budget?.capped(2_000)
        val catalogSignerRotationWork = catalogSignerRotationAttempt?.budget?.capped(2_000)
        val signerRotationRecoveryWork = signerRotationRecovery?.budget?.capped(2_000)
        val signerRotationAuthorWork = signerRotationAuthor?.phaseBudget?.capped(2_000)
        val signerRotationDeliveryWork = signerRotationDelivery?.budget?.capped(2_000)
        val signerRotationActivationWork = signerRotationActivation?.budget?.capped(2_000)
        val testRunActivationWork = testRunActivation?.budget?.capped(2_000)
        val testRegistrationWork = testRegistration?.budget?.capped(2_000)
        val testInitialAdmissionWork = testInitialAdmission?.budget?.capped(2_000)
        val testRecoveryRegistrationWork = testRecoveryRegistration?.budget?.capped(2_000)
        val testRunSealingWork = testRunSealer?.budget?.capped(2_000)
        val testOrdinarySealWork = testOrdinarySealer?.budget?.capped(2_000)
        val testInstallationManifestWork = testInstallationManifest?.budget?.capped(2_000)
        val testInstallationManifestPublicationWork = testInstallationManifestPublication?.phaseBudget()?.capped(2_000)
        val testRunPurgeWork = testRunPurge?.phaseBudget()?.capped(2_000)
        val testRunOwnerDeleteWork = testRunOwnerDelete?.budget?.capped(2_000)
        val testRunOwnerDeleteAllWork = testRunOwnerDeleteAll?.budget?.capped(2_000)
        val testRunAdminDeleteWork = testRunAdminDelete?.budget?.capped(2_000)
        val testOrdinaryDrainWork = testOrdinaryDrain?.budget?.capped(2_000)
        // Secure randomness stays connection-free, before phase publication, locks or permit acquisition.
        val enrollmentOwnerReference = if (path === PersistencePhasePath.COMPLAINT_INSTALLATION_ENROLLMENT) UUID.randomUUID() else null
        val caller = PersistenceOwnedFactoryCaller.capture()
        if (caller.sampleOutsideLocks() != null) {
            caller.restoreAfterFailure()
            throw PersistencePhaseException(PersistencePhaseFailureCode.INTERRUPTED)
        }
        if (!admissionCut.tryLock()) throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
        var phase: PersistencePhaseContext? = null
        try {
            if ((0 until phases.length()).any { phases.get(it)?.blocksEntry(path) == true }) {
                throw PersistencePhaseException(PersistencePhaseFailureCode.CLEANUP_UNRESOLVED)
            }
            val slot = (0 until phases.length()).firstOrNull { phases.get(it) == null }
                ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            val prepared = PersistencePhaseContext(
                this,
                slot,
                caller,
                path,
                deletionScope,
                enrollmentOwnerReference,
                rotationAttempt,
                rotationWork,
                cutoffAttempt,
                cutoffWork,
                catalogRefresh,
                catalogRefreshWork,
                desiredAttempt,
                desiredWork,
                firstDesiredAttempt,
                firstDesiredWork,
                catalogAuthorAttempt,
                catalogAuthorWork,
                catalogFinalizerAttempt,
                catalogFinalizerWork,
                catalogPublisherAttempt,
                catalogPublisherWork,
                catalogSignerRotationAttempt,
                catalogSignerRotationWork,
                signerRotationRecovery,
                signerRotationRecoveryWork,
                signerRotationAuthor,
                signerRotationAuthorWork,
                signerRotationDelivery,
                signerRotationDeliveryWork,
                signerRotationActivation,
                signerRotationActivationWork,
                testRunActivation,
                testRunActivationWork,
                testRegistration,
                testRegistrationWork,
                testInitialAdmission,
                testInitialAdmissionWork,
                testRecoveryRegistration,
                testRecoveryRegistrationWork,
                testRunSealer,
                testRunSealingWork,
                testOrdinarySealer,
                testOrdinarySealWork,
                testRunOwnerDelete,
                testRunOwnerDeleteWork,
                testRunOwnerDeleteAll,
                testRunOwnerDeleteAllWork,
                testRunAdminDelete,
                testRunAdminDeleteWork,
                testOrdinaryDrain,
                testOrdinaryDrainWork,
                testInstallationManifest,
                testInstallationManifestWork,
                testInstallationManifestPublication,
                testInstallationManifestPublicationWork,
                testRunPurge,
                testRunPurgeWork,
            )
            phase = prepared
            // Retain before any publication/permit effect, including entry failures that never return a phase to the executor.
            catalogSignerRotationAttempt?.retainPhase(prepared)
            signerRotationRecovery?.retainPhase(prepared)
            signerRotationAuthor?.retainPhase(prepared)
            signerRotationDelivery?.retainPhase(prepared)
            signerRotationActivation?.retainPhase(prepared)
            testRunActivation?.retainPhase(prepared)
            testRegistration?.retainPhase(prepared)
            testInitialAdmission?.retainPhase(prepared)
            testRecoveryRegistration?.retainPhase(prepared)
            testRunSealer?.retainPhase(prepared)
            testOrdinarySealer?.retainPhase(prepared)
            testInstallationManifest?.retainPhase(prepared)
            testInstallationManifestPublication?.retainPhase(prepared)
            testRunPurge?.retainPhase(prepared)
            testRunOwnerDelete?.retainPhase(prepared)
            testRunOwnerDeleteAll?.retainPhase(prepared)
            testRunAdminDelete?.retainPhase(prepared)
            testOrdinaryDrain?.retainPhase(prepared)
            check(phases.compareAndSet(slot, null, prepared))
            current.set(prepared) // Retain the exact original-caller recovery path BEFORE any permit is spent.
            if (!path.source) prepared.reserveComplaintClaim()
            prepared.retainEntryPermit(selection.acquire(path) ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED))
            prepared.publishEntry()
            return prepared
        } catch (failure: Throwable) {
            catalogSignerRotationAttempt?.observeFailure(failure)
            signerRotationRecovery?.observeFailure(failure)
            signerRotationAuthor?.observeFailure(failure)
            signerRotationDelivery?.observeFailure(failure)
            signerRotationActivation?.observeFailure(failure)
            testRunActivation?.observeFailure(failure)
            testRegistration?.observeFailure(failure)
            testInitialAdmission?.observeFailure(failure)
            testRecoveryRegistration?.observeFailure(failure)
            testRunSealer?.observeFailure(failure)
            testOrdinarySealer?.observeFailure(failure)
            testInstallationManifest?.observeFailure(failure)
            testInstallationManifestPublication?.observeFailure(failure)
            testRunPurge?.observeFailure(failure)
            testRunOwnerDelete?.observeFailure(failure)
            testRunOwnerDeleteAll?.observeFailure(failure)
            testRunAdminDelete?.observeFailure(failure)
            testOrdinaryDrain?.observeFailure(failure)
            try {
                phase?.entryPublicationFailed()
            } catch (cleanup: Throwable) {
                catalogSignerRotationAttempt?.observeFailure(cleanup)
                signerRotationRecovery?.observeFailure(cleanup)
                signerRotationAuthor?.observeFailure(cleanup)
                signerRotationDelivery?.observeFailure(cleanup)
                signerRotationActivation?.observeFailure(cleanup)
                testRunActivation?.observeFailure(cleanup)
                testRegistration?.observeFailure(cleanup)
                testInitialAdmission?.observeFailure(cleanup)
                testRecoveryRegistration?.observeFailure(cleanup)
                testRunSealer?.observeFailure(cleanup)
                testOrdinarySealer?.observeFailure(cleanup)
                testInstallationManifest?.observeFailure(cleanup)
                testInstallationManifestPublication?.observeFailure(cleanup)
                testRunPurge?.observeFailure(cleanup)
                testRunOwnerDelete?.observeFailure(cleanup)
                testRunOwnerDeleteAll?.observeFailure(cleanup)
                testRunAdminDelete?.observeFailure(cleanup)
                testOrdinaryDrain?.observeFailure(cleanup)
                throw PersistencePhaseException(PersistencePhaseFailureCode.CLEANUP_UNRESOLVED, cleanupProven = false)
            } finally {
                phase?.let { catalogSignerRotationAttempt?.observePhaseCleanup(it) }
                phase?.let { signerRotationRecovery?.observePhaseCleanup(it) }
                phase?.let { signerRotationAuthor?.observePhaseCleanup(it) }
                phase?.let { signerRotationDelivery?.observePhaseCleanup(it) }
                phase?.let { signerRotationActivation?.observePhaseCleanup(it) }
                phase?.let { testRunActivation?.observePhaseCleanup(it) }
                phase?.let { testRegistration?.observePhaseCleanup(it) }
                phase?.let { testInitialAdmission?.observePhaseCleanup(it) }
                phase?.let { testRecoveryRegistration?.observePhaseCleanup(it) }
                phase?.let { testRunSealer?.observePhaseCleanup(it) }
                phase?.let { testOrdinarySealer?.observePhaseCleanup(it) }
                phase?.let { testInstallationManifest?.observePhaseCleanup(it) }
                phase?.let { testInstallationManifestPublication?.observePhaseCleanup(it) }
                phase?.let { testRunPurge?.observePhaseCleanup(it) }
                phase?.let { testRunOwnerDelete?.observePhaseCleanup(it) }
                phase?.let { testRunOwnerDeleteAll?.observePhaseCleanup(it) }
                phase?.let { testRunAdminDelete?.observePhaseCleanup(it) }
                phase?.let { testOrdinaryDrain?.observePhaseCleanup(it) }
            }
            // Only the genuinely unused entry was cleaned here; preserve an already bounded reason.
            throw failure as? PersistencePhaseException ?: PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
        } finally {
            admissionCut.unlock()
        }
    }

    private fun requireCatalogGenesisEntry(author: CatalogGenesisFreezeAttemptV1?, finalizer: CatalogGenesisFinalizeAttemptV1?) {
        if ((selection as? Selection.CatalogCoordinator)?.authoring == true && author == null) {
            throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
        if ((selection as? Selection.CatalogCoordinator)?.finalizing == true && finalizer == null) {
            throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
    }

    /** Purpose plus the actual original owner, before any budget/publication/permit effect. */
    private fun requireSignerRotationRecoveryEntry(
        path: PersistencePhasePath,
        attempt: CatalogSignerRotationFreezeAttemptV1?,
        original: CatalogSignerRotationPreparedRecoveryV1?,
    ) {
        if ((selection as? Selection.CatalogCoordinator)?.recovering != true) {
            if (original != null) throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            return
        }
        when (path) {
            PersistencePhasePath.COMPLAINT_CATALOG_SNAPSHOT, PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_ACQUIRE -> {
                if (original == null || attempt != null) throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }

            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_READ, PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_SIGNATURE -> {
                if (original != null || attempt == null) throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
                attempt.requireRecoveryPurpose(this)
            }

            else -> throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
    }

    /** Initial author is separate from recovery and cannot gain entry from a phase mask or a supplied process graph. */
    private fun requireSignerRotationAuthorEntry(
        path: PersistencePhasePath,
        attempt: CatalogSignerRotationFreezeAttemptV1?,
        original: CatalogSignerRotationInitialAuthorV1?,
    ) {
        if ((selection as? Selection.CatalogCoordinator)?.signerAuthoring != true) {
            if (original != null) throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            return
        }
        when (path) {
            PersistencePhasePath.COMPLAINT_CATALOG_SNAPSHOT,
            PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_COMPLETE,
            PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PROJECT,
            PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_ACQUIRE,
            -> if (original == null || attempt != null) throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)

            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_READ,
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_PREPARE,
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_SIGNATURE,
            -> {
                if (original != null || attempt == null) throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
                attempt.requireInitialAuthorPurpose(this)
            }

            else -> throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
    }

    private fun requireSignerRotationDeliveryEntry(path: PersistencePhasePath, original: CatalogSignerRotationDeliveryV1?) {
        if ((selection as? Selection.CatalogCoordinator)?.signerDelivering != true) {
            if (original != null || path in SIGNER_ROTATION_FINALIZATION_PATHS) {
                throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            return
        }
        if (original == null || path !in SIGNER_ROTATION_DELIVERY_PATHS) {
            throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
    }

    private fun requireSignerRotationActivationEntry(path: PersistencePhasePath, original: CatalogSignerRotationActivationV1?) {
        if ((selection as? Selection.CatalogCoordinator)?.signerActivating != true) {
            if (original != null || path in SIGNER_ROTATION_ACTIVATION_EFFECT_PATHS) {
                throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            return
        }
        if (original == null || path !in SIGNER_ROTATION_ACTIVATION_PATHS) {
            throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
    }

    private fun requireTestRunActivationEntry(path: PersistencePhasePath, original: CatalogTestRunActivationV1?) {
        if ((selection as? Selection.CatalogCoordinator)?.testActivating != true) {
            if (original != null || path.catalogTestRunActivation) throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            return
        }
        if (original == null || !path.catalogTestRunActivation) throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
    }

    internal fun detach(phase: PersistencePhaseContext, slot: Int) {
        check(phases.compareAndSet(slot, phase, null)) // A late old completion cannot erase a new permit.
    }

    internal fun clearCaller(phase: PersistencePhaseContext) {
        check(current.get() === phase)
        current.remove()
    }

    internal fun retainCallerForRecovery(phase: PersistencePhaseContext) {
        check(current.get() == null || current.get() === phase)
        current.set(phase)
    }

    override fun toString(): String = "PersistencePhaseOwnership(redacted)"

    /** Private closed composition, not caller-selected resource/lock flags or an alternate finalizer. */
    private sealed interface Selection {
        val manager: PlatformTransactionManager
        val dataSource: GuardedDataSource
        val ownerLimit: Int
        fun bind(owner: PersistencePhaseOwnership)
        fun requireResources()
        fun acquire(path: PersistencePhasePath): LocalPersistencePermit?

        class Ordinary(
            private val admission: OrdinaryPersistenceAdmission,
            override val manager: GuardedJpaTransactionManager,
            val otherManager: GuardedJdbcTransactionManager?,
        ) : Selection {
            override val dataSource: GuardedDataSource get() = manager.dataSource
            override val ownerLimit: Int get() = admission.ownerLimit
            override fun bind(owner: PersistencePhaseOwnership) = manager.bindPhaseOwner(owner)

            fun matchesComplaintPool(size: Int): Boolean = admission.matchesComplaintPool(size)

            override fun requireResources() {
                dataSource.requireOrdinaryPhaseResource()
                manager.requireResourcePair()
            }

            override fun acquire(path: PersistencePhasePath): LocalPersistencePermit? = when (path) {
                PersistencePhasePath.SOURCE_GRANT_CLEANUP,
                PersistencePhasePath.SOURCE_STEP_UP_SNAPSHOT,
                PersistencePhasePath.SOURCE_STEP_UP_ISSUANCE,
                -> admission.trySourceBoundary()

                PersistencePhasePath.COMPLAINT_GRANT_CLEANUP,
                PersistencePhasePath.COMPLAINT_STEP_UP_SNAPSHOT,
                PersistencePhasePath.COMPLAINT_STEP_UP_ISSUANCE,
                PersistencePhasePath.COMPLAINT_ADMIN_AUDIT,
                PersistencePhasePath.COMPLAINT_RECOVERY_SETTLEMENT,
                PersistencePhasePath.COMPLAINT_TEST_RESERVE_SPEND,
                PersistencePhasePath.COMPLAINT_INSTALLATION_ENROLLMENT,
                PersistencePhasePath.COMPLAINT_INSTALLATION_SESSION_PREFLIGHT,
                PersistencePhasePath.COMPLAINT_INSTALLATION_SESSION_REFRESH,
                PersistencePhasePath.COMPLAINT_INSTALLATION_DELETION_PREFLIGHT,
                PersistencePhasePath.COMPLAINT_INSTALLATION_CURRENT_STATE,
                PersistencePhasePath.COMPLAINT_OWNER_HISTORY_AUTHENTICATION,
                PersistencePhasePath.COMPLAINT_OWNER_HISTORY_PAGE,
                PersistencePhasePath.COMPLAINT_OWNER_DETAIL,
                PersistencePhasePath.COMPLAINT_ADMIN_READ_AUTHENTICATION,
                PersistencePhasePath.COMPLAINT_ADMIN_SEARCH,
                PersistencePhasePath.COMPLAINT_ADMIN_DETAIL,
                PersistencePhasePath.COMPLAINT_ADMIN_STATS,
                PersistencePhasePath.COMPLAINT_ADMIN_EDIT_PREFLIGHT,
                PersistencePhasePath.COMPLAINT_ADMIN_STATUS_PREFLIGHT,
                PersistencePhasePath.COMPLAINT_ADMIN_BATCH_STATUS_PREFLIGHT,
                PersistencePhasePath.COMPLAINT_ADMIN_EDIT,
                PersistencePhasePath.COMPLAINT_ADMIN_STATUS,
                PersistencePhasePath.COMPLAINT_ADMIN_BATCH_STATUS,
                PersistencePhasePath.COMPLAINT_OWNER_OPERATION_AUTHENTICATION,
                PersistencePhasePath.COMPLAINT_OWNER_CREATE_PREFLIGHT,
                PersistencePhasePath.COMPLAINT_OWNER_CREATE,
                PersistencePhasePath.COMPLAINT_OWNER_REPLY_PREFLIGHT,
                PersistencePhasePath.COMPLAINT_OWNER_REPLY,
                PersistencePhasePath.COMPLAINT_OWNER_OPERATION_STATUS,
                PersistencePhasePath.COMPLAINT_OWNER_EDIT_AUTHENTICATION,
                PersistencePhasePath.COMPLAINT_OWNER_EDIT_PREFLIGHT,
                PersistencePhasePath.COMPLAINT_OWNER_EDIT_STATUS,
                PersistencePhasePath.COMPLAINT_OWNER_DELETE_AUTHENTICATION,
                PersistencePhasePath.COMPLAINT_OWNER_DELETE_PREFLIGHT,
                PersistencePhasePath.COMPLAINT_ADMIN_DELETE_PREFLIGHT,
                PersistencePhasePath.COMPLAINT_OWNER_DELETE_STATUS,
                PersistencePhasePath.COMPLAINT_OWNER_EDIT,
                -> admission.tryComplaintBoundary()

                PersistencePhasePath.COMPLAINT_DELETION_ADMIN_AUDIT,
                PersistencePhasePath.COMPLAINT_DELETION_MUTATION,
                PersistencePhasePath.COMPLAINT_DELETION_FENCE_PREFIX,
                PersistencePhasePath.COMPLAINT_DELETION_CONTROL_SNAPSHOT,
                PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_AUTHORIZE,
                PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_RELOAD,
                PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_VERIFY,
                PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_APPLY,
                PersistencePhasePath.COMPLAINT_OWNER_DELETE_AUTHORIZE,
                PersistencePhasePath.COMPLAINT_ADMIN_DELETE_AUTHORIZE,
                PersistencePhasePath.COMPLAINT_OWNER_DELETE_RELOAD,
                PersistencePhasePath.COMPLAINT_ADMIN_DELETE_RELOAD,
                PersistencePhasePath.COMPLAINT_OWNER_DELETE_VERIFY,
                PersistencePhasePath.COMPLAINT_ADMIN_DELETE_VERIFY,
                PersistencePhasePath.COMPLAINT_OWNER_DELETE_APPLY,
                PersistencePhasePath.COMPLAINT_ADMIN_DELETE_APPLY,
                PersistencePhasePath.COMPLAINT_CATALOG_SNAPSHOT,
                PersistencePhasePath.COMPLAINT_CATALOG_PROJECTED_HEAD,
                PersistencePhasePath.COMPLAINT_TEST_NAMESPACE_REGISTRATION,
                PersistencePhasePath.COMPLAINT_TEST_INITIAL_ADMISSION_CAPTURE,
                PersistencePhasePath.COMPLAINT_TEST_INITIAL_ADMISSION_RELEASE,
                PersistencePhasePath.COMPLAINT_TEST_NAMESPACE_RECOVERY_REGISTRATION,
                PersistencePhasePath.COMPLAINT_TEST_RUN_SEAL,
                PersistencePhasePath.COMPLAINT_TEST_RUN_SEALED_AUDIT,
                PersistencePhasePath.COMPLAINT_TEST_ORDINARY_SEAL,
                PersistencePhasePath.COMPLAINT_TEST_INSTALLATION_MANIFEST_PREPARE,
                PersistencePhasePath.COMPLAINT_TEST_INSTALLATION_MANIFEST_PUBLICATION,
                PersistencePhasePath.COMPLAINT_TEST_INSTALLATION_MANIFEST_VERIFY,
                PersistencePhasePath.COMPLAINT_TEST_RUN_PURGE_PUBLICATION,
                PersistencePhasePath.COMPLAINT_TEST_RUN_PURGE_VERIFY,
                PersistencePhasePath.COMPLAINT_TEST_ORDINARY_DRAIN,
                PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PREPARE,
                PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_SIGNATURE,
                PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_COMPLETE,
                PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PROJECT,
                PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_READ,
                PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_PREPARE,
                PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_SIGNATURE,
                PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_FINAL_READ,
                PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_COMPLETE,
                PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_PROJECT,
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
                PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_READ,
                PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_PREPARE,
                PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_SIGNATURE,
                PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_COMPLETE,
                PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_PROJECT,
                PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_ACQUIRE,
                PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_RENEW,
                PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_RELINQUISH,
                PersistencePhasePath.COMPLAINT_EPOCH_ROTATION_REQUEST,
                PersistencePhasePath.COMPLAINT_EPOCH_ROTATION_RESUME,
                PersistencePhasePath.COMPLAINT_CUTOFF_CONTROL,
                PersistencePhasePath.COMPLAINT_CUTOFF_PAGE,
                PersistencePhasePath.COMPLAINT_CUTOFF_VERIFY,
                PersistencePhasePath.COMPLAINT_SEAL_PREPARE,
                PersistencePhasePath.COMPLAINT_DESIRED_BOOTSTRAP,
                PersistencePhasePath.COMPLAINT_DESIRED_CLOSE,
                PersistencePhasePath.COMPLAINT_DESIRED_SUPERSEDE,
                PersistencePhasePath.COMPLAINT_DESIRED_SIGNED_GENESIS_FIRST,
                PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PUBLISH_RECHECK,
                -> throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
        }

        class Deletion(private val admission: DeletionPersistenceAdmission, override val manager: GuardedJdbcTransactionManager) : Selection {
            override val dataSource: GuardedDataSource get() = manager.dataSource
            override val ownerLimit: Int get() = admission.ownerLimit
            override fun bind(owner: PersistencePhaseOwnership) = manager.bindPhaseOwner(owner)
            override fun requireResources() = dataSource.requireDeletionPhaseResource()

            override fun acquire(path: PersistencePhasePath): LocalPersistencePermit? {
                if (path !in DELETION_PATHS) {
                    throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
                }
                return when (path) {
                    PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_AUTHORIZE,
                    PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_RELOAD,
                    PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_VERIFY,
                    PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_APPLY,
                PersistencePhasePath.COMPLAINT_OWNER_DELETE_AUTHORIZE,
                PersistencePhasePath.COMPLAINT_ADMIN_DELETE_AUTHORIZE,
                PersistencePhasePath.COMPLAINT_OWNER_DELETE_RELOAD,
                PersistencePhasePath.COMPLAINT_ADMIN_DELETE_RELOAD,
                PersistencePhasePath.COMPLAINT_OWNER_DELETE_VERIFY,
                PersistencePhasePath.COMPLAINT_ADMIN_DELETE_VERIFY,
                PersistencePhasePath.COMPLAINT_OWNER_DELETE_APPLY,
                PersistencePhasePath.COMPLAINT_ADMIN_DELETE_APPLY,
                    -> admission.tryPrivacyDeletion()

                    else -> admission.tryRoutineDeletion()
                }
            }
        }

        class CatalogCoordinator(private val resources: CatalogCoordinatorPersistence) : Selection {
            val authoring: Boolean get() = resources.catalogGenesisAuthoring
            val finalizing: Boolean get() = resources.catalogGenesisFinalization
            val recovering: Boolean get() = resources.catalogSignerRotationRecovery
            val signerAuthoring: Boolean get() = resources.catalogSignerRotationAuthoring
            val signerDelivering: Boolean get() = resources.catalogSignerRotationDelivery
            val signerActivating: Boolean get() = resources.catalogSignerRotationActivation
            val testActivating: Boolean get() = resources.catalogTestRunActivation
            override val dataSource: GuardedDataSource get() = resources.dataSource
            override val manager: GuardedJdbcTransactionManager get() = resources.manager
            override val ownerLimit: Int get() = 1
            override fun bind(owner: PersistencePhaseOwnership) = manager.bindPhaseOwner(owner)
            override fun requireResources() = resources.requireResources()

            override fun acquire(path: PersistencePhasePath): LocalPersistencePermit? {
                val allowed = when {
                    testActivating -> TEST_RUN_ACTIVATION_PATHS
                    resources.desiredInstallationOperator -> DESIRED_INSTALL_PATHS
                    authoring -> CATALOG_AUTHOR_PATHS
                    finalizing -> CATALOG_FINALIZER_PATHS
                    recovering -> SIGNER_ROTATION_RECOVERY_PATHS
                    signerAuthoring -> CATALOG_SIGNER_ROTATION_AUTHOR_PATHS
                    signerDelivering -> SIGNER_ROTATION_DELIVERY_PATHS
                    signerActivating -> SIGNER_ROTATION_ACTIVATION_PATHS
                    else -> CATALOG_PATHS
                }
                if (path !in allowed) {
                    throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
                }
                return resources.tryPhaseAdmission()
            }
        }
    }

    companion object {
        private val TEST_RUN_ACTIVATION_PATHS = setOf(
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
        )
        private val SIGNER_ROTATION_ACTIVATION_EFFECT_PATHS = setOf(
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_READ,
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_PREPARE,
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_SIGNATURE,
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_COMPLETE,
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_PROJECT,
        )
        private val SIGNER_ROTATION_ACTIVATION_PATHS = SIGNER_ROTATION_ACTIVATION_EFFECT_PATHS + setOf(
            PersistencePhasePath.COMPLAINT_CATALOG_SNAPSHOT,
            PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_ACQUIRE,
        )
        private val SIGNER_ROTATION_FINALIZATION_PATHS = setOf(
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_FINAL_READ,
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_COMPLETE,
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_PROJECT,
        )
        private val SIGNER_ROTATION_DELIVERY_PATHS = SIGNER_ROTATION_FINALIZATION_PATHS + setOf(
            PersistencePhasePath.COMPLAINT_CATALOG_SNAPSHOT,
            PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_ACQUIRE,
        )
        private val CATALOG_SIGNER_ROTATION_AUTHOR_PATHS = setOf(
            PersistencePhasePath.COMPLAINT_CATALOG_SNAPSHOT,
            PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_COMPLETE,
            PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PROJECT,
            PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_ACQUIRE,
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_READ,
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_PREPARE,
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_SIGNATURE,
        )
        private val SIGNER_ROTATION_RECOVERY_PATHS = setOf(
            PersistencePhasePath.COMPLAINT_CATALOG_SNAPSHOT,
            PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_ACQUIRE,
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_READ,
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_SIGNATURE,
        )
        private val CATALOG_FINALIZER_PATHS = setOf(
            PersistencePhasePath.COMPLAINT_CATALOG_SNAPSHOT,
            PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_COMPLETE,
            PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PROJECT,
        )
        private val CATALOG_AUTHOR_PATHS = setOf(
            PersistencePhasePath.COMPLAINT_CATALOG_SNAPSHOT,
            PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PREPARE,
            PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_SIGNATURE,
        )
        private val DESIRED_INSTALL_PATHS = setOf(
            PersistencePhasePath.COMPLAINT_DESIRED_BOOTSTRAP,
            PersistencePhasePath.COMPLAINT_DESIRED_CLOSE,
            PersistencePhasePath.COMPLAINT_DESIRED_SUPERSEDE,
            PersistencePhasePath.COMPLAINT_DESIRED_SIGNED_GENESIS_FIRST,
            PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PUBLISH_RECHECK,
        )
        private val CATALOG_PATHS = setOf(
            PersistencePhasePath.COMPLAINT_CATALOG_SNAPSHOT,
            PersistencePhasePath.COMPLAINT_CATALOG_PROJECTED_HEAD,
            PersistencePhasePath.COMPLAINT_TEST_NAMESPACE_REGISTRATION,
            PersistencePhasePath.COMPLAINT_TEST_INITIAL_ADMISSION_CAPTURE,
            PersistencePhasePath.COMPLAINT_TEST_INITIAL_ADMISSION_RELEASE,
            PersistencePhasePath.COMPLAINT_TEST_NAMESPACE_RECOVERY_REGISTRATION,
            PersistencePhasePath.COMPLAINT_TEST_RUN_SEAL,
            PersistencePhasePath.COMPLAINT_TEST_RUN_SEALED_AUDIT,
            PersistencePhasePath.COMPLAINT_TEST_ORDINARY_SEAL,
            PersistencePhasePath.COMPLAINT_TEST_INSTALLATION_MANIFEST_PREPARE,
            PersistencePhasePath.COMPLAINT_TEST_INSTALLATION_MANIFEST_PUBLICATION,
            PersistencePhasePath.COMPLAINT_TEST_INSTALLATION_MANIFEST_VERIFY,
            PersistencePhasePath.COMPLAINT_TEST_RUN_PURGE_PUBLICATION,
            PersistencePhasePath.COMPLAINT_TEST_RUN_PURGE_VERIFY,
            PersistencePhasePath.COMPLAINT_TEST_ORDINARY_DRAIN,
            PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PREPARE,
            PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_SIGNATURE,
            PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_COMPLETE,
            PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PROJECT,
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_READ,
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_PREPARE,
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_SIGNATURE,
            PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_ACQUIRE,
            PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_RENEW,
            PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_RELINQUISH,
            PersistencePhasePath.COMPLAINT_EPOCH_ROTATION_REQUEST,
            PersistencePhasePath.COMPLAINT_EPOCH_ROTATION_RESUME,
            PersistencePhasePath.COMPLAINT_CUTOFF_CONTROL,
            PersistencePhasePath.COMPLAINT_CUTOFF_PAGE,
            PersistencePhasePath.COMPLAINT_CUTOFF_VERIFY,
            PersistencePhasePath.COMPLAINT_SEAL_PREPARE,
        )
        private val DELETION_PATHS = setOf(
            PersistencePhasePath.COMPLAINT_DELETION_MUTATION,
            PersistencePhasePath.COMPLAINT_DELETION_ADMIN_AUDIT,
            PersistencePhasePath.COMPLAINT_DELETION_FENCE_PREFIX,
            PersistencePhasePath.COMPLAINT_DELETION_CONTROL_SNAPSHOT,
            PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_AUTHORIZE,
            PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_RELOAD,
            PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_VERIFY,
            PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_APPLY,
                PersistencePhasePath.COMPLAINT_OWNER_DELETE_AUTHORIZE,
                PersistencePhasePath.COMPLAINT_ADMIN_DELETE_AUTHORIZE,
                PersistencePhasePath.COMPLAINT_OWNER_DELETE_RELOAD,
                PersistencePhasePath.COMPLAINT_ADMIN_DELETE_RELOAD,
                PersistencePhasePath.COMPLAINT_OWNER_DELETE_VERIFY,
                PersistencePhasePath.COMPLAINT_ADMIN_DELETE_VERIFY,
                PersistencePhasePath.COMPLAINT_OWNER_DELETE_APPLY,
                PersistencePhasePath.COMPLAINT_ADMIN_DELETE_APPLY,
        )
        private val current = ThreadLocal<PersistencePhaseContext?>()
        private val loans = ThreadLocal<PersistenceLeaseCompletion?>()

        internal fun deletion(
            admission: DeletionPersistenceAdmission,
            manager: GuardedJdbcTransactionManager,
            nanoClock: PersistenceNanoClock = SystemPersistenceNanoClock,
        ): PersistencePhaseOwnership = PersistencePhaseOwnership(Selection.Deletion(admission, manager), nanoClock)

        internal fun catalogCoordinator(
            resources: CatalogCoordinatorPersistence,
            nanoClock: PersistenceNanoClock = SystemPersistenceNanoClock,
        ): PersistencePhaseOwnership = PersistencePhaseOwnership(Selection.CatalogCoordinator(resources), nanoClock)

        internal fun current(): PersistencePhaseContext? = current.get()

        internal fun prepareAcquisition(dataSource: GuardedDataSource, acquisition: PoolLifecycle.Acquisition): PersistenceLeaseCompletion {
            val phase = current.get()
            phase?.authorizeAcquisition(dataSource)
            val completion = PersistenceLeaseCompletion.prepare(dataSource, acquisition, phase)
            completion.nextOutstanding = loans.get()
            loans.set(completion) // Retained before acquisition.enter(), Hikari or any lazy-manager callback.
            phase?.retainAcquisition(completion)
            return completion
        }

        /** Prunes only exact ended identities. No resource lookup, counts, close retry or remote callback. */
        internal fun reconcileLoans() {
            var selected = loans.get()
            var previous: PersistenceLeaseCompletion? = null
            while (selected != null) {
                val next = selected.nextOutstanding
                if (selected.quiescent()) {
                    if (previous == null) {
                        if (next == null) loans.remove() else loans.set(next)
                    } else {
                        previous.nextOutstanding = next
                    }
                    selected.nextOutstanding = null
                } else {
                    previous = selected
                }
                selected = next
            }
        }

        internal fun connectionFree() {
            current.get()?.reconcileQuarantine()
            reconcileLoans()
            val callerRetainsPersistence = current.get() != null || loans.get() != null || LocalPersistencePermit.callerHasOutstandingPermit() ||
                EpochRotationPersistence.callerHasOutstanding()
            if (callerRetainsPersistence || !springConnectionFree()) {
                throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            }
        }

        internal fun springConnectionFree(): Boolean = !TransactionSynchronizationManager.isActualTransactionActive() &&
            !TransactionSynchronizationManager.isSynchronizationActive() && TransactionSynchronizationManager.getResourceMap().isEmpty()
    }
}

/** Necessary gate only; its existence does not certify any unwritten password/network caller. */
internal fun requireConnectionFree() = PersistencePhaseOwnership.connectionFree()

/** Only named entry methods select a path; no caller resource, SQL, callback or transaction flag. */
internal enum class PersistencePhasePath {
    SOURCE_GRANT_CLEANUP,
    COMPLAINT_GRANT_CLEANUP,
    SOURCE_STEP_UP_SNAPSHOT,
    COMPLAINT_STEP_UP_SNAPSHOT,
    SOURCE_STEP_UP_ISSUANCE,
    COMPLAINT_STEP_UP_ISSUANCE,
    COMPLAINT_ADMIN_AUDIT,
    COMPLAINT_DELETION_ADMIN_AUDIT,
    COMPLAINT_RECOVERY_SETTLEMENT,
    COMPLAINT_TEST_RESERVE_SPEND,
    COMPLAINT_INSTALLATION_ENROLLMENT,
    COMPLAINT_INSTALLATION_SESSION_PREFLIGHT,
    COMPLAINT_INSTALLATION_SESSION_REFRESH,
    COMPLAINT_INSTALLATION_DELETION_PREFLIGHT,
    COMPLAINT_OWNER_DELETE_ALL_AUTHORIZE,
    COMPLAINT_OWNER_DELETE_ALL_RELOAD,
    COMPLAINT_OWNER_DELETE_ALL_VERIFY,
    COMPLAINT_OWNER_DELETE_ALL_APPLY,
    COMPLAINT_OWNER_DELETE_AUTHORIZE,
    COMPLAINT_ADMIN_DELETE_AUTHORIZE,
    COMPLAINT_OWNER_DELETE_RELOAD,
    COMPLAINT_ADMIN_DELETE_RELOAD,
    COMPLAINT_OWNER_DELETE_VERIFY,
    COMPLAINT_ADMIN_DELETE_VERIFY,
    COMPLAINT_OWNER_DELETE_APPLY,
    COMPLAINT_ADMIN_DELETE_APPLY,
    COMPLAINT_OWNER_DELETE_AUTHENTICATION,
    COMPLAINT_OWNER_DELETE_PREFLIGHT,
    COMPLAINT_ADMIN_DELETE_PREFLIGHT,
    COMPLAINT_OWNER_DELETE_STATUS,
    COMPLAINT_INSTALLATION_CURRENT_STATE,
    COMPLAINT_OWNER_HISTORY_AUTHENTICATION,
    COMPLAINT_OWNER_HISTORY_PAGE,
    COMPLAINT_OWNER_DETAIL,
    COMPLAINT_ADMIN_READ_AUTHENTICATION,
    COMPLAINT_ADMIN_SEARCH,
    COMPLAINT_ADMIN_DETAIL,
    COMPLAINT_ADMIN_STATS,
    COMPLAINT_ADMIN_EDIT_PREFLIGHT,
    COMPLAINT_ADMIN_STATUS_PREFLIGHT,
    COMPLAINT_ADMIN_BATCH_STATUS_PREFLIGHT,
    COMPLAINT_ADMIN_EDIT,
    COMPLAINT_ADMIN_STATUS,
    COMPLAINT_ADMIN_BATCH_STATUS,
    COMPLAINT_OWNER_OPERATION_AUTHENTICATION,
    COMPLAINT_OWNER_CREATE_PREFLIGHT,
    COMPLAINT_OWNER_CREATE,
    COMPLAINT_OWNER_REPLY_PREFLIGHT,
    COMPLAINT_OWNER_REPLY,
    COMPLAINT_OWNER_OPERATION_STATUS,
    COMPLAINT_OWNER_EDIT_AUTHENTICATION,
    COMPLAINT_OWNER_EDIT_PREFLIGHT,
    COMPLAINT_OWNER_EDIT_STATUS,
    COMPLAINT_OWNER_EDIT,
    COMPLAINT_DELETION_MUTATION,
    COMPLAINT_DELETION_FENCE_PREFIX,
    COMPLAINT_DELETION_CONTROL_SNAPSHOT,
    COMPLAINT_CATALOG_SNAPSHOT,
    COMPLAINT_CATALOG_PROJECTED_HEAD,
    COMPLAINT_TEST_NAMESPACE_REGISTRATION,
    COMPLAINT_TEST_INITIAL_ADMISSION_CAPTURE,
    COMPLAINT_TEST_INITIAL_ADMISSION_RELEASE,
    COMPLAINT_TEST_NAMESPACE_RECOVERY_REGISTRATION,
    COMPLAINT_TEST_RUN_SEAL,
    COMPLAINT_TEST_RUN_SEALED_AUDIT,
    COMPLAINT_TEST_ORDINARY_SEAL,
    COMPLAINT_TEST_INSTALLATION_MANIFEST_PREPARE,
    COMPLAINT_TEST_INSTALLATION_MANIFEST_PUBLICATION,
    COMPLAINT_TEST_INSTALLATION_MANIFEST_VERIFY,
    COMPLAINT_TEST_RUN_PURGE_PUBLICATION,
    COMPLAINT_TEST_RUN_PURGE_VERIFY,
    COMPLAINT_TEST_ORDINARY_DRAIN,
    COMPLAINT_CATALOG_GENESIS_PREPARE,
    COMPLAINT_CATALOG_GENESIS_SIGNATURE,
    COMPLAINT_CATALOG_GENESIS_COMPLETE,
    COMPLAINT_CATALOG_GENESIS_PROJECT,
    COMPLAINT_CATALOG_SIGNER_ROTATION_READ,
    COMPLAINT_CATALOG_SIGNER_ROTATION_PREPARE,
    COMPLAINT_CATALOG_SIGNER_ROTATION_SIGNATURE,
    COMPLAINT_CATALOG_SIGNER_ROTATION_FINAL_READ,
    COMPLAINT_CATALOG_SIGNER_ROTATION_COMPLETE,
    COMPLAINT_CATALOG_SIGNER_ROTATION_PROJECT,
    COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_SNAPSHOT,
    COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_LEASE_ACQUIRE,
    COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_PREPARE,
    COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_PREPARED_RELOAD,
    COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_SIGNATURE,
    COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_SIGNED_RELOAD,
    COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_DELIVERY_RELOAD,
    COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_COMPLETE,
    COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_PENDING_RELOAD,
    COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_PROJECT_RELOAD,
    COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_PROJECT,
    COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_PROJECTED_RELOAD,
    COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_READ,
    COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_PREPARE,
    COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_SIGNATURE,
    COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_COMPLETE,
    COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_PROJECT,
    COMPLAINT_COORDINATOR_LEASE_ACQUIRE,
    COMPLAINT_COORDINATOR_LEASE_RENEW,
    COMPLAINT_COORDINATOR_LEASE_RELINQUISH,
    COMPLAINT_EPOCH_ROTATION_REQUEST,
    COMPLAINT_EPOCH_ROTATION_RESUME,
    COMPLAINT_CUTOFF_CONTROL,
    COMPLAINT_CUTOFF_PAGE,
    COMPLAINT_CUTOFF_VERIFY,
    COMPLAINT_SEAL_PREPARE,
    COMPLAINT_DESIRED_BOOTSTRAP,
    COMPLAINT_DESIRED_CLOSE,
    COMPLAINT_DESIRED_SUPERSEDE,
    COMPLAINT_DESIRED_SIGNED_GENESIS_FIRST,
    COMPLAINT_CATALOG_GENESIS_PUBLISH_RECHECK,
    ;

    internal val testInitialAdmission: Boolean
        get() = this === COMPLAINT_TEST_INITIAL_ADMISSION_CAPTURE || this === COMPLAINT_TEST_INITIAL_ADMISSION_RELEASE

    internal val testRunSealing: Boolean
        get() = this === COMPLAINT_TEST_RUN_SEAL || this === COMPLAINT_TEST_RUN_SEALED_AUDIT

    internal val catalogTestRunActivation: Boolean
        get() = this === COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_SNAPSHOT || this === COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_LEASE_ACQUIRE ||
            this === COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_PREPARE || this === COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_PREPARED_RELOAD ||
            this === COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_SIGNATURE || this === COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_SIGNED_RELOAD ||
            this === COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_DELIVERY_RELOAD || this === COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_COMPLETE ||
            this === COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_PENDING_RELOAD || this === COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_PROJECT_RELOAD ||
            this === COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_PROJECT || this === COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_PROJECTED_RELOAD

    internal val source: Boolean
        get() = this === SOURCE_GRANT_CLEANUP || this === SOURCE_STEP_UP_SNAPSHOT || this === SOURCE_STEP_UP_ISSUANCE

    /** SQL participation only. Mixed write/no-op paths enter before branching; observations keep their existing policy. */
    internal val complaintMaintenanceWriter: Boolean
        get() = when (this) {
            COMPLAINT_TEST_NAMESPACE_REGISTRATION, // SELECT FOR UPDATE needs M/RC; only its typed owner admits the closed TEST gate.
            COMPLAINT_TEST_INITIAL_ADMISSION_CAPTURE,
            COMPLAINT_TEST_INITIAL_ADMISSION_RELEASE, // Separate M-exclusive phase, never a shared-lock upgrade.
            COMPLAINT_TEST_NAMESPACE_RECOVERY_REGISTRATION, // SELECT FOR UPDATE needs M/RC; only its typed owner admits the closed TEST gate.
            COMPLAINT_TEST_RUN_SEAL,
            COMPLAINT_TEST_RUN_SEALED_AUDIT,
            COMPLAINT_TEST_ORDINARY_SEAL,
            COMPLAINT_TEST_INSTALLATION_MANIFEST_PREPARE,
            COMPLAINT_TEST_INSTALLATION_MANIFEST_PUBLICATION,
            COMPLAINT_TEST_RUN_PURGE_PUBLICATION,
            COMPLAINT_TEST_ORDINARY_DRAIN,
            COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_LEASE_ACQUIRE,
            COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_PREPARE,
            COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_PREPARED_RELOAD,
            COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_SIGNATURE,
            COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_SIGNED_RELOAD,
            COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_DELIVERY_RELOAD,
            COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_COMPLETE,
            COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_PENDING_RELOAD,
            COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_PROJECT_RELOAD,
            COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_PROJECT,
            COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_PROJECTED_RELOAD,
            COMPLAINT_GRANT_CLEANUP,
            COMPLAINT_STEP_UP_ISSUANCE,
            COMPLAINT_ADMIN_AUDIT,
            COMPLAINT_DELETION_ADMIN_AUDIT,
            COMPLAINT_RECOVERY_SETTLEMENT,
            COMPLAINT_TEST_RESERVE_SPEND,
            COMPLAINT_INSTALLATION_ENROLLMENT,
            COMPLAINT_INSTALLATION_SESSION_REFRESH,
            COMPLAINT_OWNER_DELETE_ALL_AUTHORIZE,
            COMPLAINT_OWNER_DELETE_ALL_VERIFY,
            COMPLAINT_OWNER_DELETE_ALL_APPLY,
            COMPLAINT_OWNER_DELETE_AUTHORIZE,
            COMPLAINT_ADMIN_DELETE_AUTHORIZE,
            COMPLAINT_OWNER_DELETE_VERIFY,
            COMPLAINT_ADMIN_DELETE_VERIFY,
            COMPLAINT_OWNER_DELETE_APPLY,
            COMPLAINT_ADMIN_DELETE_APPLY,
            COMPLAINT_OWNER_CREATE,
            COMPLAINT_OWNER_REPLY,
            COMPLAINT_OWNER_EDIT,
            COMPLAINT_ADMIN_EDIT,
            COMPLAINT_ADMIN_STATUS,
            COMPLAINT_ADMIN_BATCH_STATUS,
            COMPLAINT_DELETION_MUTATION,
            COMPLAINT_CATALOG_GENESIS_PREPARE,
            COMPLAINT_CATALOG_GENESIS_SIGNATURE,
            COMPLAINT_CATALOG_GENESIS_COMPLETE,
            COMPLAINT_CATALOG_GENESIS_PROJECT,
            COMPLAINT_CATALOG_SIGNER_ROTATION_PREPARE,
            COMPLAINT_CATALOG_SIGNER_ROTATION_SIGNATURE,
            COMPLAINT_CATALOG_SIGNER_ROTATION_COMPLETE,
            COMPLAINT_CATALOG_SIGNER_ROTATION_PROJECT,
            COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_PREPARE,
            COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_SIGNATURE,
            COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_COMPLETE,
            COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_PROJECT,
            COMPLAINT_COORDINATOR_LEASE_ACQUIRE,
            COMPLAINT_COORDINATOR_LEASE_RENEW,
            COMPLAINT_COORDINATOR_LEASE_RELINQUISH,
            COMPLAINT_EPOCH_ROTATION_REQUEST,
            COMPLAINT_CUTOFF_VERIFY,
            COMPLAINT_SEAL_PREPARE,
            COMPLAINT_DESIRED_BOOTSTRAP,
            COMPLAINT_DESIRED_CLOSE,
            COMPLAINT_DESIRED_SUPERSEDE,
            COMPLAINT_DESIRED_SIGNED_GENESIS_FIRST,
            -> true

            COMPLAINT_TEST_INSTALLATION_MANIFEST_VERIFY, // Authenticated receiptless readback: publication only, no M gate or E/control lookup.
            COMPLAINT_TEST_RUN_PURGE_VERIFY, // Same exact publication-only discipline, separately typed original.
            COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_SNAPSHOT,
            COMPLAINT_STEP_UP_SNAPSHOT,
            COMPLAINT_INSTALLATION_SESSION_PREFLIGHT,
            COMPLAINT_INSTALLATION_DELETION_PREFLIGHT,
            COMPLAINT_OWNER_DELETE_ALL_RELOAD,
            COMPLAINT_OWNER_DELETE_RELOAD,
            COMPLAINT_ADMIN_DELETE_RELOAD,
            COMPLAINT_OWNER_DELETE_AUTHENTICATION,
            COMPLAINT_OWNER_DELETE_PREFLIGHT,
            COMPLAINT_ADMIN_DELETE_PREFLIGHT,
            COMPLAINT_OWNER_DELETE_STATUS,
            COMPLAINT_INSTALLATION_CURRENT_STATE,
            COMPLAINT_OWNER_HISTORY_AUTHENTICATION,
            COMPLAINT_OWNER_HISTORY_PAGE,
            COMPLAINT_OWNER_DETAIL,
            COMPLAINT_ADMIN_READ_AUTHENTICATION,
            COMPLAINT_ADMIN_SEARCH,
            COMPLAINT_ADMIN_DETAIL,
            COMPLAINT_ADMIN_STATS,
            COMPLAINT_ADMIN_EDIT_PREFLIGHT,
            COMPLAINT_ADMIN_STATUS_PREFLIGHT,
            COMPLAINT_ADMIN_BATCH_STATUS_PREFLIGHT,
            COMPLAINT_OWNER_OPERATION_AUTHENTICATION,
            COMPLAINT_OWNER_CREATE_PREFLIGHT,
            COMPLAINT_OWNER_REPLY_PREFLIGHT,
            COMPLAINT_OWNER_OPERATION_STATUS,
            COMPLAINT_OWNER_EDIT_AUTHENTICATION,
            COMPLAINT_OWNER_EDIT_PREFLIGHT,
            COMPLAINT_OWNER_EDIT_STATUS,
            COMPLAINT_DELETION_FENCE_PREFIX,
            COMPLAINT_DELETION_CONTROL_SNAPSHOT,
            COMPLAINT_CATALOG_SNAPSHOT,
            COMPLAINT_CATALOG_PROJECTED_HEAD,
            COMPLAINT_CATALOG_SIGNER_ROTATION_READ,
            COMPLAINT_CATALOG_SIGNER_ROTATION_FINAL_READ,
            COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_READ,
            COMPLAINT_EPOCH_ROTATION_RESUME,
            COMPLAINT_CUTOFF_CONTROL,
            COMPLAINT_CUTOFF_PAGE,
            COMPLAINT_CATALOG_GENESIS_PUBLISH_RECHECK,
            SOURCE_GRANT_CLEANUP,
            SOURCE_STEP_UP_SNAPSHOT,
            SOURCE_STEP_UP_ISSUANCE,
            -> false
        }

    internal val readOnly: Boolean
        get() = when (this) {
            COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_SNAPSHOT,
            COMPLAINT_INSTALLATION_SESSION_PREFLIGHT,
            COMPLAINT_INSTALLATION_DELETION_PREFLIGHT,
            COMPLAINT_INSTALLATION_CURRENT_STATE,
            COMPLAINT_OWNER_HISTORY_AUTHENTICATION,
            COMPLAINT_OWNER_HISTORY_PAGE,
            COMPLAINT_OWNER_DETAIL,
            COMPLAINT_ADMIN_READ_AUTHENTICATION,
            COMPLAINT_ADMIN_SEARCH,
            COMPLAINT_ADMIN_DETAIL,
            COMPLAINT_ADMIN_STATS,
            COMPLAINT_ADMIN_EDIT_PREFLIGHT,
            COMPLAINT_ADMIN_STATUS_PREFLIGHT,
            COMPLAINT_ADMIN_BATCH_STATUS_PREFLIGHT,
            COMPLAINT_OWNER_OPERATION_AUTHENTICATION,
            COMPLAINT_OWNER_CREATE_PREFLIGHT,
            COMPLAINT_OWNER_REPLY_PREFLIGHT,
            COMPLAINT_OWNER_OPERATION_STATUS,
            COMPLAINT_OWNER_EDIT_AUTHENTICATION,
            COMPLAINT_OWNER_EDIT_PREFLIGHT,
            COMPLAINT_OWNER_EDIT_STATUS,
            COMPLAINT_OWNER_DELETE_AUTHENTICATION,
            COMPLAINT_OWNER_DELETE_PREFLIGHT,
            COMPLAINT_ADMIN_DELETE_PREFLIGHT,
            COMPLAINT_OWNER_DELETE_STATUS,
            COMPLAINT_CATALOG_SNAPSHOT,
            COMPLAINT_CUTOFF_PAGE,
            -> true

            else -> false
        }
}

/** Bounded, value-free result. A DB fact is deliberately separate from local cleanup/refund. */
internal class PersistencePhaseException(
    val code: PersistencePhaseFailureCode,
    val databaseOutcome: PersistenceDatabaseOutcome = PersistenceDatabaseOutcome.NONE,
    val cleanupProven: Boolean = true,
) : RuntimeException("Persistence phase refused.", null, false, false)

internal enum class PersistencePhaseFailureCode {
    ENTRY_REFUSED,
    MANAGER_REFUSED,
    RESOURCE_REFUSED,
    TIME_BUDGET_EXHAUSTED,
    INTERRUPTED,
    WORK_FAILED,
    COMPLETION_FAILED,
    CLEANUP_UNRESOLVED,
}

/** Public Spring dispatch is guarded by composition: APTM's final methods cannot be overridden. */
internal class PersistenceManagerDispatch(private val identity: PlatformTransactionManager, private val delegate: PlatformTransactionManager) {
    // Any scoped begin/validation failure belongs to this retained status; unscoped failures are rethrown unchanged.
    @Suppress("TooGenericExceptionCaught")
    fun getTransaction(definition: TransactionDefinition?): TransactionStatus {
        val phase = PersistencePhaseOwnership.current()
        val root = phase?.beforeGetTransaction(identity, definition) == true
        val status = PersistenceManagedStatus(identity, phase, root)
        phase?.prepareStatus(status)
        try {
            status.attach(delegate.getTransaction(definition)) // Retain the returned status before validation/allocation.
            phase?.statusReturned(status)
            return status
        } catch (failure: Throwable) {
            if (phase == null) throw failure
            phase.managerFailure(failure)
            throw phase.failureException(PersistencePhaseFailureCode.COMPLETION_FAILED)
        } finally {
            phase?.getTransactionEnded(status)
        }
    }

    // Retain scoped failure/interrupt state before the dispatch finally; never replace the separately observed DB outcome.
    @Suppress("TooGenericExceptionCaught")
    fun complete(candidate: TransactionStatus, commit: Boolean) {
        val status = candidate as? PersistenceManagedStatus ?: throw PersistencePhaseException(PersistencePhaseFailureCode.MANAGER_REFUSED)
        status.requireOwner(identity)
        val phase = status.phase
        phase?.beforeCompletion(identity, status, commit)
        try {
            if (commit) delegate.commit(status.native()) else delegate.rollback(status.native())
        } catch (failure: Throwable) {
            if (phase == null) throw failure
            phase.managerFailure(failure)
            throw phase.failureException(PersistencePhaseFailureCode.COMPLETION_FAILED)
        } finally {
            phase?.completionDispatchEnded(status)
        }
    }
}

/** A status cannot carry another manager's delegate, escape its original owner, or mint scoped savepoints. */
internal class PersistenceManagedStatus(
    private val manager: PlatformTransactionManager,
    internal val phase: PersistencePhaseContext?,
    internal val root: Boolean,
) : TransactionStatus {
    private val caller = Thread.currentThread()
    private var value: TransactionStatus? = null

    internal fun attach(status: TransactionStatus) {
        check(value == null)
        value = status
    }

    internal fun hasReturnedStatus(): Boolean = value != null
    internal fun native(): TransactionStatus = requireNotNull(value)

    internal fun requireOwner(expected: PlatformTransactionManager = manager) {
        if (expected !== manager || caller !== Thread.currentThread() || phase !== PersistencePhaseOwnership.current()) {
            throw PersistencePhaseException(PersistencePhaseFailureCode.MANAGER_REFUSED)
        }
    }

    override fun isNewTransaction(): Boolean = native().isNewTransaction
    override fun getTransactionName(): String = native().transactionName
    override fun hasTransaction(): Boolean = native().hasTransaction()
    override fun isNested(): Boolean = native().isNested
    override fun isReadOnly(): Boolean = native().isReadOnly
    override fun hasSavepoint(): Boolean = native().hasSavepoint()
    override fun isRollbackOnly(): Boolean = native().isRollbackOnly
    override fun isCompleted(): Boolean = native().isCompleted

    override fun setRollbackOnly() {
        requireOwner()
        phase?.requireParticipation()
        native().setRollbackOnly()
    }

    override fun flush() {
        requireOwner()
        phase?.requireParticipation()
        native().flush()
    }

    override fun createSavepoint(): Any {
        requireUnscopedSavepoint()
        return native().createSavepoint()
    }

    override fun rollbackToSavepoint(savepoint: Any) {
        requireUnscopedSavepoint()
        native().rollbackToSavepoint(savepoint)
    }

    override fun releaseSavepoint(savepoint: Any) {
        requireUnscopedSavepoint()
        native().releaseSavepoint(savepoint)
    }

    private fun requireUnscopedSavepoint() {
        requireOwner()
        if (phase != null) throw phase.failureException(PersistencePhaseFailureCode.MANAGER_REFUSED)
    }

    override fun toString(): String = "PersistenceManagedStatus(redacted)"
}
