package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.atomic.AtomicBoolean

/** Inert construction. The caller retains this owner before start and must request/observe shutdown separately. */
// Fixed lifecycle facade: non-interchangeable owned routes deliberately share one custody root.
@Suppress("TooManyFunctions")
internal class PersistenceJdbcLifecycleOwner private constructor(private val root: PersistenceJdbcDriverRoot) {
    constructor(endpoint: ResolvedPersistenceEndpoint, ordinaryCapacity: Int, pathStyle: PersistencePathStyle) :
        this(PersistenceJdbcDriverRoot(endpoint, ordinaryCapacity, pathStyle))

    internal val sourceOnly: Boolean get() = root.sourceOnly
    internal val desiredInstallationOperator: Boolean get() = root.desiredInstallationOperator
    internal val catalogGenesisAuthoring: Boolean get() = root.catalogGenesisAuthoring
    internal val catalogGenesisFinalization: Boolean get() = root.catalogGenesisFinalization
    internal val catalogSignerRotationRecovery: Boolean get() = root.catalogSignerRotationRecovery
    internal val catalogSignerRotationAuthoring: Boolean get() = root.catalogSignerRotationAuthoring
    internal val catalogSignerRotationDelivery: Boolean get() = root.catalogSignerRotationDelivery
    private val namedCatalogOnly: Boolean
        get() = desiredInstallationOperator || catalogGenesisAuthoring || catalogGenesisFinalization ||
            catalogSignerRotationRecovery || catalogSignerRotationAuthoring || catalogSignerRotationDelivery
    internal val versionBoundPools: VersionBoundPersistencePools? get() = root.versionBoundPools
    internal val epochRotation: EpochRotationPersistence? get() = root.epochRotation
    internal val complaintContainment = PersistenceComplaintContainment()
    private val catalogBindingClaimed = AtomicBoolean()

    @Volatile private var catalogResources: CatalogCoordinatorPersistence? = null

    /** Retain this owner first. The fixed composition/shells remain owned here even if cold construction throws. */
    internal fun bindVersionBoundPools(
        launchProfile: PersistencePoolLaunchProfile = PersistencePoolLaunchProfile.UNKNOWN,
        nanoClock: PersistenceNanoClock = SystemPersistenceNanoClock,
    ): VersionBoundPersistencePools {
        if (namedCatalogOnly) {
            rejectPersistenceBoundary(PersistenceBoundaryFailureCode.JDBC_CONFIGURATION_FAILED)
        }
        return (versionBoundPools ?: rejectPersistenceBoundary(PersistenceBoundaryFailureCode.JDBC_CONFIGURATION_FAILED)).bind(this, launchProfile, nanoClock)
    }

    /** Original scanner/Timer plus the original coordinator only. All other participant starts are permanently sealed. */
    internal fun prepareDesiredInstallationOperator(): PersistenceLifecycleObservation {
        requireConnectionFree()
        if (!desiredInstallationOperator || catalogResources == null || ownershipLockHeld()) return PersistenceLifecycleObservation.UNAVAILABLE
        if (root.startDesiredInstallationOperatorInfrastructure() !== PersistenceLifecycleActivation.STARTED) {
            return PersistenceLifecycleObservation.UNAVAILABLE
        }
        return checkNotNull(catalogResources).prepareDesiredInstallationOperator()
    }

    /** Same original driver/scanner/Timer and sole coordinator; all unrelated starts were sealed at construction. */
    internal fun prepareCatalogGenesisAuthoring(): PersistenceLifecycleObservation {
        requireConnectionFree()
        if (!catalogGenesisAuthoring || catalogResources == null || ownershipLockHeld()) return PersistenceLifecycleObservation.UNAVAILABLE
        if (root.startCatalogGenesisAuthoringInfrastructure() !== PersistenceLifecycleActivation.STARTED) {
            return PersistenceLifecycleObservation.UNAVAILABLE
        }
        return checkNotNull(catalogResources).prepareCatalogGenesisAuthoring()
    }

    /** Fixed TARGET route; no supplied launch profile and no change to retained pool descriptors. */
    internal fun bindCatalogGenesisFinalizationPools(nanoClock: PersistenceNanoClock = SystemPersistenceNanoClock): VersionBoundPersistencePools {
        if (!catalogGenesisFinalization) rejectPersistenceBoundary(PersistenceBoundaryFailureCode.JDBC_CONFIGURATION_FAILED)
        return checkNotNull(versionBoundPools).bind(this, PersistencePoolLaunchProfile.UNKNOWN, nanoClock)
    }

    internal fun prepareCatalogGenesisFinalization(): PersistenceLifecycleObservation {
        requireConnectionFree()
        if (!catalogGenesisFinalization || catalogResources == null || ownershipLockHeld()) return PersistenceLifecycleObservation.UNAVAILABLE
        if (root.startCatalogGenesisFinalizationInfrastructure() !== PersistenceLifecycleActivation.STARTED) {
            return PersistenceLifecycleObservation.UNAVAILABLE
        }
        return checkNotNull(catalogResources).prepareCatalogGenesisFinalization()
    }

    internal fun bindCatalogSignerRotationRecoveryPools(nanoClock: PersistenceNanoClock): VersionBoundPersistencePools {
        if (!catalogSignerRotationRecovery) rejectPersistenceBoundary(PersistenceBoundaryFailureCode.JDBC_CONFIGURATION_FAILED)
        return checkNotNull(versionBoundPools).bind(this, PersistencePoolLaunchProfile.UNKNOWN, nanoClock)
    }

    internal fun prepareCatalogSignerRotationRecovery(): PersistenceLifecycleObservation {
        requireConnectionFree()
        if (!catalogSignerRotationRecovery || catalogResources == null || ownershipLockHeld()) return PersistenceLifecycleObservation.UNAVAILABLE
        if (root.startCatalogSignerRotationRecoveryInfrastructure() !== PersistenceLifecycleActivation.STARTED) {
            return PersistenceLifecycleObservation.UNAVAILABLE
        }
        return checkNotNull(catalogResources).prepareCatalogSignerRotationRecovery()
    }

    internal fun bindCatalogSignerRotationAuthoringPools(nanoClock: PersistenceNanoClock): VersionBoundPersistencePools {
        if (!catalogSignerRotationAuthoring) rejectPersistenceBoundary(PersistenceBoundaryFailureCode.JDBC_CONFIGURATION_FAILED)
        return checkNotNull(versionBoundPools).bind(this, PersistencePoolLaunchProfile.UNKNOWN, nanoClock)
    }

    internal fun prepareCatalogSignerRotationAuthoring(): PersistenceLifecycleObservation {
        requireConnectionFree()
        if (!catalogSignerRotationAuthoring || catalogResources == null || ownershipLockHeld()) return PersistenceLifecycleObservation.UNAVAILABLE
        if (root.startCatalogSignerRotationAuthoringInfrastructure() !== PersistenceLifecycleActivation.STARTED) {
            return PersistenceLifecycleObservation.UNAVAILABLE
        }
        return checkNotNull(catalogResources).prepareCatalogSignerRotationAuthoring()
    }

    internal fun bindCatalogSignerRotationDeliveryPools(nanoClock: PersistenceNanoClock): VersionBoundPersistencePools {
        if (!catalogSignerRotationDelivery) rejectPersistenceBoundary(PersistenceBoundaryFailureCode.JDBC_CONFIGURATION_FAILED)
        return checkNotNull(versionBoundPools).bind(this, PersistencePoolLaunchProfile.UNKNOWN, nanoClock)
    }

    internal fun prepareCatalogSignerRotationDelivery(): PersistenceLifecycleObservation {
        requireConnectionFree()
        if (!catalogSignerRotationDelivery || catalogResources == null || ownershipLockHeld()) return PersistenceLifecycleObservation.UNAVAILABLE
        if (root.startCatalogSignerRotationDeliveryInfrastructure() !== PersistenceLifecycleActivation.STARTED) {
            return PersistenceLifecycleObservation.UNAVAILABLE
        }
        return checkNotNull(catalogResources).prepareCatalogSignerRotationDelivery()
    }

    /** One inert exact composition on THIS owner. No endpoint/capacity override, replacement or implicit start. */
    internal fun bindCatalogCoordinator(
        launchProfile: PersistencePoolLaunchProfile = PersistencePoolLaunchProfile.UNKNOWN,
        nanoClock: PersistenceNanoClock = SystemPersistenceNanoClock,
    ): CatalogCoordinatorPersistence {
        requireConnectionFree()
        if (sourceOnly || ownershipLockHeld() || root.shutdown.get()) {
            throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
        if (namedCatalogOnly && launchProfile !== PersistencePoolLaunchProfile.UNKNOWN) {
            throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
        versionBoundPools?.requireCatalogConstruction()
        if (!catalogBindingClaimed.compareAndSet(false, true)) {
            throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
        val prepared = versionBoundPools?.prepareCatalog(this, launchProfile) ?: CatalogCoordinatorPersistence.prepare(this, root.endpoint, launchProfile)
        catalogResources = prepared // Retain before phase/resource binding can fail; never replace a failed composition.
        prepared.bindOwnership(nanoClock)
        return prepared
    }

    internal fun ownsCatalogResources(resources: CatalogCoordinatorPersistence): Boolean = catalogResources === resources

    internal fun ownsCatalogDataSource(dataSource: GuardedDataSource): Boolean = catalogResources?.dataSource === dataSource

    internal fun ownsCatalogLifecycle(lifecycle: PoolLifecycle): Boolean = catalogResources?.dataSource?.ownsLifecycle(lifecycle) == true

    internal fun ownsOrdinaryPoolIdentity(identity: PersistenceJdbcPoolIdentity): Boolean = root.ordinary.ownsPoolIdentity(identity)

    internal fun ownsDeletionPoolIdentity(identity: PersistenceJdbcPoolIdentity): Boolean = root.deletion.ownsPoolIdentity(identity)

    internal fun ownsCatalogPoolIdentity(identity: PersistenceJdbcPoolIdentity): Boolean = root.catalogCoordinator.ownsPoolIdentity(identity)

    fun start(): PersistenceLifecycleActivation = root.start()

    fun prepareDeletion(): PersistenceLifecycleActivation = root.prepareDeletion()

    internal fun prepareCatalogCoordinator(): PersistenceLifecycleActivation = root.prepareCatalogCoordinator()

    internal fun prepareOrdinaryRequest(): PersistenceOwnedFactoryRequest = root.ordinary.prepareRequest()

    internal fun prepareDeletionRequest(): PersistenceOwnedFactoryRequest = root.deletion.prepareRequest()

    fun requestOrdinary(): PersistenceFactoryResult<PersistenceJdbcCandidate> = root.ordinary.request()

    fun requestDeletion(): PersistenceFactoryResult<PersistenceJdbcCandidate> = root.deletion.request()

    fun requestOrdinaryPoolConnection(): PersistenceFactoryResult<PhysicalJdbcFacade> = root.ordinary.requestPoolConnection()

    fun requestDeletionPoolConnection(): PersistenceFactoryResult<PhysicalJdbcFacade> = root.deletion.requestPoolConnection()

    internal fun requestCatalogCoordinatorPoolConnection(): PersistenceFactoryResult<PhysicalJdbcFacade> = root.catalogCoordinator.requestPoolConnection()

    fun requestShutdown(): Boolean = root.requestShutdown()

    /** Explicit blocking material preparation after this inert owner has been retained. No activation. */
    internal fun preparePublicTrust(): PersistencePublicTrustPreparation = root.preparePublicTrust()

    /** Explicit filesystem work outside observers; request/close/local drain is not release authority. */
    internal fun releasePublicTrustAfterShutdown(): PersistencePublicTrustRelease = root.releasePublicTrustAfterShutdown()

    /** Does not release this root's shared Timer pin or stop its ordinary participant/scanner. */
    internal fun requestDeletionShutdown(): Boolean = root.requestDeletionShutdown()

    internal fun requestCatalogCoordinatorShutdown(): Boolean = root.requestCatalogCoordinatorShutdown()

    internal fun observeCatalogCoordinatorShutdown(): PersistenceLifecycleObservation =
        PersistenceManagedObserver.observe(root, PersistenceManagedObservation.CATALOG_COORDINATOR_SHUTDOWN)

    internal fun observeCatalogCoordinatorShutdown(budget: PersistenceTimeBudget): PersistenceLifecycleObservation =
        PersistenceManagedObserver.observe(root, PersistenceManagedObservation.CATALOG_COORDINATOR_SHUTDOWN, budget)

    internal fun observeCatalogCoordinatorPreparation(): PersistenceLifecycleObservation =
        PersistenceManagedObserver.observe(root, PersistenceManagedObservation.CATALOG_COORDINATOR)

    internal fun observeCatalogCoordinatorPreparation(budget: PersistenceTimeBudget): PersistenceLifecycleObservation =
        PersistenceManagedObserver.observe(root, PersistenceManagedObservation.CATALOG_COORDINATOR, budget)

    internal fun catalogCoordinatorPoolIdle(lifecycle: PoolLifecycle): Boolean = root.catalogCoordinator.catalogCoordinatorPoolIdle(lifecycle)

    internal fun observeDeletionShutdown(): PersistenceLifecycleObservation =
        PersistenceManagedObserver.observe(root, PersistenceManagedObservation.DELETION_SHUTDOWN)

    internal fun observeDeletionShutdown(budget: PersistenceTimeBudget): PersistenceLifecycleObservation =
        PersistenceManagedObserver.observe(root, PersistenceManagedObservation.DELETION_SHUTDOWN, budget)

    fun observeOrdinaryPreparation(): PersistenceLifecycleObservation = PersistenceManagedObserver.observe(root, PersistenceManagedObservation.ORDINARY)

    fun observeDeletionPreparation(): PersistenceLifecycleObservation = PersistenceManagedObserver.observe(root, PersistenceManagedObservation.DELETION)

    internal fun observeDeletionPreparation(budget: PersistenceTimeBudget): PersistenceLifecycleObservation =
        PersistenceManagedObserver.observe(root, PersistenceManagedObservation.DELETION, budget)

    internal fun deletionPoolIdle(lifecycle: PoolLifecycle): Boolean = root.deletion.deletionPoolIdle(lifecycle)

    fun observeShutdown(): PersistenceLifecycleObservation = PersistenceManagedObserver.observe(root, PersistenceManagedObservation.SHUTDOWN)

    internal fun observeShutdown(budget: PersistenceTimeBudget): PersistenceLifecycleObservation =
        PersistenceManagedObserver.observe(root, PersistenceManagedObservation.SHUTDOWN, budget)

    fun snapshot(): PersistenceLifecycleSnapshot = root.snapshot()

    internal fun ownershipLockHeld(): Boolean = root.ownershipLockHeld()

    override fun toString(): String = "PersistenceJdbcLifecycleOwner(redacted)"

    companion object {
        /** Original provider settings, owned-driver accounting, no native eligibility or deletion activation. */
        internal fun sourceOnly(endpoint: ResolvedPersistenceEndpoint, ordinaryCapacity: Int, pathStyle: PersistencePathStyle): PersistenceJdbcLifecycleOwner =
            PersistenceJdbcLifecycleOwner(PersistenceJdbcDriverRoot(endpoint, ordinaryCapacity, pathStyle, sourceOnly = true))

        internal fun versionBound(configuration: VersionBoundPersistenceConfiguration): PersistenceJdbcLifecycleOwner =
            PersistenceJdbcLifecycleOwner(configuration.createRoot())

        internal fun versionBoundWithEpochRotation(configuration: VersionBoundPersistenceConfiguration): PersistenceJdbcLifecycleOwner =
            PersistenceJdbcLifecycleOwner(configuration.createRootWithEpochRotation())

        internal fun desiredInstallationOperator(configuration: VersionBoundPersistenceConfiguration): PersistenceJdbcLifecycleOwner =
            PersistenceJdbcLifecycleOwner(configuration.createDesiredInstallationOperatorRoot())

        internal fun catalogGenesisFinalization(configuration: VersionBoundPersistenceConfiguration): PersistenceJdbcLifecycleOwner =
            PersistenceJdbcLifecycleOwner(configuration.createCatalogGenesisFinalizationRoot())

        internal fun catalogGenesisFinalizationWithEpochRotation(configuration: VersionBoundPersistenceConfiguration): PersistenceJdbcLifecycleOwner =
            PersistenceJdbcLifecycleOwner(configuration.createCatalogGenesisFinalizationRootWithEpochRotation())

        internal fun catalogGenesisAuthoring(configuration: VersionBoundPersistenceConfiguration): PersistenceJdbcLifecycleOwner =
            PersistenceJdbcLifecycleOwner(configuration.createCatalogGenesisAuthoringRoot())

        internal fun catalogSignerRotationAuthoring(
            configuration: VersionBoundPersistenceConfiguration,
            epochRotation: Boolean,
        ): PersistenceJdbcLifecycleOwner = PersistenceJdbcLifecycleOwner(configuration.createCatalogSignerRotationAuthoringRoot(epochRotation))

        internal fun catalogSignerRotationRecovery(
            configuration: VersionBoundPersistenceConfiguration,
            epochRotation: Boolean,
        ): PersistenceJdbcLifecycleOwner = PersistenceJdbcLifecycleOwner(configuration.createCatalogSignerRotationRecoveryRoot(epochRotation))

        internal fun catalogSignerRotationDelivery(
            configuration: VersionBoundPersistenceConfiguration,
            epochRotation: Boolean,
        ): PersistenceJdbcLifecycleOwner = PersistenceJdbcLifecycleOwner(configuration.createCatalogSignerRotationDeliveryRoot(epochRotation))
    }
}
