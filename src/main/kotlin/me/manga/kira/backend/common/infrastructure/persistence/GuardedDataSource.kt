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
 * The fixed operator owner's coordinator is a separate explicit route, likewise not immutable-image qualification.
 */
internal class GuardedDataSource private constructor(
    private val owner: PersistenceJdbcLifecycleOwner,
    private val endpoint: ResolvedPersistenceEndpoint,
    maximumPoolSize: Int,
    private val launchProfile: PersistencePoolLaunchProfile,
    private val route: Route,
    ordinarySettings: HikariConfig? = null,
    versionBound: VersionBoundPersistencePoolBinding? = null,
) : DataSource,
    AutoCloseable {
    constructor(
        owner: PersistenceJdbcLifecycleOwner,
        endpoint: ResolvedPersistenceEndpoint,
        maximumPoolSize: Int,
        launchProfile: PersistencePoolLaunchProfile = PersistencePoolLaunchProfile.UNKNOWN,
    ) : this(owner, endpoint, maximumPoolSize, launchProfile, Route.ORDINARY)

    private val sourceOnly = ordinarySettings != null
    private val namedCatalogOnly: Boolean
        get() = owner.desiredInstallationOperator || owner.catalogGenesisAuthoring || owner.catalogGenesisFinalization
    private val pool = if (versionBound == null) {
        if (owner.versionBoundPools != null) rejectPersistenceBoundary(PersistenceBoundaryFailureCode.JDBC_CONFIGURATION_FAILED)
        HikariDataSource()
    } else {
        versionBound.newPool(owner, endpoint, maximumPoolSize, route.participantRole(), ordinarySettings)
    }
    private val lifecycle = versionBound?.let { PoolLifecycle.versionBound(pool, owner, it) } ?: when (route) {
        Route.ORDINARY -> if (sourceOnly) PoolLifecycle.sourceOnly(pool, owner) else PoolLifecycle(pool, owner)
        Route.DELETION -> PoolLifecycle.deletion(pool, owner)
        Route.CATALOG_COORDINATOR -> PoolLifecycle.catalogCoordinator(pool, owner)
    }

    init {
        versionBound?.retain(this, lifecycle) // Before lower-source/preparation allocation, validation or factory installation can fail.
    }

    private val lower = when (route) {
        Route.ORDINARY -> PrivateJdbcDataSource(owner, endpoint, lifecycle)
        Route.DELETION -> PrivateJdbcDataSource.deletion(owner, endpoint, lifecycle)
        Route.CATALOG_COORDINATOR -> PrivateJdbcDataSource.catalogCoordinator(owner, endpoint, lifecycle)
    }
    private val deletionPreparation = if (route === Route.DELETION) DeletionPoolPreparation(owner, pool, lifecycle) else null
    private val catalogPreparation = if (route === Route.CATALOG_COORDINATOR) CatalogCoordinatorPoolPreparation(owner, pool, lifecycle) else null
    private val loginPolicy = versionBound?.material?.loginPolicy ?: when (route) {
        Route.ORDINARY -> endpoint.loginPolicy
        Route.DELETION -> PersistenceNativeSettings.deletionLoginPolicy
        Route.CATALOG_COORDINATOR -> PersistenceNativeSettings.catalogCoordinatorLoginPolicy
    }
    private val checkoutMillis = versionBound?.material?.checkoutMillis ?: when {
        route === Route.DELETION -> 500L
        route === Route.CATALOG_COORDINATOR -> 250L
        ordinarySettings != null -> ordinarySettings.connectionTimeout
        else -> endpoint.loginPolicy.durationMillis
    }
    private val closedDeletion by lazy { deletion(owner, endpoint) }

    init {
        require(maximumPoolSize > 0)
        if (versionBound != null) {
            versionBound.configure(lower)
        } else if (ordinarySettings != null) {
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
        val requiresThreadFactory = versionBound != null || sourceOnly || launchProfile === PersistencePoolLaunchProfile.CONTROLLED_TEST_ONLY
        if (requiresThreadFactory && !lifecycle.installThreadFactory()) {
            throw SQLException("Private persistence pool profile refused.")
        }
    }

    fun start(): PersistenceLifecycleActivation {
        if (namedCatalogOnly || route !== Route.ORDINARY) {
            return PersistenceLifecycleActivation.FAILED
        }
        if (!sourceOnly && launchProfile !== PersistencePoolLaunchProfile.CONTROLLED_TEST_ONLY) {
            return PersistenceLifecycleActivation.FAILED
        }
        return owner.start()
    }

    /** Explicit infrastructure warm-up only; neither a health getter nor a request may activate deletion. */
    fun prepareDeletion(): PersistenceLifecycleObservation {
        if (namedCatalogOnly || launchProfile !== PersistencePoolLaunchProfile.CONTROLLED_TEST_ONLY) {
            return PersistenceLifecycleObservation.UNAVAILABLE
        }
        return deletionPreparation?.prepare() ?: PersistenceLifecycleObservation.UNAVAILABLE
    }

    internal fun prepareCatalogCoordinator(): PersistenceLifecycleObservation {
        if (!launchRoutePermitsBusiness() || !owner.ownsCatalogDataSource(this)) {
            return PersistenceLifecycleObservation.UNAVAILABLE
        }
        return catalogPreparation?.prepare() ?: PersistenceLifecycleObservation.UNAVAILABLE
    }

    fun observePreparation(): PersistenceLifecycleObservation =
        catalogPreparation?.observation() ?: deletionPreparation?.observation() ?: owner.observeOrdinaryPreparation()

    override fun getConnection(): Connection {
        // Stock pool preparation uses its retained raw initialization ticket, not this business facade.
        // An operator connection has no unscoped caller route around the three fixed phase operations.
        if (namedCatalogOnly && PersistencePhaseOwnership.current() == null) {
            PersistenceJdbcGuardContext.refuse()
        }
        val budget = PersistencePhaseOwnership.current()?.retainedPhaseCheckoutBudget(checkoutMillis) ?: PersistenceTimeBudget.start(checkoutMillis)
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
        if (namedCatalogOnly || route !== Route.ORDINARY) {
            throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
    }

    internal fun requireDeletionPhaseResource() {
        if (namedCatalogOnly || route !== Route.DELETION) {
            throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
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

    internal fun businessReady(): Boolean = launchRoutePermitsBusiness() &&
        deletionPreparation?.prepared() != false && catalogPreparation?.prepared() != false &&
        (route !== Route.CATALOG_COORDINATOR || owner.ownsCatalogDataSource(this)) && lifecycle.businessReady()

    private fun launchRoutePermitsBusiness(): Boolean = if (namedCatalogOnly) {
        route === Route.CATALOG_COORDINATOR && launchProfile === PersistencePoolLaunchProfile.UNKNOWN && owner.ownsCatalogDataSource(this)
    } else {
        sourceOnly || launchProfile === PersistencePoolLaunchProfile.CONTROLLED_TEST_ONLY
    }

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

    private enum class Route {
        ORDINARY,
        DELETION,
        CATALOG_COORDINATOR,
        ;

        fun participantRole(): PersistenceJdbcParticipantRole = when (this) {
            ORDINARY -> PersistenceJdbcParticipantRole.ORDINARY
            DELETION -> PersistenceJdbcParticipantRole.DELETION
            CATALOG_COORDINATOR -> PersistenceJdbcParticipantRole.CATALOG_COORDINATOR
        }
    }

    companion object {
        internal fun versionBound(
            owner: PersistenceJdbcLifecycleOwner,
            endpoint: ResolvedPersistenceEndpoint,
            capacity: Int,
            role: PersistenceJdbcParticipantRole,
            binding: VersionBoundPersistencePoolBinding,
            launchProfile: PersistencePoolLaunchProfile,
        ): GuardedDataSource {
            val route = when (role) {
                PersistenceJdbcParticipantRole.ORDINARY -> Route.ORDINARY
                PersistenceJdbcParticipantRole.DELETION -> Route.DELETION
                PersistenceJdbcParticipantRole.CATALOG_COORDINATOR -> Route.CATALOG_COORDINATOR
                PersistenceJdbcParticipantRole.EPOCH_ROTATION -> error("Epoch rotation is not a DataSource.")
            }
            return GuardedDataSource(owner, endpoint, capacity, launchProfile, route, versionBound = binding)
        }

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
