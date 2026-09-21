package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogCoordinatorLeaseCustodyV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogCutoffPublicationsV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogEpochRotationCustodyV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogEpochRotationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogReadbackRefreshCustodyV1
import me.manga.kira.backend.complaint.infrastructure.catalog.JdbcCatalogSnapshotReader
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintCatalogGenesisPersistencePhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintCatalogGenesisPublishRecheckPhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintCatalogProjectedHeadPhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintCatalogSignerRotationActivationPhaseExecutorV1
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintCatalogSignerRotationFinalizationPhaseExecutorV1
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintCatalogSignerRotationPersistencePhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintCatalogTestRunActivationPhaseExecutorV1
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintTestNamespaceRegistrationPhaseExecutorV1
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintTestInitialAdmissionPhaseExecutorV1
import me.manga.kira.backend.complaint.infrastructure.transaction.TestActiveFirstCutPhaseExecutorV1
import me.manga.kira.backend.complaint.infrastructure.transaction.TestActiveFirstCutSuccessorPhaseExecutorV1
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintTestNamespaceRecoveryRegistrationPhaseExecutorV1
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintTestNamespaceActiveRegistrationPhaseExecutorV1
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintTestRunSealingPhaseExecutorV1
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintTestActiveOrdinarySealPhaseExecutorV1
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintTestOrdinarySealPhaseExecutorV1
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintTestInstallationManifestPhaseExecutorV1
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintTestInstallationManifestPublicationPhaseExecutorV1
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintTestRunPurgePhaseExecutorV1
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintTestOrdinaryDrainPhaseExecutorV1
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintCatalogSnapshotPhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintCoordinatorLeasePersistencePhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintDesiredInstallPhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintSignedGenesisFirstDPhaseExecutor
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator
import java.util.concurrent.atomic.AtomicBoolean

/** Fixed dormant coordinator tuple. Its sole permit never comes from an ordinary/deletion budget. */
internal class CatalogCoordinatorPersistence private constructor(
    private val owner: PersistenceJdbcLifecycleOwner,
    internal val dataSource: GuardedDataSource,
) : AutoCloseable {
    internal val desiredInstallationOperator: Boolean get() = owner.desiredInstallationOperator
    internal val catalogGenesisAuthoring: Boolean get() = owner.catalogGenesisAuthoring
    internal val catalogGenesisFinalization: Boolean get() = owner.catalogGenesisFinalization
    internal val catalogSignerRotationRecovery: Boolean get() = owner.catalogSignerRotationRecovery
    internal val catalogSignerRotationAuthoring: Boolean get() = owner.catalogSignerRotationAuthoring
    internal val catalogSignerRotationDelivery: Boolean get() = owner.catalogSignerRotationDelivery
    internal val catalogSignerRotationActivation: Boolean get() = owner.catalogSignerRotationActivation
    internal val catalogTestRunActivation: Boolean get() = owner.catalogTestRunActivation
    internal val manager = GuardedJdbcTransactionManager(dataSource)
    internal val catalogRefreshCustody = CatalogReadbackRefreshCustodyV1()
    internal val leaseCustody = CatalogCoordinatorLeaseCustodyV1(this)
    internal val epochRotationCustody = CatalogEpochRotationCustodyV1(this)
    private val admitted = AtomicBoolean()
    private val bindingClaimed = AtomicBoolean()
    private var phaseOwner: PersistencePhaseOwnership? = null
    private var executor: ComplaintCatalogSnapshotPhaseExecutor? = null
    private var genesisExecutor: ComplaintCatalogGenesisPersistencePhaseExecutor? = null
    private var projectedHeadExecutor: ComplaintCatalogProjectedHeadPhaseExecutor? = null
    private var leaseExecutor: ComplaintCoordinatorLeasePersistencePhaseExecutor? = null
    private var rotationExecutor: CatalogEpochRotationV1? = null
    private var cutoffExecutor: CatalogCutoffPublicationsV1? = null
    private var desiredExecutor: ComplaintDesiredInstallPhaseExecutor? = null
    private var firstDesiredExecutor: ComplaintSignedGenesisFirstDPhaseExecutor? = null
    private var publishRecheckExecutor: ComplaintCatalogGenesisPublishRecheckPhaseExecutor? = null
    private var signerRotationExecutor: ComplaintCatalogSignerRotationPersistencePhaseExecutor? = null

    private var signerRotationFinalizationExecutor: ComplaintCatalogSignerRotationFinalizationPhaseExecutorV1? = null
    private var signerRotationActivationExecutor: ComplaintCatalogSignerRotationActivationPhaseExecutorV1? = null

    private var testRunActivationExecutor: ComplaintCatalogTestRunActivationPhaseExecutorV1? = null
    private var testActiveFirstCutExecutor: TestActiveFirstCutPhaseExecutorV1? = null
    private var testActiveFirstCutSuccessorExecutor: TestActiveFirstCutSuccessorPhaseExecutorV1? = null
    internal val testActiveFirstCut: TestActiveFirstCutPhaseExecutorV1 get() = checkNotNull(testActiveFirstCutExecutor)
    internal val testActiveFirstCutSuccessor: TestActiveFirstCutSuccessorPhaseExecutorV1 get() = checkNotNull(testActiveFirstCutSuccessorExecutor)
    private var testInitialAdmissionExecutor: ComplaintTestInitialAdmissionPhaseExecutorV1? = null
    internal val testInitialAdmission: ComplaintTestInitialAdmissionPhaseExecutorV1 get() = checkNotNull(testInitialAdmissionExecutor)
    private var testRegistrationExecutor: ComplaintTestNamespaceRegistrationPhaseExecutorV1? = null
    private var testInstallationManifestPublicationExecutor: ComplaintTestInstallationManifestPublicationPhaseExecutorV1? = null
    internal val testInstallationManifestPublication: ComplaintTestInstallationManifestPublicationPhaseExecutorV1 get() = checkNotNull(testInstallationManifestPublicationExecutor)
    private var testRunPurgeExecutor: ComplaintTestRunPurgePhaseExecutorV1? = null
    internal val testRunPurge: ComplaintTestRunPurgePhaseExecutorV1 get() = checkNotNull(testRunPurgeExecutor)
    private var testRecoveryRegistrationExecutor: ComplaintTestNamespaceRecoveryRegistrationPhaseExecutorV1? = null
    private var testActiveRegistrationExecutor: ComplaintTestNamespaceActiveRegistrationPhaseExecutorV1? = null
    private var testInstallationManifestExecutor: ComplaintTestInstallationManifestPhaseExecutorV1? = null
    internal val testInstallationManifest: ComplaintTestInstallationManifestPhaseExecutorV1 get() = checkNotNull(testInstallationManifestExecutor)
    private var testActiveOrdinarySealExecutor: ComplaintTestActiveOrdinarySealPhaseExecutorV1? = null
    internal val testActiveOrdinarySeal: ComplaintTestActiveOrdinarySealPhaseExecutorV1 get() = checkNotNull(testActiveOrdinarySealExecutor)
    private var testOrdinarySealExecutor: ComplaintTestOrdinarySealPhaseExecutorV1? = null
    internal val testOrdinarySeal: ComplaintTestOrdinarySealPhaseExecutorV1 get() = checkNotNull(testOrdinarySealExecutor)
    private var testOrdinaryDrainExecutor: ComplaintTestOrdinaryDrainPhaseExecutorV1? = null
    internal val testOrdinaryDrain: ComplaintTestOrdinaryDrainPhaseExecutorV1 get() = checkNotNull(testOrdinaryDrainExecutor)
    private var testRunSealingExecutor: ComplaintTestRunSealingPhaseExecutorV1? = null
    internal val testNamespaceRegistration: ComplaintTestNamespaceRegistrationPhaseExecutorV1 get() = checkNotNull(testRegistrationExecutor)
    internal val testNamespaceRecoveryRegistration: ComplaintTestNamespaceRecoveryRegistrationPhaseExecutorV1 get() = checkNotNull(testRecoveryRegistrationExecutor)
    internal val testNamespaceActiveRegistration: ComplaintTestNamespaceActiveRegistrationPhaseExecutorV1 get() = checkNotNull(testActiveRegistrationExecutor)
    internal val testRunSealing: ComplaintTestRunSealingPhaseExecutorV1 get() = checkNotNull(testRunSealingExecutor)
    internal val testRunActivation: ComplaintCatalogTestRunActivationPhaseExecutorV1 get() = checkNotNull(testRunActivationExecutor)

    internal val ownership: PersistencePhaseOwnership get() = checkNotNull(phaseOwner)
    internal val snapshot: ComplaintCatalogSnapshotPhaseExecutor get() = checkNotNull(executor)
    internal val genesis: ComplaintCatalogGenesisPersistencePhaseExecutor get() = checkNotNull(genesisExecutor)
    internal val projectedHead: ComplaintCatalogProjectedHeadPhaseExecutor get() = checkNotNull(projectedHeadExecutor)
    internal val lease: ComplaintCoordinatorLeasePersistencePhaseExecutor get() = checkNotNull(leaseExecutor)
    internal val epochRotation: CatalogEpochRotationV1 get() = checkNotNull(rotationExecutor)
    internal val cutoffPublications: CatalogCutoffPublicationsV1 get() = checkNotNull(cutoffExecutor)
    internal val desiredInstallation: ComplaintDesiredInstallPhaseExecutor get() = checkNotNull(desiredExecutor)
    internal val signedGenesisFirstDesired: ComplaintSignedGenesisFirstDPhaseExecutor get() = checkNotNull(firstDesiredExecutor)
    internal val catalogGenesisPublishRecheck: ComplaintCatalogGenesisPublishRecheckPhaseExecutor get() = checkNotNull(publishRecheckExecutor)
    internal val signerRotation: ComplaintCatalogSignerRotationPersistencePhaseExecutor get() = checkNotNull(signerRotationExecutor)

    internal val signerRotationFinalization: ComplaintCatalogSignerRotationFinalizationPhaseExecutorV1
        get() = checkNotNull(signerRotationFinalizationExecutor)

    internal val signerRotationActivation: ComplaintCatalogSignerRotationActivationPhaseExecutorV1
        get() = checkNotNull(signerRotationActivationExecutor)

    internal fun bindOwnership(nanoClock: PersistenceNanoClock) {
        requireResources()
        check(bindingClaimed.compareAndSet(false, true))
        val bound = PersistencePhaseOwnership.catalogCoordinator(this, nanoClock)
        phaseOwner = bound
        val jdbc = JdbcTemplate(dataSource).apply { exceptionTranslator = SQLExceptionSubclassTranslator() }
        if (catalogTestRunActivation) {
            testRunActivationExecutor = ComplaintCatalogTestRunActivationPhaseExecutorV1(this, jdbc)
            return // Only dedicated TEST snapshot/lease/PREPARE/reload. No old snapshot, LIVE lease or producer effects.
        }
        if (desiredInstallationOperator) {
            desiredExecutor = ComplaintDesiredInstallPhaseExecutor(this, jdbc)
            firstDesiredExecutor = ComplaintSignedGenesisFirstDPhaseExecutor(this, jdbc)
            publishRecheckExecutor = ComplaintCatalogGenesisPublishRecheckPhaseExecutor(this, jdbc)
            return // No catalog writer, lease/rotation or readback executor is constructed on the operator root.
        }
        executor = ComplaintCatalogSnapshotPhaseExecutor(bound, JdbcCatalogSnapshotReader(jdbc))
        if (catalogSignerRotationActivation) {
            leaseExecutor = ComplaintCoordinatorLeasePersistencePhaseExecutor(this, jdbc)
            signerRotationActivationExecutor = ComplaintCatalogSignerRotationActivationPhaseExecutorV1(this, jdbc)
            return // Only fixed3 effects; no G1, overlap2, ordinary projection, epoch or cutoff writer exists on this fixed activation root.
        }
        if (catalogSignerRotationDelivery) {
            leaseExecutor = ComplaintCoordinatorLeasePersistencePhaseExecutor(this, jdbc)
            signerRotationFinalizationExecutor = ComplaintCatalogSignerRotationFinalizationPhaseExecutorV1(this, jdbc)
            return // No G1, Sign, PREPARE, ordinary projection, epoch or cutoff writer exists on this fixed delivery root.
        }
        if (catalogSignerRotationRecovery) {
            leaseExecutor = ComplaintCoordinatorLeasePersistencePhaseExecutor(this, jdbc)
            signerRotationExecutor = ComplaintCatalogSignerRotationPersistencePhaseExecutor(this, jdbc)
            return // No G1, projection, epoch/cutoff or ordinary writer executor on the fixed recovery root.
        }
        genesisExecutor = ComplaintCatalogGenesisPersistencePhaseExecutor(bound, jdbc)
        if (catalogSignerRotationAuthoring) {
            leaseExecutor = ComplaintCoordinatorLeasePersistencePhaseExecutor(this, jdbc)
            signerRotationExecutor = ComplaintCatalogSignerRotationPersistencePhaseExecutor(this, jdbc)
            return // Exact G1 refresh -> ACQUIRE -> initial overlap author; never a projected/epoch/cutoff executor.
        }
        if (catalogGenesisAuthoring || catalogGenesisFinalization) return // Only the named attempt may select its three fixed phases.
        testRegistrationExecutor = ComplaintTestNamespaceRegistrationPhaseExecutorV1(this)
        testInitialAdmissionExecutor = ComplaintTestInitialAdmissionPhaseExecutorV1(this)
        testActiveFirstCutExecutor = TestActiveFirstCutPhaseExecutorV1(this)
        testActiveFirstCutSuccessorExecutor = TestActiveFirstCutSuccessorPhaseExecutorV1(this)
        testRecoveryRegistrationExecutor = ComplaintTestNamespaceRecoveryRegistrationPhaseExecutorV1(this)
        testActiveRegistrationExecutor = ComplaintTestNamespaceActiveRegistrationPhaseExecutorV1(this)
        testRunSealingExecutor = ComplaintTestRunSealingPhaseExecutorV1(this)
        testOrdinarySealExecutor = ComplaintTestOrdinarySealPhaseExecutorV1(this)
        testActiveOrdinarySealExecutor = ComplaintTestActiveOrdinarySealPhaseExecutorV1(this)
        testInstallationManifestExecutor = ComplaintTestInstallationManifestPhaseExecutorV1(this)
        testInstallationManifestPublicationExecutor = ComplaintTestInstallationManifestPublicationPhaseExecutorV1(this)
        testRunPurgeExecutor = ComplaintTestRunPurgePhaseExecutorV1(this)
        testOrdinaryDrainExecutor = ComplaintTestOrdinaryDrainPhaseExecutorV1(this)
        projectedHeadExecutor = ComplaintCatalogProjectedHeadPhaseExecutor(this, jdbc)
        leaseExecutor = ComplaintCoordinatorLeasePersistencePhaseExecutor(this, jdbc)
        rotationExecutor = CatalogEpochRotationV1(this, jdbc)
        cutoffExecutor = CatalogCutoffPublicationsV1(this, jdbc)
        signerRotationExecutor = ComplaintCatalogSignerRotationPersistencePhaseExecutor(this, jdbc)
    }

    internal fun requireResources() {
        if (!owner.ownsCatalogResources(this) || manager.dataSource !== dataSource) {
            throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
        dataSource.requireCatalogCoordinatorPhaseResource()
    }

    internal fun tryPhaseAdmission(): LocalPersistencePermit? {
        requireResources()
        if (!admitted.compareAndSet(false, true)) return null
        return LocalPersistencePermit { check(admitted.compareAndSet(true, false)) }
    }

    /** Same sole slot for snapshot, G1 and row-only lease phases; never a physical/native or shutdown receipt. */
    internal fun activeSnapshotOwners(): Int = if (admitted.get()) 1 else 0

    fun prepare(): PersistenceLifecycleObservation {
        requireResources()
        if (requiresNamedPreparation()) {
            return PersistenceLifecycleObservation.UNAVAILABLE
        }
        checkNotNull(executor)
        return dataSource.prepareCatalogCoordinator()
    }

    private fun requiresNamedPreparation(): Boolean =
        desiredInstallationOperator || catalogGenesisAuthoring || catalogGenesisFinalization || catalogSignerRotationRecovery ||
            catalogSignerRotationAuthoring || catalogSignerRotationDelivery || catalogSignerRotationActivation || catalogTestRunActivation

    /** Only the named operator owner can start the infrastructure for this route. */
    internal fun prepareDesiredInstallationOperator(): PersistenceLifecycleObservation {
        requireResources()
        if (!desiredInstallationOperator) return PersistenceLifecycleObservation.UNAVAILABLE
        checkNotNull(desiredExecutor)
        return dataSource.prepareCatalogCoordinator()
    }

    internal fun prepareCatalogGenesisAuthoring(): PersistenceLifecycleObservation {
        requireResources()
        if (!catalogGenesisAuthoring) return PersistenceLifecycleObservation.UNAVAILABLE
        checkNotNull(genesisExecutor)
        return dataSource.prepareCatalogCoordinator()
    }

    internal fun prepareCatalogGenesisFinalization(): PersistenceLifecycleObservation {
        requireResources()
        if (!catalogGenesisFinalization) return PersistenceLifecycleObservation.UNAVAILABLE
        checkNotNull(genesisExecutor)
        return dataSource.prepareCatalogCoordinator()
    }

    internal fun prepareCatalogSignerRotationRecovery(): PersistenceLifecycleObservation {
        requireResources()
        if (!catalogSignerRotationRecovery) return PersistenceLifecycleObservation.UNAVAILABLE
        checkNotNull(executor)
        checkNotNull(leaseExecutor)
        checkNotNull(signerRotationExecutor)
        return dataSource.prepareCatalogCoordinator()
    }

    internal fun prepareCatalogSignerRotationAuthoring(): PersistenceLifecycleObservation {
        requireResources()
        if (!catalogSignerRotationAuthoring) return PersistenceLifecycleObservation.UNAVAILABLE
        checkNotNull(executor)
        checkNotNull(genesisExecutor)
        checkNotNull(leaseExecutor)
        checkNotNull(signerRotationExecutor)
        return dataSource.prepareCatalogCoordinator()
    }

    internal fun prepareCatalogSignerRotationDelivery(): PersistenceLifecycleObservation {
        requireResources()
        if (!catalogSignerRotationDelivery) return PersistenceLifecycleObservation.UNAVAILABLE
        checkNotNull(executor)
        checkNotNull(leaseExecutor)
        checkNotNull(signerRotationFinalizationExecutor)
        return dataSource.prepareCatalogCoordinator()
    }

    internal fun prepareCatalogSignerRotationActivation(): PersistenceLifecycleObservation {
        requireResources()
        if (!catalogSignerRotationActivation) return PersistenceLifecycleObservation.UNAVAILABLE
        checkNotNull(executor)
        checkNotNull(leaseExecutor)
        checkNotNull(signerRotationActivationExecutor)
        return dataSource.prepareCatalogCoordinator()
    }

    internal fun prepareCatalogTestRunActivation(): PersistenceLifecycleObservation {
        requireResources()
        if (!catalogTestRunActivation) return PersistenceLifecycleObservation.UNAVAILABLE
        checkNotNull(testRunActivationExecutor)
        return dataSource.prepareCatalogCoordinator()
    }

    fun observePreparation(): PersistenceLifecycleObservation = dataSource.observePreparation()

    fun requestShutdown(): PoolLifecycle.ShutdownReceipt? = dataSource.requestShutdown()

    fun shutdownInvocation(): PoolShutdownInvocation = dataSource.shutdownInvocation()

    override fun close() = dataSource.close()

    override fun toString(): String = "CatalogCoordinatorPersistence(dormant,one-slot,no-authority)"

    companion object {
        internal fun prepare(
            owner: PersistenceJdbcLifecycleOwner,
            endpoint: ResolvedPersistenceEndpoint,
            launchProfile: PersistencePoolLaunchProfile,
        ): CatalogCoordinatorPersistence = CatalogCoordinatorPersistence(owner, GuardedDataSource.catalogCoordinator(owner, endpoint, launchProfile))

        internal fun versionBound(
            owner: PersistenceJdbcLifecycleOwner,
            binding: VersionBoundPersistencePoolBinding,
            launchProfile: PersistencePoolLaunchProfile,
        ): CatalogCoordinatorPersistence = CatalogCoordinatorPersistence(owner, binding.construct(owner, launchProfile))
    }
}
