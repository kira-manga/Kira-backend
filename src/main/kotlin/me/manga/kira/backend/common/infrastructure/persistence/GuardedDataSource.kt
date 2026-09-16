package me.manga.kira.backend.common.infrastructure.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.io.PrintWriter
import java.sql.Connection
import java.sql.SQLException
import java.sql.SQLFeatureNotSupportedException
import java.util.logging.Logger
import javax.sql.DataSource

/**
 * This guarded identity retains the private lower factory, one inert stock Hikari, and its
 * actor/lifecycle owner before initialization. The raw pool/factory are never Spring beans.
 * CONTROLLED_TEST_ONLY is a fixture selection, not evidence approving a production launch image.
 */
internal class GuardedDataSource private constructor(
    private val owner: PersistenceJdbcLifecycleOwner,
    private val endpoint: ResolvedPersistenceEndpoint,
    maximumPoolSize: Int,
    private val launchProfile: PersistencePoolLaunchProfile,
    private val route: Route,
    ordinarySettings: HikariConfig? = null,
) : DataSource,
    AutoCloseable {
    constructor(
        owner: PersistenceJdbcLifecycleOwner,
        endpoint: ResolvedPersistenceEndpoint,
        maximumPoolSize: Int,
        launchProfile: PersistencePoolLaunchProfile = PersistencePoolLaunchProfile.UNKNOWN,
    ) : this(owner, endpoint, maximumPoolSize, launchProfile, Route.ORDINARY)

    private val sourceOnly = ordinarySettings != null
    private val pool = HikariDataSource()
    private val lifecycle = when (route) {
        Route.ORDINARY -> if (sourceOnly) PoolLifecycle.sourceOnly(pool, owner) else PoolLifecycle(pool, owner)
        Route.DELETION -> PoolLifecycle.deletion(pool, owner)
        Route.CATALOG_COORDINATOR -> PoolLifecycle.catalogCoordinator(pool, owner)
    }
    private val lower = when (route) {
        Route.ORDINARY -> PrivateJdbcDataSource(owner, endpoint, lifecycle)
        Route.DELETION -> PrivateJdbcDataSource.deletion(owner, endpoint, lifecycle)
        Route.CATALOG_COORDINATOR -> PrivateJdbcDataSource.catalogCoordinator(owner, endpoint, lifecycle)
    }
    private val deletionPreparation = if (route === Route.DELETION) DeletionPoolPreparation(owner, pool, lifecycle) else null
    private val catalogPreparation = if (route === Route.CATALOG_COORDINATOR) CatalogCoordinatorPoolPreparation(owner, pool, lifecycle) else null
    private val loginPolicy = when (route) {
        Route.ORDINARY -> endpoint.loginPolicy
        Route.DELETION -> PersistenceNativeSettings.deletionLoginPolicy
        Route.CATALOG_COORDINATOR -> PersistenceNativeSettings.catalogCoordinatorLoginPolicy
    }
    private val checkoutMillis = when {
        route === Route.DELETION -> 500L
        route === Route.CATALOG_COORDINATOR -> 250L
        ordinarySettings != null -> ordinarySettings.connectionTimeout
        else -> endpoint.loginPolicy.durationMillis
    }
    private val closedDeletion by lazy { deletion(owner, endpoint) }

    init {
        require(maximumPoolSize > 0)
        if (ordinarySettings != null) {
            check(route === Route.ORDINARY && owner.sourceOnly)
            ordinarySettings.copyStateTo(pool) // Public Hikari API; endpoint/credentials were already removed.
        } else {
            pool.maximumPoolSize = maximumPoolSize
            pool.minimumIdle = when (route) {
                Route.ORDINARY -> 0
                Route.DELETION -> 4
                Route.CATALOG_COORDINATOR -> 1
            }
            pool.initializationFailTimeout = -1
            pool.connectionTimeout = maxOf(250L, checkoutMillis)
            pool.validationTimeout = if (route !== Route.ORDINARY) 250L else minOf(pool.connectionTimeout, 5_000L)
            pool.isRegisterMbeans = false
        }
        pool.dataSource = lower
        if (route !== Route.ORDINARY) {
            pool.isAutoCommit = true
            pool.isAllowPoolSuspension = false
        }
        // Source-only settings preserve Boot's ordinary defaults/customizations, not the test
        // fixture's minIdle=0/failFast=-1 profile. Neither route grants native qualification.
        if (sourceOnly) pool.validate()
        if ((sourceOnly || launchProfile === PersistencePoolLaunchProfile.CONTROLLED_TEST_ONLY) && !lifecycle.installThreadFactory()) {
            throw SQLException("Private persistence pool profile refused.")
        }
    }

    fun start(): PersistenceLifecycleActivation {
        if (route !== Route.ORDINARY || (!sourceOnly && launchProfile !== PersistencePoolLaunchProfile.CONTROLLED_TEST_ONLY)) {
            return PersistenceLifecycleActivation.FAILED
        }
        return owner.start()
    }

    /** Explicit infrastructure warm-up only; neither a health getter nor a request may activate deletion. */
    fun prepareDeletion(): PersistenceLifecycleObservation {
        if (launchProfile !== PersistencePoolLaunchProfile.CONTROLLED_TEST_ONLY) return PersistenceLifecycleObservation.UNAVAILABLE
        return deletionPreparation?.prepare() ?: PersistenceLifecycleObservation.UNAVAILABLE
    }

    internal fun prepareCatalogCoordinator(): PersistenceLifecycleObservation {
        if (launchProfile !== PersistencePoolLaunchProfile.CONTROLLED_TEST_ONLY || !owner.ownsCatalogDataSource(this)) {
            return PersistenceLifecycleObservation.UNAVAILABLE
        }
        return catalogPreparation?.prepare() ?: PersistenceLifecycleObservation.UNAVAILABLE
    }

    fun observePreparation(): PersistenceLifecycleObservation =
        catalogPreparation?.observation() ?: deletionPreparation?.observation() ?: owner.observeOrdinaryPreparation()

    override fun getConnection(): Connection {
        val budget = PersistenceTimeBudget.start(checkoutMillis)
        if (!businessReady()) PersistenceJdbcGuardContext.refuse()
        val acquisition = lifecycle.prepareAcquisition(budget)
        val completion = PersistencePhaseOwnership.prepareAcquisition(this, acquisition)
        var entitlement: PoolLifecycle.LeaseEntitlement? = null
        var facade: LeaseJdbcFacade? = null
        var obtained = false
        var endAttempted = false
        var delivered = false
        try {
            if (!acquisition.enter()) PersistenceJdbcGuardContext.refuse()
            val handle = pool.connection
            obtained = true
            // Capture before any unwrap, association, allocation or facade construction can fail.
            if (!acquisition.capture(handle)) {
                acquisition.failBeforeEnd()
                PersistenceJdbcGuardContext.refuse()
            }
            entitlement = acquisition.prepareLeaseEntitlement() ?: PersistenceJdbcGuardContext.refuse()
            val physical = handle.unwrap(PhysicalJdbcFacade::class.java)
            val prepared = physical.prepareLease(this, handle, entitlement, budget, completion)
            facade = prepared
            endAttempted = true
            if (!acquisition.end() || !acquisition.actualFrameEnded()) PersistenceJdbcGuardContext.refuse()
            // No borrower exposure until the actual acquisition TL/bookkeeping tail has ended.
            if (persistenceFactoryRemainingMillis(budget) == 0L) PersistenceJdbcGuardContext.refuse()
            completion.phase?.requireAcceptedLease()
            delivered = true
            return prepared
        } finally {
            try {
                if (!delivered) {
                    if (obtained || endAttempted) acquisition.failBeforeEnd()
                    cleanupUndelivered(acquisition, facade, entitlement)
                }
            } finally {
                // An ENDING failure is retained, not retried as though it were an untouched frame.
                try {
                    if (!endAttempted && acquisition.entered() && !acquisition.end()) lifecycle.requestShutdown(budget)
                } finally {
                    completion.ingressEnded()
                }
            }
        }
    }

    private fun cleanupUndelivered(acquisition: PoolLifecycle.Acquisition, facade: LeaseJdbcFacade?, entitlement: PoolLifecycle.LeaseEntitlement?) {
        try {
            facade?.deliveryFailed()
        } finally {
            try {
                entitlement?.revoke() // This connection was never returned to a borrower.
            } finally {
                acquisition.handoff() // Exact state retains the handle; no guessed raw close.
            }
        }
    }

    override fun getConnection(username: String?, password: String?): Connection {
        if (!endpoint.credentialsMatch(username, password)) PersistenceJdbcGuardContext.refuse()
        return connection
    }

    internal fun ownsPool(identity: PersistenceJdbcPoolIdentity): Boolean = identity.boundTo(lifecycle)

    internal fun ownsLifecycle(candidate: PoolLifecycle): Boolean = lifecycle === candidate

    internal val complaintContainment: PersistenceComplaintContainment get() = owner.complaintContainment

    internal fun requireOrdinaryPhaseResource() {
        if (route !== Route.ORDINARY) throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
    }

    internal fun requireDeletionPhaseResource() {
        if (route !== Route.DELETION) throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
    }

    internal fun requireCatalogCoordinatorPhaseResource() {
        if (route !== Route.CATALOG_COORDINATOR || owner.sourceOnly || !owner.ownsCatalogDataSource(this)) {
            throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
    }

    internal fun sourceOnlyComposition(): Boolean = sourceOnly && owner.sourceOnly

    /** One cold sibling on the SAME owner. No bean lookup/health/request can prepare it. */
    internal fun closedDeletionDataSource(): GuardedDataSource {
        check(sourceOnlyComposition())
        return closedDeletion
    }

    internal fun ordinaryPoolSize(): Int = pool.maximumPoolSize

    internal fun ordinaryValidationQuery(): String? = pool.connectionTestQuery

    internal fun businessReady(): Boolean = (sourceOnly || launchProfile === PersistencePoolLaunchProfile.CONTROLLED_TEST_ONLY) &&
        deletionPreparation?.prepared() != false && catalogPreparation?.prepared() != false &&
        (route !== Route.CATALOG_COORDINATOR || owner.ownsCatalogDataSource(this)) && lifecycle.businessReady()

    internal fun evictOwned(lease: PersistenceJdbcLease, handle: Connection, budget: PersistenceTimeBudget): PersistenceLeaseRetirementClaim {
        if (!lifecycle.isAuthenticPoolCaller()) PersistenceJdbcGuardContext.refuse()
        val claim = lease.claimEviction(this, handle, budget)
        if (claim !== PersistenceLeaseRetirementClaim.Claimed) return claim
        pool.evictConnection(handle) // Exact nontransferable source retirement was claimed first.
        return claim
    }

    fun requestShutdown(): PoolLifecycle.ShutdownReceipt? = lifecycle.requestShutdown()

    fun shutdownInvocation(): PoolShutdownInvocation = lifecycle.closePool()

    override fun close() {
        lifecycle.closePool() // Request/one close, not a native or actor-completion receipt.
    }

    override fun getLoginTimeout(): Int = loginPolicy.jdbcSeconds
    override fun setLoginTimeout(seconds: Int) {
        if (!loginPolicy.acceptsJdbcSeconds(seconds)) throw SQLFeatureNotSupportedException("Private persistence settings are immutable.")
    }
    override fun getLogWriter(): PrintWriter? = null
    override fun setLogWriter(out: PrintWriter?) {
        if (out != null) throw SQLFeatureNotSupportedException("Private persistence logging is immutable.")
    }
    override fun getParentLogger(): Logger = throw SQLFeatureNotSupportedException("Private persistence logger is not exposed.")
    override fun isWrapperFor(iface: Class<*>?): Boolean = iface?.isInstance(this) == true
    override fun <T : Any?> unwrap(iface: Class<T>?): T {
        if (iface?.isInstance(this) == true) return iface.cast(this)
        throw SQLException("Private persistence unwrap refused.")
    }
    override fun toString(): String = "GuardedDataSource(redacted)"

    private enum class Route { ORDINARY, DELETION, CATALOG_COORDINATOR }

    companion object {
        internal fun sourceOnly(owner: PersistenceJdbcLifecycleOwner, endpoint: ResolvedPersistenceEndpoint, settings: HikariConfig): GuardedDataSource =
            GuardedDataSource(owner, endpoint, settings.maximumPoolSize, PersistencePoolLaunchProfile.UNKNOWN, Route.ORDINARY, settings)

        internal fun deletion(
            owner: PersistenceJdbcLifecycleOwner,
            endpoint: ResolvedPersistenceEndpoint,
            launchProfile: PersistencePoolLaunchProfile = PersistencePoolLaunchProfile.UNKNOWN,
        ): GuardedDataSource = GuardedDataSource(owner, endpoint, 4, launchProfile, Route.DELETION)

        internal fun catalogCoordinator(
            owner: PersistenceJdbcLifecycleOwner,
            endpoint: ResolvedPersistenceEndpoint,
            launchProfile: PersistencePoolLaunchProfile,
        ): GuardedDataSource = GuardedDataSource(owner, endpoint, 1, launchProfile, Route.CATALOG_COORDINATOR)
    }
}

internal enum class PersistencePoolLaunchProfile { UNKNOWN, CONTROLLED_TEST_ONLY }
