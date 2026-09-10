package me.manga.kira.backend.common.infrastructure.persistence

/** Inert construction. The caller retains this owner before start and must request/observe shutdown separately. */
internal class PersistenceJdbcLifecycleOwner(endpoint: ResolvedPersistenceEndpoint, ordinaryCapacity: Int, pathStyle: PersistencePathStyle) {
    private val root = PersistenceJdbcDriverRoot(endpoint, ordinaryCapacity, pathStyle)

    fun start(): PersistenceLifecycleActivation = root.start()

    fun prepareDeletion(): PersistenceLifecycleActivation = root.prepareDeletion()

    internal fun prepareOrdinaryRequest(): PersistenceOwnedFactoryRequest = root.ordinary.prepareRequest()

    internal fun prepareDeletionRequest(): PersistenceOwnedFactoryRequest = root.deletion.prepareRequest()

    fun requestOrdinary(): PersistenceFactoryResult<PersistenceJdbcCandidate> = root.ordinary.request()

    fun requestDeletion(): PersistenceFactoryResult<PersistenceJdbcCandidate> = root.deletion.request()

    fun requestOrdinaryPoolConnection(): PersistenceFactoryResult<PhysicalJdbcFacade> = root.ordinary.requestPoolConnection()

    fun requestDeletionPoolConnection(): PersistenceFactoryResult<PhysicalJdbcFacade> = root.deletion.requestPoolConnection()

    fun requestShutdown(): Boolean = root.requestShutdown()

    fun observeOrdinaryPreparation(): PersistenceLifecycleObservation = PersistenceManagedObserver.observe(root, PersistenceManagedObservation.ORDINARY)

    fun observeDeletionPreparation(): PersistenceLifecycleObservation = PersistenceManagedObserver.observe(root, PersistenceManagedObservation.DELETION)

    fun observeShutdown(): PersistenceLifecycleObservation = PersistenceManagedObserver.observe(root, PersistenceManagedObservation.SHUTDOWN)

    fun snapshot(): PersistenceLifecycleSnapshot = root.snapshot()

    internal fun ownershipLockHeld(): Boolean = root.ownershipLockHeld()

    override fun toString(): String = "PersistenceJdbcLifecycleOwner(redacted)"
}
