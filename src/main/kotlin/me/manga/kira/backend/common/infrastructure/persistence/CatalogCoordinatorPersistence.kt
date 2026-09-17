package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogCoordinatorLeaseCustodyV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogEpochRotationCustodyV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogEpochRotationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogReadbackRefreshCustodyV1
import me.manga.kira.backend.complaint.infrastructure.catalog.JdbcCatalogSnapshotReader
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintCatalogGenesisPersistencePhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintCatalogSnapshotPhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintCoordinatorLeasePersistencePhaseExecutor
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator
import java.util.concurrent.atomic.AtomicBoolean

/** Fixed dormant coordinator tuple. Its sole permit never comes from an ordinary/deletion budget. */
internal class CatalogCoordinatorPersistence private constructor(
    private val owner: PersistenceJdbcLifecycleOwner,
    internal val dataSource: GuardedDataSource,
) : AutoCloseable {
    internal val manager = GuardedJdbcTransactionManager(dataSource)
    internal val catalogRefreshCustody = CatalogReadbackRefreshCustodyV1()
    internal val leaseCustody = CatalogCoordinatorLeaseCustodyV1(this)
    internal val epochRotationCustody = CatalogEpochRotationCustodyV1(this)
    private val admitted = AtomicBoolean()
    private val bindingClaimed = AtomicBoolean()
    private var phaseOwner: PersistencePhaseOwnership? = null
    private var executor: ComplaintCatalogSnapshotPhaseExecutor? = null
    private var genesisExecutor: ComplaintCatalogGenesisPersistencePhaseExecutor? = null
    private var leaseExecutor: ComplaintCoordinatorLeasePersistencePhaseExecutor? = null
    private var rotationExecutor: CatalogEpochRotationV1? = null

    internal val ownership: PersistencePhaseOwnership get() = checkNotNull(phaseOwner)
    internal val snapshot: ComplaintCatalogSnapshotPhaseExecutor get() = checkNotNull(executor)
    internal val genesis: ComplaintCatalogGenesisPersistencePhaseExecutor get() = checkNotNull(genesisExecutor)
    internal val lease: ComplaintCoordinatorLeasePersistencePhaseExecutor get() = checkNotNull(leaseExecutor)
    internal val epochRotation: CatalogEpochRotationV1 get() = checkNotNull(rotationExecutor)

    internal fun bindOwnership(nanoClock: PersistenceNanoClock) {
        requireResources()
        check(bindingClaimed.compareAndSet(false, true))
        val bound = PersistencePhaseOwnership.catalogCoordinator(this, nanoClock)
        phaseOwner = bound
        val jdbc = JdbcTemplate(dataSource).apply { exceptionTranslator = SQLExceptionSubclassTranslator() }
        executor = ComplaintCatalogSnapshotPhaseExecutor(bound, JdbcCatalogSnapshotReader(jdbc))
        genesisExecutor = ComplaintCatalogGenesisPersistencePhaseExecutor(bound, jdbc)
        leaseExecutor = ComplaintCoordinatorLeasePersistencePhaseExecutor(this, jdbc)
        rotationExecutor = CatalogEpochRotationV1(this, jdbc)
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
        checkNotNull(executor)
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
